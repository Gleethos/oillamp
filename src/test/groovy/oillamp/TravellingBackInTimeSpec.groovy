package oillamp

import dev.lamp.ExitStatus
import dev.lamp.Lamp
import dev.lamp.LampEvent
import dev.lamp.LampEvent.SaveKind
import spock.lang.IgnoreIf
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir
import spock.lang.Timeout

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 *  {@code save}, {@code history} and {@code restore}: a lamp's history.
 *
 *  <p>An agent can wreck its own home: delete a project, break a toolchain, fill a config file
 *  with nonsense. The sandbox keeps that away from the user's machine, but the work inside is
 *  still lost. A lamp therefore keeps snapshots of the agent's home and its configuration, in a
 *  git repository beside the home that the agent cannot see, and can go back to any of them.
 *
 *  <p>The files are real, in a temporary directory, as everywhere in these scenarios. A snapshot
 *  is only worth something if exact bytes, permissions and links come back, and a simulated disk
 *  would hide exactly the mistakes that lose them.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class TravellingBackInTimeSpec extends Specification {

    @TempDir Path tmp
    @Subject ScenarioHost host

    final List<LampEvent> reported = new java.util.concurrent.CopyOnWriteArrayList<>()
    Thread session

    def setup() {
        host = new ScenarioHost(tmp)
        host.machine { it.reallyRuns('ssh-keygen') }
    }

    def cleanup() {
        if (session?.alive) {
            host.oillamp.run('stop', host.lampPath().toString())
            session.join(20_000)
        }
    }

    def 'A session saves the lamp as it starts'() {
        reportInfo """
            Nobody remembers to save before the thing that goes wrong. So every session begins
            with a save, taken before the sandbox runs, and whatever the agent does in that
            session can be undone by going back to it. The snapshot says it is a startup save,
            and which session it began, so a person reading the history knows what it is.
        """
        given: 'a new lamp'
            var lamp = host.lampPath()

        when: 'a session runs on it and ends, having changed nothing'
            var ran = host.oillamp.run('at', lamp.toString())

        then:
            ran.succeeded()

        and: 'the lamp was saved as the session started'
            var saved = ran.events().findAll { it instanceof LampEvent.Saved }*.snapshot()
            saved*.kind() == [SaveKind.STARTUP]
            saved[0].session().isPresent()

        and: 'nothing had changed when it ended, so there was no second save'
            ran.events().any { it instanceof LampEvent.Info && it.text().startsWith('nothing changed since') }
            host.oillamp.run('history', lamp.toString()).events().find { it instanceof LampEvent.History }.snapshots()*.kind() == [SaveKind.STARTUP]
    }

    def 'A session that changed the lamp is saved again as it ends'() {
        reportInfo """
            The end of a session is the other moment worth keeping: it is the work the agent did.
            The save runs after the container has stopped, so nothing is writing while it reads.
            It is part of the shutdown itself, which is also what runs after Ctrl-C.
        """
        given: 'a lamp whose agent writes a file during the session'
            var lamp = host.lampPath()
            var oillamp = host.oillamp.observedBy { event ->
                if (event instanceof LampEvent.Summary && event.title() == 'your session is up')
                    Files.writeString(home(lamp).resolve('workspace/notes.txt'), 'done for today\n')
            }

        when:
            var ran = oillamp.run('at', lamp.toString())

        then: 'the history has the startup save and, newest first, the shutdown save'
            ran.succeeded()
            host.oillamp.run('history', lamp.toString()).events().find { it instanceof LampEvent.History }.snapshots()*.kind() == [SaveKind.SHUTDOWN, SaveKind.STARTUP]
    }

    def 'A save by hand keeps what the person wrote, and saves nothing when nothing changed'() {
        reportInfo """
            A person saves by hand to mark a moment, such as "before the upgrade", and will look
            for that moment by what they wrote. A second save with nothing changed would only
            add a line to the history that is identical to the one before, so there is none.
        """
        given:
            var lamp = aLampThatHasRun()
            Files.writeString(home(lamp).resolve('workspace/plan.md'), '# the plan\n')

        when:
            var first = host.oillamp.run('save', lamp.toString(), '--message', 'before the upgrade')
            var second = host.oillamp.run('save', lamp.toString(), '-m', 'again')

        then: 'the first made a snapshot, marked as saved while no session ran'
            first.succeeded()
            var made = first.events().find { it instanceof LampEvent.Saved }.snapshot()
            made.kind() == SaveKind.IDLE
            made.message() == 'before the upgrade'
            first.console().contains('saved ' + made.shortId())

        and: 'the second found nothing to save'
            second.succeeded()
            !second.events().any { it instanceof LampEvent.Saved }
            second.console().contains('nothing changed since ' + made.shortId())

        and: 'the history lists it by what the person wrote'
            var listed = host.oillamp.run('history', lamp.toString())
            listed.console().contains(made.shortId())
            listed.console().contains('idle save')
            listed.console().contains('before the upgrade')
    }

    def 'A save message is kept as it was written, however it was given'() {
        reportInfo """
            `--message=text` is the same as `--message text` and `-m text`. Whatever follows
            --message is the message, even when it looks like an option or is `--` itself, so a
            person who marks a moment with "-- after the upgrade --" finds exactly that in the
            history.
        """
        given:
            var lamp = aLampThatHasRun()

        when: 'the lamp changes before each save, so each one makes a snapshot'
            var notes = home(lamp).resolve('workspace/notes.txt')
            Files.writeString(notes, 'first')
            var joined = host.oillamp.run('save', lamp.toString(), '--message=before the upgrade')
            Files.writeString(notes, 'second')
            var dashed = host.oillamp.run('save', lamp.toString(), '-m', '-- after the upgrade --')
            Files.writeString(notes, 'third')
            var bare = host.oillamp.run('save', lamp.toString(), '--message', '--')

        then:
            joined.events().find { it instanceof LampEvent.Saved }.snapshot().message() == 'before the upgrade'
            dashed.events().find { it instanceof LampEvent.Saved }.snapshot().message() == '-- after the upgrade --'
            bare.events().find { it instanceof LampEvent.Saved }.snapshot().message() == '--'
    }

    def 'A restore brings back exactly what was saved, and removes what came after'() {
        reportInfo """
            The whole point of a snapshot is that the agent's home comes back as it was: every
            byte, and the things git on its own would lose. A project the agent cloned has its
            own .git directory, which a plain `git add` would store as a bare pointer, losing the
            project. A private key must come back as 0600, or ssh refuses to use it. A link must
            come back as a link, an empty directory as a directory, and a directory the agent made
            read-only, as Go does with its module cache, must neither stop the restore nor come
            back writable.

            Whatever appeared after the snapshot is removed, or the lamp would be a mixture of
            two moments rather than the one the person chose.
        """
        given: 'a lamp whose agent has a cloned project, a key, a script, a link and odd directories'
            var lamp = aLampThatHasRun()
            var home = home(lamp)
            write(home, 'workspace/game/.git/HEAD', 'ref: refs/heads/main\n')
            write(home, 'workspace/game/.git/objects/ab/cdef', 'not really an object\n')
            write(home, 'workspace/game/src/Main.java', 'class Main {}\n')
            write(home, '.ssh/id_ed25519', 'PRIVATE KEY\n')
            chmod(home.resolve('.ssh/id_ed25519'), 'rw-------')
            chmod(home.resolve('.ssh'), 'rwx------')
            write(home, 'bin/build.sh', '#!/bin/sh\necho build\n')
            chmod(home.resolve('bin/build.sh'), 'rwxr-xr-x')
            Files.createSymbolicLink(home.resolve('latest'), Path.of('workspace/game'))
            Files.createDirectories(home.resolve('empty/inside'))
            write(home, 'go/pkg/mod/lib@v1/lib.go', 'package lib\n')
            chmod(home.resolve('go/pkg/mod/lib@v1'), 'r-xr-xr-x')
            var saved = host.oillamp.run('save', lamp.toString()).events().find { it instanceof LampEvent.Saved }.snapshot()

        and: 'then the agent wrecks it'
            deleteTree(home.resolve('workspace/game'))
            Files.writeString(home.resolve('bin/build.sh'), 'rm -rf everything\n')
            chmod(home.resolve('.ssh/id_ed25519'), 'rw-r--r--')
            Files.delete(home.resolve('latest'))
            write(home, 'workspace/junk.bin', 'x' * 10_000)
            chmod(home.resolve('go/pkg/mod/lib@v1'), 'rwxr-xr-x')
            Files.delete(home.resolve('go/pkg/mod/lib@v1/lib.go'))
            chmod(home.resolve('go/pkg/mod/lib@v1'), 'r-xr-xr-x')

        when: 'the person restores the snapshot, by the first eight characters of its id'
            var restored = host.oillamp.run('restore', lamp.toString(), saved.shortId())

        then:
            restored.succeeded()
            restored.events().any { it instanceof LampEvent.Restored && it.target().id() == saved.id() }

        and: 'the cloned project is back, its .git directory included'
            Files.readString(home.resolve('workspace/game/.git/HEAD')) == 'ref: refs/heads/main\n'
            Files.readString(home.resolve('workspace/game/.git/objects/ab/cdef')) == 'not really an object\n'
            Files.readString(home.resolve('workspace/game/src/Main.java')) == 'class Main {}\n'

        and: 'contents, permissions and links are as they were'
            Files.readString(home.resolve('bin/build.sh')) == '#!/bin/sh\necho build\n'
            mode(home.resolve('bin/build.sh')) == 'rwxr-xr-x'
            mode(home.resolve('.ssh/id_ed25519')) == 'rw-------'
            mode(home.resolve('.ssh')) == 'rwx------'
            Files.isSymbolicLink(home.resolve('latest'))
            Files.readSymbolicLink(home.resolve('latest')) == Path.of('workspace/game')
            Files.isDirectory(home.resolve('empty/inside'))

        and: 'the read-only directory got its file back, and is read-only again'
            Files.readString(home.resolve('go/pkg/mod/lib@v1/lib.go')) == 'package lib\n'
            mode(home.resolve('go/pkg/mod/lib@v1')) == 'r-xr-xr-x'

        and: 'what the agent added afterwards is gone'
            !Files.exists(home.resolve('workspace/junk.bin'))
    }

    def 'A restore can itself be undone'() {
        reportInfo """
            Restoring the wrong snapshot must not be a second disaster. So a restore first saves
            the lamp as it is, and says how to go back to that. The history only ever grows: the
            restore adds a snapshot of its own rather than removing the ones after its target.
        """
        given:
            var lamp = aLampThatHasRun()
            var home = home(lamp)
            write(home, 'workspace/story.txt', 'chapter one\n')
            var early = host.oillamp.run('save', lamp.toString()).events().find { it instanceof LampEvent.Saved }.snapshot()
            Files.writeString(home.resolve('workspace/story.txt'), 'chapter two\n')

        when: 'the person restores the early snapshot'
            var restored = host.oillamp.run('restore', lamp.toString(), early.shortId())

        then: 'the lamp was saved first, and the way back is printed'
            restored.succeeded()
            var safety = restored.events().find { it instanceof LampEvent.Saved }.snapshot()
            safety.kind() == SaveKind.BEFORE_RESTORE
            restored.console().contains('oillamp restore ' + lamp + ' ' + safety.shortId())
            Files.readString(home.resolve('workspace/story.txt')) == 'chapter one\n'

        when: 'they change their mind'
            var undone = host.oillamp.run('restore', lamp.toString(), safety.shortId())

        then: 'chapter two is back'
            undone.succeeded()
            Files.readString(home.resolve('workspace/story.txt')) == 'chapter two\n'

        and: 'every step is in the history, newest first'
            host.oillamp.run('history', lamp.toString()).events().find { it instanceof LampEvent.History }.snapshots()*.kind().take(4) == [SaveKind.RESTORE, SaveKind.RESTORE, SaveKind.BEFORE_RESTORE, SaveKind.IDLE]
    }

    def 'A restore brings back the configuration too'() {
        reportInfo """
            oillamp.toml says what the sandbox may reach and how it looks. A snapshot that brought
            back the agent's home but kept today's network rules would not be the lamp as it was.
        """
        given:
            var lamp = aLampThatHasRun()
            var config = lamp.resolve('oillamp.toml')
            var original = Files.readString(config)
            host.oillamp.run('save', lamp.toString())
            var saved = host.oillamp.run('history', lamp.toString()).events().find { it instanceof LampEvent.History }.snapshots().first()
            Files.writeString(config, original + '\n# changed later\n')

        when:
            var restored = host.oillamp.run('restore', lamp.toString(), saved.shortId())

        then:
            restored.succeeded()
            Files.readString(config) == original
            mode(config) == 'rw-------'
    }

    def 'A restore is refused while the session runs, but a save is not'() {
        reportInfo """
            Replacing the agent's home under running programs would leave them working in files
            that changed beneath them, so a restore needs the session stopped, and says so.

            Saving is different: the moment a person most wants a snapshot is while the session
            runs, just before they let the agent try something risky. That save is marked as a
            running save, because a program writing at that moment may leave a file half written.
        """
        given: 'a running session'
            var lamp = aLampThatHasRun()
            var earlier = host.oillamp.run('history', lamp.toString()).events().find { it instanceof LampEvent.History }.snapshots().first()
            startASession(lamp)
            write(home(lamp), 'workspace/risky.txt', 'about to try something\n')

        when:
            var restore = host.oillamp.run('restore', lamp.toString(), earlier.shortId())

        then:
            restore.status() == ExitStatus.LAMP_BUSY
            restore.reported('OIL-HISTORY-005')
            Files.exists(home(lamp).resolve('workspace/risky.txt'))

        when:
            var save = host.oillamp.run('save', lamp.toString(), '-m', 'before the risky part')

        then:
            save.succeeded()
            var made = save.events().find { it instanceof LampEvent.Saved }.snapshot()
            made.kind() == SaveKind.RUNNING
            made.session().isPresent()
    }

    def 'A snapshot that does not exist is named in the refusal'() {
        reportInfo """
            A restore replaces the agent's home, so it happens only for a snapshot oillamp is
            sure the person meant: a unique beginning of an id, at least four characters long.
            Anything else is refused with the reason and a pointer to the list.
        """
        given:
            var lamp = aLampThatHasRun()

        when:
            var outcome = host.oillamp.run('restore', lamp.toString(), given)

        then:
            outcome.status() == ExitStatus.USAGE
            outcome.reported('OIL-HISTORY-001')
            outcome.console().contains('oillamp history ' + lamp)

        where:
            given << ['0000000', 'ab', 'not-an-id']
    }

    def 'An application saves, lists and restores through the Lamp API'() {
        reportInfo """
            An application that holds lamps, such as a desktop app with an agent in each, offers
            the same three things with its own buttons. It gets values back rather than text to
            parse, and a failure as an exception that carries the full problem.
        """
        given:
            var lamp = aLampThatHasRun()
            var lamps = Lamp.at(lamp).launchedBy(host.launcher)
            write(home(lamp), 'workspace/draft.txt', 'first draft\n')

        when:
            var saved = lamps.save('first draft').orElseThrow()
            Files.writeString(home(lamp).resolve('workspace/draft.txt'), 'ruined\n')
            var listed = lamps.history()
            var restored = lamps.restore(saved.shortId())

        then:
            saved.message() == 'first draft'
            listed.first().id() == saved.id()
            restored.kind() == SaveKind.RESTORE
            Files.readString(home(lamp).resolve('workspace/draft.txt')) == 'first draft\n'

        when: 'it asks for a snapshot that is not there'
            lamps.restore('deadbeef')

        then:
            var failed = thrown(Lamp.Failed)
            failed.problem().code().value() == 'OIL-HISTORY-001'
            failed.status() == ExitStatus.USAGE
    }

    @IgnoreIf({ !['/usr/bin/git', '/bin/git', '/usr/local/bin/git'].any { Files.isExecutable(Path.of(it)) } })
    def 'git itself can read the history'() {
        reportInfo """
            oillamp writes the history in git's format itself, rather than by running git, so
            that a nested .git directory is stored like any other. It is still an ordinary
            repository: a person who knows git can read it with git, without oillamp.
        """
        given:
            var lamp = aLampThatHasRun()
            write(home(lamp), 'workspace/game/.git/HEAD', 'ref: refs/heads/main\n')
            write(home(lamp), 'workspace/readme.txt', 'hello\n')
            host.oillamp.run('save', lamp.toString(), '--message', 'with a nested repository')
            var repository = lamp.resolve('.oillamp/history')
            var agentDir = home(lamp).fileName.toString()

        expect: 'git log reads the messages'
            git(repository, 'log', '--format=%s').readLines().first() == 'idle save: with a nested repository'

        and: 'git show reads a file'
            git(repository, 'show', "HEAD:${agentDir}/workspace/readme.txt") == 'hello\n'

        and: 'git finds nothing wrong beyond the nested .git it warns about'
            var check = new ProcessBuilder('git', '--git-dir', repository.toString(), 'fsck', '--strict')
                    .redirectErrorStream(true).start()
            var said = check.inputStream.text
            check.waitFor() == 0 || said.readLines().every { it.contains('hasDotgit') }
    }

    // ─── helpers ───────────────────────────────────────────────────────────────────────────

    /** A lamp that exists, has run one session, and so has its first snapshot. */
    private Path aLampThatHasRun() {
        var lamp = host.lampPath()
        assert host.oillamp.run('at', lamp.toString()).succeeded()
        lamp
    }

    private static Path home(Path lamp) { Lamp.agentHome(lamp).orElseThrow() }

    private static void write(Path home, String relative, String content) {
        var file = home.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }

    private static void chmod(Path path, String permissions) {
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions))
    }

    private static String mode(Path path) {
        PosixFilePermissions.toString(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS))
    }

    private static void deleteTree(Path root) {
        Files.walk(root).sorted(Comparator.reverseOrder()).each { Files.delete(it) }
    }

    private static String git(Path repository, String... arguments) {
        var process = new ProcessBuilder(['git', '--git-dir', repository.toString()] + arguments.toList()).start()
        var output = process.inputStream.text
        assert process.waitFor() == 0 : process.errorStream.text
        output
    }

    private void startASession(Path lamp) {
        host.machine { it.windowsStayOpenFor(Duration.ofSeconds(60)) }
        var oillamp = host.oillamp.observedBy { reported.add(it) }
        session = Thread.start { oillamp.run('at', lamp.toString()) }
        var deadline = System.currentTimeMillis() + 30_000
        while (!reported.any { it instanceof LampEvent.Summary && it.title() == 'your session is up' }) {
            assert System.currentTimeMillis() < deadline : 'the session never came up'
            Thread.sleep(50)
        }
    }
}
