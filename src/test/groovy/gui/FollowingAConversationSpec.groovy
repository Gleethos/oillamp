package gui

import dev.gui.genie.LampApiConversionUtil
import dev.gui.model.Entry
import dev.gui.model.Genie
import dev.gui.model.Handout
import dev.gui.model.Schedule
import dev.gui.pi.PiEvent
import dev.lamp.Lamp
import spock.lang.Specification
import sprouts.Tuple

import java.time.Instant

/**
 *  How a conversation with a genie turns into what the chat shows.
 *
 *  <p>A genie's harness reports what it does as a stream of events: pieces of an answer, tools
 *  started and finished, answers complete. {@link Genie#hear} folds each one into the genie, as a
 *  pure function, so the whole conversation can be followed here without a window or a sandbox.
 */
class FollowingAConversationSpec extends Specification {

    Genie genie = Genie.named('Aladdin').waking().awake()

    def 'Sending a message shows it, empties the draft, and sets the genie to work'() {
        reportInfo """
            The user types, presses return, and sees their message in the chat at once, with the
            genie marked as thinking. What is sent is trimmed; an empty draft sends nothing.
        """
        when:
            var sent = genie.withDraft('  Paint me a sunset  ').send()

        then:
            sent.transcript().entries()*.text() == ['Paint me a sunset']
            sent.transcript().entries().first().kind() == Entry.Kind.YOU
            sent.draft() == ''
            sent.phase() == Genie.Phase.WORKING

        and: 'a blank draft, or a genie that is not awake, sends nothing'
            genie.withDraft('   ').send() == genie.withDraft('   ')
            Genie.named('Asleep').withDraft('hi').send().transcript().isEmpty()
    }

    def 'An answer grows in one entry while it is written, and is replaced by the final text'() {
        reportInfo """
            The pieces of an answer arrive many times a second. They all go into one entry,
            which the chat shows growing. When the answer is complete, the text pi recorded
            replaces the pieces, so what stays in the chat is exactly what the genie said.
        """
        when:
            var heard = genie.withDraft('hi').send()
                    .hear(new PiEvent.Thinking(''))
                    .hear(new PiEvent.Said('Hel'))
                    .hear(new PiEvent.Said('lo'))

        then: 'one answer, still being written'
            heard.transcript().entries().size() == 2
            with(heard.transcript().entries().last()) {
                kind() == Entry.Kind.GENIE
                text() == 'Hello'
                isWriting()
            }

        when:
            var done = heard.hear(new PiEvent.Answered('Hello!', '', 42)).hear(new PiEvent.Settled())

        then: 'the final text, done, and the genie waits again'
            done.transcript().entries().last().text() == 'Hello!'
            !done.transcript().entries().last().isWriting()
            done.phase() == Genie.Phase.READY
            done.tokens() == 42
    }

    def 'Each tool the genie uses is shown, with its result filed under it'() {
        reportInfo """
            Between thinking and answering, a genie uses tools. Each shows as its own entry
            saying what it does, which the chat marks as running until the result arrives. The
            result is filed with the call it belongs to by pi's call id, even when several run.
            Thinking that shared no thoughts leaves nothing behind when the genie turns to a
            tool: there is nothing in it to show.
        """
        when:
            var working = genie.withDraft('what is in my outbox?').send()
                    .hear(new PiEvent.Thinking(''))
                    .hear(new PiEvent.ToolStarted('call_1', 'bash', 'ls ~/outbox'))
                    .hear(new PiEvent.ToolStarted('call_2', 'read', '~/outbox/notes.md'))
                    .hear(new PiEvent.ToolFinished('call_1', false, 'notes.md'))
                    .hear(new PiEvent.ToolFinished('call_2', true, 'permission denied'))

        then:
            var entries = working.transcript().entries()
            entries*.kind() == [Entry.Kind.YOU, Entry.Kind.TOOL, Entry.Kind.TOOL]
            entries[1].title() == 'bash'
            entries[1].text() == 'ls ~/outbox'
            entries[1].detail() == 'notes.md'
            entries[1].state() == Entry.State.DONE
            entries[2].state() == Entry.State.FAILED
            working.activity() == 'using read'
    }

