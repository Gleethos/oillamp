# Working on oillamp

## Where things are explained

| Question | Document |
|---|---|
| What is oillamp, and how do I use it? | [README.md](README.md) |
| What are containers, uid maps, Wayland, VNC, Spock…? | [docs/TECH-STACK.md](docs/TECH-STACK.md) |
| How does the application work, and where does its state live? | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) |
| Why is it built this way? | [docs/DECISIONS.md](docs/DECISIONS.md) |
| What works, what is verified, what is missing? | [docs/STATUS.md](docs/STATUS.md) |
| How do I use Sprouts collections? | [docs/SproutsCheatSheet.md](docs/SproutsCheatSheet.md) |

`docs/archive/oillamp-design-spec.md` is the original design, kept for history. It is out of date.
Do not cite it.

When you change a design decision, update its entry in DECISIONS.md. When you find or close a gap
between what the code does and what anything says it does, update STATUS.md.

## Build and test

You need a JDK 25. Gradle downloads itself through the wrapper.

```sh
./gradlew build          # compile, run the scenarios, check the architecture rules
./gradlew test           # the scenarios only; then read build/spock-reports/*.md
./gradlew spikes         # checks against real podman; slow, needs podman and a network
./gradlew installDist    # build/install/oillamp/bin/oillamp, for development
./gradlew singleFile     # build/dist/oillamp, the single-file executable
```

`./gradlew build` must stay fast and must pass on a machine without podman. Anything that needs a
real container belongs in a spike (tag it `spike`).

After changing anything under `src/main/resources/image/`, rebuild with `installDist` or
`singleFile` before trying it. The image tag is a hash of those files, so the next session builds a
new image automatically, but only if you run the new build of oillamp. Old images are not removed;
clear them now and then with `podman images localhost/oillamp/sandbox` and `podman rmi`.

## Getting to know the code

1. **Run it with `--dry-run`.** `oillamp at /tmp/x --dry-run --verbose` prints everything a real
   run would do, including the full `podman run` command, and changes nothing. Read that output
   next to `LampPlanner.java`.
2. **`OilLamp.java`**: the entry point.
3. **`Machine.java`**: every effect the program can have on your computer. The exceptions are the
   lamp's own files (`Filesystem.java`) and the sockets of a running session (`Relay.java`,
   `Control.java`, `Egress.java`).
4. **`Step.java`**: every kind of change oillamp can make during setup.
5. **`LampPlanner.java`**: a pure function turning facts into a plan. The clearest example of the
   style of the whole code base.
6. **`SandboxPhase.java`**: where the container command is built.
7. **`SessionMachine.java`**: every rule about how a session starts and ends.
8. **`src/main/resources/image/rootfs/usr/local/lib/oillamp/entrypoint`**: the container's first
   process.

### The difficult parts

- **The entrypoint** is a bash script of about 330 lines running as the container's first process.
  It is the least type-checked part of the system and the one with the biggest consequences when it
  breaks. `TheSandboxImageSpec` checks that it parses, and the spikes run it for real.
- **Unix socket paths are limited to 107 bytes.** Host-side sockets are reached through the short
  runtime directory under `$XDG_RUNTIME_DIR`. Any change that moves a socket must respect this.
- **`/home/agent` is a bind mount**, so anything the image puts there at build time is hidden when
  the container runs. SDKMAN and pi's settings are installed elsewhere and copied in by the
  entrypoint; any new tool that installs into the home needs the same treatment.
- **The agent runs as your uid.** Its isolation comes from what the container can see. Think twice
  before attaching another directory or changing the uid map.

## Coding conventions

- Data is immutable `record`s. Validation happens in the record's constructor, so an invalid value
  cannot exist.
- Collections in records are Sprouts persistent collections (`Tuple`, `ValueSet`, `Association`),
  never `java.util.List`, `Set`, `Map` or arrays. See
  [docs/SproutsCheatSheet.md](docs/SproutsCheatSheet.md).
- Alternatives are `sealed interface`s with record cases, handled with exhaustive `switch`
  statements without `default`, so adding a case forces every `switch` to be updated.
- No `null`. Absence is `Optional`. NullAway checks this during compilation.
- Time comes from `Machine.now()`, never `Instant.now()`.
- Only final Java 25 features; no preview features.

## Architecture rules the build enforces

`TheShapeOfTheCodeSpec` fails the build if:

- a third type in `dev.oillamp`, or an unlisted one in `dev.lamp`, becomes `public`;
- `dev.lamp` imports anything from `dev.oillamp`;
- a public method uses a package-private type in its signature;
- a class outside the allowlist uses files, processes, threads, `System` or `SecureRandom`.

If you need to change one of these rules, change the test deliberately and say why in the commit.

## How the tests are organised

All tests are Spock specifications in `src/test/groovy/oillamp/`, a package outside `dev.oillamp`,
so they can only use the public types, like any other caller.

