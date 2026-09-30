package dev.gui.model;

import java.util.UUID;

import dev.gui.pi.PiEvent;

import sprouts.HasId;
import sprouts.Tuple;

/// One genie: an agent that lives in a lamp of its own, and the conversation with it.
///
/// @param id        stable for the genie's life; its lamp directory is named after it
/// @param name      what the user calls it
/// @param phase     whether it is asleep, waking, ready, working or broken
/// @param activity  what is happening right now, in a few words, such as the lamp's last report
/// @param transcript the conversation
/// @param draft     what the user is typing to it
/// @param tokens    how many tokens its model calls have counted since the app started
/// @param handouts  the files in its outbox, which the user can save
/// @param desktopShown whether its desktop is shown next to the chat
/// @param conversations every conversation with it, and which one the chat shows
/// @param schedule  the jobs that wake it, as its schedule page shows them
public record Genie(UUID id, String name, Phase phase, String activity, Transcript transcript,
                    String draft, int tokens, Tuple<Handout> handouts, boolean desktopShown,
                    Conversations conversations, Schedule schedule)
        implements HasId<UUID> {

    public enum Phase {
        /// Its lamp is not running. Its home and its conversation are kept on disk.
        ASLEEP,
        /// Its lamp is starting; the first time, the sandbox image is built, which takes minutes.
        WAKING,
        /// It waits for the user.
        READY,
        /// It is answering.
        WORKING,
        /// Its lamp or its harness failed; [Genie#activity()] says why.
        BROKEN;

        public boolean isAwake() { return this == READY || this == WORKING; }
    }

    public static Genie named(String name) {
        return asleep(UUID.randomUUID(), name);
    }

    public static Genie asleep(UUID id, String name) {
        return new Genie(id, name, Phase.ASLEEP, "asleep", Transcript.empty(), "", 0,
                         Tuple.of(Handout.class), false, Conversations.NONE, Schedule.unread(java.time.ZoneId.systemDefault()));
    }

    public Genie withName(String name)             { return new Genie(id, name, phase, activity, transcript, draft, tokens, handouts, desktopShown, conversations, schedule); }
    public Genie withPhase(Phase phase)            { return new Genie(id, name, phase, activity, transcript, draft, tokens, handouts, desktopShown, conversations, schedule); }
    public Genie withActivity(String activity)     { return new Genie(id, name, phase, activity, transcript, draft, tokens, handouts, desktopShown, conversations, schedule); }
    public Genie withTranscript(Transcript transcript) { return new Genie(id, name, phase, activity, transcript, draft, tokens, handouts, desktopShown, conversations, schedule); }
    public Genie withDraft(String draft)           { return new Genie(id, name, phase, activity, transcript, draft, tokens, handouts, desktopShown, conversations, schedule); }
    public Genie withTokens(int tokens)            { return new Genie(id, name, phase, activity, transcript, draft, tokens, handouts, desktopShown, conversations, schedule); }
    public Genie withHandouts(Tuple<Handout> handouts) { return new Genie(id, name, phase, activity, transcript, draft, tokens, handouts, desktopShown, conversations, schedule); }
    public Genie withDesktopShown(boolean shown)   { return new Genie(id, name, phase, activity, transcript, draft, tokens, handouts, shown, conversations, schedule); }
    public Genie withConversations(Conversations conversations) { return new Genie(id, name, phase, activity, transcript, draft, tokens, handouts, desktopShown, conversations, schedule); }
    public Genie withSchedule(Schedule schedule)   { return new Genie(id, name, phase, activity, transcript, draft, tokens, handouts, desktopShown, conversations, schedule); }

    // ─── its lamp ──────────────────────────────────────────────────────────────────────────

    public Genie waking() {
        return withPhase(Phase.WAKING).withActivity("lighting the lamp");
    }

    /// Its lamp reported progress while waking, such as building the image.
    public Genie lampSays(String what) {
        return phase == Phase.WAKING ? withActivity(what) : this;
    }

    /// Waking is done. A genie that broke while waking, because its harness died say, stays
    /// broken: the news of that may arrive before the news that waking finished.
    public Genie awake() {
        return phase == Phase.WAKING ? withPhase(Phase.READY).withActivity("ready") : this;
    }

    public Genie asleep() {
        return withPhase(Phase.ASLEEP).withActivity("asleep")
                .withTranscript(transcript.settled()).withDesktopShown(false);
    }

    public Genie broken(String why) {
        return withPhase(Phase.BROKEN).withActivity(why).withDesktopShown(false)
                .withTranscript(transcript.settled().problem(why));
    }

    // ─── the conversation ──────────────────────────────────────────────────────────────────

    public boolean canSend() { return phase.isAwake() && !draft.isBlank(); }

    /// The draft becomes the user's next message, and the genie is working on it. Whoever calls
    /// this sends the draft, as it was before, to the genie's harness.
    public Genie send() {
        if (!canSend()) return this;
        return withTranscript(transcript.you(draft.strip())).withDraft("")
                .withPhase(Phase.WORKING).withActivity("thinking");
    }

    /// Whether the user may ask one of their questions differently now: while the genie waits,
    /// and only a question pi has given an id.
    public boolean canAskInstead(Entry question) {
        return phase == Phase.READY && question.kind() == Entry.Kind.YOU && !question.ref().isEmpty();
    }

    /// The user asks `text` instead of their question `id`. The chat forgets what followed it,
    /// which pi keeps as a branch of its own, and the genie works on the new question. Whoever
    /// calls this tells the genie's harness.
    public Genie askInstead(String id, String text) {
        if (phase != Phase.READY || text.isBlank()) return this;
        return withTranscript(transcript.askedInstead(id, text.strip()))
                .withPhase(Phase.WORKING).withActivity("thinking");
    }

    public Genie hear(PiEvent event) {
        Genie heard = withTranscript(transcript.hear(event));
        return switch (event) {
            case PiEvent.Answered answered -> heard.withTokens(tokens + answered.tokens());
            case PiEvent.ToolStarted tool  -> heard.withActivity("using " + tool.tool());
            case PiEvent.Said ignored      -> heard.withActivity("writing");
            case PiEvent.Thinking ignored  -> heard.withActivity("thinking");
            case PiEvent.Settled ignored   -> phase.isAwake() ? heard.withPhase(Phase.READY).withActivity("ready") : heard;
            case PiEvent.History history   -> heard.withConversations(conversations.withHere(
                    new Conversations.Here(conversations.here().file(), history.leaf())));
            case PiEvent.Opened opened     -> heard.withConversations(conversations.withHere(
                    new Conversations.Here(Conversations.inHome(opened.file()), conversations.here().leaf())));
            // A question pi could not take is not being worked on, and nothing else will say so.
            case PiEvent.Refused refused when phase == Phase.WORKING
                    && (refused.command().equals("prompt") || refused.command().equals("send_user_message"))
                                           -> heard.withPhase(Phase.READY).withActivity("ready");
            default                        -> heard;
        };
    }

    /// What pi sent after an answer: the chat learns pi's ids for the questions in it, and the
    /// genie where it now is. Unlike [#hear], the chat is not replaced.
    public Genie learn(PiEvent.History history) {
        return withTranscript(transcript.learn(history)).withConversations(conversations.withHere(
                new Conversations.Here(conversations.here().file(), history.leaf())));
    }

    /// What is in the outbox now. A file that was not there before is announced in the chat;
    /// [#withHandouts] takes a listing without announcing anything, as when the genie wakes.
    public Genie outbox(Tuple<Handout> now) {
        Transcript told = transcript;
        for (Handout file : now)
            if (!handouts.contains(file)) told = told.handedOver(file);
        return withHandouts(now).withTranscript(told);
    }

    public Genie gave(String fileName) {
        return withTranscript(transcript.gave(fileName));
    }
}
