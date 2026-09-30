package dev.oillamp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

import dev.lamp.LampEvent;

/// Everyone following a session through `oillamp follow`, and what one who joins late needs to
/// catch up.
///
/// Every event the session reports passes through [#accept], which hands it to each follower and
/// remembers the little that describes the present:
///
/// - [LampEvent.SessionOpened], how to reach the sandbox;
/// - the last [LampEvent.SessionStateChanged];
/// - the run in progress, from its [LampEvent.RunStarted], with what the agent wrote so far. The
///   pieces of text it wrote one after another are joined, so a long answer is a few events;
/// - the runs waiting their turn.
///
/// The catch-up ends with a [LampEvent.AgentStatus], which the session reports at no other time,
/// so a follower knows where the present ends and the news begins.
///
/// A follower that joins gets those first, then every event as it happens, with nothing missed
/// and nothing twice: both are done under one lock. Its feed ends once the session has ended.
final class Followers implements Consumer<LampEvent> {

    /// How many events a follower may fall behind before it is dropped. One that reads nothing
    /// would otherwise hold every event of a session that runs for days.
    private static final int MOST_BEHIND = 50_000;

    /// Put on a follower's queue to end its feed.
    private static final LampEvent END = new LampEvent.Info("session", "the session has ended");

    private final Object lock = new Object();
    private Optional<LampEvent.SessionOpened> opened = Optional.empty();
    private Optional<LampEvent.SessionStateChanged> state = Optional.empty();
    private final List<LampEvent> running = new ArrayList<>();
    private final Map<String, LampEvent.Run> waiting = new LinkedHashMap<>();
    private final List<BlockingQueue<LampEvent>> following = new ArrayList<>();
    private boolean ended;

    @Override public void accept(LampEvent event) {
        synchronized (lock) {
            remember(event);
            following.removeIf(queue -> {
                if (queue.size() < MOST_BEHIND) return !queue.add(event);
                // Dropped: its feed ends, and the follower may join again.
                queue.clear();
                queue.add(END);
                return true;
            });
        }
    }

    /// A new follower. The events come from [Feed#next], starting with the catch-up.
    Feed follow() {
        synchronized (lock) {
            BlockingQueue<LampEvent> queue = new LinkedBlockingQueue<>();
            opened.ifPresent(queue::add);
            state.ifPresent(queue::add);
            queue.addAll(running);
            int ahead = 0;
            for (LampEvent.Run run : waiting.values()) queue.add(new LampEvent.RunQueued(run, ahead++));
            Optional<LampEvent.Run> current = running.isEmpty() ? Optional.empty()
                    : Optional.of(((LampEvent.RunStarted) running.getFirst()).run());
            queue.add(new LampEvent.AgentStatus(current, sprouts.Tuple.of(LampEvent.Run.class, List.copyOf(waiting.values()))));
            if (ended) queue.add(END);
            else following.add(queue);
            return new Feed(this, queue);
        }
    }

    /// Ends every feed, once the session's last event has been passed on.
    void end() {
        synchronized (lock) {
            ended = true;
            following.forEach(queue -> queue.add(END));
            following.clear();
        }
    }

    private void remember(LampEvent event) {
        switch (event) {
            case LampEvent.SessionOpened session -> opened = Optional.of(session);
            case LampEvent.SessionStateChanged changed -> state = Optional.of(changed);
            case LampEvent.RunQueued queued -> waiting.put(queued.run().id(), queued.run());
            case LampEvent.RunStarted started -> {
                waiting.remove(started.run().id());
                running.clear();
                running.add(started);
            }
            case LampEvent.RunProgress progress when !running.isEmpty() -> joinOrAdd(progress);
            case LampEvent.RunFinished finished -> {
                waiting.remove(finished.run().id());
                if (!running.isEmpty() && running.getFirst() instanceof LampEvent.RunStarted started
                        && started.run().id().equals(finished.run().id()))
                    running.clear();
            }
            default -> { }
        }
    }

    /// Adds `progress` to the run in progress, joined to the piece before when both are text of
    /// the same kind.
    private void joinOrAdd(LampEvent.RunProgress progress) {
        if (running.getLast() instanceof LampEvent.RunProgress last && last.run().equals(progress.run())) {
            if (last.progress() instanceof LampEvent.Progress.Said before
                    && progress.progress() instanceof LampEvent.Progress.Said now) {
                running.set(running.size() - 1, new LampEvent.RunProgress(last.run(),
                        new LampEvent.Progress.Said(before.text() + now.text())));
                return;
            }
            if (last.progress() instanceof LampEvent.Progress.Thought before
                    && progress.progress() instanceof LampEvent.Progress.Thought now) {
                running.set(running.size() - 1, new LampEvent.RunProgress(last.run(),
                        new LampEvent.Progress.Thought(before.text() + now.text())));
                return;
            }
        }
        running.add(progress);
    }

    private void leave(BlockingQueue<LampEvent> queue) {
        synchronized (lock) {
            following.remove(queue);
        }
    }

    /// One follower's events.
    static final class Feed implements AutoCloseable {

        private final Followers followers;
        private final BlockingQueue<LampEvent> queue;

        private Feed(Followers followers, BlockingQueue<LampEvent> queue) {
            this.followers = followers;
            this.queue = queue;
        }

        /// The follower has hung up; nothing more is kept for it.
        @Override public void close() { followers.leave(queue); }

        /// The next event, waiting for it if need be; empty once the session has ended.
        Optional<LampEvent> next() throws InterruptedException {
            LampEvent event = queue.take();
            return event == END ? Optional.empty() : Optional.of(event);
        }
    }
}
