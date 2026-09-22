package dev.oillamp;

/**
 * {@code viewer.clipboard} — which way the clipboard flows between host and sandbox (D-23).
 *
 * <p>The default is one-way on purpose: pasting <em>into</em> the sandbox is useful, while
 * copying <em>out</em> of it is where content the agent produced could leak onto the host
 * clipboard unnoticed.
 */
enum ClipboardMode {
    TO_AGENT, BOTH, NONE;

    public String configName() {
        return switch (this) {
            case TO_AGENT -> "to-agent";
            case BOTH     -> "both";
            case NONE     -> "none";
        };
    }
}
