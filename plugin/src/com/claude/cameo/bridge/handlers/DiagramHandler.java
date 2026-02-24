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
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Diagram;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.NamedElement;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Namespace;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import javax.imageio.ImageIO;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
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
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                exchange.getResponseHeaders().set("Access-Control-Allow-Methods",
                        "GET, POST, OPTIONS");
                exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if ("GET".equals(method)) {
                if (path.equals("/api/v1/diagrams")) {
                    handleListDiagrams(exchange);
                } else {
                    String diagramId = JsonHelper.extractPathParam(exchange, PREFIX);
                    String subPath = JsonHelper.extractSubPath(exchange, PREFIX);

                    if (diagramId != null && "image".equals(subPath)) {
                        handleExportImage(exchange, diagramId);
                    } else if (diagramId != null && subPath == null) {
                        handleGetDiagram(exchange, diagramId);
                    } else {
                        HttpBridgeServer.sendError(exchange, 404, "NOT_FOUND",
                                "Unknown endpoint: " + path);
                    }
                }
            } else if ("POST".equals(method)) {
                if (path.equals("/api/v1/diagrams")) {
                    handleCreateDiagram(exchange);
                } else {
                    String diagramId = JsonHelper.extractPathParam(exchange, PREFIX);
                    String subPath = JsonHelper.extractSubPath(exchange, PREFIX);

                    if (diagramId != null && "elements".equals(subPath)) {
                        handleAddElement(exchange, diagramId);
                    } else if (diagramId != null && "layout".equals(subPath)) {
                        handleLayout(exchange, diagramId);
                    } else {
                        HttpBridgeServer.sendError(exchange, 404, "NOT_FOUND",
                                "Unknown endpoint: " + path);
                    }
                }
            } else {
                HttpBridgeServer.sendError(exchange, 405, "METHOD_NOT_ALLOWED",
                        "Only GET and POST are supported");
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
            if (presentationElements != null) {
                for (PresentationElement pe : presentationElements) {
                    try {
                        Element modelElement = pe.getElement();
                        if (modelElement == null) continue;

                        JsonObject shapeJson = new JsonObject();
                        shapeJson.addProperty("elementId", modelElement.getID());
                        if (modelElement instanceof NamedElement) {
                            shapeJson.addProperty("name",
                                    ((NamedElement) modelElement).getName());
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

                        shapesArray.add(shapeJson);
                    } catch (Exception e) {
                        LOG.log(Level.FINE, "Error reading presentation element", e);
                    }
                }
            }
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

        JsonObject result = EdtDispatcher.write("MCP Bridge: Add Element to Diagram", project -> {
            DiagramPresentationElement dpe = findDiagramById(project, diagramId);
            dpe.ensureLoaded();

            Element element = (Element) project.getElementByID(elementId);
            if (element == null) {
                throw new IllegalArgumentException("Element not found: " + elementId);
            }

            PresentationElementsManager pem = PresentationElementsManager.getInstance();
            ShapeElement shape = pem.createShapeElement(element, dpe, true, new Point(x, y));

            if (shape == null) {
                throw new IllegalStateException(
                        "Failed to create shape for element: " + elementId);
            }

            if (body.has("width") && body.has("height")) {
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
}
