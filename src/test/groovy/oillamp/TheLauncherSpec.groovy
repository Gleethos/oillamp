package oillamp

import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 *  The single-file launcher, {@code src/packaging/launcher.sh}: a shell script with a compressed
 *  archive attached, which unpacks itself into the cache directory once and then runs the Java
 *  runtime it unpacked.
 *
 *  <p>These scenarios build a small launcher from the real script, with a stand-in for the Java
 *  runtime that only says it was started, and run it the way a user does.
 */
class TheLauncherSpec extends Specification {

    @TempDir Path tmp

    def 'A cache directory damaged by an earlier run is replaced, not left to fail every time'() {
        reportInfo """
            The launcher unpacks into a directory named after its exact build, and skips unpacking
            when that directory already holds a Java runtime. A directory without one, left by
            an unpack that was cut short or by someone tidying their cache, used to stay: the new
            copy was moved inside it rather than in its place, and every run then failed with
            "found no Java runtime", whatever the user did short of deleting the cache by hand.
        """
        given: 'a launcher, and a cache directory for its build with no Java runtime in it'
            var launcher = aLauncher()
            var unpacked = whereItUnpacks(launcher)
            Files.createDirectories(unpacked.resolve('lib'))

        when:
            var run = start(launcher, 'version')

        then: 'it unpacks again and runs'
            run.waitFor() == 0
            run.inputStream.text.contains('the runtime started with: dev.oillamp.OilLamp version')
            Files.isExecutable(unpacked.resolve('runtime/bin/java'))

        and: 'nothing is left nested inside the cache directory'
            leftoversUnder(unpacked.parent).isEmpty()
    }

    def 'Several launchers started at once leave one copy, not a stray one each'() {
        reportInfo """
            The first run of a new build unpacks about 100 MB. Two oillamp commands started at the
            same moment, say a session and a `status` in another terminal, both unpack, and the
            second to finish finds the directory already there. It was meant to give up quietly;
            instead its copy was moved inside the first one's, and stayed there for good.
        """
        given:
            var launcher = aLauncher()
            var unpacked = whereItUnpacks(launcher)

        when: 'five start at once on an empty cache'
            var runs = (1..5).collect { start(launcher, 'version') }

        then: 'every one of them runs'
            runs.every { it.waitFor() == 0 }

        and: 'and exactly one copy is left'
            Files.isExecutable(unpacked.resolve('runtime/bin/java'))
            leftoversUnder(unpacked.parent).isEmpty()
    }

    // ─── building and running a launcher ───────────────────────────────────────────────────

    /**
     *  The real launcher script with a small archive attached. The archive holds a stand-in for
     *  the Java runtime, and a few megabytes of filler so that unpacking takes long enough for
     *  launchers started together to overlap, as a real 100 MB unpack does.
     */
    private Path aLauncher() {
        var payload = Files.createDirectories(tmp.resolve('payload'))
        var java = Files.createDirectories(payload.resolve('runtime/bin')).resolve('java')
        Files.writeString(java, '#!/bin/sh\nshift 4; echo "the runtime started with: $*"\n')
        Files.setPosixFilePermissions(java, PosixFilePermissions.fromString('rwxr-xr-x'))
        var filler = new byte[8 * 1024 * 1024]
        new Random(1).nextBytes(filler)
        Files.write(Files.createDirectories(payload.resolve('lib')).resolve('filler.jar'), filler)

        var archive = tmp.resolve('payload.tar.gz')
        assert new ProcessBuilder('tar', '-czf', archive.toString(), '-C', payload.toString(), '.')
                .inheritIO().start().waitFor() == 0

        var script = Files.readString(Path.of('src/packaging/launcher.sh'))
                          .replace('@VERSION@', '0.0.0').replace('@FINGERPRINT@', 'test')
        var launcher = tmp.resolve('oillamp')
        Files.writeString(launcher, script)
        Files.write(launcher, Files.readAllBytes(archive), java.nio.file.StandardOpenOption.APPEND)
        Files.setPosixFilePermissions(launcher, PosixFilePermissions.fromString('rwxr-xr-x'))
        launcher
    }

    private Process start(Path launcher, String... arguments) {
        var builder = new ProcessBuilder([launcher.toString(), *arguments])
        builder.environment().put('XDG_CACHE_HOME', tmp.resolve('cache').toString())
        builder.redirectErrorStream(true).start()
    }

    private Path whereItUnpacks(Path launcher) {
        var where = start(launcher, '--where')
        assert where.waitFor() == 0
        Path.of(where.inputStream.text.trim())
    }

    /** Anything a launcher left half-done: a staging directory, wherever it ended up. */
    private static List<Path> leftoversUnder(Path cache) {
        if (!Files.exists(cache)) return []
        Files.walk(cache).withCloseable { paths ->
            paths.filter { it.fileName.toString().contains('.incoming.') }.toList()
        }
    }
}
