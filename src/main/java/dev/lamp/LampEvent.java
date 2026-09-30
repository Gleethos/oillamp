package dev.lamp;

import java.time.Duration;

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
    static java.util.Optional<LampEvent> fromJson(String line) { return Wire.read(line); }

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
    record SessionOpened(String session, sprouts.Tuple<String> command, java.nio.file.Path desktop)
            implements LampEvent {}

    /// A window oillamp opened on the user's desktop: the terminal or a viewer.
    ///
    /// It carries the full command line, because when a window opens and closes again, the first
    /// question is what exactly was run.
    record WindowOpened(String what, sprouts.Tuple<String> argv) implements LampEvent {}

    /// A block of lines with a title, such as the briefing when a session starts or the summary when it ends.
    record Summary(String title, sprouts.Tuple<String> lines) implements LampEvent {}

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
    record Snapshot(String id, java.time.Instant at, SaveKind kind, String message,
                    java.util.Optional<String> session, java.util.Optional<String> run) {

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
    record History(sprouts.Tuple<Snapshot> snapshots)   implements LampEvent {}

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
    record Job(String id, String when, java.util.Optional<java.time.Instant> next, String prompt,
               JobAuthor author, java.time.Instant created,
               java.util.Optional<java.time.Instant> expires, boolean enabled) {}

    /// Who added a job. The user's jobs are theirs alone: the agent may read them but not change
    /// or remove them.
    enum JobAuthor { USER, AGENT }

    /// A lamp's schedule. What `oillamp schedule` answers.
    ///
    /// @param enabled whether `schedule.enabled` is on in `oillamp.toml`. When it is off, no job runs
    /// @param paused  whether the user paused the schedule with `oillamp schedule <dir> pause`
    /// @param zone    the time zone the jobs' times are read in, such as `Europe/Berlin`
    record Schedule(boolean enabled, boolean paused, String zone, sprouts.Tuple<Job> jobs)
            implements LampEvent {}

    /// A job was added to the schedule, by the user or by the agent.
    record JobAdded(Job job)                            implements LampEvent {}
    /// A job was taken off the schedule.
    ///
    /// @param why for example "removed by the user", "expired" or "ran once, as it was meant to"
    record JobRemoved(Job job, String why)              implements LampEvent {}
    /// A job was switched on or off, or the whole schedule paused or resumed.
    record ScheduleChanged(String what)                 implements LampEvent {}

    /// One time the agent was woken: by a job, or by someone asking it something.
    ///
    /// @param id  the run's name, such as `run-12`. A run's snapshot carries it, so
    ///            `oillamp history` shows which snapshot a run made
    /// @param job          the job that woke the agent, or empty when someone asked
    /// @param conversation the conversation it continues, or empty for a new one
    record Run(String id, java.util.Optional<String> job, String prompt, java.util.Optional<String> conversation) {}

    /// A run has to wait, because the agent is busy with another one.
    ///
    /// @param ahead how many runs are before it
    record RunQueued(Run run, int ahead)                implements LampEvent {}
    /// The agent was woken and given the prompt.
    record RunStarted(Run run)                          implements LampEvent {}
    /// A run ended.
    ///
    /// @param outcome  how it ended
    /// @param answer   the agent's last message, which usually says what it did
    /// @param snapshot the snapshot of the lamp made as it ended, or empty when that save failed
    /// @param took     how long the agent worked
    /// @param conversation the conversation it happened in, which for a new one is known only
    ///                 now; empty when pi did not get as far as opening one
    record RunFinished(Run run, RunOutcome outcome, String answer,
                       java.util.Optional<Snapshot> snapshot, Duration took,
                       java.util.Optional<String> conversation) implements LampEvent {}

    /// How a run ended.
    enum RunOutcome {
        /// The agent finished and said so.
        FINISHED,
        /// The agent, or the model behind it, failed. The answer says why.
        FAILED,
        /// The run took longer than `schedule.max_run_minutes` and was stopped.
        TIMED_OUT,
        /// The session ended while the agent was working.
        INTERRUPTED
    }

    // ─── conversations ─────────────────────────────────────────────────────────────────────

    /// The conversations the agent had in a lamp, the most recent first. What
    /// `oillamp conversations <dir>` answers.
    record Conversations(sprouts.Tuple<Lamp.Conversation> conversations) implements LampEvent {}

    /// One conversation, in full. What `oillamp conversations <dir> <conversation>` answers.
    record ConversationShown(Lamp.Conversation conversation) implements LampEvent {}

    /// Something went wrong but oillamp carried on.
    record Warning(Problem problem)                     implements LampEvent {}
    /// Something went wrong and oillamp stopped.
    record Failure(Problem problem)                     implements LampEvent {}
}
