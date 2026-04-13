import unittest

from cameo_mcp.auto_remediation import (
    build_cross_diagram_remediation_plan,
    detect_cross_diagram_inconsistencies_for_artifacts,
)


class FakeBridge:
    def __init__(self) -> None:
        self.interface_flow_properties_calls: list[list[str]] = []
        self.elements = {
            "a1": {"id": "a1", "type": "OpaqueAction", "name": "Display Service Options"},
            "a2": {"id": "a2", "type": "OpaqueAction", "name": "Send Confirmation"},
            "n1": {"id": "n1", "type": "InitialNode", "name": "Start"},
            "f1": {"id": "f1", "type": "ActivityFinalNode", "name": "Done"},
            "if-1": {"id": "if-1", "humanType": "Interface Block", "name": "Customer UI Port Type"},
            "if-2": {"id": "if-2", "humanType": "Interface Block", "name": "Scheduling System Port Type"},
            "iflow-1": {"id": "iflow-1", "type": "InformationFlow", "name": "Availability Query"},
            "req-1": {"id": "req-1", "humanType": "Requirement", "name": "Appointment Response Time"},
            "req-2": {"id": "req-2", "humanType": "Requirement", "name": "System Availability"},
            "blk-1": {"id": "blk-1", "humanType": "Block", "name": "Scheduling System"},
        }
        self.shapes = {
            "act-1": {
                "shapes": [
                    {"presentationId": "pe-n1", "elementId": "n1", "elementType": "InitialNode"},
                    {"presentationId": "pe-a1", "elementId": "a1", "elementType": "OpaqueAction"},
                    {"presentationId": "pe-a2", "elementId": "a2", "elementType": "OpaqueAction"},
                    {"presentationId": "pe-f1", "elementId": "f1", "elementType": "ActivityFinalNode"},
                ]
            },
            "ibd-1": {
                "shapes": [
                    {"presentationId": "pe-port", "elementId": "if-1", "elementType": "Port"},
                    {"presentationId": "pe-flow", "elementId": "iflow-1", "elementType": "InformationFlow"},
                ]
            },
        }
        self.relationships = {
            "a1": {
                "outgoing": [
                    {
                        "relationshipId": "cf-1",
                        "type": "ControlFlow",
                        "sources": [{"id": "a1"}],
                        "targets": [{"id": "f1"}],
                    }
                ],
                "incoming": [
                    {
                        "relationshipId": "cf-0",
                        "type": "ControlFlow",
                        "sources": [{"id": "n1"}],
                        "targets": [{"id": "a1"}],
                    }
                ],
                "undirected": [],
            },
            "a2": {"outgoing": [], "incoming": [], "undirected": []},
            "req-1": {"outgoing": [], "incoming": [], "undirected": []},
            "req-2": {"outgoing": [], "incoming": [], "undirected": []},
            "if-1": {
                "outgoing": [],
                "incoming": [],
                "undirected": [
                    {
                        "relationshipId": "iflow-1",
                        "type": "InformationFlow",
                        "name": "Availability Query",
                        "conveyed": [{"id": "c-1", "name": "Available Slots"}],
                        "relatedElements": [{"id": "blk-1", "name": "Scheduling System"}],
                    }
                ],
            },
        }
        self.interface_flow_payload = {
            "interfaceBlocks": [
                {"id": "if-1", "name": "Customer UI Port Type"},
                {"id": "if-2", "name": "Scheduling System Port Type"},
            ],
            "flowProperties": [
                {"id": "fp-1", "name": "Available Slots", "ownerId": "if-1", "direction": "out"},
                {"id": "fp-2", "name": "Available Slots", "ownerId": "if-2", "direction": "in"},
            ],
        }
        self.specifications = {
            "req-1": {
                "elementId": "req-1",
                "appliedStereotypes": [{"stereotype": "Requirement", "taggedValues": {"id": "", "text": ""}}],
            },
            "req-2": {
                "elementId": "req-2",
                "documentation": "The system shall be easy to use.",
                "appliedStereotypes": [{"stereotype": "Requirement", "taggedValues": {"id": "", "text": "The system shall be easy to use."}}],
            },
        }

    async def list_diagram_shapes(self, diagram_id):
        return self.shapes[diagram_id]

    async def get_element(self, element_id):
        return self.elements[element_id]

    async def get_relationships(self, element_id):
        return self.relationships.get(element_id, {"outgoing": [], "incoming": [], "undirected": []})

    async def get_specification(self, element_id):
        return self.specifications[element_id]

    async def get_interface_flow_properties(self, interface_block_ids):
        self.interface_flow_properties_calls.append(list(interface_block_ids))
        return self.interface_flow_payload


