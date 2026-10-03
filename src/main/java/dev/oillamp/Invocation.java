package dev.oillamp;

import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;

import dev.lamp.ExitStatus;
import dev.lamp.LampEvent;
import dev.lamp.Problem;

import sprouts.Association;
import sprouts.Tuple;
import sprouts.ValueSet;

/// Parses the command line into a [Command], and calls the matching method on [Commands].
///
/// Written by hand rather than with a command-line library. There are only a few commands and
/// options, and this makes it simple to give a helpful message for a mistyped command and exit
/// code 2 for every usage error.
final class Invocation {

    private Invocation() {}

    static ExitStatus execute(Machine machine, Consumer<LampEvent> sink,
                              ConsoleRenderer console, String version, String... argv) {
        Tuple<String> arguments = Tuple.of(String.class, argv);
        // Before anything is printed, so that even a usage error comes without colour.
        if (arguments.contains("--no-color")) console.withoutColour();
        // Likewise: an application reading standard output must never see a line that is not JSON.
        if (arguments.contains("--embedded")) console.asJsonLines();

        Result<Command> parsed = parse(arguments);
        if (parsed instanceof Result.Err<Command> refused) {
            console.banner(version, "");
            for (Problem problem : refused.problems()) sink.accept(new LampEvent.Failure(problem));
            return ExitStatus.USAGE;
        }
        Command command = ((Result.Ok<Command>) parsed).value();

        // The renderer was created before the options were known, so tell it now.
        console.verbose(command.options().verbose());
        Commands commands = new Commands(machine, new Context(sink, command.options(), version));

        return switch (command) {
            case Command.NoCommand _ -> {
                console.banner(version, "");
                sink.accept(new LampEvent.Answer(usage()));
                yield ExitStatus.USAGE;
            }
            case Command.Version _ -> {
                sink.accept(new LampEvent.Answer("oillamp " + version + "\njava " + Runtime.version()));
                yield ExitStatus.SUCCESS;
            }
            case Command.Help _ -> {
                sink.accept(new LampEvent.Answer(usage()));
                yield ExitStatus.SUCCESS;
            }
            case Command.About _ -> {
                sink.accept(new LampEvent.Answer(IntroductionTextUtil.about(version)));
                yield ExitStatus.SUCCESS;
            }
            case Command.Guide _ -> {
                sink.accept(new LampEvent.Answer(IntroductionTextUtil.guide()));
                yield ExitStatus.SUCCESS;
            }
            case Command.Completion _ -> {
                // Straight to stdout, with no banner: the output is meant to be evaluated by a
                // shell, and anything else printed would be evaluated along with it.
                console.plain(GeneratedFileTextUtil.bashCompletion());
                yield ExitStatus.SUCCESS;
            }
            // Explain that there is no such command, instead of answering "unknown command".
            case Command.Image _ -> {
                sink.accept(new LampEvent.Failure(ProblemCatalogUtil.usage(
                        "there is no 'image' command: oillamp rebuilds the sandbox image by itself "
                      + "whenever anything that goes into it changes",
                        usage())));
                yield ExitStatus.USAGE;
            }
            case Command.Doctor doctor -> {
                console.banner(version, doctor.lamp().map(Path::toString).orElse(""));
                yield commands.doctor(doctor.lamp());
            }
            case Command.Config config -> switch (config.action()) {
                case CHECK          -> commands.checkConfig(config.lamp());
                case SHOW_EFFECTIVE -> commands.showEffectiveConfig(config.lamp());
                case PATH -> {
                    sink.accept(new LampEvent.Answer(config.lamp().resolve("oillamp.toml").toString()));
                    yield ExitStatus.SUCCESS;
                }
            };
            case Command.At at -> {
                console.banner(version, at.lamp().toString());
                yield commands.at(at.lamp());
            }
            // These five talk to a running session through its control socket. None of them
            // sets anything up.
            case Command.View view     -> commands.view(view.lamp(), view.viewOnly());
            case Command.Shell shell   -> commands.shell(shell.lamp());
            case Command.Stop stop     -> commands.stop(stop.lamp());
            case Command.Status status -> commands.status(status.lamp());
            case Command.Follow follow -> commands.follow(follow.lamp());
            case Command.List _        -> commands.list();
            // Talks to no session, and refuses if one answers.
            case Command.Remove remove -> {
                console.banner(version, remove.lamps().size() == 1 ? remove.lamps().first().toString()
                                                                    : remove.lamps().size() + " lamps");
                yield commands.remove(remove.lamps(), remove.confirmed());
            }
            case Command.Recordings recordings ->
                    commands.recordings(recordings.lamp(), recordings.open(), recordings.prune());
            // The lamp's history. `restore` needs the lamp stopped; the other two do not.
            case Command.Save save -> {
                console.banner(version, save.lamp().toString());
                yield commands.save(save.lamp(), save.message());
            }
            case Command.History history -> commands.history(history.lamp());
            case Command.Restore restore -> {
                console.banner(version, restore.lamp().toString());
                yield commands.restore(restore.lamp(), restore.snapshot());
            }
            // The schedule, and the agent it wakes. `schedule` works with or without a session;
            // `ask` needs one, since the session is what holds the agent.
            case Command.Schedule schedule -> commands.schedule(schedule.lamp(), schedule.action());
            case Command.Conversations conversations ->
                    commands.conversations(conversations.lamp(), conversations.conversation());
            case Command.Ask ask       -> commands.ask(ask.lamp(), ask.prompt(), ask.place(), ask.waitForAnswer());
            case Command.Cancel cancel -> commands.cancel(cancel.lamp(), cancel.run());
        };
    }

