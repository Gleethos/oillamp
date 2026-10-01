package dev.oillamp;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import dev.lamp.ExitStatus;
import dev.lamp.LampEvent;

import sprouts.Tuple;

/// Parses the command line and calls the matching method on [Commands].
///
/// Written by hand rather than with a command-line library. There are only a few commands and
/// options, and this makes it simple to give a helpful message for a mistyped command and exit
/// code 2 for every usage error.
final class Invocation {

    private Invocation() {}

    static ExitStatus execute(Machine machine, Consumer<LampEvent> sink,
                              ConsoleRenderer console, String version, String... argv) {
        List<String> arguments = new ArrayList<>(List.of(argv));
        // Before anything is printed, so that even a usage error comes without colour.
        if (arguments.contains("--no-color")) console.withoutColour();
        // Likewise: an application reading standard output must never see a line that is not JSON.
        if (arguments.contains("--embedded")) console.asJsonLines();

        Context.Options options = Context.Options.defaults();
        // Only `view` reads this, so it stays a local rather than joining Options, where every
        // command would carry a switch that means nothing to it.
        boolean viewOnly = false;
        // Likewise --yes, which only `remove` reads. It is not a general "assume yes": it is the
        // answer to one question, asked by one command, that deletes the agent's home.
        boolean confirmed = false;
        // `recordings` only.
        Optional<String> open = Optional.empty();
        // `save` only.
        String message = "";
        // `schedule <dir> add` only.
        Optional<String> cron = Optional.empty();
        Optional<String> at = Optional.empty();
        Optional<String> expires = Optional.empty();
        // `ask` only.
        Optional<String> in = Optional.empty();
        Optional<String> after = Optional.empty();
        Optional<String> insteadOf = Optional.empty();
        // `at` only: where model requests go, and which variable holds the key.
        Optional<String> modelService = Optional.empty();
        Optional<String> modelKeyEnv = Optional.empty();
        // --open, --model-service and --model-key-env take a value, either as `--open=x` or as
        // the argument after them; pending is the option still waiting for its value.
        Optional<String> pending = Optional.empty();
        boolean prune = false;
        // `ask` only.
        boolean wait = true;
        List<String> positional = new ArrayList<>();
        // Every option other than the four that apply to all commands, as it is spelt in usage(),
        // so that a command given one it does not take can refuse it.
        List<String> commandOptions = new ArrayList<>();
        // After `--`, everything is taken as it is written, so a prompt may start with a dash.
        boolean literal = false;
        for (String argument : arguments) {
            if (literal) {
                positional.add(argument);
                continue;
            }
            if (argument.equals("--") && pending.isEmpty()) {
                literal = true;
                continue;
            }
            String option = VALUE_OPTIONS.stream().filter(taking -> argument.startsWith(taking + "="))
                    .findFirst().orElse(argument.equals("-y") ? "--yes"
                                      : argument.equals("-m") ? "--message" : argument);
            if (pending.isPresent()) {
                switch (pending.get()) {
                    case "--open"          -> open = Optional.of(argument);
                    case "--model-service" -> modelService = Optional.of(argument);
                    case "--message"       -> message = argument;
                    case "--cron"          -> cron = Optional.of(argument);
                    case "--at"            -> at = Optional.of(argument);
                    case "--expires"       -> expires = Optional.of(argument);
                    case "--in"            -> in = Optional.of(argument);
                    case "--after"         -> after = Optional.of(argument);
                    case "--instead-of"    -> insteadOf = Optional.of(argument);
                    default                -> modelKeyEnv = Optional.of(argument);
                }
                pending = Optional.empty();
                continue;
            }
            if (VALUE_OPTIONS.contains(option) && argument.startsWith(option + "=")) {
                commandOptions.add(option);
                String value = argument.substring(option.length() + 1);
                switch (option) {
                    case "--open"          -> open = Optional.of(value);
                    case "--model-service" -> modelService = Optional.of(value);
                    case "--message"       -> message = value;
                    case "--cron"          -> cron = Optional.of(value);
                    case "--at"            -> at = Optional.of(value);
                    case "--expires"       -> expires = Optional.of(value);
                    case "--in"            -> in = Optional.of(value);
                    case "--after"         -> after = Optional.of(value);
                    case "--instead-of"    -> insteadOf = Optional.of(value);
                    default                -> modelKeyEnv = Optional.of(value);
                }
                continue;
            }
            if (OPTIONS_OF.values().stream().anyMatch(taken -> taken.contains(option)))
                commandOptions.add(option);
            switch (argument) {
                case "--verbose", "-v" -> options = options.withVerbose(true);
                case "--debug"         -> options = options.withDebug(true).withVerbose(true);
                case "--no-color"      -> { }   // handled before this loop
                case "--dry-run"       -> options = options.withDryRun(true);
                case "--no-install"    -> options = options.withAutoInstall(false);
                case "--init"          -> options = options.withInit(true);
                case "--no-viewer"     -> options = options.withViewer(false);
                case "--no-windows"    -> options = options.withWindows(false);
                case "--embedded"      -> options = options.withEmbedded(true);
                case "--enable-scheduling" -> options = options.withScheduling(true);
                case "--view-only"     -> viewOnly = true;
                case "--yes", "-y"     -> confirmed = true;
                case "--prune"         -> prune = true;
                case "--no-wait"       -> wait = false;
                case "--open", "--model-service", "--model-key-env",
                     "--cron", "--at", "--expires", "--in", "--after", "--instead-of" -> pending = Optional.of(argument);
                case "--message", "-m" -> pending = Optional.of("--message");
                default -> {
                    if (argument.startsWith("-")) {
                        console.banner(version, "");
                        sink.accept(new LampEvent.Failure(Problems.usage(
                                "'" + argument + "' is not an option oillamp knows", usage())));
                        return ExitStatus.USAGE;
                    } else {
                        positional.add(argument);
                    }
                }
            }
        }

        if (pending.isPresent() && pending.get().equals("--message")) {
            console.banner(version, "");
            sink.accept(new LampEvent.Failure(Problems.usage(
                    "--message needs the text to save with, for example --message \"before the upgrade\"",
                    usageOf("save"))));
            return ExitStatus.USAGE;
        }
        if (pending.isPresent() && Set.of("--in", "--after", "--instead-of").contains(pending.get())) {
            console.banner(version, "");
            sink.accept(new LampEvent.Failure(Problems.usage(pending.get() + " needs "
                    + (pending.get().equals("--in") ? "a conversation, as `oillamp conversations` lists them"
                                                    : "an entry, as `oillamp conversations <dir> <conversation>` shows them"),
                    usageOf("ask"))));
            return ExitStatus.USAGE;
        }
        if (pending.isPresent() && Set.of("--cron", "--at", "--expires").contains(pending.get())) {
            console.banner(version, "");
            sink.accept(new LampEvent.Failure(Problems.usage(
                    pending.get() + " needs a value, for example " + switch (pending.get()) {
                        case "--cron" -> "--cron \"0 9 * * 1-5\"";
                        case "--at"   -> "--at \"2026-10-01 09:00\"";
                        default       -> "--expires \"in 14d\"";
                    }, usageOf("schedule"))));
            return ExitStatus.USAGE;
        }
        if (pending.isPresent() && !pending.get().equals("--open")) {
            console.banner(version, "");
            sink.accept(new LampEvent.Failure(Problems.usage(
                    pending.get() + " needs a value, for example " + pending.get()
                  + (pending.get().equals("--model-service") ? " https://api.eu.edenai.run" : " MY_MODEL_KEY"),
                    usageOf("at"))));
            return ExitStatus.USAGE;
        }
        if (pending.isPresent()) {
            console.banner(version, "");
            sink.accept(new LampEvent.Failure(Problems.usage(
                    "--open needs the session to play, for example --open 20260101-120000",
                    "oillamp recordings <dir> [--open <session>] [--prune]")));
            return ExitStatus.USAGE;
        }

        if (modelService.isPresent()) {
            Optional<String> wrong = ConfigLoader.serviceProblem(modelService.get());
            if (wrong.isPresent()) {
                console.banner(version, "");
                sink.accept(new LampEvent.Failure(Problems.usage(
                        "--model-service \"" + modelService.get() + "\": " + wrong.get(), usageOf("at"))));
                return ExitStatus.USAGE;
            }
        }
        if (modelKeyEnv.isPresent() && !ConfigLoader.isVariableName(modelKeyEnv.get())) {
            console.banner(version, "");
            sink.accept(new LampEvent.Failure(Problems.usage(
                    "--model-key-env \"" + modelKeyEnv.get() + "\": expected the name of an "
                  + "environment variable, such as EDENAI_API_KEY", usageOf("at"))));
            return ExitStatus.USAGE;
        }
        options = options.withModel(new Context.ModelOverride(
                modelService.map(URI::create), modelKeyEnv));

        // The renderer was created before the options were known, so tell it now.
        console.verbose(options.verbose());

        if (positional.isEmpty()) {
            console.banner(version, "");
            sink.accept(new LampEvent.Answer(usage()));
            return ExitStatus.USAGE;
        }

        String command = positional.get(0);
        List<String> rest = positional.subList(1, positional.size());

        if (OPTIONS_OF.containsKey(command)) {
            Optional<String> misplaced = commandOptions.stream()
                    .filter(option -> !OPTIONS_OF.get(command).contains(option)).findFirst();
            if (misplaced.isPresent())
                return misused(console, sink, version, command,
                        "`oillamp " + command + "` does not take " + misplaced.get());
            if (NEEDS_A_LAMP.contains(command) && rest.isEmpty())
                return missingDirectory(console, sink, version, command);
            if (ONE_LAMP_ONLY.contains(command) && rest.size() > 1)
                return oneLampOnly(console, sink, version, command, rest);
            int allowed = MOST_ARGUMENTS.getOrDefault(command, Integer.MAX_VALUE);
            if (rest.size() > allowed && allowed == 0)
                return misused(console, sink, version, command,
                        "`oillamp " + command + "` takes no arguments, but was given "
                      + String.join(" ", rest));
            if (rest.size() > allowed)
                return misused(console, sink, version, command,
                        "`oillamp " + command + "` was given more than it takes, starting with '"
                      + rest.get(allowed) + "'");
        }

        if (command.equals("version")) {
            sink.accept(new LampEvent.Answer(
                    "oillamp " + version + "\njava " + Runtime.version()));
            return ExitStatus.SUCCESS;
        }
        if (command.equals("help")) {
            sink.accept(new LampEvent.Answer(usage()));
            return ExitStatus.SUCCESS;
        }
        if (command.equals("about")) {
            sink.accept(new LampEvent.Answer(Handbook.about(version)));
            return ExitStatus.SUCCESS;
        }
        if (command.equals("guide")) {
            sink.accept(new LampEvent.Answer(Handbook.guide()));
            return ExitStatus.SUCCESS;
        }

        Context context = new Context(sink, options, version);
        Commands commands = new Commands(machine, context);

        return switch (command) {
            case "doctor" -> {
                console.banner(version, rest.isEmpty() ? "" : rest.get(0));
                yield commands.doctor(rest.isEmpty() ? Optional.empty() : Optional.of(Path.of(rest.get(0))));
            }
            case "at" -> {
                console.banner(version, rest.get(0));
                yield commands.at(Path.of(rest.get(0)));
            }
            case "config" -> {
                Path lamp = Path.of(rest.get(0));
                String action = rest.size() > 1 ? rest.get(1) : "check";
                yield switch (action) {
                    case "check"           -> commands.checkConfig(lamp);
                    case "show-effective"  -> commands.showEffectiveConfig(lamp);
                    case "path"            -> {
                        sink.accept(new LampEvent.Answer(lamp.resolve("oillamp.toml").toString()));
                        yield ExitStatus.SUCCESS;
                    }
                    default -> {
                        sink.accept(new LampEvent.Failure(Problems.usage(
                                "'" + action + "' is not a config action",
                                "oillamp config <dir> (check | show-effective | path)")));
                        yield ExitStatus.USAGE;
                    }
                };
            }
            // These five talk to a running session through its control socket. None of them
            // sets anything up.
            case "view"   -> commands.view(Path.of(rest.get(0)), viewOnly);
            case "shell"  -> commands.shell(Path.of(rest.get(0)));
            case "stop"   -> commands.stop(Path.of(rest.get(0)));
            case "status" -> commands.status(Path.of(rest.get(0)));
            case "follow" -> commands.follow(Path.of(rest.get(0)));
            case "list" -> commands.list();

            // Not one of the four above: it talks to no session, and refuses if one answers.
            // The one command that takes several lamps, since a pattern like `test*` is the
            // natural way to clean up after experiments.
            case "remove" -> {
                console.banner(version, rest.size() == 1 ? rest.get(0) : rest.size() + " lamps");
                Tuple<Path> lamps = Tuple.of(Path.class);
                for (String lamp : rest) lamps = lamps.add(Path.of(lamp));
                yield commands.remove(lamps, confirmed);
            }

            case "recordings" -> commands.recordings(Path.of(rest.get(0)), open, prune);

            // The lamp's history. `restore` needs the lamp stopped; the other two do not.
            case "save" -> {
                console.banner(version, rest.get(0));
                yield commands.save(Path.of(rest.get(0)), message);
            }
            case "history" -> commands.history(Path.of(rest.get(0)));

            // The schedule, and the agent it wakes. `schedule` works with or without a session;
            // `ask` needs one, since the session is what holds the agent.
            case "schedule" -> {
                String action = rest.size() > 1 ? rest.get(1) : "list";
                Optional<String> argument = rest.size() > 2 ? Optional.of(rest.get(2)) : Optional.empty();
                boolean needsArgument = Set.of("add", "remove", "enable", "disable").contains(action);
                if (needsArgument && argument.isEmpty())
                    yield misused(console, sink, version, "schedule", action.equals("add")
                            ? "`oillamp schedule <dir> add` needs the prompt the agent is woken with, in quotes"
                            : "`oillamp schedule <dir> " + action + "` needs the job, such as job-3");
                if (!needsArgument && argument.isPresent())
                    yield misused(console, sink, version, "schedule", "`oillamp schedule <dir> " + action
                            + "` takes nothing more, but was given '" + argument.get() + "'");
                if (!action.equals("add") && (cron.isPresent() || at.isPresent() || expires.isPresent()))
                    yield misused(console, sink, version, "schedule",
                            "--cron, --at and --expires only go with `oillamp schedule <dir> add`");
                yield commands.schedule(Path.of(rest.get(0)),
                        new Commands.ScheduleAction(action, argument, cron, at, expires));
            }
            case "conversations" -> commands.conversations(Path.of(rest.get(0)),
                    rest.size() > 1 ? Optional.of(rest.get(1)) : Optional.empty());
            case "ask" -> {
                if (rest.size() < 2)
                    yield misused(console, sink, version, "ask",
                            "`oillamp ask` needs something to ask the agent, in quotes");
                if ((after.isPresent() || insteadOf.isPresent()) && in.isEmpty())
                    yield misused(console, sink, version, "ask",
                            "--after and --instead-of name an entry of the conversation that --in names");
                if (after.isPresent() && insteadOf.isPresent())
                    yield misused(console, sink, version, "ask",
                            "a question goes either after an entry or instead of a question, not both");
                yield commands.ask(Path.of(rest.get(0)), rest.get(1), new Commands.AskPlace(in, after, insteadOf), wait);
            }
            case "cancel" -> commands.cancel(Path.of(rest.get(0)),
                    rest.size() > 1 ? Optional.of(rest.get(1)) : Optional.empty());
            case "restore" -> {
                if (rest.size() < 2)
                    yield misused(console, sink, version, "restore",
                            "`oillamp restore` needs the snapshot to go back to, as `oillamp history` names it");
                console.banner(version, rest.get(0));
                yield commands.restore(Path.of(rest.get(0)), rest.get(1));
            }

            case "completion" -> {
                String shell = rest.isEmpty() ? "bash" : rest.get(0);
                if (!shell.equals("bash")) {
                    console.banner(version, "");
                    sink.accept(new LampEvent.Failure(Problems.usage(
                            "oillamp only ships a completion script for bash, not '" + shell + "'",
                            "oillamp completion bash")));
                    yield ExitStatus.USAGE;
                }
                // Straight to stdout, with no banner: the output is meant to be evaluated by a
                // shell, and anything else printed would be evaluated along with it.
                console.plain(GeneratedFileTextUtil.bashCompletion());
                yield ExitStatus.SUCCESS;
            }

            // Planned once and then dropped: the image is rebuilt automatically whenever its
            // inputs change, so there is nothing to manage by hand. Explain that instead of
            // answering "unknown command".
            case "image" -> {
                sink.accept(new LampEvent.Failure(Problems.usage(
                        "there is no 'image' command: oillamp rebuilds the sandbox image by itself "
                      + "whenever anything that goes into it changes",
                        usage())));
                yield ExitStatus.USAGE;
            }
            default -> {
                console.banner(version, "");
                sink.accept(new LampEvent.Failure(Problems.usage(
                        "'" + command + "' is not an oillamp command", usage())));
                yield ExitStatus.USAGE;
            }
        };
    }

