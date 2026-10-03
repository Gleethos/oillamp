package dev.oillamp;

import java.nio.file.Path;
import java.util.Optional;

import sprouts.Tuple;

/// What the command line asks oillamp to do, once [Invocation#parse] has found nothing wrong with
/// it. Each command holds what it was given, and the options every command passes on to [Context].
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
            record Add(String prompt, Optional<String> cron, Optional<String> at, Optional<String> expires) implements Action {}
            record Remove(String job) implements Action {}
            record Enable(String job) implements Action {}
            record Disable(String job) implements Action {}
            record Pause() implements Action {}
            record Resume() implements Action {}
        }
    }

    record Conversations(Path lamp, Optional<String> conversation, Context.Options options) implements Command {}

    record Ask(Path lamp, String prompt, Commands.AskPlace place, boolean waitForAnswer, Context.Options options) implements Command {}

    record Cancel(Path lamp, Optional<String> run, Context.Options options) implements Command {}
}
