package dev.oillamp;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

import sprouts.Association;
import sprouts.Tuple;

/// A lamp's complete, validated configuration.
///
/// [ConfigLoader] checks every value before building this: the display size is in range,
/// the address ranges parse, no two forwards share a name or a port, and `llm.forward` names a
/// forward that exists. Code that reads it does not need to validate again, and every configuration
/// mistake is reported in one place, with its file and key path.
///
/// Some settings are read and validated but not yet used; see "Configuration reference" in
/// `docs/ARCHITECTURE.md`.
record LampConfig(
    Display display,
    Viewer viewer,
    Terminal terminal,
    Recording recording,
    Limits limits,
    NetworkPolicy network,
    Tuple<Forward> forwards,
    Optional<Llm> llm,
    Model model,
    Git git,
    AgentTools agentTools,
    Image image,
    Host host,
    Timeouts timeouts,
    Schedule schedule
) {
    /// The same configuration with other `[model]` settings, such as those an application gave
    /// on the command line for one session.
    public LampConfig withModel(Model changed) {
        return new LampConfig(display, viewer, terminal, recording, limits, network, forwards, llm,
                              changed, git, agentTools, image, host, timeouts, schedule);
    }

    /// The same configuration with other `[schedule]` settings.
    public LampConfig withSchedule(Schedule changed) {
        return new LampConfig(display, viewer, terminal, recording, limits, network, forwards, llm,
                              model, git, agentTools, image, host, timeouts, changed);
    }

    /// The version of the config schema this build writes and understands.
    public static final int SCHEMA_VERSION = 1;

    public record Display(int width, int height, double scale, GpuMode gpu, WindowLayout windows) {
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

    /// Which terminal emulator opens the sandbox shell.
    public sealed interface Terminal {
        /// Detect it: the desktop's own terminal first, then the first installed one from the table.
        record Auto() implements Terminal {}
        record Profile(TerminalProfileId id) implements Terminal {}
        /// A user-supplied argv template containing `{cmd}` and optionally `{title}`.
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
        /// `cpus = 0` means all host CPUs but one, so the host's own desktop stays responsive.
        public int resolveCpus(int hostCpuCount) { return cpus > 0 ? cpus : Math.max(1, hostCpuCount - 1); }
    }

    /// `[llm]`. `apiKeyEnv` and `apiKeyFile` are not used yet.
    public record Llm(String forward, String basePath, String apiKeyEnv, String apiKeyFile,
                      Tuple<String> models, String providerName) {}

    /// `[model]`: where oillamp sends the sandbox's model requests, and where it finds the key.
    ///
    /// Both stay on the host. Inside the sandbox the harnesses always see the same local address
    /// and a placeholder key, whatever is set here.
    ///
    /// @param service the model service, such as `https://api.eu.edenai.run`, or a model server
    ///                on this machine with the path of its API, such as
    ///                `http://127.0.0.1:11434/v1`. Plain `http` only for a service on this
    ///                machine's loopback, so the key never crosses a network unencrypted
    /// @param keyEnv  the host environment variable holding the key, read when a session starts
    public record Model(URI service, String keyEnv) {
        public Model {
            if (!keyEnv.matches("[A-Za-z_][A-Za-z0-9_]*"))
                throw new IllegalArgumentException("model.key_env is not a variable name: " + keyEnv);
        }

        /// Whether the harnesses offer only models served in the EU. True for Eden AI, whose
        /// model list says where each model is served. Any other service is the user's own
        /// choice, and its model list says nothing about regions, so filtering it by region
        /// would leave no model at all.
        public boolean euOnly() {
            String host = Optional.ofNullable(service.getHost()).orElse("");
            return host.equals("edenai.run") || host.endsWith(".edenai.run");
        }
    }

    /// `[git]`: the name and email on the commits the agent makes.
    ///
    /// Without one, git in the sandbox refuses to commit, and an agent then tends to set one
    /// itself inside the repository, where it stays and is used by the user's own tools too.
    ///
    /// @param name  used only with [GitIdentity#CUSTOM]
    /// @param email used only with [GitIdentity#CUSTOM]
    public record Git(GitIdentity identity, String name, String email) {
        public Git {
            if (name.matches("(?s).*[\\r\\n].*") || email.matches("(?s).*[\\r\\n].*"))
                throw new IllegalArgumentException("git.name and git.email are one line each");
        }
    }

    /// `[agent_tools]`. `versions` is not used yet; the newest versions are installed.
    public record AgentTools(Tuple<String> install, Association<String, String> versions) {}

    public record Image(String base, String nodeVersion, String jdkPackage, Tuple<String> extraAptPackages) {}

    /// `[host]`. `autoInstall` is not used yet; only `--no-install` stops installing.
    public record Host(boolean autoInstall) {}

    /// `[timeouts]`. The wait for readiness is doubled straight after an image build.
    public record Timeouts(int containerReadySeconds, int terminalConnectSeconds, int stopSeconds) {
        public Timeouts {
            if (containerReadySeconds  < 1) throw new IllegalArgumentException("timeouts.container_ready_seconds");
            if (terminalConnectSeconds < 1) throw new IllegalArgumentException("timeouts.terminal_connect_seconds");
            if (stopSeconds            < 1) throw new IllegalArgumentException("timeouts.stop_seconds");
        }
        public Duration containerReady()  { return Duration.ofSeconds(containerReadySeconds); }
        public Duration terminalConnect() { return Duration.ofSeconds(terminalConnectSeconds); }
        public Duration stop()            { return Duration.ofSeconds(stopSeconds); }
    }

    /// `[schedule]`: whether jobs may wake the agent while a session runs, and the limits on the
    /// jobs the agent adds for itself. The user's own jobs have no limits besides the run time.
    ///
    /// @param maxAgentJobs            how many jobs the agent may have on the schedule at once
    /// @param minAgentIntervalMinutes the shortest time between two runs of one of the agent's jobs
    /// @param maxAgentDays            how long one of the agent's jobs may stay on the schedule
    /// @param maxAgentRunsPerDay      how many of the agent's jobs may run in any 24 hours
    /// @param maxRunMinutes           how long any run may take before it is stopped
    /// @param notesMaxKb              how large the agent's notes may grow, in kilobytes
    public record Schedule(boolean enabled, int maxAgentJobs, int minAgentIntervalMinutes,
                           int maxAgentDays, int maxAgentRunsPerDay, int maxRunMinutes,
                           int notesMaxKb) {
        public Schedule {
            if (maxAgentJobs < 0)            throw new IllegalArgumentException("schedule.max_agent_jobs cannot be negative");
            if (minAgentIntervalMinutes < 1) throw new IllegalArgumentException("schedule.min_agent_interval_minutes");
            if (maxAgentDays < 1)            throw new IllegalArgumentException("schedule.max_agent_days");
            if (maxAgentRunsPerDay < 0)      throw new IllegalArgumentException("schedule.max_agent_runs_per_day cannot be negative");
            if (maxRunMinutes < 1)           throw new IllegalArgumentException("schedule.max_run_minutes");
            if (notesMaxKb < 1)              throw new IllegalArgumentException("schedule.notes_max_kb");
        }
        public Schedule switchedOn() {
            return new Schedule(true, maxAgentJobs, minAgentIntervalMinutes, maxAgentDays,
                                maxAgentRunsPerDay, maxRunMinutes, notesMaxKb);
        }
        public Duration minAgentInterval() { return Duration.ofMinutes(minAgentIntervalMinutes); }
        public Duration maxAgentLife()     { return Duration.ofDays(maxAgentDays); }
        public Duration maxRun()           { return Duration.ofMinutes(maxRunMinutes); }
    }

    public Optional<Forward> forwardNamed(String name) {
        for (Forward forward : forwards)
            if (forward.name().equals(name)) return Optional.of(forward);
        return Optional.empty();
    }

    /// The forward that `llm.forward` names, if both are configured.
    public Optional<Forward> llmForward() {
        return llm.flatMap(config -> forwardNamed(config.forward()));
    }
}
