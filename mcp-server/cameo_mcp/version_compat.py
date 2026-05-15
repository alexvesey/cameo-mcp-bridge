"""
Version-compatibility utilities for the Cameo MCP bridge Python server.

At startup the server fetches ``/api/v1/capabilities`` from the Java plugin.
The response now includes two fields added by this fork:

``cameoVersion``
    The Cameo version string detected by the Java plugin
    (e.g. ``"2022x"`` or ``"2024x"``).

``available``
    A flat list of capability-group keys whose underlying Java APIs are
    confirmed present in the running Cameo installation.  On 2022x this
    list omits ``"relationMaps"`` and ``"simulation"``; on 2024x all
    groups are present.

The :func:`require_capability` helper is called at the top of each
version-specific tool handler.  It raises :class:`CapabilityNotAvailable`
when the required group is absent, which the server's exception handler
converts to a structured JSON error that is readable by the LLM.
"""

from __future__ import annotations

import logging
from typing import Optional

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Group keys that are 2024x-only
# These must match the group names in BridgeCapabilities.java exactly.
# ---------------------------------------------------------------------------

RELATION_MAPS_GROUP = "relationMaps"
SIMULATION_GROUP    = "simulation"

V2024X_ONLY_GROUPS: frozenset[str] = frozenset({
    RELATION_MAPS_GROUP,
    SIMULATION_GROUP,
})

# ---------------------------------------------------------------------------
# Runtime state — populated during server startup
# ---------------------------------------------------------------------------

_available_groups: frozenset[str] = frozenset()
_cameo_version:    str | None      = None


def init_from_capabilities(capabilities_response: dict) -> None:
    """
    Initialise the compat state from the ``/api/v1/capabilities`` response.

    Call this once at server startup before registering tool handlers.

    Args:
        capabilities_response: parsed JSON body from the capabilities endpoint.
    """
    global _available_groups, _cameo_version

    raw_available = capabilities_response.get("available", [])
    _available_groups = frozenset(raw_available)
    _cameo_version    = capabilities_response.get("cameoVersion", "unknown")

    v2024x_present = V2024X_ONLY_GROUPS.issubset(_available_groups)
    logger.info(
        "version_compat: Cameo %s — %d capability groups available "
        "(2024x-only groups %s)",
        _cameo_version,
        len(_available_groups),
        "PRESENT" if v2024x_present else "ABSENT (2022x mode)",
    )


def get_cameo_version() -> str | None:
    """Return the detected Cameo version string, or None if not yet initialised."""
    return _cameo_version


def is_available(group: str) -> bool:
    """Return True if *group* is in the available capability list."""
    return group in _available_groups


class CapabilityNotAvailable(Exception):
    """
    Raised by :func:`require_capability` when a tool depends on a
    capability group that is absent on the current Cameo version.

    Attributes:
        group:       the capability group key that was required
        version:     the detected Cameo version string
        alternative: an optional hint for the LLM
    """

    def __init__(self, group: str, version: str | None,
                 alternative: Optional[str] = None) -> None:
        self.group       = group
        self.version     = version or "unknown"
        self.alternative = alternative
        msg = (
            f"Capability group '{group}' is not available in Cameo {self.version}. "
        )
        if alternative:
            msg += f"Alternative: {alternative}"
        super().__init__(msg)

    def to_dict(self) -> dict:
        """Serialises to the structured JSON error format expected by the LLM."""
        d = {
            "error":      str(self),
            "capability": self.group,
            "version":    self.version,
        }
        if self.alternative:
            d["alternative"] = self.alternative
        return d


def require_capability(group: str, alternative: Optional[str] = None) -> None:
    """
    Assert that *group* is available on the current Cameo installation.

    Call this at the start of any tool handler that depends on a
    version-specific API.  If the group is absent a
    :class:`CapabilityNotAvailable` is raised immediately, bypassing the
    HTTP call to the Java plugin.

    Args:
        group:       capability group key (e.g. ``"relationMaps"``).
        alternative: optional suggestion for the LLM if the capability is absent.

    Raises:
        CapabilityNotAvailable: when *group* is not in the available set.
    """
    if not is_available(group):
        raise CapabilityNotAvailable(group, _cameo_version, alternative)


# ---------------------------------------------------------------------------
# Convenience wrappers used by the server for the two 2024x-only groups
# ---------------------------------------------------------------------------

_RELATION_MAP_ALT = (
    "Use a standard Block Definition Diagram together with "
    "cameo_query_elements to explore relationships in 2022x."
)

_SIMULATION_ALT = (
    "Use cameo_run_native_validation to run active-validation constraints "
    "as a partial substitute for parametric simulation in 2022x."
)


def require_relation_maps() -> None:
    """Raise if Relation Map APIs are absent (2022x)."""
    require_capability(RELATION_MAPS_GROUP, _RELATION_MAP_ALT)


def require_simulation() -> None:
    """Raise if Simulation APIs are absent (2022x)."""
    require_capability(SIMULATION_GROUP, _SIMULATION_ALT)
