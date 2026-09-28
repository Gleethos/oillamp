package gui

import dev.gui.pi.PiEvent
import dev.gui.pi.PiProtocol
import groovy.json.JsonSlurper
import spock.lang.Specification
import sprouts.Tuple

/**
 *  How Genies talks to a genie: pi's RPC mode, one JSON object per line in each direction.
 *
 *  <p>pi is the agent harness inside the sandbox. Genies starts it with {@code pi --mode rpc} over
 *  the lamp's ssh command, writes commands to its standard input and reads events from its
 *  standard output. The lines here are shaped exactly like the ones pi 0.87 writes, taken from
 *  its documentation in the sandbox image.
 */
class SpeakingPisProtocolSpec extends Specification {

    def 'What the user types is sent as one prompt, on one line'() {
        reportInfo """
            A message can hold anything the user typed: quotes, new lines, other languages.
            It travels as a JSON string, so pi receives it exactly, and it stays on one line,
            because pi reads one command per line.
        """
        when:
            var line = PiProtocol.prompt('Hi "genie",\nwhat is in ~/inbox?', false)

        then:
            !line.contains('\n')
            new JsonSlurper().parseText(line) == [type: 'prompt', message: 'Hi "genie",\nwhat is in ~/inbox?']
    }

    def 'A message sent while the genie is still working waits until it is done'() {
        reportInfo """
            pi refuses a plain prompt while it is busy. The user may still type ahead, as in any
            chat, so a message sent then is queued as a follow-up, which pi answers once it has
            finished the current one.
        """
        expect:
            new JsonSlurper().parseText(PiProtocol.prompt('and then?', true)).streamingBehavior == 'followUp'
    }

    def 'An answer arrives piece by piece while it is written, then whole, with what it cost'() {
        reportInfo """
            The chat shows the answer as it is written, like any chat with a model. Each piece
            is a message_update with a text_delta. The message_end that follows holds the whole
            answer as pi recorded it, which replaces the pieces, and the tokens the model
            counted, which the app adds up per genie.
        """
        expect:
            PiProtocol.read('{"type":"message_update","usage":{},"assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":"Hel"}}') ==
                    Optional.of(new PiEvent.Said('Hel'))
            PiProtocol.read('''{"type":"message_end","message":{"role":"assistant","content":[
                                  {"type":"thinking","thinking":"hmm"},{"type":"text","text":"Hello!"}],
                                "stopReason":"stop","usage":{"totalTokens":128}}}''') ==
                    Optional.of(new PiEvent.Answered('Hello!', '', 128))
    }

    def 'Thinking arrives as its start and then the thoughts, piece by piece'() {
        reportInfo """
            A model that thinks before it answers says so with thinking_start, and a model that
            shares its thoughts sends them as thinking_delta pieces, which the chat shows in a
            thinking row the user can open.
        """
        expect:
            PiProtocol.read('{"type":"message_update","usage":{},"assistantMessageEvent":{"type":"thinking_start","contentIndex":0}}') ==
                    Optional.of(new PiEvent.Thinking(''))
            PiProtocol.read('{"type":"message_update","usage":{},"assistantMessageEvent":{"type":"thinking_delta","contentIndex":0,"delta":"Let me see"}}') ==
                    Optional.of(new PiEvent.Thinking('Let me see'))
    }

    def 'The user\'s own message, echoed back by pi, is not shown twice'() {
        reportInfo """
            pi reports every message of the conversation, including the one the user just sent.
            The chat already shows that one, so only the genie's messages are read.
        """
        expect:
            PiProtocol.read('{"type":"message_end","message":{"role":"user","content":"hi","timestamp":1}}').isEmpty()
    }

