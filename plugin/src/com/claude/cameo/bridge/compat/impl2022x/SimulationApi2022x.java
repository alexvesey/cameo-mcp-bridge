package com.claude.cameo.bridge.compat.impl2022x;

import com.claude.cameo.bridge.compat.CameoVersion;
import com.claude.cameo.bridge.compat.NotAvailableInVersionException;
import com.claude.cameo.bridge.compat.api.SimulationApi;

import java.util.Map;

/**
 * Cameo 2022x stub for {@link SimulationApi}.
 *
 * <p>The REST-based simulation trigger API was introduced in 2024x.
 * All methods raise {@link NotAvailableInVersionException}.
 * The nearest equivalent in 2022x is running active validation via the
 * standard element validation API — callers should use {@code validate_model}
 * instead.</p>
 */
public class SimulationApi2022x implements SimulationApi {

    private static final String ALT =
            "Use the 'validate_model' tool to run active validation in 2022x. " +
            "Full parametric simulation is available in Cameo 2024x only.";

    private final CameoVersion version;

    public SimulationApi2022x(CameoVersion version) {
        this.version = version;
    }

    @Override
    public String startSimulation(String simConfigId, Map<String, String> parameters)
            throws NotAvailableInVersionException {
        throw new NotAvailableInVersionException(
                "start_simulation", version,
                "Simulation REST triggers are not available in Cameo 2022x.", ALT);
    }

    @Override
    public Map<String, Object> getSimulationStatus(String runId)
            throws NotAvailableInVersionException {
        throw new NotAvailableInVersionException(
                "get_simulation_status", version,
                "Simulation status polling is not available in Cameo 2022x.", ALT);
    }

    @Override
    public void cancelSimulation(String runId)
            throws NotAvailableInVersionException {
        throw new NotAvailableInVersionException(
                "cancel_simulation", version,
                "Simulation cancellation is not available in Cameo 2022x.", ALT);
    }

    @Override
    public Map<String, Object> getSimulationResults(String runId)
            throws NotAvailableInVersionException {
        throw new NotAvailableInVersionException(
                "get_simulation_results", version,
                "Simulation result retrieval is not available in Cameo 2022x.", ALT);
    }
}