    def 'A genie whose model shares its thoughts can be watched thinking'() {
        reportInfo """
            Some models think before they answer, and share what they think. The thoughts go
            into an entry of their own, which grows as they come and is marked as thinking
            while it lasts; the user can open it and watch. It is done the moment the genie
            starts to answer or to use a tool, and its thoughts stay to be read later.
        """
        when:
            var thinking = genie.withDraft('why is the sky blue?').send()
                    .hear(new PiEvent.Thinking(''))
                    .hear(new PiEvent.Thinking('Rayleigh '))
                    .hear(new PiEvent.Thinking('scattering.'))

        then:
            with(thinking.transcript().entries().last()) {
                kind() == Entry.Kind.THINKING
                text() == 'Rayleigh scattering.'
                isWriting()
            }
            thinking.activity() == 'thinking'

        when:
            var answering = thinking.hear(new PiEvent.Said('Because'))

        then:
            answering.transcript().entries()*.kind() == [Entry.Kind.YOU, Entry.Kind.THINKING, Entry.Kind.GENIE]
            !answering.transcript().entries()[1].isWriting()
            answering.transcript().entries()[1].text() == 'Rayleigh scattering.'
    }

    def 'While the genie does a scheduled job, the user is told that their message waits for it'() {
        reportInfo """
            A genie does one thing at a time. A message sent while it does a job of its schedule,
            such as one it missed while asleep and catches up on as it wakes, waits until the job
            is done. Until then the chat does not claim the genie thinks: it names the job and how
            long it has run, and the line under the genie's name says it does a scheduled job.
            Once the job is over, the genie works on the message, and thinks.
        """
        given:
            var start = Instant.parse('2026-10-04T19:48:55Z')
            var job = new Schedule.Running('run-7', 'job-3', 'Do something creative\nwith your desktop', start)
            var sent = genie.withDraft('How is it going?').send()

        when:
            var waiting = sent.withSchedule(sent.schedule().withRunning(Optional.of(job)))

        then:
            waiting.waitingOn(start.plusSeconds(72)) == new Genie.Waiting(
                    'Aladdin is doing a scheduled job first: Do something creative', '1:12')
            waiting.status() == 'doing a scheduled job'

        when:
            var answering = waiting.withSchedule(waiting.schedule().withRunning(Optional.empty()))

        then:
            answering.waitingOn(start.plusSeconds(100)) == new Genie.Waiting('Aladdin is thinking', '')
            answering.status() == 'thinking'
    }

    def 'A thought that starts in the middle of an answer does not break the answer in two'() {
        reportInfo """
            Some models, Mistral's among them, announce a thought in the middle of their text,
            often with nothing in it. The answer goes on in the same entry regardless, and a
            thought with nothing in it leaves no row behind. Seen with a real genie: its heading
            stood alone as one answer, and the rest followed as another.
        """
        when:
            var answered = genie.withDraft('find big files').send()
                    .hear(new PiEvent.Said('## Finding'))
                    .hear(new PiEvent.Thinking(''))
                    .hear(new PiEvent.Said(' big files'))
                    .hear(new PiEvent.Answered('## Finding big files', '', 10))
                    .hear(new PiEvent.Settled())

        then:
            answered.transcript().entries()*.kind() == [Entry.Kind.YOU, Entry.Kind.GENIE]
            answered.transcript().entries().last().text() == '## Finding big files'
    }

    def 'When the model fails, the chat says why, in the conversation'() {
        reportInfo """
            A refused key or an unreachable service ends the answer with an error. The user reads
            the reason where they are looking, in the chat, and the genie is ready for the next
            message. A retry the harness makes by itself is mentioned, so a slow answer is not a
            mystery. When the failed answer before it said why already, the retry does not say
            it again.
        """
        when:
            var failed = genie.withDraft('hi').send()
                    .hear(new PiEvent.Retrying(1, 3, '529 overloaded'))
                    .hear(new PiEvent.Answered('', '503 unavailable', 0))
                    .hear(new PiEvent.Retrying(2, 3, '503 unavailable'))
                    .hear(new PiEvent.Answered('', '401 there is no model key', 0))
                    .hear(new PiEvent.Settled())

        then:
            var entries = failed.transcript().entries()
            entries[1].isFailed()
            entries[1].text() == 'The model service had trouble of its own (529): overloaded. Trying again, 1 of 3.'
            entries[2].text() == 'The model service had trouble of its own (503): unavailable.'
            !entries[3].isFailed()
            entries[3].text() == 'Trying again, 2 of 3.'
            entries.last().isFailed()
            entries.last().text() == 'The model service refused the key (401): there is no model key.'
            failed.phase() == Genie.Phase.READY
    }

