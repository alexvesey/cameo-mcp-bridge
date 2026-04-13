"""Semantic auto-remediation helpers for the MCP layer.

This module turns the existing semantic validation findings into previewable
remediation receipts and patch plans. It never mutates the live model; it only
reads the bridge state and proposes structured follow-up actions for a later
apply/preview workflow.
"""

from __future__ import annotations

import asyncio
import re
from collections import defaultdict
from typing import Any, Mapping, Sequence

from cameo_mcp import client as default_bridge_client
from cameo_mcp import semantic_validation, verification


def _normalized_text(value: Any) -> str:
    return " ".join(str(value or "").split())


def _normalized_key(value: Any) -> str:
    return _normalized_text(value).casefold()


def _tokenize(value: Any) -> set[str]:
    return {
        token
        for token in re.findall(r"[a-z0-9]+", _normalized_key(value))
        if token
    }


def _slugify(value: Any) -> str:
    slug = re.sub(r"[^a-z0-9]+", "-", _normalized_key(value)).strip("-")
    return slug or "item"


def _unique_values(values: Sequence[str]) -> list[str]:
    seen: set[str] = set()
    ordered: list[str] = []
    for value in values:
        text = _normalized_text(value)
        if not text or text in seen:
            continue
        seen.add(text)
        ordered.append(text)
    return ordered


def _term_similarity(left: str, right: str) -> tuple[int, int, int]:
    left_tokens = _tokenize(left)
    right_tokens = _tokenize(right)
    overlap = len(left_tokens & right_tokens)
    shared_prefix = int(bool(left_tokens) and bool(right_tokens) and (
        next(iter(left_tokens)) == next(iter(right_tokens))
    ))
    combined = len(left_tokens | right_tokens)
    return overlap, shared_prefix, -combined


def _best_term_match(term: str, candidates: Sequence[str]) -> str | None:
    scored = [
        (candidate, _term_similarity(term, candidate))
        for candidate in candidates
        if _normalized_text(candidate)
    ]
    if not scored:
        return None
    scored.sort(key=lambda item: item[1], reverse=True)
    candidate, score = scored[0]
    return candidate if score[0] > 0 or score[1] > 0 else candidate


def _element_lookup(elements: Sequence[Mapping[str, Any]]) -> dict[str, dict[str, Any]]:
    lookup: dict[str, dict[str, Any]] = {}
    for element in elements:
        element_id = str(element.get("id") or element.get("elementId") or "")
        if element_id:
            lookup[element_id] = dict(element)
    return lookup


def _relationship_term_names(relationships: Sequence[Mapping[str, Any]]) -> list[str]:
    terms: list[str] = []
    for relationship in relationships:
        name = _normalized_text(relationship.get("name") or "")
        if name:
            terms.append(name)
        item_property = relationship.get("itemProperty")
        if isinstance(item_property, Mapping):
            item_name = _normalized_text(item_property.get("name") or "")
            if item_name:
                terms.append(item_name)
        for conveyed in relationship.get("conveyed") or ():
            if isinstance(conveyed, Mapping):
                conveyed_name = _normalized_text(conveyed.get("name") or "")
                if conveyed_name:
                    terms.append(conveyed_name)
    return _unique_values(terms)


def _target_name(payload: Mapping[str, Any]) -> str:
    return _normalized_text(
        payload.get("name")
        or payload.get("elementName")
        or payload.get("humanType")
        or payload.get("type")
        or payload.get("id")
        or payload.get("elementId")
        or ""
    )


def _make_receipt_id(prefix: str, *parts: Any) -> str:
    suffix = "-".join(_slugify(part) for part in parts if _normalized_text(part))
    return f"{prefix}-{suffix}" if suffix else prefix


def _preview(before: Mapping[str, Any], after: Mapping[str, Any], *, note: str | None = None) -> dict[str, Any]:
    preview = {"before": dict(before), "after": dict(after)}
    if note:
        preview["note"] = note
    return preview


