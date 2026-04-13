import unittest

from cameo_mcp.proofing import (
    analyze_comment_proofing,
    analyze_diagram_text_proofing,
    analyze_state_transition_proofing,
    apply_patch_plan,
    build_patch_plan,
    collect_proofing_targets,
    proof_comments,
    proof_diagram_text,
    proof_model_text,
    proof_requirements,
    proof_state_transition_names,
    proof_texts,
)


class ProofingTests(unittest.TestCase):
    def test_proof_requirements_returns_semantic_baseline_and_patch_plan(self) -> None:
        report = proof_requirements(
            [
                {
                    "id": "req-1",
                    "type": "Requirement",
                    "name": "apointment response time",
                    "text": "the system shall respond within 5 seconds",
                }
            ]
        )

        self.assertFalse(report["ok"])
        self.assertIn("semanticBaseline", report["sections"])
        self.assertTrue(report["semanticBaseline"]["ok"])
        self.assertGreaterEqual(report["patch_plan"]["operationCount"], 1)
        operations = report["patch_plan"]["operations"]
        self.assertTrue(any(operation["action"] == "rename_element" for operation in operations))
        self.assertTrue(any(operation["action"] == "replace_text" for operation in operations))

    def test_proof_comments_flags_todo_placeholders(self) -> None:
        report = proof_comments(
            [
                {
                    "id": "comment-1",
                    "body": "  TODO: tighten this label  ",
                }
            ]
        )

        self.assertFalse(report["ok"])
        self.assertEqual(1, report["patch_plan"]["operationCount"])
        finding = report["findings"][0]
        self.assertIn("placeholder", finding["message"])
        self.assertEqual("Tighten this label.", report["patch_plan"]["operations"][0]["suggestedText"])

    def test_state_transition_proofing_distinguishes_title_and_pascal_case(self) -> None:
        report = proof_state_transition_names(
            states=[
                {
                    "id": "state-1",
                    "name": "query pending",
                }
            ],
            transitions=[
                {
                    "id": "transition-1",
                    "name": "booking requested",
                }
            ],
        )

        self.assertFalse(report["ok"])
        operations = report["patch_plan"]["operations"]
        self.assertIn("Query Pending", {operation["suggestedText"] for operation in operations})
        self.assertIn("BookingRequested", {operation["suggestedText"] for operation in operations})

    def test_diagram_text_proofing_supports_auto_apply_patch_plans(self) -> None:
        report = proof_diagram_text(
            [
                {
                    "id": "diagram-text-1",
                    "label": "  verified selection  ",
                }
            ],
            auto_apply=True,
        )

        self.assertFalse(report["ok"])
        self.assertEqual("auto_apply", report["patch_plan"]["mode"])
        self.assertTrue(report["patch_plan"]["previewOnly"])
        self.assertEqual("Verified Selection", report["patch_plan"]["operations"][0]["suggestedText"])

    def test_combined_text_proofing_aggregates_sections(self) -> None:
        report = proof_texts(
            requirements=[
                {
                    "id": "req-1",
                    "name": "response latency",
                    "text": "the system shall respond within 5 seconds",
                }
            ],
            comments=[
                {
                    "id": "comment-1",
                    "body": "  fix this later  ",
                }
            ],
            states=[
                {
                    "id": "state-1",
                    "name": "idle mode",
                }
            ],
            transitions=[
                {
                    "id": "transition-1",
                    "name": "query requested",
                }
            ],
            diagram_text=[
                {
                    "id": "diagram-text-1",
                    "text": "show verification result",
                }
            ],
        )

        self.assertFalse(report["ok"])
        self.assertEqual(6, report["patch_plan"]["operationCount"])
        self.assertEqual(4, report["metrics"]["sectionCount"])
        self.assertIn("stateTransitions", report["sections"])
        self.assertIn("diagramText", report["sections"])

    def test_build_patch_plan_accepts_dict_findings(self) -> None:
        patch_plan = build_patch_plan(
            [
                {
                    "category": "requirements",
                    "severity": "medium",
                    "artifactType": "Requirement",
                    "artifactId": "req-99",
                    "field": "text",
                    "message": "normalize casing",
                    "currentText": "hello world",
                    "suggestedText": "Hello world.",
                    "suggestions": "Hello",
                    "confidence": 0.9,
                }
            ]
        )

        self.assertEqual(1, patch_plan["operationCount"])
        self.assertEqual("Hello world.", patch_plan["operations"][0]["suggestedText"])

    def test_aliases_point_to_the_same_analyzers(self) -> None:
        self.assertEqual(
            proof_comments([{"id": "c-1", "body": "hello"}]),
            analyze_comment_proofing([{"id": "c-1", "body": "hello"}]),
        )
        self.assertEqual(
            proof_diagram_text([{"id": "d-1", "label": "hello"}]),
            analyze_diagram_text_proofing([{"id": "d-1", "label": "hello"}]),
        )
        self.assertEqual(
            proof_state_transition_names([{"id": "s-1", "name": "idle"}], [{"id": "t-1", "name": "go"}]),
            analyze_state_transition_proofing([{"id": "s-1", "name": "idle"}], [{"id": "t-1", "name": "go"}]),
        )


