package dev.gui.view;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/// What the window asks the app to do, beyond changing its state: everything that starts,
/// stops or moves something outside the window. The app, [dev.gui.Genies], does it.
public interface Actions {

    void newGenie();

    void wake(UUID genie);

    void sleep(UUID genie);

    /// Sends the selected genie's draft.
    void send();

    /// Stops what the genie is doing.
    void stop(UUID genie);

    /// Deletes the genie and its lamp, for good. The window has asked the user already.
    void delete(UUID genie);

    /// Puts a file of the user's into the genie's inbox.
    void give(UUID genie, Path file);

    /// Saves a file from the genie's outbox where the user chose.
    void save(UUID genie, String name, Path target);

    /// Asks the model server on this computer, named in the settings, which models it has.
    void lookUpModels();

    /// The settings page closes: keep the settings.
    void settingsDone();

    /// The socket of the genie's desktop, while it is awake.
    Optional<Path> desktopOf(UUID genie);
}