    def 'An error from the model is said in plain words'() {
        reportInfo """
            pi passes on what it got: oillamp's answer when it could not reach the model service,
            the word of the library pi uses for a connection that broke, or the service's status
            and its message in JSON. The chat says what happened in words, keeps the service's
            own message, and says who could not do what. These errors are real ones from a
            genie's conversations.
        """
        when:
            var failed = genie.withDraft('hi').send().hear(new PiEvent.Answered('', error, 0))

        then:
            failed.transcript().entries().last().isFailed()
            failed.transcript().entries().last().text().startsWith(said)

        where:
            error                                                                                  | said
            '502 oillamp: cannot reach the model service at api.eu.edenai.run — Connect timed out\n' |
                    'oillamp could not reach the model service at api.eu.edenai.run (Connect timed out), so the genie got no answer.'
            'Connection error.'                                                                    |
                    'The connection to the model service broke off before an answer came'
            '451: {"message":"Model x is not available on the EU endpoint.","type":"request_forbidden"}' |
                    'The model service refused the request (451): Model x is not available on the EU endpoint.'
            '429 {"error":{"message":"Rate limit \\"tier 1\\" reached"}}'                             |
                    'The model service takes no more requests for now, or the key\'s credit ran out (429): Rate limit "tier 1" reached'
            '401 oillamp: there is no model key for this session.'                                 |
                    'oillamp: there is no model key for this session.'
            'something odd'                                                                        |
                    'The model could not answer: something odd'
    }

    def 'A genie that was stopped is no problem'() {
        reportInfo """
            When the user stops the genie, the model's answer ends with an error too: pi says the
            request was aborted. The user did that themselves, so the chat says so in grey, as a
            notice, not as a problem in red beside a dizzy genie.
        """
        when:
            var stopped = genie.withDraft('hi').send()
                    .hear(new PiEvent.Said('Half'))
                    .hear(new PiEvent.Answered('Half', 'Request was aborted', 0))

        then:
            stopped.transcript().entries()*.text() == ['hi', 'Half', 'Stopped before the answer was done.']
            !stopped.transcript().entries().last().isFailed()
    }

    def 'An answer the genie used tools after said what it was doing'() {
        reportInfo """
            Between tool calls, a genie often says what it is about to do. Such an answer is
            marked, so the genie beside it in the chat keeps working there, while the answer it
            ends with stays an answer.
        """
        when:
            var worked = genie.withDraft('tidy up').send()
                    .hear(new PiEvent.Said('Let me look.'))
                    .hear(new PiEvent.Answered('Let me look.', '', 0))
                    .hear(new PiEvent.ToolStarted('c1', 'bash', 'ls'))
                    .hear(new PiEvent.ToolFinished('c1', false, 'notes.md'))
                    .hear(new PiEvent.ToolStarted('c2', 'bash', 'rm -r tmp'))
                    .hear(new PiEvent.ToolFinished('c2', false, ''))
                    .hear(new PiEvent.Said('Done.'))
                    .hear(new PiEvent.Answered('Done.', '', 0))
                    .hear(new PiEvent.Settled())

        then:
            var answers = worked.transcript().entries().findAll { it.kind() == Entry.Kind.GENIE }
            answers*.text() == ['Let me look.', 'Done.']
            answers*.beforeTools() == [true, false]
    }