class RemediationPlanTests(unittest.TestCase):
    def test_build_cross_diagram_remediation_plan_creates_previewable_receipts(self) -> None:
        result = build_cross_diagram_remediation_plan(
            activity_validation={
                "ok": False,
                "diagramId": "act-1",
                "checks": [{"name": "actions-connected", "ok": False, "details": {"isolatedActionIds": ["a2"]}}],
                "metrics": {
                    "initialNodeIds": ["n1"],
                    "actionIds": ["a1", "a2"],
                    "isolatedActionIds": ["a2"],
                    "unreachableActionIds": ["a2"],
                },
                "elements": [
                    {"id": "n1", "type": "InitialNode", "name": "Start"},
                    {"id": "a1", "type": "OpaqueAction", "name": "Display Service Options"},
                    {"id": "a2", "type": "OpaqueAction", "name": "Send Confirmation"},
                ],
            },
            port_validation={
                "ok": False,
                "checks": [{"name": "duplicate-flow-properties", "ok": False, "details": {}}],
                "metrics": {
                    "duplicateFlowProperties": {"Available Slots": ["if-1", "if-2"]},
                    "directionConflicts": {},
                    "unnamedPropertyIds": ["fp-9"],
                    "orphanPropertyIds": [],
                    "missingDirectionIds": [],
                },
                "interfaceBlocks": [
                    {"id": "if-1", "name": "Customer UI Port Type"},
                    {"id": "if-2", "name": "Scheduling System Port Type"},
                ],
                "flowProperties": [
                    {"id": "fp-1", "name": "Available Slots", "ownerId": "if-1", "direction": "out"},
                    {"id": "fp-2", "name": "Available Slots", "ownerId": "if-2", "direction": "in"},
                ],
            },
            requirement_validation={
                "ok": False,
                "checks": [{"name": "requirement-text-present", "ok": False, "details": {}}],
                "metrics": {
                    "missingIdIds": ["req-1"],
                    "blankTextIds": ["req-1"],
                    "weakTextIds": ["req-2"],
                    "evaluatedRequirements": [
                        {"elementId": "req-1", "name": "Appointment Response Time", "requirementId": "", "text": ""},
                        {"elementId": "req-2", "name": "System Availability", "requirementId": "", "text": "The system shall be easy to use."},
                    ],
                },
                "requirements": [
                    {"id": "req-1", "name": "Appointment Response Time"},
                    {"id": "req-2", "name": "System Availability"},
                ],
            },
            trace_validation={
                "ok": False,
                "checks": [{"name": "activity-to-port-coverage", "ok": False, "details": {}}],
                "metrics": {
                    "activityTermCount": 2,
                    "portTermCount": 1,
                    "ibdTermCount": 1,
                    "missingPortTerms": ["Available Slots"],
                    "missingIbdTerms": ["Availability Query"],
                    "missingRequirementTraceIds": ["req-1"],
                },
                "activityTerms": ["Display Service Options", "Send Confirmation"],
                "portTerms": ["Available Slots"],
                "ibdTerms": ["Availability Query"],
                "requirementLinks": {"req-1": [], "req-2": []},
            },
            architecture_elements=[{"id": "blk-1", "name": "Scheduling System", "humanType": "Block"}],
        )

        self.assertFalse(result["ok"])
        self.assertGreater(result["summary"]["issueCount"], 0)
        self.assertEqual("preview", result["patchPlan"]["mode"])
        self.assertTrue(all(receipt["status"] == "preview" for receipt in result["receipts"]))
        trace_receipt = next(
            receipt
            for receipt in result["receipts"]
            if receipt["category"] == "cross-diagram-traceability" and receipt["target"].get("elementId") == "req-1"
        )
        self.assertEqual("blk-1", trace_receipt["preview"]["after"]["traceTargets"][0]["elementId"])
        port_receipt = next(
            receipt
            for receipt in result["receipts"]
            if receipt["category"] == "port-boundary" and receipt["target"].get("flowPropertyName") == "Available Slots"
        )
        self.assertIn("candidateFixes", port_receipt["preview"]["after"])

    def test_build_cross_diagram_remediation_plan_is_empty_when_clean(self) -> None:
        result = build_cross_diagram_remediation_plan(
            activity_validation={"ok": True, "checks": [], "metrics": {}, "elements": []},
            port_validation={"ok": True, "checks": [], "metrics": {}, "interfaceBlocks": [], "flowProperties": []},
            requirement_validation={"ok": True, "checks": [], "metrics": {}, "requirements": []},
            trace_validation={"ok": True, "checks": [], "metrics": {}, "activityTerms": [], "portTerms": [], "ibdTerms": [], "requirementLinks": {}},
            architecture_elements=[],
        )

        self.assertTrue(result["ok"])
        self.assertEqual(0, result["summary"]["issueCount"])
        self.assertEqual([], result["receipts"])


class RemediationServiceTests(unittest.IsolatedAsyncioTestCase):
    async def test_detect_cross_diagram_inconsistencies_for_artifacts_uses_semantic_validation(self) -> None:
        bridge = FakeBridge()
        result = await detect_cross_diagram_inconsistencies_for_artifacts(
            activity_diagram_id="act-1",
            interface_block_ids=["if-1", "if-2"],
            ibd_diagram_id="ibd-1",
            requirement_ids=["req-1", "req-2"],
            architecture_element_ids=["blk-1"],
            bridge=bridge,
        )

        self.assertFalse(result["ok"])
        self.assertGreater(result["summary"]["issueCount"], 0)
        self.assertIn("findings", result)
        self.assertEqual([["if-1", "if-2"]], bridge.interface_flow_properties_calls)
        self.assertTrue(
            any(
                receipt["category"] == "cross-diagram-traceability" and receipt["target"].get("elementId") == "req-1"
                for receipt in result["receipts"]
            )
        )


if __name__ == "__main__":
    unittest.main()
