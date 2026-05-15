package com.claude.cameo.bridge.compat.impl2024x;

import com.claude.cameo.bridge.compat.api.DiagramApi;
import com.nomagic.magicdraw.core.Application;
import com.nomagic.magicdraw.core.Project;
import com.nomagic.magicdraw.uml.symbols.DiagramPresentationElement;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Package;

import java.io.File;
import java.util.*;
import java.util.logging.Logger;

/**
 * Cameo 2024x implementation of {@link DiagramApi}.
 *
 * <p>Wraps the 2024x API surface which is largely identical to 2022x
 * for core diagram operations, but uses the updated
 * {@code com.dassault_systemes.modeler.foundation} namespace where available.</p>
 */
public class DiagramApi2024x implements DiagramApi {

    private static final Logger LOG = Logger.getLogger(DiagramApi2024x.class.getName());

    @Override
    public List<Map<String, String>> listDiagrams() {
        Project project = Application.getInstance().getProject();
        if (project == null) return Collections.emptyList();

        List<Map<String, String>> result = new ArrayList<>();
        for (DiagramPresentationElement dpe : project.getDiagrams()) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("id",   dpe.getElement().getID());
            entry.put("name", dpe.getName());
            entry.put("type", dpe.getDiagramType().getType());
            Element owner = dpe.getElement().getOwner();
            entry.put("ownerQualifiedName",
                    owner != null ? owner.getHumanName() : "");
            result.add(entry);
        }
        return result;
    }

    @Override
    public void openDiagram(String diagramId) {
        Project project = Application.getInstance().getProject();
        if (project == null) return;
        for (DiagramPresentationElement dpe : project.getDiagrams()) {
            if (dpe.getElement().getID().equals(diagramId)) {
                // 2024x uses the same MDIManager API
                Application.getInstance().getMainFrame()
                        .getDiagramsMDIManager().openDiagram(dpe);
                return;
            }
        }
        LOG.warning("openDiagram: diagram not found: " + diagramId);
    }

    @Override
    public String createDiagram(String ownerElementId, String diagramType, String name) {
        Project project = Application.getInstance().getProject();
        Element owner   = project.getElementByID(ownerElementId);
        if (!(owner instanceof Package pkg)) {
            throw new IllegalArgumentException("Owner must be a Package; got: "
                    + (owner != null ? owner.getHumanType() : "null"));
        }
        // 2024x: same DiagramTypeManager path as 2022x for the core call
        DiagramPresentationElement dpe =
                project.getDiagramTypeManager()
                        .createDiagram(diagramType, pkg, name);
        return dpe.getElement().getID();
    }

    @Override
    public void exportDiagramAsImage(String diagramId, String outputPath) {
        Project project = Application.getInstance().getProject();
        for (DiagramPresentationElement dpe : project.getDiagrams()) {
            if (dpe.getElement().getID().equals(diagramId)) {
                try {
                    com.nomagic.magicdraw.export.image.ImageExporter.export(
                            dpe,
                            com.nomagic.magicdraw.export.image.ImageExporter.PNG,
                            new File(outputPath));
                } catch (Exception e) {
                    throw new RuntimeException("Failed to export diagram " + diagramId, e);
                }
                return;
            }
        }
        throw new IllegalArgumentException("Diagram not found: " + diagramId);
    }

    @Override
    public List<String> getDiagramElements(String diagramId) {
        Project project = Application.getInstance().getProject();
        for (DiagramPresentationElement dpe : project.getDiagrams()) {
            if (dpe.getElement().getID().equals(diagramId)) {
                List<String> ids = new ArrayList<>();
                dpe.getPresentationElements().forEach(pe -> {
                    if (pe.getElement() != null) {
                        ids.add(pe.getElement().getID());
                    }
                });
                return ids;
            }
        }
        throw new IllegalArgumentException("Diagram not found: " + diagramId);
    }
}
