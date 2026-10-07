package dev.gui.genie;

import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.gui.model.Hardware;
import dev.gui.model.OllamaSetup;

import io.airlift.compress.v3.zstd.ZstdInputStream;
import sprouts.Tuple;

/// Ollama on this computer: finds it, installs it, starts it, and gets a model ready for genies.
///
/// Genies installs Ollama for this user alone, into a folder of its own, [#home], from Ollama's
/// official Linux archive: no password, and nothing changes outside that folder. An Ollama
/// installed some other way, on the `PATH`, is used instead. Started by Genies, Ollama outlives
/// it, so a genie left running in the background keeps its model. It holds a model in memory
/// only for a few minutes after the last request.
///
/// Every method blocks, some for minutes; Genies calls them off Swing's event thread. Each one
/// that takes long gives up when its thread is interrupted.
public final class OllamaKeeper {

    private static final ObjectMapper JSON = new ObjectMapper();

    /// Where Ollama answers its own API: on this computer only.
    private static final String API = "http://127.0.0.1:11434/api/";

    /// Where Ollama's archives are, one for each kind of processor, and one more with what AMD
    /// graphics cards need.
    private static final String DOWNLOADS = "https://ollama.com/download/";

    /// The context of Genies' versions of the models, in tokens. Ollama's own is a few thousand,
    /// which a genie's instructions and the output of its tools overflow: the model then forgets
    /// the start of the conversation, and its task with it.
    static final int CONTEXT = 32_768;

    /// How long Ollama has to answer a question about itself.
    private static final Duration ASKING = Duration.ofSeconds(5);

    /// How long Ollama has to start.
    private static final Duration STARTING = Duration.ofSeconds(30);

    /// How long trying a model may take: its first answer loads it from the disk, which takes
    /// minutes for a large model.
    private static final Duration TRYING = Duration.ofMinutes(10);

    /// AMD's vendor number, as Linux lists a graphics card's maker.
    private static final String AMD = "0x1002";

    private final Path home;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build();

    /// @param home the folder Genies installs Ollama into, and writes what it says into
    public OllamaKeeper(Path home) { this.home = home; }

    /// How far a step is, from 0 to 1, and what happens, in a few words.
    @FunctionalInterface
    public interface Progress {
        void at(double progress, String says);
    }

    /// Whether Ollama is installed, whether it runs, the models it has, and what the computer has
    /// for running them.
    public OllamaSetup.Found look() throws InterruptedException {
        String version = "";
        Tuple<String> models = Tuple.of(String.class);
        try {
            version = get("version").path("version").asText("");
            for (JsonNode model : get("tags").path("models")) models = models.add(model.path("name").asText());
        } catch (IOException notRunning) {
            // Then there is no version and there are no models.
        }
        return new OllamaSetup.Found(program().map(Path::toString).orElse(""), version, models, hardware());
    }

    /// Installs Ollama into [#home], replacing what was there only once the new one is whole. A
    /// computer with an AMD graphics card gets a second archive, with what Ollama needs for it.
    public void install(Progress progress) throws IOException, InterruptedException {
        String processor = System.getProperty("os.arch").equals("aarch64") ? "arm64" : "amd64";
        Path partial = Path.of(home + ".part");
        deleteAll(partial);
        Files.createDirectories(partial);
        unpack(URI.create(DOWNLOADS + "ollama-linux-" + processor + ".tar.zst"), partial, "Downloading Ollama", progress);
        if (!amdCards().isEmpty()) unpack(URI.create(DOWNLOADS + "ollama-linux-" + processor + "-rocm.tar.zst"), partial,
                        "Downloading what Ollama needs for AMD graphics cards", progress);
        // What Ollama said when it last ran stays.
        Path log = home.resolve("serve.log");
        if (Files.exists(log)) Files.move(log, partial.resolve("serve.log"));
        deleteAll(home);
        Files.move(partial, home, StandardCopyOption.ATOMIC_MOVE);
    }

