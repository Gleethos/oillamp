package dev.oillamp;


/**
 * The files oillamp writes into a new lamp for the <em>user</em> to read and edit — spec §20.2.
 *
 * <p>Held as text blocks rather than classpath resources so that the pure core stays free of I/O
 * (§22) and so that a scenario can assert the shipped template and the built-in defaults agree.
 * A commented template that has drifted from the real defaults is worse than no template at all.
 */
final class Templates {

    private Templates() {}

    /**
     * The commented configuration written into a new lamp.
     *
     * <p>Every value here must parse to exactly {@code ConfigDefaults.lampConfig()} — a scenario
     * checks it, so this file can be trusted as documentation.
     */
    public static String defaultConfig() {
        return """
            # oillamp lamp configuration. Edited by you, never visible to the agent.
            # Changes take effect at the next `oillamp at`. Arrays replace (not merge with) values
            # from ~/.config/oillamp/config.toml.
            schema_version = 1

            [display]
            width  = 1920
            height = 1080
            scale  = 1.0              # 1.0, 1.25, 1.5, 2.0 …
            gpu    = "auto"           # "auto" | "on" | "off"

            [viewer]
            open_on_start = true
            clipboard     = "to-agent" # "to-agent" | "both" | "none"
            view_only     = false
            max_fps       = 30

            [terminal]
            profile = "auto"          # "auto" | "ptyxis" | "gnome-terminal" | "kgx" | "konsole" | "kitty" | "foot" | "alacritty" | "wezterm" | "xterm"
            # command = ["myterm", "--title", "{title}", "--", "{cmd}"]   # custom terminal; overrides profile

            [recording]
            enabled        = true
            codec          = "libx264"
            crf            = 30
            max_fps        = 10
            max_age_days   = 14
            max_total_gb   = 20

            [limits]
            memory = "16g"
            cpus   = 0                # 0 = host CPUs minus one
            pids   = 8192

            [network]
            default        = "allow"  # applies when no rule matches
            log_allowed    = true
            console_denied = true

            # Rules are evaluated top to bottom; the first match wins. Put allow-exceptions ABOVE the deny rule.
            # [[network.rules]]
            # label  = "internal maven mirror"
            # action = "allow"
            # hosts  = ["nexus.corp.example.com"]
            # ports  = [443]

            [[network.rules]]
            label  = "block private, internal and loopback ranges"
            action = "deny"
            cidrs  = ["10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "100.64.0.0/10",
                      "127.0.0.0/8", "169.254.0.0/16", "0.0.0.0/8",
                      "::1/128", "fc00::/7", "fe80::/10"]

            # Example: allow-list mode — set default = "deny" above and list what is allowed:
            # [[network.rules]]
            # label  = "package registries"
            # action = "allow"
            # hosts  = ["registry.npmjs.org", "repo1.maven.org", "*.pythonhosted.org", "pypi.org"]

            # Forwards expose one fixed target at 127.0.0.1:<port> inside the sandbox (not subject to rules).
            # [[network.forwards]]
            # name   = "llm"
            # port   = 8000
            # target = "llm.corp.example.com:8000"

            [llm]
            forward       = ""        # e.g. "llm" — enables preconfiguration of the agent tools
            base_path     = "/v1"
            api_key_env   = "OILLAMP_LLM_API_KEY"
            api_key_file  = ""
            models        = []
            provider_name = "company"

            [agent_tools]
            install  = ["opencode", "pi"]
            versions = { opencode = "latest", pi = "latest" }

            [image]
            base               = "docker.io/library/debian:trixie"
            node_version       = "24"          # major LTS line; exact version resolved at build
            jdk_package        = "temurin-25-jdk"
            extra_apt_packages = []

            [host]
            auto_install = true       # install missing host packages with sudo apt-get (logged)

            [timeouts]
            container_ready_seconds  = 45
            terminal_connect_seconds = 60
            stop_seconds             = 15
            """;
    }

    /** A short note so that someone finding this directory in six months knows what it is. */
    public static String readme(LampLayout layout) {
        return """
            This directory is an oillamp lamp: one sandboxed Linux machine with its own desktop,
            for an AI coding agent to work in.

              oillamp at %s

            starts it, opens a terminal already logged into the sandbox, and opens a window showing
            the agent's desktop. Closing that terminal shuts the sandbox down again.

            What is here
              oillamp.toml            Your settings: display size, recording, and the network policy.
                                      The agent cannot see or change this file — that is the point.
              agent-lamp-%s/   The agent's whole world. It is mounted as /home/agent inside
                                      the sandbox and persists between sessions. Put repositories in
                                      its workspace/ and native libraries in its libs/.
              .oillamp/               oillamp's own state: identity, SSH keys, logs, screen
                                      recordings and sockets. Not visible to the agent.

            Everything outside agent-lamp-%s/ is invisible to the agent, including this file.
            """.formatted(layout.root(), layout.agentId().value(), layout.agentId().value());
    }
}
