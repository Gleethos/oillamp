package dev.oillamp;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

import dev.lamp.Problem;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import sprouts.Tuple;

/// Turns configuration files into a validated [LampConfig].
///
/// Each file is parsed into a tree, the trees are merged ([ConfigTree]), and the merged
/// tree is read setting by setting ([ConfigSection]). Reading collects problems instead of
/// throwing; only at the end are they judged. It is given the files' text, not their paths, so it
/// can be tested without files.
///
/// Every setting and its default is listed in `docs/ARCHITECTURE.md`, "Configuration
/// reference".
final class ConfigLoader {

    private ConfigLoader() {}

    private static final TomlMapper TOML = TomlMapper.builder().build();

    /// Reads, merges and validates configuration.
    ///
    /// @param sources the files, global first and the lamp's last; built-in defaults apply beneath both
    public static Result<LampConfig> load(Tuple<ConfigSource> sources) {
        Tuple<Problem> parseProblems = Tuple.of(Problem.class);
        Optional<ConfigTree> merged = Optional.empty();

        for (ConfigSource source : sources) {
            JsonNode parsed;
            try {
                parsed = TOML.readTree(source.text());
            } catch (JacksonException e) {
                parseProblems = parseProblems.add(
                        Problems.configUnparseable(source.origin(), firstLineOf(e.getOriginalMessage())));
                continue;
            }
            if (source.kind() == ConfigSource.Kind.LAMP)
                parseProblems = parseProblems.addAll(checkSchemaVersion(source, parsed));
            merged = Optional.of(merged.isEmpty()
                    ? ConfigTree.of(parsed, source.origin())
                    : merged.get().mergedWith(parsed, source.origin()));
        }

        if (!parseProblems.isEmpty() && merged.isEmpty())
            return Result.err(parseProblems);

        ConfigSection root = merged.isEmpty()
                ? ConfigSection.root(ConfigTree.of(TOML.createObjectNode(), ConfigSource.BUILT_IN))
                : ConfigSection.root(merged.get());

        LampConfig config = read(root);
        Tuple<Problem> problems = parseProblems.addAll(root.problems());
        Tuple<Problem> errors = problems.retainIf(Problem::isError);
        return errors.isEmpty() ? Result.ok(config, problems) : Result.err(problems);
    }

    private static Tuple<Problem> checkSchemaVersion(ConfigSource source, JsonNode parsed) {
        JsonNode version = parsed.get("schema_version");
        if (version == null || version.isNull())
            return Tuple.of(Problem.class, Problems.configInvalidValue(source.origin(), "schema_version",
                    "(missing)", "every lamp config declares schema_version = " + LampConfig.SCHEMA_VERSION));
        if (!version.isIntegralNumber())
            return Tuple.of(Problem.class, Problems.configWrongType(source.origin(), "schema_version",
                    version.asText(), "a whole number"));
        if (version.asInt() > LampConfig.SCHEMA_VERSION)
            return Tuple.of(Problem.class, Problems.configInvalidValue(source.origin(), "schema_version",
                    version.asText(), "this oillamp understands up to " + LampConfig.SCHEMA_VERSION
                            + " — upgrade oillamp to read this lamp"));
        return Tuple.of(Problem.class);
    }

    // ─── the schema ────────────────────────────────────────────────────────────────────────