    /// The options each command takes, besides `--verbose`, `--debug` and `--no-color`, which
    /// every command takes. `doctor` and `config` change nothing anyway, so they accept the two
    /// options that promise that.
    private static final Map<String, Set<String>> OPTIONS_OF = Map.ofEntries(
            Map.entry("at",         Set.of("--init", "--dry-run", "--no-install", "--no-viewer", "--no-windows",
                                                        "--embedded", "--model-service", "--model-key-env",
                                                        "--enable-scheduling")),
            Map.entry("view",       Set.of("--view-only")),
            Map.entry("remove",     Set.of("--yes", "--dry-run", "--embedded")),
            Map.entry("recordings", Set.of("--open", "--prune", "--dry-run")),
            Map.entry("doctor",     Set.of("--dry-run", "--no-install")),
            Map.entry("config",     Set.of("--dry-run", "--no-install")),
            Map.entry("save",       Set.of("--message", "--embedded")),
            Map.entry("history",    Set.of("--embedded")),
            Map.entry("schedule",   Set.of("--cron", "--at", "--expires", "--embedded")),
            Map.entry("ask",        Set.of("--in", "--after", "--instead-of", "--no-wait", "--embedded")),
            Map.entry("cancel",     Set.of("--embedded")),
            Map.entry("conversations", Set.of("--embedded")),
            Map.entry("restore",    Set.of("--embedded")),
            Map.entry("shell",      Set.of()),
            Map.entry("stop",       Set.of("--embedded")),
            Map.entry("status",     Set.of("--embedded")),
            Map.entry("follow",     Set.of("--embedded")),
            Map.entry("list",       Set.of()),
            Map.entry("completion", Set.of()),
            Map.entry("version",    Set.of()),
            Map.entry("help",       Set.of()),
            Map.entry("about",      Set.of()),
            Map.entry("guide",      Set.of()));

