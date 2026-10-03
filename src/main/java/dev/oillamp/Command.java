package dev.oillamp;

import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;

import dev.lamp.Problem;

import sprouts.Association;
import sprouts.Tuple;
import sprouts.ValueSet;

/// Everything oillamp can be asked on the command line: one record per command, holding what it
/// was given and the options every command passes on to [Context]; [#parse], which reads a
/// command line into one of them or says what is wrong with it; and the usage text.
sealed interface Command {

    Context.Options options();

    /// Only options, or nothing at all: oillamp shows how to use it, and fails.
    record NoCommand(Context.Options options) implements Command {}

    record Version(Context.Options options) implements Command {}

    record Help(Context.Options options) implements Command {}

    record About(Context.Options options) implements Command {}

    record Guide(Context.Options options) implements Command {}

    /// `completion bash`; bash is the only shell there is a script for.
    record Completion(Context.Options options) implements Command {}

    /// Planned once and then dropped: the image is rebuilt automatically whenever its inputs
    /// change, so there is nothing to manage by hand.
    record Image(Context.Options options) implements Command {}

    record Doctor(Optional<Path> lamp, Context.Options options) implements Command {}

    record Config(Path lamp, Config.Action action, Context.Options options) implements Command {
        enum Action { CHECK, SHOW_EFFECTIVE, PATH }
    }

    record At(Path lamp, Context.Options options) implements Command {}

    record View(Path lamp, boolean viewOnly, Context.Options options) implements Command {}

    record Shell(Path lamp, Context.Options options) implements Command {}

    record Stop(Path lamp, Context.Options options) implements Command {}

    record Status(Path lamp, Context.Options options) implements Command {}

    record Follow(Path lamp, Context.Options options) implements Command {}

    record List(Context.Options options) implements Command {}

    /// `confirmed` is `--yes`: the answer to the one question `remove` asks before it deletes
    /// the agent's home, not a general "assume yes".
    record Remove(Tuple<Path> lamps, boolean confirmed, Context.Options options) implements Command {}

    record Recordings(Path lamp, Optional<String> open, boolean prune, Context.Options options) implements Command {}

    record Save(Path lamp, String message, Context.Options options) implements Command {}

    record History(Path lamp, Context.Options options) implements Command {}

    record Restore(Path lamp, String snapshot, Context.Options options) implements Command {}

    record Schedule(Path lamp, Schedule.Action action, Context.Options options) implements Command {

        /// What `oillamp schedule <dir>` is asked to do; `ListJobs` when nothing is asked.
        sealed interface Action {
            record ListJobs() implements Action {}
            record Add(String prompt, Optional<String> cron, Optional<String> at,
                       Optional<String> expires) implements Action {}
            record Remove(String job) implements Action {}
            record Enable(String job) implements Action {}
            record Disable(String job) implements Action {}
            record Pause() implements Action {}
            record Resume() implements Action {}
        }
    }

    record Conversations(Path lamp, Optional<String> conversation, Context.Options options) implements Command {}

    /// `conversation` is the one `--in` names, by its id or the start of it; without one the
    /// question starts a new conversation. `after` names an entry in it to continue after, other
    /// than one of the user's questions; `insteadOf` one of the user's questions, to ask this one
    /// instead of.
    record Ask(Path lamp, String prompt, Optional<String> conversation, Optional<String> after,
               Optional<String> insteadOf, boolean waitForAnswer, Context.Options options) implements Command {}

    record Cancel(Path lamp, Optional<String> run, Context.Options options) implements Command {}

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

        if (positional.isEmpty()) return Result.ok(new NoCommand(options));

        String command = positional.first();
        Tuple<String> rest = positional.removeFirst();

        Optional<ValueSet<String>> takes = OPTIONS_OF.get(command);
        if (takes.isPresent()) {
            Optional<String> misplaced = commandOptions.stream()
                    .filter(option -> !takes.get().contains(option)).findFirst();
            if (misplaced.isPresent())
                return Result.err(ProblemCatalogUtil.usage(
                        "`oillamp " + command + "` does not take " + misplaced.get(), usageOf(command)));
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
                return Result.err(ProblemCatalogUtil.usage(
                        "`oillamp " + command + "` takes no arguments, but was given "
                      + rest.join(" "), usageOf(command)));
            if (rest.size() > allowed)
                return Result.err(ProblemCatalogUtil.usage("`oillamp " + command
                      + "` was given more than it takes, starting with '" + rest.get(allowed) + "'", usageOf(command)));
        }

