package oillamp

import dev.oillamp.ExitStatus
import dev.oillamp.LampEvent
import dev.oillamp.Machine
import dev.oillamp.OilLamp
import groovy.json.JsonSlurper
import spock.lang.Requires
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Stepwise
import spock.lang.Tag

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * The model key, in a real session on this machine: real image, real container, real harnesses,
 * and, when the key in this environment is a real Eden AI key, real model requests.
 *
 * <p>oillamp is started exactly as a user starts it, {@code oillamp at <dir>}, as a separate process
 * with {@code EDENAI_API_KEY} in its environment. Its terminal is configured to be the shell command
 * itself, with no window, so the spike opens nothing on this desktop. Commands then reach the
 * sandbox over ssh, as the agent's shell does.
 *
 * <p>When no key is set here, a made-up one stands in: every check that the key stays out still
 * runs, and the checks that need Eden AI to answer are skipped.
 *
 * <p>The key itself never appears in this spike's output. Checks that involve it compare in a
 * helper and report only whether, and where, it was found.
 */
@Tag('spike')
@Stepwise
@Requires({ Spike.containerNetworkWorks() })
class KeepingTheModelKeyOutOfARealSandboxSpec extends Specification {

    static final String REAL_KEY = System.getenv('EDENAI_API_KEY') ?: ''
    /** A model the team's Eden AI account allows on the EU endpoint. */
    static final String MODEL = System.getenv('OILLAMP_SPIKE_MODEL') ?: 'mistral/mistral-small-latest'

    @Shared Path directory = Files.createTempDirectory('oillamp-spike-model-key-').resolve('lamp')
    @Shared String key = REAL_KEY ?: 'sk-oillamp-spike-canary-' + UUID.randomUUID()
    @Shared Process engine
    @Shared Path engineOutput = directory.parent.resolve('engine.log')

    def cleanupSpec() {
        // However the scenarios ended, the session ends here: first as a user would end it, then
        // with SIGTERM, which oillamp treats like Ctrl-C and still shuts down cleanly.
        if (engine?.alive) {
            oillamp('stop', directory.toString())
            if (!engine.waitFor(2, TimeUnit.MINUTES)) engine.destroy()
            engine.waitFor(2, TimeUnit.MINUTES)
        }
        if (Files.exists(directory.resolve('.oillamp/lamp.json'))) oillamp('remove', directory.toString(), '--yes')
        Spike.removeTree(directory.parent.toString())
    }

    def 'A session starts with the key in oillamp\'s environment, and the key goes no further'() {
        reportInfo """
            The user starts oillamp with EDENAI_API_KEY set, as the quick start says. oillamp reads
            it and keeps it in memory. It must not write it into the lamp: not into the settings
            file the container reads, not into the agent's home, not into its own terminal output.
            The lamp directory is on the user's disk, and the terminal output is what people paste
            into bug reports.

            The first start after a change to the image builds the image, which takes minutes.
        """
        given: 'a lamp whose shell "window" is the shell command itself, so no window opens'
            Files.createDirectories(directory)
            Files.writeString(directory.resolve('oillamp.toml'), '''
                schema_version = 1
                [viewer]
                open_on_start = false
                [terminal]
                command = ["{cmd}"]
            '''.stripIndent())

        when: 'oillamp is started as a user starts it, with the key in its environment'
            var java = Path.of(System.getProperty('java.home'), 'bin', 'java').toString()
            var builder = new ProcessBuilder(java, '-cp', System.getProperty('java.class.path'),
                                             'dev.oillamp.OilLamp', 'at', directory.toString())
            builder.environment().put('EDENAI_API_KEY', key)
            engine = builder.redirectErrorStream(true).redirectOutput(engineOutput.toFile()).start()

        then: 'the session comes up'
            eventually(Duration.ofMinutes(25)) {
                !engine.alive || oillamp('status', directory.toString()).succeeded()
            }
            engine.alive

        and: 'no file in the lamp holds the key, whatever it is called'
            filesHolding(directory).isEmpty()

        and: 'and oillamp did not print it'
            !holdsKey(Files.readString(engineOutput))
    }

