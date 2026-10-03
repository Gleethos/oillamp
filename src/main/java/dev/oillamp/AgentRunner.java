package dev.oillamp;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import dev.lamp.LampEvent;
import dev.lamp.LampEvent.JobAuthor;
import dev.lamp.LampEvent.RunOutcome;
import dev.lamp.LampEvent.SaveKind;

import sprouts.Association;
import sprouts.Tuple;

/// Hands prompts to the agent (pi, in the sandbox) one at a time, and waits for each answer.
/// Prompts come from two places: jobs on the schedule that are due, and `oillamp ask`. They wait in
/// one queue, so a question asked while a job is being worked on waits until it is done. Handling
/// one prompt is called a run (`run-12`).
///
/// A prompt is not passed on as written: [WakePromptUtil] puts the agent's notes and what recent
/// runs did in front of it. The lamp is saved before a run if anything changed, and after it
/// always, so the history keeps what the user did apart from what the agent did. A run is stopped
/// after `schedule.max_run_minutes`.
///
/// Due jobs are queued only while `schedule.enabled` is on. The agent changes its own schedule
/// through [#answerAgent], under the same rules as the user.
///
/// The queue is kept in memory only: prompts still waiting when the session ends are dropped. The
/// pi process belongs to [Harness], not to this class.
final class AgentRunner {

    /// How often the schedule is looked at when nothing has changed.
    static final Duration TICK = Duration.ofSeconds(30);
    /// How long the end of a session waits for a run to stop and be saved.
    static final Duration WIND_DOWN = Duration.ofMinutes(6);
    /// The most of an agent's last message kept in the snapshot's message.
    private static final int ANSWER_KEPT = 16 * 1024;

    private final Machine machine;
    private final Context context;
    private final LampLayout layout;
    private final LampConfig.Schedule config;
    private final SessionId session;
    private final ScheduleBook book;
    private final Harness harness;

    private final LinkedBlockingDeque<Pending> queue = new LinkedBlockingDeque<>();
    private final Semaphore look = new Semaphore(0);
    private volatile Optional<Pending> current = Optional.empty();
    private volatile boolean stopping;
    private volatile Optional<Thread> worker = Optional.empty();

    /// A run waiting its turn, and whoever waits for its end.
    ///
    /// @param where where the question goes, or empty for a new conversation
    private record Pending(LampEvent.Run run, Optional<ScheduledJob> job, Optional<Harness.Target> where,
                           CompletableFuture<LampEvent.RunFinished> done) {}

    AgentRunner(Machine machine, Context context, LampLayout layout, LampConfig.Schedule config, SessionId session) {
        this.machine = machine;
        this.context = context;
        this.layout = layout;
        this.config = config;
        this.session = session;
        this.book = new ScheduleBook(layout);
        this.harness = new Harness(machine, layout);
    }

    /// Starts working, once the sandbox is up. Runs asked for before this wait until now.
    void begin() {
        if (worker.isPresent()) return;
        worker = Optional.of(Thread.ofVirtual().name("oillamp-runs").start(this::performQueuedRuns));
        if (config.enabled()) Thread.ofVirtual().name("oillamp-schedule").start(this::watchTheSchedule);
    }

    /// A queued question: its run, and a future completed when the run ends.
    record Asked(LampEvent.Run run, CompletableFuture<LampEvent.RunFinished> done) {}

    /// Queues `prompt` as a new run.
    ///
    /// @param conversation the conversation it continues, or empty for a new one
    /// @param where        where in that conversation the question goes
    /// @return the queued run; an error when the session is ending or the schedule file cannot be written
    Result<Asked> ask(String prompt, Optional<String> conversation, Optional<Harness.Target> where) {
        if (stopping) return Result.err(ProblemCatalogUtil.runRefused("the session is ending"));
        Result<Schedule.Numbered> numbered = book.update(schedule -> Result.ok(schedule.numberRun()), Schedule.Numbered::schedule);
        if (!(numbered instanceof Result.Ok<Schedule.Numbered>(Schedule.Numbered number, var _)))
            return Result.err(numbered.problems());
        CompletableFuture<LampEvent.RunFinished> done = new CompletableFuture<>();
        LampEvent.Run run = new LampEvent.Run(number.run(), Optional.empty(), prompt.strip(), conversation);
        enqueue(new Pending(run, Optional.empty(), where, done));
        return Result.ok(new Asked(run, done));
    }

