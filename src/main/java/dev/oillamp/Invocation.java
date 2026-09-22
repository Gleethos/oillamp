package dev.oillamp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import sprouts.Tuple;

/**
 * Parses what the user typed and dispatches it — spec §28.
 *
 * <p>Hand-written rather than delegating to a command-line library, because oillamp's surface is
 * a handful of subcommands with a handful of flags, and the two things that actually matter here
 * — a helpful message for a mistyped command, and exit code 2 for every usage error — are easier
 * to get exactly right directly than to configure.
 *
 * <p>Deliberately <b>package-private</b>: the parsed command line. §28's grammar is the contract
 * users type against; this record is merely how it is represented in memory today.
 */
final class Invocation {

    private Invocation() {}

    static ExitStatus execute(Machine machine, Consumer<LampEvent> sink,
                              ConsoleRenderer console, String version, String... argv) {
        List<String> arguments = new ArrayList<>(List.of(argv));

        Context.Options options = Context.Options.defaults();
        List<String> positional = new ArrayList<>();
        for (String argument : arguments) {
            switch (argument) {
                case "--verbose", "-v" -> options = options.withVerbose(true);
                case "--debug"         -> options = options.withDebug(true).withVerbose(true);
                case "--no-color"      -> { }   // honoured by the renderer, which is already built
                case "--dry-run"       -> options = options.withDryRun(true);
                case "--no-install"    -> options = options.withAutoInstall(false);
                case "--init"          -> options = options.withInit(true);
                case "--no-viewer"     -> options = options.withViewer(false);
                default -> {
                    if (argument.startsWith("-")) {
                        console.banner(version, "");
                        sink.accept(new LampEvent.Failure(Problems.usage(
                                "'" + argument + "' is not an option oillamp knows", usage())));
                        return ExitStatus.USAGE;
                    }
                    positional.add(argument);
                }
            }
        }

        // Now that the options are known, tell the renderer. Until this line --verbose parsed
        // correctly and changed nothing, because the sink was holding the renderer built before
        // the command line was read.
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
                console.banner(version, rest.isEmpty() ? "" : rest.get(0));
                yield commands.doctor(rest.isEmpty() ? Optional.empty() : Optional.of(Path.of(rest.get(0))));
            }
            case "at" -> {
                if (rest.isEmpty()) yield missingDirectory(console, sink, version, "at");
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
            // These arrive with the milestones that make them meaningful; saying so beats a
            // bare "unknown command" for something the help text lists.
            case "view", "shell", "stop", "status", "list", "recordings", "image" -> {
                sink.accept(new LampEvent.Failure(Problems.usage(
                        "'" + command + "' needs a running session, which the next milestone adds",
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

    static String usage() {
        return """
            oillamp [--verbose] [--debug] [--no-color] <command>

              at <dir> [--init] [--dry-run] [--no-install] [--no-viewer]
                    Set up (if needed) and start a session.
              doctor [<dir>]
                    Check the host, and the lamp if one is given. Changes nothing.
              config <dir> (check | show-effective | path)
                    Validate the configuration, print it, or print its path.
              version
              help
            """;
    }
}
