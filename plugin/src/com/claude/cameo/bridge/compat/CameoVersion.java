package com.claude.cameo.bridge.compat;

/**
 * Enumeration of supported Cameo Systems Modeler / CATIA Magic versions.
 *
 * <p>Each constant carries the capability set that the version exposes
 * so the compat factory can gate tool availability without reflection.</p>
 */
public enum CameoVersion {

    V2022X("2022x") {
        @Override
        public boolean supportsRelationMap()   { return false; }
        @Override
        public boolean supportsRadialLayout()  { return false; }
        @Override
        public boolean supportsSimulationRest(){ return false; }
        @Override
        public boolean supportsDiagramTable()  { return true;  }
        @Override
        public int     javaApiLevel()          { return 11; }
    },

    V2024X("2024x") {
        @Override
        public boolean supportsRelationMap()   { return true;  }
        @Override
        public boolean supportsRadialLayout()  { return true;  }
        @Override
        public boolean supportsSimulationRest(){ return true;  }
        @Override
        public boolean supportsDiagramTable()  { return true;  }
        @Override
        public int     javaApiLevel()          { return 17; }
    };

    private final String label;

    CameoVersion(String label) {
        this.label = label;
    }

    public String getLabel() { return label; }

    // ---- capability predicates (overridden per constant) ----

    public abstract boolean supportsRelationMap();
    public abstract boolean supportsRadialLayout();
    public abstract boolean supportsSimulationRest();
    public abstract boolean supportsDiagramTable();
    public abstract int     javaApiLevel();

    /** Serialises to the wire format used in X-Cameo-Version response header. */
    @Override
    public String toString() { return label; }
}
