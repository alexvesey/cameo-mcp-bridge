package com.claude.cameo.bridge.compat.impl2024x;

import com.claude.cameo.bridge.compat.api.SimulationApi;
import com.nomagic.magicdraw.core.Application;
import com.nomagic.magicdraw.core.Project;
import com.nomagic.magicdraw.simulation.SimulationManager;
import com.nomagic.magicdraw.simulation.SimulationRun;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;

import java.util.*;
import java.util.logging.Logger;

/**
 * Cameo 2024x implementation of {@link SimulationApi}.
 *
 * <p>Uses the {@code com.nomagic.magicdraw.simulation} REST-bridge API
 * introduced in 2024x which exposes a Java-side controller that maps
 * directly to the HTTP bridge's JSON protocol.</p>
 */
public class SimulationApi2024x implements SimulationApi {

    private static final Logger LOG = Logger.getLogger(SimulationApi2024x.class.getName());

    @Override
    public String startSimulation(String simConfigId, Map<String, String> parameters) {
        Project project = Application.getInstance().getProject();
        Element simConfig = project.getElementByID(simConfigId);
        if (simConfig == null) {
            throw new IllegalArgumentException("SimulationConfig element not found: " + simConfigId);
        }
        SimulationRun run = SimulationManager.getInstance()
                .startSimulation(project, simConfig, parameters);
        return run.getRunId();
    }

    @Override
    public Map<String, Object> getSimulationStatus(String runId) {
        SimulationRun run = SimulationManager.getInstance().getRunById(runId);
        if (run == null) {
            throw new IllegalArgumentException("Simulation run not found: " + runId);
        }
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("state",    run.getState().name());
        status.put("progress", run.getProgressPercent());
        if (run.hasResult()) {
            status.put("result", run.getSummary());
        }
        return status;
    }

    @Override
    public void cancelSimulation(String runId) {
        SimulationRun run = SimulationManager.getInstance().getRunById(runId);
        if (run == null) {
            throw new IllegalArgumentException("Simulation run not found: " + runId);
        }
        run.cancel();
    }

    @Override
    public Map<String, Object> getSimulationResults(String runId) {
        SimulationRun run = SimulationManager.getInstance().getRunById(runId);
        if (run == null) {
            throw new IllegalArgumentException("Simulation run not found: " + runId);
        }
        if (!run.hasResult()) {
            throw new IllegalStateException("Simulation run " + runId + " has not completed yet.");
        }
        return run.getResults();
    }
}
