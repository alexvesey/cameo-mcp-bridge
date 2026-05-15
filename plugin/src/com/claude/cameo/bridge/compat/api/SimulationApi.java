package com.claude.cameo.bridge.compat.api;

import com.claude.cameo.bridge.compat.NotAvailableInVersionException;

import java.util.Map;

/**
 * Version-neutral interface for model simulation operations.
 *
 * <p><strong>2022x note:</strong> The REST-based simulation trigger API was
 * introduced in 2024x.  The 2022x implementation throws
 * {@link NotAvailableInVersionException} for all methods in this interface.
 * A basic "run active validation" path is available via
 * {@link ElementApi} as a partial alternative.</p>
 */
public interface SimulationApi {

    /**
     * Starts a simulation run for the given simulation configuration element.
     *
     * @param simConfigId element ID of the SimulationConfig stereotyped element
     * @param parameters  runtime parameter overrides (name → value)
     * @return run ID string for polling status
     * @throws NotAvailableInVersionException on 2022x
     */
    String startSimulation(String simConfigId, Map<String, String> parameters)
            throws NotAvailableInVersionException;

    /**
     * Polls the status of an ongoing simulation run.
     *
     * @param runId run ID returned by {@link #startSimulation}
     * @return status descriptor with "state", "progress", and optional "result" keys
     * @throws NotAvailableInVersionException on 2022x
     */
    Map<String, Object> getSimulationStatus(String runId)
            throws NotAvailableInVersionException;

    /**
     * Cancels a running simulation.
     *
     * @param runId run ID to cancel
     * @throws NotAvailableInVersionException on 2022x
     */
    void cancelSimulation(String runId)
            throws NotAvailableInVersionException;

    /**
     * Returns the results of a completed simulation run.
     *
     * @param runId run ID of the completed simulation
     * @return nested map of result data (variable name → value)
     * @throws NotAvailableInVersionException on 2022x
     */
    Map<String, Object> getSimulationResults(String runId)
            throws NotAvailableInVersionException;
}
