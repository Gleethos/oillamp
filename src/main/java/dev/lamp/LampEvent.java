package dev.lamp;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import sprouts.Tuple;

/// Everything oillamp reports, as values.
///
/// oillamp never writes to the console directly. It emits these events, and the console
/// renderer prints them. Tests inspect them, and a future graphical front end could display them
/// its own way.
///
/// Public because any caller that wants to show progress, including a GUI, needs to see them.
public sealed interface LampEvent {

    /// This event as one line of JSON, the form in which an embedded oillamp reports it to the
    /// application that started it. [#fromJson] reads it back into an equal value.
    default String toJson() { return Wire.write(this); }

    /// Reads a line written by [#toJson].
    ///
    /// Empty for a line that is not an event this version of oillamp knows, such as one from a
    /// newer version, so that a reader can skip it rather than fail.
    static Optional<LampEvent> fromJson(String line) { return Wire.read(line); }

    /// The phases of `oillamp at`, in order.
    enum Phase {
        /// Check and repair the host itself: packages, subuid ranges, a working rootless podman.
        HOST,
        /// Create or load the lamp: config, identity, lock, keys, per-session files.
        LAMP,
        /// Make sure a sandbox image matching the current configuration exists.
        IMAGE,
        /// Start the container, the relays and the windows, then supervise.
        SESSION
    }

    /// A planned or executed unit of work, projected for display.
    ///
    /// @param kind     the step's type name, for example `"InstallPackages"`; safe to match on
    /// @param describe one line, for the console and for `--dry-run`
    /// @param detail   the full story, for the session log
    record StepInfo(String kind, String describe, String detail) {}

    record PhaseStarted(Phase phase)                    implements LampEvent {}
    record PhaseFinished(Phase phase, Duration took)    implements LampEvent {}

    /// A check that passed, e.g. `[host] ✓ podman 5.4.2, rootless, crun`.
    record Ok(String area, String text)                 implements LampEvent {}
    /// A neutral remark, e.g. why the session fell back to software rendering.
    record Info(String area, String text)               implements LampEvent {}

    /// Emitted instead of performing a step when `--dry-run` is given.
    record StepPlanned(StepInfo step)                   implements LampEvent {}
    record StepStarted(StepInfo step)                   implements LampEvent {}
    record StepSucceeded(StepInfo step, Duration took)  implements LampEvent {}
    record StepSkipped(StepInfo step, String why)       implements LampEvent {}

    /// A line of output from a subprocess, tagged with its source (e.g. `"image"`, `"sway"`).
    record Output(String sourceTag, String line)        implements LampEvent {}

    /// What the user asked to see: the effective configuration, the version, the usage text.
    ///
    /// Unlike [Output], which is a subprocess's output and hidden unless `--verbose`,
    /// an answer is always shown.
    record Answer(String text)                          implements LampEvent {}

    /// A summary of a session's state, such as "running, up 3 minutes, one extra shell".
    ///
    /// This is a description of the supervisor's internal state, not the state itself, so the
    /// internal states can change without changing this public type.
    record SessionStatus(String state, String detail, Duration uptime, int extraShells) {}

    record SessionStateChanged(SessionStatus status)    implements LampEvent {}

    /// The session is running, and this is how to run a command in its sandbox.
    ///
    /// Reported once per session. An application reads the command from here rather than working
    /// out socket paths itself, because the engine is the one that knows them.
    ///
    /// @param session the session id, such as `20260928-120000`
    /// @param command an `ssh` command line. Append a command, as ssh expects it, and it runs in
    ///                the sandbox as the agent user, with the same environment as the agent's
    ///                shell, and with no terminal. It counts as an extra shell while it runs
    /// @param desktop the Unix socket of the sandbox's desktop. It speaks VNC (RFB 3.8) with no
    ///                password, because only this user can open it
    /// @param desktopWidth  the desktop's own width in pixels, `display.width`. A viewer may give
    ///                the desktop another size for a while, such as the size of a panel it shows
    ///                it in; this is the size to give it back
    /// @param desktopHeight the desktop's own height in pixels, `display.height`
    record SessionOpened(String session, Tuple<String> command, Path desktop, int desktopWidth, int desktopHeight)
            implements LampEvent {}

    /// The agent asks the user to look at its desktop, because it shows them something there.
    /// An application that shows the desktop can open it now.
    ///
    /// @param what what the agent shows, in its own words, such as "the chart you asked for".
    ///             Control characters are removed, and it is at most 200 characters long. Empty
    ///             when the agent did not say
    record LookAtDesktop(String what) implements LampEvent {}

    /// A window oillamp opened on the user's desktop: the terminal or a viewer.
    ///
    /// It carries the full command line, because when a window opens and closes again, the first
    /// question is what exactly was run.
    record WindowOpened(String what, Tuple<String> argv) implements LampEvent {}

    /// A block of lines with a title, such as the briefing when a session starts or the summary when it ends.
    record Summary(String title, Tuple<String> lines) implements LampEvent {}

