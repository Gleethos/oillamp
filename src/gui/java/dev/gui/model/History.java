package dev.gui.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import sprouts.HasId;
import sprouts.Tuple;

/// A genie's history as its page shows it: the moments oillamp saved the genie's home at, newest
/// first. The genie can go back to any of them.
///
/// Read from the genie's lamp, awake or asleep. Going back needs the genie asleep, and saves how
/// it is first, so going back can itself be undone.
///
/// @param read    whether it was read from the lamp yet; before that, the page says it is looking
/// @param moments newest first
/// @param picked  the id of the moment whose card is open, with its buttons; empty for none
/// @param earlier whether every moment is shown, not only the newest [#SHOWN]
/// @param busy    what is on its way to the lamp, such as "Saving…"; empty while nothing is
/// @param note    what the last save came to when it saved nothing; empty otherwise
/// @param problem why the last save or going back failed, in the lamp's words; empty when it did not
public record History(boolean read, Tuple<Moment> moments, String picked, boolean earlier, String busy,
                      String note, String problem) {

    /// One saved state of the genie's home.
    ///
    /// @param id           oillamp's id for it, 40 hexadecimal characters
    /// @param message      for [Kind#SAVED], what the user wrote, or nothing; for [Kind#RAN], a
    ///                     line naming the run, then a blank line and the genie's last words; for
    ///                     [Kind#WENT_BACK], `back to ` and the first eight characters of the id of
    ///                     the moment it went back to
    /// @param run          the run, such as `run-12`, for [Kind#BEFORE_RUN] and [Kind#RAN]; empty otherwise
    /// @param job          for [Kind#RAN] of a job's run, the job, such as `job-3`; empty otherwise
    /// @param outcome      how the run ended, for [Kind#RAN]
    /// @param conversation for [Kind#RAN], pi's id for the run's conversation; empty when it had none
    public record Moment(String id, Instant at, Kind kind, String message, String run, String job,
                         Optional<Schedule.Outcome> outcome, String conversation) implements HasId<String> {

        /// For [Kind#RAN], the genie's last words in the run; empty otherwise, or when it said nothing.
        public String said() {
            int body = message.indexOf("\n\n");
            return kind != Kind.RAN || body < 0 ? "" : message.substring(body + 2).strip();
        }

        /// More about it, on one line; empty when there is nothing more to say.
        public String detail() {
            String said = Schedule.firstLine(said(), 160);
            return switch (kind) {
                case WOKE -> "Saved as its sandbox started";
                case SLEPT -> "Saved after its sandbox stopped";
                case SAVED -> message.isBlank() ? "" : "Saved by hand";
                case BEFORE_RUN -> "Saved as it began on a question or a job";
                case RAN -> switch (outcome.orElse(Schedule.Outcome.FAILED)) {
                    case FINISHED -> said;
                    case STOPPED -> "Stopped. " + said;
                    case FAILED -> "Failed. " + said;
                    case TIMED_OUT -> "Ran out of time. " + said;
                };
                case BEFORE_GOING_BACK -> "Kept so that going back can be undone";
                case WENT_BACK -> "";
            };
        }
    }

    public enum Kind {
        /// oillamp saved as the genie's sandbox started.
        WOKE,
        /// oillamp saved after the genie's sandbox stopped.
        SLEPT,
        /// The user saved, from Genies or with `oillamp save`.
        SAVED,
        /// oillamp saved as a run began, because something had changed since the last moment.
        BEFORE_RUN,
        /// oillamp saved as a run ended: an answer to the user, or a job's run.
        RAN,
        /// oillamp saved just before going back, so that going back can be undone.
        BEFORE_GOING_BACK,
        /// The genie went back to an earlier moment.
        WENT_BACK
    }

    /// The moments of one day, newest first.
    public record Day(LocalDate date, Tuple<Moment> moments) {}

    /// How many moments are shown while [#earlier] is off.
    public static final int SHOWN = 40;

    public static final History UNREAD = new History(false, Tuple.of(Moment.class), "", false, "", "", "");

    public History withPicked(String picked)    { return new History(read, moments, picked, earlier, busy, note, problem); }
    public History withEarlier(boolean earlier) { return new History(read, moments, picked, earlier, busy, note, problem); }
    public History withBusy(String busy)        { return new History(read, moments, picked, earlier, busy, note, problem); }
    public History withNote(String note)        { return new History(read, moments, picked, earlier, busy, note, problem); }
    public History withProblem(String problem)  { return new History(read, moments, picked, earlier, busy, note, problem); }

    /// What the lamp holds now. The picked moment stays picked while it is still there.
    public History readAs(Tuple<Moment> moments) {
        String stillPicked = moments.any(it -> it.id().equals(picked)) ? picked : "";
        return new History(true, moments, stillPicked, earlier, busy, note, problem);
    }

    /// Picks `id`, or unpicks it when it is picked already.
    public History pick(String id) {
        return withPicked(picked.equals(id) ? "" : id);
    }

    public Optional<Moment> find(String id) {
        for (Moment moment : moments) if (moment.id().equals(id)) return Optional.of(moment);
        return Optional.empty();
    }

    /// How many older moments are left out while [#earlier] is off.
    public int hidden() {
        return earlier ? 0 : Math.max(0, moments.size() - SHOWN);
    }

    /// The moments shown, by the day they were saved on `zone`'s clock, the newest day first.
    public Tuple<Day> days(ZoneId zone) {
        Tuple<Moment> shown = moments.slice(0, moments.size() - hidden());
        Tuple<Day> days = Tuple.of(Day.class);
        for (Moment moment : shown) {
            LocalDate date = LocalDate.ofInstant(moment.at(), zone);
            if (!days.isEmpty() && days.last().date().equals(date))
                days = days.setAt(days.size() - 1, new Day(date, days.last().moments().add(moment)));
            else
                days = days.add(new Day(date, Tuple.of(Moment.class, moment)));
        }
        return days;
    }

    /// The moment that was the newest when the genie last went back, while going back is the last
    /// thing that changed it: going back there undoes it. That is oillamp's save before going
    /// back, or, when nothing had changed since the moment before, that moment. Waking, sleeping
    /// and what changed before a run do not count; an answer or a save does, and then there is
    /// nothing to undo.
    public Optional<Moment> undo() {
        for (int i = 0; i < moments.size(); i++) {
            switch (moments.get(i).kind()) {
                case WOKE, SLEPT, BEFORE_RUN -> { }
                case WENT_BACK -> { return i + 1 < moments.size() ? Optional.of(moments.get(i + 1)) : Optional.empty(); }
                default -> { return Optional.empty(); }
            }
        }
        return Optional.empty();
    }

    /// What `moment` is about, on one line, such as "Answered: Plot the sales figures" or "Went
    /// back to how it was yesterday at 14:05".
    ///
    /// @param genie whose conversations and jobs give runs their titles
    /// @param now   on the clock of the genie's schedule, against which days are named
    public String title(Moment moment, Genie genie, LocalDateTime now) {
        return switch (moment.kind()) {
            case WOKE -> "Woke up";
            case SLEPT -> "Went to sleep";
            case SAVED -> moment.message().isBlank() ? "Saved by hand" : Schedule.firstLine(moment.message(), 90);
            case BEFORE_RUN -> "Before it went to work";
            case RAN -> !moment.job().isEmpty()
                    ? genie.schedule().job(moment.job()).map(job -> "Did the job: " + job.title()).orElse("Did a scheduled job")
                    : genie.conversations().find(moment.conversation()).map(it -> "Answered: " + it.title()).orElse("Answered a question");
            case BEFORE_GOING_BACK -> "Before going back";
            case WENT_BACK -> wentBackTo(moment).map(it -> "Went back to how it was " + when(it.at(), genie.schedule().zone(), now))
                                                .orElse("Went back to an earlier moment");
        };
    }

    /// The moment a [Kind#WENT_BACK] went back to, while it is in the history.
    public Optional<Moment> wentBackTo(Moment wentBack) {
        String said = wentBack.message().strip();
        if (wentBack.kind() != Kind.WENT_BACK || !said.startsWith("back to ")) return Optional.empty();
        String start = said.substring("back to ".length()).split("\\s", 2)[0];
        if (start.length() < 4) return Optional.empty();
        for (Moment moment : moments) if (moment.id().startsWith(start)) return Optional.of(moment);
        return Optional.empty();
    }

    /// "today at 14:05", "yesterday at 09:30", "on Thursday 1 October at 18:00".
    public static String when(Instant at, ZoneId zone, LocalDateTime now) {
        LocalDateTime then = LocalDateTime.ofInstant(at, zone);
        return DateWordingUtil.dayInSentence(then.toLocalDate(), now.toLocalDate()) + " at " + Recurrence.clock(then.toLocalTime());
    }
}
