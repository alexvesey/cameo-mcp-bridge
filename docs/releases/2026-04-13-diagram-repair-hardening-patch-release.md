# Diagram Repair Hardening Patch Release

Date: 2026-04-13
Release: `2.3.1`

## Summary

This patch release hardens the diagram-repair functionality introduced in `2.3.0`. The main driver was a pair of Java regressions that could block clean plugin compilation on real Cameo 2024x installs, followed by several runtime behaviors that made repair responses look more successful than they really were.

Version `2.3.1` keeps the `2.3.0` feature surface intact while fixing the compile path, softening batch failure behavior, and making per-target receipts more trustworthy.

## Shipped

### 1. Compile fixes

- Restored the missing `RepairDefaults.hiddenLabelKeys` field and constructor wiring used by hidden-label repair
- Wrapped the affected `setPresentationElementProperties(...)` and `resetLabelPositions(...)` calls so installations where those APIs throw checked exceptions compile cleanly

### 2. Safer batch repair behavior

- Repair/configure endpoints now skip unsupported or missing presentation targets and report them as per-target errors
- Supported targets continue processing even when some requested presentations cannot be repaired

### 3. Better candidate filtering

- Allocation-compartment normalization now excludes comment/note presentations instead of selecting them as shape candidates

### 4. More accurate receipts

- Transition/item-flow presentation calls now report `supportedRequests`, `unsupportedRequests`, `status`, and `fullyApplied`
- `repairLabelPositions` now reports `updatedCount` and per-target receipts correctly for processed paths

## Compatibility

- Python MCP server version: `2.3.1`
- Plugin version: `2.3.1`
- API version: `v1`
- Handshake version: `1`

Plugin/server version lockstep is still required.

## Verification

- `python3 -m pytest mcp-server/tests -q` passed with `140 passed`

## Follow-on Work

- Compile the Java plugin against a JDK-enabled Cameo 2024x environment before cutting the next larger feature release
- Add Java-side unit coverage around repair receipts and unsupported-target handling to catch these regressions before release time
