package dev.oillamp;

import java.util.Optional;

import sprouts.Association;
import sprouts.Tuple;

/**
 * A lamp's complete, validated configuration.
 *
 * <p>{@link ConfigLoader} checks every value before building this: the display size is in range,
 * the address ranges parse, no two forwards share a name or a port, and {@code llm.forward} names a
 * forward that exists. Code that reads it does not need to validate again, and every configuration
 * mistake is reported in one place, with its file and key path.
 *
 * <p>Some settings are read and validated but not yet used; see "Configuration reference" in
 * {@code docs/ARCHITECTURE.md}.
 */
record LampConfig(
    Display display,
    Viewer viewer,
    Terminal terminal,
    Recording recording,
    Limits limits,
    NetworkPolicy network,
    Tuple<Forward> forwards,
    Optional<Llm> llm,
    AgentTools agentTools,
    Image image,
    Host host,
    Timeouts timeouts
) {
    /** The version of the config schema this build writes and understands. */
    public static final int SCHEMA_VERSION = 1;

    public record Display(int width, int height, double scale, GpuMode gpu) {
        public Display {
            if (width  < 640 || width  > 7680) throw new IllegalArgumentException("display.width out of range: " + width);
            if (height < 480 || height > 4320) throw new IllegalArgumentException("display.height out of range: " + height);
            if (scale  < 0.5 || scale  > 4.0)  throw new IllegalArgumentException("display.scale out of range: " + scale);
        }
        public String size() { return width + "x" + height; }
    }

    public record Viewer(boolean openOnStart, ClipboardMode clipboard, boolean viewOnly, int maxFps) {
        public Viewer {
            if (maxFps < 1 || maxFps > 120) throw new IllegalArgumentException("viewer.max_fps out of range: " + maxFps);
        }
    }

    /** Which terminal emulator opens the sandbox shell. */
    public sealed interface Terminal {
        /** Detect it: the desktop's own terminal first, then the first installed one from the table. */
        record Auto() implements Terminal {}
        record Profile(TerminalProfileId id) implements Terminal {}
        /** A user-supplied argv template containing {@code {cmd}} and optionally {@code {title}}. */
        record Custom(Tuple<String> template) implements Terminal {}
    }

    public record Recording(boolean enabled, String codec, int crf, int maxFps,
                            int maxAgeDays, int maxTotalGb) {
        public Recording {
            if (crf < 0 || crf > 51)   throw new IllegalArgumentException("recording.crf out of range: " + crf);
            if (maxFps < 1)            throw new IllegalArgumentException("recording.max_fps must be positive");
            if (maxAgeDays < 0)        throw new IllegalArgumentException("recording.max_age_days cannot be negative");
            if (maxTotalGb < 0)        throw new IllegalArgumentException("recording.max_total_gb cannot be negative");
        }
    }

    public record Limits(String memory, int cpus, int pids) {
        public Limits {
            if (!memory.matches("\\d+[kmgKMG]?")) throw new IllegalArgumentException("limits.memory: " + memory);
            if (cpus < 0)  throw new IllegalArgumentException("limits.cpus cannot be negative");
            if (pids < 64) throw new IllegalArgumentException("limits.pids is far too low: " + pids);
        }
        /** {@code cpus = 0} means all host CPUs but one, so the host's own desktop stays responsive. */
        public int resolveCpus(int hostCpuCount) { return cpus > 0 ? cpus : Math.max(1, hostCpuCount - 1); }
    }

    /** {@code [llm]}. {@code apiKeyEnv} and {@code apiKeyFile} are not used yet. */
    public record Llm(String forward, String basePath, String apiKeyEnv, String apiKeyFile,
                      Tuple<String> models, String providerName) {
        public Optional<String> defaultModel() {
            return models.isEmpty() ? Optional.empty() : Optional.of(models.first());
        }
    }

    /** {@code [agent_tools]}. {@code versions} is not used yet; the newest versions are installed. */
    public record AgentTools(Tuple<String> install, Association<String, String> versions) {}

    public record Image(String base, String nodeVersion, String jdkPackage, Tuple<String> extraAptPackages) {}

    /** {@code [host]}. {@code autoInstall} is not used yet; only {@code --no-install} stops installing. */
    public record Host(boolean autoInstall) {}

    /** {@code [timeouts]}. {@code containerReadySeconds} is not used yet; the wait is fixed in {@code SandboxPhase}. */
    public record Timeouts(int containerReadySeconds, int terminalConnectSeconds, int stopSeconds) {
        public Timeouts {
            if (containerReadySeconds  < 1) throw new IllegalArgumentException("timeouts.container_ready_seconds");
            if (terminalConnectSeconds < 1) throw new IllegalArgumentException("timeouts.terminal_connect_seconds");
            if (stopSeconds            < 1) throw new IllegalArgumentException("timeouts.stop_seconds");
        }
        public java.time.Duration containerReady()  { return java.time.Duration.ofSeconds(containerReadySeconds); }
        public java.time.Duration terminalConnect() { return java.time.Duration.ofSeconds(terminalConnectSeconds); }
        public java.time.Duration stop()            { return java.time.Duration.ofSeconds(stopSeconds); }
    }

    public Optional<Forward> forwardNamed(String name) {
        for (Forward forward : forwards)
            if (forward.name().equals(name)) return Optional.of(forward);
        return Optional.empty();
    }

    /** The forward that {@code llm.forward} names, if both are configured. */
    public Optional<Forward> llmForward() {
        return llm.flatMap(config -> forwardNamed(config.forward()));
    }
}
