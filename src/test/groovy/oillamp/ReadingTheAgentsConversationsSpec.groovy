package oillamp

import dev.lamp.Lamp
import dev.lamp.Lamp.Conversation.Kind
import groovy.json.JsonOutput
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir
import spock.lang.Timeout

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 *  Reading the agent's conversations: {@code Lamp.conversations} and {@code oillamp conversations}.
 *
 *  <p>pi, the agent in the sandbox, keeps every conversation in a file of its own in the agent's
 *  home: one JSON object per line, each entry naming the one before it. That makes a conversation a
 *  tree, which forks wherever someone asked something else instead of an earlier question. An
 *  application shows these conversations, and moves around in them, without starting pi, and
 *  whether or not the lamp is running.
 *
 *  <p>The files here are written the way pi writes them, into the agent's home of a real lamp.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ReadingTheAgentsConversationsSpec extends Specification {

    @TempDir Path tmp
    @Subject Sandbox sandbox

    Path lamp

    def setup() {
        sandbox = new Sandbox(tmp)
        sandbox.machine { it.reallyRuns('ssh-keygen') }
        lamp = sandbox.lampPath()
        assert sandbox.oillamp.run('at', lamp.toString()).succeeded()
    }

    def 'An application reads the agent\'s conversations as values, the most recent first'() {
        reportInfo """
            An application that shows a lamp's conversations, such as a chat app, needs them as
            values it can list and draw, not as pi's files. They are read straight from the agent's
            home, so they are there while the lamp sleeps too, and the list is quick enough to read
            again whenever something may have changed.
        """
        given:
            conversation('01a0ec69-de1c-7234-914c-b970a862c13e', '2026-09-28T10:00:00Z') {
                question 'q1', 'How big is the repository?'
                answer 'a1', 'q1', 'About 12,000 lines.'
            }
            conversation('01a0ed10-aaaa-7234-914c-b970a862c13e', '2026-09-29T10:00:00Z') {
                name 'run-3 (job-1)'
                question 'q1', 'Check the build'
                answer 'a1', 'q1', 'It is green.'
            }

        when:
            var found = Lamp.conversations(lamp)

        then: 'the most recent comes first, named as it was named, or by its first question'
            found*.title() == ['run-3 (job-1)', 'How big is the repository?']
            found*.id() == ['01a0ed10-aaaa-7234-914c-b970a862c13e', '01a0ec69-de1c-7234-914c-b970a862c13e']

        and: 'each is the whole line of entries, question and answer'
            var first = found[1]
            first.line()*.kind() == [Kind.OTHER, Kind.MESSAGE_TO_AGENT, Kind.MESSAGE_FROM_AGENT]
            first.line()*.text().takeRight(2) == ['How big is the repository?', 'About 12,000 lines.']
            first.started() == Instant.parse('2026-09-28T10:00:00Z')

        and: 'one is found by the start of its id, as oillamp shows it'
            Lamp.conversation(lamp, '01a0ec69-de1c').map { it.title() } == Optional.of('How big is the repository?')
            Lamp.conversation(lamp, '01a0').isEmpty()
    }

    def 'A conversation that forks keeps both branches, and stands where it was written last'() {
        reportInfo """
            Asking something else instead of an earlier question does not throw the first answer
            away: pi starts a second branch from the same point, and the conversation forks there.
            The conversation stands at the entry written last, which is where the next question
            goes unless it says otherwise. Every other branch can still be walked to.
        """
        given:
            conversation('01a0ec69-de1c-7234-914c-b970a862c13e', '2026-09-28T10:00:00Z') {
                question 'q1', 'Name a colour'
                answer 'a1', 'q1', 'Blue.'
                question 'q2', 'Name a fruit', 'a1'
                answer 'a2', 'q2', 'An apple.'
                question 'q2b', 'Name an animal', 'a1'
                answer 'a2b', 'q2b', 'A fox.'
            }

        when:
            var found = Lamp.conversations(lamp).first()

        then: 'it stands on the branch written last'
            found.leaf() == Optional.of('a2b')
            found.line()*.id() == ['s0', 'q1', 'a1', 'q2b', 'a2b']

        and: 'it forks after the first answer'
            found.children('a1')*.id() == ['q2', 'q2b']

        and: 'the other branch is still there to walk to'
            found.lineTo('a2')*.text().findAll() == ['Name a colour', 'Blue.', 'Name a fruit', 'An apple.']
    }

    def 'An answer carries what the agent thought, the tools it called, and whether it failed'() {
        reportInfo """
            A chat shows more than words: what the model thought before it answered, each tool it
            ran with the one line that says what it did, the tool's output, and answers that failed,
            with the reason the model service gave.
        """
        given:
            conversation('01a0ec69-de1c-7234-914c-b970a862c13e', '2026-09-28T10:00:00Z') {
                question 'q1', 'List the files'
                entry id: 'a1', parentId: 'q1', message: [role: 'assistant', stopReason: 'toolUse', content: [
                        [type: 'thinking', thinking: 'I will use ls.'],
                        [type: 'toolCall', id: 'call-1', name: 'bash', arguments: [command: 'ls -la\n~/workspace']]]]
                entry id: 't1', parentId: 'a1', message: [role: 'toolResult', toolCallId: 'call-1', toolName: 'bash',
                        content: [[type: 'text', text: 'ls: cannot access']], isError: true]
                entry id: 'a2', parentId: 't1', message: [role: 'assistant', stopReason: 'error',
                        errorMessage: '403: model not allowed', content: []]
            }

        when:
            var line = Lamp.conversations(lamp).first().line()

        then:
            var call = line.find { it.id() == 'a1' }
            call.thinking() == 'I will use ls.'
            call.calls()*.name() == ['bash']
            call.calls().first().summary() == 'ls -la ⏎ ~/workspace'

        and:
            var output = line.find { it.id() == 't1' }
            output.kind() == Kind.TOOL_OUTPUT
            output.tool() == Optional.of('bash')
            output.failed()

        and:
            var failed = line.find { it.id() == 'a2' }
            failed.failed()
            failed.text() == '403: model not allowed'
    }

    def 'What the agent left in its home cannot lead the reader astray'() {
        reportInfo """
            The agent writes these files and can write anything there: a link to a file of the
            user's elsewhere, a directory that is a link, lines that are not JSON, a file that is
            not a conversation at all. None of that is followed or trusted. The conversations that
            are real are still read.
        """
        given: 'a real conversation, with a line of rubbish in it'
            var file = conversation('01a0ec69-de1c-7234-914c-b970a862c13e', '2026-09-28T10:00:00Z') {
                question 'q1', 'Hello'
            }
            Files.writeString(file, Files.readString(file) + 'this is not JSON\n')

        and: 'a link to a conversation outside the agent\'s home, and a directory that is a link'
            var outside = Files.createDirectories(tmp.resolve('outside'))
            var secret = outside.resolve('secret.jsonl')
            Files.writeString(secret, JsonOutput.toJson([type: 'session', id: 'secret-session', timestamp: '2026-09-29T10:00:00Z']) + '\n')
            Files.createSymbolicLink(file.parent.resolve('linked.jsonl'), secret)
            Files.createSymbolicLink(file.parent.parent.resolve('--elsewhere--'), outside)

        and: 'a file with no header'
            Files.writeString(file.parent.resolve('headless.jsonl'), '{"type":"message","id":"x"}\n')

        when:
            var found = Lamp.conversations(lamp)

        then:
            found*.id() == ['01a0ec69-de1c-7234-914c-b970a862c13e']
            found.first().line()*.text().contains('Hello')
    }

    def 'oillamp conversations lists them, and shows one with the ids to ask after'() {
        reportInfo """
            From a terminal, a person lists the conversations and looks into one. The ids next to
            each question and answer are what oillamp ask takes, to continue after an answer or to
            ask something instead of a question. A fork is marked where it happens.
        """
        given:
            conversation('01a0ec69-de1c-7234-914c-b970a862c13e', '2026-09-28T10:00:00Z') {
                question 'q1', 'Name a colour'
                answer 'a1', 'q1', 'Blue.'
                question 'q2', 'Name a fruit', 'a1'
                answer 'a2', 'q2', 'An apple.'
                question 'q2b', 'Name an animal', 'a1'
                answer 'a2b', 'q2b', 'A fox.\u001B[2J'
            }

        when:
            var listed = sandbox.oillamp.run('conversations', lamp.toString())
            var shown = sandbox.oillamp.run('conversations', lamp.toString(), '01a0ec69-de1c')
            var missing = sandbox.oillamp.run('conversations', lamp.toString(), 'ffff')

        then:
            listed.succeeded()
            listed.console().contains('01a0ec69-de1c-7234')
            listed.console().contains('Name a colour')

        and: 'the line it stands on, with its ids and its fork'
            shown.succeeded()
            shown.console().contains('q2b  you:   Name an animal')
            shown.console().contains('a2b  agent: A fox.')
            shown.console().contains('1 other question was asked here instead')
            !shown.console().contains('\u001B[2J')

        and:
            missing.reported('OIL-CONVERSATION-001')
    }

    def 'A conversation can be forgotten for good'() {
        reportInfo """
            A person tidying up deletes the conversations they no longer want. Nothing else of the
            agent's home changes.
        """
        given:
            conversation('01a0ec69-de1c-7234-914c-b970a862c13e', '2026-09-28T10:00:00Z') { question 'q1', 'One' }
            conversation('01a0ed10-aaaa-7234-914c-b970a862c13e', '2026-09-29T10:00:00Z') { question 'q1', 'Two' }
            var lamps = Lamp.at(lamp)

        when:
            lamps.forget('01a0ec69')

        then:
            lamps.conversations()*.title() == ['Two']

        when:
            lamps.forget('ffffffff')

        then:
            thrown(IOException)
    }

    // ─── writing conversations the way pi does ─────────────────────────────────────────────

    /** Writes a session file, as pi does, and returns it. The first entry is pi's instructions. */
    private Path conversation(String id, String started, @DelegatesTo(PiFile) Closure lines) {
        var directory = Files.createDirectories(Lamp.agentHome(lamp).orElseThrow().resolve('.pi/agent/sessions/--home-agent-workspace--'))
        var file = directory.resolve(started.replace(':', '-') + '_' + id + '.jsonl')
        var pi = new PiFile(Instant.parse(started))
        pi.line([type: 'session', version: 3, id: id, timestamp: started, cwd: '/home/agent/workspace'])
        pi.entry(id: 's0', parentId: null, message: [role: 'system', content: ''])
        lines.delegate = pi
        lines.resolveStrategy = Closure.DELEGATE_FIRST
        lines()
        Files.writeString(file, pi.text.toString())
        file
    }

    static class PiFile {
        final StringBuilder text = new StringBuilder()
        Instant clock
        String last = 's0'

        PiFile(Instant start) { clock = start }

        void line(Map record) { text.append(JsonOutput.toJson(record)).append('\n') }

        void entry(Map fields) {
            clock = clock.plusSeconds(10)
            line([type: 'message', timestamp: clock.toString()] + fields)
            last = fields.id
        }

        void name(String name) {
            clock = clock.plusSeconds(1)
            line([type: 'session_info', id: 'n-' + name.hashCode(), parentId: last, timestamp: clock.toString(), name: name])
        }

        void question(String id, String text, String parent = last) {
            entry(id: id, parentId: parent, message: [role: 'user', content: text])
        }

        void answer(String id, String parent, String text) {
            entry(id: id, parentId: parent, message: [role: 'assistant', stopReason: 'stop',
                                                      content: [[type: 'text', text: text]]])
        }
    }
}