    /// The options that take a value.
    private static final Set<String> VALUE_OPTIONS = Set.of(
            "--open", "--model-service", "--model-key-env", "--message", "--cron", "--at", "--expires",
            "--in", "--after", "--instead-of");

    /// The commands that take a lamp directory and cannot do without it.
    private static final Set<String> NEEDS_A_LAMP = Set.of(
            "at", "view", "shell", "stop", "status", "follow", "recordings", "config", "remove",
            "save", "history", "restore", "schedule", "ask", "conversations", "cancel");

    /// The commands that work on one lamp. `remove` takes several, since a pattern such as
    /// `test*` is the natural way to clean up after experiments.
    private static final Set<String> ONE_LAMP_ONLY = Set.of(
            "at", "view", "shell", "stop", "status", "follow", "recordings", "doctor", "save", "history");

    /// How many arguments may follow the other commands.
    private static final Map<String, Integer> MOST_ARGUMENTS = Map.ofEntries(
            Map.entry("config", 2), Map.entry("completion", 1), Map.entry("restore", 2),
            Map.entry("schedule", 3), Map.entry("ask", 2), Map.entry("conversations", 2), Map.entry("cancel", 2),
            Map.entry("list", 0), Map.entry("version", 0), Map.entry("help", 0),
            Map.entry("about", 0), Map.entry("guide", 0));

