package dev.gui.model;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;

/// Where the genies' model runs, and how they reach it.
///
/// Three places, each with settings of its own, so switching between them loses none:
///
/// - **Eden AI**, at its EU endpoint, with the key in `EDENAI_API_KEY` from the environment
///   Genies was started from, or one the user enters.
/// - **a model server elsewhere**, such as Ollama on another machine behind a proxy that checks
///   a key: its address and, if it asks for one, its key.
/// - **this computer**: a model server such as Ollama, LM Studio or llama.cpp's server, on this
///   machine's loopback. It needs no key.
///
/// Wherever it is, the key and the address never reach a genie. They go to the lamp's engine on
/// the host, which sends the genie's requests on; inside the sandbox there is only oillamp's relay
/// and a placeholder key.
///
/// @param place     which of the three the genies use
/// @param edenAi    Eden AI's settings
/// @param elsewhere the model server elsewhere's settings
/// @param local     the model server on this computer's settings
public record Settings(Place place, EdenAi edenAi, Elsewhere elsewhere, OnThisMachine local) {

    public enum Place {
        /// Eden AI's EU endpoint.
        EDEN_AI,
        /// A model server on another machine, reached over https.
        ELSEWHERE,
        /// A model server on this computer.
        THIS_MACHINE
    }

    public enum KeySource {
        /// `EDENAI_API_KEY` in the environment Genies was started from.
        ENVIRONMENT,
        /// The key entered in the settings.
        ENTERED
    }

    /// Eden AI, at [#EDEN_AI_SERVICE].
    ///
    /// @param keySource whether the key comes from the environment or was entered here
    /// @param key       the entered key, or empty
    /// @param model     the model genies use, as Eden AI names it, such as
    ///                  `mistral/mistral-small-latest`
    public record EdenAi(KeySource keySource, String key, String model) {
        public EdenAi withKeySource(KeySource keySource) { return new EdenAi(keySource, key, model); }
        public EdenAi withKey(String key)               { return new EdenAi(keySource, key, model); }
        public EdenAi withModel(String model)           { return new EdenAi(keySource, key, model); }
    }

    /// A model server on another machine.
    ///
    /// @param address where its OpenAI-style API is, such as `https://ollama.example.com/v1`
    /// @param key     the key it asks for, or empty when it asks for none
    /// @param model   the model genies use, as the server lists it
    public record Elsewhere(String address, String key, String model) {
        public Elsewhere withAddress(String address) { return new Elsewhere(address, key, model); }
        public Elsewhere withKey(String key)         { return new Elsewhere(address, key, model); }
        public Elsewhere withModel(String model)     { return new Elsewhere(address, key, model); }
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

    /// Eden AI's EU endpoint. It answers OpenAI-style requests under `/v3`.
    public static final String EDEN_AI_SERVICE = "https://api.eu.edenai.run";

    /// The variable the Eden AI key is read from, unless the user entered one.
    public static final String KEY_VARIABLE = "EDENAI_API_KEY";

    /// What a model server that asks for no key is sent as its key. The lamp needs one to send.
    static final String NO_KEY_NEEDED = "no-key-needed";

    public static Settings defaults() {
        return new Settings(Place.EDEN_AI,
                new EdenAi(KeySource.ENVIRONMENT, "", "mistral/mistral-small-latest"),
                new Elsewhere("", "", ""),
                new OnThisMachine("http://127.0.0.1:11434/v1", ""));
    }

    public Settings withPlace(Place place)             { return new Settings(place, edenAi, elsewhere, local); }
    public Settings withEdenAi(EdenAi edenAi)          { return new Settings(place, edenAi, elsewhere, local); }
    public Settings withElsewhere(Elsewhere elsewhere) { return new Settings(place, edenAi, elsewhere, local); }
    public Settings withLocal(OnThisMachine local)     { return new Settings(place, edenAi, elsewhere, local); }

    // ─── what a genie's lamp is given ──────────────────────────────────────────────────────

    /// The address model requests go to.
    public String service() {
        return switch (place) {
            case EDEN_AI      -> EDEN_AI_SERVICE;
            case ELSEWHERE    -> elsewhere.address().strip();
            case THIS_MACHINE -> local.address().strip();
        };
    }

    /// The model the genies use.
    public String model() {
        return switch (place) {
            case EDEN_AI      -> edenAi.model();
            case ELSEWHERE    -> elsewhere.model();
            case THIS_MACHINE -> local.model();
        };
    }

    /// The same settings with another model, in the place they use.
    public Settings withModel(String model) {
        return switch (place) {
            case EDEN_AI      -> withEdenAi(edenAi.withModel(model));
            case ELSEWHERE    -> withElsewhere(elsewhere.withModel(model));
            case THIS_MACHINE -> withLocal(local.withModel(model));
        };
    }