### Scenarios

Each scenario describes a situation a user can be in and runs the real `OilLamp.run(...)` against a
`SimulatedMachine`. The helper `Sandbox.groovy` sets up a simulated Ubuntu machine with a temporary
home; a scenario changes what it needs:

```groovy
sandbox.machine { it.withoutPodman().withoutSubordinateIds() }
var outcome = sandbox.oillamp.run('at', sandbox.lampPath().toString(), '--dry-run')
outcome.reported('OIL-PKG-001')
```

Files in the lamp are real, in a temporary directory: the lamp's security is made of permission
bits, ownership and symlinks, and a simulated filesystem would hide bugs where they matter most.
Session scenarios run a real supervisor with real Unix sockets; only the container is simulated.
Scenarios need no podman, no network and no display, and together they run in about two minutes.

| Spec | About |
|---|---|
| `CheckingTheMachineSpec` | `doctor` and host prerequisites |
| `SettingUpALampSpec` | creating, reusing, refusing and removing lamps; recordings |
| `ConfiguringALampSpec` | the configuration file and its validation |
| `StartingTheSandboxSpec` | readiness and socket checks |
| `SupervisingASessionSpec` | a whole session: windows, shutdown, failures |
| `TheSessionCommandsSpec` | `status`, `stop`, `shell`, `view`, `list` |
| `DecidingWhatTheSandboxMayReachSpec` | the egress proxy and policy, over the real socket |
| `UsingTheCommandLineSpec` | usage errors, version, crash handling |
| `TheLampCommandSpec` | the `lamp` script, with stub tools on `PATH` |
| `ThePointerSpec` | what `lamp click`, `move`, `drag` and `scroll` send, against a stand-in VNC server |
| `TheLauncherSpec` | the single-file launcher script |
| `TheSandboxImageSpec` | static checks of the image files |
| `TheShapeOfTheCodeSpec` | the public types, `dev.lamp` staying apart from the engine, and the deciding/doing split |

### Spikes

A simulation only knows what its author already believed about podman, sway or sshd. Spikes check
those beliefs against the real tools: they pull images, build the real image (without the
toolchain) and start real containers. They are tagged `spike`, excluded from `test`, and skip
themselves when podman is not available. `Spike.groovy` runs commands through `Machine.real()`, the
same code path oillamp uses.

| Spec | Checks |
|---|---|
| `VerifyingPodmanAssumptionsSpec` | rootless podman on Ubuntu; the uid mapping; read-only root with writable mounts; Unix sockets in both directions across the container boundary |
| `VerifyingTheImageBaseSpec` | every package the image needs exists in Debian trixie |
| `VerifyingTerminalProfilesSpec` | installed terminals accept oillamp's arguments; terminal emulators return before their window closes |
| `VerifyingTheSandboxDesktopSpec` | the real image: readiness, SSH login as `agent`, screen size and rendering, vncviewer on a Unix socket, clicking and typing with `lamp`, a playable recording, a clean second session |

The results of these checks, and what was tested by hand on real hardware, are in STATUS.md.

## Writing comments, documentation and test descriptions

The people maintaining oillamp are programmers, not container or Linux-graphics specialists. Write
for them.

- **Say the thing.** Do not point at it. "Unix socket paths are limited to 107 bytes, so sockets are
  reached through the short runtime directory" is useful. "See D-25" or "per §9.2" is not.
  Requirement codes, section numbers, spike numbers (S1, S2, …) and milestone names (M3, M4, …) mean
  nothing to a reader who was not there.
- **Name the concept.** Write "the infra user (container uid 1001)", not "the other user" or "the
  one described above".
- **Explain the reason, once, where it applies.** A comment that says *why* is worth keeping; a
  comment that restates the code is not.
- **Keep it true.** A comment that describes how things used to be is worse than none. When you
  change behaviour, search for comments and documents that describe it.
- **Plain sentences.** Short sentences, ordinary words, few dashes. Avoid figures of speech and
  words like "load-bearing".
- **Define a term the first time** in a document if a reader might not know it (user namespace,
  subordinate id, bind mount, compositor).
- **Doc comments are Markdown** (`///`, supported since Java 23), not HTML Javadoc: `code` in
  backticks, `[OtherType#method]` for a link, a blank `///` line between paragraphs, `-` for a
  list. Groovy has no Markdown comments, so the Spock specs keep `/** */`.
- **Javadoc:** the first sentence says what the type or method is. Explain visibility only for the
  public types; everything else is package-private by default and needs no comment about it.
- **Scenarios:** the name says what the user experiences. The `reportInfo` block explains, without
  referring to other documents, why the behaviour matters. It is rendered into the test report and
  should make sense on its own.

## Commit messages

Say what changed and why, in plain words. If a change fixes something found on real hardware, add
it to "Lessons from real hardware" in STATUS.md.
