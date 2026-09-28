package dev.gui.model;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;

/// Where the genies' model runs, and how they reach it.
///
/// Two places, each with settings of its own, so switching between them loses neither:
///
/// - **a hosted service**: Eden AI's EU endpoint out of the box, with the key in
///   `EDENAI_API_KEY` from the environment Genies was started from. The user may enter a key of
///   their own, and name another service.
/// - **this computer**: a model server such as Ollama, LM Studio or llama.cpp's server, on this
///   machine's loopback. It needs no key.
///
/// Either way, the key and the address never reach a genie. They go to the lamp's engine on the
/// host, which sends the genie's requests on; inside the sandbox there is only oillamp's relay
/// and a placeholder key.
///
/// @param place  which of the two the genies use
/// @param hosted the hosted service's settings
/// @param local  the model server's settings
public record Settings(Place place, Hosted hosted, OnThisMachine local) {

    public enum Place {
        /// A service on the internet, Eden AI's EU endpoint unless the user names another.
        HOSTED,
        /// A model server on this computer.
        THIS_MACHINE
    }

    public enum KeySource {
        /// `EDENAI_API_KEY` in the environment Genies was started from.
        ENVIRONMENT,
        /// The key entered in the settings.
        ENTERED
    }

    /// A hosted model service.
    ///
    /// @param service   its address, such as `https://api.eu.edenai.run`. It must answer
    ///                  OpenAI-style requests under `/v3`, as Eden AI does, or under the path given
    /// @param keySource whether the key comes from the environment or was entered here
    /// @param key       the entered key, or empty
    /// @param model     the model genies use, as the service names it, such as
    ///                  `mistral/mistral-small-latest`
    public record Hosted(String service, KeySource keySource, String key, String model) {
        public Hosted withService(String service)       { return new Hosted(service, keySource, key, model); }
        public Hosted withKeySource(KeySource keySource) { return new Hosted(service, keySource, key, model); }
        public Hosted withKey(String key)               { return new Hosted(service, keySource, key, model); }
        public Hosted withModel(String model)           { return new Hosted(service, keySource, key, model); }
    }

    /// A model server on this computer.
    ///
    /// @param address where its OpenAI-style API is, such as `http://127.0.0.1:11434/v1` for
    ///                Ollama or `http://127.0.0.1:1234/v1` for LM Studio
    /// @param model   the model genies use, as the server lists it, such as `qwen2.5:7b`
    public record OnThisMachine(String address, String model) {
        public OnThisMachine withAddress(String address) { return new OnThisMachine(address, model); }
        public OnThisMachine withModel(String model)     { return new OnThisMachine(address, model); }
    }

    /// The variable the key is read from, unless the user entered one.
    public static final String KEY_VARIABLE = "EDENAI_API_KEY";

    /// What a model server on this computer is sent as its key. It asks for none, and the lamp
    /// needs one to send.
    static final String NO_KEY_NEEDED = "not-needed-on-this-machine";

    public static Settings defaults() {
        return new Settings(Place.HOSTED,
                new Hosted("https://api.eu.edenai.run", KeySource.ENVIRONMENT, "", "mistral/mistral-small-latest"),
                new OnThisMachine("http://127.0.0.1:11434/v1", ""));
    }

    public Settings withPlace(Place place)            { return new Settings(place, hosted, local); }
    public Settings withHosted(Hosted hosted)         { return new Settings(place, hosted, local); }
    public Settings withLocal(OnThisMachine local)    { return new Settings(place, hosted, local); }

    // ─── what a genie's lamp is given ──────────────────────────────────────────────────────

    /// The address model requests go to.
    public String service() {
        return place == Place.HOSTED ? hosted.service() : local.address();
    }

    /// The model the genies use.
    public String model() {
        return place == Place.HOSTED ? hosted.model() : local.model();
    }

    /// The same settings with another model, in the place they use.
    public Settings withModel(String model) {
        return place == Place.HOSTED ? withHosted(hosted.withModel(model)) : withLocal(local.withModel(model));
    }

    /// The key for the genies' requests: the entered one, the one in the environment, or, for a
    /// model server on this computer, a stand-in it ignores.
    ///
    /// @param environmentKey the value of [#KEY_VARIABLE] where Genies was started, if set
    public Optional<String> keyFrom(Optional<String> environmentKey) {
        if (place == Place.THIS_MACHINE) return Optional.of(NO_KEY_NEEDED);
        return switch (hosted.keySource()) {
            case ENTERED     -> Optional.of(hosted.key().strip()).filter(entered -> !entered.isEmpty());
            case ENVIRONMENT -> environmentKey.filter(found -> !found.isBlank());
        };
    }

    /// Why a genie could not reach its model with these settings, or nothing when it can.
    public Optional<String> problem(Optional<String> environmentKey) {
        if (place == Place.THIS_MACHINE) {
            Optional<String> wrongAddress = serviceProblem(local.address());
            if (wrongAddress.isPresent()) return wrongAddress;
            if (!isOnThisMachine(local.address()))
                return Optional.of("A model server on this computer is reached at 127.0.0.1 or localhost, such as http://127.0.0.1:11434/v1.");
            if (local.model().isBlank()) return Optional.of("Name the model, as the model server lists it; Look up shows its list.");
            return Optional.empty();
        }
        Optional<String> wrongService = serviceProblem(hosted.service());
        if (wrongService.isPresent()) return wrongService;
        if (hosted.model().isBlank()) return Optional.of("Name the model the genies should use.");
        if (keyFrom(environmentKey).isEmpty())
            return Optional.of(hosted.keySource() == KeySource.ENTERED
                    ? "Enter a key, or use the one in " + KEY_VARIABLE + "."
                    : KEY_VARIABLE + " is not set where Genies was started. Enter a key in the settings, or set it and start Genies again.");
        return Optional.empty();
    }

    /// The same rules the lamp's engine applies: the key travels to this service, so it must be
    /// `https`, except on this machine's loopback; a path is allowed, for an API that lives under
    /// one; a user, query or fragment is not.
    static Optional<String> serviceProblem(String text) {
        URI uri;
        try {
            uri = new URI(text.strip());
        } catch (URISyntaxException e) {
            return Optional.of("The address should look like https://api.eu.edenai.run or http://127.0.0.1:11434/v1.");
        }
        String host = Optional.ofNullable(uri.getHost()).orElse("");
        String scheme = Optional.ofNullable(uri.getScheme()).orElse("");
        if (host.isEmpty() || !scheme.matches("https?") || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null
                || !Optional.ofNullable(uri.getRawPath()).orElse("").matches("(/[A-Za-z0-9._~-]+)*/?"))
            return Optional.of("The address should be a scheme, a host, and perhaps a port and a path, such as https://api.eu.edenai.run or http://127.0.0.1:11434/v1.");
        if (scheme.equals("http") && !isLoopback(host))
            return Optional.of("The key is sent to the service, so it must use https:// (http:// only on this machine).");
        return Optional.empty();
    }

    private static boolean isOnThisMachine(String address) {
        try {
            return isLoopback(Optional.ofNullable(new URI(address.strip()).getHost()).orElse(""));
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static boolean isLoopback(String host) {
        return host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]");
    }

    /// Never shows the key, so settings can be logged or printed safely.
    @Override public String toString() {
        return "Settings[place=" + place + ", service=" + hosted.service() + ", keySource=" + hosted.keySource()
             + ", key=" + (hosted.key().isEmpty() ? "none" : "(entered, hidden)") + ", model=" + hosted.model()
             + ", local=" + local.address() + " " + local.model() + "]";
    }
}
