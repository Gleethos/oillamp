package dev.oillamp;

import sprouts.Association;
import sprouts.Pair;
import sprouts.Tuple;

/**
 * Renders {@code .oillamp/session/runtime.env} — spec §20.3.
 *
 * <p>This file is sourced by the container entrypoint (a Bash script running as container root)
 * and by every agent login shell. That makes it the one place where a quoting mistake turns a
 * configuration value into executed code, so the rules are strict and tested:
 *
 * <ul>
 *   <li>every value is wrapped in single quotes, inside which the shell expands nothing;</li>
 *   <li>an embedded single quote is closed, escaped and reopened ({@code '\''});</li>
 *   <li>a value containing a newline is <b>refused</b>, not escaped — {@code source} would read
 *       the remainder as a separate assignment, and no legitimate value needs one.</li>
 * </ul>
 *
 * <p>Keys are emitted in sorted order so the file is stable across runs and diffable in the log.
 */
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

    /**
     * The variables the sandbox is started with — spec §20.3.
     *
     * <p>Read twice: by the container entrypoint, which uses them to size the display and start
     * the recorder, and by every agent login shell through {@code /etc/profile.d/oillamp.sh},
     * which turns the proxy variables into the agent's network configuration (§18.6).
     *
     * <p>Built sorted, so the rendered file — and therefore the image hash and the log — is
     * stable between runs that configured the same thing.
     */
    public static Association<String, String> variables(LampConfig config,
                                                        LampLayout layout,
                                                        SessionId session,
                                                        String renderer) {
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

        for (Forward forward : config.forwards())
            if (config.llm().isPresent() && config.llm().get().forward().equals(forward.name())) {
                LampConfig.Llm llm = config.llm().get();
                env = env.put("OILLAMP_LLM_BASE_URL", forward.inContainerUrl(llm.basePath()))
                         .put("OILLAMP_LLM_MODELS",   String.join(",", llm.models()))
                         .put("OILLAMP_LLM_PROVIDER", llm.providerName());
            }
        return env;
    }

    /** {@code "name:port name:port …"}, which the entrypoint splits to start one bridge each. */
    private static String forwardList(LampConfig config) {
        Tuple<String> entries = Tuple.of(String.class);
        for (Forward forward : config.forwards())
            entries = entries.add(forward.name() + ":" + forward.port());
        return String.join(" ", entries);
    }

    /** Wraps a value so that a POSIX shell reproduces it byte for byte. */
    public static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static Tuple<String> sorted(Tuple<String> lines) {
        return lines.sort(String::compareTo);
    }
}
