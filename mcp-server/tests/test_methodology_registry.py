import unittest

from cameo_mcp.methodology.registry import get_pack, get_recipe, list_packs


class MethodologyRegistryTests(unittest.TestCase):
    def test_oosem_and_uaf_packs_are_registered(self) -> None:
        packs = list_packs()

        self.assertGreaterEqual(len(packs), 2)
        self.assertEqual("oosem", packs[0].id)
        self.assertGreaterEqual(len(packs[0].recipes), 7)
        self.assertIn("uaf", {pack.id for pack in packs})

    def test_oosem_pack_exposes_review_and_evidence_sections(self) -> None:
        pack = get_pack("oosem")

        self.assertGreaterEqual(len(pack.review_sections), 3)
        self.assertGreaterEqual(len(pack.evidence_sections), 3)
        self.assertIn("requirements", {phase.id for phase in pack.method_phases})

    def test_pack_recipe_lookup_is_stable(self) -> None:
        recipe = get_recipe("oosem", "use_case_model")

        self.assertEqual("Use Case Model", recipe.title)
        self.assertEqual("needs", recipe.phase_id)

    def test_logical_architecture_satisfy_direction_matches_runtime(self) -> None:
        recipe = get_recipe("oosem", "logical_architecture_scaffold")

        satisfy = next(
            relationship
            for relationship in recipe.mandatory_relationships
            if relationship.relationship == "satisfy"
        )

        self.assertEqual("logical_block", satisfy.source_role)
        self.assertEqual("system_requirement", satisfy.target_role)


if __name__ == "__main__":
    unittest.main()
