package com.claude.cameo.bridge.compat;

/**
 * Thrown when a tool depends on an API that is not present in the detected
 * Cameo version.  The message is surfaced verbatim to the LLM so it must be
 * clear, actionable, and include a nearest-equivalent suggestion when one exists.
 *
 * <p>The HTTP handler catches this exception and returns {@code 501 Not Implemented}
 * with the JSON body:</p>
 * <pre>
 * {
 *   "error": "...",
 *   "tool":  "...",
 *   "version": "2022x",
 *   "alternative": "..."  // optional
 * }
 * </pre>
 */
public class NotAvailableInVersionException extends RuntimeException {

    private final String toolName;
    private final CameoVersion version;
    private final String alternative;

    public NotAvailableInVersionException(String toolName, CameoVersion version,
                                          String humanMessage, String alternative) {
        super(humanMessage);
        this.toolName    = toolName;
        this.version     = version;
        this.alternative = alternative;
    }

    public NotAvailableInVersionException(String toolName, CameoVersion version,
                                          String humanMessage) {
        this(toolName, version, humanMessage, null);
    }

    public String getToolName()    { return toolName; }
    public CameoVersion getVersion(){ return version; }
    /** May be null if no alternative exists. */
    public String getAlternative() { return alternative; }
}
