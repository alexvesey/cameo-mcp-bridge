package com.claude.cameo.bridge.compat.api;

import java.util.List;
import java.util.Map;

/**
 * Version-neutral interface for model element CRUD operations.
 */
public interface ElementApi {

    /**
     * Retrieves an element's properties by its unique element ID.
     *
     * @param elementId  Cameo element ID (MD element UUID)
     * @return map of property names to string values
     */
    Map<String, Object> getElement(String elementId);

    /**
     * Creates a new element under the specified owner.
     *
     * @param ownerElementId  element ID of the parent package/block
     * @param metaclass       UML/SysML metaclass name (e.g. "Block", "Requirement")
     * @param name            initial name for the element
     * @param properties      optional additional properties to set on creation
     * @return element ID of the newly created element
     */
    String createElement(String ownerElementId, String metaclass,
                         String name, Map<String, Object> properties);

    /**
     * Updates writable properties on an existing element.
     *
     * @param elementId   element ID
     * @param properties  map of property name → new value
     */
    void updateElement(String elementId, Map<String, Object> properties);

    /**
     * Deletes an element from the model.  This is irreversible within a
     * single transaction; callers should ensure an undo checkpoint is set
     * before calling.
     *
     * @param elementId element ID of the element to delete
     */
    void deleteElement(String elementId);

    /**
     * Finds elements matching the given criteria.
     *
     * @param metaclass  optional metaclass filter (null = all)
     * @param nameGlob   optional name glob pattern (null = all)
     * @param ownerId    optional owner element ID to restrict search scope
     * @return list of matching element descriptors
     */
    List<Map<String, Object>> findElements(String metaclass, String nameGlob, String ownerId);

    /**
     * Returns the full qualified name of an element.
     *
     * @param elementId element ID
     * @return qualified name string (e.g. "Model::System::PowerSubsystem")
     */
    String getQualifiedName(String elementId);
}
