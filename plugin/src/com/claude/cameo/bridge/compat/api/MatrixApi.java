package com.claude.cameo.bridge.compat.api;

import java.util.List;
import java.util.Map;

/**
 * Version-neutral interface for Diagram Table / Matrix operations.
 *
 * <p>This feature is present in both 2022x and 2024x, but method
 * signatures differ between the two versions.  Both implementations
 * adapt to this shared interface.</p>
 */
public interface MatrixApi {

    /**
     * Creates a new dependency matrix between two element sets.
     *
     * @param ownerElementId   element ID of the owning package
     * @param rowScopeId       element ID defining the row scope
     * @param columnScopeId    element ID defining the column scope
     * @param relationMetatype UML/SysML metaclass for the dependency relation
     * @param name             display name for the matrix
     * @return element ID of the created matrix
     */
    String createMatrix(String ownerElementId, String rowScopeId,
                        String columnScopeId, String relationMetatype, String name);

    /**
     * Reads all non-empty cells from a matrix, returning row/column pairs.
     *
     * @param matrixId element ID of the matrix
     * @return list of cell descriptors with "row", "column", and "value" keys
     */
    List<Map<String, String>> readMatrixCells(String matrixId);

    /**
     * Sets a cell value in a matrix.  For dependency matrices this
     * typically creates or deletes a relation between row and column elements.
     *
     * @param matrixId        element ID of the matrix
     * @param rowElementId    element ID of the row element
     * @param columnElementId element ID of the column element
     * @param value           "true" to create the relation, "false" to remove it
     */
    void setMatrixCell(String matrixId, String rowElementId,
                       String columnElementId, String value);

    /**
     * Lists all matrices in the currently open project.
     *
     * @return list of matrix descriptors (id, name, type)
     */
    List<Map<String, String>> listMatrices();
}