    /// One saved state of a lamp: the agent's home and the lamp's `oillamp.toml`, as they were
    /// at one moment. `oillamp restore` brings a lamp back to it.
    ///
    /// @param id      the snapshot's name, 40 hexadecimal characters. Any unique beginning of it
    ///                of at least four characters names it too, such as the eight `oillamp
    ///                history` shows
    /// @param at      when it was saved
    /// @param kind    what made it
    /// @param message what the person who saved it wrote, or empty
    /// @param session the session running at the time, such as `20260928-120000`, or empty
    /// @param run     for a snapshot made as a run began or ended, the run, such as `run-12`
    /// @param job     for a snapshot made as a job's run ended, the job, such as `job-3`
    /// @param outcome for a snapshot made as a run ended, how it ended
    /// @param conversation for a snapshot made as a run ended, the id of the conversation the run
    ///                had; empty when the agent did not get as far as opening one
    record Snapshot(String id, Instant at, SaveKind kind, String message, Optional<String> session,
                    Optional<String> run, Optional<String> job, Optional<RunOutcome> outcome,
                    Optional<String> conversation) {

        /// The first eight characters of [#id()], which is how oillamp shows a snapshot.
        public String shortId() { return id.substring(0, Math.min(8, id.length())); }
    }

    /// What made a snapshot.
    enum SaveKind {
        /// oillamp saved as a session started, before the sandbox ran.
        STARTUP("startup save"),
        /// Someone saved while a session was running. Programs in the sandbox may have been
        /// writing at that moment.
        RUNNING("running save"),
        /// oillamp saved as a session ended, after the sandbox had stopped.
        SHUTDOWN("shutdown save"),
        /// Someone saved while no session was running.
        IDLE("idle save"),
        /// oillamp saved just before a restore, so that the restore can be undone.
        BEFORE_RESTORE("safety save before a restore"),
        /// A restore: the lamp was brought back to an earlier snapshot.
        RESTORE("restore"),
        /// oillamp saved just before waking the agent, so that what the user changed and what
        /// the agent then did are two separate snapshots.
        BEFORE_RUN("save before a run"),
        /// oillamp saved as a run ended. The message holds the agent's last words.
        RUN("run");

        private final String label;

        SaveKind(String label) { this.label = label; }

        /// How oillamp names it, such as `startup save`.
        public String label() { return label; }
    }

    /// A save made a snapshot. A save that finds nothing changed makes none, and reports an
    /// [Info] instead.
    ///
    /// @param files how many files the snapshot holds; 0 where that was not counted
    record Saved(Snapshot snapshot, int files)          implements LampEvent {}

    /// A lamp's snapshots, newest first. What `oillamp history` answers.
    record History(Tuple<Snapshot> snapshots) implements LampEvent {}

    /// The lamp was brought back to `target`, and `result` is the snapshot that records it.
    /// When the lamp already was as `target` holds it, nothing changed and `result` is `target`.
    record Restored(Snapshot target, Snapshot result)   implements LampEvent {}

    // ─── the schedule, and the runs that wake the agent ───────────────────────────────────

    /// One job on a lamp's schedule: a prompt that wakes the agent at a set time, or again and
    /// again. Jobs only run while a session runs.
    ///
    /// @param id      the job's name, such as `job-3`
    /// @param when    when it runs, as it was written: a cron expression such as `0 9 * * 1-5`,
    ///                or `once at` and a time
    /// @param next    when it runs next; empty when it will not run again, or is disabled
    /// @param prompt  what the agent is asked when it wakes
    /// @param author  who added it
    /// @param created when it was added
    /// @param expires when it is taken off the schedule; empty for a job that stays until removed
    /// @param enabled false for a job the user switched off, which keeps its place but does not run
    /// @param upcoming when it runs after the moment the schedule was read and within seven days
    ///                of it, before it expires, earliest first; at most 200 times. Empty when it is
    ///                switched off. A `next` at or before that moment is not among them: the job
    ///                came due, and runs as soon as a session can run it
    record Job(String id, String when, Optional<Instant> next, String prompt, JobAuthor author,
               Instant created, Optional<Instant> expires, boolean enabled, Tuple<Instant> upcoming) {}

    /// Who added a job. The user's jobs are theirs alone: the agent may read them but not change
    /// or remove them.
    enum JobAuthor { USER, AGENT }

    /// A lamp's schedule. What `oillamp schedule` answers.
    ///
    /// @param enabled whether `schedule.enabled` is on in `oillamp.toml`. When it is off, no job runs
    /// @param paused  whether the user paused the schedule with `oillamp schedule <dir> pause`
    /// @param zone    the time zone the jobs' times are read in, such as `Europe/Berlin`
    record Schedule(boolean enabled, boolean paused, String zone, Tuple<Job> jobs)
            implements LampEvent {}

