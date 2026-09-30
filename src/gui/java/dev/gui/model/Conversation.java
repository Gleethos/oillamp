package dev.gui.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import sprouts.HasId;
import sprouts.Tuple;

/// One conversation with a genie, as pi keeps it: a session file in the genie's home, holding a
/// tree of entries.
///
/// Every entry points to the one before it. Most of the time that makes a line: a question, its
/// answer, the next question. When the user asks something else instead of an earlier question,
/// the new question points to the same entry the old one did, and the line forks there. pi keeps
/// both sides, and so does this.
///
/// Only what the tree of conversations needs is kept, not what was said: the chat asks pi for
/// that when the genie is awake.
///
/// @param id       pi's id for the session
/// @param file     the session file, relative to the genie's home
/// @param name     the name the conversation was given, or nothing
/// @param modified when its last entry was written, as pi writes times, so that it sorts
/// @param steps    its entries, in the order pi wrote them
/// @param job      the job whose run had it, such as `job-3`, or nothing for one the user began
public record Conversation(String id, String file, String name, String modified, Tuple<Step> steps, String job)
        implements HasId<String> {

    /// Whether a job's run had it, rather than the user.
    public boolean byJob() { return !job.isEmpty(); }

    /// One entry of a conversation.
    ///
    /// @param parent the entry it follows, or nothing for the first
    /// @param asked  whether it is a question of the user's; everything else, such as an answer,
    ///               a tool's result or a change of model, is not
    /// @param text   the question, or nothing
    public record Step(String id, String parent, boolean asked, String text) {}

    /// What a row of a conversation shows at most, in characters.
    static final int TITLE_LENGTH = 60;

    /// Its name, or its first question.
    public String title() {
        if (!name.isBlank()) return oneLine(name);
        for (Step step : steps) if (step.asked()) return oneLine(step.text());
        return "New conversation";
    }

    /// The conversation as a row of the tree, with its branches below it.
    public Talk.Chat talk(Conversations.Here here) {
        boolean inHere = here.file().equals(file);
        Shape shape = new Shape(steps);
        Tuple<Talk.Branch> starts = shape.branches(shape.firstQuestions(), inHere ? here.leaf() : "");
        if (starts.size() != 1) return new Talk.Chat(id, title(), 0, "", inHere, starts);
        Talk.Branch start = starts.first();
        return new Talk.Chat(id, title(), start.turns(), start.leaf(), inHere, start.forks());
    }

    static String oneLine(String text) {
        String line = text.strip().replaceAll("\\s+", " ");
        return line.length() <= TITLE_LENGTH ? line : line.substring(0, TITLE_LENGTH - 1) + "…";
    }

    /// The entries as a tree, for folding into branches. Walked without recursion: a genie that
    /// works for long writes long lines of answers and tool results between two questions.
    private static final class Shape {

        private final Map<String, List<Step>> children = new HashMap<>();
        private final Map<String, Integer> order = new HashMap<>();
        private final List<Step> roots = new ArrayList<>();

        Shape(Tuple<Step> steps) {
            Set<String> ids = new HashSet<>();
            for (Step step : steps) {
                order.put(step.id(), order.size());
                ids.add(step.id());
            }
            // An entry whose parent is missing, as in a file cut short, starts a tree of its own.
            for (Step step : steps) {
                if (step.parent().isEmpty() || !ids.contains(step.parent())) roots.add(step);
                else children.computeIfAbsent(step.parent(), parent -> new ArrayList<>()).add(step);
            }
        }

        /// The questions the conversation starts with: one, unless the first was asked again.
        List<Step> firstQuestions() {
            return questionsFrom(roots);
        }

        /// The first questions found below `from`, not looking past a question.
        private List<Step> questionsFrom(List<Step> from) {
            List<Step> found = new ArrayList<>();
            Deque<Step> open = new ArrayDeque<>(from);
            while (!open.isEmpty()) {
                Step step = open.pop();
                if (step.asked()) found.add(step);
                else below(step).reversed().forEach(open::push);
            }
            found.sort((a, b) -> Integer.compare(orderOf(a), orderOf(b)));
            return found;
        }

        /// The questions asked next after `question`: one, or several where it forks.
        private List<Step> next(Step question) {
            return questionsFrom(below(question));
        }

        /// The question and all that followed it up to the next question.
        private List<Step> turn(Step question) {
            List<Step> turn = new ArrayList<>(List.of(question));
            Deque<Step> open = new ArrayDeque<>(below(question));
            while (!open.isEmpty()) {
                Step step = open.pop();
                if (step.asked()) continue;
                turn.add(step);
                below(step).forEach(open::push);
            }
            return turn;
        }

        Tuple<Talk.Branch> branches(List<Step> firsts, String leaf) {
            Tuple<Talk.Branch> branches = Tuple.of(Talk.Branch.class);
            for (Step first : firsts) branches = branches.add(branch(first, leaf));
            return branches;
        }

        /// The run of questions from `first` on, for as long as there is exactly one next.
        private Talk.Branch branch(Step first, String leaf) {
            List<Step> own = new ArrayList<>();
            int turns = 0;
            Step question = first;
            List<Step> next;
            while (true) {
                turns++;
                own.addAll(turn(question));
                next = next(question);
                if (next.size() != 1) break;
                question = next.getFirst();
            }
            Step last = own.getFirst();
            for (Step step : own) if (orderOf(step) > orderOf(last)) last = step;
            boolean here = own.stream().anyMatch(step -> step.id().equals(leaf));
            return new Talk.Branch(first.id(), oneLine(first.text()), turns, last.id(), here, branches(next, leaf));
        }

        /// Where the entry is in the file: later entries were written later.
        private int orderOf(Step step) {
            return order.getOrDefault(step.id(), -1);
        }

        private List<Step> below(Step step) {
            return children.getOrDefault(step.id(), List.of());
        }
    }
}