async def _gather_diagram_snapshot(diagram_id: str, bridge: Any) -> dict[str, Any]:
    diagram_shapes = await bridge.list_diagram_shapes(diagram_id)
    shapes = [
        shape
        for shape in (diagram_shapes.get("shapes") or ())
        if isinstance(shape, Mapping)
    ]
    element_ids = sorted(
        {
            str(shape.get("elementId"))
            for shape in shapes
            if shape.get("elementId")
        }
    )

    elements = await asyncio.gather(*(bridge.get_element(element_id) for element_id in element_ids)) if element_ids else []
    relationship_responses = await asyncio.gather(
        *(bridge.get_relationships(element_id) for element_id in element_ids)
    ) if element_ids else []
    relationships: list[dict[str, Any]] = []
    for response in relationship_responses:
        if not isinstance(response, Mapping):
            continue
        for bucket in ("outgoing", "incoming", "undirected"):
            for relationship in response.get(bucket) or ():
                if isinstance(relationship, Mapping):
                    relationships.append(dict(relationship))

    return {
        "diagramId": diagram_id,
        "diagramShapes": diagram_shapes,
        "elements": list(elements),
        "relationships": relationships,
    }


def _related_requirement_architecture_ids(
    requirement_id: str,
    response: Mapping[str, Any],
) -> list[str]:
    related_ids: set[str] = set()
    for bucket in ("outgoing", "incoming"):
        for relationship in response.get(bucket) or ():
            if not isinstance(relationship, Mapping):
                continue
            for endpoint_key in ("sources", "targets"):
                for endpoint in relationship.get(endpoint_key) or ():
                    if not isinstance(endpoint, Mapping):
                        continue
                    endpoint_id = endpoint.get("id")
                    if endpoint_id and str(endpoint_id) != requirement_id:
                        related_ids.add(str(endpoint_id))
    for relationship in response.get("undirected") or ():
        if not isinstance(relationship, Mapping):
            continue
        for endpoint in relationship.get("relatedElements") or ():
            if not isinstance(endpoint, Mapping):
                continue
            endpoint_id = endpoint.get("id")
            if endpoint_id and str(endpoint_id) != requirement_id:
                related_ids.add(str(endpoint_id))
    return sorted(related_ids)


