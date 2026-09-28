package dev.gui.genie;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.gui.model.Genie;
import dev.gui.model.Settings;

import sprouts.Tuple;

/// Where Genies keeps what outlives it, in one directory:
///
/// - `genies.json`: each genie's id and name, in order;
/// - `settings.json`: the model settings, readable by this user only, because it can hold a key;
/// - `lamps/<id>/`: each genie's lamp, with its home and its conversation in it.
///
/// The directory is `$XDG_DATA_HOME/genies`, which is usually `~/.local/share/genies`.
public final class Shelf {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path root;

    public Shelf(Path root) { this.root = root; }

    /// The shelf in the user's data directory.
    public static Shelf standard(Function<String, Optional<String>> environment) {
        Path data = environment.apply("XDG_DATA_HOME").filter(value -> !value.isBlank()).map(Path::of)
                .orElseGet(() -> Path.of(System.getProperty("user.home"), ".local", "share"));
        return new Shelf(data.resolve("genies"));
    }

    public Path root() { return root; }

    /// The lamp directory of the genie `id`.
    public Path lampOf(UUID id) { return root.resolve("lamps").resolve(id.toString()); }

    /// The genies there were when Genies last closed, all asleep. None, the first time.
    public Tuple<Genie> genies() {
        Tuple<Genie> genies = Tuple.of(Genie.class);
        for (JsonNode entry : read("genies.json").path("genies")) {
            try {
                genies = genies.add(Genie.asleep(UUID.fromString(entry.path("id").asText()), entry.path("name").asText()));
            } catch (IllegalArgumentException notAnId) {
                // An entry someone edited by hand into something else is skipped.
            }
        }
        return genies;
    }

    public void keep(Tuple<Genie> genies) throws IOException {
        ArrayNode list = JSON.createArrayNode();
        for (Genie genie : genies) list.addObject().put("id", genie.id().toString()).put("name", genie.name());
        ObjectNode file = JSON.createObjectNode();
        file.set("genies", list);
        write("genies.json", file);
    }

    /// The settings as last kept, or the defaults.
    ///
    /// The hosted service's settings are the file's top-level fields, as they were before a model
    /// server on this computer could be chosen, so an older file still reads.
    public Settings settings() {
        JsonNode file = read("settings.json");
        Settings defaults = Settings.defaults();
        Settings.KeySource source = file.path("keySource").asText().equals("ENTERED")
                ? Settings.KeySource.ENTERED : Settings.KeySource.ENVIRONMENT;
        Settings.Place place = file.path("place").asText().equals("THIS_MACHINE")
                ? Settings.Place.THIS_MACHINE : Settings.Place.HOSTED;
        JsonNode local = file.path("local");
        return new Settings(place,
                new Settings.Hosted(file.path("service").asText(defaults.hosted().service()), source,
                                    file.path("key").asText(""), file.path("model").asText(defaults.hosted().model())),
                new Settings.OnThisMachine(local.path("address").asText(defaults.local().address()),
                                           local.path("model").asText(defaults.local().model())));
    }

    /// Keeps the settings, in a file only this user can read. An entered key is kept only
    /// while the settings say to use it.
    public void keep(Settings settings) throws IOException {
        Settings.Hosted hosted = settings.hosted();
        ObjectNode file = JSON.createObjectNode()
                .put("place", settings.place().name())
                .put("service", hosted.service().strip())
                .put("keySource", hosted.keySource().name())
                .put("model", hosted.model().strip());
        if (hosted.keySource() == Settings.KeySource.ENTERED) file.put("key", hosted.key().strip());
        file.putObject("local")
                .put("address", settings.local().address().strip())
                .put("model", settings.local().model().strip());
        write("settings.json", file);
    }

    private JsonNode read(String name) {
        try {
            return JSON.readTree(Files.readString(root.resolve(name), StandardCharsets.UTF_8));
        } catch (IOException missingOrBroken) {   // JacksonException is one too
            return JSON.createObjectNode();
        }
    }

    /// Writes a file whole or not at all, readable by this user only.
    private void write(String name, JsonNode content) throws IOException {
        Files.createDirectories(root);
        Path partial = root.resolve("." + name + ".part");
        Files.deleteIfExists(partial);
        Files.createFile(partial, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(partial, content.toPrettyString() + "\n", StandardCharsets.UTF_8);
        Files.move(partial, root.resolve(name), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
