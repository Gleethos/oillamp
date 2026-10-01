package dev.oillamp;

import java.io.EOFException;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.lamp.LampEvent;
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
    /// Set by [#cancel] to stop the run in progress, and cleared as each run begins.
    private volatile boolean cancelled;

    /// How much of a tool's output is passed on as progress. The agent saw all of it.
    private static final int OUTPUT_SHOWN = 4_000;

    Harness(Machine machine, LampLayout layout) {
        this.machine = machine;
        this.layout = layout;
    }

    /// How a run ended, the agent's last message, and the conversation it happened in.
    ///
    /// @param conversation pi's id for the conversation, when pi said which it was
    record Answer(RunOutcome outcome, String text, Optional<String> conversation) {
        Answer(RunOutcome outcome, String text) { this(outcome, text, Optional.empty()); }
        Answer in(Optional<String> conversation) { return new Answer(outcome, text, conversation); }
    }

    /// Where a question goes, when it is not a new conversation.
    ///
    /// @param file   the conversation's file, relative to the agent's home
    /// @param moveTo the entry to move to first: after an answer, the question continues after it;
    ///               at a question, it is asked instead of that question. Empty to continue where
    ///               the conversation stands
    record Target(String file, Optional<String> moveTo) {
        Target {
            if (!file.startsWith(".pi/agent/sessions/") || file.contains("..") || file.contains("\n"))
                throw new IllegalArgumentException("not one of pi's conversations: " + file);
            if (moveTo.filter(id -> !id.matches("[A-Za-z0-9-]{1,64}")).isPresent())
                throw new IllegalArgumentException("not an entry: " + moveTo.get());
        }
    }

    /// Gives pi `prompt`, in a new conversation or where `target` says, and waits until it is
    /// done, `limit` has passed, or [#stop] was called. Called by one thread at a time.
    ///
    /// @param name what a new conversation is called in pi's list, such as `run-12 (job-3)`; empty
    ///             to leave it to be known by its first question
    /// @param progress told what the agent does, as it does it
    Answer run(Optional<String> name, String prompt, Duration limit, Optional<Target> target,
               Consumer<LampEvent.Progress> progress) {
        cancelled = false;
        if (stopping) return new Answer(RunOutcome.INTERRUPTED, "the session was ending, so the agent was not woken");
        try {
            Machine.Conversation agent = started();
            if (target.isEmpty()) {
                if (!accepted(answer(agent, command("new_session"))))
                    return failed("pi would not start a new conversation");
                if (name.isPresent()) send(agent, command("set_session_name").put("name", name.get()));
            } else {
                if (!accepted(answer(agent, command("switch_session").put("sessionPath", "/home/agent/" + target.get().file()))))
                    return failed("pi could not open the conversation " + target.get().file());
                if (target.get().moveTo().isPresent()) {
                    Optional<String> couldNot = moveTo(agent, target.get().moveTo().get());
                    if (couldNot.isPresent()) return failed("pi could not go to entry " + target.get().moveTo().get() + ": " + couldNot.get());
                }
            }
            Optional<String> conversation = answer(agent, command("get_state"))
                    .map(state -> state.path("data").path("sessionId").asText("")).filter(id -> !id.isEmpty());
            conversation.ifPresent(id -> progress.accept(new LampEvent.Progress.Opened(id)));
            if (cancelled) return new Answer(RunOutcome.CANCELLED, "").in(conversation);
            ObjectNode ask = command("prompt").put("message", prompt);
            if (!accepted(answer(agent, ask))) return failed("pi did not accept the prompt").in(conversation);
            return follow(agent, machine.now().plus(limit), progress).in(conversation);
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
    private Answer follow(Machine.Conversation agent, Instant deadline,
                          Consumer<LampEvent.Progress> progress) throws IOException, InterruptedException {
        String said = "";
        String stopReason = "";
        String error = "";
        Optional<RunOutcome> stoppedBecause = Optional.empty();
        Instant giveUp = Instant.MAX;
        while (true) {
            Instant now = machine.now();
            if (stoppedBecause.isEmpty() && (stopping || cancelled || now.isAfter(deadline))) {
                stoppedBecause = Optional.of(stopping ? RunOutcome.INTERRUPTED
                                           : cancelled ? RunOutcome.CANCELLED : RunOutcome.TIMED_OUT);
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
            Optional<JsonNode> record = JsonUtil.parse(line.get());
            if (record.isEmpty()) continue;
            JsonNode event = record.get();
            switch (event.path("type").asText()) {
                case "message_update" -> {
                    JsonNode update = event.path("assistantMessageEvent");
                    switch (update.path("type").asText()) {
                        case "text_delta" -> progress.accept(new LampEvent.Progress.Said(update.path("delta").asText()));
                        case "thinking_delta" -> progress.accept(new LampEvent.Progress.Thought(update.path("delta").asText()));
                        default -> { }
                    }
                }
                case "message_end" -> {
                    JsonNode message = event.path("message");
                    if (message.path("role").asText().equals("assistant")) {
                        String text = text(message.path("content"));
                        if (!text.isBlank()) said = text;
                        stopReason = message.path("stopReason").asText();
                        error = message.path("errorMessage").asText("");
                        boolean failed = stopReason.equals("error") || stopReason.equals("aborted");
                        progress.accept(new LampEvent.Progress.Answered(failed && !error.isBlank() ? error : text, failed));
                    }
                }
                case "tool_execution_start" -> progress.accept(new LampEvent.Progress.ToolStarted(
                        event.path("toolCallId").asText(), event.path("toolName").asText(), summary(event.path("args"))));
                case "tool_execution_end" -> {
                    String output = text(event.path("result").path("content"));
                    progress.accept(new LampEvent.Progress.ToolFinished(event.path("toolCallId").asText(),
                            event.path("isError").asBoolean(false),
                            output.length() <= OUTPUT_SHOWN ? output : output.substring(0, OUTPUT_SHOWN) + "\n[…]"));
                }
                case "auto_retry_start" -> progress.accept(new LampEvent.Progress.Retrying(
                        event.path("attempt").asInt(), event.path("maxAttempts").asInt(), event.path("errorMessage").asText()));
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
        if (!Set.of("select", "confirm", "input", "editor").contains(method)) return;
        send(agent, JsonUtil.object().put("type", "extension_ui_response")
                .put("id", request.path("id").asText()).put("cancelled", true));
    }

    /// Asks the current run to stop, and every later one not to start: the session is ending.
    void stop() { stopping = true; }

    /// Asks the current run to stop, because someone cancelled it. Later runs go ahead.
    void cancel() { cancelled = true; }

    /// One line saying what a tool call does: the command for `bash`, the file for the file tools,
    /// and the arguments as JSON for anything else.
    private static String summary(JsonNode arguments) {
        for (String field : new String[] {"command", "path", "file_path", "pattern", "url"})
            if (arguments.path(field).isTextual()) return oneLine(arguments.path(field).asText());
        return oneLine(arguments.isMissingNode() || arguments.isNull() ? "" : arguments.toString());
    }

    private static String oneLine(String text) {
        String line = text.strip().replaceAll("\\s*\\n\\s*", " ⏎ ");
        return line.length() <= 160 ? line : line.substring(0, 159) + "…";
    }

    /// Ends pi, if it runs.
    @Override public void close() {
        pi.ifPresent(Machine.Conversation::close);
        pi = Optional.empty();
    }

    private Machine.Conversation started() {
        if (pi.isPresent() && pi.get().isRunning()) return pi.get();
        close();
        Machine.Conversation started = machine.converse(Machine.Command.of(SandboxSshUtil.harnessArgv(layout))
                .labelled("pi").shieldedFromSignals());
        pi = Optional.of(started);
        return started;
    }

    private ObjectNode command(String type) {
        commands++;
        return JsonUtil.object().put("id", "oillamp-" + commands).put("type", type);
    }

    private static void send(Machine.Conversation agent, ObjectNode command) throws IOException {
        agent.send(command.toString());
    }

    /// Sends a command and waits for pi's reply to it, passing over events that come first.
    ///
    /// @return the reply, or empty when none came in time
    private Optional<JsonNode> answer(Machine.Conversation agent, ObjectNode command) throws IOException, InterruptedException {
        send(agent, command);
        String id = command.path("id").asText();
        Instant deadline = machine.now().plus(ANSWER_TIME);
        while (machine.now().isBefore(deadline) && !stopping) {
            Optional<String> line = agent.receive(Duration.ofSeconds(1));
            if (line.isEmpty()) continue;
            Optional<JsonNode> record = JsonUtil.parse(line.get());
            if (record.isEmpty()) continue;
            if (record.get().path("type").asText().equals("extension_ui_request")) declineDialog(agent, record.get());
            if (record.get().path("type").asText().equals("response") && record.get().path("id").asText().equals(id))
                return record;
        }
        return Optional.empty();
    }

    /// Whether pi carried a command out: it said it succeeded, and no extension cancelled it.
    private static boolean accepted(Optional<JsonNode> reply) {
        return reply.filter(r -> r.path("success").asBoolean(false) && !r.path("data").path("cancelled").asBoolean(false))
                    .isPresent();
    }

    /// Moves within the open conversation to `entry`, through oillamp's extension in the sandbox,
    /// which says when it has moved.
    ///
    /// The extension must be there: without it, pi would take the command for a question and
    /// send it to the model.
    ///
    /// @return why it could not, or empty once it has moved
    private Optional<String> moveTo(Machine.Conversation agent, String entry) throws IOException, InterruptedException {
        Optional<JsonNode> commands = answer(agent, command("get_commands"));
        boolean known = false;
        for (JsonNode command : commands.map(reply -> reply.path("data").path("commands")).orElse(JsonUtil.object()))
            known |= command.path("name").asText().equals(MOVE);
        if (!known) return Optional.of("pi does not have oillamp's extension; start the session again to add it");
        send(agent, command("prompt").put("message", "/" + MOVE + " " + entry));
        Instant deadline = machine.now().plus(ANSWER_TIME);
        while (machine.now().isBefore(deadline) && !stopping) {
            Optional<String> line = agent.receive(Duration.ofSeconds(1));
            if (line.isEmpty()) continue;
            Optional<JsonNode> record = JsonUtil.parse(line.get());
            if (record.isEmpty() || !record.get().path("type").asText().equals("extension_ui_request")) continue;
            String message = record.get().path("message").asText();
            if (message.equals("oillamp: moved")) return Optional.empty();
            if (message.startsWith(COULD_NOT_MOVE)) return Optional.of(message.substring(COULD_NOT_MOVE.length()));
            declineDialog(agent, record.get());
        }
        return Optional.of("pi did not say it had moved");
    }

    /// The command of oillamp's extension that moves within a conversation, and how it says it could not.
    private static final String MOVE = "oillamp-goto";
    private static final String COULD_NOT_MOVE = "oillamp: could not move: ";

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
