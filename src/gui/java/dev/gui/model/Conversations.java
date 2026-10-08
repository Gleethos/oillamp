package dev.gui.model;

import java.util.Optional;

import sprouts.Tuple;

/// Every conversation with one genie, and where the genie is among them.
///
/// Under the genie they are two trees: the conversations the user had, and those the runs of its
/// scheduled jobs had.
///
/// @param all   the conversations, newest first, as read from the genie's home
/// @param here  where the genie is: the conversation pi has open, and the entry it continues from
/// @param chatsFold how the tree of the user's conversations is shown
/// @param jobsFold  how the tree of the jobs' conversations is shown
/// @param folded    whether the user folded both trees away into the genie's card, which they
///                  can only while the genie is not awake; awake, the trees are always there
/// @param aside     the conversation the genie is answering in, while the chat shows another
public record Conversations(Tuple<Conversation> all, Here here, Fold chatsFold, Fold jobsFold, boolean folded, Optional<Aside> aside) {

    /// The conversation the genie is answering the user in, put aside while the chat shows
    /// another one, and still growing with the answer. The chat takes it back when it goes there.
    ///
    /// @param here       where in it the genie is; its file is empty until pi has named it
    /// @param transcript what the chat would show there now
    public record Aside(Here here, Transcript transcript) {}

    /// A place in a genie's conversations.
    ///
    /// @param file the session file, relative to the genie's home, or nothing while unknown
    /// @param leaf pi's id for the entry the next question will follow, or nothing while unknown
    public record Here(String file, String leaf) {
        public static final Here UNKNOWN = new Here("", "");
    }

    public static final Conversations NONE = new Conversations(Tuple.of(Conversation.class), Here.UNKNOWN);

    public Conversations(Tuple<Conversation> all, Here here) { this(all, here, Fold.CLOSED, Fold.CLOSED, false, Optional.empty()); }

    /// Where pi keeps a genie's conversations, relative to its home. pi names the directory
    /// after the directory it runs in, which for a genie is its home, `/home/agent`.
    public static final String DIRECTORY = ".pi/agent/sessions/--home-agent--";

    /// The genie's home inside its sandbox.
    public static final String HOME = "/home/agent/";

    public Conversations withAll(Tuple<Conversation> all) { return new Conversations(all, here, chatsFold, jobsFold, folded, aside); }
    public Conversations withHere(Here here)              { return new Conversations(all, here, chatsFold, jobsFold, folded, aside); }
    public Conversations withChatsFold(Fold chatsFold)    { return new Conversations(all, here, chatsFold, jobsFold, folded, aside); }
    public Conversations withJobsFold(Fold jobsFold)      { return new Conversations(all, here, chatsFold, jobsFold, folded, aside); }
    public Conversations withFolded(boolean folded)       { return new Conversations(all, here, chatsFold, jobsFold, folded, aside); }
    public Conversations withAside(Optional<Aside> aside) { return new Conversations(all, here, chatsFold, jobsFold, folded, aside); }

    /// How many conversations the user had, a new one not yet on disk among them, and how many
    /// the jobs' runs had.
    public int chatCount() { return chats().size(); }
    public int jobCount()  { return all.retainIf(Conversation::byJob).size(); }

    public Optional<Conversation> find(String id) {
        for (Conversation conversation : all) if (conversation.id().equals(id)) return Optional.of(conversation);
        return Optional.empty();
    }

    /// The one pi has open, once it is on disk.
    public Optional<Conversation> current() {
        for (Conversation conversation : all) if (conversation.file().equals(here.file())) return Optional.of(conversation);
        return Optional.empty();
    }

    /// The tree of the user's conversations: a row for each, with its branches below it.
    ///
    /// pi writes a new conversation to disk only once something was said in it. Until then, it
    /// is shown as a row of its own at the top, so the user sees where they are, or, while the
    /// genie answers in it and the chat shows another, can go back to it.
    public Tuple<Talk> chats() {
        Tuple<Talk> rows = all.retainIf(it -> !it.byJob()).mapTo(Talk.class, conversation -> conversation.talk(here));
        if (!here.file().isEmpty() && current().isEmpty())
            rows = rows.addAt(0, new Talk.Chat(here.file(), "New conversation", 0, "", true, Tuple.of(Talk.Branch.class)));
        if (aside.isPresent() && fileOf(ASIDE).isPresent())
            rows = rows.addAt(0, new Talk.Chat(ASIDE, "New conversation", 0, "", false, Tuple.of(Talk.Branch.class)));
        return rows;
    }

    /// The id of the row of a new conversation the genie answers in while the chat shows another.
    public static final String ASIDE = "new conversation, answering";

    /// The file of the conversation a row of the trees stands for, relative to the genie's home;
    /// empty for a new one.
    ///
    /// @param row the row's id: pi's id for the conversation, or [#ASIDE]
    public Optional<String> fileOf(String row) {
        Optional<String> file = find(row).map(Conversation::file);
        if (file.isPresent() || !row.equals(ASIDE)) return file;
        return aside.map(it -> it.here().file())
                    .filter(asideFile -> all.retainIf(it -> it.file().equals(asideFile)).isEmpty());
    }

    /// The tree of the conversations the runs of the genie's scheduled jobs had.
    public Tuple<Talk> jobRuns() {
        return all.retainIf(Conversation::byJob).mapTo(Talk.class, conversation -> conversation.talk(here));
    }

    /// The ids leading to the row where the genie is, in whichever tree it is: its conversation,
    /// and the branches down to the one it is on. Nothing when that is not known.
    public Tuple<String> herePath() {
        Tuple<String> inChats = pathToHere(chats());
        return inChats.isEmpty() ? pathToHere(jobRuns()) : inChats;
    }

    /// The ids leading to the row of `rows` where the genie is, or nothing when it is in none.
    public static Tuple<String> pathToHere(Tuple<Talk> rows) {
        for (Talk row : rows)
            if (row instanceof Talk.Chat chat && chat.here())
                return pathTo(chat.branches(), Tuple.of(String.class, chat.id()));
        return Tuple.of(String.class);
    }

    /// Where a session file, as pi names it inside the sandbox, is relative to the genie's home.
    public static String inHome(String sandboxPath) {
        return sandboxPath.startsWith(HOME) ? sandboxPath.substring(HOME.length()) : sandboxPath;
    }

    /// `above`, followed by the ids down to the branch among `level` where the genie is, or just
    /// `above` when it is on none of them.
    private static Tuple<String> pathTo(Tuple<Talk.Branch> level, Tuple<String> above) {
        for (Talk.Branch branch : level) {
            if (branch.here()) return above.add(branch.id());
            Tuple<String> deeper = pathTo(branch.forks(), above.add(branch.id()));
            if (deeper.size() > above.size() + 1) return deeper;
        }
        return above;
    }
}
