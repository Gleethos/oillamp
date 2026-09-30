package oillamp

import dev.lamp.Lamp
import dev.lamp.Lamp.Conversation.Kind
import dev.lamp.Lamp.Question
import dev.lamp.LampEvent
import dev.lamp.LampEvent.RunOutcome
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir
import spock.lang.Timeout

import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 *  Asking the agent in one of its conversations: {@code oillamp ask --in}, {@code --after},
 *  {@code --instead-of}, and {@code Lamp.Question}.
 *
 *  <p>A chat is more than a string of questions each starting afresh. A person continues a
 *  conversation, goes back to an earlier answer and asks from there, or asks something else
 *  instead of an earlier question. pi keeps every conversation as a tree, so none of that loses
 *  anything: each is a new branch. The session, which holds the agent, opens the conversation,
 *  moves to the right entry, and asks.
 *
 *  <p>The stand-in pi here keeps its conversations the way pi does, in files in the agent's home,
 *  so each scenario reads the result back through {@code Lamp.conversations}, as an application
 *  would.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class AskingInAConversationSpec extends Specification {

    @TempDir Path tmp
    @Subject Sandbox sandbox

    Path lamp
    final List<LampEvent> reported = new java.util.concurrent.CopyOnWriteArrayList<>()
    final List<String> prompts = new java.util.concurrent.CopyOnWriteArrayList<>()
    Thread session

    /** An agent that answers each question with one word, so the tree is easy to read. */
    static final Map<String, String> ANSWERS = [
            'Name a colour'  : 'Blue.',
            'Name a fruit'   : 'An apple.',
            'Name an animal' : 'A fox.',
            'Name a tree'    : 'An oak.',
            'Name a river'   : 'The Danube.']

    def setup() {
        sandbox = new Sandbox(tmp)
        sandbox.machine { it.reallyRuns('ssh-keygen') }
        lamp = sandbox.lampPath()
        // With the schedule on, so that a question that continues a conversation is seen to go
        // without the notes and recent runs a new one gets.
        sandbox.givenConfig(lamp, 'schema_version = 1\n[schedule]\nenabled = true\n')
        assert sandbox.oillamp.run('at', lamp.toString()).succeeded()
        sandbox.machine { it.agent { String prompt -> prompts << prompt; ANSWERS.find { prompt.contains(it.key) }?.value ?: 'Hm.' } }
        startASession()
    }

    def cleanup() {
        if (session?.alive) {
            sandbox.oillamp.run('stop', lamp.toString())
            session.join(30_000)
        }
    }

    def 'A question with no place starts a new conversation, which the application then finds'() {
        reportInfo """
            Asked without saying where, a question starts a conversation of its own. The run says
            which conversation that was, so an application can show it, and it is known by its
            first question.
        """
        when:
            var asked = ask('Name a colour')

        then:
            asked.outcome() == RunOutcome.FINISHED
            var conversation = Lamp.conversation(lamp, asked.conversation().orElseThrow()).orElseThrow()
            conversation.title() == 'Name a colour'
            questionsAndAnswers(conversation.line()) == ['Name a colour', 'Blue.']
    }

    def 'Asking in a conversation continues it where it stands, with the question as it was written'() {
        reportInfo """
            The next question in a chat goes after the last answer. What a person asks goes as
            they wrote it, never wrapped in anything, because that is what the chat shows as
            their message. Only a scheduled job's run gets the agent's notes put in front of it.
        """
        given:
            var first = ask('Name a colour').conversation().orElseThrow()

        when:
            var second = sandbox.oillamp.run('ask', lamp.toString(), '--in', first.take(13), 'Name a fruit')

        then:
            second.succeeded()
            second.console().contains('An apple.')
            prompts == ['Name a colour', 'Name a fruit']

        and: 'it is the same conversation, one question longer'
            Lamp.conversations(lamp).size() == 1
            questionsAndAnswers(Lamp.conversation(lamp, first).orElseThrow().line()) ==
                    ['Name a colour', 'Blue.', 'Name a fruit', 'An apple.']
    }

    def 'Asking instead of an earlier question keeps the old one as a branch of its own'() {
        reportInfo """
            A person who regrets a question asks something else in its place. Nothing is lost:
            the old question and everything after it stay as a branch, and the conversation now
            stands on the new one.
        """
        given:
            var conversation = ask('Name a colour').conversation().orElseThrow()
            askIn(conversation, 'Name a fruit')
            var fruit = question(conversation, 'Name a fruit')

        when:
            var asked = running().ask(Question.insteadOf(conversation, fruit.id(), 'Name an animal'))

        then:
            asked.outcome() == RunOutcome.FINISHED
            var tree = Lamp.conversation(lamp, conversation).orElseThrow()
            questionsAndAnswers(tree.line()) == ['Name a colour', 'Blue.', 'Name an animal', 'A fox.']

        and: 'the conversation forks where the old question was'
            tree.children(fruit.parent().orElseThrow())*.text() == ['Name a fruit', 'Name an animal']
    }

    def 'Continuing after an earlier answer starts a branch there'() {
        reportInfo """
            Going back to an earlier answer and asking from there is how a person explores
            another direction. The conversation forks after that answer; the later questions and
            answers stay on their own branch.
        """
        given:
            var conversation = ask('Name a colour').conversation().orElseThrow()
            askIn(conversation, 'Name a fruit')
            var blue = answerTo(conversation, 'Name a colour')

        when:
            var asked = sandbox.oillamp.run('ask', lamp.toString(), '--in', conversation, '--after', blue.id(), 'Name a tree')

        then:
            asked.succeeded()
            var tree = Lamp.conversation(lamp, conversation).orElseThrow()
            questionsAndAnswers(tree.line()) == ['Name a colour', 'Blue.', 'Name a tree', 'An oak.']
            tree.children(blue.id())*.text() == ['Name a fruit', 'Name a tree']
    }

    def 'A place that is not there is refused before anything is saved or asked'() {
        reportInfo """
            A conversation that was deleted, an entry that is not in it, or an answer given where a
            question is needed: each is said at once, in words that say what to give instead, and
            neither the agent nor the history is touched.
        """
        given:
            var conversation = ask('Name a colour').conversation().orElseThrow()
            var colour = question(conversation, 'Name a colour')
            var blue = answerTo(conversation, 'Name a colour')
            var snapshots = history().size()
            var asked = prompts.size()

        when:
            var noConversation = sandbox.oillamp.run('ask', lamp.toString(), '--in', 'ffffffff', 'x')
            var noEntry = sandbox.oillamp.run('ask', lamp.toString(), '--in', conversation, '--after', 'deadbeef', 'x')
            var afterAQuestion = sandbox.oillamp.run('ask', lamp.toString(), '--in', conversation, '--after', colour.id(), 'x')
            var insteadOfAnAnswer = sandbox.oillamp.run('ask', lamp.toString(), '--in', conversation, '--instead-of', blue.id(), 'x')
            var entryWithoutConversation = sandbox.oillamp.run('ask', lamp.toString(), '--after', blue.id(), 'x')

        then:
            noConversation.reported('OIL-CONVERSATION-001')
            noEntry.reported('OIL-CONVERSATION-001')
            afterAQuestion.problems().first().whatHappened().contains('give --instead-of ' + colour.id())
            insteadOfAnAnswer.problems().first().whatHappened().contains('give --after ' + blue.id())
            !entryWithoutConversation.succeeded()

        and:
            prompts.size() == asked
            history().size() == snapshots
    }

    def 'A question says what it needs when it is made'() {
        reportInfo """
            An application builds its questions in code. One that could never be asked, such as an
            entry with no conversation to be in, is refused where it is made, not later by the
            session.
        """
        when:
            new Question('x', Optional.empty(), Optional.of('a1'), Optional.empty())

        then:
            thrown(IllegalArgumentException)

        when:
            Question.fresh('  ')

        then:
            thrown(IllegalArgumentException)
    }

    // ─── helpers ───────────────────────────────────────────────────────────────────────────

    private LampEvent.RunFinished ask(String prompt) {
        var asked = sandbox.oillamp.run('ask', lamp.toString(), prompt)
        assert asked.succeeded()
        asked.events().find { it instanceof LampEvent.RunFinished }
    }

    private void askIn(String conversation, String prompt) {
        assert sandbox.oillamp.run('ask', lamp.toString(), '--in', conversation, prompt).succeeded()
    }

    /** The lamp, as an application sees it. The session holding it was started from the command line. */
    private Lamp.Starting running() {
        Lamp.at(lamp).launchedBy(sandbox.launcher)
    }

    private Lamp.Conversation.Entry question(String conversation, String text) {
        Lamp.conversation(lamp, conversation).orElseThrow().entries().find { it.kind() == Kind.QUESTION && it.text() == text }
    }

    private Lamp.Conversation.Entry answerTo(String conversation, String text) {
        var tree = Lamp.conversation(lamp, conversation).orElseThrow()
        tree.children(question(conversation, text).id()).first()
    }

    private static List<String> questionsAndAnswers(Iterable<Lamp.Conversation.Entry> line) {
        line.findAll { it.kind() in [Kind.QUESTION, Kind.ANSWER] }*.text()
    }

    private List<LampEvent.Snapshot> history() {
        sandbox.oillamp.run('history', lamp.toString()).events().find { it instanceof LampEvent.History }.snapshots().collect()
    }

    private void startASession() {
        sandbox.machine { it.windowsStayOpenFor(Duration.ofSeconds(110)) }
        var oillamp = sandbox.oillamp.observedBy { reported.add(it) }
        session = Thread.start { oillamp.run('at', lamp.toString()) }
        var deadline = System.currentTimeMillis() + 30_000
        while (!reported.any { it instanceof LampEvent.Summary && it.title() == 'your session is up' }) {
            assert System.currentTimeMillis() < deadline : 'the session never came up'
            Thread.sleep(50)
        }
    }
}
