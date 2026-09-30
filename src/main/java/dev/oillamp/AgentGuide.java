package dev.oillamp;


/// Writes `~/AGENTS.md`, the guide that tells the agent what kind of machine it is on.
///
/// An agent that does not know it is in a sandbox wastes time: it tries `sudo apt install`,
/// wonders why DNS does not work, and reports "the network is broken" when a policy rule refused one
/// host. The guide is rewritten every session from the current configuration and says what
/// persists, how to use the desktop, how the network works and why a request may be refused.
///
/// It is open about the sandbox's limits, so the agent can work within them and report
/// accurately when it hits one.
final class AgentGuide {

    private AgentGuide() {}

    public static String render(LampConfig config, LampLayout layout) {
        StringBuilder out = new StringBuilder();
        out.append("""
            # This machine

            You are working inside a sandbox created by oillamp. It is a container with its own
            Linux user, its own graphical desktop, and no direct network access.

            """);
        // Recording is off unless the user turned it on, so the guide says which is the case.
        out.append(config.recording().enabled()
                ? "A human can watch this desktop live, and everything on screen is being "
                + "recorded\nto a file they keep. You cannot stop, read or alter that recording.\n"
                : "A human can watch this desktop live. Nothing is being recorded — this lamp "
                + "has\nrecording switched off.\n");
        out.append("""

            ## What persists

            Only your home directory, `/home/agent`, survives between sessions. Everything else —
            installed system packages, files in `/tmp`, anything under `/usr` — is discarded when
            the session ends, because the root filesystem comes from a read-only image.

            - `~/workspace` — put repositories here. Your shell starts in it.
            - `~/libs` — native libraries. Already on `LD_LIBRARY_PATH` and `java.library.path`,
              so `System.loadLibrary` finds them with no extra flags.
            - `~/screenshots` — where `lamp screenshot` writes by default.

            Language-level dependencies (Maven, npm, pip, venvs) install into your home and so
            persist, as does anything you install with SDKMAN — see below. System packages do
            not: there is no `sudo` and no `apt` here. If you need one, say so — the human adds
            it to `image.extra_apt_packages` and rebuilds.

            ## The desktop

            """);
        out.append("You have a real graphical desktop at ")
           .append(config.display().size())
           .append(", running a Wayland compositor with Xwayland available, so Swing and other\n")
           .append("X11 applications work. Launch GUI applications from your shell as you would\n")
           .append("anywhere else; they appear on that desktop and the human sees them.\n\n");
        // How windows behave decides how the agent gets at a window that is covered or too small.
        out.append(config.display().windows() == WindowLayout.FLOATING
                ? "Windows float, as on most desktops: each opens at the size its application asks\n"
                + "for and may cover others. Move one by dragging its title bar, and resize it by\n"
                + "dragging its edge, with `lamp drag` (below). Take a screenshot first to find them.\n\n"
                : "Windows are tiled: they share the screen side by side and never cover each\n"
                + "other, and a single window fills the whole screen. They cannot be moved or\n"
                + "resized by dragging.\n\n");
        out.append("""
            Both kinds of application are already set up, with nothing to configure:

            - Wayland applications use `WAYLAND_DISPLAY=/run/lamp/wayland-1`.
            - X11 applications, including Java Swing and AWT, use `DISPLAY=:0`, served by
              Xwayland. Run them normally, without `-Djava.awt.headless=true`.

            `xdpyinfo | head -2` checks the X11 display. If it fails, or an application reports
            "Can't connect to X11 window server" or "Authorization required", the sandbox is at
            fault, not your program. Do not start your own X server (`Xwayland`, `Xvfb`): it
            would not appear on the human's desktop in a usable way. Report the exact error to
            the human instead; restarting the session with `oillamp at` usually fixes it.

            """);
        out.append("""
            The `lamp` command drives it:

            ```
            lamp screenshot                 # PNG of the whole screen; prints the path
            lamp screenshot --region X,Y,W,H
            lamp click X Y                  # click at screen pixels X,Y (as in a screenshot)
            lamp click X Y double           # double click
            lamp click X Y right            # right (or middle) button
            lamp move X Y                   # move the pointer to X,Y
            lamp drag X1 Y1 X2 Y2
            lamp scroll X Y 3               # scroll down 3 steps at X,Y; negative scrolls up
            lamp type "some text"
            lamp key ctrl+shift+t
            lamp wait-stable                # block until the screen stops changing
            lamp info                       # size, renderer, output name
            ```

            This works the same for Wayland and X11 applications, including Java Swing. Click a
            text field before typing into it, so it has the keyboard focus.

            `lamp click` and `lamp type` report no result. After any action, `lamp wait-stable`
            then `lamp screenshot` is the reliable way to see what actually happened. After
            launching an application, the same shows whether it appeared.

            `lamp` is a shell script and a convenience, not a gate. It is a thin wrapper over
            tools that are installed here and that you may call directly whenever it does not
            do what you need:

            | Tool | For |
            |---|---|
            | `grim` | capture the screen or a region to PNG |
            | `slurp` | pick a region interactively |
            | `wtype -s 150` | type text, press key combinations (keep the `-s 150` pause: without it X11 applications lose the first key) |
            | `/usr/local/lib/oillamp/lamp-pointer` | move, click, drag and scroll at screen positions |

            Do not use `wlrctl pointer` to click: it moves the pointer by an offset, not to a
            position, and its clicks do not reach any window.

            Read the script with `cat $(command -v lamp)` if you want to see exactly what it
            runs. If you find something it should do and does not, say so; it is one file.

            ## Network

            """);
        out.append("There is no direct network and **no DNS**. Any tool that resolves host names\n")
           .append("itself will fail; tools that honour the standard proxy variables work normally,\n")
           .append("and those variables are already set for you. `npm`, `pip`, `curl`, `git` over\n")
           .append("HTTPS and the JVM all do, so installing project dependencies works. (`apt` can\n")
           .append("reach its mirrors but still cannot install: the root filesystem is read-only.)\n\n")
           .append("`pip` installs into `~/.local` (`PIP_USER` is set), which is in your home and so\n")
           .append("survives the session. If you make a virtualenv, `unset PIP_USER` first: pip\n")
           .append("refuses a `--user` install inside one.\n\n");
        out.append("Outbound HTTP and HTTPS go through a proxy that applies a policy you cannot see\n")
           .append("or change. The default for anything not matched by a rule is **")
           .append(config.network().defaultDecision() == Decision.ALLOW ? "allow" : "deny")
           .append("**, and there ")
           .append(config.network().rules().size() == 1 ? "is 1 rule" : "are " + config.network().rules().size() + " rules")
           .append(".\n\n")
           .append("If a request is refused you get a `403` whose body names the rule that refused it,\n")
           .append("for example:\n\n")
           .append("```\noillamp: connection to 10.0.0.1:5432 denied by rule \"")
           .append(config.network().rules().isEmpty() ? "…" : config.network().rules().first().label())
           .append("\" in oillamp.toml\n```\n\n")
           .append("Report that message verbatim rather than retrying — it tells the human exactly\n")
           .append("which rule to change.\n\n");

        out.append("## JVM toolchains\n\n")
           .append("One JDK is installed system-wide (`")
           .append(config.image().jdkPackage())
           .append("`). For any other version, or for\n")
           .append("Groovy, Gradle, Maven, Kotlin, Scala or sbt, use SDKMAN. It is already here and\n")
           .append("`sdk` is defined in your shell:\n\n")
           .append("""
            ```
            sdk list java                  # the identifiers, e.g. 21.0.8-tem
            sdk install java <identifier>  # into ~/.sdkman, so it outlasts this session
            sdk use java <identifier>      # this shell only
            sdk default java <identifier>  # every shell from now on
            sdk install groovy             # latest of any candidate; same for gradle, maven, sbt
            ```

            `sdk use` and `sdk default` set `JAVA_HOME` and `PATH` for you. For a project that
            pins its own, `sdk env init` writes a `.sdkmanrc` and `sdk env install` installs what
            it names. Downloads go through the proxy like everything else, and SDKMAN is
            configured not to prompt, so none of these will sit waiting for an answer you cannot
            give.

            """);

        if (!config.agentTools().install().isEmpty()) {
            out.append("## Harnesses already installed\n\n")
               .append("These were installed when the image was built, so they are here the moment\n")
               .append("you log in:\n\n");
            for (String tool : config.agentTools().install())
                out.append("- `").append(tool).append("`\n");
            out.append("\n`pi` and `opencode` use Eden AI as their model provider, through oillamp:\n")
               .append("they send their requests to `http://127.0.0.1:3129/v3`, and oillamp, outside\n")
               .append("this sandbox, adds the key and sends them on. You do not have the key, and you\n")
               .append("do not need it: `EDENAI_API_KEY` here is a placeholder. If a request is\n")
               .append("answered with 401, the human has not given oillamp a key; tell them.\n\n");
            if (config.model().euOnly())
                out.append("Only models served in the EU are offered. `EDENAI_BASE_URL` and\n")
                   .append("`EDENAI_EU_ONLY` are set for that; do not change them.")
                   .append(refusesEdenAiOutsideTheEu(config)
                           ? " The global endpoint,\n`api.edenai.run`, is refused by the network policy."
                           : "")
                   .append(" If a model you were asked\nto use is not offered, say so rather than looking ")
                   .append("for a way around this.\n\n");
            else
                out.append("The human chose the model service for this sandbox, and it is not Eden AI:\n")
                   .append("`pi --provider edenai` offers the models that service lists. `opencode`'s list\n")
                   .append("of models is Eden AI's, from when the image was built, so use `pi`.\n")
                   .append("`EDENAI_BASE_URL` is set for this; do not change it.\n\n");
            if (config.agentTools().install().contains("pi"))
                out.append("- `pi` has the Eden AI provider extension already installed — it is listed in\n")
               .append("  `~/.pi/agent/settings.json` with its clone under `~/.pi/agent/git/`.\n")
               .append("  `pi install` works through the proxy, and what it writes lands in your home,\n")
               .append("  so it lasts beyond this session.\n");
            if (config.agentTools().install().contains("opencode"))
                out.append("- `opencode` reads its Eden AI settings from the file `OPENCODE_CONFIG` names.\n")
               .append("  Its list of Eden AI models is the one the EU endpoint offered when this\n")
               .append("  sandbox's image was built.\n");
            out.append('\n');
        }

        config.llmForward().ifPresent(forward -> out
                .append("## Language model\n\n")
                .append("An OpenAI-compatible endpoint is reachable at `")
                .append(forward.inContainerUrl(config.llm().map(LampConfig.Llm::basePath).orElse("/v1")))
                .append("`.\n")
                .append("`OILLAMP_LLM_BASE_URL` already points at it, and `OILLAMP_LLM_MODELS` lists\n")
                .append("the model names to use. No API key is configured for you.\n\n"));

        if (!config.forwards().isEmpty()) {
            out.append("## Direct forwards\n\nThese endpoints bypass the proxy policy entirely:\n\n");
            for (Forward forward : config.forwards())
                out.append("- `http://127.0.0.1:").append(forward.port()).append("` → ")
                   .append(forward.name()).append('\n');
            out.append('\n');
        }

        if (config.schedule().enabled()) out.append(scheduleSection(config.schedule()));

        out.append("""
            ## What you cannot do

            You share this machine with a second user that runs the compositor, the screen
            recorder and the network bridges. You cannot signal those processes, read their
            control sockets, or alter the recording of your own screen. This is by design and
            not worth working around — the boundary is what allows you to be trusted with the
            rest.
            """);
        return out.toString();
    }

