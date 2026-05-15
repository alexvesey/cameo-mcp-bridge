package com.claude.cameo.bridge.compat.impl2022x;

import com.claude.cameo.bridge.compat.api.ElementApi;
import com.nomagic.magicdraw.core.Application;
import com.nomagic.magicdraw.core.Project;
import com.nomagic.magicdraw.openapi.uml.ModelElementsManager;
import com.nomagic.magicdraw.openapi.uml.ReadOnlyElementException;
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.NamedElement;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Package;

import java.util.*;
import java.util.logging.Logger;

/**
 * Cameo 2022x implementation of {@link ElementApi}.
 */
public class ElementApi2022x implements ElementApi {

    private static final Logger LOG = Logger.getLogger(ElementApi2022x.class.getName());

    @Override
    public Map<String, Object> getElement(String elementId) {
        Project project = Application.getInstance().getProject();
        Element el = project.getElementByID(elementId);
        if (el == null) {
            throw new IllegalArgumentException("Element not found: " + elementId);
        }
        return elementToMap(el);
    }

    @Override
    public String createElement(String ownerElementId, String metaclass,
                                String name, Map<String, Object> properties) {
        Project project = Application.getInstance().getProject();
        Element owner   = project.getElementByID(ownerElementId);
        if (owner == null) {
            throw new IllegalArgumentException("Owner element not found: " + ownerElementId);
        }
        Element newEl = project.getModelFactory().createElement(metaclass);
        if (newEl instanceof NamedElement ne) {
            ne.setName(name);
        }
        try {
            ModelElementsManager.getInstance().addElement(newEl, owner);
        } catch (ReadOnlyElementException e) {
            throw new RuntimeException("Cannot add element to read-only owner: " + ownerElementId, e);
        }
        if (properties != null) {
            applyProperties(newEl, properties);
        }
        return newEl.getID();
    }

    @Override
    public void updateElement(String elementId, Map<String, Object> properties) {
        Project project = Application.getInstance().getProject();
        Element el = project.getElementByID(elementId);
        if (el == null) {
            throw new IllegalArgumentException("Element not found: " + elementId);
        }
        applyProperties(el, properties);
    }

    @Override
    public void deleteElement(String elementId) {
        Project project = Application.getInstance().getProject();
        Element el = project.getElementByID(elementId);
        if (el == null) {
            throw new IllegalArgumentException("Element not found: " + elementId);
        }
        try {
            ModelElementsManager.getInstance().removeElement(el);
        } catch (ReadOnlyElementException e) {
            throw new RuntimeException("Cannot delete read-only element: " + elementId, e);
        }
    }

    @Override
    public List<Map<String, Object>> findElements(String metaclass,
                                                   String nameGlob,
                                                   String ownerId) {
        Project project = Application.getInstance().getProject();
        List<Map<String, Object>> results = new ArrayList<>();
        Collection<Element> candidates = metaclass != null
                ? project.getElementsOfType(metaclass, true)
                : project.getAllElements();

        for (Element el : candidates) {
            if (ownerId != null && !isDescendantOf(el, ownerId, project)) continue;
            if (nameGlob != null && el instanceof NamedElement ne) {
                if (!matchesGlob(ne.getName(), nameGlob)) continue;
            }
            results.add(elementToMap(el));
        }
        return results;
    }

    @Override
    public String getQualifiedName(String elementId) {
        Project project = Application.getInstance().getProject();
        Element el = project.getElementByID(elementId);
        if (el == null) {
            throw new IllegalArgumentException("Element not found: " + elementId);
        }
        return el instanceof NamedElement ne ? ne.getQualifiedName() : elementId;
    }

    // ---- helpers ----

    private Map<String, Object> elementToMap(Element el) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id",         el.getID());
        map.put("metaclass",  el.getHumanType());
        map.put("humanName",  el.getHumanName());
        if (el instanceof NamedElement ne) {
            map.put("name",           ne.getName());
            map.put("qualifiedName",  ne.getQualifiedName());
        }
        List<String> stereotypes = new ArrayList<>();
        StereotypesHelper.getStereotypes(el)
                .forEach(s -> stereotypes.add(s.getQualifiedName()));
        map.put("stereotypes", stereotypes);
        Element owner = el.getOwner();
        map.put("ownerId", owner != null ? owner.getID() : null);
        return map;
    }

    private void applyProperties(Element el, Map<String, Object> properties) {
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            switch (entry.getKey()) {
                case "name" -> {
                    if (el instanceof NamedElement ne) {
                        ne.setName(String.valueOf(entry.getValue()));
                    }
                }
                // Additional property setters are added as needed
                default -> LOG.fine("applyProperties: unhandled property '" + entry.getKey() + "'");
            }
        }
    }

    private boolean isDescendantOf(Element el, String ownerId, Project project) {
        Element cursor = el.getOwner();
        while (cursor != null) {
            if (cursor.getID().equals(ownerId)) return true;
            cursor = cursor.getOwner();
        }
        return false;
    }

    private boolean matchesGlob(String name, String glob) {
        if (name == null) return false;
        String regex = ("\\Q" + glob + "\\E")
                .replace("*", "\\E.*\\Q")
                .replace("?", "\\E.\\Q");
        return name.matches(regex);
    }
}