    def 'Using a tool shows what the genie does, and what came of it'() {
        reportInfo """
            A genie works by using tools: running commands, writing files. The chat shows each
            one as a line saying what it does, the command itself for a shell command, so the
            user can follow along. Its output is kept, shortened if long: the genie saw all of
            it, the user needs the gist.
        """
        when:
            var started = PiProtocol.read('{"type":"tool_execution_start","toolCallId":"call_1","toolName":"bash","args":{"command":"ls -la\\n~/outbox"}}')
            var finished = PiProtocol.read(JsonOutput(
                    [type: 'tool_execution_end', toolCallId: 'call_1', toolName: 'bash', isError: false,
                     result: [content: [[type: 'text', text: 'x' * 5000]]]]))

        then:
            started == Optional.of(new PiEvent.ToolStarted('call_1', 'bash', 'ls -la ⏎ ~/outbox'))
            var result = finished.get() as PiEvent.ToolFinished
            result.call() == 'call_1'
            !result.failed()
            result.output().startsWith('x' * 4000)
            result.output().endsWith('(1000 more characters)')
    }

    def 'When the model cannot answer, the chat says why'() {
        reportInfo """
            The model service may refuse the key, be down, or the user may stop the genie. pi
            ends the answer with an error then, and its message is what the user needs to see,
            for example the relay's own explanation that there is no key.
        """
        expect:
            PiProtocol.read('''{"type":"message_end","message":{"role":"assistant","content":[],
                                "stopReason":"error","errorMessage":"401 oillamp: there is no model key",
                                "usage":{"totalTokens":0}}}''') ==
                    Optional.of(new PiEvent.Answered('', '401 oillamp: there is no model key', 0))
            PiProtocol.read('{"type":"auto_retry_start","attempt":1,"maxAttempts":3,"delayMs":2000,"errorMessage":"529 overloaded"}') ==
                    Optional.of(new PiEvent.Retrying(1, 3, '529 overloaded'))
    }

    def 'The conversation so far comes back from pi, so the sandbox is where it is kept'() {
        reportInfo """
            A genie remembers its conversation across restarts because pi saves it in the
            sandbox, and is started with --continue. When the app opens a genie again, it asks
            pi for the messages and shows them. Only what the user wrote and what the genie
            answered is shown; tool calls and their results were steps on the way.
        """
        expect:
            PiProtocol.read(JsonOutput([type: 'response', command: 'get_messages', success: true, data: [messages: [
                    [role: 'user', content: 'Make me a chart', timestamp: 1],
                    [role: 'assistant', content: [[type: 'toolCall', id: 'c', name: 'bash', arguments: [:]]]],
                    [role: 'toolResult', toolCallId: 'c', content: [[type: 'text', text: 'done']]],
                    [role: 'assistant', content: [[type: 'text', text: 'It is in ~/outbox/chart.png']]]]]])) ==
                    Optional.of(new PiEvent.History(Tuple.of(PiEvent.History.Line,
                            new PiEvent.History.Line(true, 'Make me a chart'),
                            new PiEvent.History.Line(false, 'It is in ~/outbox/chart.png'))))
            new JsonSlurper().parseText(PiProtocol.askForHistory()) == [type: 'get_messages']
    }

    def 'A command pi refuses is reported with pi\'s reason'() {
        reportInfo """
            pi answers every command with a response. A refused one carries the reason, which
            the chat shows instead of silently dropping the user's message.
        """
        expect:
            PiProtocol.read('{"type":"response","command":"prompt","success":false,"error":"Agent is busy"}') ==
                    Optional.of(new PiEvent.Refused('prompt', 'Agent is busy'))
    }

    def 'Anything else pi says is read as nothing'() {
        reportInfo """
            pi reports much more than a chat shows, and a newer pi may report things this
            version has never heard of. None of it is an error: the chat simply skips it.
        """
        expect:
            PiProtocol.read(line).isEmpty()

        where:
            line << ['{"type":"turn_start"}', '{"type":"compaction_start","reason":"threshold"}',
                     '{"type":"response","command":"prompt","success":true}', 'not json', '[1,2]', '']
    }

    private static String JsonOutput(Map value) { groovy.json.JsonOutput.toJson(value) }
}