    /// Starts Ollama, unless it runs already, and waits until it answers.
    public void start() throws IOException, InterruptedException {
        if (isRunning()) return;
        Path program = program().orElseThrow(() -> new IOException("Ollama is not installed."));
        Files.createDirectories(home);
        Path log = home.resolve("serve.log");
        // In a session of its own, so that closing the terminal Genies was started from does not
        // end it too.
        ProcessBuilder serve = new ProcessBuilder("setsid", program.toString(), "serve")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        serve.environment().put("OLLAMA_HOST", "127.0.0.1:11434");
        try {
            serve.start();
        } catch (IOException noSetsid) {
            serve.command(program.toString(), "serve").start();
        }
        Instant until = Instant.now().plus(STARTING);
        while (Instant.now().isBefore(until)) {
            if (isRunning()) return;
            Thread.sleep(500);
        }
        throw new IOException("Ollama did not start within " + STARTING.toSeconds() + " seconds. What it said is in " + log + ".");
    }

    /// Downloads `model` into Ollama. Ollama keeps what it downloaded of a model that was
    /// stopped, and goes on from there the next time.
    public void pull(String model, Progress progress) throws IOException, InterruptedException {
        HttpResponse<InputStream> response = send(post("pull", JSON.createObjectNode().put("model", model), ASKING),
                                                  HttpResponse.BodyHandlers.ofInputStream());
        Map<String, long[]> layers = new HashMap<>();
        try (BufferedReader lines = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            for (String line = lines.readLine(); line != null; line = lines.readLine()) {
                if (Thread.interrupted()) throw new InterruptedException();
                JsonNode said = JSON.readTree(line);
                String error = said.path("error").asText("");
                if (error.contains("file does not exist"))
                    throw new IOException("Ollama has no model called " + model + ". Check its name on ollama.com/library.");
                if (!error.isEmpty()) throw new IOException("Ollama could not download " + model + ": " + error);
                if (said.path("status").asText().equals("success")) return;
                if (said.has("total")) layers.put(said.path("digest").asText(), new long[] { said.path("completed").asLong(), said.path("total").asLong() });
                long done = layers.values().stream().mapToLong(it -> it[0]).sum();
                long all = layers.values().stream().mapToLong(it -> it[1]).sum();
                if (all > 0) progress.at((double) done / all, "Downloading " + model + ": " + amount(done) + " of " + amount(all) + ".");
            }
        }
        throw new IOException("Ollama stopped downloading " + model + " without saying why.");
    }

    /// Makes Genies' version of `model`, named [OllamaSetup#PREFIX] and the model, with a context
    /// large enough for a genie, once it is sure the model can use tools, as a genie must.
    public void prepare(String model) throws IOException, InterruptedException {
        JsonNode shown = ask(post("show", JSON.createObjectNode().put("model", model), ASKING));
        boolean tools = false;
        for (JsonNode capability : shown.path("capabilities")) tools |= capability.asText().equals("tools");
        if (!tools) throw new IOException(model + " cannot use tools, so a genie could not work with it. Choose a model that can: "
                                          + "Genies suggests some, and ollama.com/search?c=tools lists them all.");
        ObjectNode create = JSON.createObjectNode().put("model", OllamaSetup.PREFIX + model).put("from", model).put("stream", false);
        create.putObject("parameters").put("num_ctx", CONTEXT);
        ask(post("create", create, Duration.ofMinutes(2)));
    }

    /// Asks `model` for one word, to be sure it loads and answers.
    public void tryOut(String model) throws IOException, InterruptedException {
        ObjectNode chat = JSON.createObjectNode().put("model", model).put("stream", false);
        chat.putArray("messages").addObject().put("role", "user").put("content", "Say hello.");
        chat.putObject("options").put("num_predict", 1);
        ask(post("chat", chat, TRYING));
    }

    // ─── what the computer has ─────────────────────────────────────────────────────────────

