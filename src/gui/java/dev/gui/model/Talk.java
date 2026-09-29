package dev.gui.model;

import sprouts.HasId;
import sprouts.Tuple;

/// One row of the conversations tree under a genie: a whole conversation, or one branch of it.
///
/// pi keeps a conversation as a tree. Asking something else instead of an earlier question
/// leaves the old question, and everything after it, as a branch of its own. The tree shows
/// only where that happened: a [Branch] is a run of questions with no alternatives, and its
/// [Branch#forks()] are the alternatives where it ends.
///
/// Every row is made from a [Conversation] and where the genie is in it, by
/// [Conversation#talk(Conversations.Here)]; nothing here is kept on its own.
public sealed interface Talk extends HasId<String> {

    /// What the row reads.
    String title();

    /// Whether the genie is here: in this conversation, or on this branch of it.
    boolean here();

    /// pi's id for the entry the genie continues from when the user goes to this row, or nothing
    /// for wherever pi left it.
    String leaf();

    /// A whole conversation, which is one of pi's session files.
    ///
    /// Most conversations start with one question. The row of the conversation then stands for
    /// the run of questions it starts with too, since both would read the same, and `branches`
    /// are the alternatives where that run ends. A conversation whose first question was asked
    /// differently starts with several branches instead.
    ///
    /// @param id       pi's id for the session
    /// @param title    its name, or its first question
    /// @param turns    how many questions it starts with before it forks, or 0 when it starts
    ///                 with several branches
    /// @param leaf     the last entry of the run it starts with, or nothing when it starts with
    ///                 several branches
    /// @param branches the alternatives
    record Chat(String id, String title, int turns, String leaf, boolean here, Tuple<Branch> branches)
            implements Talk {}

    /// A run of questions and answers in which nothing was asked differently.
    ///
    /// @param id    pi's id for its first question
    /// @param title its first question, on one line
    /// @param turns how many questions it holds
    /// @param leaf  pi's id for its last entry: where the genie continues when the user goes here
    /// @param forks the alternatives at its end, if anything was asked differently there
    record Branch(String id, String title, int turns, String leaf, boolean here, Tuple<Branch> forks)
            implements Talk {}
}