    /// Reads the command line into a [Command], or says what is wrong with it.
    static Result<Command> parse(Tuple<String> arguments) {
        Context.Options options = Context.Options.defaults();
        // The options only one command reads.
        boolean viewOnly = false;
        boolean confirmed = false;
        boolean prune = false;
        boolean wait = true;
        // The value of each option in VALUE_OPTIONS that was given, given either as `--open=x` or
        // as the argument after it; pending is the option still waiting for its value.
        Association<String, String> values = Association.between(String.class, String.class);
        Optional<String> pending = Optional.empty();
        Tuple<String> positional = Tuple.of(String.class);
        // Every option other than the four that apply to all commands, as it is spelt in usage(),
        // so that a command given one it does not take can refuse it.
        Tuple<String> commandOptions = Tuple.of(String.class);
        // After `--`, everything is taken as it is written, so a prompt may start with a dash.
        boolean literal = false;
        for (String argument : arguments) {
            if (literal) {
                positional = positional.add(argument);
                continue;
            }
            if (pending.isPresent()) {
                values = values.put(pending.get(), argument);
                pending = Optional.empty();
                continue;
            }
            if (argument.equals("--")) {
                literal = true;
                continue;
            }
            String option = VALUE_OPTIONS.stream().filter(taking -> argument.startsWith(taking + "="))
                    .findFirst().orElse(argument.equals("-y") ? "--yes"
                                      : argument.equals("-m") ? "--message" : argument);
            if (OPTIONS_OF.values().any(taken -> taken.contains(option)))
                commandOptions = commandOptions.add(option);
            if (VALUE_OPTIONS.contains(option) && argument.startsWith(option + "=")) {
                values = values.put(option, argument.substring(option.length() + 1));
                continue;
            }
            switch (option) {
                case "--verbose", "-v" -> options = options.withVerbose(true);
                case "--debug"         -> options = options.withDebug(true).withVerbose(true);
                case "--no-color"      -> { }   // handled by execute, before anything was printed
                case "--dry-run"       -> options = options.withDryRun(true);
                case "--no-install"    -> options = options.withAutoInstall(false);
                case "--init"          -> options = options.withInit(true);
                case "--no-viewer"     -> options = options.withViewer(false);
                case "--no-windows"    -> options = options.withWindows(false);
                case "--embedded"      -> options = options.withEmbedded(true);
                case "--enable-scheduling" -> options = options.withScheduling(true);
                case "--view-only"     -> viewOnly = true;
                case "--yes"           -> confirmed = true;
                case "--prune"         -> prune = true;
                case "--no-wait"       -> wait = false;
                default -> {
                    if (VALUE_OPTIONS.contains(option))
                        pending = Optional.of(option);
                    else if (argument.startsWith("-"))
                        return Result.err(ProblemCatalogUtil.usage(
                                "'" + argument + "' is not an option oillamp knows", usage()));
                    else
                        positional = positional.add(argument);
                }
            }
        }

        if (pending.isPresent()) return Result.err(switch (pending.get()) {
            case "--message" -> ProblemCatalogUtil.usage(
                    "--message needs the text to save with, for example --message \"before the upgrade\"",
                    usageOf("save"));
            case "--in" -> ProblemCatalogUtil.usage(
                    "--in needs a conversation, as `oillamp conversations` lists them", usageOf("ask"));
            case "--after", "--instead-of" -> ProblemCatalogUtil.usage(pending.get()
                    + " needs an entry, as `oillamp conversations <dir> <conversation>` shows them", usageOf("ask"));
            case "--cron" -> ProblemCatalogUtil.usage(
                    "--cron needs a value, for example --cron \"0 9 * * 1-5\"", usageOf("schedule"));
            case "--at" -> ProblemCatalogUtil.usage(
                    "--at needs a value, for example --at \"2026-10-01 09:00\"", usageOf("schedule"));
            case "--expires" -> ProblemCatalogUtil.usage(
                    "--expires needs a value, for example --expires \"in 14d\"", usageOf("schedule"));
            case "--model-service" -> ProblemCatalogUtil.usage(
                    "--model-service needs a value, for example --model-service https://api.eu.edenai.run",
                    usageOf("at"));
            case "--model-key-env" -> ProblemCatalogUtil.usage(
                    "--model-key-env needs a value, for example --model-key-env MY_MODEL_KEY", usageOf("at"));
            default -> ProblemCatalogUtil.usage(
                    "--open needs the session to play, for example --open 20260101-120000",
                    "oillamp recordings <dir> [--open <session>] [--prune]");
        });

        Optional<String> modelService = values.get("--model-service");
        Optional<String> modelKeyEnv = values.get("--model-key-env");
        if (modelService.isPresent()) {
            Optional<String> wrong = ConfigLoadingUtil.serviceProblem(modelService.get());
            if (wrong.isPresent())
                return Result.err(ProblemCatalogUtil.usage(
                        "--model-service \"" + modelService.get() + "\": " + wrong.get(), usageOf("at")));
        }
        if (modelKeyEnv.isPresent() && !ConfigLoadingUtil.isVariableName(modelKeyEnv.get()))
            return Result.err(ProblemCatalogUtil.usage(
                    "--model-key-env \"" + modelKeyEnv.get() + "\": expected the name of an "
                  + "environment variable, such as EDENAI_API_KEY", usageOf("at")));
        options = options.withModel(new Context.ModelOverride(modelService.map(URI::create), modelKeyEnv));

        if (positional.isEmpty()) return Result.ok(new Command.NoCommand(options));

        String command = positional.first();
        Tuple<String> rest = positional.removeFirst();

        Optional<ValueSet<String>> takes = OPTIONS_OF.get(command);
        if (takes.isPresent()) {
            Optional<String> misplaced = commandOptions.stream()
                    .filter(option -> !takes.get().contains(option)).findFirst();
            if (misplaced.isPresent())
                return Result.err(ProblemCatalogUtil.usage("`oillamp " + command + "` does not take " + misplaced.get(), usageOf(command)));
            if (NEEDS_A_LAMP.contains(command) && rest.isEmpty())
                return Result.err(ProblemCatalogUtil.usage(
                        "`oillamp " + command + "` needs the path of a lamp directory",
                        "oillamp " + command + " <dir>"));
            // Usually a shell pattern such as `test*` that expanded to more than one; acting on
            // the first and ignoring the rest would look as if it had done them all.
            if (ONE_LAMP_ONLY.contains(command) && rest.size() > 1)
                return Result.err(ProblemCatalogUtil.usage(
                        "`oillamp " + command + "` works on one lamp, but was given " + rest.size()
                      + " directories (" + rest.join(", ") + "); run it once for each",
                        "oillamp " + command + " <dir>"));
            int allowed = MOST_ARGUMENTS.get(command).orElse(Integer.MAX_VALUE);
            if (rest.size() > allowed && allowed == 0)
                return Result.err(ProblemCatalogUtil.usage("`oillamp " + command + "` takes no arguments, but was given "
                      + rest.join(" "), usageOf(command)));
            if (rest.size() > allowed)
                return Result.err(ProblemCatalogUtil.usage("`oillamp " + command
                      + "` was given more than it takes, starting with '" + rest.get(allowed) + "'", usageOf(command)));
        }

        return switch (command) {
            case "version" -> Result.ok(new Command.Version(options));
            case "help"    -> Result.ok(new Command.Help(options));
            case "about"   -> Result.ok(new Command.About(options));
            case "guide"   -> Result.ok(new Command.Guide(options));
            case "image"   -> Result.ok(new Command.Image(options));
            case "completion" -> {
                String shell = rest.isEmpty() ? "bash" : rest.first();
                if (!shell.equals("bash"))
                    yield Result.err(ProblemCatalogUtil.usage(
                            "oillamp only ships a completion script for bash, not '" + shell + "'",
                            "oillamp completion bash"));
                yield Result.ok(new Command.Completion(options));
            }
            case "doctor" -> Result.ok(new Command.Doctor(
                    rest.isEmpty() ? Optional.empty() : Optional.of(Path.of(rest.first())), options));
            case "config" -> {
                String action = rest.size() > 1 ? rest.get(1) : "check";
                yield switch (action) {
                    case "check"          -> Result.ok(new Command.Config(Path.of(rest.first()), Command.Config.Action.CHECK, options));
                    case "show-effective" -> Result.ok(new Command.Config(Path.of(rest.first()), Command.Config.Action.SHOW_EFFECTIVE, options));
                    case "path"           -> Result.ok(new Command.Config(Path.of(rest.first()), Command.Config.Action.PATH, options));
                    default -> Result.err(ProblemCatalogUtil.usage("'" + action + "' is not a config action",
                            "oillamp config <dir> (check | show-effective | path)"));
                };
            }
            case "at"     -> Result.ok(new Command.At(Path.of(rest.first()), options));
            case "view"   -> Result.ok(new Command.View(Path.of(rest.first()), viewOnly, options));
            case "shell"  -> Result.ok(new Command.Shell(Path.of(rest.first()), options));
            case "stop"   -> Result.ok(new Command.Stop(Path.of(rest.first()), options));
            case "status" -> Result.ok(new Command.Status(Path.of(rest.first()), options));
            case "follow" -> Result.ok(new Command.Follow(Path.of(rest.first()), options));
            case "list"   -> Result.ok(new Command.List(options));
            // The one command that takes several lamps, since a pattern like `test*` is the
            // natural way to clean up after experiments.
            case "remove" -> Result.ok(new Command.Remove(rest.mapTo(Path.class, Path::of), confirmed, options));
            case "recordings" -> Result.ok(new Command.Recordings(Path.of(rest.first()), values.get("--open"),
                    prune, options));
            case "save" -> Result.ok(new Command.Save(Path.of(rest.first()), values.get("--message").orElse(""),
                    options));
            case "history" -> Result.ok(new Command.History(Path.of(rest.first()), options));
            case "restore" -> {
                if (rest.size() < 2)
                    yield Result.err(ProblemCatalogUtil.usage("`oillamp restore` needs the snapshot to go back to, as `oillamp history` names it", usageOf("restore")));
                yield Result.ok(new Command.Restore(Path.of(rest.first()), rest.get(1), options));
            }
            case "schedule" -> {
                String action = rest.size() > 1 ? rest.get(1) : "list";
                Optional<String> argument = rest.size() > 2 ? Optional.of(rest.get(2)) : Optional.empty();
                Optional<String> cron = values.get("--cron");
                Optional<String> at = values.get("--at");
                Optional<String> expires = values.get("--expires");
                boolean needsArgument = ValueSet.of("add", "remove", "enable", "disable").contains(action);
                if (needsArgument && argument.isEmpty())
                    yield Result.err(ProblemCatalogUtil.usage(action.equals("add")
                            ? "`oillamp schedule <dir> add` needs the prompt the agent is woken with, in quotes"
                            : "`oillamp schedule <dir> " + action + "` needs the job, such as job-3", usageOf("schedule")));
                if (!needsArgument && argument.isPresent())
                    yield Result.err(ProblemCatalogUtil.usage("`oillamp schedule <dir> " + action
                            + "` takes nothing more, but was given '" + argument.get() + "'", usageOf("schedule")));
                if (!action.equals("add") && (cron.isPresent() || at.isPresent() || expires.isPresent()))
                    yield Result.err(ProblemCatalogUtil.usage("--cron, --at and --expires only go with `oillamp schedule <dir> add`", usageOf("schedule")));
                String job = argument.orElse("");
                Optional<Command.Schedule.Action> chosen = switch (action) {
                    case "list"    -> Optional.of(new Command.Schedule.Action.ListJobs());
                    case "add"     -> Optional.of(new Command.Schedule.Action.Add(job, cron, at, expires));
                    case "remove"  -> Optional.of(new Command.Schedule.Action.Remove(job));
                    case "enable"  -> Optional.of(new Command.Schedule.Action.Enable(job));
                    case "disable" -> Optional.of(new Command.Schedule.Action.Disable(job));
                    case "pause"   -> Optional.of(new Command.Schedule.Action.Pause());
                    case "resume"  -> Optional.of(new Command.Schedule.Action.Resume());
                    default        -> Optional.empty();
                };
                if (chosen.isEmpty())
                    yield Result.err(ProblemCatalogUtil.usage("'" + action + "' is not something `oillamp schedule` does", usageOf("schedule")));
                yield Result.ok(new Command.Schedule(Path.of(rest.first()), chosen.get(), options));
            }
            case "conversations" -> Result.ok(new Command.Conversations(Path.of(rest.first()),
                    rest.size() > 1 ? Optional.of(rest.get(1)) : Optional.empty(), options));
            case "ask" -> {
                Optional<String> in = values.get("--in");
                Optional<String> after = values.get("--after");
                Optional<String> insteadOf = values.get("--instead-of");
                if (rest.size() < 2)
                    yield Result.err(ProblemCatalogUtil.usage("`oillamp ask` needs something to ask the agent, in quotes", usageOf("ask")));
                if ((after.isPresent() || insteadOf.isPresent()) && in.isEmpty())
                    yield Result.err(ProblemCatalogUtil.usage("--after and --instead-of name an entry of the conversation that --in names", usageOf("ask")));
                if (after.isPresent() && insteadOf.isPresent())
                    yield Result.err(ProblemCatalogUtil.usage("a question goes either after an entry or instead of a question, not both", usageOf("ask")));
                yield Result.ok(new Command.Ask(Path.of(rest.first()), rest.get(1),
                        new Commands.AskPlace(in, after, insteadOf), wait, options));
            }
            case "cancel" -> Result.ok(new Command.Cancel(Path.of(rest.first()),
                    rest.size() > 1 ? Optional.of(rest.get(1)) : Optional.empty(), options));
            default -> Result.err(ProblemCatalogUtil.usage("'" + command + "' is not an oillamp command", usage()));
        };
    }

