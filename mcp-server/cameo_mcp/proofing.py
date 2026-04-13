"""Proofing helpers for spelling, style, naming, and previewable patch plans.

The module is intentionally read-only. It analyzes requirement text, comments,
state and transition names, and diagram text, then returns findings plus a
previewable patch plan rather than mutating the model.
"""

from __future__ import annotations

import asyncio
import difflib
import re
from dataclasses import asdict, dataclass, field
from functools import lru_cache
from pathlib import Path
from typing import Any, Mapping, Sequence

from cameo_mcp import client as default_bridge_client
from cameo_mcp import verification

__all__ = [
    "ProofFinding",
    "ProofPatchOperation",
    "ProofReport",
    "analyze_comment_proofing",
    "analyze_diagram_text_proofing",
    "analyze_state_transition_proofing",
    "analyze_text_proofing",
    "apply_patch_plan",
    "build_patch_plan",
    "collect_proofing_targets",
    "proof_comments",
    "proof_diagram_text",
    "proof_model_text",
    "proof_requirements",
    "proof_state_transition_names",
    "proof_texts",
]


_REPO_ROOT = Path(__file__).resolve().parents[2]
_LEXICON_SOURCES = (
    _REPO_ROOT / "README.md",
    _REPO_ROOT / "CHANGELOG.md",
    _REPO_ROOT / "mcp-server" / "cameo_mcp" / "verification.py",
    _REPO_ROOT / "mcp-server" / "cameo_mcp" / "semantic_validation.py",
    _REPO_ROOT / "mcp-server" / "cameo_mcp" / "state_machine_semantics.py",
    _REPO_ROOT / "mcp-server" / "cameo_mcp" / "methodology" / "runtime.py",
    _REPO_ROOT / "mcp-server" / "cameo_mcp" / "methodology" / "service.py",
)

_WORD_RE = re.compile(r"[A-Za-z][A-Za-z']*")
_IDENTIFIER_RE = re.compile(r"[A-Z]?[a-z]+|[A-Z]+(?=[A-Z][a-z]|\b)|[0-9]+")
_MULTISPACE_RE = re.compile(r"\s+")
_TOKEN_SPLIT_RE = re.compile(r"[^A-Za-z0-9]+")
_LEADING_TRAILING_PUNCT_RE = re.compile(r"^[\s\W_]+|[\s\W_]+$")
_TODO_RE = re.compile(r"\b(todo|fixme|tbd|placeholder|xxx)\b", re.IGNORECASE)

_PREFERRED_CASE = {
    "api": "API",
    "ap": "AP",
    "bdd": "BDD",
    "cim": "CIM",
    "catia": "CATIA",
    "cameo": "Cameo",
    "gui": "GUI",
    "id": "ID",
    "ibd": "IBD",
    "mcp": "MCP",
    "oosem": "OOSEM",
    "pdf": "PDF",
    "pim": "PIM",
    "ppt": "PPT",
    "sc": "SC",
    "sql": "SQL",
    "sysml": "SysML",
    "ui": "UI",
}

_ALWAYS_ALLOWED_TOKENS = {
    "a",
    "an",
    "and",
    "be",
    "by",
    "for",
    "from",
    "in",
    "is",
    "of",
    "on",
    "or",
    "the",
    "to",
    "with",
}


