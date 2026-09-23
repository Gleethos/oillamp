package dev.oillamp;

/// The `viewer.clipboard` setting: which way the clipboard may flow between your desktop and
/// the sandbox.
///
/// The default, [#TO_AGENT], is one-way on purpose. Pasting into the sandbox is useful.
/// Copying out of it is how content the agent produced could reach your clipboard unnoticed.
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