    def 'A conversation opened again shows what the genie did then'() {
        reportInfo """
            A job's conversation is shown from pi's session file. Its answers say whether tools
            were used after them, and whether the model failed, so the chat shows the same as
            while the genie worked: what it said before using tools as such, and a failure as a
            problem in plain words rather than as something the genie said. oillamp keeps a failed
            answer's text and the error in one, as two paragraphs; they come apart again here.
        """
        given:
            var at = Instant.parse('2026-10-05T20:09:48Z')
            var none = Tuple.of(Lamp.Conversation.ToolCall)
            var said = { String id, String parent, String text, Tuple<Lamp.Conversation.ToolCall> calls, boolean failed ->
                new Lamp.Conversation.Entry(id, Optional.of(parent), at, Lamp.Conversation.Kind.MESSAGE_FROM_AGENT,
                        text, '', calls, Optional.empty(), failed)
            }
            var conversation = new Lamp.Conversation('c', 'run-44 (job-3)', 'x.jsonl', at, at, Tuple.of(Lamp.Conversation.Entry,
                    new Lamp.Conversation.Entry('q1', Optional.empty(), at, Lamp.Conversation.Kind.MESSAGE_TO_AGENT,
                            'Make something', '', none, Optional.empty(), false),
                    said('a1', 'q1', "I'll make a game.", Tuple.of(Lamp.Conversation.ToolCall,
                            new Lamp.Conversation.ToolCall('t1', 'write', 'game.html')), false),
                    new Lamp.Conversation.Entry('o1', Optional.of('a1'), at, Lamp.Conversation.Kind.TOOL_OUTPUT,
                            'Wrote it', '', none, Optional.of('write'), false),
                    said('a2', 'o1', '502 oillamp: cannot reach the model service at api.eu.edenai.run — Connect timed out\n', none, true),
                    said('a3', 'a2', 'Connection error.', none, true),
                    said('a4', 'a3', 'It is half done.\n\nRequest was aborted', none, true)))

        when:
            var opened = genie.hear(LampApiConversionUtil.history(conversation, 'a4'))

        then:
            var entries = opened.transcript().entries()
            entries*.kind() == [Entry.Kind.YOU, Entry.Kind.GENIE, Entry.Kind.NOTICE, Entry.Kind.NOTICE,
                                Entry.Kind.GENIE, Entry.Kind.NOTICE]
            entries*.beforeTools() == [false, true, false, false, false, false]
            entries*.isFailed() == [false, false, true, true, false, false]
            entries[2].text().startsWith('oillamp could not reach the model service at api.eu.edenai.run')
            entries[3].text().startsWith('The connection to the model service broke off')
            entries[4].text() == 'It is half done.'
            entries[5].text() == 'Stopped before the answer was done.'
    }

    def 'A genie that wakes again shows the conversation its harness kept'() {
        reportInfo """
            The conversation is kept by pi, in the genie's home in the sandbox. When the genie
            wakes, the chat asks for it and shows it, replacing whatever it showed before. The
            user's questions carry pi's ids, so each can be asked differently later, and the
            genie learns which entry it continues from.
        """
        when:
            var restored = genie.hear(new PiEvent.History(Tuple.of(PiEvent.History.Line,
                    new PiEvent.History.Line(true, 'hello', 'q1', false, false),
                    new PiEvent.History.Line(false, 'hi there', 'a1', false, false)), 'a1'))

        then:
            restored.transcript().entries()*.kind() == [Entry.Kind.YOU, Entry.Kind.GENIE]
            restored.transcript().entries()*.text() == ['hello', 'hi there']
            restored.transcript().entries()*.ref() == ['q1', 'a1']
            restored.conversations().here().leaf() == 'a1'
    }

    def 'After an answer the chat learns the ids of the questions, and keeps all it shows'() {
        reportInfo """
            A question the user just sent has no id of pi's yet, so it cannot be asked
            differently. After every answer the chat asks pi for the conversation again. When pi
            has the same questions the chat shows, the chat learns the ids. Either way it keeps
            its rows, with the tools the genie used and the files it handed over, which pi's
            conversation leaves out.
        """
        given:
            var answered = genie.withDraft('draw me a map').send()
                    .hear(new PiEvent.ToolStarted('c1', 'bash', 'draw-map'))
                    .hear(new PiEvent.Answered('Here it is.', '', 10))
                    .hear(new PiEvent.Settled())

        when:
            var learned = answered.learn(new PiEvent.History(Tuple.of(PiEvent.History.Line,
                    new PiEvent.History.Line(true, 'draw me a map', 'q1', false, false),
                    new PiEvent.History.Line(false, 'Here it is.', 'a1', false, false)), 'a1'))

        then:
            learned.transcript().entries()*.kind() == [Entry.Kind.YOU, Entry.Kind.TOOL, Entry.Kind.GENIE]
            learned.transcript().entries().first().ref() == 'q1'
            learned.canAskInstead(learned.transcript().entries().first())

        and: 'questions pi has differently, as when it expanded a template, leave the chat as it is'
            answered.learn(new PiEvent.History(Tuple.of(PiEvent.History.Line,
                    new PiEvent.History.Line(true, 'Draw a map of: ...', 'q1', false, false)), 'a1')).transcript() == answered.transcript()
    }

