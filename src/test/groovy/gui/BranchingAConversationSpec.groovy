package gui

import dev.gui.model.Conversation
import dev.gui.model.Conversations
import dev.gui.model.Talk
import spock.lang.Specification
import sprouts.Tuple

/**
 *  How a conversation becomes the tree under a genie.
 *
 *  <p>pi keeps a conversation as a tree of entries: each entry, whether a question, an answer
 *  or a tool's result, points to the one before it. Asking something else instead of an earlier
 *  question adds a new question pointing to the same entry the old one did, so the line forks
 *  there, and both sides are kept.
 *
 *  <p>The tree under a genie shows only where that happened. A branch is a run of questions in
 *  which nothing was asked differently, labelled by its first question; its children are the
 *  alternatives where it ends. A conversation's own row stands for the run it starts with,
 *  which would read the same, so a long conversation that never forked is one row.
 */
class BranchingAConversationSpec extends Specification {

    def 'A conversation that never forked is one row, however long it is'() {
        reportInfo """
            Three questions, each with its answer, one after the other. The tree shows the
            conversation, titled by its first question, with nothing below it. Going there
            continues after the last entry.
        """
        given:
            var conversation = conversation(
                    question('q1', '', 'How do I fix my printer?'), answer('a1', 'q1'),
                    question('q2', 'a1', 'It says paper jam'), answer('a2', 'q2'),
                    question('q3', 'a2', 'Still jammed'), answer('a3', 'q3'))

        when:
            var chat = conversation.talk(Conversations.Here.UNKNOWN)

        then:
            chat.title() == 'How do I fix my printer?'
            chat.turns() == 3
            chat.leaf() == 'a3'
            chat.branches().isEmpty()
    }

    def 'Asking a question differently splits the conversation where that question was'() {
        reportInfo """
            The user asked the second question again, differently. pi then added the new
            question after the first answer, next to the old one. So below the conversation are
            two branches: the old second question with what followed it, and the new one. Each
            continues after its own last entry; the conversation's own row continues after the
            first answer, where the two part.
        """
        given:
            var conversation = conversation(
                    question('q1', '', 'Plan a trip'), answer('a1', 'q1'),
                    question('q2', 'a1', 'By train'), answer('a2', 'q2'),
                    question('q3', 'a2', 'Overnight?'), answer('a3', 'q3'),
                    question('q2b', 'a1', 'By bike'), answer('a2b', 'q2b'))

        when:
            var chat = conversation.talk(Conversations.Here.UNKNOWN)

        then:
            chat.title() == 'Plan a trip'
            chat.turns() == 1
            chat.leaf() == 'a1'
            chat.branches()*.title() == ['By train', 'By bike']
            chat.branches()*.turns() == [2, 1]
            chat.branches()*.leaf() == ['a3', 'a2b']
    }

    def 'Asking the very first question differently gives a conversation two beginnings'() {
        reportInfo """
            Before the first question pi writes a few entries of its own, such as the model in
            use. A first question asked again hangs off the same entry as the first one, so the
            conversation has two branches at its top, and its own row goes wherever pi left it.
        """
        given:
            var conversation = conversation(
                    other('m', ''), question('q1', 'm', 'Write a poem'), answer('a1', 'q1'),
                    question('q1b', 'm', 'Write a limerick'), answer('a1b', 'q1b'))

        when:
            var chat = conversation.talk(Conversations.Here.UNKNOWN)

        then:
            chat.branches()*.title() == ['Write a poem', 'Write a limerick']
            chat.turns() == 0
            chat.leaf() == ''
    }

    def 'The branch the genie is on is marked, and so is the way down to it'() {
        reportInfo """
            pi continues from one entry, its leaf. The branch holding that entry is where the
            genie is; the tree selects it, and the path of ids leading to it names that row.
            Here the genie is on the new second question.
        """
        given:
            var conversation = conversation(
                    question('q1', '', 'Plan a trip'), answer('a1', 'q1'),
                    question('q2', 'a1', 'By train'), answer('a2', 'q2'),
                    question('q2b', 'a1', 'By bike'), answer('a2b', 'q2b'))
            var conversations = new Conversations(Tuple.of(Conversation, conversation),
                                                  new Conversations.Here(conversation.file(), 'a2b'))

        when:
            var chat = conversations.tree().first() as Talk.Chat

        then:
            chat.here()
            chat.branches()*.here() == [false, true]
            conversations.herePath().toList() == ['s1', 'q2b']

        when: 'the genie is where the two part'
            conversations = conversations.withHere(new Conversations.Here(conversation.file(), 'a1'))

        then: 'the conversation itself is its row'
            conversations.herePath().toList() == ['s1']
    }

    def 'A new conversation shows at the top until pi writes it'() {
        reportInfo """
            pi writes a conversation to disk only once something is said in it. Right after the
            user started a new one, the genie is in a conversation no file holds yet; it is
            shown at the top of the tree, so the user sees where they are.
        """
        given:
            var conversations = new Conversations(Tuple.of(Conversation, conversation(question('q1', '', 'Hi'))),
                                                  new Conversations.Here('.pi/agent/sessions/--home-agent--/new.jsonl', ''))

        expect:
            conversations.tree()*.title() == ['New conversation', 'Hi']
            conversations.tree().first().here()
            conversations.herePath().size() == 1
    }

    def 'A conversation is titled by its name, or by its first question on one short line'() {
        reportInfo """
            A row has room for one short line. A long question with line breaks is put on one
            line and shortened; a conversation someone named is titled by its name.
        """
        expect:
            conversation(question('q1', '', 'Line one\n  line two ' + 'x' * 80)).title() ==~ /Line one line two x+…/
            conversation(question('q1', '', 'Line one\n  line two ' + 'x' * 80)).title().length() == 60
            new Conversation('s1', 'f', 'Trip planning', '', Tuple.of(Conversation.Step)).title() == 'Trip planning'
            new Conversation('s1', 'f', '', '', Tuple.of(Conversation.Step)).title() == 'New conversation'
    }

    // ─── helpers ───────────────────────────────────────────────────────────────────────────

    private static Conversation conversation(Conversation.Step... steps) {
        new Conversation('s1', '.pi/agent/sessions/--home-agent--/s1.jsonl', '', '2026-09-29T10:00:00.000Z',
                         Tuple.of(Conversation.Step, steps))
    }

    private static Conversation.Step question(String id, String parent, String text) {
        new Conversation.Step(id, parent, true, text)
    }

    private static Conversation.Step answer(String id, String parent) {
        new Conversation.Step(id, parent, false, '')
    }

    private static Conversation.Step other(String id, String parent) {
        new Conversation.Step(id, parent, false, '')
    }
}