    private static ExitStatus misused(ConsoleRenderer console, Consumer<LampEvent> sink,
                                      String version, String command, String what) {
        console.banner(version, "");
        sink.accept(new LampEvent.Failure(Problems.usage(what, usageOf(command))));
        return ExitStatus.USAGE;
    }

    /// The line of [#usage] that describes `command`, such as `oillamp stop <dir>`.
    static String usageOf(String command) {
        return usage().lines().map(String::strip)
                .filter(line -> line.equals(command) || line.startsWith(command + " "))
                .findFirst().map(line -> "oillamp " + line).orElse(usage());
    }

    private static ExitStatus missingDirectory(ConsoleRenderer console, Consumer<LampEvent> sink,
                                               String version, String command) {
        console.banner(version, "");
        sink.accept(new LampEvent.Failure(Problems.usage(
                "`oillamp " + command + "` needs the path of a lamp directory",
                "oillamp " + command + " <dir>")));
        return ExitStatus.USAGE;
    }

    /// A command that works on one lamp was given several directories. Usually a shell pattern
    /// such as `test*` expanded to more than one; acting on the first and ignoring the rest would
    /// look as if it had done them all.
    private static ExitStatus oneLampOnly(ConsoleRenderer console, Consumer<LampEvent> sink,
                                          String version, String command, List<String> given) {
        console.banner(version, "");
        sink.accept(new LampEvent.Failure(Problems.usage(
                "`oillamp " + command + "` works on one lamp, but was given " + given.size()
              + " directories (" + String.join(", ", given) + "); run it once for each",
                "oillamp " + command + " <dir>")));
        return ExitStatus.USAGE;
    }

