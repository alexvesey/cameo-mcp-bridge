"""
Tests for cameo_mcp.version_compat — capability gating and version negotiation.

These tests mock the Java bridge HTTP layer entirely so they run without a
running Cameo instance.  Two profiles are exercised:

  2022x — available groups exclude "relationMaps" and "simulation"
  2024x — all groups present
"""

from __future__ import annotations

import unittest
from unittest.mock import AsyncMock, MagicMock, patch

from cameo_mcp import version_compat
from cameo_mcp.version_compat import (
    CapabilityNotAvailable,
    init_from_capabilities,
    is_available,
    get_cameo_version,
    require_capability,
    require_relation_maps,
    require_simulation,
    RELATION_MAPS_GROUP,
    SIMULATION_GROUP,
)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _caps_2022x() -> dict:
    """Minimal capabilities response mimicking a 2022x Cameo installation."""
    return {
        "cameoVersion": "2022x",
        "available": [
            "health", "project", "ui", "session",
            "elements", "stereotypes", "relationships",
            "matrices", "genericTables", "diagrams",
            "snapshots", "validation", "probes",
            "specification", "reports", "requirements",
            "importExport", "teamwork", "datahub",
            "criteria", "profiles", "variants",
            "extensions", "typedDiagrams", "macros",
        ],
    }


def _caps_2024x() -> dict:
    """Minimal capabilities response mimicking a 2024x Cameo installation."""
    caps = _caps_2022x()
    caps["cameoVersion"] = "2024x"
    caps["available"] = caps["available"] + ["relationMaps", "simulation"]
    return caps


def _reset_compat() -> None:
    """Reset module-level compat state between tests."""
    version_compat._available_groups = frozenset()
    version_compat._cameo_version = None


# ---------------------------------------------------------------------------
# init_from_capabilities
# ---------------------------------------------------------------------------

class TestInitFromCapabilities(unittest.TestCase):

    def setUp(self) -> None:
        _reset_compat()

    def test_2022x_version_stored(self) -> None:
        init_from_capabilities(_caps_2022x())
        self.assertEqual(get_cameo_version(), "2022x")

    def test_2024x_version_stored(self) -> None:
        init_from_capabilities(_caps_2024x())
        self.assertEqual(get_cameo_version(), "2024x")

    def test_2022x_relation_maps_absent(self) -> None:
        init_from_capabilities(_caps_2022x())
        self.assertFalse(is_available(RELATION_MAPS_GROUP))

    def test_2022x_simulation_absent(self) -> None:
        init_from_capabilities(_caps_2022x())
        self.assertFalse(is_available(SIMULATION_GROUP))

    def test_2024x_relation_maps_present(self) -> None:
        init_from_capabilities(_caps_2024x())
        self.assertTrue(is_available(RELATION_MAPS_GROUP))

    def test_2024x_simulation_present(self) -> None:
        init_from_capabilities(_caps_2024x())
        self.assertTrue(is_available(SIMULATION_GROUP))

    def test_core_group_always_available(self) -> None:
        for caps in [_caps_2022x(), _caps_2024x()]:
            with self.subTest(version=caps["cameoVersion"]):
                _reset_compat()
                init_from_capabilities(caps)
                self.assertTrue(is_available("elements"))
                self.assertTrue(is_available("diagrams"))
                self.assertTrue(is_available("matrices"))

    def test_missing_available_field_gives_empty_set(self) -> None:
        init_from_capabilities({"cameoVersion": "2022x"})
        self.assertFalse(is_available("elements"))

    def test_idempotent_reinit(self) -> None:
        init_from_capabilities(_caps_2022x())
        init_from_capabilities(_caps_2024x())
        # Second call should overwrite the first
        self.assertEqual(get_cameo_version(), "2024x")
        self.assertTrue(is_available(RELATION_MAPS_GROUP))


# ---------------------------------------------------------------------------
# require_capability
# ---------------------------------------------------------------------------