@dataclass(frozen=True)
class ProofFinding:
    category: str
    severity: str
    artifact_type: str
    artifact_id: str
    field: str
    message: str
    current_text: str
    suggested_text: str = ""
    suggestions: tuple[str, ...] = ()
    confidence: float = 0.0
    evidence: Mapping[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        return _to_plain(self)


@dataclass(frozen=True)
class ProofPatchOperation:
    action: str
    artifact_type: str
    artifact_id: str
    field: str
    current_text: str
    suggested_text: str
    reason: str
    category: str
    confidence: float = 0.0
    auto_apply: bool = False

    def to_dict(self) -> dict[str, Any]:
        return _to_plain(self)


@dataclass
class ProofReport:
    name: str
    ok: bool
    summary: str
    checks: tuple[dict[str, Any], ...] = ()
    metrics: dict[str, Any] = field(default_factory=dict)
    findings: tuple[ProofFinding, ...] = ()
    patch_plan: dict[str, Any] = field(default_factory=dict)
    semantic_baseline: dict[str, Any] = field(default_factory=dict)
    sections: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        return _to_plain(self)


def _to_plain(value: Any) -> Any:
    if hasattr(value, "__dataclass_fields__"):
        return {key: _to_plain(item) for key, item in asdict(value).items()}
    if isinstance(value, dict):
        return {key: _to_plain(item) for key, item in value.items()}
    if isinstance(value, tuple):
        return [_to_plain(item) for item in value]
    if isinstance(value, list):
        return [_to_plain(item) for item in value]
    return value


def _normalized_text(value: Any) -> str:
    return _MULTISPACE_RE.sub(" ", str(value or "").strip())


def _normalized_key(value: Any) -> str:
    return _normalized_text(value).casefold()


def _check(name: str, ok: bool, details: Any) -> dict[str, Any]:
    return {"name": name, "ok": ok, "details": details}


def _extract_id(item: Mapping[str, Any]) -> str:
    for key in ("id", "elementId", "artifactId", "presentationId", "commentId"):
        value = item.get(key)
        if value:
            return str(value)
    return ""


def _extract_kind(item: Mapping[str, Any], fallback: str) -> str:
    for key in ("artifactType", "kind", "type", "humanType", "elementType", "role"):
        value = item.get(key)
        if value:
            return str(value)
    return fallback


def _extract_text_fields(item: Mapping[str, Any], role: str) -> list[tuple[str, str]]:
    role_key = role.casefold()
    if role_key == "requirement":
        keys = ("text", "documentation", "body", "comment", "name", "label", "elementName", "title")
    elif role_key == "comment":
        keys = ("body", "comment", "text", "documentation", "name", "label", "elementName")
    elif role_key in {"state", "transition"}:
        keys = ("name", "elementName", "label", "text", "body", "comment")
    else:
        keys = ("label", "name", "elementName", "text", "body", "comment", "documentation", "title")

    extracted: list[tuple[str, str]] = []
    for key in keys:
        value = item.get(key)
        if isinstance(value, str) and _normalized_text(value):
            extracted.append((key, _normalized_text(value)))
    if not extracted:
        fallback = _normalized_text(item.get("name") or item.get("label") or item.get("text") or item.get("body"))
        if fallback:
            extracted.append(("text", fallback))
    return extracted


def _normalize_requirement_payload(item: Mapping[str, Any]) -> dict[str, Any]:
    payload = dict(item)
    if not payload.get("requirementId") and payload.get("id"):
        payload["requirementId"] = payload["id"]
    return payload


def _iter_words(text: str) -> list[str]:
    parts = _WORD_RE.findall(text)
    words: list[str] = []
    for part in parts:
        if any(ch.isupper() for ch in part[1:]) and any(ch.islower() for ch in part):
            words.extend(token for token in _IDENTIFIER_RE.findall(part) if token)
        else:
            words.append(part)
    return words


@lru_cache(maxsize=1)
def _lexicon() -> set[str]:
    words: set[str] = set(_ALWAYS_ALLOWED_TOKENS)
    words.update(_PREFERRED_CASE)

    for source in _LEXICON_SOURCES:
        try:
            text = source.read_text(encoding="utf-8")
        except OSError:
            continue
        for token in _WORD_RE.findall(text):
            lowered = token.casefold()
            if len(lowered) >= 3:
                words.add(lowered)

    return words


def _preferred_case(token: str) -> str:
    lowered = token.casefold()
    if lowered in _PREFERRED_CASE:
        return _PREFERRED_CASE[lowered]
    if token.isupper() and len(token) <= 6:
        return token
    if any(ch.isupper() for ch in token[1:]):
        return token
    return token[:1].upper() + token[1:].lower() if token else token


def _split_for_casing(text: str) -> list[str]:
    chunks: list[str] = []
    for part in _TOKEN_SPLIT_RE.split(_normalized_text(text)):
        if not part:
            continue
        subparts = _IDENTIFIER_RE.findall(part)
        chunks.extend(subparts or [part])
    return chunks


def _title_case(text: str) -> str:
    words = _split_for_casing(text)
    pieces = [_preferred_case(word) for word in words if word]
    return " ".join(pieces)


def _sentence_case(text: str) -> str:
    normalized = _normalized_text(text)
    if not normalized:
        return normalized
    words = normalized.split(" ")
    pieces: list[str] = []
    for index, word in enumerate(words):
        if not word:
            continue
        if word.casefold() in _PREFERRED_CASE:
            pieces.append(_PREFERRED_CASE[word.casefold()])
            continue
        if index == 0:
            pieces.append(_preferred_case(word))
        else:
            pieces.append(word.lower())
    sentence = " ".join(pieces)
    if sentence and sentence[-1] not in ".!?":
        sentence += "."
    return sentence


def _pascal_case(text: str) -> str:
    normalized = _normalized_text(text)
    if not normalized:
        return normalized
    tokens = _split_for_casing(normalized)
    return "".join(
        _preferred_case(token) if token.casefold() in _PREFERRED_CASE else token[:1].upper() + token[1:].lower()
        for token in tokens
        if token
    )


def _normalize_whitespace(text: str) -> str:
    return _MULTISPACE_RE.sub(" ", str(text or "").strip())


def _spelling_suggestions(text: str) -> tuple[str, ...]:
    suggestions: list[str] = []
    lexicon_set = _lexicon()
    lexicon = sorted(lexicon_set)
    seen: set[str] = set()
    for word in _iter_words(text):
        lowered = word.casefold()
        if not lowered or lowered in _ALWAYS_ALLOWED_TOKENS:
            continue
        if lowered in lexicon_set:
            continue
        if word.isupper() and len(word) <= 6:
            continue
        if re.search(r"(.)\1\1", word):
            continue
        matches = difflib.get_close_matches(lowered, lexicon, n=2, cutoff=0.88)
        for match in matches:
            suggestion = _preferred_case(match)
            if suggestion not in seen:
                seen.add(suggestion)
                suggestions.append(suggestion)
    return tuple(suggestions)


def _role_style(role: str) -> str:
    role_key = role.casefold()
    if role_key == "requirement":
        return "sentence"
    if role_key == "comment":
        return "sentence"
    if role_key == "transition":
        return "pascal"
    if role_key == "state":
        return "title"
    return "title"


def _suggest_text(text: str, role: str, field: str) -> str:
    normalized = _normalize_whitespace(text)
    if not normalized:
        return normalized

    style = _role_style(role)
    if field in {"name", "label", "elementName", "title"}:
        if style == "pascal":
            return _pascal_case(normalized)
        return _title_case(normalized)

    suggestion = _sentence_case(normalized)
    if field in {"body", "comment", "documentation", "text"}:
        return suggestion
    return suggestion


def _issue_from_text(
    *,
    category: str,
    role: str,
    item: Mapping[str, Any],
    field: str,
    text: str,
    suggested_text: str,
    spelling: tuple[str, ...],
    severity: str,
    message: str,
) -> ProofFinding | None:
    item_id = _extract_id(item)
    kind = _extract_kind(item, role)
    if text == suggested_text and not spelling:
        return None
    evidence = {
        "role": role,
        "field": field,
        "suggestedStyle": _role_style(role),
        "spellingSuggestions": list(spelling),
    }
    return ProofFinding(
        category=category,
        severity=severity,
        artifact_type=kind,
        artifact_id=item_id,
        field=field,
        message=message,
        current_text=text,
        suggested_text=suggested_text,
        suggestions=spelling,
        confidence=0.86 if suggested_text != text else 0.62,
        evidence=evidence,
    )


def _build_patch_plan(findings: Sequence[ProofFinding], *, auto_apply: bool) -> dict[str, Any]:
    operations: list[dict[str, Any]] = []
    seen: set[tuple[str, str, str]] = set()
    for finding in findings:
        if not finding.suggested_text:
            continue
        key = (finding.artifact_type, finding.artifact_id, finding.field)
        if key in seen:
            continue
        seen.add(key)
        action = "rename_element" if finding.field in {"name", "label", "elementName", "title"} else "replace_text"
        operations.append(
            {
                "action": action,
                "target": {
                    "artifactType": finding.artifact_type,
                    "artifactId": finding.artifact_id,
                    "field": finding.field,
                },
                "currentText": finding.current_text,
                "suggestedText": finding.suggested_text,
                "category": finding.category,
                "reason": finding.message,
                "confidence": finding.confidence,
                "previewOnly": True,
                "autoApply": auto_apply,
            }
        )

    return {
        "mode": "auto_apply" if auto_apply else "preview",
        "previewOnly": True,
        "operationCount": len(operations),
        "operations": operations,
    }


def build_patch_plan(
    findings: Sequence[Mapping[str, Any] | ProofFinding],
    *,
    auto_apply: bool = False,
) -> dict[str, Any]:
    normalized: list[ProofFinding] = []
    for finding in findings:
        if isinstance(finding, ProofFinding):
            normalized.append(finding)
            continue
        raw_suggestions = finding.get("suggestions") or ()
        if isinstance(raw_suggestions, str):
            suggestions = (raw_suggestions,)
        else:
            suggestions = tuple(str(item) for item in raw_suggestions)
        normalized.append(
            ProofFinding(
                category=str(finding.get("category") or "proofing"),
                severity=str(finding.get("severity") or "medium"),
                artifact_type=str(finding.get("artifactType") or finding.get("artifact_type") or ""),
                artifact_id=str(finding.get("artifactId") or finding.get("artifact_id") or ""),
                field=str(finding.get("field") or ""),
                message=str(finding.get("message") or ""),
                current_text=str(finding.get("currentText") or finding.get("current_text") or ""),
                suggested_text=str(finding.get("suggestedText") or finding.get("suggested_text") or ""),
                suggestions=suggestions,
                confidence=float(finding.get("confidence") or 0.0),
                evidence=dict(finding.get("evidence") or {}),
            )
        )
    return _build_patch_plan(normalized, auto_apply=auto_apply)


def _analyze_collection(
    items: Sequence[Mapping[str, Any]] | None,
    *,
    role: str,
    category: str,
    auto_apply: bool,
    include_semantic_baseline: bool = False,
) -> ProofReport:
    records = [item for item in (items or ()) if isinstance(item, Mapping)]
    findings: list[ProofFinding] = []
    checks: list[dict[str, Any]] = []
    metrics = {
        "itemCount": len(records),
        "textItemCount": 0,
        "spellingIssueCount": 0,
        "styleIssueCount": 0,
        "namingIssueCount": 0,
    }

    for item in records:
        extracted_fields = _extract_text_fields(item, role)
        if not extracted_fields:
            continue
        metrics["textItemCount"] += 1
        for field, text in extracted_fields:
            if _TODO_RE.search(text):
                findings.append(
                    ProofFinding(
                        category=category,
                        severity="medium",
                        artifact_type=_extract_kind(item, role),
                        artifact_id=_extract_id(item),
                        field=field,
                        message="remove placeholder text before submission",
                        current_text=text,
                        suggested_text=_sentence_case(re.sub(_TODO_RE, "", text, count=1).strip(" :-")),
                        suggestions=(),
                        confidence=0.74,
                        evidence={"role": role, "field": field, "placeholder": "todo"},
                    )
                )
                metrics["styleIssueCount"] += 1
                continue
            suggested = _suggest_text(text, role, field)
            spelling = _spelling_suggestions(text)
            if spelling:
                metrics["spellingIssueCount"] += 1
            if _LEADING_TRAILING_PUNCT_RE.search(text):
                metrics["styleIssueCount"] += 1
            style_changed = suggested != text
            if style_changed:
                if field in {"name", "label", "elementName", "title"}:
                    metrics["namingIssueCount"] += 1
                else:
                    metrics["styleIssueCount"] += 1
            issue = _issue_from_text(
                category=category,
                role=role,
                item=item,
                field=field,
                text=text,
                suggested_text=suggested,
                spelling=spelling,
                severity="high" if spelling and suggested != text else "medium",
                message=_issue_message(role, field, text, suggested, spelling),
            )
            if issue is not None:
                findings.append(issue)

    checks.extend(
        [
            _check(f"{role}-items-present", bool(records), {"itemCount": len(records)}),
            _check(
                f"{role}-proofing-clean",
                not findings,
                {"findingCount": len(findings), "spellingIssueCount": metrics["spellingIssueCount"]},
            ),
        ]
    )
    patch_plan = _build_patch_plan(findings, auto_apply=auto_apply)
    summary = _summary_for_report(role, findings)
    return ProofReport(
        name=role,
        ok=not findings,
        summary=summary,
        checks=tuple(checks),
        metrics=metrics,
        findings=tuple(findings),
        patch_plan=patch_plan,
        sections={
            "items": records,
        },
    )


def _issue_message(
    role: str,
    field: str,
    current: str,
    suggested: str,
    spelling: tuple[str, ...],
) -> str:
    parts: list[str] = []
    if spelling:
        parts.append(f"possible spelling issue(s): {', '.join(spelling)}")
    if suggested != current:
        if field in {"name", "label", "elementName", "title"}:
            parts.append(f"suggested { _role_style(role) } case")
        else:
            parts.append("normalize punctuation, spacing, or sentence case")
    return "; ".join(parts) or "proofing adjustment recommended"


def _summary_for_report(role: str, findings: Sequence[ProofFinding]) -> str:
    if not findings:
        return f"No {role} proofing issues detected."
    categories = sorted({finding.category for finding in findings})
    return f"{len(findings)} {role} proofing issue(s) across {', '.join(categories)}."


def proof_requirements(
    requirements: Sequence[Mapping[str, Any]] | None,
    *,
    auto_apply: bool = False,
) -> dict[str, Any]:
    requirement_list = [
        _normalize_requirement_payload(item)
        for item in (requirements or ())
        if isinstance(item, Mapping)
    ]
    semantic_baseline = verification.verify_requirement_quality(
        requirement_list,
        require_id=True,
        require_measurement=True,
        min_text_length=20,
    )
    report = _analyze_collection(
        requirement_list,
        role="requirement",
        category="requirements",
        auto_apply=auto_apply,
        include_semantic_baseline=True,
    )
    report.semantic_baseline = semantic_baseline
    report.sections["semanticBaseline"] = semantic_baseline
    report.checks = tuple(report.checks) + (
        _check("requirement-semantic-baseline", semantic_baseline["ok"], semantic_baseline["metrics"]),
    )
    report.ok = report.ok and semantic_baseline["ok"]
    report.summary = _summary_for_report("requirement", report.findings)
    payload = report.to_dict()
    payload["semanticBaseline"] = semantic_baseline
    return payload


def proof_comments(
    comments: Sequence[Mapping[str, Any]] | None,
    *,
    auto_apply: bool = False,
) -> dict[str, Any]:
    return _analyze_collection(
        comments,
        role="comment",
        category="comments",
        auto_apply=auto_apply,
    ).to_dict()


def proof_state_transition_names(
    states: Sequence[Mapping[str, Any]] | None = None,
    transitions: Sequence[Mapping[str, Any]] | None = None,
    *,
    auto_apply: bool = False,
) -> dict[str, Any]:
    state_report = _analyze_collection(
        states,
        role="state",
        category="states",
        auto_apply=auto_apply,
    )
    transition_report = _analyze_collection(
        transitions,
        role="transition",
        category="transitions",
        auto_apply=auto_apply,
    )

    findings = state_report.findings + transition_report.findings
    patch_plan = build_patch_plan(findings, auto_apply=auto_apply)
    checks = tuple(state_report.checks) + tuple(transition_report.checks)
    summary = (
        f"{len(findings)} naming issue(s) across states and transitions."
        if findings
        else "No state or transition naming issues detected."
    )
    report = ProofReport(
        name="state-transition",
        ok=state_report.ok and transition_report.ok,
        summary=summary,
        checks=checks,
        metrics={
            "stateCount": state_report.metrics.get("itemCount", 0),
            "transitionCount": transition_report.metrics.get("itemCount", 0),
            "findingCount": len(findings),
        },
        findings=findings,
        patch_plan=patch_plan,
        sections={
            "states": state_report.to_dict(),
            "transitions": transition_report.to_dict(),
        },
    )
    return report.to_dict()


def proof_diagram_text(
    diagram_text: Sequence[Mapping[str, Any]] | None,
    *,
    auto_apply: bool = False,
) -> dict[str, Any]:
    return _analyze_collection(
        diagram_text,
        role="diagram",
        category="diagram-text",
        auto_apply=auto_apply,
    ).to_dict()


def proof_texts(
    *,
    requirements: Sequence[Mapping[str, Any]] | None = None,
    comments: Sequence[Mapping[str, Any]] | None = None,
    states: Sequence[Mapping[str, Any]] | None = None,
    transitions: Sequence[Mapping[str, Any]] | None = None,
    diagram_text: Sequence[Mapping[str, Any]] | None = None,
    auto_apply: bool = False,
) -> dict[str, Any]:
    requirement_report = proof_requirements(requirements, auto_apply=auto_apply)
    comment_report = proof_comments(comments, auto_apply=auto_apply)
    state_transition_report = proof_state_transition_names(
        states,
        transitions,
        auto_apply=auto_apply,
    )
    diagram_report = proof_diagram_text(diagram_text, auto_apply=auto_apply)

    sections = {
        "requirements": requirement_report,
        "comments": comment_report,
        "stateTransitions": state_transition_report,
        "diagramText": diagram_report,
    }
    findings: list[dict[str, Any]] = []
    for report in sections.values():
        findings.extend(report.get("findings") or [])

    ok = all(report.get("ok", False) for report in sections.values())
    patch_plan = build_patch_plan(findings, auto_apply=auto_apply)
    summary = (
        "No proofing issues detected."
        if ok
        else f"{len(findings)} proofing issue(s) found across {len([report for report in sections.values() if not report.get('ok', False)])} section(s)."
    )
    return ProofReport(
        name="proofing",
        ok=ok,
        summary=summary,
        checks=tuple(
            _check(f"{name}-section", report.get("ok", False), report.get("metrics") or {})
            for name, report in sections.items()
        ),
        metrics={
            "sectionCount": len(sections),
            "findingCount": len(findings),
            "autoApply": auto_apply,
        },
        findings=tuple(
            ProofFinding(
                category=str(item.get("category") or "proofing"),
                severity=str(item.get("severity") or "medium"),
                artifact_type=str(item.get("artifact_type") or item.get("artifactType") or ""),
                artifact_id=str(item.get("artifact_id") or item.get("artifactId") or ""),
                field=str(item.get("field") or ""),
                message=str(item.get("message") or ""),
                current_text=str(item.get("current_text") or item.get("currentText") or ""),
                suggested_text=str(item.get("suggested_text") or item.get("suggestedText") or ""),
                suggestions=tuple(str(s) for s in (item.get("suggestions") or ())),
                confidence=float(item.get("confidence") or 0.0),
                evidence=dict(item.get("evidence") or {}),
            )
            for item in findings
        ),
        patch_plan=patch_plan,
        sections=sections,
    ).to_dict()


def analyze_text_proofing(
    *,
    requirements: Sequence[Mapping[str, Any]] | None = None,
    comments: Sequence[Mapping[str, Any]] | None = None,
    states: Sequence[Mapping[str, Any]] | None = None,
    transitions: Sequence[Mapping[str, Any]] | None = None,
    diagram_text: Sequence[Mapping[str, Any]] | None = None,
    auto_apply: bool = False,
) -> dict[str, Any]:
    return proof_texts(
        requirements=requirements,
        comments=comments,
        states=states,
        transitions=transitions,
        diagram_text=diagram_text,
        auto_apply=auto_apply,
    )


def analyze_comment_proofing(
    comments: Sequence[Mapping[str, Any]] | None,
    *,
    auto_apply: bool = False,
) -> dict[str, Any]:
    return proof_comments(comments, auto_apply=auto_apply)


def analyze_state_transition_proofing(
    states: Sequence[Mapping[str, Any]] | None = None,
    transitions: Sequence[Mapping[str, Any]] | None = None,
    *,
    auto_apply: bool = False,
) -> dict[str, Any]:
    return proof_state_transition_names(states, transitions, auto_apply=auto_apply)


def analyze_diagram_text_proofing(
    diagram_text: Sequence[Mapping[str, Any]] | None,
    *,
    auto_apply: bool = False,
) -> dict[str, Any]:
    return proof_diagram_text(diagram_text, auto_apply=auto_apply)


def _type_descriptor(item: Mapping[str, Any]) -> str:
    values = [
        item.get("artifactType"),
        item.get("kind"),
        item.get("type"),
        item.get("humanType"),
        item.get("elementType"),
    ]
    stereotype_values = item.get("stereotypes") or ()
    if isinstance(stereotype_values, str):
        stereotype_values = (stereotype_values,)
    values.extend(stereotype_values)
    return " ".join(_normalized_text(value) for value in values if _normalized_text(value))


def _is_requirement(item: Mapping[str, Any]) -> bool:
    descriptor = _normalized_key(_type_descriptor(item))
    return "requirement" in descriptor


def _is_comment(item: Mapping[str, Any]) -> bool:
    descriptor = _normalized_key(_type_descriptor(item))
    return any(token in descriptor for token in ("comment", "note", "annotation"))


def _is_transition(item: Mapping[str, Any]) -> bool:
    return "transition" in _normalized_key(_type_descriptor(item))


def _is_state(item: Mapping[str, Any]) -> bool:
    descriptor = _normalized_key(_type_descriptor(item))
    return "state" in descriptor and "transition" not in descriptor and "state machine" not in descriptor


def _merge_specification(item: Mapping[str, Any], specification: Mapping[str, Any] | None) -> dict[str, Any]:
    merged = dict(item)
    if not isinstance(specification, Mapping):
        return merged
    merged["specification"] = dict(specification)
    for key in ("documentation", "constraints"):
        if key in specification:
            merged[key] = specification[key]

    properties = specification.get("properties")
    if isinstance(properties, Mapping):
        for key in ("name", "documentation", "body", "text", "comment"):
            if key in properties and key not in merged:
                merged[key] = properties[key]

    stereotypes = specification.get("appliedStereotypes") or ()
    if isinstance(stereotypes, Sequence):
        for stereotype in stereotypes:
            if not isinstance(stereotype, Mapping):
                continue
            tagged = stereotype.get("taggedValues")
            if not isinstance(tagged, Mapping):
                continue
            for key in ("text", "body", "comment", "documentation", "id"):
                value = tagged.get(key)
                if value is not None and key not in merged:
                    merged[key] = value
    return merged


async def _specifications_for_ids(
    element_ids: Sequence[str],
    *,
    bridge: Any,
) -> dict[str, Mapping[str, Any]]:
    if not element_ids:
        return {}
    specifications = await asyncio.gather(
        *(bridge.get_specification(element_id) for element_id in element_ids)
    )
    return {
        element_id: specification
        for element_id, specification in zip(element_ids, specifications)
        if isinstance(specification, Mapping)
    }


async def _collect_root_elements(root_package_id: str, *, bridge: Any) -> list[dict[str, Any]]:
    result = await bridge.query_elements(
        package=root_package_id,
        recursive=True,
        limit=5000,
        view="full",
    )
    return [
        dict(item)
        for item in (result.get("elements") or ())
        if isinstance(item, Mapping)
    ]


async def _collect_diagram_text_targets(
    *,
    root_package_id: str | None,
    diagram_ids: Sequence[str] | None,
    package_elements: Sequence[Mapping[str, Any]] | None,
    bridge: Any,
) -> list[dict[str, Any]]:
    selected_diagram_ids: list[str] = [str(item) for item in (diagram_ids or ()) if str(item).strip()]
    if not selected_diagram_ids and root_package_id:
        subtree_ids = {
            str(item.get("id") or item.get("elementId") or "")
            for item in (package_elements or ())
            if item.get("id") or item.get("elementId")
        }
        subtree_ids.add(root_package_id)
        diagrams = await bridge.list_diagrams()
        selected_diagram_ids = [
            str(diagram.get("id"))
            for diagram in (diagrams.get("diagrams") or ())
            if isinstance(diagram, Mapping)
            and str(diagram.get("ownerId") or "") in subtree_ids
            and diagram.get("id")
        ]

    targets: list[dict[str, Any]] = []
    if not selected_diagram_ids:
        return targets

    for diagram_id in selected_diagram_ids:
        shapes = await bridge.list_diagram_shapes(diagram_id)
        for shape in (shapes.get("shapes") or ()):
            if not isinstance(shape, Mapping):
                continue
            label = _normalized_text(
                shape.get("elementName")
                or shape.get("name")
                or shape.get("label")
                or shape.get("text")
                or ""
            )
            if not label:
                continue
            targets.append(
                {
                    "id": str(shape.get("presentationId") or shape.get("elementId") or ""),
                    "artifactType": shape.get("elementType") or shape.get("shapeType") or "DiagramText",
                    "artifactId": str(shape.get("elementId") or shape.get("presentationId") or ""),
                    "field": "label",
                    "label": label,
                    "diagramId": diagram_id,
                    "presentationId": shape.get("presentationId"),
                    "elementId": shape.get("elementId"),
                    "shapeType": shape.get("shapeType"),
                    "elementType": shape.get("elementType"),
                }
            )
    return targets


async def collect_proofing_targets(
    *,
    root_package_id: str | None = None,
    requirement_ids: Sequence[str] | None = None,
    comment_ids: Sequence[str] | None = None,
    state_ids: Sequence[str] | None = None,
    transition_ids: Sequence[str] | None = None,
    diagram_ids: Sequence[str] | None = None,
    bridge: Any = default_bridge_client,
) -> dict[str, list[dict[str, Any]]]:
    """Collect proofable model text from a package or explicit element lists."""
    package_elements = await _collect_root_elements(root_package_id, bridge=bridge) if root_package_id else []
    explicit_ids = {
        str(item)
        for sequence in (requirement_ids, comment_ids, state_ids, transition_ids)
        for item in (sequence or ())
        if str(item).strip()
    }

    elements_by_id = {
        str(item.get("id") or item.get("elementId") or ""): dict(item)
        for item in package_elements
        if item.get("id") or item.get("elementId")
    }
    if explicit_ids:
        missing_ids = [element_id for element_id in explicit_ids if element_id not in elements_by_id]
        if missing_ids:
            explicit_elements = await asyncio.gather(
                *(bridge.get_element(element_id) for element_id in missing_ids)
            )
            for element in explicit_elements:
                if isinstance(element, Mapping) and (element.get("id") or element.get("elementId")):
                    elements_by_id[str(element.get("id") or element.get("elementId"))] = dict(element)

    requirement_candidates = list(requirement_ids or ()) or [
        element_id for element_id, item in elements_by_id.items() if _is_requirement(item)
    ]
    comment_candidates = list(comment_ids or ()) or [
        element_id for element_id, item in elements_by_id.items() if _is_comment(item)
    ]
    state_candidates = list(state_ids or ()) or [
        element_id for element_id, item in elements_by_id.items() if _is_state(item)
    ]
    transition_candidates = list(transition_ids or ()) or [
        element_id for element_id, item in elements_by_id.items() if _is_transition(item)
    ]

    spec_ids = _unique_ids(requirement_candidates + comment_candidates)
    specifications = await _specifications_for_ids(spec_ids, bridge=bridge)

    requirements = [
        _merge_specification(elements_by_id[element_id], specifications.get(element_id))
        for element_id in _unique_ids(requirement_candidates)
        if element_id in elements_by_id
    ]
    comments = [
        _merge_specification(elements_by_id[element_id], specifications.get(element_id))
        for element_id in _unique_ids(comment_candidates)
        if element_id in elements_by_id
    ]
    states = [
        dict(elements_by_id[element_id])
        for element_id in _unique_ids(state_candidates)
        if element_id in elements_by_id
    ]
    transitions = [
        dict(elements_by_id[element_id])
        for element_id in _unique_ids(transition_candidates)
        if element_id in elements_by_id
    ]
    diagram_text = await _collect_diagram_text_targets(
        root_package_id=root_package_id,
        diagram_ids=diagram_ids,
        package_elements=tuple(elements_by_id.values()),
        bridge=bridge,
    )
    return {
        "requirements": requirements,
        "comments": comments,
        "states": states,
        "transitions": transitions,
        "diagramText": diagram_text,
    }


def _unique_ids(values: Sequence[str]) -> list[str]:
    seen: set[str] = set()
    ordered: list[str] = []
    for value in values:
        text = str(value or "").strip()
        if not text or text in seen:
            continue
        seen.add(text)
        ordered.append(text)
    return ordered


async def apply_patch_plan(
    patch_plan: Mapping[str, Any],
    *,
    bridge: Any = default_bridge_client,
) -> dict[str, Any]:
    """Apply a preview patch plan against the live model where the bridge supports it."""
    operations = [
        dict(operation)
        for operation in (patch_plan.get("operations") or ())
        if isinstance(operation, Mapping)
    ]
    receipts: list[dict[str, Any]] = []
    for operation in operations:
        target = operation.get("target") or {}
        if not isinstance(target, Mapping):
            target = {}
        artifact_id = str(target.get("artifactId") or "").strip()
        field = str(target.get("field") or "").strip()
        suggested_text = str(operation.get("suggestedText") or "")
        if not artifact_id or not field or not suggested_text:
            receipts.append(
                {
                    "status": "skipped",
                    "operation": operation,
                    "reason": "missing artifact id, field, or suggested text",
                }
            )
            continue

        try:
            if field in {"name", "label", "elementName", "title"}:
                result = await bridge.modify_element(artifact_id, name=suggested_text)
                applied_with = "modify_element"
            elif field == "documentation":
                result = await bridge.modify_element(artifact_id, documentation=suggested_text)
                applied_with = "modify_element"
            else:
                result = await bridge.set_specification(
                    artifact_id,
                    properties={field: suggested_text},
                )
                applied_with = "set_specification"
            receipts.append(
                {
                    "status": "applied",
                    "artifactId": artifact_id,
                    "field": field,
                    "suggestedText": suggested_text,
                    "appliedWith": applied_with,
                    "result": result,
                }
            )
        except Exception as exc:  # pragma: no cover - exercised via server integration mocks
            receipts.append(
                {
                    "status": "failed",
                    "artifactId": artifact_id,
                    "field": field,
                    "suggestedText": suggested_text,
                    "error": str(exc),
                }
            )

    failures = [receipt for receipt in receipts if receipt["status"] == "failed"]
    return {
        "ok": not failures,
        "mode": "applied",
        "operationCount": len(operations),
        "receiptCount": len(receipts),
        "failedCount": len(failures),
        "receipts": receipts,
    }


async def proof_model_text(
    *,
    root_package_id: str | None = None,
    requirement_ids: Sequence[str] | None = None,
    comment_ids: Sequence[str] | None = None,
    state_ids: Sequence[str] | None = None,
    transition_ids: Sequence[str] | None = None,
    diagram_ids: Sequence[str] | None = None,
    auto_apply: bool = False,
    bridge: Any = default_bridge_client,
) -> dict[str, Any]:
    """Collect model text from the bridge, analyze it, and optionally apply safe fixes."""
    sections = await collect_proofing_targets(
        root_package_id=root_package_id,
        requirement_ids=requirement_ids,
        comment_ids=comment_ids,
        state_ids=state_ids,
        transition_ids=transition_ids,
        diagram_ids=diagram_ids,
        bridge=bridge,
    )
    report = proof_texts(
        requirements=sections["requirements"],
        comments=sections["comments"],
        states=sections["states"],
        transitions=sections["transitions"],
        diagram_text=sections["diagramText"],
        auto_apply=auto_apply,
    )
    report["collected"] = {
        "rootPackageId": root_package_id,
        "requirementCount": len(sections["requirements"]),
        "commentCount": len(sections["comments"]),
        "stateCount": len(sections["states"]),
        "transitionCount": len(sections["transitions"]),
        "diagramTextCount": len(sections["diagramText"]),
        "diagramIds": [str(item) for item in (diagram_ids or ()) if str(item).strip()],
    }
    if auto_apply and report.get("patch_plan", {}).get("operations"):
        report["applyReceipts"] = await apply_patch_plan(report["patch_plan"], bridge=bridge)
        report["autoApplyCompleted"] = bool(report["applyReceipts"].get("ok"))
    return report
