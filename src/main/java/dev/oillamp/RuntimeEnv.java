package dev.oillamp;

import sprouts.Association;
import sprouts.Pair;
import sprouts.Tuple;

/// Writes `.oillamp/session/runtime.env`, the settings file the container reads.
///
/// The entrypoint (a bash script running as container root) and every login shell of the agent
/// execute this file with `source`. A quoting mistake would therefore turn a configuration
/// value into a command, so the rules are strict:
///
/// - every value is wrapped in single quotes, inside which the shell expands nothing;
/// - a single quote inside a value is written as `'\''` (close, escaped quote, reopen);
/// - a value containing a line break is **refused**, because `source` would read the
///   rest as a separate command, and no real value needs one.
///
/// Keys are written in sorted order, so the file is the same for the same configuration.
final class RuntimeEnv {

    private RuntimeEnv() {}

    public static Result<String> render(Association<String, String> variables) {
        Tuple<String> lines = Tuple.of(String.class);
        for (Pair<String, String> entry : variables) {
            String key = entry.first();
            String value = entry.second();
            if (!key.matches("[A-Z][A-Z0-9_]*"))
                return Result.err(Problems.internal("RuntimeEnv",
                        "'" + key + "' is not a shell-safe environment variable name"));
            if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)
                return Result.err(Problems.internal("RuntimeEnv",
                        "the value of " + key + " contains a line break, which would split the "
                      + "assignment when the entrypoint sources this file"));
            lines = lines.add(key + "=" + quote(value));
        }
        return Result.ok(String.join("\n", sorted(lines)) + "\n");
    }

    /// Environment variables copied from the host into the sandbox when they are set.
    ///
    /// The agent can read everything in `runtime.env`; that is the point, since its harness
    /// needs these keys. It is also why this is a short, explicit list rather than the whole host
    /// environment.
    public static final Tuple<String> INHERITED_FROM_HOST = Tuple.of(String.class,
            "EDENAI_API_KEY", "EDENAI_BASE_URL", "EDENAI_EU_ONLY", "EDENAI_MAX_TOKENS");

    /// The variables for this session.
    ///
    /// They are read by the entrypoint, which uses them to set up the display, the recorder and
    /// the network bridges, and by every login shell of the agent through
    /// `/etc/profile.d/oillamp.sh`, which turns them into the agent's environment.
    public static Association<String, String> variables(LampConfig config,
                                                        LampLayout layout,
                                                        SessionId session,
                                                        String renderer,
                                                        Association<String, String> fromHost) {
        Association<String, String> env = Association.betweenSorted(String.class, String.class)
            .put("OILLAMP_SESSION",         session.value())
            .put("OILLAMP_AGENT_ID",        layout.agentId().value())
            .put("OILLAMP_LAMP_NAME",       layout.name())
            .put("OILLAMP_DISPLAY_WIDTH",   Integer.toString(config.display().width()))
            .put("OILLAMP_DISPLAY_HEIGHT",  Integer.toString(config.display().height()))
            .put("OILLAMP_DISPLAY_SCALE",   Double.toString(config.display().scale()))
            .put("OILLAMP_RENDERER",        renderer)
            .put("OILLAMP_VNC_MAX_FPS",     Integer.toString(config.viewer().maxFps()))
            .put("OILLAMP_RECORDING_ENABLED", Boolean.toString(config.recording().enabled()))
            .put("OILLAMP_RECORDING_CODEC", config.recording().codec())
            .put("OILLAMP_RECORDING_CRF",   Integer.toString(config.recording().crf()))
            .put("OILLAMP_RECORDING_MAX_FPS", Integer.toString(config.recording().maxFps()))
            .put("OILLAMP_PROXY_PORT",      Integer.toString(Forward.PROXY_PORT))
            .put("OILLAMP_FORWARDS",        forwardList(config));

        for (Pair<String, String> inherited : fromHost)
            env = env.put(inherited.first(), inherited.second());

        for (Forward forward : config.forwards())
            if (config.llm().isPresent() && config.llm().get().forward().equals(forward.name())) {
                LampConfig.Llm llm = config.llm().get();
                env = env.put("OILLAMP_LLM_BASE_URL", forward.inContainerUrl(llm.basePath()))
                         .put("OILLAMP_LLM_MODELS",   String.join(",", llm.models()))
                         .put("OILLAMP_LLM_PROVIDER", llm.providerName());
            }
        return env;
    }

    /// `"name:port name:port"`, which the entrypoint splits to start one bridge per forward.
    private static String forwardList(LampConfig config) {
        Tuple<String> entries = Tuple.of(String.class);
        for (Forward forward : config.forwards())
            entries = entries.add(forward.name() + ":" + forward.port());
        return String.join(" ", entries);
    }

    /// Wraps a value so that a POSIX shell reproduces it byte for byte.
    public static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static Tuple<String> sorted(Tuple<String> lines) {
        return lines.sort(String::compareTo);
    }
}
