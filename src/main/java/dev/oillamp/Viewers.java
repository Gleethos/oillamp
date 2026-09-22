package dev.oillamp;

import sprouts.Tuple;

/**
 * The command that opens a window onto the sandbox desktop — spec §15.
 *
 * <p>One profile in v1, TigerVNC's {@code vncviewer}, because it is the viewer that takes a Unix
 * socket path directly (confirmed by spike S8) and therefore needs no TCP port anywhere — which
 * is what keeps {@code --network=none} on the container true rather than nearly true.
 *
 * <p>Every option here exists to close something off. {@code -RemoteResize=0} stops the viewer
 * from changing the desktop size the recording was started at; {@code -SendPrimary=0} stops a
 * stray X selection on the host from being pushed into the sandbox; the clipboard pair is the
 * one-way default of D-23, spelled out rather than left to the viewer's own defaults.
 *
 * <p>Deliberately <b>package-private</b>: the viewer profile table, the counterpart to
 * {@link Terminals}. A second viewer is a row here, not an API change.
 */
final class Viewers {

    private Viewers() {}

    /** The executable oillamp looks for on PATH, and installs as {@code tigervnc-viewer}. */
    public static final String EXECUTABLE = "vncviewer";

    /**
     * The argv that opens the desktop.
     *
     * @param viewOnly the user watches without being able to type — {@code oillamp view
     *                 --view-only}, or {@code viewer.view_only} in the configuration
     */
    public static Tuple<String> argv(LampLayout layout, LampConfig config, boolean viewOnly) {
        LampConfig.Viewer viewer = config.viewer();
        Tuple<String> argv = Tuple.of(String.class,
                EXECUTABLE,
                "-Shared=1",
                "-AcceptClipboard=" + flag(viewer.clipboard() == ClipboardMode.BOTH),
                "-SendClipboard=" + flag(viewer.clipboard() != ClipboardMode.NONE),
                "-SendPrimary=0",
                "-RemoteResize=0",
                "-geometry", config.display().size());
        if (viewOnly) argv = argv.add("-ViewOnly=1");
        // The socket path, not a display number: there is no TCP port to connect to, by design.
        return argv.add(layout.vncSocket().toString());
    }

    /** The window title oillamp would like, where the viewer lets it be set (§15, spike S14). */
    public static String titleFor(String lampName) { return "oillamp · " + lampName; }

    private static String flag(boolean on) { return on ? "1" : "0"; }
}
