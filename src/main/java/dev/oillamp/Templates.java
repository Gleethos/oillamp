package dev.oillamp;


/// The text of files oillamp generates: the commented `oillamp.toml`, the lamp's
/// `README.txt`, the agent's `.bashrc` and the bash completion script.
///
/// They are Java text blocks rather than resource files, so no file needs to be read to produce
/// them.
final class Templates {

    private Templates() {}

    /// The commented configuration written into a new lamp.
    ///
    /// Every value here must match [ConfigDefaults#lampConfig()]. A scenario in
    /// `ConfiguringALampSpec` checks this, because many users read this file as the
    /// documentation of the settings.
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
            windows = "floating"      # "floating": move by the title bar, resize by the edges
                                      # "tiling": windows share the screen and never overlap

            [viewer]
            open_on_start = true
            clipboard     = "to-agent" # "to-agent" | "both" | "none"
            view_only     = false
            max_fps       = 30

            [terminal]
            profile = "auto"          # "auto" | "ptyxis" | "gnome-terminal" | "kgx" | "konsole" | "kitty" | "foot" | "alacritty" | "wezterm" | "xterm"
            # command = ["myterm", "--title", "{title}", "--", "{cmd}"]   # custom terminal; overrides profile

            # Records the sandbox desktop to .oillamp/recordings/<session>.mkv, so you can watch
            # what an agent did. Off by default: it is a continuous recording of the screen, which
            # should be switched on deliberately. The recording runs at max_fps frames per second.
            # The agent can read the recording but cannot stop, change or delete it: it is written
            # by the sandbox's `lamp` user, outside the agent's home.
            # `oillamp recordings <lamp>` lists them.
            [recording]
            enabled        = false    # true to record the desktop for the whole session
            codec          = "libx264" # wf-recorder codec; "h264_vaapi" when a GPU is in use
            crf            = 30       # quality, lower is better and bigger (x264 CRF)
            max_fps        = 10       # frame cap; screen content rarely needs more
            max_age_days   = 14       # retention: delete recordings older than this
            max_total_gb   = 20       # retention: delete oldest until the total fits

            [limits]
            memory = "16g"
            cpus   = 0                # 0 = host CPUs minus one
            pids   = 8192

            [network]
            default        = "allow"  # applies when no rule matches
            log_allowed    = true
            console_denied = true

            # Rules are evaluated top to bottom; the first match wins. Put allow-exceptions ABOVE the deny rules.
            # [[network.rules]]
            # label  = "internal maven mirror"
            # action = "allow"
            # hosts  = ["nexus.corp.example.com"]
            # ports  = [443]

            # Eden AI is only used through its EU endpoint, api.eu.edenai.run. Remove this rule to allow the global one.
            [[network.rules]]
            label  = "Eden AI only through its EU endpoint"
            action = "deny"
            hosts  = ["api.edenai.run"]

            [[network.rules]]
            label  = "block private, internal and loopback ranges"
            action = "deny"
            cidrs  = ["10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "100.64.0.0/10",
                      "127.0.0.0/8", "169.254.0.0/16", "0.0.0.0/8",
                      "::1/128", "::/128", "fc00::/7", "fe80::/10"]

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
            container_ready_seconds  = 60
            terminal_connect_seconds = 60
            stop_seconds             = 15
            """;
    }

    /// The bash completion script, printed by `oillamp completion bash`.
    ///
    /// Printed rather than installed, because oillamp itself is never installed anywhere. The user
    /// decides whether to evaluate it in one shell or add it to their startup file.
    ///
    /// After the command name it completes directories, because every command that takes an
    /// argument takes a lamp directory.
    public static String bashCompletion() {
        return """
            # oillamp bash completion.
            #
            # For this shell only:      eval "$(oillamp completion bash)"
            # For every future shell:   echo 'eval "$(oillamp completion bash)"' >> ~/.bashrc
            _oillamp() {
                local commands='at view shell stop status list remove recordings doctor config guide about version help'
                local previous="${COMP_WORDS[COMP_CWORD-1]}"
                local current="${COMP_WORDS[COMP_CWORD]}"

                # The word right after `config` is what to do with it, not a path.
                if [ "$previous" = config ]; then
                    COMPREPLY=($(compgen -W 'check show-effective path' -- "$current"))
                    return
                fi

                case "$current" in
                    -*)
                        COMPREPLY=($(compgen -W '--verbose --debug --no-color --dry-run \
                            --no-install --init --no-viewer --view-only --yes --open --prune' \
                            -- "$current"))
                        return ;;
                esac