class TestRequireCapability(unittest.TestCase):

    def setUp(self) -> None:
        _reset_compat()

    def test_raises_when_group_absent_2022x(self) -> None:
        init_from_capabilities(_caps_2022x())
        with self.assertRaises(CapabilityNotAvailable) as ctx:
            require_capability(RELATION_MAPS_GROUP)
        exc = ctx.exception
        self.assertEqual(exc.group, RELATION_MAPS_GROUP)
        self.assertEqual(exc.version, "2022x")

    def test_no_raise_when_group_present_2024x(self) -> None:
        init_from_capabilities(_caps_2024x())
        # Should not raise
        require_capability(RELATION_MAPS_GROUP)
        require_capability(SIMULATION_GROUP)

    def test_alternative_message_included(self) -> None:
        init_from_capabilities(_caps_2022x())
        with self.assertRaises(CapabilityNotAvailable) as ctx:
            require_capability(RELATION_MAPS_GROUP, alternative="Use BDD instead")
        self.assertIn("Use BDD instead", ctx.exception.alternative)

    def test_to_dict_contains_required_keys(self) -> None:
        init_from_capabilities(_caps_2022x())
        try:
            require_capability(SIMULATION_GROUP)
        except CapabilityNotAvailable as exc:
            d = exc.to_dict()
            self.assertIn("error", d)
            self.assertIn("capability", d)
            self.assertIn("version", d)
            self.assertEqual(d["capability"], SIMULATION_GROUP)
            self.assertEqual(d["version"], "2022x")


class TestConvenienceWrappers(unittest.TestCase):

    def setUp(self) -> None:
        _reset_compat()

    def test_require_relation_maps_raises_on_2022x(self) -> None:
        init_from_capabilities(_caps_2022x())
        with self.assertRaises(CapabilityNotAvailable):
            require_relation_maps()

    def test_require_simulation_raises_on_2022x(self) -> None:
        init_from_capabilities(_caps_2022x())
        with self.assertRaises(CapabilityNotAvailable):
            require_simulation()

    def test_require_relation_maps_passes_on_2024x(self) -> None:
        init_from_capabilities(_caps_2024x())
        require_relation_maps()  # no exception

    def test_require_simulation_passes_on_2024x(self) -> None:
        init_from_capabilities(_caps_2024x())
        require_simulation()  # no exception


# ---------------------------------------------------------------------------
# Version headers in client
# ---------------------------------------------------------------------------

class TestClientVersionHeaders(unittest.TestCase):

    def setUp(self) -> None:
        from cameo_mcp import client
        client._shared_client = None
        client._shared_client_base_url = None
        client._detected_cameo_version = None
        client._detected_server_version = None

    def test_client_sends_x_client_version_header(self) -> None:
        import os
        from cameo_mcp import client
        # Clear proxy env vars that can trigger a socksio ImportError in the sandbox
        for k in ["https_proxy", "http_proxy", "HTTPS_PROXY", "HTTP_PROXY",
                  "ALL_PROXY", "all_proxy"]:
            os.environ.pop(k, None)
        http_client = client._get_client()
        header_keys_lower = [k.lower() for k in http_client.headers.keys()]
        self.assertIn("x-client-version", header_keys_lower)

    def test_capture_version_headers_stores_cameo_version(self) -> None:
        from cameo_mcp import client

        mock_response = MagicMock()
        mock_response.headers = {
            "X-Server-Version": "2.3.5",
            "X-Cameo-Version": "2022x",
        }
        client._capture_version_headers(mock_response)
        self.assertEqual(client._detected_cameo_version, "2022x")
        self.assertEqual(client._detected_server_version, "2.3.5")

    def test_capture_version_headers_handles_missing_headers(self) -> None:
        from cameo_mcp import client
        mock_response = MagicMock()
        mock_response.headers = {}
        client._capture_version_headers(mock_response)
        self.assertIsNone(client._detected_cameo_version)


# ---------------------------------------------------------------------------
# BridgeCapabilities available-list contract (mocked Java response shape)
# ---------------------------------------------------------------------------

class TestBridgeCapabilitiesAvailableField(unittest.TestCase):
    """
    Verifies that the Python layer correctly reads the `available` field
    from the capabilities response, regardless of whether it comes from
    a 2022x or 2024x bridge.
    """

    def setUp(self) -> None:
        _reset_compat()

    def test_2022x_bridge_response_excludes_2024x_groups(self) -> None:
        init_from_capabilities(_caps_2022x())
        for group in [RELATION_MAPS_GROUP, SIMULATION_GROUP]:
            with self.subTest(group=group):
                self.assertFalse(is_available(group),
                    f"Expected '{group}' to be absent in 2022x profile")

    def test_2024x_bridge_response_includes_all_groups(self) -> None:
        init_from_capabilities(_caps_2024x())
        for group in [RELATION_MAPS_GROUP, SIMULATION_GROUP]:
            with self.subTest(group=group):
                self.assertTrue(is_available(group),
                    f"Expected '{group}' to be present in 2024x profile")

    def test_unknown_group_always_unavailable(self) -> None:
        for caps in [_caps_2022x(), _caps_2024x()]:
            with self.subTest(version=caps["cameoVersion"]):
                _reset_compat()
                init_from_capabilities(caps)
                self.assertFalse(is_available("nonexistent_group_xyz"))


if __name__ == "__main__":
    unittest.main()