    /// The options each command takes, besides `--verbose`, `--debug` and `--no-color`, which
    /// every command takes. `doctor` and `config` change nothing anyway, so they accept the two
    /// options that promise that.
    private static final Association<String, ValueSet<String>> OPTIONS_OF =
            Association.between(String.class, ValueSet.classTyped(String.class))
                .put("at",         ValueSet.of("--init", "--dry-run", "--no-install", "--no-viewer", "--no-windows",
                                               "--embedded", "--model-service", "--model-key-env",
                                               "--enable-scheduling"))
                .put("view",       ValueSet.of("--view-only"))
                .put("remove",     ValueSet.of("--yes", "--dry-run", "--embedded"))
                .put("recordings", ValueSet.of("--open", "--prune", "--dry-run"))
                .put("doctor",     ValueSet.of("--dry-run", "--no-install"))
                .put("config",     ValueSet.of("--dry-run", "--no-install"))
                .put("save",       ValueSet.of("--message", "--embedded"))
                .put("history",    ValueSet.of("--embedded"))
                .put("schedule",   ValueSet.of("--cron", "--at", "--expires", "--embedded"))
                .put("ask",        ValueSet.of("--in", "--after", "--instead-of", "--no-wait", "--embedded"))
                .put("cancel",     ValueSet.of("--embedded"))
                .put("conversations", ValueSet.of("--embedded"))
                .put("restore",    ValueSet.of("--embedded"))
                .put("shell",      ValueSet.of(String.class))
                .put("stop",       ValueSet.of("--embedded"))
                .put("status",     ValueSet.of("--embedded"))
                .put("follow",     ValueSet.of("--embedded"))
                .put("list",       ValueSet.of(String.class))
                .put("completion", ValueSet.of(String.class))
                .put("version",    ValueSet.of(String.class))
                .put("help",       ValueSet.of(String.class))
                .put("about",      ValueSet.of(String.class))
                .put("guide",      ValueSet.of(String.class));

