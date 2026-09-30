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
public record Conversations(Tuple<Conversation> all, Here here, Fold chatsFold, Fold jobsFold) {

    /// Whether a tree is open under the genie, and how tall its area is; the user drags the
    /// area's lower edge to change that. Trees start closed.
    ///
    /// @param height the most the tree's area takes, in the window's units; a shorter tree
    ///               takes less
    public record Fold(boolean shown, int height) {
        public static final int LOWEST = 60;
        public static final int HIGHEST = 900;
        public static final Fold CLOSED = new Fold(false, 180);

        public Fold toggled()                 { return new Fold(!shown, height); }
        public Fold withHeight(int height)    { return new Fold(shown, Math.clamp(height, LOWEST, HIGHEST)); }
    }

    /// A place in a genie's conversations.
    ///
    /// @param file the session file, relative to the genie's home, or nothing while unknown
    /// @param leaf pi's id for the entry the next question will follow, or nothing while unknown
    public record Here(String file, String leaf) {
        public static final Here UNKNOWN = new Here("", "");
    }

    public static final Conversations NONE = new Conversations(Tuple.of(Conversation.class), Here.UNKNOWN);

    public Conversations(Tuple<Conversation> all, Here here) { this(all, here, Fold.CLOSED, Fold.CLOSED); }

    /// Where pi keeps a genie's conversations, relative to its home. pi names the directory
    /// after the directory it runs in, which for a genie is its home, `/home/agent`.
    public static final String DIRECTORY = ".pi/agent/sessions/--home-agent--";

    /// The genie's home inside its sandbox.
    public static final String HOME = "/home/agent/";

    public Conversations withAll(Tuple<Conversation> all) { return new Conversations(all, here, chatsFold, jobsFold); }
    public Conversations withHere(Here here)              { return new Conversations(all, here, chatsFold, jobsFold); }
    public Conversations withChatsFold(Fold chatsFold)    { return new Conversations(all, here, chatsFold, jobsFold); }
    public Conversations withJobsFold(Fold jobsFold)      { return new Conversations(all, here, chatsFold, jobsFold); }

    /// How many conversations the user had, and how many the jobs' runs had.
    public int chatCount() { return all.retainIf(it -> !it.byJob()).size(); }
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
    /// is shown as a row of its own at the top, so the user sees where they are.
    public Tuple<Talk> chats() {
        Tuple<Talk> rows = all.retainIf(it -> !it.byJob()).mapTo(Talk.class, conversation -> conversation.talk(here));
        if (!here.file().isEmpty() && current().isEmpty())
            rows = rows.addAt(0, new Talk.Chat(here.file(), "New conversation", 0, "", true, Tuple.of(Talk.Branch.class)));
        return rows;
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