    /// Stops a run: the one in progress, or one still waiting, which then never starts.
    ///
    /// @param run the run, or empty for the one in progress
    /// @return what was cancelled, or why nothing was
    Result<String> cancel(Optional<String> run) {
        Optional<Pending> working = current;
        if (working.isPresent() && run.map(id -> id.equals(working.get().run().id())).orElse(true)) {
            harness.cancel();
            return Result.ok(working.get().run().id());
        }
        if (run.isEmpty()) return Result.err(ProblemCatalogUtil.runRefused("the agent is not working on anything"));
        for (Pending waiting : queue)
            if (waiting.run().id().equals(run.get()) && queue.remove(waiting)) {
                LampEvent.RunFinished finished = new LampEvent.RunFinished(waiting.run(), RunOutcome.CANCELLED,
                        "cancelled before the agent got to it", Optional.empty(), Duration.ZERO, waiting.run().conversation());
                context.sink().accept(finished);
                waiting.done().complete(finished);
                return Result.ok(run.get());
            }
        return Result.err(ProblemCatalogUtil.runRefused("no run called " + run.get() + " is in progress or waiting"));
    }

    /// The run in progress and those waiting, for `oillamp status`.
    LampEvent.AgentStatus status() {
        Tuple<LampEvent.Run> waiting = Tuple.of(LampEvent.Run.class);
        for (Pending pending : queue) waiting = waiting.add(pending.run());
        return new LampEvent.AgentStatus(current.map(Pending::run), waiting);
    }

    /// Looks at the schedule now, because it changed.
    void scheduleChanged() { look.release(); }

    /// What the agent is doing, for `oillamp status`.
    String describe() {
        String waiting = queue.isEmpty() ? "" : ", " + queue.size() + " more waiting";
        return current.map(pending -> "working on " + pending.run().id() + " ("
                        + pending.run().job().map(job -> "job " + job).orElse("asked") + ")" + waiting)
                .orElse(queue.isEmpty() ? "idle" : "starting" + waiting);
    }

    boolean busy() { return current.isPresent() || !queue.isEmpty(); }

