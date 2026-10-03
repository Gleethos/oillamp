package dev.oillamp;

import java.nio.file.Path;
import java.util.function.Consumer;

import dev.lamp.ExitStatus;
import dev.lamp.LampEvent;
import dev.lamp.Problem;

import sprouts.Tuple;

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

        Result<Command> parsed = Command.parse(arguments);
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
                sink.accept(new LampEvent.Answer(Command.usage()));
                yield ExitStatus.USAGE;
            }
            case Command.Version _ -> {
                sink.accept(new LampEvent.Answer("oillamp " + version + "\njava " + Runtime.version()));
                yield ExitStatus.SUCCESS;
            }
            case Command.Help _ -> {
                sink.accept(new LampEvent.Answer(Command.usage()));
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
                        Command.usage())));
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
}
