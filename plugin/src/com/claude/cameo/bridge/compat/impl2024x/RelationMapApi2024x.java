package com.claude.cameo.bridge.compat.impl2024x;

import com.claude.cameo.bridge.compat.api.RelationMapApi;
import com.nomagic.magicdraw.core.Application;
import com.nomagic.magicdraw.core.Project;
import com.nomagic.magicdraw.visualization.relationshipmap.RelationshipMapDiagram;
import com.nomagic.magicdraw.visualization.relationshipmap.RelationshipMapManager;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;

import java.util.*;
import java.util.logging.Logger;

/**
 * Cameo 2024x implementation of {@link RelationMapApi}.
 *
 * <p>Uses the {@code com.nomagic.magicdraw.visualization.relationshipmap}
 * package introduced in 2024x.</p>
 */
public class RelationMapApi2024x implements RelationMapApi {

    private static final Logger LOG = Logger.getLogger(RelationMapApi2024x.class.getName());

    @Override
    public String createRelationMap(String rootElementId, int depth,
                                    List<String> relationTypes) {
        Project project = Application.getInstance().getProject();
        Element root = project.getElementByID(rootElementId);
        if (root == null) {
            throw new IllegalArgumentException("Root element not found: " + rootElementId);
        }
        RelationshipMapDiagram diagram =
                RelationshipMapManager.getInstance()
                        .createRelationshipMap(project, root, depth, relationTypes);
        return diagram.getID();
    }

    @Override
    public void applyRelationMapLayout(String diagramId, String layout) {
        Project project = Application.getInstance().getProject();
        RelationshipMapDiagram diagram =
                RelationshipMapManager.getInstance().getById(project, diagramId);
        if (diagram == null) {
            throw new IllegalArgumentException("Relation Map not found: " + diagramId);
        }
        RelationshipMapManager.getInstance().applyLayout(diagram, layout);
    }

    @Override
    public List<Map<String, Object>> getRelationMapElements(String diagramId) {
        Project project = Application.getInstance().getProject();
        RelationshipMapDiagram diagram =
                RelationshipMapManager.getInstance().getById(project, diagramId);
        if (diagram == null) {
            throw new IllegalArgumentException("Relation Map not found: " + diagramId);
        }
        List<Map<String, Object>> results = new ArrayList<>();
        diagram.getDisplayedElements().forEach(el -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id",         el.getID());
            entry.put("humanName",  el.getHumanName());
            entry.put("metaclass",  el.getHumanType());
            results.add(entry);
        });
        return results;
    }
}
