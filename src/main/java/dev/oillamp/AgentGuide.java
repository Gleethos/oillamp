package dev.oillamp;


/**
 * Renders the guide the agent reads as {@code ~/AGENTS.md} — spec §19.3.
 *
 * <p>An agent that does not know it is in a sandbox wastes its time and the user's: it will try
 * {@code sudo apt install}, wonder why DNS does not resolve, and report "network is broken" when
 * a policy denied one host. This file is written fresh every session and tells it, concretely,
 * what persists, what does not, how to reach the desktop, and why a request may be refused.
 *
 * <p>It deliberately does not hide the sandbox. An agent that understands the boundary can work
 * inside it and report accurately when it hits one; an agent that does not will guess.
 *
 * <p>Deliberately <b>package-private</b>: the Markdown it renders is the contract, not this class.
 * The guide is rewritten whenever we learn something new about how agents misread their sandbox
 * (§19.3).
 */
final class AgentGuide {

    private AgentGuide() {}

    public static String render(LampConfig config, LampLayout layout) {
        StringBuilder out = new StringBuilder();
        out.append("""
            # This machine

            You are working inside a sandbox created by oillamp. It is a container with its own
            Linux user, its own graphical desktop, and no direct network access. A human is
            watching this desktop live and everything on screen is being recorded.

            ## What persists

            Only your home directory, `/home/agent`, survives between sessions. Everything else —
            installed system packages, files in `/tmp`, anything under `/usr` — is discarded when
            the session ends, because the root filesystem comes from a read-only image.

            - `~/workspace` — put repositories here. Your shell starts in it.
            - `~/libs` — native libraries. Already on `LD_LIBRARY_PATH` and `java.library.path`,
              so `System.loadLibrary` finds them with no extra flags.
            - `~/screenshots` — where `lamp screenshot` writes by default.

            Language-level dependencies (Maven, npm, pip, venvs) install into your home and so
            persist. System packages do not: there is no `sudo` and no `apt` here. If you need
            one, say so — the human adds it to `image.extra_apt_packages` and rebuilds.

            ## The desktop

            """);
        out.append("You have a real graphical desktop at ")
           .append(config.display().size())
           .append(", running a Wayland compositor with Xwayland available, so Swing and other\n")
           .append("X11 applications work. Launch GUI applications from your shell as you would\n")
           .append("anywhere else; they appear on that desktop and the human sees them.\n\n");
        out.append("""
            The `lamp` command drives it:

            ```
            lamp screenshot                 # PNG of the whole screen; prints the path
            lamp screenshot --region X,Y,W,H
            lamp click X Y                  # absolute coordinates
            lamp move X Y                   # and: drag, scroll
            lamp type "some text"
            lamp key ctrl+shift+t
            lamp wait-stable                # block until the screen stops changing
            lamp info                       # size, renderer, output name
            ```

            After launching an application, `lamp wait-stable` then `lamp screenshot` is the
            reliable way to see what actually appeared.

            `lamp` is a shell script and a convenience, not a gate. It is a thin wrapper over
            ordinary Wayland tools that are installed here and that you may call directly
            whenever it does not do what you need:

            | Tool | For |
            |---|---|
            | `grim` | capture the screen or a region to PNG |
            | `slurp` | pick a region interactively |
            | `wtype` | type text, press key combinations |
            | `wlrctl` | move the pointer, click, scroll |

            Read the script with `cat $(command -v lamp)` if you want to see exactly what it
            runs. If you find something it should do and does not, say so — it is one file.

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

        if (!config.agentTools().install().isEmpty()) {
            out.append("## Harnesses already installed\n\n")
               .append("These were installed when the image was built, because there is no network\n")
               .append("here to install them from now:\n\n");
            for (String tool : config.agentTools().install())
                out.append("- `").append(tool).append("`\n");
            out.append("\n`pi` has the Eden AI provider extension already installed — it is listed in\n")
               .append("`~/.pi/agent/settings.json` with its clone under `~/.pi/agent/git/`. It needs\n")
               .append("`EDENAI_API_KEY`, which is set here only if the human had it set on the host.\n")
               .append("`pi install` works through the proxy, and what it writes lands in your home,\n")
               .append("so it lasts beyond this session.\n\n");
        }

        config.llmForward().ifPresent(forward -> out
                .append("## Language model\n\n")
                .append("An OpenAI-compatible endpoint is reachable at `")
                .append(forward.inContainerUrl(config.llm().map(LampConfig.Llm::basePath).orElse("/v1")))
                .append("`.\n")
                .append("`OPENAI_BASE_URL` and `OILLAMP_LLM_BASE_URL` already point at it.\n\n"));

        if (!config.forwards().isEmpty()) {
            out.append("## Direct forwards\n\nThese endpoints bypass the proxy policy entirely:\n\n");
            for (Forward forward : config.forwards())
                out.append("- `http://127.0.0.1:").append(forward.port()).append("` → ")
                   .append(forward.name()).append('\n');
            out.append('\n');
        }

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
}
