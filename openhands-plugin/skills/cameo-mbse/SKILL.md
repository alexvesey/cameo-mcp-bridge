---
name: cameo-mbse
description: >
  MBSE workflow guidance for OpenHands agents driving Cameo Systems Modeler /
  CATIA Magic via the MCP bridge. Covers tool sequences, OOSEM conformance,
  and 2022x graceful-degradation patterns.
---

# Cameo MBSE Skill Pack

## 1. Session startup sequence

Run these four tools at the start of every session — in order, in full:

```
cameo_probe_bridge        → confirm localhost bridge reachable
cameo_status              → confirm plugin version + Cameo health
cameo_get_capabilities    → check available groups (note if 2022x)
cameo_get_project         → confirm a project is open
```

If `cameo_get_capabilities` shows `cameoVersion = "2022x"`, the groups
`"relationMaps"` and `"simulation"` will be absent from `available`.
Skip those tools and use the 2022x alternatives listed in §5.

---

## 2. Tool quick-reference

### Project / Status
| Tool | When to use |
|---|---|
| `cameo_status` | Health check — always first |
| `cameo_get_capabilities` | Read available groups before version-specific tools |
| `cameo_get_project` | Confirm open project + file path |
| `cameo_save_project` | Persist changes before closing |

### Element CRUD
| Tool | When to use |
|---|---|
| `cameo_query_elements` | Find elements by type, name, or owner |
| `cameo_get_element` | Read properties of a known element ID |
| `cameo_create_element` | Create Block, Requirement, Port, etc. |
| `cameo_modify_element` | Rename or update properties |
| `cameo_delete_element` | Remove an element (wrap in undo session) |

### Diagrams
| Tool | When to use |
|---|---|
| `cameo_list_diagrams` | Enumerate all diagrams |
| `cameo_create_diagram` | New BDD, IBD, Sequence, etc. |
| `cameo_add_to_diagram` | Place element shapes on canvas |
| `cameo_get_diagram_image` | Export PNG for review |
| `cameo_auto_layout` | Run Cameo's auto-layout algorithm |

### Stereotypes & Tagged Values
| Tool | When to use |
|---|---|
| `cameo_apply_stereotype` | Stamp SysML stereotype on an element |
| `cameo_set_tagged_values` | Write tagged-value properties |

### Matrices
| Tool | When to use |
|---|---|
| `cameo_list_matrices` | Enumerate dependency matrices |
| `cameo_create_matrix` | New Satisfy / Allocate / Refine matrix |
| `cameo_get_matrix` | Read full matrix with cell data |

### Relation Maps *(2024x only)*
| Tool | When to use |
|---|---|
| `cameo_list_relation_maps` | List existing relation maps |
| `cameo_create_relation_map` | Build a new graph for a root element |
| `cameo_configure_relation_map` | Set depth, criteria, layout |
| `cameo_render_relation_map` | Refresh and render to PNG |

### Simulation *(2024x only)*
| Tool | When to use |
|---|---|
| `cameo_list_simulation_configurations` | Enumerate SimulationConfig elements |
| `cameo_run_simulation` | Synchronous run with result |
| `cameo_run_simulation_async` | Long-running async run |
| `cameo_get_simulation_result` | Poll or fetch completed result |
| `cameo_terminate_simulation` | Cancel a running job |

### Validation
| Tool | When to use |
|---|---|
| `cameo_run_native_validation` | Run Cameo's active validation suite |
| `cameo_get_validation_result` | Fetch results by run ID |

---

## 3. Common workflow sequences

### 3.1 Create a Block Definition Diagram with two Blocks and an Association

```
1. cameo_query_elements(type="Package") → find target package ID
2. cameo_create_diagram(parentId=<pkg>, type="BDD", name="Vehicle BDD")
3. cameo_create_element(ownerId=<pkg>, type="Block", name="Vehicle")
4. cameo_create_element(ownerId=<pkg>, type="Block", name="PowerSubsystem")
5. cameo_create_relationship(sourceId=<vehicle>, targetId=<power>,
                              type="Association", name="hasPower")
6. cameo_add_to_diagram(diagramId=<bdd>, elementIds=[<vehicle>,<power>])
7. cameo_auto_layout(diagramId=<bdd>)
8. cameo_run_native_validation(scopeId=<pkg>) → check for errors
```

### 3.2 Build a Function Allocation matrix (OOSEM Phase 2)

```
1. cameo_create_matrix(ownerId=<pkg>,
                        rowScopeId=<functions_pkg>,
                        columnScopeId=<blocks_pkg>,
                        type="allocate",
                        name="Function Allocation")
2. cameo_query_elements(type="Activity", ownerId=<functions_pkg>)
   → for each function:
     cameo_get_matrix → read existing cells
     if not yet allocated:
       cameo_modify_element → set tagged value to allocate
3. cameo_run_native_validation → confirm no unallocated functions
```

### 3.3 Requirements coverage check

```
1. cameo_query_elements(type="Requirement") → list all reqs
2. For each requirement:
   cameo_get_element(elementId=<req>) → check for Satisfy links
   if none found → log as gap
3. cameo_create_relationship(type="Satisfy",
                              sourceId=<satisfying_element>,
                              targetId=<requirement>)
4. cameo_run_native_validation → confirm satisfy constraints pass
```

### 3.4 Relation Map analysis *(2024x only — skip to §5.1 on 2022x)*

```
1. cameo_create_relation_map(parentId=<pkg>,
                              contextElementId=<root>,
                              depth=2,
                              dependencyCriteria=["Dependency","Refine"])
2. cameo_configure_relation_map(relationMapId=<rm>,
                                 layout="Hierarchic Group",
                                 legendEnabled=true)
3. cameo_render_relation_map(relationMapId=<rm>) → PNG for review
```

---

## 4. OOSEM Phase 2 conformance rules

When building or reviewing a logical architecture model:

- Every **Function** from the functional architecture must be allocated to
  exactly one logical **Block** (check with the Allocate matrix).
- Every Level-2 **Requirement** must have at least one **Satisfy** link to a
  logical element.
- Every **FlowPort** must participate in at least one **ItemFlow** in the IBD.
- Every **Block** with flow ports must appear on an IBD under its owner.
- Run `cameo_run_native_validation` before marking a phase complete.

---

## 5. 2022x graceful-degradation patterns

When `cameo_get_capabilities` shows `"2022x"`:

### 5.1 Substitute for Relation Maps

```
# Instead of cameo_create_relation_map, manually traverse:
1. cameo_query_elements(type="Block", ownerId=<pkg>)
2. For each block: cameo_get_element(elementId=<id>)
   → read relationships array to find dependencies
3. cameo_create_diagram(type="BDD") and cameo_add_to_diagram
   to visualise the traversal results manually
```

### 5.2 Substitute for Simulation

```
# Instead of cameo_run_simulation, use active validation:
cameo_run_native_validation(scopeId=<sim_config_owner>)
# Parametric constraints run as validation rules in 2022x.
```

### 5.3 Error handling pattern

```python
result = call_tool("cameo_create_relation_map", {...})
if "error" in result and "not available" in result["error"]:
    # Fall back to manual BDD traversal (§5.1)
    ...
elif "error" in result:
    raise RuntimeError(result["error"])
```

---

## 6. Compact-read rules

Follow these in all sessions to avoid timeouts and oversized payloads:

- Always use `view="compact"` when available.
- Prefer `cameo_query_elements` with narrow type filters over full containment dumps.
- Request diagram images only when visual review is explicitly needed.
- For matrices, read row/column counts first; pull full cell data only if needed.
- Validate in focused scopes (a single package) rather than the whole model.