    /// A job was added to the schedule, by the user or by the agent.
    record JobAdded(Job job)                            implements LampEvent {}
    /// A job was taken off the schedule.
    ///
    /// @param why for example "removed by the user", "expired" or "ran once, as it was meant to"
    record JobRemoved(Job job, String why)              implements LampEvent {}
    /// A job was switched on or off, or the whole schedule paused or resumed.
    record ScheduleChanged(String what)                 implements LampEvent {}

    /// One time the agent is woken, by a job or by someone asking. Runs happen one at a time.
    ///
    /// @param id           `run-` and a number counted per lamp, such as `run-12`. The snapshots
    ///                     taken as the run began and ended carry it too
    /// @param job          the job that woke the agent; empty when someone asked
    /// @param prompt       the job's prompt, or the question, as written
    /// @param conversation the conversation the run continues; empty for a new one
    record Run(String id, Optional<String> job, String prompt, Optional<String> conversation) {}

    /// A run waits, because the agent is busy.
    ///
    /// @param ahead runs waiting before this one, not counting the one in progress
    record RunQueued(Run run, int ahead)                implements LampEvent {}
    record RunStarted(Run run)                          implements LampEvent {}
    /// A run ended, and the lamp was saved.
    ///
    /// @param answer       the agent's last message; for a failed run, the error
    /// @param snapshot     the snapshot taken as the run ended; empty only when that save failed
    /// @param took         from [RunStarted] to this event
    /// @param conversation the conversation the run happened in; empty when pi failed before it
    ///                     opened one
    record RunFinished(Run run, RunOutcome outcome, String answer,
                       Optional<Snapshot> snapshot, Duration took,
                       Optional<String> conversation) implements LampEvent {}

    /// The session queued a question as `run`. What `oillamp ask --no-wait` and [Lamp#send] report.
    record RunAccepted(Run run)                         implements LampEvent {}

    /// Something the agent did during a run, as pi reports it.
    ///
    /// @param run the run's id
    record RunProgress(String run, Progress progress)   implements LampEvent {}

    /// In one run: [Opened] once, then per message of the agent's, [Thought] and [Said] pieces and
    /// an [Answered], followed by a [ToolStarted] and a [ToolFinished] for each tool it calls.
    sealed interface Progress {
        /// The conversation the run happens in.
        record Opened(String conversation) implements Progress {}
        /// Part of the agent's message. The parts, joined, are the text [Answered] reports.
        record Said(String text) implements Progress {}
        /// Part of the model's thinking, for models that show it.
        record Thought(String text) implements Progress {}
        /// A message of the agent's is complete. The last one of a run is its answer.
        ///
        /// @param text   the message; when it failed, the error
        /// @param failed the model failed, or the message was stopped
        record Answered(String text, boolean failed) implements Progress {}
        /// A tool call began.
        ///
        /// @param call    the id the matching [ToolFinished] has
        /// @param summary the command for `bash`, the path for the file tools, otherwise the
        ///                arguments as JSON; one line, at most 160 characters
        record ToolStarted(String call, String tool, String summary) implements Progress {}
        /// A tool call ended.
        ///
        /// @param failed the tool reported an error
        /// @param output the first 4,000 characters of the output
        record ToolFinished(String call, boolean failed, String output) implements Progress {}
        /// A request to the model failed, and pi tries it again.
        ///
        /// @param attempt the try this is, from 1
        /// @param most    the tries pi makes before the run fails
        /// @param why     what the model service said
        record Retrying(int attempt, int most, String why) implements Progress {}
    }

    /// What `oillamp status` and [Lamp#agentStatus] report about the agent.
    ///
    /// @param current the run in progress; empty when the agent is idle
    /// @param waiting the runs waiting, in the order they will run
    record AgentStatus(Optional<Run> current, Tuple<Run> waiting) implements LampEvent {
        public boolean busy() { return current.isPresent() || !waiting.isEmpty(); }
    }

    /// How a run ended.
    enum RunOutcome {
        /// The agent finished and said so.
        FINISHED,
        /// The agent, or the model behind it, failed. The answer says why.
        FAILED,
        /// The run took longer than `schedule.max_run_minutes` and was stopped.
        TIMED_OUT,
        /// The session ended while the agent was working.
        INTERRUPTED,
        /// Someone stopped it, with `oillamp cancel` or [Lamp#cancel()].
        CANCELLED
    }

    // ─── conversations ─────────────────────────────────────────────────────────────────────

    /// The conversations the agent had in a lamp, the most recent first. What
    /// `oillamp conversations <dir>` answers.
    record Conversations(Tuple<Lamp.Conversation> conversations) implements LampEvent {}

    /// One conversation, in full. What `oillamp conversations <dir> <conversation>` answers.
    record ConversationShown(Lamp.Conversation conversation) implements LampEvent {}

    /// Something went wrong but oillamp carried on.
    record Warning(Problem problem)                     implements LampEvent {}
    /// Something went wrong and oillamp stopped.
    record Failure(Problem problem)                     implements LampEvent {}
}
