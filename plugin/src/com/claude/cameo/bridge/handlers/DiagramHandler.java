package com.claude.cameo.bridge.handlers;

import com.claude.cameo.bridge.HttpBridgeServer;
import com.claude.cameo.bridge.util.EdtDispatcher;
import com.claude.cameo.bridge.util.ElementSerializer;
import com.claude.cameo.bridge.util.JsonHelper;
import com.nomagic.magicdraw.core.Project;
import com.nomagic.magicdraw.export.image.ImageExporter;
import com.nomagic.magicdraw.openapi.uml.ModelElementsManager;
import com.nomagic.magicdraw.openapi.uml.PresentationElementsManager;
import com.nomagic.magicdraw.uml.symbols.DiagramPresentationElement;
import com.nomagic.magicdraw.uml.symbols.PresentationElement;
import com.nomagic.magicdraw.uml.symbols.shapes.ShapeElement;
import com.nomagic.magicdraw.uml.symbols.paths.PathElement;
import com.nomagic.magicdraw.properties.PropertyManager;
import com.nomagic.magicdraw.properties.Property;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Diagram;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.NamedElement;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Namespace;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import javax.imageio.ImageIO;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles diagram REST endpoints.
 */
public class DiagramHandler implements HttpHandler {

    private static final Logger LOG = Logger.getLogger(DiagramHandler.class.getName());
    private static final String PREFIX = "/api/v1/diagrams/";

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();