    def 'Nothing the agent can read holds the key: no file, no environment, no process'() {
        reportInfo """
            The agent can read every file its user may read, the environment of every process it
            runs, and their command lines. Here all of that is copied out of the sandbox and
            searched, on the host, for the key: the agent's home, the session's settings, the
            sockets and recordings, /tmp, /run, /etc, and /proc for every process the agent can
            see. The key is only searched for out here, because sending it in to search for would
            put it in the sandbox.
        """
        when: 'everything the agent can read is copied out'
            var copied = inSandboxBytes('''
                for p in /proc/[0-9]*; do
                    cat "$p/environ" "$p/cmdline" 2>/dev/null; printf '\\n'
                done
                tar -c --ignore-failed-read -f - /home/agent /oillamp /tmp /run /etc 2>/dev/null
                env
            ''')

        then: 'there was a lot to look through, including the harnesses\' own environment'
            copied.length > 100_000
            new String(copied, StandardCharsets.ISO_8859_1).contains('EDENAI_API_KEY=held-by-oillamp-on-the-host')

        and: 'and the key is not in any of it'
            !holdsKey(copied)
    }

    def 'The harnesses in the sandbox point at the relay, and hold only the placeholder'() {
        reportInfo """
            What the agent sees when it looks: the model address is the relay inside the sandbox,
            and the key is a placeholder that says where the real one is.
        """
        expect:
            inSandbox('echo "$EDENAI_BASE_URL"').strip() == 'http://127.0.0.1:3129/v3'
            inSandbox('echo "$EDENAI_API_KEY"').strip() == 'held-by-oillamp-on-the-host'
            new JsonSlurper().parseText(inSandbox('cat "$OPENCODE_CONFIG"'))
                    .provider.edenai.options.baseURL == 'http://127.0.0.1:3129/v3'
    }

    def 'Going around the relay gets the agent nowhere'() {
        reportInfo """
            An agent that ignores the relay and calls Eden AI directly, through the proxy, has
            only the placeholder to offer, and is refused by Eden AI. The global endpoint is
            refused before that, by the network policy.
        """
        expect: 'the EU endpoint turns the placeholder away'
            inSandbox('''curl -s -o /dev/null -w '%{http_code}' --max-time 60 \\
                         -H "Authorization: Bearer $EDENAI_API_KEY" -H 'Content-Type: application/json' \\
                         -d '{"model":"''' + MODEL + '''","max_tokens":5,"messages":[{"role":"user","content":"hi"}]}' \\
                         https://api.eu.edenai.run/v3/chat/completions''') in ['401', '403']

        and: 'the global endpoint is not even reached'
            inSandbox("curl -s -o /dev/null -w '%{http_connect}' --max-time 30 https://api.edenai.run/v3/models") == '403'
    }

    @Requires({ REAL_KEY })
    def 'Through the relay, the model answers, with the key the sandbox never had'() {
        reportInfo """
            The same request as above, sent to the relay instead, with the same placeholder. This
            time oillamp adds the real key on the host, sends it to Eden AI's EU endpoint over
            HTTPS, and the model answers. The request is tiny (five tokens at most) and needs a
            real key in this environment; without one, it is skipped.
        """
        when:
            var answer = inSandbox('''curl -s --max-time 120 \\
                         -H "Authorization: Bearer $EDENAI_API_KEY" -H 'Content-Type: application/json' \\
                         -d '{"model":"''' + MODEL + '''","max_tokens":5,"messages":[{"role":"user","content":"Reply with the word ok"}]}' \\
                         "$EDENAI_BASE_URL/chat/completions"''')

        then:
            new JsonSlurper().parseText(answer).choices[0].message.content instanceof String
    }

    @Requires({ REAL_KEY })
    def 'pi, holding only the placeholder, gets an answer from the model'() {
        reportInfo """
            The real test of the whole arrangement: the harness itself, as the agent would run it.
            pi's Eden AI extension reads the relay's address and the placeholder, asks for the
            model catalog and then for an answer, and both requests leave the sandbox without a
            key and reach Eden AI with one.
        """
        when:
            var answer = inSandbox("pi --provider edenai --model '${MODEL}' --no-session -p 'Reply with the single word ok.' </dev/null")

        then:
            answer.toLowerCase().contains('ok')
    }