    private static LampConfig read(ConfigSection root) {
        LampConfig defaults = ConfigDefaults.lampConfig();
        root.allowOnly("schema_version", "display", "viewer", "terminal", "recording",
                       "limits", "network", "llm", "model", "git", "agent_tools", "image", "host", "timeouts",
                       "schedule");

        LampConfig.Display display   = readDisplay(root.table("display"), defaults.display());
        LampConfig.Viewer viewer     = readViewer(root.table("viewer"), defaults.viewer());
        LampConfig.Terminal terminal = readTerminal(root.table("terminal"), defaults.terminal());
        LampConfig.Recording recording = readRecording(root.table("recording"), defaults.recording());
        LampConfig.Limits limits     = readLimits(root.table("limits"), defaults.limits());

        ConfigSection networkSection = root.table("network");
        networkSection.allowOnly("default", "log_allowed", "console_denied", "rules", "forwards");
        NetworkPolicy network        = readNetwork(networkSection, defaults.network());
        Tuple<Forward> forwards      = readForwards(networkSection);

        Optional<LampConfig.Llm> llm = readLlm(root.table("llm"), forwards);
        LampConfig.Model model       = readModel(root.table("model"), defaults.model());
        LampConfig.Git git           = readGit(root.table("git"), defaults.git());
        LampConfig.AgentTools tools  = readAgentTools(root.table("agent_tools"), defaults.agentTools());
        LampConfig.Image image       = readImage(root.table("image"), defaults.image());
        LampConfig.Host host         = readHost(root.table("host"), defaults.host());
        LampConfig.Timeouts timeouts = readTimeouts(root.table("timeouts"), defaults.timeouts());
        LampConfig.Schedule schedule = readSchedule(root.table("schedule"), defaults.schedule());

        return new LampConfig(display, viewer, terminal, recording, limits,
                              network, forwards, llm, model, git, tools, image, host, timeouts, schedule);
    }

    private static LampConfig.Display readDisplay(ConfigSection s, LampConfig.Display fallback) {
        s.allowOnly("width", "height", "scale", "gpu", "windows");
        int width   = bounded(s, "width",  fallback.width(),  640, 7680, "a width between 640 and 7680");
        int height  = bounded(s, "height", fallback.height(), 480, 4320, "a height between 480 and 4320");
        double scale = s.number("scale", fallback.scale());
        if (scale < 0.5 || scale > 4.0) {
            s.invalid("scale", Double.toString(scale), "a scale between 0.5 and 4.0");
            scale = fallback.scale();
        }
        GpuMode gpu = s.oneOf("gpu", fallback.gpu(), ConfigLoader::parseGpuMode, "\"auto\", \"on\" or \"off\"");
        WindowLayout windows = s.oneOf("windows", fallback.windows(), ConfigLoader::parseWindowLayout,
                                       "\"floating\" or \"tiling\"");
        return new LampConfig.Display(width, height, scale, gpu, windows);
    }

    private static LampConfig.Viewer readViewer(ConfigSection s, LampConfig.Viewer fallback) {
        s.allowOnly("open_on_start", "clipboard", "view_only", "max_fps");
        return new LampConfig.Viewer(
                s.bool("open_on_start", fallback.openOnStart()),
                s.oneOf("clipboard", fallback.clipboard(), ConfigLoader::parseClipboard,
                        "\"to-agent\", \"both\" or \"none\""),
                s.bool("view_only", fallback.viewOnly()),
                bounded(s, "max_fps", fallback.maxFps(), 1, 120, "a frame rate between 1 and 120"));
    }

    private static LampConfig.Terminal readTerminal(ConfigSection s, LampConfig.Terminal fallback) {
        s.allowOnly("profile", "command");
        Tuple<String> command = s.strings("command", Tuple.of(String.class));
        if (!command.isEmpty()) {
            // A custom command replaces the profile entirely, so it must contain {cmd}.
            if (!command.any(token -> token.contains(Terminals.COMMAND_PLACEHOLDER)))
                s.invalid("command", String.join(" ", command),
                          "the template must contain {cmd}, which expands to the ssh command line");
            return new LampConfig.Terminal.Custom(command);
        }
        String profile = s.string("profile", "auto");
        if (profile.equals("auto")) return fallback;
        for (TerminalProfileId id : TerminalProfileId.values())
            if (id.configName().equals(profile)) return new LampConfig.Terminal.Profile(id);
        s.invalid("profile", "\"" + profile + "\"",
                  "expected \"auto\" or one of: "
                  + String.join(", ", Terminals.supportedNames()));
        return fallback;
    }

