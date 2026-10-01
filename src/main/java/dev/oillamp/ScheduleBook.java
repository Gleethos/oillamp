package dev.oillamp;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;

/// Reads and changes a lamp's schedule file, `.oillamp/schedule.json`.
///
/// Three kinds of caller change it: `oillamp schedule` from a terminal, the session as jobs run,
/// and the agent through the session. Each change reads the file, applies a [Schedule] method and
/// writes the result, all while holding `.oillamp/schedule.lock`, so that no change is lost to
/// another made at the same moment. A lamp that has no file has an empty schedule.
final class ScheduleBook {

    /// How long a change waits for another one to finish. Changes take milliseconds.
    private static final Duration PATIENCE = Duration.ofSeconds(10);

    private final LampLayout layout;

    ScheduleBook(LampLayout layout) {
        this.layout = layout;
    }

    /// The schedule as it is now.
    Result<Schedule> read() {
        Optional<String> text = FilesystemUtil.readString(layout.scheduleFile());
        return text.isEmpty() ? Result.ok(Schedule.empty()) : Schedule.parse(text.get(), layout.scheduleFile());
    }

    /// Changes the schedule, and stores what `change` returns, unless it refuses.
    ///
    /// @param change decides the change, and what to report about it
    /// @param stored the schedule to store, taken from what `change` returned
    <T> Result<T> update(Function<Schedule, Result<T>> change, Function<T, Schedule> stored) {
        Optional<LampLock> lock;
        try {
            lock = LampLock.acquireWithin(layout.scheduleLock(), PATIENCE);
        } catch (IOException e) {
            return Result.err(Problems.scheduleDamaged(layout.scheduleFile(), Problems.reason(e)));
        }
        if (lock.isEmpty())
            return Result.err(Problems.scheduleDamaged(layout.scheduleFile(),
                    "another oillamp process has held the schedule for over " + PATIENCE.toSeconds() + " seconds"));
        try {
            Result<Schedule> current = read();
            if (!(current instanceof Result.Ok<Schedule>(Schedule schedule, var _))) return Result.err(current.problems());
            Result<T> changed = change.apply(schedule);
            if (!(changed instanceof Result.Ok<T>(T value, var _))) return changed;
            FilesystemUtil.writeFile(layout.scheduleFile(), stored.apply(value).render(), PosixMode.PRIVATE_FILE);
            return changed;
        } catch (IOException e) {
            return Result.err(Problems.scheduleDamaged(layout.scheduleFile(), Problems.reason(e)));
        } finally {
            try {
                lock.get().close();
            } catch (IOException ignored) {
                // The kernel releases it when this process ends.
            }
        }
    }
}
