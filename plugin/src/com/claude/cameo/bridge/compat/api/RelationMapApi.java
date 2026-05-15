package com.claude.cameo.bridge.compat.api;

import com.claude.cameo.bridge.compat.NotAvailableInVersionException;

import java.util.List;
import java.util.Map;

/**
 * Version-neutral interface for Relation Map operations.
 *
 * <p><strong>2022x note:</strong> The Relation Map feature was not
 * available in Cameo 2022x.  All implementations for that version
 * throw {@link NotAvailableInVersionException} with a descriptive
 * message and the nearest available alternative.</p>
 */
public interface RelationMapApi {

    /**
     * Creates a Relation Map diagram for the given root element.
     *
     * @param rootElementId  element ID of the focus element
     * @param depth          relationship traversal depth (1–5)
     * @param relationTypes  list of relation metatypes to include
     * @return element ID of the created Relation Map diagram
     * @throws NotAvailableInVersionException on 2022x
     */
    String createRelationMap(String rootElementId, int depth,
                             List<String> relationTypes)
            throws NotAvailableInVersionException;

    /**
     * Applies a layout algorithm to an existing Relation Map.
     *
     * @param diagramId  element ID of the Relation Map diagram
     * @param layout     layout name: "hierarchical", "radial", "organic"
     * @throws NotAvailableInVersionException on 2022x (all layouts)
     */
    void applyRelationMapLayout(String diagramId, String layout)
            throws NotAvailableInVersionException;

    /**
     * Queries the related elements visible on a Relation Map.
     *
     * @param diagramId element ID of the diagram
     * @return list of element descriptor maps
     * @throws NotAvailableInVersionException on 2022x
     */
    List<Map<String, Object>> getRelationMapElements(String diagramId)
            throws NotAvailableInVersionException;
}