    /// The options that take a value.
    private static final ValueSet<String> VALUE_OPTIONS = ValueSet.of(
            "--open", "--model-service", "--model-key-env", "--message", "--cron", "--at", "--expires",
            "--in", "--after", "--instead-of");

    /// The commands that take a lamp directory and cannot do without it.
    private static final ValueSet<String> NEEDS_A_LAMP = ValueSet.of(
            "at", "view", "shell", "stop", "status", "follow", "recordings", "config", "remove",
            "save", "history", "restore", "schedule", "ask", "conversations", "cancel");

    /// The commands that work on one lamp. `remove` takes several.
    private static final ValueSet<String> ONE_LAMP_ONLY = ValueSet.of(
            "at", "view", "shell", "stop", "status", "follow", "recordings", "doctor", "save", "history");

    /// How many arguments may follow the other commands.
    private static final Association<String, Integer> MOST_ARGUMENTS =
            Association.between(String.class, Integer.class)
                .put("config", 2).put("completion", 1).put("restore", 2).put("schedule", 3).put("ask", 2)
                .put("conversations", 2).put("cancel", 2)
                .put("list", 0).put("version", 0).put("help", 0).put("about", 0).put("guide", 0);

    /// The line of [#usage] that describes `command`, such as `oillamp stop <dir>`.
    static String usageOf(String command) {
        return usage().lines().map(String::strip)
                .filter(line -> line.equals(command) || line.startsWith(command + " "))
                .findFirst().map(line -> "oillamp " + line).orElse(usage());
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
