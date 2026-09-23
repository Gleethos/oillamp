package dev.oillamp;

import sprouts.Tuple;

/// Builds the command that opens a viewer window onto the sandbox desktop.
///
/// The viewer is TigerVNC's `vncviewer`, because it can connect to a Unix socket path
/// directly, so no TCP port is needed anywhere.
///
/// Each option closes something off. `-RemoteResize=0` stops the viewer from changing the
/// desktop size. `-SendPrimary=0` stops your X text selection from being sent into the
/// sandbox. The two clipboard options follow `viewer.clipboard` explicitly instead of relying
/// on the viewer's own defaults.
final class Viewers {

    private Viewers() {}

    /// The executable oillamp looks for on PATH, and installs as `tigervnc-viewer`.
    public static final String EXECUTABLE = "vncviewer";

    /// The argv that opens the desktop.
    ///
    /// @param viewOnly the user watches without being able to type (`oillamp view                 --view-only`, or `viewer.view_only` in the configuration)
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

    /// The window title oillamp would like. Not used yet: wayvnc's desktop name has not been set up.
    public static String titleFor(String lampName) { return "oillamp · " + lampName; }

    private static String flag(boolean on) { return on ? "1" : "0"; }
}
