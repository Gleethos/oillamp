package dev.gui.genie;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import sprouts.Tuple;

/// The models a model server on this computer offers, asked for on the host.
///
/// OpenAI-style servers (Ollama, LM Studio, llama.cpp's server) list them at `<address>/models`,
/// as `{"data":[{"id":…},…]}`. The ids are what genies name their model with.
public final class ModelCatalog {

    private ModelCatalog() {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration PATIENCE = Duration.ofSeconds(5);

    /// The models the server at `address`, such as `http://127.0.0.1:11434/v1`, offers.
    ///
    /// @throws IOException when it cannot be asked; the message says why, for the user
    public static Tuple<String> ofServerAt(String address) throws IOException, InterruptedException {
        URI models = URI.create(address.strip().replaceAll("/+$", "") + "/models");
        HttpResponse<String> answer;
        try {
            answer = HttpClient.newBuilder().connectTimeout(PATIENCE).build().send(
                    HttpRequest.newBuilder(models).timeout(PATIENCE).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (ConnectException refused) {
            throw new IOException("Nothing answers at " + address.strip() + ". Is the model server running?");
        } catch (HttpTimeoutException slow) {
            throw new IOException("The model server at " + address.strip() + " did not answer within five seconds.");
        }
        if (answer.statusCode() != 200)
            throw new IOException("The model server answered " + answer.statusCode() + " at " + models
                    + "; is the address the one of its OpenAI-style API, such as …/v1?");
        return parse(answer.body());
    }

    /// The model ids in an OpenAI-style model list, in its order. Anything else reads as none.
    static Tuple<String> parse(String list) {
        Tuple<String> ids = Tuple.of(String.class);
        try {
            for (JsonNode model : JSON.readTree(list).path("data"))
                if (model.path("id").isTextual() && !model.path("id").asText().isBlank())
                    ids = ids.add(model.path("id").asText());
        } catch (IOException notAList) {
            // None.
        }
        return ids;
    }
}