    def 'Asking a question differently leaves out what followed it, and the genie works on the new one'() {
        reportInfo """
            As in other chat apps, the user may change a question they asked earlier. pi keeps
            the old question, and what followed it, as a branch of its own, which the tree of
            conversations shows. The chat shows the branch the genie is on: everything up to the
            old question, and then the new one.
        """
        given:
            var talked = genie.hear(new PiEvent.History(Tuple.of(PiEvent.History.Line,
                    new PiEvent.History.Line(true, 'Plan a trip', 'q1', false, false),
                    new PiEvent.History.Line(false, 'Where to?', 'a1', false, false),
                    new PiEvent.History.Line(true, 'By train', 'q2', false, false),
                    new PiEvent.History.Line(false, 'Nice.', 'a2', false, false)), 'a2'))

        when:
            var changed = talked.askInstead('q2', '  By bike  ')

        then:
            changed.transcript().entries()*.text() == ['Plan a trip', 'Where to?', 'By bike']
            changed.phase() == Genie.Phase.WORKING

        and: 'only while the genie waits, and only for a question pi has an id for'
            !changed.canAskInstead(changed.transcript().entries().first())
            !talked.canAskInstead(talked.transcript().entries()[1])
    }

    def 'A question pi could not take leaves the genie waiting, and says why'() {
        reportInfo """
            pi may refuse a question: a plain one it cannot accept, or one asked differently
            that it could not send on to the model, such as when there is no key. Nothing more
            comes from pi then, so the genie would look busy for good. It waits for the user
            again instead, and the chat says why.
        """
        given:
            var working = genie.withDraft('hi').send()

        when:
            var refused = working.hear(new PiEvent.Refused('send_user_message', 'No API key found for the selected model.'))

        then:
            refused.phase() == Genie.Phase.READY
            refused.transcript().entries().last().isFailed()
            refused.transcript().entries().last().text().contains('No API key found')
    }

    def 'A new file in the outbox is announced once, and the files already there when it woke are not'() {
        reportInfo """
            A genie hands the user a file by putting it in ~/outbox. The app looks there after
            every answer. A file that was not there before is announced in the chat, so the user
            notices it; the list of files is kept for the user to save any of them. Files that
            were already there when the genie woke were announced before and are not again.
        """
        given:
            var awake = genie.withHandouts(Tuple.of(Handout, new Handout('old.txt', 12)))

        when:
            var told = awake.outbox(Tuple.of(Handout, new Handout('old.txt', 12), new Handout('sunset.png', 204_800)))
                            .outbox(Tuple.of(Handout, new Handout('old.txt', 12), new Handout('sunset.png', 204_800)))

        then:
            told.transcript().entries().size() == 1
            with(told.transcript().entries().first()) {
                kind() == Entry.Kind.FILE
                title() == 'sunset.png'
                text().contains('200.0 KB')
            }
            told.handouts().size() == 2
    }

    def 'When the genie goes to sleep, nothing is left half written'() {
        reportInfo """
            The user may put a genie to sleep while it answers, or its sandbox may fail. The
            answer then stays as far as it got, and nothing in the chat is shown as still
            running. A failure is said in the chat as well as in the genie's status.
        """
        given:
            var interrupted = genie.withDraft('hi').send().hear(new PiEvent.Said('Once upon'))
                    .hear(new PiEvent.ToolStarted('c', 'bash', 'sleep 100'))

        expect:
            interrupted.asleep().transcript().entries().every { !it.isWriting() }
            with(interrupted.broken('the sandbox stopped')) {
                phase() == Genie.Phase.BROKEN
                activity() == 'the sandbox stopped'
                transcript().entries().last().isFailed()
            }
    }
}