    private static LampConfig.Recording readRecording(ConfigSection s, LampConfig.Recording fallback) {
        s.allowOnly("enabled", "codec", "crf", "max_fps", "max_age_days", "max_total_gb");
        return new LampConfig.Recording(
                s.bool("enabled", fallback.enabled()),
                s.string("codec", fallback.codec()),
                bounded(s, "crf", fallback.crf(), 0, 51, "an x264 CRF between 0 and 51"),
                bounded(s, "max_fps", fallback.maxFps(), 1, 60, "a frame rate between 1 and 60"),
                bounded(s, "max_age_days", fallback.maxAgeDays(), 0, 3650, "a number of days, 0 for no age limit"),
                bounded(s, "max_total_gb", fallback.maxTotalGb(), 0, 10_000, "a size in GB, 0 for no size limit"));
    }

    private static LampConfig.Limits readLimits(ConfigSection s, LampConfig.Limits fallback) {
        s.allowOnly("memory", "cpus", "pids");
        String memory = s.string("memory", fallback.memory());
        if (!memory.matches("\\d+[kmgKMG]?")) {
            s.invalid("memory", "\"" + memory + "\"", "a size such as \"16g\", \"512m\" or \"2048\"");
            memory = fallback.memory();
        }
        return new LampConfig.Limits(memory,
                bounded(s, "cpus", fallback.cpus(), 0, 1024, "a CPU count, 0 for \"all but one\""),
                bounded(s, "pids", fallback.pids(), 64, 1_000_000, "a process limit of at least 64"));
    }

    private static NetworkPolicy readNetwork(ConfigSection s, NetworkPolicy fallback) {
        Decision fallbackDecision = fallback.defaultDecision();
        Decision defaultDecision = s.oneOf("default", fallbackDecision,
                ConfigLoader::parseDecision, "\"allow\" or \"deny\"");
        boolean logAllowed    = s.bool("log_allowed", fallback.logAllowed());
        boolean consoleDenied = s.bool("console_denied", fallback.consoleDenied());

        Tuple<ConfigSection> ruleSections = s.tableArray("rules");
        if (ruleSections.isEmpty() && !s.has("rules"))
            return new NetworkPolicy(defaultDecision, fallback.rules(), logAllowed, consoleDenied);

        Tuple<Rule> rules = Tuple.of(Rule.class);
        for (ConfigSection section : ruleSections) {
            Optional<Rule> rule = readRule(section);
            if (rule.isPresent()) rules = rules.add(rule.get());
        }
        return new NetworkPolicy(defaultDecision, rules, logAllowed, consoleDenied);
    }

    private static Optional<Rule> readRule(ConfigSection s) {
        s.allowOnly("label", "action", "hosts", "ports", "cidrs");
        String label = s.string("label", "");
        if (label.isBlank()) {
            s.invalid("label", "(missing)",
                      "every rule needs a label — it is quoted in the log and in the 403 the agent sees");
            return Optional.empty();
        }
        Optional<Decision> action = s.requiredOneOf("action", ConfigLoader::parseDecision,
                                                    "\"allow\" or \"deny\"");
        if (action.isEmpty()) return Optional.empty();

        Tuple<HostPattern> hosts = Tuple.of(HostPattern.class);
        for (String pattern : s.strings("hosts", Tuple.of(String.class))) {
            if (pattern.isBlank() || pattern.chars().filter(c -> c == '*').count() > 1
                    || (pattern.contains("*") && !pattern.equals("*") && !pattern.startsWith("*."))) {
                s.invalid("hosts", "\"" + pattern + "\"",
                          "expected an exact host, \"*.example.com\", or \"*\"");
                continue;
            }
            hosts = hosts.add(HostPattern.parse(pattern));
        }

        Tuple<PortRange> ports = Tuple.of(PortRange.class);
        for (String port : s.scalarsAsText("ports")) {
            Optional<PortRange> range = PortRange.parse(port);
            if (range.isEmpty()) {
                s.invalid("ports", "\"" + port + "\"", "expected a port such as 443, or a range such as \"8000-8100\"");
                continue;
            }
            ports = ports.add(range.get());
        }

        Tuple<Cidr> cidrs = Tuple.of(Cidr.class);
        for (String text : s.strings("cidrs", Tuple.of(String.class))) {
            Optional<Cidr> cidr = Cidr.parse(text);
            if (cidr.isEmpty()) {
                s.invalid("cidrs", "\"" + text + "\"",
                          "expected a network such as \"10.0.0.0/8\" or \"fc00::/7\"");
                continue;
            }
            cidrs = cidrs.add(cidr.get());
        }
        return Optional.of(new Rule(label, action.get(), hosts, ports, cidrs));
    }