    /// The key for the genies' requests: the entered one, the one in the environment, or, for a
    /// model server that asks for none, a stand-in it ignores.
    ///
    /// @param environmentKey the value of [#KEY_VARIABLE] where Genies was started, if set
    public Optional<String> keyFrom(Optional<String> environmentKey) {
        return switch (place) {
            case THIS_MACHINE -> Optional.of(NO_KEY_NEEDED);
            case ELSEWHERE    -> Optional.of(elsewhere.key().isBlank() ? NO_KEY_NEEDED : elsewhere.key().strip());
            case EDEN_AI      -> switch (edenAi.keySource()) {
                case ENTERED     -> Optional.of(edenAi.key().strip()).filter(entered -> !entered.isEmpty());
                case ENVIRONMENT -> environmentKey.filter(found -> !found.isBlank());
            };
        };
    }

    /// The key to ask for the model list with: the one the genies' requests carry, unless that is
    /// the stand-in for a server that asks for none.
    public Optional<String> listingKeyFrom(Optional<String> environmentKey) {
        return keyFrom(environmentKey).filter(key -> !key.equals(NO_KEY_NEEDED));
    }

    /// Why a genie could not reach its model with these settings, or nothing when it can.
    public Optional<String> problem(Optional<String> environmentKey) {
        return switch (place) {
            case EDEN_AI -> {
                if (edenAi.model().isBlank()) yield Optional.of("Name the model the genies should use; Look up shows Eden AI's list.");
                if (keyFrom(environmentKey).isPresent()) yield Optional.empty();
                yield Optional.of(edenAi.keySource() == KeySource.ENTERED
                        ? "Enter a key, or use the one in " + KEY_VARIABLE + "."
                        : KEY_VARIABLE + " is not set where Genies was started. Enter a key in the settings, or set it and start Genies again.");
            }
            case ELSEWHERE -> {
                if (elsewhere.address().isBlank())
                    yield Optional.of("Enter the server's address, such as https://ollama.example.com/v1.");
                Optional<String> wrongAddress = serviceProblem(elsewhere.address());
                if (wrongAddress.isPresent()) yield wrongAddress;
                if (elsewhere.model().isBlank()) yield Optional.of("Name the model, as the server lists it; Look up shows its list.");
                yield Optional.empty();
            }
            case THIS_MACHINE -> {
                Optional<String> wrongAddress = serviceProblem(local.address());
                if (wrongAddress.isPresent()) yield wrongAddress;
                if (!isOnThisMachine(local.address()))
                    yield Optional.of("A model server on this computer is reached at 127.0.0.1 or localhost, such as http://127.0.0.1:11434/v1.");
                if (local.model().isBlank()) yield Optional.of("Name the model, as the model server lists it; Look up shows its list.");
                yield Optional.empty();
            }
        };
    }

    /// The same rules the lamp's engine applies: the key travels to this service, so it must be
    /// `https`, except on this machine's loopback; a path is allowed, for an API that lives under
    /// one; a user, query or fragment is not.
    static Optional<String> serviceProblem(String text) {
        URI uri;
        try {
            uri = new URI(text.strip());
        } catch (URISyntaxException e) {
            return Optional.of("The address should look like https://ollama.example.com/v1 or http://127.0.0.1:11434/v1.");
        }
        String host = Optional.ofNullable(uri.getHost()).orElse("");
        String scheme = Optional.ofNullable(uri.getScheme()).orElse("");
        if (host.isEmpty() || !scheme.matches("https?") || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null
                || !Optional.ofNullable(uri.getRawPath()).orElse("").matches("(/[A-Za-z0-9._~-]+)*/?"))
            return Optional.of("The address should be a scheme, a host, and perhaps a port and a path, such as https://ollama.example.com/v1 or http://127.0.0.1:11434/v1.");
        if (scheme.equals("http") && !isLoopback(host))
            return Optional.of("The key is sent to the server, so it must use https:// (http:// only on this computer).");
        return Optional.empty();
    }

    /// Whether `service` is Eden AI, as a settings file from before a server elsewhere could be
    /// chosen may name it.
    public static boolean isEdenAi(String service) {
        try {
            String host = Optional.ofNullable(new URI(service.strip()).getHost()).orElse("");
            return host.equals("edenai.run") || host.endsWith(".edenai.run");
        } catch (URISyntaxException e) {
            return false;
        }
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

    /// Never shows a key, so settings can be logged or printed safely.
    @Override public String toString() {
        return "Settings[place=" + place
             + ", edenAi=" + edenAi.keySource() + " " + (edenAi.key().isEmpty() ? "no key entered" : "(key entered, hidden)") + " " + edenAi.model()
             + ", elsewhere=" + elsewhere.address() + " " + (elsewhere.key().isEmpty() ? "no key" : "(key hidden)") + " " + elsewhere.model()
             + ", local=" + local.address() + " " + local.model() + "]";
    }
}
