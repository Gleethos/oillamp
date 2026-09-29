package dev.gui.model;

import java.util.Optional;

import sprouts.Tuple;

/// Every conversation with one genie, and where the genie is among them.
///
/// @param all  the conversations, newest first, as read from the genie's home
/// @param here where the genie is: the conversation pi has open, and the entry it continues from
/// @param shown whether the tree of them is open under the genie; it starts closed
public record Conversations(Tuple<Conversation> all, Here here, boolean shown) {

    /// A place in a genie's conversations.
    ///
    /// @param file the session file, relative to the genie's home, or nothing while unknown
    /// @param leaf pi's id for the entry the next question will follow, or nothing while unknown
    public record Here(String file, String leaf) {
        public static final Here UNKNOWN = new Here("", "");
    }

    public static final Conversations NONE = new Conversations(Tuple.of(Conversation.class), Here.UNKNOWN, false);

    public Conversations(Tuple<Conversation> all, Here here) { this(all, here, false); }

    /// Where pi keeps a genie's conversations, relative to its home. pi names the directory
    /// after the directory it runs in, which for a genie is its home, `/home/agent`.
    public static final String DIRECTORY = ".pi/agent/sessions/--home-agent--";

    /// The genie's home inside its sandbox.
    public static final String HOME = "/home/agent/";

    public Conversations withAll(Tuple<Conversation> all) { return new Conversations(all, here, shown); }
    public Conversations withHere(Here here)              { return new Conversations(all, here, shown); }
    public Conversations withShown(boolean shown)         { return new Conversations(all, here, shown); }

    public Optional<Conversation> find(String id) {
        for (Conversation conversation : all) if (conversation.id().equals(id)) return Optional.of(conversation);
        return Optional.empty();
    }

    /// The one pi has open, once it is on disk.
    public Optional<Conversation> current() {
        for (Conversation conversation : all) if (conversation.file().equals(here.file())) return Optional.of(conversation);
        return Optional.empty();
    }

    /// The tree under the genie: a row for every conversation, with its branches below it.
    ///
    /// pi writes a new conversation to disk only once something was said in it. Until then, it
    /// is shown as a row of its own at the top, so the user sees where they are.
    public Tuple<Talk> tree() {
        Tuple<Talk> rows = all.mapTo(Talk.class, conversation -> conversation.talk(here));
        if (!here.file().isEmpty() && current().isEmpty())
            rows = rows.addAt(0, new Talk.Chat(here.file(), "New conversation", 0, "", true, Tuple.of(Talk.Branch.class)));
        return rows;
    }

    /// The ids leading to the row where the genie is: its conversation, and the branches down to
    /// the one it is on. Nothing when that is not known.
    public Tuple<String> herePath() {
        for (Talk row : tree())
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