    private static Tuple<Forward> readForwards(ConfigSection network) {
        Tuple<Forward> forwards = Tuple.of(Forward.class);
        for (ConfigSection s : network.tableArray("forwards")) {
            s.allowOnly("name", "port", "target");
            String name = s.string("name", "");
            if (!name.matches("[a-z0-9-]+")) {
                s.invalid("name", "\"" + name + "\"", "expected lowercase letters, digits and dashes");
                continue;
            }
            int port = s.integer("port", 0);
            if (port < 1024 || port > 65535) {
                s.invalid("port", Integer.toString(port), "expected a port between 1024 and 65535");
                continue;
            }
            if (port == Forward.PROXY_PORT) {
                s.invalid("port", Integer.toString(port),
                          "port " + Forward.PROXY_PORT + " is the sandbox's egress proxy — pick another");
                continue;
            }
            if (port == Forward.MODEL_PORT) {
                s.invalid("port", Integer.toString(port),
                          "port " + Forward.MODEL_PORT + " is where the sandbox reaches the model service "
                        + "through oillamp — pick another");
                continue;
            }
            String target = s.string("target", "");
            HostAndPort endpoint;
            try {
                endpoint = HostAndPort.parse(target);
            } catch (IllegalArgumentException e) {
                s.invalid("target", "\"" + target + "\"", "expected host:port, e.g. \"llm.corp.example.com:8000\"");
                continue;
            }
            Forward forward = new Forward(name, port, endpoint);
            String clash = firstClashWith(forwards, forward);
            if (!clash.isEmpty()) {
                s.invalidHere("\"" + name + "\" on port " + port, clash);
                continue;
            }
            forwards = forwards.add(forward);
        }
        return forwards;
    }

    /// Two forwards sharing a name or a port would make the sandbox's port map ambiguous.
    private static String firstClashWith(Tuple<Forward> existing, Forward candidate) {
        for (Forward other : existing) {
            if (other.name().equals(candidate.name()))
                return "forward names must be unique — \"" + candidate.name() + "\" is already used";
            if (other.port() == candidate.port())
                return "forward ports must be unique — port " + candidate.port()
                     + " is already used by \"" + other.name() + "\"";
        }
        return "";
    }

    private static Optional<LampConfig.Llm> readLlm(ConfigSection s, Tuple<Forward> forwards) {
        s.allowOnly("forward", "base_path", "api_key_env", "api_key_file", "models", "provider_name");
        String forward = s.string("forward", "");
        if (forward.isBlank()) return Optional.empty();          // no LLM configured
        boolean known = forwards.any(f -> f.name().equals(forward));
        if (!known) {
            s.invalid("forward", "\"" + forward + "\"",
                      forwards.isEmpty()
                          ? "no [[network.forwards]] are configured — add one before pointing llm.forward at it"
                          : "expected one of the configured forwards: " + names(forwards));
            return Optional.empty();
        }
        return Optional.of(new LampConfig.Llm(
                forward,
                s.string("base_path", "/v1"),
                s.string("api_key_env", "OILLAMP_LLM_API_KEY"),
                s.string("api_key_file", ""),
                s.strings("models", Tuple.of(String.class)),
                s.string("provider_name", "company")));
    }