    static String usage() {
        return """
            oillamp [--verbose] [--debug] [--no-color] <command>

            After `--`, every argument is taken as written, so a prompt may start with a dash.

            New here? `oillamp guide` walks through a first session; `oillamp about` says what
            oillamp is for and what it is built from.

              at <dir> [--init] [--dry-run] [--no-install] [--no-viewer] [--no-windows]
                       [--embedded] [--model-service <url>] [--model-key-env <name>]
                       [--enable-scheduling]
                    Set up (if needed) and run a session. Opens a shell window and a viewer
                    onto the sandbox's desktop, and stays in the foreground until the session
                    ends: Ctrl-C here, closing this terminal, or `oillamp stop <dir>`.
                      --init          accept a directory that is not empty as a new lamp
                      --dry-run       show every step and change nothing; with --verbose, also
                                      the full podman command
                      --no-install    report missing host packages instead of installing them
                      --no-viewer     open the shell window, but no viewer
                      --no-windows    open no windows at all, so no display is needed (under
                                      tmux, or over ssh); get in with `shell` and `view`
                      --embedded      for applications that start oillamp themselves: no
                                      windows, events as JSON lines, ends when stdin closes
                      --model-service <url>, --model-key-env <name>
                                      use this model service, and the key in this variable,
                                      instead of the lamp's [model] settings, for this session
                      --enable-scheduling
                                      let jobs on the schedule wake the agent in this session,
                                      as `enabled = true` under [schedule] would
              view <dir> [--view-only]
                    Open another viewer onto a running session's desktop. Needs a display.
                    --view-only lets you watch without typing.
              shell <dir>
                    Open an extra shell in this terminal. Needs no display. Closing it does
                    not end the session.
              stop <dir>
                    Ask a running session to shut down, or clean up after one that crashed.
              status <dir>
                    What a running session is doing.
              follow <dir> [--embedded]
                    Report what a running session reports, as it happens, until it ends:
                    first what it is doing now, then everything after. Ctrl-C stops
                    following, not the session.
              list
                    Every oillamp sandbox running on this host.
              remove <dir>... --yes
                    Delete one or more lamps: the agent's home, the state, the config. Without
                    --yes it only says what would go. Every lamp is checked first; if any cannot
                    be removed, none is. Needed because parts of a lamp belong to the sandbox's
                    own users and `rm -rf` cannot remove them.
              save <dir> [--message <text>]
                    Take a snapshot of the lamp: the agent's home and oillamp.toml. Works while
                    a session runs. oillamp also saves as each session starts and ends, when
                    anything changed.
              history <dir>
                    List the lamp's snapshots, newest first.
              restore <dir> <snapshot>
                    Bring the lamp back to a snapshot, named by the start of its id. The
                    session must be stopped. The lamp is saved first, so this can be undone.
              schedule <dir> [add | remove <job> | enable <job> | disable <job> | pause | resume]
                    List the jobs that wake the agent while a session runs, or change them.
                    Jobs run only in a session started with --enable-scheduling, or with
                    `enabled = true` under [schedule] in oillamp.toml.
                      add (--cron "<expression>" | --at <time>) [--expires <time>] "<prompt>"
                                      --cron "0 9 * * 1-5" repeats, as cron would; --at runs once,
                                      at "2026-10-01 09:00" on this machine's clock or "in 2h"
              ask <dir> [--in <conversation> [--after <entry> | --instead-of <entry>]] [--no-wait] "<prompt>"
                    Wake the agent in a running session with a prompt, and print its answer.
                    If it is busy, the prompt waits its turn. The lamp is saved before and after.
                      --in            continue that conversation where it stands, instead of
                                      starting a new one; `oillamp conversations` lists them
                      --after         continue after that entry, such as an earlier answer
                      --instead-of    ask this instead of that question, leaving the old one
                                      and its answers as a branch of their own
                      --no-wait       say which run answers it, and return at once
              cancel <dir> [<run>]
                    Stop the run the agent is working on, or a run that is still waiting.
              conversations <dir> [<conversation>]
                    List the agent's conversations, or show one, with the ids of its entries.
              recordings <dir> [--open <session>] [--prune]
                    List this lamp's screen recordings. --open plays one, --prune
                    applies the configured retention now instead of at the next start.
              completion bash
                    Print a bash completion script. Use it with:
                        eval "$(oillamp completion bash)"
              doctor [<dir>]
                    Check the host, and the lamp if one is given. Changes nothing.
              config <dir> (check | show-effective | path)
                    Validate the configuration, print it, or print its path.
              guide
                    A first session, step by step.
              about
                    Why oillamp exists, and what it is built from.
              version
              help
            """;
    }
}