    /// The computer's memory, and its best graphics card that a model can run on.
    static Hardware hardware() throws InterruptedException {
        long memory = 0;
        try {
            memory = memoryIn(Files.readString(Path.of("/proc/meminfo")));
        } catch (IOException unreadable) {
            // Then the memory is unknown, and Genies says so.
        }
        List<Hardware.Graphics> cards = new ArrayList<>(nvidiaIn(output("nvidia-smi", "--query-gpu=name,memory.total", "--format=csv,noheader,nounits")));
        cards.addAll(amdCards());
        Optional<Hardware.Graphics> best = cards.stream().max(Comparator.comparingLong(Hardware.Graphics::memory));
        return new Hardware(memory, best);
    }

    /// The AMD graphics cards with memory of their own, enough to run a model, as Linux lists them.
    private static List<Hardware.Graphics> amdCards() {
        List<Hardware.Graphics> cards = new ArrayList<>();
        try (DirectoryStream<Path> drm = Files.newDirectoryStream(Path.of("/sys/class/drm"), "card[0-9]*")) {
            for (Path card : drm) {
                Path device = card.resolve("device");
                if (!Files.isReadable(device.resolve("mem_info_vram_total"))
                        || !Files.readString(device.resolve("vendor")).strip().equals(AMD)) continue;
                long memory = Long.parseLong(Files.readString(device.resolve("mem_info_vram_total")).strip());
                Path product = device.resolve("product_name");
                String name = Files.isReadable(product) ? Files.readString(product).strip() : "";
                if (memory >= Hardware.LEAST_GRAPHICS_MEMORY) cards.add(new Hardware.Graphics(name.isEmpty() ? "AMD Radeon" : name, memory));
            }
        } catch (IOException | NumberFormatException unreadable) {
            // A card that cannot be read counts as none.
        }
        return cards;
    }

    /// The memory in `/proc/meminfo`, in bytes, or 0.
    static long memoryIn(String meminfo) {
        for (String line : meminfo.lines().toList())
            if (line.startsWith("MemTotal:")) return Long.parseLong(line.replaceAll("\\D", "")) * 1024;
        return 0;
    }

    /// The cards with enough memory to run a model, of those that `nvidia-smi
    /// --query-gpu=name,memory.total --format=csv,noheader,nounits` lists one on each line, such
    /// as `NVIDIA GeForce RTX 4070, 12282`, in mebibytes.
    static List<Hardware.Graphics> nvidiaIn(String output) {
        List<Hardware.Graphics> cards = new ArrayList<>();
        for (String line : output.lines().toList()) {
            int comma = line.lastIndexOf(',');
            if (comma < 0) continue;
            try {
                long memory = Long.parseLong(line.substring(comma + 1).strip()) << 20;
                if (memory >= Hardware.LEAST_GRAPHICS_MEMORY) cards.add(new Hardware.Graphics(line.substring(0, comma).strip(), memory));
            } catch (NumberFormatException notACard) {
                // nvidia-smi said something else, such as that it found no driver.
            }
        }
        return cards;
    }

    // ─── the rest ──────────────────────────────────────────────────────────────────────────

    /// The `ollama` program: one installed on the `PATH`, or else Genies' own.
    public Optional<Path> program() {
        String path = Optional.ofNullable(System.getenv("PATH")).orElse("");
        for (String directory : path.split(":", -1)) {
            if (directory.isEmpty()) continue;
            Path found = Path.of(directory, "ollama");
            if (Files.isExecutable(found)) return Optional.of(found);
        }
        Path own = home.resolve("bin").resolve("ollama");
        return Files.isExecutable(own) ? Optional.of(own) : Optional.empty();
    }

    private boolean isRunning() throws InterruptedException {
        try {
            get("version");
            return true;
        } catch (IOException notRunning) {
            return false;
        }
    }

