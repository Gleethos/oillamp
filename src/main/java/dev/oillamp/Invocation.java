package dev.oillamp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

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

        Context.Options options = Context.Options.defaults();
        // Only `view` reads this, so it stays a local rather than joining Options, where every
        // command would carry a switch that means nothing to it.
        boolean viewOnly = false;
        // Likewise --yes, which only `remove` reads. It is not a general "assume yes": it is the
        // answer to one question, asked by one command, that deletes the agent's home.
        boolean confirmed = false;
        // `recordings` only. --open takes a session id, so it is the one option here that
        // consumes the argument after it; openPending is how a flat switch does that.
        Optional<String> open = Optional.empty();
        boolean openPending = false;
        boolean prune = false;
        List<String> positional = new ArrayList<>();
        for (String argument : arguments) {
            switch (argument) {
                case "--verbose", "-v" -> options = options.withVerbose(true);
                case "--debug"         -> options = options.withDebug(true).withVerbose(true);
                case "--no-color"      -> { }   // handled before this loop
                case "--dry-run"       -> options = options.withDryRun(true);
                case "--no-install"    -> options = options.withAutoInstall(false);
                case "--init"          -> options = options.withInit(true);
                case "--no-viewer"     -> options = options.withViewer(false);
                case "--view-only"     -> viewOnly = true;
                case "--yes", "-y"     -> confirmed = true;
                case "--prune"         -> prune = true;
                case "--open"          -> openPending = true;
                default -> {
                    if (argument.startsWith("--open=")) {
                        open = Optional.of(argument.substring("--open=".length()));
                    } else if (openPending) {
                        open = Optional.of(argument);
                        openPending = false;
                    } else if (argument.startsWith("-")) {
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

        if (openPending) {
            console.banner(version, "");
            sink.accept(new LampEvent.Failure(Problems.usage(
                    "--open needs the session to play, for example --open 20260101-120000",
                    "oillamp recordings <dir> [--open <session>] [--prune]")));
            return ExitStatus.USAGE;
        }

        // The renderer was created before the options were known, so tell it now.
        console.verbose(options.verbose());

        if (positional.isEmpty()) {
            console.banner(version, "");
            sink.accept(new LampEvent.Answer(usage()));
            return ExitStatus.USAGE;
        }

        String command = positional.get(0);
        List<String> rest = positional.subList(1, positional.size());

        if (command.equals("version")) {
            sink.accept(new LampEvent.Answer(
                    "oillamp " + version + "\njava " + Runtime.version()));
            return ExitStatus.SUCCESS;
        }
        if (command.equals("help")) {
            sink.accept(new LampEvent.Answer(usage()));
            return ExitStatus.SUCCESS;
        }

        Context context = new Context(sink, options, version);
        Commands commands = new Commands(machine, context);

        return switch (command) {
            case "doctor" -> {
                if (rest.size() > 1) yield oneLampOnly(console, sink, version, "doctor", rest);
                console.banner(version, rest.isEmpty() ? "" : rest.get(0));
                yield commands.doctor(rest.isEmpty() ? Optional.empty() : Optional.of(Path.of(rest.get(0))));
            }
            case "at" -> {
                if (rest.isEmpty()) yield missingDirectory(console, sink, version, "at");
                if (rest.size() > 1) yield oneLampOnly(console, sink, version, "at", rest);
                console.banner(version, rest.get(0));
                yield commands.at(Path.of(rest.get(0)));
            }
            case "config" -> {
                if (rest.isEmpty()) yield missingDirectory(console, sink, version, "config");
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
            // These four talk to a running session through its control socket. None of them
            // sets anything up.
            case "view" -> {
                if (rest.isEmpty()) yield missingDirectory(console, sink, version, "view");
                if (rest.size() > 1) yield oneLampOnly(console, sink, version, "view", rest);
                yield commands.view(Path.of(rest.get(0)), viewOnly);
            }
            case "shell" -> {
                if (rest.isEmpty()) yield missingDirectory(console, sink, version, "shell");
                if (rest.size() > 1) yield oneLampOnly(console, sink, version, "shell", rest);
                yield commands.shell(Path.of(rest.get(0)));
            }
            case "stop" -> {
                if (rest.isEmpty()) yield missingDirectory(console, sink, version, "stop");
                if (rest.size() > 1) yield oneLampOnly(console, sink, version, "stop", rest);
                yield commands.stop(Path.of(rest.get(0)));
            }
            case "status" -> {
                if (rest.isEmpty()) yield missingDirectory(console, sink, version, "status");
                if (rest.size() > 1) yield oneLampOnly(console, sink, version, "status", rest);
                yield commands.status(Path.of(rest.get(0)));
            }
            case "list" -> commands.list();

            // Not one of the four above: it talks to no session, and refuses if one answers.
            // The one command that takes several lamps, since a pattern like `test*` is the
            // natural way to clean up after experiments.
            case "remove" -> {
                if (rest.isEmpty()) yield missingDirectory(console, sink, version, "remove");
                console.banner(version, rest.size() == 1 ? rest.get(0) : rest.size() + " lamps");
                Tuple<Path> lamps = Tuple.of(Path.class);
                for (String lamp : rest) lamps = lamps.add(Path.of(lamp));
                yield commands.remove(lamps, confirmed);
            }

            case "recordings" -> {
                if (rest.isEmpty()) yield missingDirectory(console, sink, version, "recordings");
                if (rest.size() > 1) yield oneLampOnly(console, sink, version, "recordings", rest);
                yield commands.recordings(Path.of(rest.get(0)), open, prune);
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
                console.plain(Templates.bashCompletion());
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

              at <dir> [--init] [--dry-run] [--no-install] [--no-viewer]
                    Set up (if needed) and run a session. Stays in the foreground until it ends.
              view <dir> [--view-only]
                    Open another window onto a running session's desktop.
              shell <dir>
                    Open an extra shell in this terminal. Closing it does not end the session.
              stop <dir>
                    Ask a running session to shut down, or clean up after one that crashed.
              status <dir>
                    What a running session is doing.
              list
                    Every oillamp sandbox running on this host.
              remove <dir>... --yes
                    Delete one or more lamps: the agent's home, the state, the config. Without
                    --yes it only says what would go. Every lamp is checked first; if any cannot
                    be removed, none is. Needed because parts of a lamp belong to the sandbox's
                    own users and `rm -rf` cannot remove them.
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
              version
              help
            """;
    }
}