        return switch (command) {
            case "version" -> Result.ok(new Version(options));
            case "help"    -> Result.ok(new Help(options));
            case "about"   -> Result.ok(new About(options));
            case "guide"   -> Result.ok(new Guide(options));
            case "image"   -> Result.ok(new Image(options));
            case "completion" -> {
                String shell = rest.isEmpty() ? "bash" : rest.first();
                if (!shell.equals("bash"))
                    yield Result.err(ProblemCatalogUtil.usage(
                            "oillamp only ships a completion script for bash, not '" + shell + "'",
                            "oillamp completion bash"));
                yield Result.ok(new Completion(options));
            }
            case "doctor" -> Result.ok(new Doctor(
                    rest.isEmpty() ? Optional.empty() : Optional.of(Path.of(rest.first())), options));
            case "config" -> {
                String action = rest.size() > 1 ? rest.get(1) : "check";
                yield switch (action) {
                    case "check"          -> Result.ok(new Config(Path.of(rest.first()), Config.Action.CHECK, options));
                    case "show-effective" ->
                            Result.ok(new Config(Path.of(rest.first()), Config.Action.SHOW_EFFECTIVE, options));
                    case "path"           -> Result.ok(new Config(Path.of(rest.first()), Config.Action.PATH, options));
                    default -> Result.err(ProblemCatalogUtil.usage("'" + action + "' is not a config action",
                            "oillamp config <dir> (check | show-effective | path)"));
                };
            }
            case "at"     -> Result.ok(new At(Path.of(rest.first()), options));
            case "view"   -> Result.ok(new View(Path.of(rest.first()), viewOnly, options));
            case "shell"  -> Result.ok(new Shell(Path.of(rest.first()), options));
            case "stop"   -> Result.ok(new Stop(Path.of(rest.first()), options));
            case "status" -> Result.ok(new Status(Path.of(rest.first()), options));
            case "follow" -> Result.ok(new Follow(Path.of(rest.first()), options));
            case "list"   -> Result.ok(new List(options));
            // The one command that takes several lamps, since a pattern like `test*` is the
            // natural way to clean up after experiments.
            case "remove" -> Result.ok(new Remove(rest.mapTo(Path.class, Path::of), confirmed, options));
            case "recordings" -> Result.ok(new Recordings(Path.of(rest.first()), values.get("--open"),
                    prune, options));
            case "save" -> Result.ok(new Save(Path.of(rest.first()), values.get("--message").orElse(""),
                    options));
            case "history" -> Result.ok(new History(Path.of(rest.first()), options));
            case "restore" -> {
                if (rest.size() < 2)
                    yield Result.err(ProblemCatalogUtil.usage(
                            "`oillamp restore` needs the snapshot to go back to, as `oillamp history` names it",
                            usageOf("restore")));
                yield Result.ok(new Restore(Path.of(rest.first()), rest.get(1), options));
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
                    yield Result.err(ProblemCatalogUtil.usage(
                            "--cron, --at and --expires only go with `oillamp schedule <dir> add`", usageOf("schedule")));
                String job = argument.orElse("");
                Optional<Schedule.Action> chosen = switch (action) {
                    case "list"    -> Optional.of(new Schedule.Action.ListJobs());
                    case "add"     -> Optional.of(new Schedule.Action.Add(job, cron, at, expires));
                    case "remove"  -> Optional.of(new Schedule.Action.Remove(job));
                    case "enable"  -> Optional.of(new Schedule.Action.Enable(job));
                    case "disable" -> Optional.of(new Schedule.Action.Disable(job));
                    case "pause"   -> Optional.of(new Schedule.Action.Pause());
                    case "resume"  -> Optional.of(new Schedule.Action.Resume());
                    default        -> Optional.empty();
                };
                if (chosen.isEmpty())
                    yield Result.err(ProblemCatalogUtil.usage(
                            "'" + action + "' is not something `oillamp schedule` does", usageOf("schedule")));
                yield Result.ok(new Schedule(Path.of(rest.first()), chosen.get(), options));
            }
            case "conversations" -> Result.ok(new Conversations(Path.of(rest.first()),
                    rest.size() > 1 ? Optional.of(rest.get(1)) : Optional.empty(), options));
            case "ask" -> {
                Optional<String> in = values.get("--in");
                Optional<String> after = values.get("--after");
                Optional<String> insteadOf = values.get("--instead-of");
                if (rest.size() < 2)
                    yield Result.err(ProblemCatalogUtil.usage(
                            "`oillamp ask` needs something to ask the agent, in quotes", usageOf("ask")));
                if ((after.isPresent() || insteadOf.isPresent()) && in.isEmpty())
                    yield Result.err(ProblemCatalogUtil.usage(
                            "--after and --instead-of name an entry of the conversation that --in names", usageOf("ask")));
                if (after.isPresent() && insteadOf.isPresent())
                    yield Result.err(ProblemCatalogUtil.usage(
                            "a question goes either after an entry or instead of a question, not both", usageOf("ask")));
                yield Result.ok(new Ask(Path.of(rest.first()), rest.get(1), in, after, insteadOf,
                        wait, options));
            }
            case "cancel" -> Result.ok(new Cancel(Path.of(rest.first()),
                    rest.size() > 1 ? Optional.of(rest.get(1)) : Optional.empty(), options));
            default -> Result.err(ProblemCatalogUtil.usage("'" + command + "' is not an oillamp command", usage()));
        };
    }

    /// The options each command takes, besides `--verbose`, `--debug` and `--no-color`, which
    /// every command takes. `doctor` and `config` change nothing anyway, so they accept the two
    /// options that promise that.
    Association<String, ValueSet<String>> OPTIONS_OF =
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
    ValueSet<String> VALUE_OPTIONS = ValueSet.of(
            "--open", "--model-service", "--model-key-env", "--message", "--cron", "--at", "--expires",
            "--in", "--after", "--instead-of");

    /// The commands that take a lamp directory and cannot do without it.
    ValueSet<String> NEEDS_A_LAMP = ValueSet.of(
            "at", "view", "shell", "stop", "status", "follow", "recordings", "config", "remove",
            "save", "history", "restore", "schedule", "ask", "conversations", "cancel");

    /// The commands that work on one lamp. `remove` takes several.
    ValueSet<String> ONE_LAMP_ONLY = ValueSet.of(
            "at", "view", "shell", "stop", "status", "follow", "recordings", "doctor", "save", "history");

    /// How many arguments may follow the other commands.
    Association<String, Integer> MOST_ARGUMENTS =
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
