package com.claude.cameo.bridge.compat;

import com.claude.cameo.bridge.compat.api.*;
import com.claude.cameo.bridge.compat.impl2022x.*;
import com.claude.cameo.bridge.compat.impl2024x.*;

/**
 * Factory that binds the correct compat implementation at runtime.
 *
 * <p>Call {@link #create()} once at plugin startup; the returned
 * {@link CompatApis} record holds all five API handles.  Every
 * handler in the HTTP bridge should use these handles rather than
 * importing concrete implementation classes.</p>
 */
public final class CompatApiFactory {

    private CompatApiFactory() {}

    /**
     * Detects the running Cameo version and constructs matching API impls.
     */
    public static CompatApis create() {
        CameoVersion version = VersionDetector.detect();
        return switch (version) {
            case V2022X -> new CompatApis(
                    new DiagramApi2022x(),
                    new ElementApi2022x(),
                    new RelationMapApi2022x(version),
                    new MatrixApi2022x(),
                    new SimulationApi2022x(version),
                    version
            );
            case V2024X -> new CompatApis(
                    new DiagramApi2024x(),
                    new ElementApi2024x(),
                    new RelationMapApi2024x(),
                    new MatrixApi2024x(),
                    new SimulationApi2024x(),
                    version
            );
        };
    }

    /**
     * Immutable record carrying all API handles plus the detected version.
     */
    public record CompatApis(
            DiagramApi   diagrams,
            ElementApi   elements,
            RelationMapApi relationMaps,
            MatrixApi    matrices,
            SimulationApi simulations,
            CameoVersion  version
    ) {}
}