    private static LampConfig.Model readModel(ConfigSection s, LampConfig.Model fallback) {
        s.allowOnly("service", "key_env");
        java.net.URI service = fallback.service();
        String text = s.string("service", fallback.service().toString());
        Optional<String> wrong = serviceProblem(text);
        if (wrong.isPresent()) s.invalid("service", "\"" + text + "\"", wrong.get());
        else service = java.net.URI.create(text);
        String keyEnv = s.string("key_env", fallback.keyEnv());
        if (!isVariableName(keyEnv)) {
            s.invalid("key_env", "\"" + keyEnv + "\"", "expected the name of an environment variable, "
                    + "such as \"EDENAI_API_KEY\"");
            keyEnv = fallback.keyEnv();
        }
        return new LampConfig.Model(service, keyEnv);
    }

    static boolean isVariableName(String text) { return text.matches("[A-Za-z_][A-Za-z0-9_]*"); }

    /// Why `text` cannot be the model service, or empty if it can. It is a scheme, a host, an
    /// optional port and an optional path, such as `/v1` for a model server whose API is there;
    /// no user, query or fragment. The key travels to it, so it must be `https`, except for a
    /// service on this machine's own loopback.
    static Optional<String> serviceProblem(String text) {
        java.net.URI uri;
        try {
            uri = new java.net.URI(text);
        } catch (java.net.URISyntaxException e) {
            return Optional.of("expected an address such as \"https://api.eu.edenai.run\"");
        }
        String host = Optional.ofNullable(uri.getHost()).orElse("");
        if (host.isEmpty() || !Optional.ofNullable(uri.getScheme()).orElse("").matches("https?")
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !Optional.ofNullable(uri.getRawPath()).orElse("").matches("(/[A-Za-z0-9._~-]+)*/?"))
            return Optional.of("expected a scheme, a host, and optionally a port and a path, "
                    + "such as \"https://api.eu.edenai.run\" or \"http://127.0.0.1:11434/v1\"");
        boolean loopback = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]");
        if (uri.getScheme().equals("http") && !loopback)
            return Optional.of("the key is sent to this service, so it must be https:// "
                    + "(plain http:// only for a service on this machine, such as http://127.0.0.1:8080)");
        return Optional.empty();
    }

    private static LampConfig.Git readGit(ConfigSection s, LampConfig.Git fallback) {
        s.allowOnly("identity", "name", "email");
        GitIdentity identity = s.oneOf("identity", fallback.identity(), ConfigLoader::parseGitIdentity,
                                       "\"genie\", \"host\", \"custom\" or \"none\"");
        String name = oneLine(s, "name", fallback.name());
        String email = oneLine(s, "email", fallback.email());
        if (identity == GitIdentity.CUSTOM && (name.isBlank() || email.isBlank()))
            s.invalid("identity", "\"custom\"", "a custom identity needs both git.name and git.email");
        return new LampConfig.Git(identity, name, email);
    }

    private static String oneLine(ConfigSection s, String key, String fallback) {
        String value = s.string(key, fallback);
        if (value.indexOf('\n') < 0 && value.indexOf('\r') < 0) return value;
        s.invalid(key, "(several lines)", "expected a single line");
        return fallback;
    }

    private static LampConfig.AgentTools readAgentTools(ConfigSection s, LampConfig.AgentTools fallback) {
        s.allowOnly("install", "versions");
        Tuple<String> install = s.strings("install", fallback.install());
        var versions = s.has("versions") ? s.stringTable("versions") : fallback.versions();
        return new LampConfig.AgentTools(install, versions);
    }

    private static LampConfig.Image readImage(ConfigSection s, LampConfig.Image fallback) {
        s.allowOnly("base", "node_version", "jdk_package", "extra_apt_packages");
        return new LampConfig.Image(
                s.string("base", fallback.base()),
                s.string("node_version", fallback.nodeVersion()),
                s.string("jdk_package", fallback.jdkPackage()),
                s.strings("extra_apt_packages", fallback.extraAptPackages()));
    }

    private static LampConfig.Host readHost(ConfigSection s, LampConfig.Host fallback) {
        s.allowOnly("auto_install");
        return new LampConfig.Host(s.bool("auto_install", fallback.autoInstall()));
    }

    private static LampConfig.Timeouts readTimeouts(ConfigSection s, LampConfig.Timeouts fallback) {
        s.allowOnly("container_ready_seconds", "terminal_connect_seconds", "stop_seconds");
        return new LampConfig.Timeouts(
                bounded(s, "container_ready_seconds", fallback.containerReadySeconds(), 1, 3600, "a number of seconds"),
                bounded(s, "terminal_connect_seconds", fallback.terminalConnectSeconds(), 1, 3600, "a number of seconds"),
                bounded(s, "stop_seconds", fallback.stopSeconds(), 1, 600, "a number of seconds"));
    }

    private static LampConfig.Schedule readSchedule(ConfigSection s, LampConfig.Schedule fallback) {
        s.allowOnly("enabled", "max_agent_jobs", "min_agent_interval_minutes", "max_agent_days",
                    "max_agent_runs_per_day", "max_run_minutes", "notes_max_kb");
        return new LampConfig.Schedule(
                s.bool("enabled", fallback.enabled()),
                bounded(s, "max_agent_jobs", fallback.maxAgentJobs(), 0, 100, "a number of jobs from 0 to 100"),
                bounded(s, "min_agent_interval_minutes", fallback.minAgentIntervalMinutes(), 1, 7 * 24 * 60,
                        "a number of minutes, at least 1"),
                bounded(s, "max_agent_days", fallback.maxAgentDays(), 1, 366, "a number of days from 1 to 366"),
                bounded(s, "max_agent_runs_per_day", fallback.maxAgentRunsPerDay(), 0, 1440,
                        "a number of runs from 0 to 1440"),
                bounded(s, "max_run_minutes", fallback.maxRunMinutes(), 1, 24 * 60,
                        "a number of minutes from 1 to 1440"),
                bounded(s, "notes_max_kb", fallback.notesMaxKb(), 1, 1024, "a size in kilobytes from 1 to 1024"));
    }

    // ─── helpers ───────────────────────────────────────────────────────────────────────────

    private static int bounded(ConfigSection s, String key, int fallback, int min, int max, String expected) {
        int value = s.integer(key, fallback);
        if (value < min || value > max) {
            s.invalid(key, Integer.toString(value), "expected " + expected);
            return fallback;
        }
        return value;
    }

    private static Optional<GpuMode> parseGpuMode(String text) {
        for (GpuMode mode : GpuMode.values())
            if (mode.configName().equals(text.toLowerCase(Locale.ROOT))) return Optional.of(mode);
        return Optional.empty();
    }

    private static Optional<WindowLayout> parseWindowLayout(String text) {
        for (WindowLayout layout : WindowLayout.values())
            if (layout.configName().equals(text.toLowerCase(Locale.ROOT))) return Optional.of(layout);
        return Optional.empty();
    }

    private static Optional<GitIdentity> parseGitIdentity(String text) {
        for (GitIdentity identity : GitIdentity.values())
            if (identity.configName().equals(text.toLowerCase(Locale.ROOT))) return Optional.of(identity);
        return Optional.empty();
    }

    private static Optional<ClipboardMode> parseClipboard(String text) {
        for (ClipboardMode mode : ClipboardMode.values())
            if (mode.configName().equals(text.toLowerCase(Locale.ROOT))) return Optional.of(mode);
        return Optional.empty();
    }

    private static Optional<Decision> parseDecision(String text) {
        for (Decision decision : Decision.values())
            if (decision.configName().equals(text.toLowerCase(Locale.ROOT))) return Optional.of(decision);
        return Optional.empty();
    }

    private static String names(Tuple<Forward> forwards) {
        Tuple<String> out = Tuple.of(String.class);
        for (Forward forward : forwards) out = out.add("\"" + forward.name() + "\"");
        return String.join(", ", out);
    }

    private static String firstLineOf(String message) {
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }
}
