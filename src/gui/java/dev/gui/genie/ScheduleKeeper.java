package dev.gui.genie;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import dev.gui.model.Genie;
import dev.gui.model.JobDraft;
import dev.gui.model.Schedule;
import dev.lamp.Lamp;
import dev.lamp.LampEvent;

/// Reads and changes one genie's schedule, through the Lamp API, whether the genie is awake or
/// asleep: the schedule lives in the lamp, not in its sandbox.
///
/// Each call starts an engine process that ends by itself, which takes a moment, so the work is
/// done one piece after the other on a thread of its own, and the calls return at once. What
/// comes of it is a change to the genie, handed to `changes`, as [GenieRunner] does.
public final class ScheduleKeeper {

    private final Lamp.Starting lamp;
    private final Consumer<UnaryOperator<Genie>> changes;
    private final ExecutorService work = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("schedule").factory());
    /// Whether a reading waits its turn already; one reading then covers every change before it.
    private final AtomicBoolean readingQueued = new AtomicBoolean();
    /// Whether the schedule was ever asked for. Until then, nothing shows it, and news from the
    /// lamp does not make the keeper read it.
    private volatile boolean watched;

    /// @param lamp the genie's lamp, unlit: [Lighter#unlit]
    public ScheduleKeeper(Lamp.Starting lamp, Consumer<UnaryOperator<Genie>> changes) {
        this.lamp = lamp;
        this.changes = changes;
    }

    /// Reads the jobs, and the runs they had over the last week, from the lamp.
    public void read() {
        watched = true;
        if (!readingQueued.compareAndSet(false, true)) return;
        work.execute(() -> {
            readingQueued.set(false);
            try {
                LampEvent.Schedule schedule = lamp.schedule();
                var runs = LampTalk.runs(lamp.history());
                var jobs = LampTalk.jobs(schedule);
                ZoneId zone = ZoneId.of(schedule.zone());
                change(it -> it.readAs(schedule.paused(), zone, jobs, runs));
            } catch (IOException | Lamp.Failed failed) {
                change(it -> it.withProblem("The schedule could not be read: " + reason(failed)));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /// Adds the job written in `draft`, or changes the job it replaces: the new one is added
    /// first, so a job that cannot be added leaves the old one as it was. A job that was switched
    /// off stays off. The editor closes once it is done, and says why when it cannot be.
    ///
    /// @param now  when the user saved, on the schedule's clock
    public void save(JobDraft draft, LocalDateTime now, ZoneId zone) {
        if (draft.problem(now).isPresent()) return;
        change(it -> it.withBusy(true).withProblem(""));
        work.execute(() -> {
            try {
                String prompt = draft.prompt().strip();
                LampEvent.Job added;
                if (draft.repeats()) {
                    String cron = draft.recurrence().orElseThrow().cron();
                    Optional<Instant> until = draft.ends().map(end -> end.atZone(zone).toInstant());
                    added = until.isPresent() ? lamp.repeat(cron, prompt, until.get()) : lamp.repeat(cron, prompt);
                } else {
                    added = lamp.once(draft.once().orElseThrow().atZone(zone).toInstant(), prompt);
                }
                if (draft.replaces().isPresent()) {
                    boolean wasOff = lamp.schedule().jobs().stream()
                            .anyMatch(job -> job.id().equals(draft.replaces().get()) && !job.enabled());
                    if (wasOff) lamp.disable(added.id());
                    lamp.unschedule(draft.replaces().get());
                }
                String id = added.id();
                change(it -> it.closeEditor().withPicked(id));
            } catch (IOException | Lamp.Failed failed) {
                change(it -> it.withBusy(false).withProblem("oillamp could not add it: " + reason(failed)));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        read();
    }

    /// Takes job `id` off the schedule, whoever added it.
    public void remove(String id) {
        act(() -> lamp.unschedule(id), "The job could not be removed");
    }

    /// Switches job `id` on or off.
    public void switchJob(String id, boolean on) {
        act(() -> {
            if (on) lamp.enable(id);
            else lamp.disable(id);
        }, "The job could not be switched " + (on ? "on" : "off"));
    }

    /// Pauses the whole schedule, or lets it run again.
    public void pause(boolean paused) {
        act(() -> {
            if (paused) lamp.pause();
            else lamp.resume();
        }, "The schedule could not be " + (paused ? "paused" : "resumed"));
    }

    /// News from the genie's lamp while it is awake: a job's run begins or ends, or the genie
    /// changed its own jobs.
    void heard(LampEvent event, Instant now) {
        switch (event) {
            case LampEvent.RunStarted started when started.run().job().isPresent() ->
                    change(it -> it.withRunning(Optional.of(new Schedule.Running(started.run().id(), started.run().job().get(), now))));
            case LampEvent.RunFinished finished when finished.run().job().isPresent() -> {
                change(it -> it.withRunning(Optional.empty()));
                if (watched) read();
            }
            case LampEvent.JobAdded ignored -> { if (watched) read(); }
            case LampEvent.JobRemoved ignored -> { if (watched) read(); }
            case LampEvent.ScheduleChanged ignored -> { if (watched) read(); }
            default -> { }
        }
    }

    /// The genie's lamp went out: no run of a job is going any more.
    void wentOut() {
        change(it -> it.withRunning(Optional.empty()));
    }

    private interface Change { void run() throws IOException, InterruptedException, Lamp.Failed; }

    private void act(Change action, String failure) {
        change(it -> it.withProblem(""));
        work.execute(() -> {
            try {
                action.run();
            } catch (IOException | Lamp.Failed failed) {
                change(it -> it.withProblem(failure + ": " + reason(failed)));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        read();
    }

    private void change(UnaryOperator<Schedule> change) {
        changes.accept(genie -> genie.withSchedule(change.apply(genie.schedule())));
    }

    private static String reason(Exception failed) {
        if (failed instanceof Lamp.Failed refused) return refused.problem().whatHappened();
        return Optional.ofNullable(failed.getMessage()).filter(message -> !message.isBlank()).orElse(failed.toString());
    }
}