def _build_activity_receipts(
    activity_validation: Mapping[str, Any] | None,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    receipts: list[dict[str, Any]] = []
    steps: list[dict[str, Any]] = []
    if not activity_validation:
        return receipts, steps

    metrics = activity_validation.get("metrics") or {}
    elements = _element_lookup(
        item for item in (activity_validation.get("elements") or ()) if isinstance(item, Mapping)
    )
    initial_node_ids = [str(item) for item in metrics.get("initialNodeIds") or ()]
    action_terms = [
        _normalized_text(element.get("name") or element.get("elementName") or element.get("id") or "")
        for element in elements.values()
        if "action" in _normalized_key(element.get("type") or element.get("humanType") or element.get("elementType"))
    ]
    anchor_ids = _unique_values(initial_node_ids)

    for action_id in _unique_values(metrics.get("isolatedActionIds") or ()):
        element = elements.get(action_id, {"id": action_id})
        element_name = _target_name(element)
        receipt_id = _make_receipt_id("activity", "isolated", element_name or action_id)
        anchors = [
            {
                "elementId": anchor_id,
                "name": _target_name(elements.get(anchor_id, {"id": anchor_id})),
            }
            for anchor_id in anchor_ids
        ]
        if not anchors and action_terms:
            anchors = [{"name": action_terms[0]}]
        preview = _preview(
            {"action": element_name or action_id, "state": "isolated"},
            {
                "action": element_name or action_id,
                "state": "connected",
                "candidateAnchors": anchors,
            },
            note="Add a control-flow path into or out of the action before applying.",
        )
        receipt = {
            "receiptId": receipt_id,
            "status": "preview",
            "category": "activity-flow",
            "title": f"Connect isolated action '{element_name or action_id}'",
            "reason": "The activity graph reports an isolated action with no control-flow path.",
            "target": {
                "diagramId": activity_validation.get("diagramId"),
                "elementId": action_id,
                "elementName": element_name,
            },
            "preview": preview,
            "applyHint": {
                "operation": "add_control_flow",
                "preferredAnchors": anchors,
            },
            "evidence": {
                "isolatedActionIds": [action_id],
                "activityTerms": list(activity_validation.get("metrics", {}).get("actionIds") or []),
            },
        }
        receipts.append(receipt)
        steps.append(
            {
                "stepId": receipt_id,
                "kind": "add_control_flow",
                "target": receipt["target"],
                "preview": preview,
                "applyHint": receipt["applyHint"],
                "confidence": 0.78,
                "receiptId": receipt_id,
            }
        )

    for action_id in _unique_values(metrics.get("unreachableActionIds") or ()):
        if any(step["target"]["elementId"] == action_id for step in steps if step.get("target")):
            continue
        element = elements.get(action_id, {"id": action_id})
        element_name = _target_name(element)
        receipt_id = _make_receipt_id("activity", "unreachable", element_name or action_id)
        preview = _preview(
            {"action": element_name or action_id, "state": "unreachable"},
            {
                "action": element_name or action_id,
                "state": "reachable",
                "candidateFix": "connect this action to the existing flow or add an entry path",
            },
            note="The action is not reachable from the current initial nodes.",
        )
        receipt = {
            "receiptId": receipt_id,
            "status": "preview",
            "category": "activity-flow",
            "title": f"Make action '{element_name or action_id}' reachable",
            "reason": "The activity graph reports an unreachable action.",
            "target": {
                "diagramId": activity_validation.get("diagramId"),
                "elementId": action_id,
                "elementName": element_name,
            },
            "preview": preview,
            "applyHint": {
                "operation": "rewire_activity_flow",
                "suggestion": "Connect the action to an initial path or remove the dead branch.",
            },
            "evidence": {"unreachableActionIds": [action_id]},
        }
        receipts.append(receipt)
        steps.append(
            {
                "stepId": receipt_id,
                "kind": "rewire_activity_flow",
                "target": receipt["target"],
                "preview": preview,
                "applyHint": receipt["applyHint"],
                "confidence": 0.71,
                "receiptId": receipt_id,
            }
        )

    return receipts, steps


def _build_port_receipts(
    port_validation: Mapping[str, Any] | None,
    trace_validation: Mapping[str, Any] | None,
    activity_terms: Sequence[str],
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    receipts: list[dict[str, Any]] = []
    steps: list[dict[str, Any]] = []
    if not port_validation:
        return receipts, steps

    metrics = port_validation.get("metrics") or {}
    interface_blocks = [
        item for item in (port_validation.get("interfaceBlocks") or ())
        if isinstance(item, Mapping)
    ]
    flow_properties = [
        item for item in (port_validation.get("flowProperties") or ())
        if isinstance(item, Mapping)
    ]
    flow_by_name: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for flow_property in flow_properties:
        flow_by_name[_normalized_key(flow_property.get("name") or "")].append(dict(flow_property))

    for name, owners in (metrics.get("duplicateFlowProperties") or {}).items():
        owner_names = [
            _target_name(next((block for block in interface_blocks if str(block.get("id") or "") == str(owner_id)), {"id": owner_id}))
            for owner_id in owners
        ]
        receipt_id = _make_receipt_id("port", "duplicate", name)
        receipt = {
            "receiptId": receipt_id,
            "status": "preview",
            "category": "port-boundary",
            "title": f"Normalize duplicate flow property '{name}'",
            "reason": "The same flow-property name is defined on multiple interface blocks.",
            "target": {
                "flowPropertyName": name,
                "ownerIds": list(owners),
                "ownerNames": owner_names,
            },
            "preview": _preview(
                {"name": name, "owners": owner_names},
                {
                    "name": name,
                    "owners": owner_names,
                    "candidateFixes": [
                        "keep the shared name and explicitly whitelist it",
                        "rename the interface-side flow property to a distinct term",
                    ],
                },
                note="Preview-only. Pick one policy before applying a rename or allow-list update.",
            ),
            "applyHint": {
                "operation": "normalize_shared_flow_property_name",
                "candidatePolicy": "allow-list or rename",
            },
            "evidence": {"duplicateFlowProperties": {name: owner_names}},
        }
        receipts.append(receipt)
        steps.append(
            {
                "stepId": receipt_id,
                "kind": "normalize_shared_flow_property_name",
                "target": receipt["target"],
                "preview": receipt["preview"],
                "applyHint": receipt["applyHint"],
                "confidence": 0.82,
                "receiptId": receipt_id,
            }
        )

    for scope_name, conflicts in (metrics.get("directionConflicts") or {}).items():
        for name, directions in conflicts.items():
            receipt_id = _make_receipt_id("port", "direction", scope_name or "scope", name)
            receipt = {
                "receiptId": receipt_id,
                "status": "preview",
                "category": "port-boundary",
                "title": f"Normalize direction for '{name}' in '{scope_name or 'unnamed scope'}'",
                "reason": "The same flow property is exposed with conflicting directions in one scope.",
                "target": {
                    "scopeName": scope_name,
                    "flowPropertyName": name,
                    "directions": list(directions),
                },
                "preview": _preview(
                    {"scope": scope_name, "name": name, "directions": list(directions)},
                    {
                        "scope": scope_name,
                        "name": name,
                        "directions": [directions[0]],
                        "candidateFix": "keep one direction per scope and align the opposite side",
                    },
                    note="Preview-only. Standardize the port-side direction before applying.",
                ),
                "applyHint": {
                    "operation": "normalize_flow_property_direction",
                    "candidateDirections": list(directions),
                },
                "evidence": {"directionConflicts": {scope_name: {name: list(directions)}}},
            }
            receipts.append(receipt)
            steps.append(
                {
                    "stepId": receipt_id,
                    "kind": "normalize_flow_property_direction",
                    "target": receipt["target"],
                    "preview": receipt["preview"],
                    "applyHint": receipt["applyHint"],
                    "confidence": 0.8,
                    "receiptId": receipt_id,
                }
            )

    for flow_property_id in _unique_values(metrics.get("unnamedPropertyIds") or ()):
        receipt_id = _make_receipt_id("port", "unnamed", flow_property_id)
        receipt = {
            "receiptId": receipt_id,
            "status": "preview",
            "category": "port-boundary",
            "title": f"Name the flow property '{flow_property_id}'",
            "reason": "The flow-property list contains an unnamed entry.",
            "target": {"flowPropertyId": flow_property_id},
            "preview": _preview(
                {"flowPropertyId": flow_property_id, "name": ""},
                {"flowPropertyId": flow_property_id, "name": "Suggested Name"},
                note="Choose a domain-accurate label before applying.",
            ),
            "applyHint": {
                "operation": "rename_flow_property",
                "suggestion": "Give the property a meaningful name that matches the activity wording.",
            },
            "evidence": {"unnamedPropertyIds": [flow_property_id]},
        }
        receipts.append(receipt)
        steps.append(
            {
                "stepId": receipt_id,
                "kind": "rename_flow_property",
                "target": receipt["target"],
                "preview": receipt["preview"],
                "applyHint": receipt["applyHint"],
                "confidence": 0.66,
                "receiptId": receipt_id,
            }
        )

    trace_port_terms = list(trace_validation.get("portTerms") or []) if trace_validation else []
    for missing_term in _unique_values((trace_validation or {}).get("metrics", {}).get("missingPortTerms") or ()):
        best_activity_term = _best_term_match(missing_term, activity_terms)
        receipt_id = _make_receipt_id("trace", "port", missing_term)
        receipt = {
            "receiptId": receipt_id,
            "status": "preview",
            "category": "cross-diagram-traceability",
            "title": f"Align port trace term '{missing_term}'",
            "reason": "The activity term is not represented by the current port-side wording.",
            "target": {
                "diagramSide": "ports",
                "missingTerm": missing_term,
            },
            "preview": _preview(
                {"missingTerm": missing_term, "currentPortTerms": trace_port_terms},
                {
                    "missingTerm": missing_term,
                    "suggestedPortTerm": best_activity_term or missing_term,
                    "candidateFixes": [
                        "rename a flow property to the matching activity term",
                        "if the name is already correct, mark the term as intentionally shared",
                    ],
                },
                note="Preview-only. This is a term-alignment suggestion, not a live rename.",
            ),
            "applyHint": {
                "operation": "align_port_term",
                "preferredTerm": best_activity_term or missing_term,
            },
            "evidence": {
                "missingPortTerms": [missing_term],
                "activityTerms": list(activity_terms),
                "portTerms": trace_port_terms,
            },
        }
        receipts.append(receipt)
        steps.append(
            {
                "stepId": receipt_id,
                "kind": "align_port_term",
                "target": receipt["target"],
                "preview": receipt["preview"],
                "applyHint": receipt["applyHint"],
                "confidence": 0.74 if best_activity_term else 0.58,
                "receiptId": receipt_id,
            }
        )

    return receipts, steps


def _build_requirement_receipts(
    requirement_validation: Mapping[str, Any] | None,
    trace_validation: Mapping[str, Any] | None,
    architecture_elements: Sequence[Mapping[str, Any]] | None,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    receipts: list[dict[str, Any]] = []
    steps: list[dict[str, Any]] = []
    if not requirement_validation:
        return receipts, steps

    metrics = requirement_validation.get("metrics") or {}
    requirements = [
        item for item in (requirement_validation.get("requirements") or ())
        if isinstance(item, Mapping)
    ]
    requirements_by_id = {
        str(requirement.get("id") or requirement.get("elementId") or ""): dict(requirement)
        for requirement in requirements
        if requirement.get("id") or requirement.get("elementId")
    }
    architecture_list = [
        item for item in (architecture_elements or ())
        if isinstance(item, Mapping)
    ]
    architecture_candidates = [
        {
            "elementId": str(element.get("id") or element.get("elementId") or ""),
            "name": _target_name(element),
            "type": _normalized_text(element.get("type") or element.get("humanType") or element.get("elementType") or ""),
        }
        for element in architecture_list
        if str(element.get("id") or element.get("elementId") or "")
    ]

    for requirement_id in _unique_values(metrics.get("missingIdIds") or ()):
        requirement = requirements_by_id.get(requirement_id, {"id": requirement_id})
        candidate_id = f"REQ-{_slugify(_target_name(requirement) or requirement_id).upper()}"
        receipt_id = _make_receipt_id("requirement", "missing-id", requirement_id)
        receipt = {
            "receiptId": receipt_id,
            "status": "preview",
            "category": "requirement-quality",
            "title": f"Assign requirement id to '{_target_name(requirement) or requirement_id}'",
            "reason": "The requirement is missing a stable identifier.",
            "target": {
                "elementId": requirement_id,
                "name": _target_name(requirement),
            },
            "preview": _preview(
                {"elementId": requirement_id, "requirementId": ""},
                {"elementId": requirement_id, "requirementId": candidate_id},
                note="Preview-only. Verify the identifier convention before applying.",
            ),
            "applyHint": {
                "operation": "set_requirement_id",
                "candidateRequirementId": candidate_id,
            },
            "evidence": {"missingIdIds": [requirement_id]},
        }
        receipts.append(receipt)
        steps.append(
            {
                "stepId": receipt_id,
                "kind": "set_requirement_id",
                "target": receipt["target"],
                "preview": receipt["preview"],
                "applyHint": receipt["applyHint"],
                "confidence": 0.67,
                "receiptId": receipt_id,
            }
        )

    for requirement_id in _unique_values(metrics.get("blankTextIds") or ()):
        requirement = requirements_by_id.get(requirement_id, {"id": requirement_id})
        requirement_name = _target_name(requirement)
        receipt_id = _make_receipt_id("requirement", "blank-text", requirement_id)
        receipt = {
            "receiptId": receipt_id,
            "status": "preview",
            "category": "requirement-quality",
            "title": f"Add requirement text for '{requirement_name or requirement_id}'",
            "reason": "The requirement text is blank.",
            "target": {
                "elementId": requirement_id,
                "name": requirement_name,
            },
            "preview": _preview(
                {"elementId": requirement_id, "text": ""},
                {
                    "elementId": requirement_id,
                    "textTemplate": "The system shall <observable behavior> within <measurable threshold>.",
                },
                note="Preview-only. Replace the template with domain-specific language before applying.",
            ),
            "applyHint": {
                "operation": "rewrite_requirement_text",
                "suggestion": "State the observable behavior and a measurable constraint.",
            },
            "evidence": {"blankTextIds": [requirement_id]},
        }
        receipts.append(receipt)
        steps.append(
            {
                "stepId": receipt_id,
                "kind": "rewrite_requirement_text",
                "target": receipt["target"],
                "preview": receipt["preview"],
                "applyHint": receipt["applyHint"],
                "confidence": 0.63,
                "receiptId": receipt_id,
            }
        )

    for requirement_id in _unique_values(metrics.get("weakTextIds") or ()):
        requirement = requirements_by_id.get(requirement_id, {"id": requirement_id})
        requirement_name = _target_name(requirement)
        receipt_id = _make_receipt_id("requirement", "weak-text", requirement_id)
        receipt = {
            "receiptId": receipt_id,
            "status": "preview",
            "category": "requirement-quality",
            "title": f"Strengthen requirement text for '{requirement_name or requirement_id}'",
            "reason": "The requirement text is not sufficiently measurable.",
            "target": {
                "elementId": requirement_id,
                "name": requirement_name,
            },
            "preview": _preview(
                {"elementId": requirement_id, "textState": "weak"},
                {
                    "elementId": requirement_id,
                    "textTemplate": "The system shall <verb> <object> within <specific threshold>.",
                },
                note="Preview-only. Use a measurable threshold and an observable outcome.",
            ),
            "applyHint": {
                "operation": "rewrite_requirement_text",
                "suggestion": "Add a measurable threshold and an observable outcome.",
            },
            "evidence": {"weakTextIds": [requirement_id]},
        }
        receipts.append(receipt)
        steps.append(
            {
                "stepId": receipt_id,
                "kind": "rewrite_requirement_text",
                "target": receipt["target"],
                "preview": receipt["preview"],
                "applyHint": receipt["applyHint"],
                "confidence": 0.65,
                "receiptId": receipt_id,
            }
        )

    missing_requirement_ids = _unique_values((trace_validation or {}).get("metrics", {}).get("missingRequirementTraceIds") or ())
    for requirement_id in missing_requirement_ids:
        requirement = requirements_by_id.get(requirement_id, {"id": requirement_id})
        requirement_name = _target_name(requirement)
        candidate = None
        if architecture_candidates:
            candidate_names = [candidate_item["name"] for candidate_item in architecture_candidates]
            best_name = _best_term_match(requirement_name or requirement_id, candidate_names)
            candidate = next((item for item in architecture_candidates if item["name"] == best_name), architecture_candidates[0])
        receipt_id = _make_receipt_id("trace", "requirement", requirement_id)
        receipt = {
            "receiptId": receipt_id,
            "status": "preview",
            "category": "cross-diagram-traceability",
            "title": f"Trace requirement '{requirement_name or requirement_id}' to architecture",
            "reason": "The requirement is not linked to any known architecture element.",
            "target": {
                "elementId": requirement_id,
                "name": requirement_name,
            },
            "preview": _preview(
                {"requirementId": requirement_id, "traceTargets": []},
                {
                    "requirementId": requirement_id,
                    "traceTargets": [candidate] if candidate else architecture_candidates[:3],
                },
                note="Preview-only. Choose the final trace target before applying a relationship.",
            ),
            "applyHint": {
                "operation": "add_trace_relationship",
                "candidateTargets": [candidate] if candidate else architecture_candidates[:3],
            },
            "evidence": {
                "missingRequirementTraceIds": [requirement_id],
                "architectureElementIds": [item["elementId"] for item in architecture_candidates],
            },
        }
        receipts.append(receipt)
        steps.append(
            {
                "stepId": receipt_id,
                "kind": "add_trace_relationship",
                "target": receipt["target"],
                "preview": receipt["preview"],
                "applyHint": receipt["applyHint"],
                "confidence": 0.72 if candidate else 0.52,
                "receiptId": receipt_id,
            }
        )

    return receipts, steps


def _build_ibd_receipts(
    trace_validation: Mapping[str, Any] | None,
    activity_terms: Sequence[str],
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    receipts: list[dict[str, Any]] = []
    steps: list[dict[str, Any]] = []
    if not trace_validation:
        return receipts, steps

    ibd_terms = list(trace_validation.get("ibdTerms") or [])
    for missing_term in _unique_values((trace_validation.get("metrics") or {}).get("missingIbdTerms") or ()):
        best_activity_term = _best_term_match(missing_term, activity_terms)
        receipt_id = _make_receipt_id("trace", "ibd", missing_term)
        receipt = {
            "receiptId": receipt_id,
            "status": "preview",
            "category": "cross-diagram-traceability",
            "title": f"Align IBD trace term '{missing_term}'",
            "reason": "The activity term is not represented by the current IBD wording.",
            "target": {
                "diagramSide": "ibd",
                "missingTerm": missing_term,
            },
            "preview": _preview(
                {"missingTerm": missing_term, "currentIbdTerms": ibd_terms},
                {
                    "missingTerm": missing_term,
                    "suggestedIbdTerm": best_activity_term or missing_term,
                    "candidateFixes": [
                        "rename the item-flow label to the matching activity term",
                        "if the label is deliberate, mark it as intentionally different",
                    ],
                },
                note="Preview-only. This is a term-alignment suggestion, not a live rename.",
            ),
            "applyHint": {
                "operation": "align_ibd_term",
                "preferredTerm": best_activity_term or missing_term,
            },
            "evidence": {
                "missingIbdTerms": [missing_term],
                "activityTerms": list(activity_terms),
                "ibdTerms": ibd_terms,
            },
        }
        receipts.append(receipt)
        steps.append(
            {
                "stepId": receipt_id,
                "kind": "align_ibd_term",
                "target": receipt["target"],
                "preview": receipt["preview"],
                "applyHint": receipt["applyHint"],
                "confidence": 0.74 if best_activity_term else 0.58,
                "receiptId": receipt_id,
            }
        )

    return receipts, steps


def build_cross_diagram_remediation_plan(
    *,
    activity_validation: Mapping[str, Any] | None = None,
    port_validation: Mapping[str, Any] | None = None,
    requirement_validation: Mapping[str, Any] | None = None,
    trace_validation: Mapping[str, Any] | None = None,
    architecture_elements: Sequence[Mapping[str, Any]] | None = None,
) -> dict[str, Any]:
    activity_validation = dict(activity_validation or {})
    port_validation = dict(port_validation or {})
    requirement_validation = dict(requirement_validation or {})
    trace_validation = dict(trace_validation or {})

    activity_terms = list(trace_validation.get("activityTerms") or [])
    port_terms = list(trace_validation.get("portTerms") or [])
    ibd_terms = list(trace_validation.get("ibdTerms") or [])

    receipts: list[dict[str, Any]] = []
    steps: list[dict[str, Any]] = []

    activity_receipts, activity_steps = _build_activity_receipts(activity_validation)
    port_receipts, port_steps = _build_port_receipts(port_validation, trace_validation, activity_terms)
    requirement_receipts, requirement_steps = _build_requirement_receipts(
        requirement_validation,
        trace_validation,
        architecture_elements,
    )
    ibd_receipts, ibd_steps = _build_ibd_receipts(trace_validation, activity_terms)

    for bucket in (
        activity_receipts,
        port_receipts,
        requirement_receipts,
        ibd_receipts,
    ):
        receipts.extend(bucket)
    for bucket in (
        activity_steps,
        port_steps,
        requirement_steps,
        ibd_steps,
    ):
        steps.extend(bucket)

    checks: list[dict[str, Any]] = []
    for validation in (activity_validation, port_validation, requirement_validation, trace_validation):
        checks.extend(validation.get("checks") or [])

    summary = {
        "issueCount": len(receipts),
        "receiptCount": len(receipts),
        "stepCount": len(steps),
        "activityIssueCount": len(activity_receipts),
        "portIssueCount": len(port_receipts),
        "requirementIssueCount": len(requirement_receipts),
        "ibdIssueCount": len(ibd_receipts),
        "activityTermCount": len(activity_terms),
        "portTermCount": len(port_terms),
        "ibdTermCount": len(ibd_terms),
        "missingRequirementTraceCount": len(trace_validation.get("metrics", {}).get("missingRequirementTraceIds") or []),
    }

    patch_plan = {
        "planId": "cross-diagram-auto-remediation",
        "mode": "preview",
        "summary": summary,
        "steps": steps,
    }

    return {
        "ok": not receipts and all(check.get("ok", False) for check in checks) if checks else not receipts,
        "checks": checks,
        "summary": summary,
        "findings": {
            "activity": activity_validation,
            "ports": port_validation,
            "requirements": requirement_validation,
            "traceability": trace_validation,
        },
        "receipts": receipts,
        "patchPlan": patch_plan,
        "architectureElements": [dict(item) for item in (architecture_elements or ()) if isinstance(item, Mapping)],
    }


async def detect_cross_diagram_inconsistencies_for_artifacts(
    *,
    activity_diagram_id: str | None = None,
    interface_block_ids: Sequence[str] | None = None,
    ibd_diagram_id: str | None = None,
    requirement_ids: Sequence[str] | None = None,
    architecture_element_ids: Sequence[str] | None = None,
    allow_shared_flow_property_names: Sequence[str] | None = None,
    require_id: bool = True,
    require_measurement: bool = True,
    min_requirement_text_length: int = 20,
    max_partition_depth: int = 1,
    allow_stereotype_partition_labels: bool = False,
    bridge: Any = default_bridge_client,
) -> dict[str, Any]:
    activity_validation: dict[str, Any] = {}
    port_validation: dict[str, Any] = {}
    requirement_validation: dict[str, Any] = {}
    trace_validation: dict[str, Any] = {}

    if activity_diagram_id:
        activity_validation = await semantic_validation.verify_activity_flow_semantics_for_diagram(
            activity_diagram_id,
            max_partition_depth=max_partition_depth,
            allow_stereotype_partition_labels=allow_stereotype_partition_labels,
            bridge=bridge,
        )
    if interface_block_ids:
        port_validation = await semantic_validation.verify_port_boundary_consistency_for_interfaces(
            interface_block_ids,
            allow_shared_flow_property_names=allow_shared_flow_property_names,
            bridge=bridge,
        )
    if requirement_ids:
        requirement_validation = await semantic_validation.verify_requirement_quality_for_ids(
            requirement_ids,
            require_id=require_id,
            require_measurement=require_measurement,
            min_text_length=min_requirement_text_length,
            bridge=bridge,
        )
    activity_terms: list[str] = []
    if activity_validation:
        activity_terms = verification.extract_activity_trace_terms(
            activity_validation.get("elements") or (),
            activity_validation.get("relationships") or (),
        )

    ibd_terms: list[str] = []
    if ibd_diagram_id:
        ibd_snapshot = await _gather_diagram_snapshot(ibd_diagram_id, bridge)
        ibd_terms = verification.extract_ibd_trace_terms(
            ibd_snapshot.get("elements") or (),
            ibd_snapshot.get("relationships") or (),
        )

    requirement_links: dict[str, list[str]] = {}
    if requirement_ids:
        relationship_responses = await asyncio.gather(
            *(bridge.get_relationships(requirement_id) for requirement_id in requirement_ids)
        )
        requirement_links = {
            str(requirement_id): _related_requirement_architecture_ids(
                str(requirement_id),
                response if isinstance(response, Mapping) else {},
            )
            for requirement_id, response in zip(requirement_ids, relationship_responses)
        }

    if activity_terms or port_validation or ibd_terms or requirement_links or architecture_element_ids:
        trace_validation = verification.verify_cross_diagram_traceability(
            activity_terms=activity_terms,
            port_terms=[
                str(flow_property.get("name")).strip()
                for flow_property in (port_validation.get("flowProperties") or ())
                if isinstance(flow_property, Mapping) and str(flow_property.get("name") or "").strip()
            ],
            ibd_terms=ibd_terms,
            requirement_links=requirement_links,
            requirement_ids=requirement_ids,
            architecture_element_ids=architecture_element_ids,
        )

    architecture_elements = []
    if architecture_element_ids:
        architecture_elements = list(
            await asyncio.gather(*(bridge.get_element(element_id) for element_id in architecture_element_ids))
        )

    return build_cross_diagram_remediation_plan(
        activity_validation=activity_validation,
        port_validation=port_validation,
        requirement_validation=requirement_validation,
        trace_validation=trace_validation,
        architecture_elements=architecture_elements,
    )