class FakeProofingBridge:
    def __init__(self) -> None:
        self.modified: list[tuple[str, dict[str, str]]] = []
        self.spec_updates: list[tuple[str, dict[str, str]]] = []

    async def query_elements(self, **kwargs):
        return {
            "elements": [
                {"id": "req-1", "type": "Requirement", "name": "apointment response time"},
                {"id": "comment-1", "type": "Comment", "name": "TODO note"},
                {"id": "state-1", "type": "State", "name": "query pending"},
                {"id": "transition-1", "type": "Transition", "name": "booking requested"},
            ]
        }

    async def get_specification(self, element_id: str):
        if element_id == "req-1":
            return {
                "elementId": "req-1",
                "appliedStereotypes": [
                    {"stereotype": "Requirement", "taggedValues": {"text": "the system shall respond within 5 seconds"}}
                ],
            }
        if element_id == "comment-1":
            return {"elementId": "comment-1", "properties": {"body": "TODO: tighten this label"}}
        return {"elementId": element_id, "properties": {}}

    async def list_diagrams(self):
        return {"diagrams": [{"id": "dia-1", "ownerId": "req-1"}]}

    async def list_diagram_shapes(self, diagram_id: str):
        return {
            "shapes": [
                {
                    "presentationId": "pe-1",
                    "elementId": "req-1",
                    "elementType": "Requirement",
                    "shapeType": "ClassView",
                    "elementName": "verified selection",
                }
            ]
        }

    async def modify_element(self, element_id: str, **kwargs):
        self.modified.append((element_id, kwargs))
        return {"id": element_id, "updated": kwargs}

    async def set_specification(self, element_id: str, **kwargs):
        self.spec_updates.append((element_id, kwargs))
        return {"elementId": element_id, "updated": kwargs}


class ProofingBridgeTests(unittest.IsolatedAsyncioTestCase):
    async def test_collect_proofing_targets_hydrates_root_package_content(self) -> None:
        bridge = FakeProofingBridge()

        result = await collect_proofing_targets(root_package_id="pkg-1", bridge=bridge)

        self.assertEqual(1, len(result["requirements"]))
        self.assertEqual("the system shall respond within 5 seconds", result["requirements"][0]["text"])
        self.assertEqual(1, len(result["comments"]))
        self.assertEqual("TODO: tighten this label", result["comments"][0]["body"])
        self.assertEqual(1, len(result["diagramText"]))

    async def test_apply_patch_plan_routes_name_and_text_updates(self) -> None:
        bridge = FakeProofingBridge()

        result = await apply_patch_plan(
            {
                "operations": [
                    {
                        "target": {"artifactId": "state-1", "field": "name"},
                        "suggestedText": "Query Pending",
                    },
                    {
                        "target": {"artifactId": "req-1", "field": "text"},
                        "suggestedText": "The system shall respond within 5 seconds.",
                    },
                ]
            },
            bridge=bridge,
        )

        self.assertTrue(result["ok"])
        self.assertEqual([("state-1", {"name": "Query Pending"})], bridge.modified)
        self.assertEqual(
            [("req-1", {"properties": {"text": "The system shall respond within 5 seconds."}})],
            bridge.spec_updates,
        )

    async def test_proof_model_text_can_auto_apply(self) -> None:
        bridge = FakeProofingBridge()

        result = await proof_model_text(root_package_id="pkg-1", auto_apply=True, bridge=bridge)

        self.assertIn("applyReceipts", result)
        self.assertTrue(result["autoApplyCompleted"])
        self.assertGreater(result["applyReceipts"]["receiptCount"], 0)


if __name__ == "__main__":
    unittest.main()
