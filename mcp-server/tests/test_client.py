import unittest
from unittest.mock import AsyncMock, patch

from cameo_mcp import client


def reset_client_state() -> None:
    client._shared_client = None
    client._shared_client_base_url = None
    client._capabilities_cache = None
    client._capabilities_cache_base_url = None


class BridgeMetadataTests(unittest.TestCase):
    def setUp(self) -> None:
        reset_client_state()

    def test_annotate_bridge_metadata_marks_compatible_bridge(self) -> None:
        annotated = client._annotate_bridge_metadata(
            {
                "pluginVersion": client.BRIDGE_PLUGIN_VERSION,
                "apiVersion": client.BRIDGE_API_VERSION,
                "handshakeVersion": client.BRIDGE_HANDSHAKE_VERSION,
            }
        )

        compatibility = annotated["compatibility"]
        self.assertTrue(compatibility["clientCompatible"])
        self.assertEqual([], compatibility["clientCompatibilityErrors"])

    def test_require_compatible_bridge_rejects_mismatch(self) -> None:
        with self.assertRaisesRegex(RuntimeError, "plugin version mismatch"):
            client._require_compatible_bridge(
                {
                    "pluginVersion": "9.9.9",
                    "apiVersion": client.BRIDGE_API_VERSION,
                    "handshakeVersion": client.BRIDGE_HANDSHAKE_VERSION,
                }
            )


class ClientRequestTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        reset_client_state()

    async def test_status_returns_annotated_metadata(self) -> None:
        with patch(
            "cameo_mcp.client._request_raw",
            new=AsyncMock(
                return_value={
                    "status": "ok",
                    "pluginVersion": client.BRIDGE_PLUGIN_VERSION,
                    "apiVersion": client.BRIDGE_API_VERSION,
                    "handshakeVersion": client.BRIDGE_HANDSHAKE_VERSION,
                }
            ),
        ):
            result = await client.status()

        self.assertEqual("ok", result["status"])
        self.assertTrue(result["compatibility"]["clientCompatible"])

    async def test_ensure_compatible_bridge_caches_capabilities_by_base_url(self) -> None:
        with patch(
            "cameo_mcp.client._request_raw",
            new=AsyncMock(
                return_value={
                    "pluginVersion": client.BRIDGE_PLUGIN_VERSION,
                    "apiVersion": client.BRIDGE_API_VERSION,
                    "handshakeVersion": client.BRIDGE_HANDSHAKE_VERSION,
                }
            ),
        ) as request_raw:
            first = await client._ensure_compatible_bridge()
            second = await client._ensure_compatible_bridge()

        self.assertTrue(first["compatibility"]["clientCompatible"])
        self.assertEqual(first, second)
        request_raw.assert_awaited_once_with("GET", "/capabilities")

    async def test_query_elements_passes_paging_and_view_params(self) -> None:
        with patch("cameo_mcp.client._request", new=AsyncMock(return_value={"count": 0})) as request:
            await client.query_elements(
                type="Block",
                name="ATM",
                package="pkg-1",
                stereotype="requirement",
                recursive=False,
                limit=25,
                offset=50,
                view="full",
            )

        request.assert_awaited_once()
        self.assertEqual("GET", request.await_args.args[0])
        self.assertEqual("/elements", request.await_args.args[1])
        self.assertEqual(
            {
                "type": "Block",
                "name": "ATM",
                "package": "pkg-1",
                "stereotype": "requirement",
                "recursive": "false",
                "limit": "25",
                "offset": "50",
                "view": "full",
            },
            request.await_args.kwargs["params"],
        )

    async def test_list_containment_children_passes_filters_and_view(self) -> None:
        with patch("cameo_mcp.client._request", new=AsyncMock(return_value={"count": 0})) as request:
            await client.list_containment_children(
                root_id="root-1",
                limit=10,
                offset=20,
                type="Block",
                name="Power",
                stereotype="block",
                view="full",
            )

        request.assert_awaited_once()
        self.assertEqual("GET", request.await_args.args[0])
        self.assertEqual("/containment-tree/children", request.await_args.args[1])
        self.assertEqual(
            {
                "limit": "10",
                "offset": "20",
                "rootId": "root-1",
                "type": "Block",
                "name": "Power",
                "stereotype": "block",
                "view": "full",
            },
            request.await_args.kwargs["params"],
        )

    async def test_add_to_diagram_omits_negative_auto_size_dimensions(self) -> None:
        with patch("cameo_mcp.client._request", new=AsyncMock(return_value={"added": True})) as request:
            await client.add_to_diagram(
                diagram_id="dia-1",
                element_id="el-1",
                x=120,
                y=220,
                width=-1,
                height=-1,
            )

        request.assert_awaited_once()
        self.assertEqual(
            {"elementId": "el-1", "x": 120, "y": 220},
            request.await_args.kwargs["json_body"],
        )

    async def test_add_to_diagram_keeps_explicit_dimensions(self) -> None:
        with patch("cameo_mcp.client._request", new=AsyncMock(return_value={"added": True})) as request:
            await client.add_to_diagram(
                diagram_id="dia-1",
                element_id="el-1",
                width=320,
                height=180,
            )

        request.assert_awaited_once()
        self.assertEqual(
            {"elementId": "el-1", "width": 320, "height": 180},
            request.await_args.kwargs["json_body"],
        )

    async def test_add_to_diagram_rejects_mixed_explicit_and_auto_size_dimensions(self) -> None:
        with self.assertRaisesRegex(ValueError, "width and height must both be non-negative"):
            await client.add_to_diagram(
                diagram_id="dia-1",
                element_id="el-1",
                width=-1,
                height=180,
            )

    async def test_create_relationship_omits_optional_connector_fields_when_absent(self) -> None:
        with patch("cameo_mcp.client._request", new=AsyncMock(return_value={"created": True})) as request:
            await client.create_relationship(
                type="Transition",
                source_id="src-1",
                target_id="tgt-1",
            )

        request.assert_awaited_once()
        self.assertEqual(
            {"type": "Transition", "sourceId": "src-1", "targetId": "tgt-1"},
            request.await_args.kwargs["json_body"],
        )

    async def test_create_relationship_includes_connector_fields(self) -> None:
        with patch("cameo_mcp.client._request", new=AsyncMock(return_value={"created": True})) as request:
            await client.create_relationship(
                type="Connector",
                source_id="port-a",
                target_id="port-b",
                owner_id="block-1",
                source_part_with_port_id="part-a",
                target_part_with_port_id="part-b",
            )

        request.assert_awaited_once()
        self.assertEqual(
            {
                "type": "Connector",
                "sourceId": "port-a",
                "targetId": "port-b",
                "ownerId": "block-1",
                "sourcePartWithPortId": "part-a",
                "targetPartWithPortId": "part-b",
            },
            request.await_args.kwargs["json_body"],
        )