    /// Downloads the archive at `archive` and unpacks it into `into` as it arrives, with `tar`.
    private void unpack(URI archive, Path into, String what, Progress progress) throws IOException, InterruptedException {
        HttpResponse<InputStream> response = http.send(HttpRequest.newBuilder(archive).build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("ollama.com answered " + response.statusCode() + " for " + archive + ".");
        }
        long total = response.headers().firstValueAsLong("content-length").orElse(0);
        long[] arrived = { 0 };
        Process tar = new ProcessBuilder("tar", "-x", "-C", into.toString()).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        // Read as it comes: on a full disk tar complains once for every file, and a pipe nobody
        // reads fills up and stops tar, and with it the download, for good.
        FutureTask<String> said = new FutureTask<>(() -> new String(tar.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).strip());
        Thread.ofVirtual().name("tar-errors").start(said);
        try (InputStream counted = new FilterInputStream(response.body()) {
                 @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                     int read = super.read(buffer, offset, length);
                     if (read > 0) arrived[0] += read;
                     return read;
                 }
             };
             InputStream unpacked = new ZstdInputStream(counted);
             OutputStream toTar = tar.getOutputStream()) {
            byte[] buffer = new byte[1 << 16];
            long shown = -1;
            for (int read = unpacked.read(buffer); read >= 0; read = unpacked.read(buffer)) {
                if (Thread.interrupted()) {
                    tar.destroy();
                    throw new InterruptedException();
                }
                toTar.write(buffer, 0, read);
                // Once for each megabyte, which is often enough to look smooth.
                if (arrived[0] >> 20 != shown) {
                    shown = arrived[0] >> 20;
                    progress.at(total > 0 ? (double) arrived[0] / total : 0, what + ": " + amount(arrived[0]) + (total > 0 ? " of " + amount(total) : "") + ".");
                }
            }
        } catch (IOException failed) {
            tar.destroy();
            throw new IOException(what + " failed: " + Optional.ofNullable(failed.getMessage()).orElse(failed.toString()), failed);
        }
        if (tar.waitFor() == 0) return;
        String complaints;
        try {
            complaints = said.get();
        } catch (ExecutionException unreadable) {
            complaints = "";
        }
        // The last lines: on a full disk the same complaint comes for every file.
        List<String> lines = complaints.lines().toList();
        throw new IOException("Unpacking Ollama failed: " + String.join("\n", lines.subList(Math.max(0, lines.size() - 5), lines.size())));
    }

    private JsonNode get(String path) throws IOException, InterruptedException {
        return ask(HttpRequest.newBuilder(URI.create(API + path)).timeout(ASKING).build());
    }

    /// A request to Ollama with `body`, which waits `timeout` for Ollama to begin its answer.
    private static HttpRequest post(String path, ObjectNode body, Duration timeout) {
        return HttpRequest.newBuilder(URI.create(API + path)).timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
    }

    /// Ollama's answer to `request`, or its error as the exception's message.
    private JsonNode ask(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode answer = JSON.readTree(response.body().isBlank() ? "{}" : response.body());
        if (response.statusCode() / 100 != 2)
            throw new IOException(answer.path("error").asText("Ollama answered " + response.statusCode() + "."));
        return answer;
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> body) throws IOException, InterruptedException {
        try {
            return http.send(request, body);
        } catch (ConnectException notRunning) {
            throw new IOException("Ollama does not answer on this computer.", notRunning);
        }
    }

    /// What `command` printed, or nothing when it is not there or fails.
    private static String output(String... command) throws InterruptedException {
        try {
            Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            // What it prints is short, so it ends without anyone reading it; one that hangs is let go.
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroy();
                return "";
            }
            return process.exitValue() == 0 ? new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8) : "";
        } catch (IOException notThere) {
            return "";
        }
    }

    private static void deleteAll(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (Stream<Path> all = Files.walk(directory)) {
            for (Path path : all.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    /// "312 MB", or "5.2 GB" from a gigabyte on.
    private static String amount(long bytes) {
        return bytes < 1L << 30 ? (bytes >> 20) + " MB" : String.format(Locale.ROOT, "%.1f GB", bytes / (double) (1L << 30));
    }
}
