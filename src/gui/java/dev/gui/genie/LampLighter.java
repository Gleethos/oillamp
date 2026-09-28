package dev.gui.genie;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import dev.gui.model.Settings;
import dev.lamp.Lamp;
import dev.lamp.LampEvent;

/// Lights genies' lamps with oillamp, the way any application does: through [Lamp].
///
/// The model service and key from the settings go to the lamp's engine, which keeps the key on
/// the host. The sandbox gets only its fixed relay address and a placeholder.
public final class LampLighter implements Lighter {

    /// The first start of any lamp builds the sandbox image, which takes several minutes, and
    /// longer on a slow network.
    static final Duration WAKING_TIME = Duration.ofMinutes(45);

    @Override
    public Lit light(Path directory, Settings settings, String key, Consumer<String> progress)
            throws IOException, InterruptedException {
        AtomicReference<String> problem = new AtomicReference<>("");
        Lamp lamp = Lamp.at(directory)
                .modelService(URI.create(settings.service().strip()))
                .modelKey(key)
                .onEvent(event -> {
                    if (event instanceof LampEvent.Failure failure)
                        problem.set(failure.problem().title() + ": " + failure.problem().whatHappened());
                    describe(event).ifPresent(progress);
                })
                .start();
        if (!lamp.awaitRunning(WAKING_TIME)) {
            lamp.close();
            String why = Optional.ofNullable(problem.get()).orElse("");
            throw new IOException(why.isEmpty()
                    ? "The genie's lamp did not start" + lamp.exitStatus().map(status -> " (" + status + ")").orElse(" in time")
                    : why);
        }
        return new Lit() {
            @Override public Process exec(String... command) throws IOException { return lamp.exec(command); }
            @Override public Path desktop() { return lamp.desktop(); }
            @Override public void close() { lamp.close(); }
        };
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
