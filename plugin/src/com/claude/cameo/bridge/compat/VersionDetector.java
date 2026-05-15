package com.claude.cameo.bridge.compat;

import com.claude.cameo.bridge.util.OptionalCapabilitySupport;
import com.google.gson.JsonObject;

import java.util.logging.Logger;

/**
 * Detects the running Cameo version at plugin load time.
 *
 * <p><strong>Detection strategy (most-to-least reliable):</strong></p>
 * <ol>
 *   <li>Class-probe: check for the {@code relationshipmap_api.jar} marker class
 *       ({@code com.nomagic.magicdraw.visualization.relationshipmap.RelationshipMapManager}).
 *       Present → 2024x feature set; absent → 2022x feature set.</li>
 *   <li>Simulation-probe: check for {@code com.nomagic.magicdraw.simulation.SimulationManager}.
 *       Layered on top of the relation-map probe.</li>
 * </ol>
 *
 * <p>This approach is more reliable than parsing a version string because
 * it directly tests what the JVM can load, matching what the compat layer
 * actually needs.</p>
 */
public final class VersionDetector {

    private static final Logger LOG = Logger.getLogger(VersionDetector.class.getName());

    /** Marker class present only when {@code relationshipmap_api.jar} is on the classpath. */
    private static final String RELATION_MAP_PROBE =
            "com.nomagic.magicdraw.visualization.relationshipmap.RelationshipMapManager";

    /** Marker class for the 2024x simulation REST bridge. */
    private static final String SIMULATION_PROBE =
            "com.nomagic.magicdraw.simulation.SimulationManager";

    private static volatile CameoVersion detected;

    private VersionDetector() {}

    /**
     * Returns the detected {@link CameoVersion}.  Result is cached after the first call.
     */
    public static CameoVersion detect() {
        if (detected != null) {
            return detected;
        }
        synchronized (VersionDetector.class) {
            if (detected != null) {
                return detected;
            }
            detected = resolveVersion();
        }
        return detected;
    }

    /**
     * Returns a JSON diagnostic object suitable for inclusion in the
     * {@code /api/v1/status} and {@code /api/v1/capabilities} responses.
     */
    public static JsonObject buildVersionDiagnostics() {
        JsonObject diag = new JsonObject();
        CameoVersion ver = detect();
        diag.addProperty("detectedVersion", ver.getLabel());

        JsonObject probes = new JsonObject();
        probes.add("relationMap",
                OptionalCapabilitySupport.classProbe(RELATION_MAP_PROBE));
        probes.add("simulation",
                OptionalCapabilitySupport.classProbe(SIMULATION_PROBE));
        diag.add("probes", probes);

        return diag;
    }

    // ---- private -------------------------------------------------------

    private static CameoVersion resolveVersion() {
        JsonObject rmProbe = OptionalCapabilitySupport.classProbe(RELATION_MAP_PROBE);
        boolean hasRelationMap = rmProbe.get("allFound").getAsBoolean();

        if (hasRelationMap) {
            LOG.info("VersionDetector: RelationshipMap API found — V2024X compat layer active");
            return CameoVersion.V2024X;
        } else {
            LOG.info("VersionDetector: RelationshipMap API absent — V2022X compat layer active");
            return CameoVersion.V2022X;
        }
    }
}
