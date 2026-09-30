package dev.gui.pi;

import sprouts.Tuple;

/// What the chat is told while a genie works. `GenieRunner` makes these from the lamp's run
/// events and conversations.
public sealed interface PiEvent {

    /// The genie's answer grew by `text`, while it is still being written.
    record Said(String text) implements PiEvent {}

    /// The genie is thinking before it answers, and its thoughts grew by `more`. Models that
    /// think without sharing their thoughts send nothing more than an empty start.
    record Thinking(String more) implements PiEvent {}

    /// The genie started using a tool, such as running a command in its shell.
    ///
    /// @param call    pi's id for this use of the tool, repeated in [ToolFinished]
    /// @param tool    the tool's name, such as `bash` or `write`
    /// @param summary what it does, in one line, such as the command being run
    record ToolStarted(String call, String tool, String summary) implements PiEvent {}

    /// A tool the genie used finished.
    ///
    /// @param output what the tool printed, shortened to what fits in the chat
    record ToolFinished(String call, boolean failed, String output) implements PiEvent {}

    /// One answer of the genie is complete.
    ///
    /// @param text   the whole answer, as pi finally recorded it
    /// @param failed why the model could not answer, such as a refused key, or empty
    /// @param tokens how many tokens the model counted for this answer, in and out
    record Answered(String text, String failed, int tokens) implements PiEvent {}

    /// The model call failed and pi tries again shortly.
    record Retrying(int attempt, int attempts, String reason) implements PiEvent {}

    /// The genie is done with the last prompt and waits for the next one.
    record Settled() implements PiEvent {}

    /// pi refused a command, such as a prompt it could not accept.
    record Refused(String command, String reason) implements PiEvent {}

    /// The questions and answers from the start of a conversation to the entry the chat shows,
    /// leaving out the branches it is not on.
    ///
    /// @param leaf pi's id for the entry the next question will follow, or nothing in a
    ///             conversation with nothing in it yet
    record History(Tuple<Line> lines, String leaf) implements PiEvent {

        /// One message of the conversation.
        ///
        /// @param fromUser true for what the user wrote, false for the genie's answers
        /// @param id       pi's id for the message, by which it can be asked differently
        public record Line(boolean fromUser, String text, String id) {}
    }

    /// The chat shows this conversation.
    ///
    /// @param file its session file, as the sandbox names it
    record Opened(String file) implements PiEvent {}
}
