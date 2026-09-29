package gui

import dev.gui.genie.SessionFiles
import dev.gui.model.Conversations
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

/**
 *  Where the tree of conversations comes from: pi's session files, in the genie's home.
 *
 *  <p>pi writes one file per conversation into {@code ~/.pi/agent/sessions/--home-agent--},
 *  a header line and then one line per entry. The genie's home is a directory in its lamp, on
 *  this computer, so Genies reads the files there, and the tree is shown while the genie sleeps
 *  too. The lines here are shaped like the ones pi 0.87 writes.
 */
class ReadingAGeniesConversationsSpec extends Specification {

    @TempDir Path home

    def 'Every conversation the genie kept is read, newest first'() {
        reportInfo """
            Each file is one conversation. Its entries become the steps the tree is folded
            from: questions of the user's with their text, and everything else without. The
            conversation last written to comes first.
        """
        given:
            session('older.jsonl', 's-old', '2026-09-28T10:00:00.000Z',
                    '{"type":"message","id":"q1","parentId":null,"timestamp":"2026-09-28T10:00:01.000Z","message":{"role":"user","content":[{"type":"text","text":"Plan a trip"}]}}')
            session('newer.jsonl', 's-new', '2026-09-29T10:00:00.000Z',
                    '{"type":"model_change","id":"m","parentId":null,"timestamp":"2026-09-29T10:00:00.500Z","provider":"edenai","modelId":"x"}',
                    '{"type":"message","id":"q1","parentId":"m","timestamp":"2026-09-29T10:00:01.000Z","message":{"role":"user","content":"Fix my printer"}}',
                    '{"type":"message","id":"a1","parentId":"q1","timestamp":"2026-09-29T10:00:02.000Z","message":{"role":"assistant","content":[{"type":"text","text":"Sure."}]}}')

        when:
            var conversations = SessionFiles.read(home)

        then:
            conversations*.id() == ['s-new', 's-old']
            conversations*.title() == ['Fix my printer', 'Plan a trip']
            conversations.first().file() == Conversations.DIRECTORY + '/newer.jsonl'
            conversations.first().modified() == '2026-09-29T10:00:02.000Z'
            conversations.first().steps()*.asked() == [false, true, false]
            conversations.first().steps()*.parent() == ['', 'm', 'q1']
    }

    def 'A name given to a conversation is its title'() {
        reportInfo """
            pi records a name for a conversation as an entry of its own, and a later one
            replaces an earlier one.
        """
        given:
            session('named.jsonl', 's1', '2026-09-29T10:00:00.000Z',
                    '{"type":"message","id":"q1","parentId":null,"timestamp":"t1","message":{"role":"user","content":"Hi"}}',
                    '{"type":"session_info","id":"n1","parentId":"q1","timestamp":"t2","name":"Greetings"}')

        expect:
            SessionFiles.read(home).first().title() == 'Greetings'
    }

    def 'A line being written, or broken, is skipped and the rest is read'() {
        reportInfo """
            pi may be writing a line at the moment Genies reads the file, and the genie itself
            could put anything into its home. Whatever is not a line of JSON is left out; so is a
            file with no header at all.
        """
        given:
            session('cut.jsonl', 's1', '2026-09-29T10:00:00.000Z',
                    '{"type":"message","id":"q1","parentId":null,"timestamp":"t1","message":{"role":"user","content":"Hi"}}',
                    '{"type":"message","id":"a1","parentId":"q1","timest')
            Files.writeString(directory().resolve('headless.jsonl'), 'not json at all\n')

        when:
            var conversations = SessionFiles.read(home)

        then:
            conversations*.id() == ['s1']
            conversations.first().steps()*.id() == ['q1']
    }

    def 'No link in the genie\'s home is followed'() {
        reportInfo """
            The genie writes its home. A link planted there could point anywhere on this
            computer, and Genies reads as the user. So a session file that is a link is left out,
            and so is the whole directory when a link leads to it.
        """
        given:
            var outside = Files.createDirectories(home.resolveSibling(home.fileName.toString() + '-outside'))
            Files.writeString(outside.resolve('secret.jsonl'), '{"type":"session","id":"secret","timestamp":"t"}\n')
            Files.createDirectories(directory())
            Files.createSymbolicLink(directory().resolve('linked.jsonl'), outside.resolve('secret.jsonl'))

        expect:
            SessionFiles.read(home).isEmpty()

        when: 'the directory itself is a link'
            Files.delete(directory().resolve('linked.jsonl'))
            Files.delete(directory())
            Files.createSymbolicLink(directory(), outside)

        then:
            SessionFiles.read(home).isEmpty()
    }

    def 'A genie that never talked has no conversations'() {
        reportInfo """
            Before the genie first woke, there is no directory of pi's at all.
        """
        expect:
            SessionFiles.read(home).isEmpty()
    }

    def 'Deleting a conversation deletes its file, and nothing else'() {
        reportInfo """
            The user deletes a conversation from the tree. Its file goes; anything that is not
            one of pi's session files in that directory is refused, however the name is spelled.
        """
        given:
            session('doomed.jsonl', 's1', 't')
            session('kept.jsonl', 's2', 't')

        when:
            SessionFiles.delete(home, Conversations.DIRECTORY + '/doomed.jsonl')

        then:
            SessionFiles.read(home)*.id() == ['s2']

        when:
            SessionFiles.delete(home, Conversations.DIRECTORY + '/../../settings.json')

        then:
            thrown(IOException)
    }

    // ─── helpers ───────────────────────────────────────────────────────────────────────────

    private Path directory() { home.resolve(Conversations.DIRECTORY) }

    private void session(String name, String id, String timestamp, String... entries) {
        Files.createDirectories(directory())
        var header = """{"type":"session","version":3,"id":"$id","timestamp":"$timestamp","cwd":"/home/agent"}"""
        Files.writeString(directory().resolve(name), ([header] + (entries as List)).join('\n') + '\n')
    }
}
