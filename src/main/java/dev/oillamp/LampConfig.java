package dev.oillamp;

import java.util.Optional;

import sprouts.Association;
import sprouts.Tuple;

/**
 * A lamp's complete, validated configuration — spec §24.3.
 *
 * <p>By the time this record exists, every value has been checked: the display size is sane, the
 * CIDRs parse, no two forwards share a name or a port, and {@code llm.forward} names a forward
 * that actually exists. Everything downstream can therefore read it without re-validating, and a
 * configuration mistake can only surface in one place — the loader — where it can be reported
 * with a file, a key path and an expectation (FR-52).
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

    /** How to find the terminal emulator that hosts the sandbox shell (D-22). */
    public sealed interface Terminal {
        /** Detect it: the desktop's own terminal first, then the known table (§17.4). */
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
        /** {@code cpus = 0} means "all host CPUs but one", so the desktop stays responsive (§13.1). */
        public int resolveCpus(int hostCpuCount) { return cpus > 0 ? cpus : Math.max(1, hostCpuCount - 1); }
    }

    public record Llm(String forward, String basePath, String apiKeyEnv, String apiKeyFile,
                      Tuple<String> models, String providerName) {
        public Optional<String> defaultModel() {
            return models.isEmpty() ? Optional.empty() : Optional.of(models.first());
        }
    }

    public record AgentTools(Tuple<String> install, Association<String, String> versions) {}

    public record Image(String base, String nodeVersion, String jdkPackage, Tuple<String> extraAptPackages) {}

    public record Host(boolean autoInstall) {}

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

    /** The forward the LLM configuration points at, if both are configured (§19.5). */
    public Optional<Forward> llmForward() {
        return llm.flatMap(config -> forwardNamed(config.forward()));
    }
}