                # The first word is the command; everything after it is a lamp directory.
                local seen=0 word
                for word in "${COMP_WORDS[@]:1:COMP_CWORD-1}"; do
                    case " $commands " in *" $word "*) seen=1; break ;; esac
                done
                if [ "$seen" = 0 ]; then
                    COMPREPLY=($(compgen -W "$commands" -- "$current"))
                else
                    COMPREPLY=($(compgen -d -- "$current"))
                fi
            }
            complete -F _oillamp oillamp
            """;
    }

    /// The agent's `~/.bashrc`.
    ///
    /// `/etc/profile` is read only by login shells. The terminal oillamp opens gets one;
    /// `ssh <lamp> 'some command'` does not, so without this file a scripted command would run
    /// with no proxy settings, no display and no `sdk`. Written only if absent, because after
    /// that it belongs to the agent.
    public static String agentBashrc() {
        return """
            # Written by oillamp when this lamp was created, and never again: from here on it is
            # yours. Add what you like.
            #
            # /etc/profile — where the proxy variables, the desktop and `sdk` come from — is read
            # by *login* shells only. `ssh <lamp> 'some command'` does not get one. Bash does read
            # this file in that case, which is what makes a scripted command see the same
            # environment as the terminal oillamp opens for you. Sourcing it twice is harmless.
            [ -r /etc/profile.d/oillamp.sh ] && . /etc/profile.d/oillamp.sh
            """;
    }

    /// The lamp's `README.txt`, so that someone who finds the directory later knows what it is.
    public static String readme(LampLayout layout) {
        return """
            This directory is an oillamp lamp: one sandboxed Linux machine with its own desktop,
            for an AI coding agent to work in.

              oillamp at %s

            starts it, opens a terminal already logged into the sandbox, and opens a window showing
            the agent's desktop. Ctrl-C in the terminal you ran it from shuts the sandbox down
            again. Closing the shell or desktop window does not; `oillamp shell` and `oillamp view`
            open new ones.

            What is here
              oillamp.toml            Your settings: display size, recording, and the network policy.
                                      The agent cannot see or change this file — that is the point.
              agent-lamp-%s/   The agent's whole world. It is mounted as /home/agent inside
                                      the sandbox and persists between sessions. Put repositories in
                                      its workspace/ and native libraries in its libs/.
              .oillamp/               oillamp's own state: identity, SSH keys, logs, screen
                                      recordings and sockets. Not visible to the agent.

            Everything outside agent-lamp-%s/ is invisible to the agent, including this file.

            Deleting this lamp
              Use `oillamp remove %s`, not `rm -rf`.

              Some of .oillamp/ is deliberately not yours. The sandbox runs a second user that
              owns the compositor, the screen recorder and their sockets, and that user is what
              stops the agent tampering with the recording of its own screen. Inside the container
              it is uid 1001; out here it is a subordinate id you have no permission over, so
              `rm -rf` deletes most of the lamp and then stops with "Permission denied" on a
              socket you have never heard of.

              `oillamp remove` deletes it from inside podman's user namespace, where those files
              can be reached. It takes the agent's home with it, so it asks first: without --yes
              it only prints what would go. It works on a lamp you already tried to delete by
              hand, and it refuses while a sandbox is still running.
            """.formatted(layout.root(), layout.agentId().value(), layout.agentId().value(),
                          layout.root());
    }
}
