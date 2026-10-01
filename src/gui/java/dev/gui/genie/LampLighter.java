package dev.gui.genie;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import dev.gui.desktop.Desktop;
import dev.gui.model.Settings;
import dev.lamp.Lamp;
import dev.lamp.LampEvent;

/// Lights genies' lamps with oillamp, the way any application does: through [Lamp].
///
/// The model service and key from the settings go to the lamp's engine, which keeps the key on
/// the host. The sandbox gets only its fixed relay address and a placeholder.
///
/// Every lamp is lit with its schedule on, so the jobs on it wake the genie while it is awake.
public final class LampLighter implements Lighter {

    private final Optional<Lamp.Launcher> launcher;

    /// Starts each lamp's engine in a process of its own, from this application's classpath.
    public LampLighter() { this(Optional.empty()); }

    /// Starts each lamp's engine with `launcher`, as the scenarios do, to run it in their own JVM.
    public LampLighter(Lamp.Launcher launcher) { this(Optional.of(launcher)); }

    private LampLighter(Optional<Lamp.Launcher> launcher) {
        this.launcher = launcher;
    }

    /// The first start of any lamp builds the sandbox image, which takes several minutes, and
    /// longer on a slow network.
    static final Duration WAKING_TIME = Duration.ofMinutes(45);

    @Override
    public Lit light(Path directory, Settings settings, String key, Consumer<String> progress,
                     Consumer<LampEvent> events) throws IOException, InterruptedException {
        AtomicReference<String> problem = new AtomicReference<>("");
        Lamp lamp = unlit(directory)
                .enableScheduling()
                .modelService(URI.create(settings.service().strip()))
                .modelKey(key)
                .onEvent(event -> {
                    if (event instanceof LampEvent.Failure failure)
                        problem.set(failure.problem().title() + ": " + failure.problem().whatHappened());
                    describe(event).ifPresent(progress);
                    events.accept(event);
                })
                .start();
        if (!lamp.awaitRunning(WAKING_TIME)) {
            lamp.close();
            String why = Optional.ofNullable(problem.get()).orElse("");
            throw new IOException(why.isEmpty()
                    ? "The genie's lamp did not start" + lamp.exitStatus().map(status -> " (" + status + ")").orElse(" in time")
                    : why);
        }
        return lit(lamp);
    }

    /// How long joining may take. The session answers at once; this allows for a busy machine.
    static final Duration JOINING_TIME = Duration.ofSeconds(30);

    @Override
    public Optional<Lit> join(Path directory, Consumer<LampEvent> events) throws IOException, InterruptedException {
        Lamp.Starting starting = unlit(directory);
        if (!starting.isRunning()) return Optional.empty();
        Lamp lamp = starting.onEvent(events).join();
        if (!lamp.awaitRunning(JOINING_TIME)) {
            lamp.close();
            return Optional.empty();
        }
        return Optional.of(lit(lamp));
    }

    /// A lamp this app started or joined. Putting out one it joined ends its session, as for one
    /// it started.
    private static Lit lit(Lamp lamp) {
        return new Lit() {
            @Override public Process exec(String... command) throws IOException { return lamp.exec(command); }
            @Override public Desktop desktop() {
                LampEvent.SessionOpened session = lamp.session();
                return new Desktop(session.desktop(), session.desktopWidth(), session.desktopHeight());
            }
            @Override public LampEvent.Run send(Lamp.Question question)
                    throws IOException, InterruptedException, Lamp.Failed { return lamp.send(question); }
            @Override public void cancel(String run)
                    throws IOException, InterruptedException, Lamp.Failed { lamp.cancel(run); }
            @Override public void close() {
                if (lamp.holds()) {
                    lamp.close();
                    return;
                }
                try {
                    lamp.stop();
                } catch (IOException | Lamp.Failed alreadyOut) {
                    // Nothing is running that could be stopped; following ends below all the same.
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    lamp.close();
                }
            }
            @Override public void leaveRunning() { lamp.leaveRunning(); }
        };
    }

    @Override
    public Lamp.Starting unlit(Path directory) {
        Lamp.Starting lamp = Lamp.at(directory);
        return launcher.isPresent() ? lamp.launchedBy(launcher.get()) : lamp;
    }

    /// What the lamp is doing, in the few words the genie's status has room for.
    static Optional<String> describe(LampEvent event) {
        return switch (event) {
            case LampEvent.PhaseStarted started -> Optional.of(switch (started.phase()) {
                case HOST -> "checking this computer";
                case LAMP -> "preparing the lamp";
                case IMAGE -> "making the sandbox; the first time takes several minutes";
                default -> "starting the sandbox";
            });
            case LampEvent.StepStarted step -> Optional.of(step.step().describe());
            case LampEvent.Ok ok -> Optional.of(ok.text());
            case LampEvent.Warning warning -> Optional.of(warning.problem().title());
            default -> Optional.empty();
        };
    }
}
