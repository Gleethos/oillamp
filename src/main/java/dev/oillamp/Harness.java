package dev.oillamp;

import java.io.EOFException;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.lamp.LampEvent.RunOutcome;

/// The agent, as a running session holds it: pi in RPC mode, in the sandbox, reached through ssh.
///
/// A session has at most one, and only the session's runs use it, one at a time, so the agent
/// never works on two things at once. pi starts with the first run and stays for the next ones.
/// Each run begins a new pi conversation, named after the run, so that it starts with a clear
/// head; what it should remember from earlier runs is in the prompt.
///
/// pi reads and writes one JSON object per line: commands on its input, and replies and events on
/// its output. Its documentation is in the sandbox, at
/// `/usr/lib/node_modules/@earendil-works/pi-coding-agent/docs/rpc.md`. pi also saves each
/// conversation in the agent's home, so the user can read a run's whole conversation there later.
final class Harness implements AutoCloseable {

    /// How long pi may take to start and to answer a command, as opposed to working on a prompt.
    private static final Duration ANSWER_TIME = Duration.ofMinutes(2);
    /// How long pi gets to wind down after it was told to stop.
    private static final Duration ABORT_TIME = Duration.ofSeconds(30);

    private final Machine machine;
    private final LampLayout layout;
    private Optional<Machine.Conversation> pi = Optional.empty();
    private int commands;
    private volatile boolean stopping;

    Harness(Machine machine, LampLayout layout) {
        this.machine = machine;
        this.layout = layout;
    }

    /// How a run ended, and the agent's last message.
    record Answer(RunOutcome outcome, String text) {}

    /// Gives pi `prompt` in a new conversation, and waits until it is done, `limit` has passed,
    /// or [#stop] was called. Called by one thread at a time.
    ///
    /// @param name what the conversation is called in pi's list, such as `run-12 (job-3)`
    Answer run(String name, String prompt, Duration limit) {
        if (stopping) return new Answer(RunOutcome.INTERRUPTED, "the session was ending, so the agent was not woken");
        try {
            Machine.Conversation agent = started();
            if (!answered(agent, command("new_session")))
                return failed("pi would not start a new conversation");
            send(agent, command("set_session_name").put("name", name));
            ObjectNode ask = command("prompt").put("message", prompt);
            if (!answered(agent, ask)) return failed("pi did not accept the prompt");
            return follow(agent, machine.now().plus(limit));
        } catch (EOFException ended) {
            return failed("pi ended" + pi.map(p -> p.errorOutput().isBlank() ? "" : ": " + p.errorOutput().strip()).orElse(""));
        } catch (IOException e) {
            return failed("pi could not be reached: " + Problems.reason(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Answer(RunOutcome.INTERRUPTED, "");
        }
    }

    /// Reads what pi reports until it has settled: it has finished, including any retries and
    /// follow-ups of its own. Tells it to stop when the time is up or the session is ending.
    private Answer follow(Machine.Conversation agent, Instant deadline) throws IOException, InterruptedException {
        String said = "";
        String stopReason = "";
        String error = "";
        Optional<RunOutcome> stoppedBecause = Optional.empty();
        Instant giveUp = Instant.MAX;
        while (true) {
            Instant now = machine.now();
            if (stoppedBecause.isEmpty() && (stopping || now.isAfter(deadline))) {
                stoppedBecause = Optional.of(stopping ? RunOutcome.INTERRUPTED : RunOutcome.TIMED_OUT);
                send(agent, command("abort"));
                giveUp = now.plus(ABORT_TIME);
            }
            if (now.isAfter(giveUp)) {
                // pi did not stop when asked, so it is ended; the next run starts a new one.
                close();
                break;
            }
            Optional<String> line = agent.receive(Duration.ofSeconds(1));
            if (line.isEmpty()) continue;
            Optional<JsonNode> record = Json.parse(line.get());
            if (record.isEmpty()) continue;
            JsonNode event = record.get();
            switch (event.path("type").asText()) {
                case "message_end" -> {
                    JsonNode message = event.path("message");
                    if (message.path("role").asText().equals("assistant")) {
                        String text = text(message.path("content"));
                        if (!text.isBlank()) said = text;
                        stopReason = message.path("stopReason").asText();
                        error = message.path("errorMessage").asText("");
                    }
                }
                case "extension_ui_request" -> declineDialog(agent, event);
                case "agent_settled" -> {
                    return answer(stoppedBecause, stopReason, said, error);
                }
                default -> { }
            }
        }
        return answer(stoppedBecause, stopReason, said, error);
    }

    private static Answer answer(Optional<RunOutcome> stoppedBecause, String stopReason, String said, String error) {
        if (stoppedBecause.isPresent()) return new Answer(stoppedBecause.get(), said);
        if (stopReason.equals("error") || stopReason.equals("aborted"))
            return new Answer(RunOutcome.FAILED, error.isBlank() ? said : said.isBlank() ? error : said + "\n\n" + error);
        return new Answer(RunOutcome.FINISHED, said);
    }

    /// An extension in the sandbox asked a question, such as "allow this command?". Nobody is
    /// there to answer during a run, and pi would wait for the answer forever, so it is declined.
    private void declineDialog(Machine.Conversation agent, JsonNode request) throws IOException {
        String method = request.path("method").asText();
        if (!java.util.Set.of("select", "confirm", "input", "editor").contains(method)) return;
        send(agent, Json.object().put("type", "extension_ui_response")
                .put("id", request.path("id").asText()).put("cancelled", true));
    }

    /// Asks the current run to stop, and every later one not to start: the session is ending.
    void stop() { stopping = true; }

    /// Ends pi, if it runs.
    @Override public void close() {
        pi.ifPresent(Machine.Conversation::close);
        pi = Optional.empty();
    }

    private Machine.Conversation started() {
        if (pi.isPresent() && pi.get().isRunning()) return pi.get();
        close();
        Machine.Conversation started = machine.converse(Machine.Command.of(Ssh.harnessArgv(layout))
                .labelled("pi").shieldedFromSignals());
        pi = Optional.of(started);
        return started;
    }

    private ObjectNode command(String type) {
        commands++;
        return Json.object().put("id", "oillamp-" + commands).put("type", type);
    }

    private static void send(Machine.Conversation agent, ObjectNode command) throws IOException {
        agent.send(command.toString());
    }

    /// Sends a command and waits for pi's reply to it, passing over events that come first.
    private boolean answered(Machine.Conversation agent, ObjectNode command) throws IOException, InterruptedException {
        send(agent, command);
        String id = command.path("id").asText();
        Instant deadline = machine.now().plus(ANSWER_TIME);
        while (machine.now().isBefore(deadline) && !stopping) {
            Optional<String> line = agent.receive(Duration.ofSeconds(1));
            if (line.isEmpty()) continue;
            Optional<JsonNode> record = Json.parse(line.get());
            if (record.isEmpty()) continue;
            if (record.get().path("type").asText().equals("extension_ui_request")) declineDialog(agent, record.get());
            if (record.get().path("type").asText().equals("response") && record.get().path("id").asText().equals(id))
                return record.get().path("success").asBoolean(false);
        }
        return false;
    }

    private Answer failed(String why) {
        close();
        return new Answer(RunOutcome.FAILED, why);
    }

    /// The text of a message's content: a string, or a list of blocks of which only the text ones count.
    private static String text(JsonNode content) {
        if (content.isTextual()) return content.asText();
        StringBuilder text = new StringBuilder();
        for (JsonNode block : content)
            if (block.path("type").asText().equals("text")) text.append(block.path("text").asText());
        return text.toString();
    }
}
