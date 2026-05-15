package com.claude.cameo.bridge.compat.impl2022x;

import com.claude.cameo.bridge.compat.CameoVersion;
import com.claude.cameo.bridge.compat.NotAvailableInVersionException;
import com.claude.cameo.bridge.compat.api.RelationMapApi;

import java.util.List;
import java.util.Map;

/**
 * Cameo 2022x stub for {@link RelationMapApi}.
 *
 * <p>Relation Map was not available in 2022x.  Every method throws
 * {@link NotAvailableInVersionException} so the HTTP bridge can return
 * a clean 501 response with an LLM-readable explanation.</p>
 */
public class RelationMapApi2022x implements RelationMapApi {

    private static final String ALT =
            "Use a standard Block Definition Diagram to visualise element relationships in 2022x.";

    private final CameoVersion version;

    public RelationMapApi2022x(CameoVersion version) {
        this.version = version;
    }

    @Override
    public String createRelationMap(String rootElementId, int depth,
                                    List<String> relationTypes)
            throws NotAvailableInVersionException {
        throw new NotAvailableInVersionException(
                "create_relation_map", version,
                "Relation Map diagrams are not available in Cameo 2022x.", ALT);
    }

    @Override
    public void applyRelationMapLayout(String diagramId, String layout)
            throws NotAvailableInVersionException {
        throw new NotAvailableInVersionException(
                "apply_relation_map_layout", version,
                "Relation Map layout ('" + layout + "') is not available in Cameo 2022x.", ALT);
    }

    @Override
    public List<Map<String, Object>> getRelationMapElements(String diagramId)
            throws NotAvailableInVersionException {
        throw new NotAvailableInVersionException(
                "get_relation_map_elements", version,
                "Relation Map element queries are not available in Cameo 2022x.", ALT);
    }
}