    @Requires({ REAL_KEY })
    def 'opencode, holding only the placeholder, gets an answer from the model'() {
        reportInfo """
            The same for opencode, which reads the relay's address from the configuration file the
            image build wrote, and the placeholder from the environment.
        """
        when:
            var answer = inSandbox("opencode run -m 'edenai/${MODEL}' 'Reply with the single word ok.' </dev/null")

        then:
            answer.toLowerCase().contains('ok')
    }

    def 'Every model request is in the network log, and the key never is'() {
        reportInfo """
            The user sees in the network log that the agent used the model, and how much. The
            log is on the user's disk and gets shared, so the key must not be in it.
        """
        when:
            var logs = Files.list(directory.resolve('.oillamp/logs')).toList()
            var text = logs.collect { Files.readString(it) }.join('\n')

        then:
            REAL_KEY ? text.contains('"channel":"model"') : true
            !holdsKey(text)
    }

    def 'Ending the session leaves the key nowhere on disk'() {
        reportInfo """
            After the session, the lamp is just files on the user's disk. Not one of them may hold
            the key, including anything written while shutting down.
        """
        when:
            var stopped = oillamp('stop', directory.toString())
            engine.waitFor(2, TimeUnit.MINUTES)

        then:
            stopped.status() == ExitStatus.SUCCESS
            !engine.alive
            filesHolding(directory).isEmpty()
            !holdsKey(Files.readString(engineOutput))
    }

    // ─── helpers ───────────────────────────────────────────────────────────────────────────

    private boolean holdsKey(String text) { text.contains(key) }

    private boolean holdsKey(byte[] bytes) {
        new String(bytes, StandardCharsets.ISO_8859_1).contains(key)
    }

    /** The paths of files under {@code root} that hold the key. Never the key itself. */
    private List<String> filesHolding(Path root) {
        var found = []
        Files.walk(root).filter { Files.isRegularFile(it) && Files.isReadable(it) }.forEach { file ->
            try {
                if (holdsKey(Files.readAllBytes(file))) found << root.relativize(file).toString()
            } catch (IOException unreadable) {
                // Files of the infrastructure user; the agent cannot read them either.
            }
        }
        found
    }

    private static OilLamp.Outcome oillamp(String... argv) { OilLamp.on(Machine.real()).run(argv) }

    /** Waits until {@code condition} holds, checking every second. */
    private static boolean eventually(Duration limit, Closure<Boolean> condition) {
        long deadline = System.nanoTime() + limit.toNanos()
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(1000)
        }
        condition()
    }

    /** The ssh command that reaches the sandbox as the agent, the way `oillamp shell` does. */
    private List<String> ssh() {
        var id = new JsonSlurper().parse(directory.resolve('.oillamp/lamp.json').toFile()).agentId
        var runtime = System.getenv('XDG_RUNTIME_DIR') ?: "/run/user/${'id -u'.execute().text.strip()}"
        ['ssh', '-F', directory.resolve('.oillamp/ssh_config').toString(),
         '-o', "ProxyCommand=socat - UNIX-CONNECT:${runtime}/oillamp/${id}/run/ssh.sock".toString(),
         '-o', 'BatchMode=yes', '-T', "lamp-${id}".toString()]
    }

    /** Runs a shell script in the sandbox and returns its standard output, byte for byte. */
    private byte[] inSandboxBytes(String script) {
        var process = new ProcessBuilder(ssh() + ['bash', '-s']).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        process.outputStream.withCloseable { it.write(script.getBytes(StandardCharsets.UTF_8)) }
        var out = process.inputStream.readAllBytes()
        assert process.waitFor(5, TimeUnit.MINUTES)
        out
    }

    private String inSandbox(String script) {
        new String(inSandboxBytes(script), StandardCharsets.UTF_8)
    }
}
