package dev.gui.genie;

import java.util.Optional;

import dev.gui.model.Conversation;
import dev.gui.pi.PiEvent;
import dev.lamp.Lamp;
import dev.lamp.LampEvent;

import sprouts.Tuple;

/// Turns what the Lamp API reports into the values the chat and the tree are drawn from.
final class LampTalk {

    private LampTalk() {}

    /// A conversation as the tree of conversations shows it: only which entries are questions.
    static Conversation conversation(Lamp.Conversation conversation) {
        Tuple<Conversation.Step> steps = Tuple.of(Conversation.Step.class);
        for (Lamp.Conversation.Entry entry : conversation.entries()) {
            boolean asked = entry.kind() == Lamp.Conversation.Kind.MESSAGE_TO_AGENT;
            steps = steps.add(new Conversation.Step(entry.id(), entry.parent().orElse(""), asked, asked ? entry.text() : ""));
        }
        return new Conversation(conversation.id(), conversation.file(), conversation.name(),
                conversation.modified().toString(), steps);
    }

    /// The questions and answers from the start of `conversation` to `leaf`, as the chat shows them.
    static PiEvent.History history(Lamp.Conversation conversation, String leaf) {
        Tuple<PiEvent.History.Line> lines = Tuple.of(PiEvent.History.Line.class);
        for (Lamp.Conversation.Entry entry : conversation.lineTo(leaf)) {
            boolean asked = entry.kind() == Lamp.Conversation.Kind.MESSAGE_TO_AGENT;
            if ((asked || entry.kind() == Lamp.Conversation.Kind.MESSAGE_FROM_AGENT) && !entry.text().isBlank())
                lines = lines.add(new PiEvent.History.Line(asked, entry.text(), entry.id()));
        }
        return new PiEvent.History(lines, leaf);
    }

    /// The chat's event for a step of a run; empty for [LampEvent.Progress.Opened], which the
    /// runner handles itself.
    static Optional<PiEvent> heard(LampEvent.Progress progress) {
        return switch (progress) {
            case LampEvent.Progress.Opened ignored -> Optional.empty();
            case LampEvent.Progress.Said said -> Optional.of(new PiEvent.Said(said.text()));
            case LampEvent.Progress.Thought thought -> Optional.of(new PiEvent.Thinking(thought.text()));
            case LampEvent.Progress.Answered answered -> Optional.of(new PiEvent.Answered(
                    answered.failed() ? "" : answered.text(), answered.failed() ? answered.text() : "", 0));
            case LampEvent.Progress.ToolStarted tool -> Optional.of(new PiEvent.ToolStarted(tool.call(), tool.tool(), tool.summary()));
            case LampEvent.Progress.ToolFinished tool -> Optional.of(new PiEvent.ToolFinished(tool.call(), tool.failed(), tool.output()));
            case LampEvent.Progress.Retrying retrying -> Optional.of(new PiEvent.Retrying(retrying.attempt(), retrying.most(), retrying.why()));
        };
    }
}
