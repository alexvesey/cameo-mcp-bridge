package com.claude.cameo.bridge.compat.api;

import java.util.List;
import java.util.Map;

/**
 * Version-neutral interface for diagram operations.
 *
 * <p>Implementations exist for each supported Cameo version in the
 * {@code impl2022x} and {@code impl2024x} packages.  All methods that
 * depend on version-specific APIs are declared here so callers never
 * need to import or cast to a concrete implementation.</p>
 */
public interface DiagramApi {

    /**
     * Lists all diagrams in the currently open project.
     *
     * @return list of diagram descriptors (id, name, type, ownerQualifiedName)
     */
    List<Map<String, String>> listDiagrams();

    /**
     * Opens a diagram by its element ID, bringing it to the foreground.
     *
     * @param diagramId element ID of the diagram
     * @throws com.claude.cameo.bridge.compat.NotAvailableInVersionException if unsupported
     */
    void openDiagram(String diagramId);

    /**
     * Creates a new diagram of the specified type under the given owner element.
     *
     * @param ownerElementId  element ID of the owning package or block
     * @param diagramType     UML/SysML diagram type key (e.g. "SysML Block Definition Diagram")
     * @param name            display name for the new diagram
     * @return element ID of the created diagram
     */
    String createDiagram(String ownerElementId, String diagramType, String name);

    /**
     * Exports a diagram as a PNG image to the given file path.
     *
     * @param diagramId  element ID of the diagram to export
     * @param outputPath absolute file-system path for the exported image
     */
    void exportDiagramAsImage(String diagramId, String outputPath);

    /**
     * Returns the set of elements that are displayed on a given diagram.
     *
     * @param diagramId element ID of the diagram
     * @return list of element IDs present on the diagram canvas
     */
    List<String> getDiagramElements(String diagramId);
}
