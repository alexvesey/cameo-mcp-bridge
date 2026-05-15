package com.claude.cameo.bridge.compat.impl2022x;

import com.claude.cameo.bridge.compat.api.MatrixApi;
import com.nomagic.magicdraw.core.Application;
import com.nomagic.magicdraw.core.Project;
import com.nomagic.magicdraw.diagramtable.DiagramTableManager;
import com.nomagic.magicdraw.diagramtable.MDDiagramTable;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;

import java.util.*;
import java.util.logging.Logger;

/**
 * Cameo 2022x implementation of {@link MatrixApi}.
 *
 * <p>Uses the {@code com.nomagic.magicdraw.diagramtable} package which is
 * present in 2022x, but note that some method names differ from 2024x.
 * This class wraps those differences so the HTTP bridge code is unchanged.</p>
 */
public class MatrixApi2022x implements MatrixApi {

    private static final Logger LOG = Logger.getLogger(MatrixApi2022x.class.getName());

    @Override
    public String createMatrix(String ownerElementId, String rowScopeId,
                               String columnScopeId, String relationMetatype, String name) {
        Project project = Application.getInstance().getProject();
        Element owner   = project.getElementByID(ownerElementId);
        Element rowScope    = project.getElementByID(rowScopeId);
        Element columnScope = project.getElementByID(columnScopeId);

        if (owner == null || rowScope == null || columnScope == null) {
            throw new IllegalArgumentException("One or more elements not found for matrix creation.");
        }

        // 2022x API: DiagramTableManager.getInstance().createTable(...)
        MDDiagramTable table = DiagramTableManager.getInstance()
                .createTable(project, name, owner, rowScope, columnScope, relationMetatype);
        return table.getID();
    }

    @Override
    public List<Map<String, String>> readMatrixCells(String matrixId) {
        Project project = Application.getInstance().getProject();
        MDDiagramTable table = DiagramTableManager.getInstance()
                .getTableByID(project, matrixId);
        if (table == null) {
            throw new IllegalArgumentException("Matrix not found: " + matrixId);
        }
        List<Map<String, String>> cells = new ArrayList<>();
        // 2022x iterates cells via getCells() returning a flat list
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
        if (table == null) {
            throw new IllegalArgumentException("Matrix not found: " + matrixId);
        }
        Element row    = project.getElementByID(rowElementId);
        Element column = project.getElementByID(columnElementId);
        if (row == null || column == null) {
            throw new IllegalArgumentException("Row or column element not found.");
        }
        boolean set = Boolean.parseBoolean(value);
        table.setCell(row, column, set);
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
