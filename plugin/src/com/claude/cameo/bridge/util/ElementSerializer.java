package com.claude.cameo.bridge.util;

import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Comment;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.NamedElement;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Property;
import com.nomagic.uml2.ext.magicdraw.mdprofiles.Stereotype;
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper;
import com.nomagic.magicdraw.uml.ClassTypes;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Collection;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Converts Cameo model {@link Element} objects into JSON representations
 * suitable for the bridge REST API.
 * <p>
 * Produces a standard envelope with id, type, name, owner, stereotypes,
 * documentation, and tagged values for any element.
 */
public class ElementSerializer {
    private static final Logger LOG = Logger.getLogger(ElementSerializer.class.getName());

    /**
     * Serialize an element to a JSON object with full detail.
     * <p>
     * The resulting object includes:
     * <ul>
     *   <li>{@code id} - the element ID</li>
     *   <li>{@code type} - the metaclass short name (e.g. "Class", "Package")</li>
     *   <li>{@code humanType} - the human-readable type name</li>
     *   <li>{@code name} - the element name (if it is a NamedElement)</li>
     *   <li>{@code ownerId} - the ID of the owning element</li>
     *   <li>{@code stereotypes} - array of applied stereotype names</li>
     *   <li>{@code documentation} - concatenated body text of owned comments</li>
     *   <li>{@code taggedValues} - object mapping tag name to string value</li>
     *   <li>{@code childCount} - number of directly owned elements</li>
     * </ul>
     *
     * @param element the Cameo model element
     * @return a JsonObject representation
     */
    public static JsonObject toJson(Element element) {
        JsonObject json = new JsonObject();

        // ID
        json.addProperty("id", element.getID());

        // Metaclass type
        try {
            String shortName = ClassTypes.getShortName(element.getClassType());
            json.addProperty("type", shortName != null ? shortName : element.getClassType().getSimpleName());
        } catch (Exception e) {
            json.addProperty("type", element.getHumanType());
        }

        // Human-readable type
        json.addProperty("humanType", element.getHumanType());

        // Name (only for NamedElements)
        if (element instanceof NamedElement) {
            String name = ((NamedElement) element).getName();
            json.addProperty("name", name != null ? name : "");
        }

        // Owner
        Element owner = element.getOwner();
        if (owner != null) {
            json.addProperty("ownerId", owner.getID());
        }

        // Stereotypes
        try {
            List<Stereotype> stereotypes = StereotypesHelper.getStereotypes(element);
            if (stereotypes != null && !stereotypes.isEmpty()) {
                JsonArray stArray = new JsonArray();
                for (Stereotype st : stereotypes) {
                    stArray.add(st.getName());
                }
                json.add("stereotypes", stArray);
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "Could not read stereotypes for " + element.getID(), e);
        }

        // Documentation (from owned comments)
        try {
            Collection<Comment> comments = element.getOwnedComment();
            if (comments != null && !comments.isEmpty()) {
                StringBuilder doc = new StringBuilder();
                for (Comment c : comments) {
                    String body = c.getBody();
                    if (body != null && !body.isEmpty()) {
                        if (doc.length() > 0) {
                            doc.append("\n");
                        }
                        doc.append(body);
                    }
                }
                if (doc.length() > 0) {
                    json.addProperty("documentation", doc.toString());
                }
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "Could not read comments for " + element.getID(), e);
        }

        // Tagged values from all applied stereotypes
        try {
            List<Stereotype> stereotypes = StereotypesHelper.getStereotypes(element);
            if (stereotypes != null && !stereotypes.isEmpty()) {
                JsonObject tags = new JsonObject();
                for (Stereotype st : stereotypes) {
                    List<Property> properties = st.getAttribute();
                    if (properties != null) {
                        for (Property prop : properties) {
                            // Skip extension meta-properties (base_Class, etc.)
                            if (StereotypesHelper.isExtensionProperty(prop)) {
                                continue;
                            }
                            String tagName = prop.getName();
                            if (tagName == null || tagName.isEmpty()) {
                                continue;
                            }
                            try {
                                Object val = StereotypesHelper.getStereotypePropertyFirst(element, st, tagName);
                                if (val != null) {
                                    tags.addProperty(tagName, String.valueOf(val));
                                }
                            } catch (Exception e) {
                                LOG.log(Level.FINE, "Could not read tag " + tagName, e);
                            }
                        }
                    }
                }
                if (tags.size() > 0) {
                    json.add("taggedValues", tags);
                }
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "Could not read tagged values for " + element.getID(), e);
        }

        // Child count
        try {
            Collection<Element> children = element.getOwnedElement();
            json.addProperty("childCount", children != null ? children.size() : 0);
        } catch (Exception e) {
            json.addProperty("childCount", 0);
        }

        return json;
    }

    /**
     * Serialize an element with minimal detail (id, type, name only).
     * Useful for listing children or search results where full detail is excessive.
     *
     * @param element the Cameo model element
     * @return a compact JsonObject representation
     */
    public static JsonObject toJsonCompact(Element element) {
        JsonObject json = new JsonObject();
        json.addProperty("id", element.getID());

        try {
            String shortName = ClassTypes.getShortName(element.getClassType());
            json.addProperty("type", shortName != null ? shortName : element.getClassType().getSimpleName());
        } catch (Exception e) {
            json.addProperty("type", element.getHumanType());
        }

        if (element instanceof NamedElement) {
            String name = ((NamedElement) element).getName();
            json.addProperty("name", name != null ? name : "");
        }

        return json;
    }
}
