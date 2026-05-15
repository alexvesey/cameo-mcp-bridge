package com.claude.cameo.bridge.compat.impl2024x;

import com.claude.cameo.bridge.compat.api.MatrixApi;
import com.nomagic.magicdraw.core.Application;
import com.nomagic.magicdraw.core.Project;
import com.nomagic.magicdraw.diagramtable.DiagramTableManager;
import com.nomagic.magicdraw.diagramtable.MDDiagramTable;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;

import java.util.*;

/**
 * Cameo 2024x implementation of {@link MatrixApi}.
 *
 * <p>In 2024x the {@code DiagramTableManager} API is largely the same
 * as in 2022x for standard matrices, but the class path is the same.
 * This implementation captures any 2024x-specific method name differences.</p>
 */
public class MatrixApi2024x implements MatrixApi {

    @Override
    public String createMatrix(String ownerElementId, String rowScopeId,
                               String columnScopeId, String relationMetatype, String name) {
        Project project = Application.getInstance().getProject();
        Element owner   = project.getElementByID(ownerElementId);
        Element rowScope    = project.getElementByID(rowScopeId);
        Element columnScope = project.getElementByID(columnScopeId);

        if (owner == null || rowScope == null || columnScope == null) {
            throw new IllegalArgumentException("One or more elements not found.");
        }
        // 2024x: same DiagramTableManager factory, additional overload accepted
        MDDiagramTable table = DiagramTableManager.getInstance()
                .createDependencyMatrix(project, name, owner,
                        rowScope, columnScope, relationMetatype);
        return table.getID();
    }

    @Override
    public List<Map<String, String>> readMatrixCells(String matrixId) {
        Project project = Application.getInstance().getProject();
        MDDiagramTable table = DiagramTableManager.getInstance()
                .getTableByID(project, matrixId);
        if (table == null) throw new IllegalArgumentException("Matrix not found: " + matrixId);

        List<Map<String, String>> cells = new ArrayList<>();
        table.getCells().forEach(cell -> {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("row",    cell.getRowElement().getID());
            entry.put("column", cell.getColumnElement().getID());
            entry.put("value",  String.valueOf(cell.getValue()));
            cells.add(entry);
        });
        return cells;
    }

    @Override
    public void setMatrixCell(String matrixId, String rowElementId,
                              String columnElementId, String value) {
        Project project = Application.getInstance().getProject();
        MDDiagramTable table = DiagramTableManager.getInstance()
                .getTableByID(project, matrixId);
        if (table == null) throw new IllegalArgumentException("Matrix not found: " + matrixId);

        Element row    = project.getElementByID(rowElementId);
        Element column = project.getElementByID(columnElementId);
        if (row == null || column == null) {
            throw new IllegalArgumentException("Row or column element not found.");
        }
        table.setCell(row, column, Boolean.parseBoolean(value));
    }

    @Override
    public List<Map<String, String>> listMatrices() {
        Project project = Application.getInstance().getProject();
        List<Map<String, String>> result = new ArrayList<>();
        DiagramTableManager.getInstance().getTables(project).forEach(table -> {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("id",   table.getID());
            entry.put("name", table.getName());
            entry.put("type", table.getClass().getSimpleName());
            result.add(entry);
        });
        return result;
    }
}
