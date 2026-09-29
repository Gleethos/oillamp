package gui

import dev.gui.model.Entry
import dev.gui.model.Genie
import dev.gui.model.Handout
import dev.gui.pi.PiEvent
import spock.lang.Specification
import sprouts.Tuple

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
            mystery.
        """
        when:
            var failed = genie.withDraft('hi').send()
                    .hear(new PiEvent.Retrying(1, 3, '529 overloaded'))
                    .hear(new PiEvent.Answered('', '401 there is no model key', 0))
                    .hear(new PiEvent.Settled())

        then:
            var entries = failed.transcript().entries()
            entries[1].text().contains('Trying again, 1 of 3')
            entries.last().isFailed()
            entries.last().text() == '401 there is no model key'
            failed.phase() == Genie.Phase.READY
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
                    new PiEvent.History.Line(true, 'hello', 'q1'), new PiEvent.History.Line(false, 'hi there', 'a1')), 'a1'))

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
                    new PiEvent.History.Line(true, 'draw me a map', 'q1'), new PiEvent.History.Line(false, 'Here it is.', 'a1')), 'a1'))

        then:
            learned.transcript().entries()*.kind() == [Entry.Kind.YOU, Entry.Kind.TOOL, Entry.Kind.GENIE]
            learned.transcript().entries().first().ref() == 'q1'
            learned.canAskInstead(learned.transcript().entries().first())

        and: 'questions pi has differently, as when it expanded a template, leave the chat as it is'
            answered.learn(new PiEvent.History(Tuple.of(PiEvent.History.Line,
                    new PiEvent.History.Line(true, 'Draw a map of: ...', 'q1')), 'a1')).transcript() == answered.transcript()
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
                    new PiEvent.History.Line(true, 'Plan a trip', 'q1'), new PiEvent.History.Line(false, 'Where to?', 'a1'),
                    new PiEvent.History.Line(true, 'By train', 'q2'), new PiEvent.History.Line(false, 'Nice.', 'a2')), 'a2'))

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