            if ("OPTIONS".equals(method)) {
                exchange.getResponseHeaders().set("Access-Control-Allow-Methods",
                        "GET, POST, PUT, DELETE, OPTIONS");
                exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            String diagramId = JsonHelper.extractPathParam(exchange, PREFIX);
            String subPath = JsonHelper.extractSubPath(exchange, PREFIX);

            if ("GET".equals(method)) {
                if (path.equals("/api/v1/diagrams")) {
                    handleListDiagrams(exchange);
                } else if (diagramId != null && "image".equals(subPath)) {
                    handleExportImage(exchange, diagramId);
                } else if (diagramId != null && subPath != null
                        && subPath.startsWith("shapes/") && subPath.endsWith("/properties")) {
                    String peId = subPath.substring("shapes/".length(),
                            subPath.length() - "/properties".length());
                    handleGetShapeProperties(exchange, diagramId, peId);
                } else if (diagramId != null && "shapes".equals(subPath)) {
                    handleListShapes(exchange, diagramId);
                } else if (diagramId != null && subPath == null) {
                    handleGetDiagram(exchange, diagramId);
                } else {
                    HttpBridgeServer.sendError(exchange, 404, "NOT_FOUND",
                            "Unknown endpoint: " + path);
                }
            } else if ("POST".equals(method)) {
                if (path.equals("/api/v1/diagrams")) {
                    handleCreateDiagram(exchange);
                } else if (diagramId != null && "elements".equals(subPath)) {
                    handleAddElement(exchange, diagramId);
                } else if (diagramId != null && "layout".equals(subPath)) {
                    handleLayout(exchange, diagramId);
                } else if (diagramId != null && "paths".equals(subPath)) {
                    handleAddPaths(exchange, diagramId);
                } else {
                    HttpBridgeServer.sendError(exchange, 404, "NOT_FOUND",
                            "Unknown endpoint: " + path);
                }
            } else if ("PUT".equals(method)) {
                if (diagramId != null && "shapes".equals(subPath)) {
                    handleMoveResizeShapes(exchange, diagramId);
                } else if (diagramId != null && "shapes/reparent".equals(subPath)) {
                    handleReparentShapes(exchange, diagramId);
                } else if (diagramId != null && "paths/route".equals(subPath)) {
                    handleRoutePaths(exchange, diagramId);
                } else if (diagramId != null && subPath != null
                        && subPath.startsWith("shapes/") && subPath.endsWith("/properties")) {
                    // Extract peId from subPath: shapes/{peId}/properties
                    String peId = subPath.substring("shapes/".length(),
                            subPath.length() - "/properties".length());
                    handleSetShapeProperties(exchange, diagramId, peId);
                } else if (diagramId != null && subPath != null
                        && subPath.startsWith("shapes/") && subPath.endsWith("/compartments")) {
                    String peId = subPath.substring("shapes/".length(),
                            subPath.length() - "/compartments".length());
                    handleSetShapeCompartments(exchange, diagramId, peId);
                } else if (diagramId != null && "presentation/transition-labels".equals(subPath)) {
                    handleConfigureTransitionLabelPresentation(exchange, diagramId);
                } else if (diagramId != null && "presentation/item-flow-labels".equals(subPath)) {
                    handleConfigureItemFlowLabelPresentation(exchange, diagramId);
                } else if (diagramId != null && "presentation/allocation-compartments".equals(subPath)) {
                    handleConfigureAllocationCompartmentPresentation(exchange, diagramId);
                } else if (diagramId != null && "repair/hidden-labels".equals(subPath)) {
                    handleRepairHiddenLabels(exchange, diagramId);
                } else if (diagramId != null && "repair/label-positions".equals(subPath)) {
                    handleRepairLabelPositions(exchange, diagramId);
                } else if (diagramId != null && "repair/conveyed-item-labels".equals(subPath)) {
                    handleForceConveyedItemLabels(exchange, diagramId);
                } else if (diagramId != null && "repair/compartment-presets".equals(subPath)) {
                    handleNormalizeCompartmentPresets(exchange, diagramId);
                } else {
                    HttpBridgeServer.sendError(exchange, 404, "NOT_FOUND",
                            "Unknown endpoint: " + path);
                }
            } else if ("DELETE".equals(method)) {
                if (diagramId != null && "shapes".equals(subPath)) {
                    handleDeleteShapes(exchange, diagramId);
                } else {
                    HttpBridgeServer.sendError(exchange, 404, "NOT_FOUND",
                            "Unknown endpoint: " + path);
                }
            } else {
                HttpBridgeServer.sendError(exchange, 405, "METHOD_NOT_ALLOWED",
                        "Method not supported: " + method);
            }
        } catch (IllegalArgumentException e) {
            HttpBridgeServer.sendError(exchange, 404, "NOT_FOUND", e.getMessage());
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Error in DiagramHandler", e);
            HttpBridgeServer.sendError(exchange, 500, "INTERNAL_ERROR", e.getMessage());
        }
    }

    private void handleListDiagrams(HttpExchange exchange) throws Exception {
        JsonObject result = EdtDispatcher.read(project -> {
            Collection<DiagramPresentationElement> diagrams = project.getDiagrams();
            JsonArray array = new JsonArray();

            if (diagrams != null) {
                for (DiagramPresentationElement dpe : diagrams) {
                    JsonObject diagramJson = new JsonObject();
                    Diagram diagram = dpe.getDiagram();
                    diagramJson.addProperty("id", diagram.getID());
                    diagramJson.addProperty("name", dpe.getName() != null ? dpe.getName() : "");
                    diagramJson.addProperty("type", dpe.getDiagramType() != null
                            ? dpe.getDiagramType().getType() : "");

                    Element owner = diagram.getOwner();
                    if (owner != null) {
                        diagramJson.addProperty("ownerId", owner.getID());
                        if (owner instanceof NamedElement) {
                            diagramJson.addProperty("ownerName",
                                    ((NamedElement) owner).getName());
                        }
                    }
                    array.add(diagramJson);
                }
            }

            JsonObject response = new JsonObject();
            response.addProperty("count", array.size());
            response.add("diagrams", array);
            return response;
        });

        HttpBridgeServer.sendJson(exchange, 200, result);
    }

    private void handleGetDiagram(HttpExchange exchange, String diagramId) throws Exception {
        JsonObject result = EdtDispatcher.read(project -> {
            DiagramPresentationElement dpe = findDiagramById(project, diagramId);

            JsonObject response = new JsonObject();
            Diagram diagram = dpe.getDiagram();
            response.addProperty("id", diagram.getID());
            response.addProperty("name", dpe.getName() != null ? dpe.getName() : "");
            response.addProperty("type", dpe.getDiagramType() != null
                    ? dpe.getDiagramType().getType() : "");

            Element owner = diagram.getOwner();
            if (owner != null) {
                response.addProperty("ownerId", owner.getID());
                if (owner instanceof NamedElement) {
                    response.addProperty("ownerName", ((NamedElement) owner).getName());
                }
            }

            dpe.ensureLoaded();
            Collection<Element> usedElements = dpe.getUsedModelElements(false);
            JsonArray elementsArray = new JsonArray();
            if (usedElements != null) {
                for (Element el : usedElements) {
                    elementsArray.add(ElementSerializer.toJsonCompact(el));
                }
            }
            response.addProperty("elementCount", elementsArray.size());
            response.add("elements", elementsArray);

            List<PresentationElement> presentationElements = dpe.getPresentationElements();
            JsonArray shapesArray = new JsonArray();
            collectShapes(presentationElements, shapesArray, null);
            response.addProperty("shapeCount", shapesArray.size());
            response.add("shapes", shapesArray);

            return response;
        });

        HttpBridgeServer.sendJson(exchange, 200, result);
    }

    private void handleExportImage(HttpExchange exchange, String diagramId) throws Exception {
        JsonObject result = EdtDispatcher.read(project -> {
            DiagramPresentationElement dpe = findDiagramById(project, diagramId);
            dpe.ensureLoaded();

            BufferedImage image = ImageExporter.export(dpe, false);
            if (image == null) {
                throw new IllegalStateException(
                        "ImageExporter returned null for diagram: " + diagramId);
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", baos);
            String base64 = Base64.getEncoder().encodeToString(baos.toByteArray());

            JsonObject response = new JsonObject();
            response.addProperty("id", diagramId);
            response.addProperty("name", dpe.getName() != null ? dpe.getName() : "");
            response.addProperty("format", "png");
            response.addProperty("width", image.getWidth());
            response.addProperty("height", image.getHeight());
            response.addProperty("image", base64);
            return response;
        });

        HttpBridgeServer.sendJson(exchange, 200, result);
    }

    private void handleCreateDiagram(HttpExchange exchange) throws Exception {
        JsonObject body = JsonHelper.parseBody(exchange);

        if (!body.has("type") || body.get("type").getAsString().isEmpty()) {
            HttpBridgeServer.sendError(exchange, 400, "MISSING_PARAM",
                    "Request body must include a type (diagram type string)");
            return;
        }

        String diagramType = body.get("type").getAsString();
        String name = body.has("name") ? body.get("name").getAsString() : null;
        String parentId = body.has("parentId") ? body.get("parentId").getAsString() : null;

        JsonObject result = EdtDispatcher.write("MCP Bridge: Create Diagram", project -> {
            Namespace parent;
            if (parentId != null && !parentId.isEmpty()) {
                Element parentElement = (Element) project.getElementByID(parentId);
                if (parentElement == null) {
                    throw new IllegalArgumentException("Parent element not found: " + parentId);
                }
                if (!(parentElement instanceof Namespace)) {
                    throw new IllegalArgumentException(
                            "Parent element is not a Namespace: " + parentId
                                    + " (type: " + parentElement.getHumanType() + ")");
                }
                parent = (Namespace) parentElement;
            } else {
                parent = project.getPrimaryModel();
                if (parent == null) {
                    throw new IllegalStateException("No primary model found in project");
                }
            }

            String resolvedType = resolveDiagramType(diagramType);

            ModelElementsManager mem = ModelElementsManager.getInstance();
            Diagram diagram = mem.createDiagram(resolvedType, parent);

            if (diagram == null) {
                throw new IllegalStateException(
                        "Failed to create diagram of type: " + resolvedType);
            }

            if (name != null && !name.isEmpty()) {
                diagram.setName(name);
            }

            JsonObject response = new JsonObject();
            response.addProperty("id", diagram.getID());
            response.addProperty("name", diagram.getName() != null ? diagram.getName() : "");
            response.addProperty("type", resolvedType);
            response.addProperty("parentId", parent.getID());
            return response;
        });

        HttpBridgeServer.sendJson(exchange, 201, result);
    }

    private void handleAddElement(HttpExchange exchange, String diagramId) throws Exception {
        JsonObject body = JsonHelper.parseBody(exchange);

        if (!body.has("elementId") || body.get("elementId").getAsString().isEmpty()) {
            HttpBridgeServer.sendError(exchange, 400, "MISSING_PARAM",
                    "Request body must include elementId");
            return;
        }

        String elementId = body.get("elementId").getAsString();
        int x = body.has("x") ? body.get("x").getAsInt() : 100;
        int y = body.has("y") ? body.get("y").getAsInt() : 100;
        String containerPeId = JsonHelper.optionalString(body, "containerPresentationId");
        boolean hasWidth = body.has("width");
        boolean hasHeight = body.has("height");

        if (hasWidth != hasHeight) {
            HttpBridgeServer.sendError(exchange, 400, "MISSING_PARAM",
                    "Request body must include both width and height, or neither");
            return;
        }

        JsonObject result = EdtDispatcher.write("MCP Bridge: Add Element to Diagram", project -> {
            DiagramPresentationElement dpe = findDiagramById(project, diagramId);
            dpe.ensureLoaded();

            Element element = (Element) project.getElementByID(elementId);
            if (element == null) {
                throw new IllegalArgumentException("Element not found: " + elementId);
            }

            PresentationElementsManager pem = PresentationElementsManager.getInstance();
            PresentationElement shapeParent;
            if (containerPeId != null) {
                shapeParent = findPresentationElement(dpe.getPresentationElements(), containerPeId);
                if (shapeParent == null) {
                    throw new IllegalArgumentException("Container shape not found: " + containerPeId);
                }
            } else {
                shapeParent = dpe;
            }
            ShapeElement shape = pem.createShapeElement(element, shapeParent, true, new Point(x, y));

            if (shape == null) {
                throw new IllegalStateException(
                        "Failed to create shape for element: " + elementId);
            }

            if (hasWidth && hasHeight) {
                int width = body.get("width").getAsInt();
                int height = body.get("height").getAsInt();
                pem.reshapeShapeElement(shape, new Rectangle(x, y, width, height));
            }

            JsonObject response = new JsonObject();
            response.addProperty("diagramId", diagramId);
            response.addProperty("elementId", elementId);
            response.addProperty("x", x);
            response.addProperty("y", y);
            try {
                Rectangle bounds = shape.getBounds();
                if (bounds != null) {
                    response.addProperty("width", bounds.width);
                    response.addProperty("height", bounds.height);
                }
            } catch (Exception e) {
                // Bounds may not be available for all shape types
            }
            response.addProperty("added", true);
            response.addProperty("presentationId", shape.getID());

            JsonObject receipt = new JsonObject();
            receipt.addProperty("operation", "addShape");
            receipt.addProperty("diagramId", diagramId);
            receipt.addProperty("elementId", elementId);
            receipt.addProperty("presentationId", shape.getID());
            if (containerPeId != null) {
                receipt.addProperty("containerPresentationId", containerPeId);
            }
            receipt.addProperty("x", x);
            receipt.addProperty("y", y);
            if (hasWidth && hasHeight) {
                receipt.addProperty("width", body.get("width").getAsInt());
                receipt.addProperty("height", body.get("height").getAsInt());
            }
            receipt.addProperty("status", "created");
            response.add("receipt", receipt);
            return response;
        });

        HttpBridgeServer.sendJson(exchange, 200, result);
    }

    private void handleLayout(HttpExchange exchange, String diagramId) throws Exception {
        JsonObject result = EdtDispatcher.write("MCP Bridge: Auto-Layout Diagram", project -> {
            DiagramPresentationElement dpe = findDiagramById(project, diagramId);
            dpe.ensureLoaded();

            boolean success = dpe.layout(true);

            JsonObject response = new JsonObject();
            response.addProperty("diagramId", diagramId);
            response.addProperty("name", dpe.getName() != null ? dpe.getName() : "");
            response.addProperty("layoutApplied", success);
            return response;
        });

        HttpBridgeServer.sendJson(exchange, 200, result);
    }

    // ── Shape Management Endpoints ──────────────────────────────────────────

    private void handleListShapes(HttpExchange exchange, String diagramId) throws Exception {
        JsonObject result = EdtDispatcher.read(project -> {
            DiagramPresentationElement dpe = findDiagramById(project, diagramId);
            dpe.ensureLoaded();

            JsonArray shapesArray = new JsonArray();
            List<PresentationElement> presentationElements = dpe.getPresentationElements();
            collectShapes(presentationElements, shapesArray, null);

            JsonObject response = new JsonObject();
            response.addProperty("diagramId", diagramId);
            response.addProperty("count", shapesArray.size());
            response.addProperty("shapeCount", shapesArray.size());
            response.add("shapes", shapesArray);
            return response;
        });

        HttpBridgeServer.sendJson(exchange, 200, result);
    }

    private void handleGetShapeProperties(HttpExchange exchange, String diagramId, String peId)
            throws Exception {
        JsonObject result = EdtDispatcher.read(project -> {
            DiagramPresentationElement dpe = findDiagramById(project, diagramId);
            dpe.ensureLoaded();

            PresentationElement target = findPresentationElement(dpe.getPresentationElements(), peId);
            if (target == null) {
                throw new IllegalArgumentException("Presentation element not found: " + peId);
            }

            PropertyManager pm = target.getPropertyManager();
            JsonArray propertiesArray = new JsonArray();
            JsonObject propertiesObject = new JsonObject();
            JsonObject compartments = new JsonObject();

            @SuppressWarnings("unchecked")
            List<Property> properties = pm.getProperties();
            for (Property property : properties) {
                JsonObject entry = new JsonObject();
                entry.addProperty("name", property.getName());
                if (property.getID() != null) {
                    entry.addProperty("id", property.getID());
                }
                if (property.getClassType() != null) {
                    entry.addProperty("classType", property.getClassType());
                }
                addJsonValue(entry, "value", property.getValue());
                propertiesArray.add(entry);
                addJsonValue(propertiesObject, property.getName(), property.getValue());

                String compartmentKey = canonicalCompartmentKey(property.getName());
                if (compartmentKey != null) {
                    addJsonValue(compartments, compartmentKey, property.getValue());
                }
            }

            JsonObject response = new JsonObject();
            response.addProperty("diagramId", diagramId);
            response.addProperty("presentationId", peId);
            response.add("properties", propertiesObject);
            response.add("propertyList", propertiesArray);
            response.add("compartments", compartments);
            response.addProperty("resultCount", propertiesArray.size());
            return response;
        });

        HttpBridgeServer.sendJson(exchange, 200, result);
    }

    /**
     * Recursively collect presentation elements into a flat array,
     * tagging each with its parentPresentationId for hierarchy context.
     */
    private void collectShapes(List<PresentationElement> elements, JsonArray shapesArray,
            String parentPeId) {
        if (elements == null) return;
        for (PresentationElement pe : elements) {
            try {
                JsonObject shapeJson = new JsonObject();
                shapeJson.addProperty("presentationId", pe.getID());
                shapeJson.addProperty("shapeType", pe.getClass().getSimpleName());

                if (parentPeId != null) {
                    shapeJson.addProperty("parentPresentationId", parentPeId);
                }

                Element modelElement = pe.getElement();
                if (modelElement != null) {
                    shapeJson.addProperty("elementId", modelElement.getID());
                    if (modelElement instanceof NamedElement) {
                        shapeJson.addProperty("elementName",
                                ((NamedElement) modelElement).getName());
                    }
                    shapeJson.addProperty("elementType",
                            modelElement.getHumanType());
                }

                try {
                    Rectangle bounds = pe.getBounds();
                    if (bounds != null) {
                        JsonObject boundsJson = new JsonObject();
                        boundsJson.addProperty("x", bounds.x);
                        boundsJson.addProperty("y", bounds.y);
                        boundsJson.addProperty("width", bounds.width);
                        boundsJson.addProperty("height", bounds.height);
                        shapeJson.add("bounds", boundsJson);
                    }
                } catch (Exception e) {
                    // Some presentation elements do not have rectangular bounds
                }

                // Count children for context
                List<PresentationElement> children = pe.getPresentationElements();
                int childCount = (children != null) ? children.size() : 0;
                if (childCount > 0) {
                    shapeJson.addProperty("childCount", childCount);
                }

                shapesArray.add(shapeJson);

                // Recurse into children
                if (childCount > 0) {
                    collectShapes(children, shapesArray, pe.getID());
                }
            } catch (Exception e) {
                LOG.log(Level.FINE, "Error reading presentation element", e);
            }
        }
    }

    private void handleMoveResizeShapes(HttpExchange exchange, String diagramId) throws Exception {
        JsonObject body = JsonHelper.parseBody(exchange);

        if (!body.has("shapes") || !body.get("shapes").isJsonArray()) {
            HttpBridgeServer.sendError(exchange, 400, "MISSING_PARAM",
                    "Request body must include a 'shapes' array");
            return;
        }

        JsonArray shapesInput = body.getAsJsonArray("shapes");

        JsonObject result = EdtDispatcher.write("MCP Bridge: Move/Resize Shapes", project -> {
            DiagramPresentationElement dpe = findDiagramById(project, diagramId);
            dpe.ensureLoaded();

            List<PresentationElement> allPEs = dpe.getPresentationElements();
            PresentationElementsManager pem = PresentationElementsManager.getInstance();
            JsonArray results = new JsonArray();

            for (JsonElement item : shapesInput) {
                JsonObject shapeReq = item.getAsJsonObject();
                if (!shapeReq.has("presentationId")
                        || !shapeReq.has("x")
                        || !shapeReq.has("y")
                        || !shapeReq.has("width")
                        || !shapeReq.has("height")) {
                    JsonObject err = new JsonObject();
                    err.addProperty("presentationId",
                            JsonHelper.optionalString(shapeReq, "presentationId"));
                    err.addProperty("error",
                            "Request item must include presentationId, x, y, width, and height");
                    results.add(err);
                    continue;
                }

                String presentationId = shapeReq.get("presentationId").getAsString();
                int x = shapeReq.get("x").getAsInt();
                int y = shapeReq.get("y").getAsInt();
                int width = shapeReq.get("width").getAsInt();
                int height = shapeReq.get("height").getAsInt();

                PresentationElement target = findPresentationElement(allPEs, presentationId);
                if (target == null) {
                    JsonObject err = new JsonObject();
                    err.addProperty("presentationId", presentationId);
                    err.addProperty("error", "Presentation element not found");
                    results.add(err);
                    continue;
                }

                if (!(target instanceof ShapeElement)) {
                    JsonObject err = new JsonObject();
                    err.addProperty("presentationId", presentationId);
                    err.addProperty("error", "Element is not a shape (cannot reshape paths)");
                    results.add(err);
                    continue;
                }

                pem.reshapeShapeElement((ShapeElement) target,
                        new Rectangle(x, y, width, height));

                JsonObject ok = new JsonObject();
                ok.addProperty("presentationId", presentationId);
                ok.addProperty("reshaped", true);
                JsonObject receipt = new JsonObject();
                receipt.addProperty("operation", "reshapeShape");
                receipt.addProperty("diagramId", diagramId);
                receipt.addProperty("presentationId", presentationId);
                receipt.addProperty("x", x);
                receipt.addProperty("y", y);
                receipt.addProperty("width", width);
                receipt.addProperty("height", height);
                receipt.addProperty("status", "applied");
                ok.add("receipt", receipt);
                results.add(ok);
            }

            JsonObject response = new JsonObject();
            response.addProperty("diagramId", diagramId);
            response.addProperty("resultCount", results.size());
            response.add("results", results);
            return response;
        });

        HttpBridgeServer.sendJson(exchange, 200, result);
    }

    private void handleDeleteShapes(HttpExchange exchange, String diagramId) throws Exception {
        JsonObject body = JsonHelper.parseBody(exchange);

        if (!body.has("presentationIds") || !body.get("presentationIds").isJsonArray()) {
            HttpBridgeServer.sendError(exchange, 400, "MISSING_PARAM",
                    "Request body must include a 'presentationIds' array");
            return;
        }

        JsonArray idsInput = body.getAsJsonArray("presentationIds");

        JsonObject result = EdtDispatcher.write("MCP Bridge: Delete Presentation Elements", project -> {
            DiagramPresentationElement dpe = findDiagramById(project, diagramId);
            dpe.ensureLoaded();

            List<PresentationElement> allPEs = dpe.getPresentationElements();
            PresentationElementsManager pem = PresentationElementsManager.getInstance();
            JsonArray results = new JsonArray();

            for (JsonElement item : idsInput) {
                String peId = item.getAsString();
                PresentationElement target = findPresentationElement(allPEs, peId);

                JsonObject entry = new JsonObject();
                entry.addProperty("presentationId", peId);

                if (target == null) {
                    entry.addProperty("error", "Presentation element not found");
                } else {
                    pem.deletePresentationElement(target);
                    entry.addProperty("deleted", true);
                }
                results.add(entry);
            }

            JsonObject response = new JsonObject();
            response.addProperty("diagramId", diagramId);
            response.addProperty("resultCount", results.size());
            response.add("results", results);
            return response;
        });

        HttpBridgeServer.sendJson(exchange, 200, result);
    }

    private void handleReparentShapes(HttpExchange exchange, String diagramId) throws Exception {
        JsonObject body = JsonHelper.parseBody(exchange);
        if (!body.has("reparentings") || !body.get("reparentings").isJsonArray()) {
            HttpBridgeServer.sendError(exchange, 400, "MISSING_PARAM",
                    "Request body must include a 'reparentings' array");
            return;
        }

        JsonArray reparentings = body.getAsJsonArray("reparentings");
        JsonObject result = EdtDispatcher.write("MCP Bridge: Reparent Shapes", project -> {
            DiagramPresentationElement dpe = findDiagramById(project, diagramId);
            dpe.ensureLoaded();

            List<PresentationElement> allPEs = dpe.getPresentationElements();
            JsonArray results = new JsonArray();
            Method setParentMethod = PresentationElement.class.getMethod(
                    "setParent", PresentationElement.class);

            for (JsonElement item : reparentings) {
                JsonObject req = item.getAsJsonObject();
                String presentationId = JsonHelper.optionalString(req, "presentationId");
                String parentPresentationId = JsonHelper.optionalString(req, "parentPresentationId");

                JsonObject entry = new JsonObject();
                entry.addProperty("presentationId", presentationId);
                entry.addProperty("parentPresentationId", parentPresentationId);

                if (presentationId == null || parentPresentationId == null) {
                    entry.addProperty("error",
                            "Each reparenting must include presentationId and parentPresentationId");
                    results.add(entry);
                    continue;
                }

                PresentationElement child = findPresentationElement(allPEs, presentationId);
                PresentationElement parent = findPresentationElement(allPEs, parentPresentationId);
                if (child == null) {
                    entry.addProperty("error", "Presentation element not found: " + presentationId);
                    results.add(entry);
                    continue;
                }
                if (parent == null) {
                    entry.addProperty("error",
                            "Parent presentation element not found: " + parentPresentationId);
                    results.add(entry);
                    continue;
                }

                setParentMethod.invoke(child, parent);
                entry.addProperty("reparented", true);
                JsonObject receipt = new JsonObject();
                receipt.addProperty("operation", "reparentShape");
                receipt.addProperty("diagramId", diagramId);
                receipt.addProperty("presentationId", presentationId);
                receipt.addProperty("parentPresentationId", parentPresentationId);
                receipt.addProperty("status", "applied");
                entry.add("receipt", receipt);
                results.add(entry);
            }

            JsonObject response = new JsonObject();
            response.addProperty("diagramId", diagramId);
            response.addProperty("resultCount", results.size());
            response.add("results", results);
            return response;
        });

        HttpBridgeServer.sendJson(exchange, 200, result);
    }

    private void handleAddPaths(HttpExchange exchange, String diagramId) throws Exception {
        JsonObject body = JsonHelper.parseBody(exchange);

        if (!body.has("paths") || !body.get("paths").isJsonArray()) {
            HttpBridgeServer.sendError(exchange, 400, "MISSING_PARAM",
                    "Request body must include a 'paths' array");
            return;
        }

        JsonArray pathsInput = body.getAsJsonArray("paths");

        JsonObject result = EdtDispatcher.write("MCP Bridge: Add Relationship Paths", project -> {
            DiagramPresentationElement dpe = findDiagramById(project, diagramId);
            dpe.ensureLoaded();

            List<PresentationElement> allPEs = dpe.getPresentationElements();
            PresentationElementsManager pem = PresentationElementsManager.getInstance();
            JsonArray results = new JsonArray();

            for (JsonElement item : pathsInput) {
                JsonObject pathReq = item.getAsJsonObject();
                if (!pathReq.has("relationshipId")
                        || !pathReq.has("sourceShapeId")
                        || !pathReq.has("targetShapeId")) {
                    JsonObject err = new JsonObject();
                    err.addProperty("error",
                            "Request item must include relationshipId, sourceShapeId, and targetShapeId");
                    results.add(err);
                    continue;
                }

                String relationshipId = pathReq.get("relationshipId").getAsString();
                String sourceShapeId = pathReq.get("sourceShapeId").getAsString();
                String targetShapeId = pathReq.get("targetShapeId").getAsString();

                JsonObject entry = new JsonObject();
                entry.addProperty("relationshipId", relationshipId);

                Element relationship = (Element) project.getElementByID(relationshipId);
                if (relationship == null) {
                    entry.addProperty("error",
                            "Relationship model element not found: " + relationshipId);
                    results.add(entry);
                    continue;
                }

                PresentationElement sourcePE = findPresentationElement(allPEs, sourceShapeId);
                if (sourcePE == null) {
                    entry.addProperty("error",
                            "Source presentation element not found: " + sourceShapeId);
                    results.add(entry);
                    continue;
                }

                PresentationElement targetPE = findPresentationElement(allPEs, targetShapeId);
                if (targetPE == null) {
                    entry.addProperty("error",
                            "Target presentation element not found: " + targetShapeId);
                    results.add(entry);
                    continue;
                }

                PathElement pathElement = pem.createPathElement(relationship, sourcePE, targetPE);
                if (pathElement != null) {
                    entry.addProperty("presentationId", pathElement.getID());
                    entry.addProperty("created", true);
                } else {
                    entry.addProperty("error", "Failed to create path element");
                }
                results.add(entry);
            }

            JsonObject response = new JsonObject();
            response.addProperty("diagramId", diagramId);
            response.addProperty("resultCount", results.size());
            response.add("results", results);
            return response;
        });

        HttpBridgeServer.sendJson(exchange, 200, result);
    }

    private void handleSetShapeProperties(HttpExchange exchange, String diagramId, String peId)
            throws Exception {
        JsonObject body = JsonHelper.parseBody(exchange);

        if (!body.has("properties") || !body.get("properties").isJsonObject()) {
            HttpBridgeServer.sendError(exchange, 400, "MISSING_PARAM",
                    "Request body must include a 'properties' object");
            return;
        }

        JsonObject propsInput = body.getAsJsonObject("properties");

        JsonObject result = EdtDispatcher.write("MCP Bridge: Set Shape Properties", project -> {
            DiagramPresentationElement dpe = findDiagramById(project, diagramId);
            dpe.ensureLoaded();

            List<PresentationElement> allPEs = dpe.getPresentationElements();
            PresentationElement target = findPresentationElement(allPEs, peId);
            if (target == null) {
                throw new IllegalArgumentException(
                        "Presentation element not found: " + peId);
            }

            // MUST clone the PropertyManager -- Cameo rejects reuse of the old one
            PropertyManager pm = target.getPropertyManager().clone();
            @SuppressWarnings("unchecked")
            List<Property> properties = pm.getProperties();
            JsonArray updated = new JsonArray();

            for (var propEntry : propsInput.entrySet()) {
                String propName = propEntry.getKey();
                boolean found = false;
                for (Property p : properties) {
                    if (propName.equals(p.getName())) {
                        JsonElement val = propEntry.getValue();
                        if (val.isJsonPrimitive()) {
                            if (val.getAsJsonPrimitive().isBoolean()) {
                                p.setValue(val.getAsBoolean());
                            } else if (val.getAsJsonPrimitive().isNumber()) {
                                p.setValue(val.getAsInt());
                            } else {
                                p.setValue(val.getAsString());
                            }
                        } else if (val.isJsonNull()) {
                            p.setValue("");
                        } else {
                            // JSON objects/arrays: convert to string representation
                            p.setValue(val.toString());
                        }
                        JsonObject u = new JsonObject();
                        u.addProperty("name", propName);
                        u.addProperty("set", true);
                        updated.add(u);
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    JsonObject u = new JsonObject();
                    u.addProperty("name", propName);
                    u.addProperty("error", "Property not found on this shape");
                    updated.add(u);
                }
            }

            PresentationElementsManager pem = PresentationElementsManager.getInstance();
            pem.setPresentationElementProperties(target, pm);

            JsonObject response = new JsonObject();
            response.addProperty("diagramId", diagramId);
            response.addProperty("presentationId", peId);
            response.add("properties", updated);
            response.addProperty("resultCount", updated.size());
            JsonObject receipt = new JsonObject();
            receipt.addProperty("operation", "setShapeProperties");
            receipt.addProperty("diagramId", diagramId);
            receipt.addProperty("presentationId", peId);
            receipt.addProperty("status", "applied");
            response.add("receipt", receipt);
            return response;
        });

        HttpBridgeServer.sendJson(exchange, 200, result);
    }

    private void handleSetShapeCompartments(HttpExchange exchange, String diagramId, String peId)
            throws Exception {
        JsonObject body = JsonHelper.parseBody(exchange);
        if (!body.has("compartments") || !body.get("compartments").isJsonObject()) {
            HttpBridgeServer.sendError(exchange, 400, "MISSING_PARAM",
                    "Request body must include a 'compartments' object");
            return;
        }

        JsonObject compartmentsInput = body.getAsJsonObject("compartments");
        JsonObject result = EdtDispatcher.write("MCP Bridge: Set Shape Compartments", project -> {
            DiagramPresentationElement dpe = findDiagramById(project, diagramId);
            dpe.ensureLoaded();

            PresentationElement target = findPresentationElement(dpe.getPresentationElements(), peId);
            if (target == null) {
                throw new IllegalArgumentException("Presentation element not found: " + peId);
            }

            PropertyManager pm = target.getPropertyManager().clone();
            @SuppressWarnings("unchecked")
            List<Property> properties = pm.getProperties();
            Map<String, Property> propertyByName = new LinkedHashMap<>();
            for (Property property : properties) {
                propertyByName.put(property.getName(), property);
            }

            JsonArray updated = new JsonArray();
            for (var compartmentEntry : compartmentsInput.entrySet()) {
                String requestedKey = compartmentEntry.getKey();
                JsonElement requestedValue = compartmentEntry.getValue();
                Property property = resolveCompartmentProperty(propertyByName, requestedKey);

                JsonObject entry = new JsonObject();
                entry.addProperty("compartment", requestedKey);
                if (property == null) {
                    entry.addProperty("error", "No matching compartment property found on this shape");
                    updated.add(entry);
                    continue;
                }

                applyCompartmentValue(property, requestedValue, requestedKey);
                entry.addProperty("property", property.getName());
                addJsonValue(entry, "value", property.getValue());
                entry.addProperty("set", true);
                updated.add(entry);
            }

            PresentationElementsManager.getInstance().setPresentationElementProperties(target, pm);

            JsonObject response = new JsonObject();
            response.addProperty("diagramId", diagramId);
            response.addProperty("presentationId", peId);
            response.add("results", updated);
            response.addProperty("resultCount", updated.size());
            JsonObject receipt = new JsonObject();
            receipt.addProperty("operation", "setShapeCompartments");
            receipt.addProperty("diagramId", diagramId);
            receipt.addProperty("presentationId", peId);
            receipt.addProperty("status", "applied");
            response.add("receipt", receipt);
            return response;
        });

        HttpBridgeServer.sendJson(exchange, 200, result);
    }

    private void handleConfigureTransitionLabelPresentation(
            HttpExchange exchange,
            String diagramId) throws Exception {
        JsonObject body = JsonHelper.parseBody(exchange);
        List<String> presentationIds = JsonHelper.optionalStringList(body, "presentationIds");
        boolean showName = readBoolean(body, "showName", true);
        boolean showTriggers = readBoolean(body, "showTriggers", true);
        boolean showGuard = readBoolean(body, "showGuard", false);
        boolean showEffect = readBoolean(body, "showEffect", false);
        boolean resetLabels = readBoolean(body, "resetLabels", true);

        JsonObject result = EdtDispatcher.write("MCP Bridge: Configure Transition Label Presentation", project -> {
            DiagramPresentationElement dpe = findDiagramById(project, diagramId);
            dpe.ensureLoaded();

            List<PresentationElement> targets = selectPresentationElements(
                    dpe.getPresentationElements(),
                    presentationIds,
                    this::isTransitionPathPresentation);

            PresentationElementsManager pem = PresentationElementsManager.getInstance();
            JsonArray results = new JsonArray();
            Map<String, PropertySelection> selections = new LinkedHashMap<>();
            selections.put("showName", new PropertySelection(showName, List.of("Show Name"), List.of("showname")));
            selections.put("showTriggers", new PropertySelection(showTriggers, List.of(), List.of("showtrigger", "showtriggers")));
            selections.put("showGuard", new PropertySelection(showGuard, List.of(), List.of("showguard")));
            selections.put("showEffect", new PropertySelection(showEffect, List.of(), List.of("showeffect")));
            for (PresentationElement target : targets) {
                results.add(configurePresentationProperties(target, pem, selections, resetLabels));
            }

            JsonObject response = new JsonObject();
            response.addProperty("diagramId", diagramId);
            response.addProperty("resultCount", results.size());
            response.add("results", results);
            JsonObject receipt = new JsonObject();
            receipt.addProperty("operation", "configureTransitionLabelPresentation");
            receipt.addProperty("diagramId", diagramId);
            receipt.addProperty("status", "applied");
            response.add("receipt", receipt);
            return response;
        });

        HttpBridgeServer.sendJson(exchange, 200, result);
    }

    private void handleConfigureItemFlowLabelPresentation(
            HttpExchange exchange,
            String diagramId) throws Exception {
        JsonObject body = JsonHelper.parseBody(exchange);
        List<String> presentationIds = JsonHelper.optionalStringList(body, "presentationIds");
        boolean showName = readBoolean(body, "showName", false);
        boolean showConveyed = readBoolean(body, "showConveyed", true);
        boolean showItemProperty = readBoolean(body, "showItemProperty", true);
        boolean showDirection = readBoolean(body, "showDirection", true);
        boolean showStereotype = readBoolean(body, "showStereotype", false);
        boolean resetLabels = readBoolean(body, "resetLabels", true);

        JsonObject result = EdtDispatcher.write("MCP Bridge: Configure Item Flow Label Presentation", project -> {
            DiagramPresentationElement dpe = findDiagramById(project, diagramId);
            dpe.ensureLoaded();

            List<PresentationElement> targets = selectPresentationElements(
                    dpe.getPresentationElements(),
                    presentationIds,
                    this::isItemFlowPathPresentation);

            PresentationElementsManager pem = PresentationElementsManager.getInstance();
            JsonArray results = new JsonArray();
            Map<String, PropertySelection> selections = new LinkedHashMap<>();
            selections.put("showName", new PropertySelection(showName, List.of("Show Name"), List.of("showname")));
            selections.put("showConveyed", new PropertySelection(showConveyed, List.of(), List.of("conveyed", "informationflow")));
            selections.put("showItemProperty", new PropertySelection(showItemProperty, List.of(), List.of("itemproperty")));
            selections.put("showDirection", new PropertySelection(showDirection, List.of(), List.of("showdirection", "direction")));
            selections.put("showStereotype", new PropertySelection(showStereotype, List.of("Show Stereotype"), List.of("showstereotype")));
            for (PresentationElement target : targets) {
                results.add(configurePresentationProperties(target, pem, selections, resetLabels));
            }

            JsonObject response = new JsonObject();
            response.addProperty("diagramId", diagramId);
            response.addProperty("resultCount", results.size());
            response.add("results", results);
            JsonObject receipt = new JsonObject();
            receipt.addProperty("operation", "configureItemFlowLabelPresentation");
            receipt.addProperty("diagramId", diagramId);
            receipt.addProperty("status", "applied");
            response.add("receipt", receipt);
            return response;
        });

        HttpBridgeServer.sendJson(exchange, 200, result);
    }

    private void handleConfigureAllocationCompartmentPresentation(
            HttpExchange exchange,
            String diagramId) throws Exception {
        JsonObject body = JsonHelper.parseBody(exchange);
        List<String> presentationIds = JsonHelper.optionalStringList(body, "presentationIds");
        boolean showAllocatedElements = readBoolean(body, "showAllocatedElements", true);
        boolean showElementProperties = readBoolean(body, "showElementProperties", true);
        boolean showPorts = readBoolean(body, "showPorts", true);
        boolean showFullPorts = readBoolean(body, "showFullPorts", true);
        boolean applyAllocationNaming = readBoolean(body, "applyAllocationNaming", true);

        JsonObject result = EdtDispatcher.write("MCP Bridge: Configure Allocation Compartments", project -> {
            DiagramPresentationElement dpe = findDiagramById(project, diagramId);
            dpe.ensureLoaded();

            List<PresentationElement> targets = selectPresentationElements(
                    dpe.getPresentationElements(),
                    presentationIds,
                    this::isAllocationCompartmentCandidate);

            PresentationElementsManager pem = PresentationElementsManager.getInstance();
            JsonArray results = new JsonArray();
            Map<String, PropertySelection> selections = new LinkedHashMap<>();
            selections.put("showAllocatedElements", new PropertySelection(
                    showAllocatedElements,
                    List.of(),
                    List.of("allocatedelements", "allocatedfrom")));
            selections.put("showElementProperties", new PropertySelection(
                    showElementProperties,
                    List.of("Show Element Properties"),
                    List.of("showelementproperties")));
            selections.put("showPorts", new PropertySelection(
                    showPorts,
                    List.of("Show Ports"),
                    List.of("showports")));
            selections.put("showFullPorts", new PropertySelection(
                    showFullPorts,
                    List.of("Show Full Ports", "Suppress Full Ports"),
                    List.of("showfullports", "suppressfullports")));
            selections.put("applyAllocationNaming", new PropertySelection(
                    applyAllocationNaming,
                    List.of("Apply SysML 1.7 Allocation Compartment Naming"),
                    List.of("allocationcompartmentnaming")));
            for (PresentationElement target : targets) {
                results.add(configurePresentationProperties(target, pem, selections, false));
            }

            JsonObject response = new JsonObject();
            response.addProperty("diagramId", diagramId);
            response.addProperty("resultCount", results.size());
            response.add("results", results);
            JsonObject receipt = new JsonObject();
            receipt.addProperty("operation", "configureAllocationCompartmentPresentation");
            receipt.addProperty("diagramId", diagramId);
            receipt.addProperty("status", "applied");
            response.add("receipt", receipt);
            return response;
        });

        HttpBridgeServer.sendJson(exchange, 200, result);
    }

    private void handleRepairHiddenLabels(HttpExchange exchange, String diagramId) throws Exception {
        JsonObject body = JsonHelper.parseBody(exchange);
        List<String> presentationIds = JsonHelper.optionalStringList(body, "presentationIds");
        boolean dryRun = readBoolean(body, "dryRun", false);

        JsonObject result = EdtDispatcher.write("MCP Bridge: Repair Hidden Labels", project -> {
            DiagramPresentationElement dpe = findDiagramById(project, diagramId);
            dpe.ensureLoaded();

            String diagramType = diagramTypeName(dpe);
            RepairDefaults defaults = repairDefaultsForDiagramType(diagramType);
            Map<String, PropertySelection> selections = buildSelectionMap(
                    true,
                    defaults.hiddenLabelKeys);

            List<PresentationElement> targets = selectPresentationElements(
                    dpe.getPresentationElements(),
                    presentationIds,
                    target -> targetSupportsAnySelection(target, selections));

            PresentationElementsManager pem = PresentationElementsManager.getInstance();
            JsonArray results = new JsonArray();
            for (PresentationElement target : targets) {
                JsonObject entry = configurePresentationProperties(
                        target, pem, selections, false, !dryRun);
                entry.addProperty("repairMode", "hidden-labels");
                entry.addProperty("applied", !dryRun);
                entry.addProperty("status", dryRun ? "preview" : "applied");
                entry.add("receipt", buildRepairReceipt(
                        "repairHiddenLabels",
                        diagramId,
                        diagramType,
                        target.getID(),
                        !dryRun,
                        false,
                        entry));
                results.add(entry);
            }

            JsonObject response = new JsonObject();
            response.addProperty("diagramId", diagramId);
            response.addProperty("diagramType", diagramType);
            response.addProperty("repairMode", "hidden-labels");
            response.addProperty("dryRun", dryRun);
            response.addProperty("resultCount", results.size());
            response.addProperty("updatedCount", countUpdatedTargets(results));
            response.add("results", results);
            response.add("receipt", buildBatchRepairReceipt(
                    "repairHiddenLabels",
                    diagramId,
                    diagramType,
                    dryRun,
                    targets.size(),
                    countUpdatedTargets(results),
                    results.size()));
            return response;
        });

        HttpBridgeServer.sendJson(exchange, 200, result);
    }

    private void handleRepairLabelPositions(HttpExchange exchange, String diagramId) throws Exception {
        JsonObject body = JsonHelper.parseBody(exchange);
        List<String> presentationIds = JsonHelper.optionalStringList(body, "presentationIds");
        boolean dryRun = readBoolean(body, "dryRun", false);
        boolean onlyOverlapping = readBoolean(body, "onlyOverlapping", true);
        int overlapPadding = body.has("overlapPadding")
                ? Math.max(0, body.get("overlapPadding").getAsInt())
                : 40;

        JsonObject result = EdtDispatcher.write("MCP Bridge: Repair Label Positions", project -> {
            DiagramPresentationElement dpe = findDiagramById(project, diagramId);
            dpe.ensureLoaded();

            String diagramType = diagramTypeName(dpe);
            List<PresentationElement> targets = selectPresentationElements(
                    dpe.getPresentationElements(),
                    presentationIds,
                    pe -> pe instanceof PathElement);
            if (onlyOverlapping) {
                targets = selectOverlappingPathTargets(targets, overlapPadding);
            }

            PresentationElementsManager pem = PresentationElementsManager.getInstance();
            JsonArray results = new JsonArray();
            for (PresentationElement target : targets) {
                JsonObject entry = new JsonObject();
                entry.addProperty("presentationId", target.getID());
                entry.addProperty("elementType", target.getElement() != null
                        ? target.getElement().getHumanType() : "");
                entry.addProperty("overlapCandidate", true);
                entry.addProperty("applied", !dryRun);
                if (!dryRun) {
                    pem.resetLabelPositions((PathElement) target);
                }
                JsonObject receipt = new JsonObject();
                receipt.addProperty("operation", "repairLabelPositions");
                receipt.addProperty("diagramId", diagramId);
                receipt.addProperty("diagramType", diagramType);
                receipt.addProperty("presentationId", target.getID());
                receipt.addProperty("onlyOverlapping", onlyOverlapping);
                receipt.addProperty("overlapPadding", overlapPadding);
                receipt.addProperty("status", dryRun ? "preview" : "applied");
                entry.add("receipt", receipt);
                results.add(entry);
            }

            JsonObject response = new JsonObject();
            response.addProperty("diagramId", diagramId);
            response.addProperty("diagramType", diagramType);
            response.addProperty("repairMode", "label-positions");
            response.addProperty("dryRun", dryRun);
            response.addProperty("onlyOverlapping", onlyOverlapping);
            response.addProperty("overlapPadding", overlapPadding);
            response.addProperty("resultCount", results.size());
            response.add("results", results);
            response.add("receipt", buildBatchRepairReceipt(
                    "repairLabelPositions",
                    diagramId,
                    diagramType,
                    dryRun,
                    targets.size(),
                    countUpdatedTargets(results),
                    results.size()));
            return response;
        });

        HttpBridgeServer.sendJson(exchange, 200, result);
    }

    private void handleForceConveyedItemLabels(
            HttpExchange exchange,
            String diagramId) throws Exception {
        JsonObject body = JsonHelper.parseBody(exchange);
        List<String> presentationIds = JsonHelper.optionalStringList(body, "presentationIds");
        boolean dryRun = readBoolean(body, "dryRun", false);
        boolean resetLabels = readBoolean(body, "resetLabels", true);

        JsonObject result = EdtDispatcher.write("MCP Bridge: Force Conveyed Item Labels", project -> {
            DiagramPresentationElement dpe = findDiagramById(project, diagramId);
            dpe.ensureLoaded();

            String diagramType = diagramTypeName(dpe);
            RepairDefaults defaults = repairDefaultsForDiagramType(diagramType);
            Map<String, PropertySelection> selections = buildSelectionMap(
                    false,
                    defaults.conveyedItemKeys);

            List<PresentationElement> targets = selectPresentationElements(
                    dpe.getPresentationElements(),
                    presentationIds,
                    target -> target instanceof PathElement && targetSupportsAnySelection(target, selections));

            PresentationElementsManager pem = PresentationElementsManager.getInstance();
            JsonArray results = new JsonArray();
            for (PresentationElement target : targets) {
                JsonObject entry = configurePresentationProperties(
                        target, pem, selections, resetLabels, !dryRun);
                entry.addProperty("repairMode", "conveyed-item-labels");
                entry.addProperty("applied", !dryRun);
                entry.addProperty("status", dryRun ? "preview" : "applied");
                entry.add("receipt", buildRepairReceipt(
                        "repairConveyedItemLabels",
                        diagramId,
                        diagramType,
                        target.getID(),
                        !dryRun,
                        resetLabels,
                        entry));
                results.add(entry);
            }

            JsonObject response = new JsonObject();
            response.addProperty("diagramId", diagramId);
            response.addProperty("diagramType", diagramType);
            response.addProperty("repairMode", "conveyed-item-labels");
            response.addProperty("dryRun", dryRun);
            response.addProperty("resetLabels", resetLabels);
            response.addProperty("resultCount", results.size());
            response.addProperty("updatedCount", countUpdatedTargets(results));
            response.add("results", results);
            response.add("receipt", buildBatchRepairReceipt(
                    "repairConveyedItemLabels",
                    diagramId,
                    diagramType,
                    dryRun,
                    targets.size(),
                    countUpdatedTargets(results),
                    results.size()));
            return response;
        });

        HttpBridgeServer.sendJson(exchange, 200, result);
    }

    private void handleNormalizeCompartmentPresets(
            HttpExchange exchange,
            String diagramId) throws Exception {
        JsonObject body = JsonHelper.parseBody(exchange);
        List<String> presentationIds = JsonHelper.optionalStringList(body, "presentationIds");
        boolean dryRun = readBoolean(body, "dryRun", false);

        JsonObject result = EdtDispatcher.write("MCP Bridge: Normalize Compartment Presets", project -> {
            DiagramPresentationElement dpe = findDiagramById(project, diagramId);
            dpe.ensureLoaded();

            String diagramType = diagramTypeName(dpe);
            RepairDefaults defaults = repairDefaultsForDiagramType(diagramType);
            Map<String, PropertySelection> selections = buildSelectionMap(
                    true,
                    defaults.compartmentKeys);

            List<PresentationElement> targets = selectPresentationElements(
                    dpe.getPresentationElements(),
                    presentationIds,
                    target -> target instanceof ShapeElement
                            && targetSupportsAnySelection(target, selections));

            PresentationElementsManager pem = PresentationElementsManager.getInstance();
            JsonArray results = new JsonArray();
            for (PresentationElement target : targets) {
                JsonObject entry = configurePresentationProperties(
                        target, pem, selections, false, !dryRun);
                entry.addProperty("repairMode", "compartment-presets");
                entry.addProperty("applied", !dryRun);
                entry.addProperty("status", dryRun ? "preview" : "applied");
                entry.add("receipt", buildRepairReceipt(
                        "normalizeCompartmentPresets",
                        diagramId,
                        diagramType,
                        target.getID(),
                        !dryRun,
                        false,
                        entry));
                results.add(entry);
            }

            JsonObject response = new JsonObject();
            response.addProperty("diagramId", diagramId);
            response.addProperty("diagramType", diagramType);
            response.addProperty("repairMode", "compartment-presets");
            response.addProperty("dryRun", dryRun);
            response.addProperty("resultCount", results.size());
            response.addProperty("updatedCount", countUpdatedTargets(results));
            response.add("results", results);
            response.add("receipt", buildBatchRepairReceipt(
                    "normalizeCompartmentPresets",
                    diagramId,
                    diagramType,
                    dryRun,
                    targets.size(),
                    countUpdatedTargets(results),
                    results.size()));
            return response;
        });

        HttpBridgeServer.sendJson(exchange, 200, result);
    }

    private void handleRoutePaths(HttpExchange exchange, String diagramId) throws Exception {
        JsonObject body = JsonHelper.parseBody(exchange);
        if (!body.has("routes") || !body.get("routes").isJsonArray()) {
            HttpBridgeServer.sendError(exchange, 400, "MISSING_PARAM",
                    "Request body must include a 'routes' array");
            return;
        }

        JsonArray routesInput = body.getAsJsonArray("routes");
        JsonObject result = EdtDispatcher.write("MCP Bridge: Route Paths", project -> {
            DiagramPresentationElement dpe = findDiagramById(project, diagramId);
            dpe.ensureLoaded();

            PresentationElementsManager pem = PresentationElementsManager.getInstance();
            List<PresentationElement> allPEs = dpe.getPresentationElements();
            JsonArray results = new JsonArray();

            for (JsonElement item : routesInput) {
                JsonObject routeReq = item.getAsJsonObject();
                String presentationId = JsonHelper.optionalString(routeReq, "presentationId");
                JsonObject entry = new JsonObject();
                entry.addProperty("presentationId", presentationId);

                if (presentationId == null) {
                    entry.addProperty("error", "Each route must include presentationId");
                    results.add(entry);
                    continue;
                }

                PresentationElement target = findPresentationElement(allPEs, presentationId);
                if (!(target instanceof PathElement)) {
                    entry.addProperty("error", "Presentation element is not a path");
                    results.add(entry);
                    continue;
                }

                PathElement path = (PathElement) target;
                List<Point> breakPoints = parsePointList(routeReq.get("breakPoints"));
                Point sourcePoint = parsePoint(routeReq.get("sourcePoint"));
                Point targetPoint = parsePoint(routeReq.get("targetPoint"));
                boolean resetLabels = !routeReq.has("resetLabels")
                        || routeReq.get("resetLabels").getAsBoolean();

                if (sourcePoint != null || targetPoint != null) {
                    pem.changePathPoints(path, sourcePoint, targetPoint, breakPoints);
                } else {
                    pem.changePathBreakPoints(path, breakPoints);
                }
                if (resetLabels) {
                    pem.resetLabelPositions(path);
                }

                entry.addProperty("routed", true);
                entry.addProperty("breakPointCount", breakPoints.size());
                JsonObject receipt = new JsonObject();
                receipt.addProperty("operation", "routePath");
                receipt.addProperty("diagramId", diagramId);
                receipt.addProperty("presentationId", presentationId);
                receipt.addProperty("resetLabels", resetLabels);
                receipt.addProperty("status", "applied");
                entry.add("receipt", receipt);
                results.add(entry);
            }

            JsonObject response = new JsonObject();
            response.addProperty("diagramId", diagramId);
            response.addProperty("resultCount", results.size());
            response.add("results", results);
            return response;
        });

        HttpBridgeServer.sendJson(exchange, 200, result);
    }

    private JsonObject configurePresentationProperties(
            PresentationElement target,
            PresentationElementsManager pem,
            Map<String, PropertySelection> selections,
            boolean resetLabels) {
        return configurePresentationProperties(target, pem, selections, resetLabels, true);
    }

    private JsonObject configurePresentationProperties(
            PresentationElement target,
            PresentationElementsManager pem,
            Map<String, PropertySelection> selections,
            boolean resetLabels,
            boolean applyChanges) {
        PropertyManager pm = target.getPropertyManager().clone();
        @SuppressWarnings("unchecked")
        List<Property> properties = pm.getProperties();
        JsonArray updated = new JsonArray();

        for (Map.Entry<String, PropertySelection> selection : selections.entrySet()) {
            applyPropertySelection(properties, updated, selection.getKey(), selection.getValue());
        }

        if (applyChanges) {
            pem.setPresentationElementProperties(target, pm);
        }
        if (applyChanges && resetLabels && target instanceof PathElement) {
            pem.resetLabelPositions((PathElement) target);
        }

        JsonObject response = new JsonObject();
        response.addProperty("presentationId", target.getID());
        if (target.getElement() != null) {
            response.addProperty("elementId", target.getElement().getID());
            response.addProperty("elementType", target.getElement().getHumanType());
        }
        response.addProperty("applied", applyChanges);
        response.addProperty("resetLabels", resetLabels && target instanceof PathElement);
        response.add("updates", updated);
        response.addProperty("resultCount", updated.size());
        return response;
    }

    private void applyPropertySelection(
            List<Property> properties,
            JsonArray updated,
            String requestKey,
            PropertySelection selection) {
        Set<String> seen = new LinkedHashSet<>();
        for (Property property : properties) {
            if (!matchesPropertySelection(property.getName(), selection)) {
                continue;
            }
            String normalized = normalizePropertyKey(property.getName());
            if (!seen.add(normalized)) {
                continue;
            }
            setBooleanLikeProperty(property, selection.value);

            JsonObject entry = new JsonObject();
            entry.addProperty("request", requestKey);
            entry.addProperty("property", property.getName());
            addJsonValue(entry, "value", property.getValue());
            entry.addProperty("set", true);
            updated.add(entry);
        }

        if (seen.isEmpty()) {
            JsonObject missing = new JsonObject();
            missing.addProperty("request", requestKey);
            missing.addProperty("error", "No matching presentation property found");
            updated.add(missing);
        }
    }

    private boolean matchesPropertySelection(String propertyName, PropertySelection selection) {
        String normalized = normalizePropertyKey(propertyName);
        for (String exactName : selection.exactNames) {
            if (normalizePropertyKey(exactName).equals(normalized)) {
                return true;
            }
        }
        for (String containsToken : selection.containsNormalizedTokens) {
            if (normalized.contains(containsToken)) {
                return true;
            }
        }
        return false;
    }

    private void setBooleanLikeProperty(Property property, boolean value) {
        String normalized = normalizePropertyKey(property.getName());
        if (normalized.startsWith("suppress")) {
            property.setValue(!value);
            return;
        }
        property.setValue(value);
    }

    private List<PresentationElement> selectPresentationElements(
            List<PresentationElement> roots,
            List<String> requestedIds,
            Predicate<PresentationElement> predicate) {
        List<PresentationElement> flattened = new ArrayList<>();
        collectPresentationElements(roots, flattened);

        if (requestedIds != null && !requestedIds.isEmpty()) {
            Map<String, PresentationElement> byId = new LinkedHashMap<>();
            for (PresentationElement pe : flattened) {
                byId.put(pe.getID(), pe);
            }
            List<PresentationElement> selected = new ArrayList<>();
            for (String presentationId : requestedIds) {
                PresentationElement target = byId.get(presentationId);
                if (target != null && predicate.test(target)) {
                    selected.add(target);
                }
            }
            return selected;
        }

        List<PresentationElement> selected = new ArrayList<>();
        for (PresentationElement pe : flattened) {
            if (predicate.test(pe)) {
                selected.add(pe);
            }
        }
        return selected;
    }

    private void collectPresentationElements(
            List<PresentationElement> elements,
            List<PresentationElement> sink) {
        if (elements == null) {
            return;
        }
        for (PresentationElement pe : elements) {
            sink.add(pe);
            collectPresentationElements(pe.getPresentationElements(), sink);
        }
    }

    private boolean isTransitionPathPresentation(PresentationElement pe) {
        return pe instanceof PathElement && presentationElementMatches(pe, "transition");
    }

    private boolean isItemFlowPathPresentation(PresentationElement pe) {
        return pe instanceof PathElement
                && (presentationElementMatches(pe, "informationflow")
                || presentationElementMatches(pe, "itemflow"));
    }

    private boolean isAllocationCompartmentCandidate(PresentationElement pe) {
        return pe instanceof ShapeElement && targetHasAllocationProperties(pe);
    }

    private boolean targetHasAllocationProperties(PresentationElement pe) {
        try {
            PropertyManager pm = pe.getPropertyManager();
            @SuppressWarnings("unchecked")
            List<Property> properties = pm.getProperties();
            for (Property property : properties) {
                String normalized = normalizePropertyKey(property.getName());
                if (normalized.contains("allocatedelements")
                        || normalized.contains("allocatedfrom")
                        || normalized.contains("allocationcompartmentnaming")
                        || normalized.contains("showelementproperties")
                        || normalized.contains("showfullports")
                        || normalized.contains("suppressfullports")) {
                    return true;
                }
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "Could not inspect allocation-related shape properties", e);
        }
        return false;
    }

    private boolean targetSupportsAnySelection(
            PresentationElement target,
            Map<String, PropertySelection> selections) {
        try {
            PropertyManager pm = target.getPropertyManager();
            @SuppressWarnings("unchecked")
            List<Property> properties = pm.getProperties();
            for (Property property : properties) {
                for (PropertySelection selection : selections.values()) {
                    if (matchesPropertySelection(property.getName(), selection)) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "Could not inspect presentation properties", e);
        }
        return false;
    }

    private Map<String, PropertySelection> buildSelectionMap(
            boolean value,
            List<String> canonicalKeys) {
        Map<String, PropertySelection> selections = new LinkedHashMap<>();
        for (String key : canonicalKeys) {
            selections.put(key, selectionForCanonicalKey(key, value));
        }
        return selections;
    }

    private PropertySelection selectionForCanonicalKey(String canonicalKey, boolean value) {
        String normalized = normalizePropertyKey(canonicalKey);
        switch (normalized) {
            case "showname":
                return new PropertySelection(value, List.of("Show Name"), List.of("showname"));
            case "showstereotype":
                return new PropertySelection(value, List.of("Show Stereotype"), List.of("showstereotype"));
            case "showtype":
                return new PropertySelection(value, List.of("Show Type"), List.of("showtype"));
            case "showtriggers":
                return new PropertySelection(value, List.of("Show Triggers"), List.of("showtrigger", "showtriggers"));
            case "showguard":
                return new PropertySelection(value, List.of("Show Guard"), List.of("showguard"));
            case "showeffect":
                return new PropertySelection(value, List.of("Show Effect"), List.of("showeffect"));
            case "showconveyed":
                return new PropertySelection(value, List.of(), List.of("conveyed", "informationflow"));
            case "showitemproperty":
                return new PropertySelection(value, List.of(), List.of("itemproperty"));
            case "showdirection":
                return new PropertySelection(value, List.of(), List.of("showdirection", "direction"));
            case "showproperties":
                return new PropertySelection(value, List.of("Show Properties"), List.of("showproperties"));
            case "showoperations":
                return new PropertySelection(value, List.of("Show Operations", "Suppress Operations"),
                        List.of("showoperations", "suppressoperations"));
            case "showconstraints":
                return new PropertySelection(value, List.of("Show Constraints"), List.of("showconstraints"));
            case "showtaggedvalues":
                return new PropertySelection(value, List.of("Show Tagged Values"),
                        List.of("showtaggedvalues"));
            case "showports":
                return new PropertySelection(value, List.of("Show Ports"), List.of("showports"));
            case "showattributes":
                return new PropertySelection(value, List.of("Suppress Attributes"), List.of("suppressattributes"));
            case "showelementproperties":
                return new PropertySelection(value, List.of("Show Element Properties"),
                        List.of("showelementproperties"));
            case "showfullports":
                return new PropertySelection(value, List.of("Show Full Ports", "Suppress Full Ports"),
                        List.of("showfullports", "suppressfullports"));
            case "showallocatedelements":
                return new PropertySelection(value, List.of(), List.of("allocatedelements", "allocatedfrom"));
            case "applyallocationnaming":
                return new PropertySelection(value, List.of("Apply SysML 1.7 Allocation Compartment Naming"),
                        List.of("allocationcompartmentnaming"));
            default:
                return new PropertySelection(value, List.of(canonicalKey), List.of(normalized));
        }
    }

    private JsonObject buildBatchRepairReceipt(
            String operation,
            String diagramId,
            String diagramType,
            boolean dryRun,
            int targetCount,
            int updatedCount,
            int resultCount) {
        JsonObject receipt = new JsonObject();
        receipt.addProperty("operation", operation);
        receipt.addProperty("diagramId", diagramId);
        receipt.addProperty("diagramType", diagramType);
        receipt.addProperty("status", dryRun ? "preview" : "applied");
        receipt.addProperty("dryRun", dryRun);
        receipt.addProperty("targetCount", targetCount);
        receipt.addProperty("updatedCount", updatedCount);
        receipt.addProperty("resultCount", resultCount);
        return receipt;
    }

    private JsonObject buildRepairReceipt(
            String operation,
            String diagramId,
            String diagramType,
            String presentationId,
            boolean applied,
            boolean resetLabels,
            JsonObject resultEntry) {
        JsonObject receipt = new JsonObject();
        receipt.addProperty("operation", operation);
        receipt.addProperty("diagramId", diagramId);
        receipt.addProperty("diagramType", diagramType);
        receipt.addProperty("presentationId", presentationId);
        receipt.addProperty("status", applied ? "applied" : "preview");
        receipt.addProperty("applied", applied);
        receipt.addProperty("resetLabels", resetLabels);
        receipt.addProperty("updateCount", resultEntry != null && resultEntry.has("updates")
                ? resultEntry.getAsJsonArray("updates").size()
                : 0);
        return receipt;
    }

    private int countUpdatedTargets(JsonArray results) {
        int count = 0;
        for (JsonElement element : results) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            if (entry.has("error")) {
                continue;
            }
            if (entry.has("updates") && entry.get("updates").isJsonArray()
                    && entry.getAsJsonArray("updates").size() > 0) {
                count++;
            }
        }
        return count;
    }

    private List<PresentationElement> selectOverlappingPathTargets(
            List<PresentationElement> targets,
            int overlapPadding) {
        if (targets == null || targets.size() < 2) {
            return targets == null ? List.of() : targets;
        }

        Map<String, Rectangle> boundsById = new LinkedHashMap<>();
        for (PresentationElement target : targets) {
            try {
                Rectangle bounds = target.getBounds();
                if (bounds != null) {
                    boundsById.put(target.getID(), new Rectangle(bounds));
                }
            } catch (Exception e) {
                LOG.log(Level.FINE, "Could not inspect path bounds for overlap", e);
            }
        }

        if (boundsById.size() < 2) {
            return List.of();
        }

        Set<String> selectedIds = new LinkedHashSet<>();
        List<Map.Entry<String, Rectangle>> entries = new ArrayList<>(boundsById.entrySet());
        for (int i = 0; i < entries.size(); i++) {
            Map.Entry<String, Rectangle> leftEntry = entries.get(i);
            Rectangle leftBounds = new Rectangle(leftEntry.getValue());
            leftBounds.grow(overlapPadding, overlapPadding);
            for (int j = i + 1; j < entries.size(); j++) {
                Map.Entry<String, Rectangle> rightEntry = entries.get(j);
                if (leftBounds.intersects(rightEntry.getValue())
                        || rightEntry.getValue().intersects(leftEntry.getValue())) {
                    selectedIds.add(leftEntry.getKey());
                    selectedIds.add(rightEntry.getKey());
                }
            }
        }

        List<PresentationElement> selected = new ArrayList<>();
        for (PresentationElement target : targets) {
            if (selectedIds.contains(target.getID())) {
                selected.add(target);
            }
        }
        return selected;
    }

    private String diagramTypeName(DiagramPresentationElement dpe) {
        if (dpe == null || dpe.getDiagramType() == null || dpe.getDiagramType().getType() == null) {
            return "";
        }
        return dpe.getDiagramType().getType();
    }

    static JsonObject describeRepairDefaults(String diagramType) {
        RepairDefaults defaults = repairDefaultsForDiagramType(diagramType);
        JsonObject json = new JsonObject();
        json.addProperty("diagramType", defaults.diagramType);
        json.addProperty("normalizedDiagramType", defaults.normalizedDiagramType);
        json.addProperty("resetPathLabelsByDefault", defaults.resetPathLabelsByDefault);
        json.add("shapeLabelKeys", toJsonArray(defaults.shapeLabelKeys));
        json.add("pathLabelKeys", toJsonArray(defaults.pathLabelKeys));
        json.add("conveyedItemKeys", toJsonArray(defaults.conveyedItemKeys));
        json.add("compartmentKeys", toJsonArray(defaults.compartmentKeys));
        return json;
    }

    private static JsonArray toJsonArray(List<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    private static RepairDefaults repairDefaultsForDiagramType(String diagramType) {
        String normalized = normalizeDiagramType(diagramType);
        List<String> shapeLabelKeys = List.of("showName", "showStereotype");
        List<String> pathLabelKeys = List.of("showName");
        List<String> conveyedItemKeys = List.of("showConveyed", "showItemProperty", "showDirection");
        List<String> compartmentKeys = List.of();
        boolean resetPathLabelsByDefault = true;

        if (normalized.contains("statemachine")) {
            shapeLabelKeys = List.of("showName");
            pathLabelKeys = List.of("showName", "showTriggers", "showGuard", "showEffect");
            conveyedItemKeys = List.of("showName", "showConveyed", "showItemProperty", "showDirection");
        } else if (normalized.contains("internalblock") || normalized.equals("ibd")) {
            shapeLabelKeys = List.of("showName", "showStereotype");
            pathLabelKeys = List.of("showName");
            conveyedItemKeys = List.of("showConveyed", "showItemProperty", "showDirection", "showStereotype");
            compartmentKeys = List.of("showPorts", "showFullPorts", "showElementProperties",
                    "showAllocatedElements", "applyAllocationNaming");
        } else if (normalized.contains("blockdefinition") || normalized.equals("bdd")
                || normalized.contains("classdiagram")) {
            shapeLabelKeys = List.of("showName", "showStereotype", "showType");
            pathLabelKeys = List.of("showName");
            compartmentKeys = List.of("showProperties", "showOperations", "showConstraints",
                    "showTaggedValues", "showPorts", "showAttributes");
        } else if (normalized.contains("requirement")) {
            shapeLabelKeys = List.of("showName", "showStereotype", "showType");
            pathLabelKeys = List.of("showName");
            compartmentKeys = List.of("showConstraints", "showTaggedValues");
        } else if (normalized.contains("usecase")) {
            shapeLabelKeys = List.of("showName", "showStereotype");
            pathLabelKeys = List.of();
            compartmentKeys = List.of();
        } else if (normalized.contains("activity")) {
            shapeLabelKeys = List.of("showName", "showStereotype");
            pathLabelKeys = List.of("showName");
            compartmentKeys = List.of();
        } else if (normalized.contains("sequence")) {
            shapeLabelKeys = List.of("showName");
            pathLabelKeys = List.of("showName");
            compartmentKeys = List.of();
        } else if (normalized.contains("component") || normalized.contains("deployment")
                || normalized.contains("package") || normalized.contains("compositestructure")) {
            shapeLabelKeys = List.of("showName", "showStereotype", "showType");
            pathLabelKeys = List.of("showName");
            compartmentKeys = List.of("showProperties", "showOperations", "showConstraints",
                    "showTaggedValues", "showPorts", "showAttributes");
        }

        return new RepairDefaults(
                diagramType == null ? "" : diagramType,
                normalized,
                shapeLabelKeys,
                pathLabelKeys,
                conveyedItemKeys,
                compartmentKeys,
                resetPathLabelsByDefault);
    }

    private static String normalizeDiagramType(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9]", "").toLowerCase();
    }

    private boolean presentationElementMatches(PresentationElement pe, String token) {
        if (pe == null) {
            return false;
        }
        String normalizedToken = normalizePropertyKey(token);
        String className = normalizePropertyKey(pe.getClass().getSimpleName());
        if (className.contains(normalizedToken)) {
            return true;
        }
        Element element = pe.getElement();
        if (element == null) {
            return false;
        }
        String humanType = normalizePropertyKey(element.getHumanType());
        if (humanType.contains(normalizedToken)) {
            return true;
        }
        Object classType = element.getClassType();
        String runtimeType = normalizePropertyKey(
                classType != null ? classType.toString() : "");
        return runtimeType.contains(normalizedToken);
    }

    private boolean readBoolean(JsonObject body, String key, boolean defaultValue) {
        if (body == null || !body.has(key) || body.get(key).isJsonNull()) {
            return defaultValue;
        }
        return body.get(key).getAsBoolean();
    }

    /**
     * Find a PresentationElement by its ID, searching recursively into children.
     */
    private PresentationElement findPresentationElement(
            List<PresentationElement> elements, String peId) {
        if (elements == null || peId == null) return null;
        for (PresentationElement pe : elements) {
            if (peId.equals(pe.getID())) {
                return pe;
            }
            // Recurse into children (regions, nested states, messages, etc.)
            List<PresentationElement> children = pe.getPresentationElements();
            if (children != null && !children.isEmpty()) {
                PresentationElement found = findPresentationElement(children, peId);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void addJsonValue(JsonObject target, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof Boolean) {
            target.addProperty(key, (Boolean) value);
        } else if (value instanceof Number) {
            target.addProperty(key, (Number) value);
        } else {
            target.addProperty(key, String.valueOf(value));
        }
    }

    private String canonicalCompartmentKey(String propertyName) {
        if (propertyName == null) {
            return null;
        }
        String normalized = normalizePropertyKey(propertyName);
        switch (normalized) {
            case "showproperties":
                return "properties";
            case "showoperations":
            case "suppressoperations":
                return "operations";
            case "showconstraints":
                return "constraints";
            case "showtaggedvalues":
                return "tagged_values";
            case "showports":
                return "ports";
            case "suppressattributes":
                return "attributes";
            case "showstereotype":
                return "stereotype";
            case "showname":
                return "name";
            case "showtype":
                return "type";
            default:
                return null;
        }
    }

    private String normalizePropertyKey(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9]", "").toLowerCase();
    }

    private Property resolveCompartmentProperty(Map<String, Property> propertyByName, String requestedKey) {
        String normalized = normalizePropertyKey(requestedKey);
        for (Map.Entry<String, Property> entry : propertyByName.entrySet()) {
            if (normalizePropertyKey(entry.getKey()).equals(normalized)) {
                return entry.getValue();
            }
        }

        Map<String, String[]> aliases = new LinkedHashMap<>();
        aliases.put("properties", new String[]{"Show Properties"});
        aliases.put("operations", new String[]{"Show Operations", "Suppress Operations"});
        aliases.put("constraints", new String[]{"Show Constraints"});
        aliases.put("taggedvalues", new String[]{"Show Tagged Values"});
        aliases.put("ports", new String[]{"Show Ports"});
        aliases.put("attributes", new String[]{"Suppress Attributes"});
        aliases.put("stereotype", new String[]{"Show Stereotype"});
        aliases.put("name", new String[]{"Show Name"});
        aliases.put("type", new String[]{"Show Type"});

        String[] candidates = aliases.get(normalized);
        if (candidates == null) {
            return null;
        }
        for (String candidate : candidates) {
            Property property = propertyByName.get(candidate);
            if (property != null) {
                return property;
            }
        }
        return null;
    }

    private void applyCompartmentValue(Property property, JsonElement value, String requestedKey) {
        String propertyName = property.getName();
        boolean boolValue = value.getAsBoolean();
        if ("Suppress Attributes".equals(propertyName) || "Suppress Operations".equals(propertyName)) {
            property.setValue(!boolValue);
            return;
        }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
            property.setValue(boolValue);
        } else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            property.setValue(value.getAsInt());
        } else {
            property.setValue(value.getAsString());
        }
    }

    private Point parsePoint(JsonElement pointElement) {
        if (pointElement == null || pointElement.isJsonNull() || !pointElement.isJsonObject()) {
            return null;
        }
        JsonObject pointJson = pointElement.getAsJsonObject();
        if (!pointJson.has("x") || !pointJson.has("y")) {
            return null;
        }
        return new Point(pointJson.get("x").getAsInt(), pointJson.get("y").getAsInt());
    }

    private List<Point> parsePointList(JsonElement pointsElement) {
        List<Point> points = new ArrayList<>();
        if (pointsElement == null || pointsElement.isJsonNull() || !pointsElement.isJsonArray()) {
            return points;
        }
        for (JsonElement pointElement : pointsElement.getAsJsonArray()) {
            Point point = parsePoint(pointElement);
            if (point != null) {
                points.add(point);
            }
        }
        return points;
    }

    // ── Utility Methods ─────────────────────────────────────────────────────

    private DiagramPresentationElement findDiagramById(Project project, String diagramId) {
        Object baseElement = project.getElementByID(diagramId);
        if (baseElement instanceof Diagram) {
            DiagramPresentationElement dpe = project.getDiagram((Diagram) baseElement);
            if (dpe != null) {
                return dpe;
            }
        }

        Collection<DiagramPresentationElement> diagrams = project.getDiagrams();
        if (diagrams != null) {
            for (DiagramPresentationElement dpe : diagrams) {
                Diagram d = dpe.getDiagram();
                if (d != null && diagramId.equals(d.getID())) {
                    return dpe;
                }
            }
        }

        throw new IllegalArgumentException("Diagram not found: " + diagramId);
    }

    private String resolveDiagramType(String input) {
        if (input == null) return input;

        String normalized = input.trim().toLowerCase().replace("-", " ").replace("_", " ");

        switch (normalized) {
            case "class":
            case "class diagram":
                return "Class Diagram";
            case "package":
            case "package diagram":
                return "Package Diagram";
            case "use case":
            case "usecase":
            case "use case diagram":
                return "Use Case Diagram";
            case "activity":
            case "activity diagram":
                return "Activity Diagram";
            case "sequence":
            case "sequence diagram":
                return "Sequence Diagram";
            case "state machine":
            case "statemachine":
            case "state machine diagram":
                return "State Machine Diagram";
            case "component":
            case "component diagram":
                return "Component Diagram";
            case "deployment":
            case "deployment diagram":
                return "Deployment Diagram";
            case "composite structure":
            case "composite structure diagram":
                return "Composite Structure Diagram";
            case "object":
            case "object diagram":
                return "Object Diagram";
            case "communication":
            case "communication diagram":
                return "Communication Diagram";
            case "interaction overview":
            case "interaction overview diagram":
                return "Interaction Overview Diagram";
            case "timing":
            case "timing diagram":
                return "Timing Diagram";
            case "profile":
            case "profile diagram":
                return "Profile Diagram";
            case "bdd":
            case "block definition":
            case "block definition diagram":
                return "SysML Block Definition Diagram";
            case "ibd":
            case "internal block":
            case "internal block diagram":
                return "SysML Internal Block Diagram";
            case "requirement":
            case "requirement diagram":
            case "requirements":
            case "sysml requirement diagram":
                return "SysML Requirement Diagram";
            case "parametric":
            case "parametric diagram":
            case "sysml parametric diagram":
                return "SysML Parametric Diagram";
            default:
                return input.trim();
        }
    }

    private static final class PropertySelection {
        private final boolean value;
        private final List<String> exactNames;
        private final List<String> containsNormalizedTokens;

        private PropertySelection(
                boolean value,
                List<String> exactNames,
                List<String> containsNormalizedTokens) {
            this.value = value;
            this.exactNames = exactNames;
            this.containsNormalizedTokens = containsNormalizedTokens;
        }
    }

    private static final class RepairDefaults {
        private final String diagramType;
        private final String normalizedDiagramType;
        private final List<String> shapeLabelKeys;
        private final List<String> pathLabelKeys;
        private final List<String> conveyedItemKeys;
        private final List<String> compartmentKeys;
        private final boolean resetPathLabelsByDefault;

        private RepairDefaults(
                String diagramType,
                String normalizedDiagramType,
                List<String> shapeLabelKeys,
                List<String> pathLabelKeys,
                List<String> conveyedItemKeys,
                List<String> compartmentKeys,
                boolean resetPathLabelsByDefault) {
            this.diagramType = diagramType;
            this.normalizedDiagramType = normalizedDiagramType;
            this.shapeLabelKeys = shapeLabelKeys;
            this.pathLabelKeys = pathLabelKeys;
            this.conveyedItemKeys = conveyedItemKeys;
            this.compartmentKeys = compartmentKeys;
            this.resetPathLabelsByDefault = resetPathLabelsByDefault;
        }
    }
}
