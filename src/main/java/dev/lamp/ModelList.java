package dev.lamp;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/// The models a model service offers a lamp's agent, asked for on the host, as [Lamp#models] does.
///
/// The list is the one the harness in the sandbox is given: the engine's relay sends the sandbox's
/// `GET /v3/models` to the service's own path in place of `/v3`, or unchanged to a service without
/// a path, with the key. For Eden AI the harness keeps only the models served in the EU.
///
/// The rules for where the list is and which models are kept are the engine's too, in its relay
/// and its `[model]` settings. They are written out here because this package must not depend on
/// the engine; `ListingAServicesModelsSpec` checks both give the same answer.
final class ModelList {

    private ModelList() {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration CONNECT_PATIENCE = Duration.ofSeconds(5);
    /// Eden AI's list, with prices and capabilities, is about half a megabyte.
    private static final Duration ANSWER_PATIENCE = Duration.ofSeconds(20);

    static List<String> of(URI service, Optional<String> key) throws IOException, InterruptedException {
        Optional<String> wrong = serviceProblem(service);
        if (wrong.isPresent()) throw new IllegalArgumentException(wrong.get());
        URI list = listAt(service);
        HttpRequest.Builder request = HttpRequest.newBuilder(list).timeout(ANSWER_PATIENCE).GET();
        key.map(String::strip).filter(it -> !it.isEmpty()).ifPresent(it -> request.header("Authorization", "Bearer " + it));
        HttpResponse<String> answer;
        // No proxy and no redirects: the key goes to this address and nowhere else.
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_PATIENCE)
                .followRedirects(HttpClient.Redirect.NEVER).build()) {
            answer = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (ConnectException refused) {
            throw new IOException("Nothing answers at " + service + ". Is the model server running?");
        } catch (HttpTimeoutException slow) {
            throw new IOException("The model service at " + service + " did not answer in time.");
        }
        return switch (answer.statusCode()) {
            case 200 -> parse(answer.body(), isEdenAi(service));
            case 401, 403 -> throw new IOException("The model service at " + service + " refused the key ("
                    + answer.statusCode() + ").");
            case 404 -> throw new IOException("The model service answered 404 at " + list
                    + "; is the address the one of its OpenAI-style API, such as …/v1?");
            default -> throw new IOException("The model service answered " + answer.statusCode() + " at " + list + ".");
        };
    }

    /// Where the list is: the service's own path, or Eden AI's `/v3` for a service without one.
    static URI listAt(URI service) {
        String base = Optional.ofNullable(service.getRawPath()).orElse("").replaceAll("/+$", "");
        return service.resolve((base.isEmpty() ? "/v3" : base) + "/models");
    }

    /// The model ids in an OpenAI-style list, `{"data":[{"id":…},…]}`, in its order. For Eden AI,
    /// only those whose `regions` include `eu`. Anything that is not such a list reads as none.
    static List<String> parse(String body, boolean euOnly) {
        List<String> ids = new ArrayList<>();
        try {
            for (JsonNode model : JSON.readTree(body).path("data")) {
                String id = model.path("id").asText("").strip();
                if (id.isEmpty() || !model.path("id").isTextual()) continue;
                if (euOnly && !servedInTheEu(model)) continue;
                ids.add(id);
            }
        } catch (JacksonException notAList) {
            // None.
        }
        return List.copyOf(ids);
    }

    private static boolean servedInTheEu(JsonNode model) {
        for (JsonNode region : model.path("regions"))
            if (region.path("code").asText("").equalsIgnoreCase("eu")) return true;
        return false;
    }

    /// Eden AI's model list says where each model is served; no other service's does.
    static boolean isEdenAi(URI service) {
        String host = Optional.ofNullable(service.getHost()).orElse("");
        return host.equals("edenai.run") || host.endsWith(".edenai.run");
    }

    /// The engine's rule for a model service: the key travels to it, so it must be `https`,
    /// except on this machine's loopback; a path is allowed, a user, query or fragment is not.
    static Optional<String> serviceProblem(URI service) {
        String host = Optional.ofNullable(service.getHost()).orElse("");
        String scheme = Optional.ofNullable(service.getScheme()).orElse("");
        if (host.isEmpty() || !scheme.matches("https?") || service.getUserInfo() != null
                || service.getQuery() != null || service.getFragment() != null
                || !Optional.ofNullable(service.getRawPath()).orElse("").matches("(/[A-Za-z0-9._~-]+)*/?"))
            return Optional.of("expected a scheme, a host, and optionally a port and a path, "
                    + "such as \"https://api.eu.edenai.run\" or \"http://127.0.0.1:11434/v1\"");
        boolean loopback = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]");
        if (scheme.equals("http") && !loopback)
            return Optional.of("the key is sent to this service, so it must be https:// "
                    + "(plain http:// only for a service on this machine, such as http://127.0.0.1:8080)");
        return Optional.empty();
    }
}