    /// Stops the run in progress, if any, and every waiting one, as the session ends. Waits until
    /// the run in progress is saved, for at most [#WIND_DOWN].
    void stop() {
        stopping = true;
        harness.stop();
        look.release();
        worker.ifPresent(thread -> {
            try {
                thread.join(WIND_DOWN);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        for (Pending left; (left = queue.poll()) != null; )
            left.done().complete(new LampEvent.RunFinished(left.run(), RunOutcome.INTERRUPTED,
                    "the session ended before the agent got to it", Optional.empty(), Duration.ZERO,
                    left.run().conversation()));
        harness.close();
    }

    // ─── the schedule ──────────────────────────────────────────────────────────────────────

    private void watchTheSchedule() {
        while (!stopping) {
            removeFinishedJobsAndQueueDueOnes();
            try {
                look.tryAcquire(TICK.toMillis(), TimeUnit.MILLISECONDS);
                look.drainPermits();
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void removeFinishedJobsAndQueueDueOnes() {
        Instant now = machine.now();
        ZoneId zone = machine.zone();
        Result<Schedule> read = book.read();
        if (!(read instanceof Result.Ok<Schedule>(Schedule schedule, var _))) {
            context.sinkAcceptProblems(read.problems().map(ProblemCatalogUtil::asWarning));
            return;
        }
        for (ScheduledJob finished : schedule.finished(now, zone))
            if (book.update(current -> Result.ok(current.without(finished.id())), s -> s).isOk())
                context.sink().accept(new LampEvent.JobRemoved(finished.describe(zone, now), finished.expiredAt(now)
                        ? "it expired" : "it ran, and will not run again"));
        for (ScheduledJob due : schedule.due(now, zone)) {
            if (stopping || isQueued(due.id())) continue;
            if (due.author() == JobAuthor.AGENT && agentRunsInADay(now) >= config.maxAgentRunsPerDay()) {
                book.update(current -> Result.ok(current.ran(due.id(), now)), s -> s);
                context.sink().accept(new LampEvent.Info("schedule",
                        due.id() + " was due, but was skipped: the agent's jobs ran "
                        + config.maxAgentRunsPerDay() + " times in the last 24 hours, the most "
                        + "`schedule.max_agent_runs_per_day` allows"));
                continue;
            }
            Result<Schedule.Numbered> numbered = book.update(current -> Result.ok(current.numberRun()), Schedule.Numbered::schedule);
            if (numbered instanceof Result.Ok<Schedule.Numbered>(Schedule.Numbered number, var _))
                enqueue(new Pending(new LampEvent.Run(number.run(), Optional.of(due.id()), due.prompt(), Optional.empty()),
                        Optional.of(due), Optional.empty(), new CompletableFuture<>()));
        }
    }

    private boolean isQueued(String job) {
        return current.flatMap(pending -> pending.run().job()).filter(job::equals).isPresent()
            || queue.stream().anyMatch(pending -> pending.run().job().filter(job::equals).isPresent());
    }

    /// How many runs of the agent's own jobs began in the last 24 hours: those the history
    /// records, and those in progress or waiting now.
    private int agentRunsInADay(Instant now) {
        int counted = (int) queue.stream().filter(pending -> pending.job().filter(job -> job.author() == JobAuthor.AGENT).isPresent()).count()
                + (current.flatMap(Pending::job).filter(job -> job.author() == JobAuthor.AGENT).isPresent() ? 1 : 0);
        Result<Tuple<GitObjectUtil.Commit>> commits = new History(layout).commitList();
        if (commits instanceof Result.Ok<Tuple<GitObjectUtil.Commit>>(Tuple<GitObjectUtil.Commit> all, var _))
            for (GitObjectUtil.Commit commit : all) {
                if (commit.snapshot().at().isBefore(now.minus(Duration.ofDays(1)))) break;
                Optional<WakePromptUtil.Past> past = WakePromptUtil.Past.of(commit);
                if (past.isPresent() && past.get().author() == JobAuthor.AGENT) counted++;
            }
        return counted;
    }

    private void enqueue(Pending pending) {
        int ahead = queue.size() + (current.isPresent() ? 1 : 0);
        queue.add(pending);
        if (ahead > 0) context.sink().accept(new LampEvent.RunQueued(pending.run(), ahead - 1));
    }

    // ─── a run ─────────────────────────────────────────────────────────────────────────────

    private void performQueuedRuns() {
        while (!stopping) {
            Pending next;
            try {
                next = queue.poll(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                return;
            }
            if (next == null) continue;
            current = Optional.of(next);
            try {
                next.done().complete(perform(next));
            } catch (RuntimeException bug) {
                context.sink().accept(new LampEvent.Warning(
                        ProblemCatalogUtil.runFailed(next.run().id(), ProblemCatalogUtil.reason(bug))));
                next.done().completeExceptionally(bug);
            } finally {
                current = Optional.empty();
            }
        }
    }

    private LampEvent.RunFinished perform(Pending pending) {
        LampEvent.Run run = pending.run();
        Instant started = machine.now();
        ZoneId zone = machine.zone();
        pending.job().ifPresent(job -> startedJob(job, started, zone));
        context.sink().accept(new LampEvent.RunStarted(run));

        History history = new History(layout);
        Association<String, String> trailers = Association.between(String.class, String.class)
                .put(GitObjectUtil.RUN_TRAILER, run.id());
        Result<History.Saving> before = history.save(SaveKind.BEFORE_RUN, "before " + run.id(), Optional.of(session),
                trailers, false, started);
        Optional<String> base = Optional.empty();
        if (before instanceof Result.Ok<History.Saving>(History.Saving saving, var _)) {
            saving.made().ifPresent(made -> context.sink().accept(new LampEvent.Saved(made, saving.files())));
            base = saving.latest().map(LampEvent.Snapshot::id);
        } else {
            context.sinkAcceptProblems(before.problems().map(ProblemCatalogUtil::asWarning));
        }

        // A job wakes an agent that knows nothing of why, so its prompt carries the agent's notes
        // and recent runs. What a person asks goes as they wrote it: it is what a chat shows as
        // their message, and AGENTS.md already tells the agent to read its notes.
        String prompt = pending.job().isPresent() ? wakePrompt(pending, history, started, zone) : run.prompt();
        // A job's conversation is named after its run; a question someone asked is known by itself.
        // Lamp.Conversation#job reads the job back from this name, so the two change together.
        Optional<String> name = run.job().map(job -> run.id() + " (" + job + ")");
        Harness.Answer answer = harness.run(name, prompt, config.maxRun(), pending.where(),
                progress -> context.sink().accept(new LampEvent.RunProgress(run.id(), progress)));
        if (answer.outcome() == RunOutcome.FAILED && !answer.text().isBlank() && answer.text().startsWith("pi "))
            context.sink().accept(new LampEvent.Warning(ProblemCatalogUtil.runFailed(run.id(), answer.text())));
        Instant ended = machine.now();

        String outcome = answer.outcome().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        String said = answer.text().strip();
        if (said.length() > ANSWER_KEPT) said = said.substring(0, ANSWER_KEPT) + "\n[…]";
        Association<String, String> after = trailers
                .put(GitObjectUtil.AUTHOR_TRAILER, pending.job().map(job -> job.author() == JobAuthor.AGENT ? "agent" : "user").orElse("user"))
                .put(GitObjectUtil.OUTCOME_TRAILER, outcome);
        if (pending.job().isPresent()) after = after.put(GitObjectUtil.JOB_TRAILER, pending.job().get().id());
        if (base.isPresent()) after = after.put(GitObjectUtil.BASE_TRAILER, base.get());
        Optional<String> conversation = answer.conversation().or(run::conversation);
        if (conversation.isPresent()) after = after.put(GitObjectUtil.CONVERSATION_TRAILER, conversation.get());
        String message = run.id() + " (" + run.job().map(job -> "job " + job).orElse("asked") + ") " + outcome
                + (said.isEmpty() ? "" : "\n\n" + said);
        Result<History.Saving> saved = history.save(SaveKind.RUN, message, Optional.of(session), after, true, ended);
        Optional<LampEvent.Snapshot> snapshot = Optional.empty();
        if (saved instanceof Result.Ok<History.Saving>(History.Saving saving, var _)) {
            snapshot = saving.made();
            if (!saving.skipped().isEmpty())
                context.sink().accept(new LampEvent.Warning(
                        ProblemCatalogUtil.filesNotSaved(layout.root(), saving.skipped())));
        } else {
            context.sinkAcceptProblems(saved.problems().map(ProblemCatalogUtil::asWarning));
        }
        LampEvent.RunFinished finished = new LampEvent.RunFinished(run, answer.outcome(), answer.text().strip(),
                snapshot, Duration.between(started, ended), conversation);
        context.sink().accept(finished);
        return finished;
    }

    /// Records that a job's run began. A job that runs once is then done, and comes off the schedule.
    private void startedJob(ScheduledJob job, Instant now, ZoneId zone) {
        boolean once = job.when() instanceof ScheduledJob.When.Once;
        book.update(schedule -> Result.ok(once ? schedule.without(job.id()) : schedule.ran(job.id(), now)), s -> s);
        if (once) context.sink().accept(
                new LampEvent.JobRemoved(job.describe(zone, now), "it runs once, and this is that run"));
    }

    private String wakePrompt(Pending pending, History history, Instant now, ZoneId zone) {
        Optional<String> notes = FilesystemUtil.readString(layout.workspace().resolve("NOTES.md"));
        Tuple<WakePromptUtil.Described> recent = recentRuns(history, WakePromptUtil.RECENT_RUNS);
        WakePromptUtil.Reason reason = pending.job().<WakePromptUtil.Reason>map(WakePromptUtil.Reason.ByJob::new)
                .orElseGet(WakePromptUtil.Reason.Asked::new);
        return WakePromptUtil.render(reason, pending.run().prompt(), notes, config.notesMaxKb() * 1024, recent, now, zone);
    }

    /// The last `count` runs, newest first, with what each changed.
    private static Tuple<WakePromptUtil.Described> recentRuns(History history, int count) {
        Tuple<WakePromptUtil.Described> found = Tuple.of(WakePromptUtil.Described.class);
        Result<Tuple<GitObjectUtil.Commit>> commits = history.commitList();
        if (!(commits instanceof Result.Ok<Tuple<GitObjectUtil.Commit>>(Tuple<GitObjectUtil.Commit> all, var _))) return found;
        for (GitObjectUtil.Commit commit : all) {
            if (found.size() >= count) break;
            Optional<WakePromptUtil.Past> past = WakePromptUtil.Past.of(commit);
            if (past.isEmpty()) continue;
            found = found.add(describe(history, all, past.get(), WakePromptUtil.CHANGES_SHOWN));
        }
        return found;
    }

    private static WakePromptUtil.Described describe(History history, Tuple<GitObjectUtil.Commit> all, WakePromptUtil.Past past, int most) {
        Optional<String> baseTree = past.base().flatMap(id ->
                all.stream().filter(commit -> commit.id().equals(id)).findFirst().map(GitObjectUtil.Commit::tree));
        Result<History.Changes> changes = history.changes(baseTree, past.tree(), most);
        return changes instanceof Result.Ok<History.Changes>(History.Changes found, var _)
                ? new WakePromptUtil.Described(past, found.shown(), found.more())
                : new WakePromptUtil.Described(past, Tuple.of(History.Change.class), false);
    }

    // ─── the agent's requests ──────────────────────────────────────────────────────────────

    /// Answers the agent, which asks through the schedule socket in the sandbox. Every request is
    /// judged by the same rules as the user's, plus the limits on the agent's own jobs; anything
    /// it sends is data, never a command to run.
    ///
    /// The answers are written for the agent to read: a tool shows the `text` of a reply, or its
    /// `error`, as they are.
    Control.Reply answerAgent(Control.Request request) {
        Instant now = machine.now();
        ZoneId zone = machine.zone();
        return switch (request.op()) {
            case "list" -> book.read().map(schedule -> Control.Reply.ok().with("text", listing(schedule, now, zone)))
                    .orElseGet(problems -> Control.Reply.failed(problems.first().whatHappened()));
            case "add" -> {
                Schedule.Request asked = new Schedule.Request(present(request, "cron"), present(request, "at"),
                        request.arguments().get("prompt").orElse(""), present(request, "expires"));
                Result<Schedule.Changed> added = book.update(schedule -> schedule.add(asked, JobAuthor.AGENT, now, zone, config),
                        Schedule.Changed::schedule);
                if (!(added instanceof Result.Ok<Schedule.Changed>(Schedule.Changed change, var _)))
                    yield Control.Reply.failed(added.problems().first().whatHappened());
                context.sink().accept(new LampEvent.JobAdded(change.job().describe(zone, now)));
                look.release();
                yield Control.Reply.ok().with("text", "Added " + change.job().id() + ": " + describe(change.job(), zone)
                        + (config.enabled() ? "" : " The schedule is switched off by the user, so it will not run until they switch it on."));
            }
            case "remove" -> {
                Result<Schedule.Changed> removed = book.update(schedule -> schedule.remove(
                        request.arguments().get("id").orElse(""), JobAuthor.AGENT, layout.root()), Schedule.Changed::schedule);
                if (!(removed instanceof Result.Ok<Schedule.Changed>(Schedule.Changed change, var _)))
                    yield Control.Reply.failed(removed.problems().first().whatHappened());
                context.sink().accept(
                        new LampEvent.JobRemoved(change.job().describe(zone, now), "removed by the agent"));
                yield Control.Reply.ok().with("text", "Removed " + change.job().id() + ".");
            }
            case "history" -> Control.Reply.ok().with("text", history(request.arguments().get("run").orElse("").strip(), zone));
            default -> Control.Reply.failed("unknown request: " + request.op());
        };
    }

    private static Optional<String> present(Control.Request request, String key) {
        return request.arguments().get(key).filter(value -> !value.isBlank());
    }

    private String listing(Schedule schedule, Instant now, ZoneId zone) {
        StringBuilder out = new StringBuilder();
        out.append(!config.enabled() ? "The schedule is switched off by the user: no job runs.\n"
                 : schedule.paused() ? "The user has paused the schedule: no job runs until they resume it.\n" : "");
        out.append("Times are on the clock of ").append(zone.getId()).append("; it is now ").append(TimeNotationUtil.show(now, zone))
           .append(". Your jobs: ").append(schedule.byAgent().size()).append(" of at most ").append(config.maxAgentJobs())
           .append(".\n");
        if (schedule.jobs().isEmpty()) return out.append("There are no jobs.").toString();
        for (ScheduledJob job : schedule.jobs())
            out.append("\n- ").append(job.id()).append(job.author() == JobAuthor.AGENT ? " (yours)" : " (the user's; you cannot change it)")
               .append(": ").append(describe(job, zone)).append("\n  Task: ").append(job.prompt().strip().replace("\n", "\n  ")).append('\n');
        return out.toString();
    }

    private static String describe(ScheduledJob job, ZoneId zone) {
        return "runs " + (job.when() instanceof ScheduledJob.When.Once ? "" : "on \"") + job.describeWhen(zone)
                + (job.when() instanceof ScheduledJob.When.Once ? "" : "\"")
                + (!job.enabled() ? ", switched off by the user" : job.next(zone).map(next -> ", next at " + TimeNotationUtil.show(next, zone)).orElse(""))
                + job.expires().map(end -> ", until " + TimeNotationUtil.show(end, zone)).orElse("") + ".";
    }

    /// One run in full, or a list of the recent ones when `run` is empty.
    private String history(String run, ZoneId zone) {
        History history = new History(layout);
        Result<Tuple<GitObjectUtil.Commit>> commits = history.commitList();
        if (!(commits instanceof Result.Ok<Tuple<GitObjectUtil.Commit>>(Tuple<GitObjectUtil.Commit> all, var _)))
            return "The lamp's history cannot be read: " + commits.problems().first().whatHappened();
        Tuple<WakePromptUtil.Past> runs = Tuple.of(WakePromptUtil.Past.class);
        for (GitObjectUtil.Commit commit : all) runs = WakePromptUtil.Past.of(commit).map(runs::add).orElse(runs);
        if (run.isEmpty()) {
            if (runs.isEmpty()) return "There have been no runs yet.";
            StringBuilder out = new StringBuilder("The last runs, newest first:\n");
            for (WakePromptUtil.Past past : runs.stream().limit(20).toList())
                out.append("\n- ").append(past.run()).append(", ").append(TimeNotationUtil.show(past.at(), zone)).append(", ")
                   .append(past.job().orElse("asked by the user")).append(": ").append(past.outcome());
            return out.append("\n\nGive a run's name for what it changed and what you said at its end.").toString();
        }
        Optional<WakePromptUtil.Past> found = runs.stream().filter(past -> past.run().equals(run)).findFirst();
        if (found.isEmpty()) return "There is no run called '" + run + "'. Leave the run out to list the recent ones.";
        WakePromptUtil.Past past = found.get();
        WakePromptUtil.Described described = describe(history, all, past, 100);
        StringBuilder out = new StringBuilder();
        out.append(past.run()).append(", ").append(TimeNotationUtil.show(past.at(), zone)).append(", ")
           .append(past.job().map(job -> "for " + job).orElse("asked by the user")).append(": ").append(past.outcome()).append(".\n");
        out.append(described.changes().isEmpty() ? "\nIt changed no files.\n" : "\nIt changed:\n");
        for (History.Change change : described.changes())
            out.append("- ").append(change.path()).append(" (").append(change.kind()).append(")\n");
        if (described.more()) out.append("- and more\n");
        if (!past.answer().isBlank()) out.append("\nWhat you said at its end:\n\n").append(past.answer()).append('\n');
        return out.toString();
    }
}