    /// How the agent works on a schedule, for a lamp that has one: the tools, the rules the host
    /// holds it to, and its notes, which are its only memory from one run to the next.
    private static String scheduleSection(LampConfig.Schedule schedule) {
        return """
            ## Working on a schedule

            This sandbox has a schedule. While the user's session runs, oillamp can wake you at set
            times with a task, each time in a new pi conversation. The user adds jobs with
            `oillamp schedule` on their machine. You can add your own with these tools, which pi
            has here:

            | Tool | What it does |
            |---|---|
            | `schedule_add` | add a job: `cron` for one that repeats (`0 9 * * 1-5`), or `at` for one that runs once (`in 2h`), and the `prompt` you will be woken with |
            | `schedule_list` | list every job, yours and the user's, with when each runs next |
            | `schedule_remove` | remove one of your own jobs |
            | `run_history` | list earlier runs, or show what one changed and what you said at its end |

            oillamp holds your jobs to these limits, and the tools say so when a request breaks
            one: at most %d jobs at once, each running at most every %d minutes, each removed
            after %d days at the latest, and at most %d runs of your jobs in any 24 hours. Every
            run is stopped after %d minutes. The user's jobs are theirs: you can see them, but not
            change or remove them.

            ### Your notes

            You remember nothing from one run to the next except what you write down. Keep your
            notes in `~/workspace/NOTES.md`:

            - When oillamp wakes you, the prompt contains your notes, what the last runs changed,
              and what you said at the end of the last one. Read them before you start.
            - If the file does not exist yet, create it.
            - Before you finish every run, rewrite it: what you did, what you found out, and what
              is left to do. Keep it under %d KB. Replace what is out of date rather than adding
              to it: it is not a log. The lamp's history already is one, and `run_history`
              reads it.

            oillamp saves your home just before and just after every run, so the user can see
            what each run changed, and undo it.

            """.formatted(schedule.maxAgentJobs(), schedule.minAgentIntervalMinutes(), schedule.maxAgentDays(),
                          schedule.maxAgentRunsPerDay(), schedule.maxRunMinutes(), schedule.notesMaxKb());
    }

    /// The pi extension that gives the agent its scheduling tools. They send requests to the
    /// session over a socket; see `src/main/resources/agent/oillamp-schedule.js`.
    static String scheduleTools() { return resource("oillamp-schedule.js"); }

    /// The pi extension through which the session moves within a conversation, to ask a question
    /// after an earlier entry. See `src/main/resources/agent/oillamp-conversations.js`.
    static String conversationTools() { return resource("oillamp-conversations.js"); }

    private static String resource(String name) {
        try (java.io.InputStream in = AgentGuide.class.getResourceAsStream("/agent/" + name)) {
            if (in == null) throw new IllegalStateException(name + " is missing from this build of oillamp");
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /// Whether the lamp's network policy refuses Eden AI's global endpoint. It does by default, but
    /// a lamp's own list of rules replaces the default one, so the guide only claims it when true.
    private static boolean refusesEdenAiOutsideTheEu(LampConfig config) {
        for (Rule rule : config.network().rules())
            if (rule.action() == Decision.DENY && rule.cidrs().isEmpty() && rule.ports().isEmpty())
                for (HostPattern host : rule.hosts())
                    if (host.matches("api.edenai.run")) return true;
        return false;
    }
}
