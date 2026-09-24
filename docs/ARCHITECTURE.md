# How oillamp works

This document describes the code as it is today. It is for the people who maintain oillamp.

Read [the README](../README.md) first. It explains what oillamp is for and introduces the Linux
mechanisms it uses: containers, rootless podman, user namespaces, uid maps, subordinate ids,
images, Wayland and VNC. This document assumes you have read it and focuses on how the code
puts those pieces together.

Two companion documents:

- [DECISIONS.md](DECISIONS.md) lists the design decisions and why each was made.
- [STATUS.md](STATUS.md) lists what works, what was verified on real hardware, and the known
  gaps between what the code does and what the configuration or older documents suggest.

The original design specification is kept in [archive/](archive/oillamp-design-spec.md) for
history. It was written before the code and is out of date in many places. Do not use it as a
reference.

---

## Contents

1. [Vocabulary](#1-vocabulary)
2. [The big picture](#2-the-big-picture)
3. [What happens when you run `oillamp at`](#3-what-happens-when-you-run-oillamp-at)
4. [How the Java code is organised](#4-how-the-java-code-is-organised)
5. [The lamp directory on disk](#5-the-lamp-directory-on-disk)
6. [The container](#6-the-container)
7. [The running session](#7-the-running-session)
8. [The network](#8-the-network)
9. [Desktop, viewer, recording and GPU](#9-desktop-viewer-recording-and-gpu)
10. [Configuration reference](#10-configuration-reference)
11. [Problem codes and exit codes](#11-problem-codes-and-exit-codes)
12. [Tests](#12-tests)
13. [Packaging](#13-packaging)

---

## 1. Vocabulary

These words are used with one meaning each, everywhere in the code and the documents.

| Word | Meaning |
|---|---|
| **host** | The computer oillamp runs on: your laptop. |
| **sandbox** | The container oillamp starts for the agent. "Container" and "sandbox" mean the same thing here. |
| **lamp** | One directory on the host that holds everything about one sandbox: configuration, state and the agent's files. You pick the path. |
| **agent id** | Eight random characters from `a`–`z` and `2`–`7`, generated when a lamp is created and never changed. Container, hostname and directory names are derived from it. Example: `v4elchzj`. |
| **agent directory** | `<lamp>/agent-lamp-<agent id>/`. Mounted into the container as `/home/agent`. The only part of the lamp the agent can see. |
| **state directory** | `<lamp>/.oillamp/`. oillamp's own files: identity, keys, sockets, recordings, logs. |
| **agent user** | The container user `agent`, uid 1000. Mapped to *your* user on the host. Runs the shell and everything the agent starts. |
| **infra user** | The container user `lamp`, uid 1001. Mapped to a subordinate id on the host that belongs to nobody. Runs the compositor, the VNC server, the recorder and the network bridges. ("infra" is short for infrastructure.) |
| **session** | One run of `oillamp at`, from start to shutdown. Its **session id** is the UTC start time, for example `20260923-085055`. |
| **supervisor** | The `oillamp at` process while a session runs. It stays in the foreground of the terminal you started it from. |
| **primary shell** | The SSH connection from the terminal window oillamp opens. When it connects, the session is up. Closing it ends nothing. |
| **extra shell** | A shell opened with `oillamp shell <dir>`. Closing it ends nothing. |
| **relay** | A Unix socket on the host that forwards connections into a socket inside the sandbox. Used for SSH. |
| **control socket** | A Unix socket the supervisor listens on, so that `oillamp stop`, `status`, `view` and `shell` can talk to a running session. |
| **egress proxy** | The HTTP proxy inside oillamp that is the sandbox's only way out to the network. "Egress" means outbound. |
| **forward** | A fixed tunnel from a port inside the sandbox to one host-reachable address, configured by you, not subject to the proxy policy. |
| **runtime directory** | `$XDG_RUNTIME_DIR/oillamp/<agent id>/`, usually `/run/user/1000/oillamp/<agent id>/`. Holds short paths to the sockets. See [Why sockets have two paths](#why-sockets-have-two-paths). |
| **phase** | One stage of starting up: host, lamp, image, session. |
| **plan** / **step** | A plan is a list of steps. A step is a description of one change oillamp intends to make, such as "create this directory with mode 0700". See [Plans and steps](#plans-and-steps). |
| **problem** | oillamp's structured error: a code, what happened, why it matters, evidence, and fixes. See [Problems and results](#problems-and-results). |
| **spike** | A test that runs against real podman to confirm an assumption about a third-party tool. See [Tests](#12-tests). |

---

## 2. The big picture

```
HOST (your desktop session)
│
│  the terminal you typed `oillamp at` in
│  └── oillamp supervisor (Java)
│        • holds the lamp's lock
│        • SSH relays:   run/ssh-primary.sock  (one connection: the terminal window)
│                        run/ssh.sock          (any number: `oillamp shell`)
│        • control socket: run/control.sock    (`oillamp stop/status/view/shell`)
│        • egress proxy:  sockets/host/proxy.sock, plus one fwd-<name>.sock per forward
│        • watches the container every 2 s, reports health every 30 s
│
│  terminal window (new) ── ssh ── socat ──▶ run/ssh-primary.sock ─┐
│  viewer window   (new) ── vncviewer ─────▶ sockets/infra/vnc.sock │
│                                                                   │
│  <lamp>/                                                          │
│   ├── oillamp.toml            configuration (agent cannot see it) │
│   ├── .oillamp/               state (only the listed parts are    │
│   │                           mounted into the container)         │
│   └── agent-lamp-<id>/  ──mounted as──▶ /home/agent               │
│                                                                   │
└─── rootless podman, --network=none, read-only image ──────────────┘
    CONTAINER  oillamp-<agent id>
      pid 1: /usr/local/lib/oillamp/entrypoint  (bash, starts as container root)
        as `lamp` (infra user):
            sway            the Wayland compositor, one virtual screen HEADLESS-1
            Xwayland        X11 display :0 for Swing and other X11 apps; the agent is allowed on it
            wayvnc          serves the screen on /oillamp/sockets/infra/vnc.sock
            wf-recorder     only if recording is enabled
            socat 127.0.0.1:3128  → /oillamp/sockets/host/proxy.sock
            socat 127.0.0.1:<p>   → /oillamp/sockets/host/fwd-<name>.sock
        as `agent`:
            dbus-daemon     session bus
            socat /oillamp/sockets/agent/ssh.sock → sshd -i  (one sshd per connection)
            your shell, the agent harness, GUI applications, the browser
```

How data moves:

- **Your shell:** terminal window → `ssh` → `ProxyCommand socat` → `run/ssh-primary.sock` →
  supervisor relay → `sockets/agent/ssh.sock` → socat in the container → `sshd -i` as `agent`.
- **The desktop picture:** `vncviewer` → `sockets/infra/vnc.sock` → wayvnc → sway.
- **The web:** a program in the sandbox → `HTTPS_PROXY=http://127.0.0.1:3128` → socat in the
  container → `sockets/host/proxy.sock` → egress proxy on the host → policy check → internet.
- **A forward:** a program → `127.0.0.1:<port>` → socat → `sockets/host/fwd-<name>.sock` →
  supervisor → TCP connection to the configured target.

Everything between host and container is a Unix domain socket in a bind-mounted directory. There
is no TCP port on the host and no network interface in the container.

---

## 3. What happens when you run `oillamp at`

This is the whole program in order. File names are in `src/main/java/dev/oillamp/`.

1. **`OilLamp.main`** builds a real `Machine` and calls `OilLamp.run(argv)`. `run` catches any
   unexpected exception and turns it into problem `OIL-INTERNAL-001`, so the user never sees a raw
   stack trace.
2. **`Invocation.execute`** parses the command line by hand (no library) and calls the matching
   method on `Commands`. Any usage mistake exits with code 2.
3. **`Commands.at`** runs the phases in order. Each phase follows the same pattern: *probe* the
   world into a record of facts, *plan* with a pure function, then *run* the plan with
   `StepRunner`.
4. **Host phase (`HostPhase`)**
   - `HostProbe.probe` collects `HostFacts`: the distribution, your user and groups, the
     graphical session, which required packages are installed, your subordinate id ranges,
     podman's version and runtime, whether `podman unshare true` works, terminal emulators on
     `PATH`, GPU render nodes, CPU count, sudo, and the filesystem type of the lamp path.
   - `HostPlanner.plan` turns the facts into steps (install packages, add a subordinate id
     range, `podman system migrate`) or problems.
   - After running the steps, the host is **probed again** and planned again in strict mode.
     The first probe ran before podman existed, so its answers about podman meant nothing.
5. **Lamp phase (`LampPhase`)**
   - Resolves the path (following symlinks) and refuses dangerous ones (`LampPaths`): `/`, your
     home directory itself, and system directories such as `/etc` or `/usr`.
   - Refuses network and FAT filesystems, which cannot hold Unix sockets.
   - `LampClassifier` decides what the directory is: missing, empty, an existing lamp, someone
     else's files, or damaged.
   - Loads the configuration (`ConfigLoader`), decides on the GPU (`Gpu.decide`), and prints a
     summary.
   - `LampPlanner.planSkeleton` plans the directory tree, identity file, SSH keys, ownership
     changes, recording retention and the runtime directory. `StepRunner` runs it.
   - `LampPlanner.planSession` then plans the per-session files (`runtime.env`, the agent guide,
     `authorized_keys`, `ssh_config`, `known_hosts`). This is a second plan because it needs the
     public keys the first plan generated. See [Plans and steps](#plans-and-steps).
6. **`--dry-run` stops here**, after also planning the image and container steps so that the
   full `podman run` command is printed. A dry run takes no lock and changes nothing.
7. **Lock.** `LampLock.tryAcquire` takes an exclusive OS file lock on `.oillamp/lock`. If another
   session holds it, oillamp reports `OIL-LOCK-001` and exits with code 4. The OS releases the
   lock when the process dies, however it dies.
8. **Image and sandbox phase (`SandboxPhase`)**
   - Computes the image tag from a hash of the image's files and build arguments. If podman does
     not have that tag, it extracts the image files from the jar and runs `podman build`.
   - Removes a leftover container with the same name, deletes the previous session's socket
     files, runs `podman run` (flags in [The `podman run` command](#the-podman-run-command)),
     waits for `ready.json` from *this* session, then connects to the VNC and SSH sockets to
     prove they answer.
9. **Supervisor (`Supervisor.run`)** binds the relays, the control socket and the egress proxy,
   writes `session.json`, opens the two windows and then waits until the session ends. On the way
   out it runs the shutdown sequence. `Commands.at` releases the lock afterwards.

The other commands reuse these parts. `doctor` runs the host phase in dry-run mode with
installing forbidden. `view`, `shell`, `stop` and `status` send one request to the control
socket. `remove` and `recordings --prune` build a plan and run it with `StepRunner`.

---

## 4. How the Java code is organised

### One package, five public types

All production code is in one package, `dev.oillamp`. Only five types are `public`:

| Type | Why it is public |
|---|---|
| `OilLamp` | The entry point. `OilLamp.on(machine).run(argv)` is the whole tool. |
| `Machine` | Everything oillamp does to the outside world goes through it. A caller (a test, or a future GUI) must be able to supply one. |
| `LampEvent` | The stream of things oillamp reports. A GUI would render these itself. |
| `Problem` | Structured errors, so a caller can inspect them rather than parse text. |
| `ExitStatus` | The process exit codes, by name. |

Everything else is package-private. Because Java does not let another package use a
package-private class, the compiler enforces this boundary. Sub-packages would need public types
to talk to each other, and those would be visible to everyone, which is why there is only one
package. The tests live in package `oillamp`, outside `dev.oillamp`, so they can only use the five
public types, the same way any other caller would.

### Deciding versus doing

Most classes only *decide*: they take values and return values, with no file access, no
processes, no clock and no randomness. A small set of classes *do* things. The test
`TheShapeOfTheCodeSpec` enforces the split. It fails the build if any class outside this list
uses `java.nio.file.Files`, `ProcessBuilder`, `Process`, `SecureRandom`, `Thread` or `System`:

`RealMachine`, `SimulatedMachine`, `Machine`, `Filesystem`, `LampLock`, `HostProbe`,
`StepRunner`, `HostPhase`, `LampPhase`, `Commands`, `ConsoleRenderer`, `OilLamp`, `Invocation`,
`Supervisor`, `Relay`, `Control`, `Egress`.

(The check does not look for `Instant.now()`. By convention, time comes from `Machine.now()`.)

`SandboxPhase`, `ImageResources` and a few others read files or the classpath through helpers on
this list, so they pass the check.

### The `Machine` interface

`Machine` is the only way the program runs commands, reads system files (`/etc/os-release`,
`/etc/subuid`, `/proc/...`), looks up executables, asks the time, generates random ids and opens
windows. It has two implementations:

- `RealMachine` does these things for real. Every command gets an argument list (never a shell
  string) and a timeout. On timeout, the process and all its children are killed. Commands marked
  `shieldedFromSignals()` run under `setsid --wait`, so a second Ctrl-C in your terminal cannot
  kill the `podman stop` that is cleaning up.
- `SimulatedMachine` pretends to be a machine described in a test, such as "Ubuntu 24.04, no
  podman, sudo needs a password". It answers with realistic command output (real `podman info`
  JSON, real `dpkg-query` lines) so the real parsers are tested. It also simulates the container
  well enough for a session to run: on `podman run` it binds real Unix sockets and writes a
  `ready.json`.

Files *inside the lamp directory* are not behind `Machine`. They go through `Filesystem`, which
touches the real disk even in tests, because the lamp's security depends on real permission bits,
ownership and symlinks.

### Plans and steps

A `Step` (in `Step.java`) is a record describing one change: `CreateDirectory`, `WriteFile`,
`InstallPackages`, `ChownForContainer`, `RunContainer`, and so on. A `Plan` is a list of steps for
one phase. Every step can describe itself in one line (`describe()`) and in detail (`detail()`).

`StepRunner.run(plan)` either announces each step (in a dry run) or performs it. This is why
`--dry-run` can be trusted: the dry run and the real run build the same plan, and the only
difference is whether `StepRunner` executes it. There is no separate "preview" code to go out of
date.

Two properties of `StepRunner`:

- A step that is already done is skipped and reported as skipped: a file written "only if absent"
  that exists, a key that exists, a directory that exists.
- It stops at the first failed step.

Adding a new kind of step is a compile error in `Step.describe()`, `Step.detail()` and
`StepRunner.perform()` until you handle it in all three. The `switch` statements have no
`default` branch on purpose.

### Problems and results

Expected failures are values, not exceptions.

- `Problem` has a code (`OIL-AREA-NNN`), a severity (`INFO`, `WARNING`, `ERROR`), a title, *what
  happened*, *why it matters*, evidence (a command and its output, a file, a value, a config
  location) and fixes (a description and optionally a command to paste).
- `Problems.java` is the catalogue: one factory method per problem, holding its fixed wording.
- `Result<T>` is either `Ok(value, warnings)` or `Err(problems)`. `Result.combine` and
  `Result.all` collect problems from independent checks, so a user with three mistakes sees all
  three in one run.

Unexpected exceptions are caught in `OilLamp.run` and reported as `OIL-INTERNAL-001`.

### Events and the console

oillamp never prints directly. Everything it wants to say is a `LampEvent`: `Ok`, `Info`,
`StepPlanned`, `Warning`, `Failure`, `Summary`, `Answer` and so on. `OilLamp.run` sends each event
to `ConsoleRenderer` (which prints it), to any listener registered with `observedBy`, and into the
`Outcome` that `run` returns. Tests assert on events and on the rendered console text.

`Context` carries the event sink and the command-line options through one run.

**The activity line.** While a step runs, `ConsoleRenderer` shows what is happening on the last
line of the terminal: a spinner, the elapsed time, a plain description of the step and the latest
line of the step's own output (for the image build, `podman build`'s output, which
`Machine.run(command, eachLine)` passes on line by line). A daemon thread redraws it in place every
120 ms, and it is cleared before any ordinary line is printed, so it never mixes with the output
above it. It is only drawn when standard output is a terminal, never into `Outcome.console()`, and
never during steps that may run `sudo` (`InstallPackages`, `AddSubIds`), because redrawing would
overwrite the password prompt.

The line must never wrap onto a second row. A carriage return only goes back to the start of the
current row, so a wrapped line leaves one row behind on every redraw and floods the terminal. Two
things prevent that. The width comes from `$COLUMNS` if it is exported, and otherwise from
`stty size` (shells usually do not export `$COLUMNS`); a longer line is shortened and ends in "…".
And while drawing, the renderer turns the terminal's automatic wrapping off (`ESC[?7l`, back on
with `ESC[?7h`), so that a line that is still too long, for example after the window was made
narrower, is cut off at the right edge. Escape sequences and control characters in the program's
output are removed before it is shown.

### Class map

| Area | Classes |
|---|---|
| Entry and commands | `OilLamp`, `Invocation`, `Commands`, `Context`, `ConsoleRenderer`, `Handbook` (the texts of `oillamp about` and `oillamp guide`) |
| The outside world | `Machine`, `RealMachine`, `SimulatedMachine`, `Filesystem`, `LampLock` |
| Host phase | `HostPhase`, `HostProbe`, `HostPlanner`, `HostFacts`, `HostRequirements`, `SubIdAllocator`, and fact records `OsRelease`, `UserInfo`, `GraphicalSession`, `PodmanFacts`, `UsernsFacts`, `SubIdFacts`, `SudoFacts`, `GpuFacts`, `TerminalCandidate`, `IdRange`, `DistroFamily`, `Installing` |
| Lamp phase | `LampPhase`, `LampPlanner`, `LampClassifier`, `LampState`, `LampLayout`, `LampPaths`, `LampMeta`, `AgentId`, `SessionId`, `DirListing`, `Retention`, `RecordingFile` |
| Configuration | `ConfigLoader`, `ConfigTree`, `ConfigSection`, `ConfigSource`, `ConfigDefaults`, `LampConfig`, `Templates`, and value types `GpuMode`, `ClipboardMode`, `TerminalProfileId` |
| Plans | `Plan`, `Step`, `StepRunner`, `PosixMode` |
| Errors and events | `Problem`, `Problems`, `Result`, `LampEvent`, `ExitStatus` |
| Shared | `Json` (every JSON file, message and answer is read and written through it) |
| Image and container | `SandboxPhase`, `ImageResources`, `ImageTag`, `ContainerName`, `RuntimeEnv`, `ReadyInfo`, `AgentGuide`, `Gpu` |
| Session | `Supervisor`, `SessionMachine`, `SessionState`, `SessionEvent`, `SessionAction`, `Relay`, `Control`, `Ssh`, `Terminals`, `Viewers` |
| Network | `Egress`, `Policy`, `NetworkPolicy`, `Rule`, `Decision`, `HostPattern`, `Cidr`, `IpAddress`, `PortRange`, `HostAndPort`, `Forward` |

### Coding conventions

- Data is immutable `record`s. Validation happens in the record's constructor, so an invalid
  value cannot exist.
- Collections in records are Sprouts persistent collections (`Tuple`, `ValueSet`,
  `Association`), never `java.util.List`, `Set`, `Map` or arrays. See
  [SproutsCheatSheet.md](SproutsCheatSheet.md).
- Alternatives are `sealed interface`s with record cases, handled with exhaustive `switch`
  statements without `default`, so adding a case forces every `switch` to be updated.
- No `null`. Absence is `Optional`. NullAway (run by Error Prone during compilation) checks this.
- Only final Java 25 features; no preview features.

---

## 5. The lamp directory on disk

```
<lamp>/
├── oillamp.toml                 your configuration                      you, 0600
├── README.txt                   a note to whoever finds this directory  you, 0644
├── .oillamp/                    oillamp's state                          you, 0700
│   ├── lamp.json                identity: schema version, agent id, dates
│   ├── lock                     locked by the supervisor while a session runs
│   ├── session.json             present while a session runs (informational)
│   ├── keys/                    client_ed25519(.pub), host_ed25519(.pub)  0700
│   ├── ssh_config               generated; used by the terminal window and `oillamp shell`
│   ├── known_hosts              pins the sandbox's host key
│   ├── session/                 rewritten each session; mounted read-only at /oillamp/session
│   │   ├── runtime.env          settings the entrypoint and login shells read
│   │   ├── authorized_keys      the client public key
│   │   ├── ssh_host_ed25519_key a copy of the host key for sshd (0600)
│   │   └── agent-guide.md       the same text as ~/AGENTS.md
│   ├── image/context/           the image build files, extracted from the jar before a build
│   ├── sockets/                 not mounted itself; each directory below is, on its own
│   │   ├── host/                you;        proxy.sock and fwd-*.sock (the supervisor binds them); read-only in the container
│   │   ├── infra/               infra user; vnc.sock and ready.json
│   │   └── agent/               you;        ssh.sock (sshd's listener)
│   ├── recordings/              infra user; <session>.mkv; mounted at /oillamp/recordings
│   └── logs/                    network-<session>.jsonl
└── agent-lamp-<agent id>/       mounted read-write at /home/agent
    ├── AGENTS.md                rewritten each session
    ├── .bashrc                  written once, then left alone
    ├── workspace/
    ├── libs/                    on LD_LIBRARY_PATH and java.library.path
    └── screenshots/
```

The paths in this tree come from methods in `LampLayout`. Commands that must work on a damaged
lamp without an agent id (`remove`) use its static helpers `stateDirOf`, `configOf` and
`readmeOf`. `LampPhase` and `Commands` still spell out `.oillamp/lamp.json` in two places.

### Who owns what, and why

- **`oillamp.toml` is outside the agent directory.** It holds the network policy. The agent must
  not be able to change the rules that restrict it.
- **`.oillamp/` is mode 0700.** No other user on the host can reach the sockets inside it. That
  lets the individual sockets be more open (the proxy socket is 0666) without exposing them.
- **`sockets/infra/` and `recordings/` belong to the infra user.** `LampPlanner` hands them over
  with `podman unshare chown 1001:1001`. `podman unshare` runs a command inside podman's user
  namespace, where "1001" means the container's infra user, and podman translates it to the
  right subordinate id on the host. Because the agent is uid 1000 and has no capabilities, it
  cannot write, delete or replace anything there. It can *read* recordings, and that is intended:
  the directory must be readable for you, and to the infra user you and the agent are the same
  kind of outsider.
- **A lamp cannot be deleted with `rm -rf`.** Those infra-owned files belong to a subordinate id
  on the host, which your account cannot delete directly. `oillamp remove` deletes them through
  `podman unshare rm -rf`.

### Why sockets have two paths

Linux limits a Unix socket path to 107 bytes. A lamp can be anywhere, so its paths are often too
long. oillamp therefore creates a short **runtime directory**:

```
$XDG_RUNTIME_DIR/oillamp/<agent id>/
├── sockets -> <lamp>/.oillamp/sockets    a symlink
└── run/                                  host-only sockets, never mounted into the container
    ├── control.sock
    ├── ssh-primary.sock
    └── ssh.sock
```

Every socket the host connects to or binds is addressed through this short path. The `run/`
sockets are deliberately outside the lamp: the container cannot see them, so the agent cannot take
the primary SSH slot or send commands to the supervisor.

### `lamp.json`

```json
{
  "schemaVersion": 1,
  "agentId": "v4elchzj",
  "createdAt": "2026-09-22T14:15:03Z",
  "createdBy": "oillamp 0.1.0",
  "lastSessionAt": "2026-09-22T16:40:10Z"
}
```

A lamp with a higher `schemaVersion` than this build understands is refused with `OIL-LAMP-004`.
There is no migration code yet, because there has only ever been version 1.

---

## 6. The container

### The image

The image is built from `src/main/resources/image/`:

| Path | Purpose |
|---|---|
| `Containerfile` | The build recipe. Debian 13 "trixie" base, everyday shell tools, the desktop stack, developer tools (JDK, Node.js, Python, Firefox), the two users, then the files from `rootfs/`. |
| `build/install-node.sh` | Installs Node.js from NodeSource (Debian's version is too old for the agent harnesses). |
| `build/install-sdkman.sh` | Installs SDKMAN into `/usr/local/share/oillamp/sdkman`, with prompts turned off. |
| `build/install-agent-tools.sh` | Installs `opencode` and `pi` with npm, and pi's Eden AI extension into `/usr/local/share/oillamp/pi`. Never fails the build. |
| `build/write-opencode-config.mjs` | Writes opencode's configuration, `/usr/local/share/oillamp/opencode/opencode.json`: Eden AI through its EU endpoint, with the models that endpoint lists. Run by `install-agent-tools.sh`. |
| `rootfs/usr/local/lib/oillamp/entrypoint` | The container's first process. Described below. |
| `rootfs/usr/local/bin/lamp` | The desktop helper the agent uses: `lamp screenshot`, `click`, `type`, … |
| `rootfs/etc/profile.d/oillamp.sh` | The agent's shell environment: proxy variables, display, library paths, SDKMAN, prompt, banner. |
| `rootfs/etc/oillamp/sshd_config` | sshd settings: key login only, every kind of forwarding off. |
| `rootfs/etc/oillamp/sway/config` | Compositor settings. No key binding runs a command. |
| `rootfs/etc/ssh/ssh_config.d/50-oillamp-proxy.conf` | Sends outbound SSH (`git@github.com:…`) through the egress proxy. |
| `rootfs/usr/share/oillamp/wallpaper.png` | The desktop background. |
| `rootfs/usr/share/oillamp/wallpaper.svg` | The drawing the background is rendered from. After changing it, render it again with `inkscape wallpaper.svg --export-type=png --export-filename=wallpaper.png`. |

At build time, Gradle writes a `MANIFEST` listing every file with its mode (`755` or `644`), so
that `ImageResources` can find the files inside the jar and extract them with the right
permissions. An entrypoint extracted without its executable bit would make the container die
immediately.

**The image tag is a hash of its inputs.** `ImageResources.hashOf` computes SHA-256 over every
image file (path, mode and contents) and every build argument (`BASE_IMAGE`, `JDK_PACKAGE`,
`NODE_MAJOR`, `EXTRA_APT_PACKAGES`, `AGENT_TOOLS`). The tag is
`localhost/oillamp/sandbox:<first 16 hex digits>`. If podman already has that tag, the build is
skipped. If any input changes, the tag changes and the next session builds a new image. Lamps with
identical inputs share one image.

The Containerfile has a `WITH_TOOLCHAIN` argument (default `true`). When `false`, the JDK,
browser, Node.js and agent tools are skipped. oillamp always builds with the default. The spike
tests use `false` to build faster.

At the end of the build, the setuid and setgid bits are removed from every file, so no program in
the image can change which user it runs as.

### The `podman run` command

`SandboxPhase.containerArgv` builds it. `--dry-run --verbose` prints it in full.

```
podman run --detach --name oillamp-<id>
    --network=none                         no network interface except loopback
    --read-only                            the image's filesystem cannot be changed
    --user 0:0                             start as container root (the entrypoint needs it)
    --userns=keep-id:uid=1000,gid=1000     your user becomes container uid 1000
    --tmpfs /run:rw,mode=755               writable scratch space in memory
    --tmpfs /tmp:rw,mode=1777
    --memory 16g --cpus <n> --pids-limit 8192
    --label oillamp.agent-id=<id> --label oillamp.lamp=<lamp path> --label oillamp.session=<session>
    --volume <lamp>/.oillamp/session:/oillamp/session:ro
    --volume <lamp>/.oillamp/sockets/host:/oillamp/sockets/host:ro
    --volume <lamp>/.oillamp/sockets/agent:/oillamp/sockets/agent
    --volume <lamp>/.oillamp/sockets/infra:/oillamp/sockets/infra
    --volume <lamp>/.oillamp/recordings:/oillamp/recordings
    --volume <lamp>/agent-lamp-<id>:/home/agent
    [--device /dev/dri/renderD128 --group-add keep-groups]    only when the GPU is used
    localhost/oillamp/sandbox:<tag>
```

Things to know about these flags:

- **The three socket directories are mounted one by one, never their parent.** The agent runs as
  your user id, and `sockets/` belongs to you, so if it were mounted the agent could move the
  infra user's `infra/` aside and serve its own desktop in its place, or leave a symbolic link
  there for the next session to follow. A directory that is itself a mount point cannot be moved
  from inside the container. `host/` is read-only because the sandbox only connects to the sockets
  in it. `oillamp at` refuses a lamp in which any of these directories is a symbolic link.

- **Capabilities are not dropped by a podman flag.** Container root starts with podman's default
  set of capabilities, which it needs to create directories for each user and switch users. The
  entrypoint then starts every long-running process with `setpriv`, which removes all
  capabilities (`--inh-caps=-all --ambient-caps=-all --bounding-set=-all`). So the agent's
  processes and the infra processes have none.
- **There is no `--init` and no `no-new-privileges`.** The entrypoint is process 1 itself. Instead
  of `no-new-privileges`, the image contains no setuid programs.
- **The labels** let `oillamp list` and `oillamp remove` find containers without keeping their own
  registry. `oillamp.lamp` in particular lets `remove` find a running container even when the
  lamp's `lamp.json` has already been deleted.
- **Container root is not host root.** With `keep-id`, container uid 0 maps to the first
  subordinate id, for example 165536, which owns nothing on the host.

### The entrypoint, step by step

`/usr/local/lib/oillamp/entrypoint` is a bash script. It runs as container root and does this:

1. Reads `/oillamp/session/runtime.env` and checks the required settings are there. Missing
   settings stop the container with exit code 70.
2. Deletes `vnc.sock`, `ready.json` and `ssh.sock` left by the previous session. The sockets
   directory is a bind mount, so it outlives the container, and wayvnc cannot bind a path that
   already exists.
3. Creates `/run/lamp` (infra user, 0711), `/run/lamp/private` (0700) and `/run/agent` (agent,
   0700), plus cache directories, and writes sway's screen size into `/run/lamp/output.conf`. It
   also creates the X11 socket directory `/tmp/.X11-unix` as root with mode 1777, as on any Linux
   system; otherwise sway would create it as the infra user with mode 0700.
4. Copies pi's configuration and SDKMAN from `/usr/local/share/oillamp/` into the agent's home,
   as the agent user, never overwriting anything already there. This is needed because
   `/home/agent` is a bind mount: anything the image put there at build time is hidden at run
   time. Failure here never stops the container.
5. Starts **sway** as the infra user and waits up to 20 s for its Wayland socket
   `/run/lamp/wayland-1`. If the GPU renderer (`gles2`) fails, it retries once with software
   rendering (`pixman`) and records `gpu_fallback: true`.
6. Makes `/run/lamp/wayland-1` world-connectable (0666) so the agent's applications can draw.
   sway's control socket stays private (0700), so the agent cannot send it commands.
   Then it opens the X11 display to the agent: sway has started Xwayland (the X11 server) as the
   infra user on display `:0`, and Xwayland only accepts its own user. The entrypoint makes the
   socket `/tmp/.X11-unix/X0` connectable and runs `xhost +si:localuser:agent` as the infra user.
   Without this, every X11 application the agent starts, including Java Swing, fails with
   "Authorization required". A failure here is logged but does not stop the sandbox.
7. Starts **wayvnc** as the infra user on `/oillamp/sockets/infra/vnc.sock`. Its own control
   socket is in `/run/lamp/private`, out of the agent's reach.
8. If recording is enabled, starts **wf-recorder** as the infra user, writing
   `/oillamp/recordings/<session>.mkv`. The quality option is called `crf` for software encoders
   and `qp` for hardware encoders (`*vaapi*`, `*nvenc*`, `*qsv*`, `*_v4l2m2m`).
9. Starts the **network bridges** as the infra user: a socat listening on `127.0.0.1:3128` for the
   proxy, and one per forward.
10. Starts, as the agent user, a **D-Bus session bus** and the **SSH listener**: socat on
    `/oillamp/sockets/agent/ssh.sock` that runs `sshd -i` for each connection.
11. Waits until both the VNC socket and the SSH socket *accept a connection* (not just exist),
    then writes `ready.json` as the infra user:
    `{"renderer":"pixman","gpu_fallback":false,"width":1920,"height":1080,"session":"<id>"}`.
12. Supervises. If sway, wayvnc, a bridge or the recorder exits, it stops everything and exits
    with code 70. The agent-side processes (D-Bus, SSH listener) are not watched this way.
13. On SIGTERM or SIGINT (which `podman stop` sends), it sends SIGINT to wf-recorder so the
    `.mkv` file is finalised, waits up to 10 s, stops the rest, and exits 0.

The `drop` function starts each process. It uses `setpriv` to switch user and remove
capabilities, sets `HOME` and the XDG directories for that user (`setpriv` does not), sets the
umask, and prefixes each output line with a tag such as `[sway]`. In GPU mode it uses
`--keep-groups` instead of `--init-groups`, so the host's `render` group survives the switch.

### The two users inside

| | agent | lamp (infra user) |
|---|---|---|
| uid inside | 1000 | 1001 |
| uid on the host | yours | a subordinate id, e.g. 166536 |
| home | `/home/agent` (the agent directory) | `/var/lib/lamp` |
| runs | sshd per connection, the shell, D-Bus, everything the agent starts | sway, swaybg, wayvnc, wf-recorder, socat bridges |
| can reach | the Wayland display socket, its home, `/tmp`, the proxy port | its own sockets and files |

The agent cannot signal the infra processes (different uid, no capabilities), cannot connect to
sway's or wayvnc's control sockets (mode 0700, owned by `lamp`), and cannot write the recordings.
These were tested on real hardware; see STATUS.md.

---

## 7. The running session

### The state machine

A session's rules live in `SessionMachine.step(state, event, now)`, a pure function that returns
the next state and a list of actions. `Supervisor` performs the actions and turns what happens
into new events. This keeps every decision about how a session ends in one place, where tests can
check it quickly.

States (`SessionState`):

| State | Meaning |
|---|---|
| `Starting` | The container has been started; the supervisor has not processed its readiness yet. |
| `AwaitingTerminal` | The sandbox answered on both sockets. The windows have been asked to open. Nobody is connected yet. |
| `Running` | The primary shell is connected. Counts extra shells. |
| `ShuttingDown` | The shutdown sequence is running. Events other than `ShutdownCompleted` are ignored. |
| `Stopped` | Final. Holds the exit code. |

Transitions:

| In state | Event | Goes to | Actions |
|---|---|---|---|
| Starting | `ContainerReady` | AwaitingTerminal | announce, open viewer (unless disabled), open terminal |
| Starting | `ContainerExited` | ShuttingDown (startup failed) | report, shut down |
| AwaitingTerminal | `PrimaryConnected` | Running | announce |
| AwaitingTerminal | `Tick` after the terminal timeout | ShuttingDown (startup failed, `OIL-TERM-002`) | report, shut down |
| Running | `PrimaryDisconnected` | Running (shell window closed) | announce how to open another shell |
| Running | `ShellConnected` / `ShellDisconnected` | Running (count ±1) | announce |
| any live state | `Interrupted` (Ctrl-C, SIGTERM, SIGHUP from closing the launching terminal) | ShuttingDown | close extra shells, shut down |
| any live state | `StopRequested` (`oillamp stop`) | ShuttingDown | close extra shells, shut down |
| AwaitingTerminal, Running | `ContainerExited` | ShuttingDown (container died) | close extra shells, shut down |
| any live state | `ActionFailed` for the terminal | ShuttingDown (startup failed) | report, shut down |
| any live state | `ActionFailed` for the viewer | unchanged | warning |
| ShuttingDown | `ShutdownCompleted` | Stopped | report cleanup problems, exit |

Closing a window never ends a session: not the shell window, not a viewer, not an extra shell.
The session ends where it was started, with Ctrl-C or by closing that terminal, or with
`oillamp stop`.

Why a terminal that fails to open still ends the session, while a failing viewer does not: a
terminal that cannot open almost always means the terminal setting is wrong, and it would be wrong
in every session, so the session stops and says why. Without a viewer you just cannot see the
desktop, and `oillamp view` can open another, so the session continues.

Exit codes by shutdown reason (`ShutdownReason.exitStatus`): `oillamp stop`
→ 0; interrupted while `Running` → 0; interrupted before `Running` → 130; container died → 5;
startup failed → 5.

### Threads

- **Event loop** (the thread that called `Supervisor.run`). Takes one event at a time from a
  queue, calls `SessionMachine.step`, stores the new state, performs the actions. If no event
  arrives within a second, it creates a `Tick`, which is how timeouts are noticed. Only this thread
  writes the state. The field is `volatile` because other threads read it.
- **Sandbox watcher.** Every 2 seconds runs `podman container inspect` to check the container is
  still running, and connects to the VNC, SSH and proxy sockets. Reports a change immediately and
  a health line every 30 seconds.
- **Relay threads.** One accept loop per relay socket, and two virtual threads per connection
  (one per direction).
- **Control socket threads.** One accept loop, and one thread per connection, which answers its
  one request. A connection that sends nothing for 5 s is closed, so it cannot hold up the rest.
- **Egress threads.** One accept loop per proxy or forward socket, one thread per connection, and
  a single writer thread for the network log.
- **Shutdown thread.** Runs the shutdown sequence so the event loop stays responsive.
- **JVM shutdown hook.** On Ctrl-C or SIGTERM it posts `Interrupted` and waits for the state to
  become `Stopped`, for as long as the shutdown sequence may take: `timeouts.stop_seconds` plus
  a minute. If the sequence never began in that time, it runs it itself. If it began but has not
  finished, it does not start it a second time; that would force-remove the container in the
  middle of the first `podman stop`, while the recording is being finished.

Before the supervisor exists, `Commands.at` holds a shutdown hook of its own. A Ctrl-C while the
image builds or the desktop comes up removes the container that run started, and so does a start
that fails. The supervisor's hook replaces it once the session is up. Without this, a container
started by an interrupted or failed run kept running with nothing watching it.

### Shutdown sequence

`Supervisor.shutDown` runs every step even if an earlier one failed:

1. Close both SSH relays and all their connections.
2. `podman stop --time <timeouts.stop_seconds> oillamp-<id>`, shielded from Ctrl-C. This lets the
   entrypoint finalise the recording.
3. `podman rm -f oillamp-<id>`, also shielded. If stop failed but remove worked, that is reported
   as information, not an error. If both failed, `OIL-SANDBOX-005`.
4. Close the egress proxy, the control socket and the windows oillamp opened.
5. Delete `session.json` and update `lastSessionAt` in `lamp.json`.
6. Print the summary: why it ended, how long it ran, the session id, the recording path.

The lock is released by `Commands.at` after `Supervisor.run` returns.

### Crash recovery

If the supervisor is killed (`kill -9`, power loss), the OS releases the lock. On the next
`oillamp at`, `SandboxPhase` finds the container with the same name and removes it, and
`LampPlanner` removes the stale `session.json`. `oillamp stop` on a lamp with no supervisor
finds the orphaned container and removes it.

### The control protocol

`Control.java`. One JSON object per line, one request per connection, over `run/control.sock`.

| Request | Reply |
|---|---|
| `{"op":"status"}` | `{"ok":true,"state":"running","detail":…,"lamp":…,"session":…,"container":…,"renderer":…,"desktop":…,"uptime":…,"shells":…,"viewer":…}` |
| `{"op":"stop"}` | `{"ok":true,"state":"shutting-down"}` and the session begins to shut down |
| `{"op":"view","view_only":"true"}` | `{"ok":true}` and another viewer opens |
| `{"op":"shell"}` | `{"ok":true,"argv":["ssh","-F",…]}`; the *asking* process runs that ssh in its own terminal |

A missing socket means no session is running (`OIL-SESSION-001`). A socket that does not answer
means a supervisor died without cleaning up (`OIL-SESSION-002`); `oillamp stop` then removes the
container, the dead socket and `session.json`. A session that answers `{"ok":false,…}`, for example
to `shell` while it is shutting down, is alive and has refused (`OIL-SESSION-003`).

Every request is answered at once, so the asking command waits at most 5 s. A socket that accepts
the connection but answers nothing within that time belongs to a supervisor that is frozen or
stuck; that is also `OIL-SESSION-002`, but `stop` then removes nothing, because the process
listening on the socket is still alive.

### The windows

- **Terminal.** `Terminals.choose` picks the terminal emulator: `terminal.command` if set, else
  `terminal.profile` if set, else the desktop's own (GNOME: Ptyxis, GNOME Terminal, Console; KDE:
  Konsole), else the first installed from the table (`ptyxis`, `gnome-terminal`, `kgx`, `konsole`,
  `kitty`, `foot`, `alacritty`, `wezterm`, `xterm`). The command inside is
  `ssh -F <lamp>/.oillamp/ssh_config -o ProxyCommand="socat - UNIX-CONNECT:<run>/ssh-primary.sock" -t lamp-<id> "cd ~/workspace && exec bash -l"`.
- **Viewer.** TigerVNC's `vncviewer`, given the socket path directly:
  `vncviewer -Shared=1 -AcceptClipboard=0 -SendClipboard=1 -SendPrimary=0 -RemoteResize=0 -geometry 1920x1080 <socket>`.
  The clipboard flags follow `viewer.clipboard`.

The session is up when the primary *SSH connection* arrives, and `oillamp status` says when it
has closed. oillamp watches the connection, not the terminal *process*: many terminal emulators
hand the window to an existing server process and exit immediately, so their process id says
nothing about the window.

A window that exits with a non-zero code within 3 seconds is reported: as a warning for the
viewer (`OIL-VIEW-001`), as a failure that ends the session for the terminal (`OIL-TERM-003`).

---

## 8. The network

### Why there is no network interface

The container runs with `--network=none`: it has only a loopback interface, no route and no DNS.
Firewall rules are not needed, because there is nothing to filter. The internet is reached
through the egress proxy instead, which runs in oillamp on the host.

### The proxy (`Egress.java`)

The proxy listens on `sockets/host/proxy.sock` (mode 0666, so the infra user's socat can connect;
the enclosing `.oillamp/` is 0700). Inside the container, socat forwards `127.0.0.1:3128` to it.
`/etc/profile.d/oillamp.sh` sets `HTTP_PROXY`, `HTTPS_PROXY` (both spellings), `NO_PROXY` and
`JAVA_TOOL_OPTIONS` so that tools use it.

It understands:

| Request | What happens |
|---|---|
| `CONNECT host:port` (HTTPS and most real traffic) | policy check → connect → `200 Connection Established` → copy bytes both ways without reading them |
| `GET http://host/path` (absolute form, plain HTTP) | policy check → connect → forward the request in normal form with hop-by-hop headers removed and `Connection: close` → stream the answer back |
| `GET /path` (normal form) | `400` explaining that this is a proxy |
| `GET https://…` | `400`: HTTPS must use `CONNECT` |
| a method that is not a plain word, or a host that is not a name or an address | `400`, before anything is printed or logged, so the agent cannot put terminal escape sequences on the user's screen or break a line of the network log |
| denied | `403`, with a body such as `oillamp: connection to 10.0.0.1:5432 denied by rule "block private, internal and loopback ranges" in oillamp.toml` |
| name does not resolve | `502` |
| cannot connect in 10 s | `504` for `CONNECT`, `502` for plain HTTP |

Limits: request head at most 64 KiB; at most 512 open connections (more are closed immediately);
name resolution times out after 5 s.

TLS is never intercepted. oillamp sees the host name and port, and the addresses it resolved,
never the content.

### The policy (`Policy.java`)

The policy is `[network]` in `oillamp.toml`: a default (`allow` or `deny`) and an ordered list of
rules. Each rule has a `label`, an `action` and up to three criteria:

- `hosts`: exact names, `*.example.com` (any subdomain, not `example.com` itself) or `*`;
  case-insensitive, trailing dot ignored.
- `ports`: numbers or ranges such as `"8000-8100"`.
- `cidrs`: address ranges such as `"10.0.0.0/8"` or `"fc00::/7"`.

A rule matches when *every criterion it lists* matches. A criterion it does not list is ignored.
A rule with no criteria matches everything.

How a connection is decided:

1. The host name is resolved **on the host** into a list of addresses (or used directly if it is
   an address).
2. For each address, in the resolver's order, the rules are checked from top to bottom. The first
   matching rule decides. If none matches, the default decides.
3. The proxy connects to the first address that is allowed. If none is allowed, the connection is
   denied, and the reported rule is the one that decided the *first* address.

Deciding per address, not per name, is what makes the default safe. Anyone can point a public name
at `127.0.0.1` or a private address. Because the shipped deny rule lists address ranges, such a
name is refused by where it points.

The shipped default is `default = "allow"` with two deny rules. The first, *"Eden AI only through
its EU endpoint"*, denies the host `api.edenai.run` (see [Eden AI, only in the
EU](#eden-ai-only-in-the-eu)). The second, *"block private, internal and loopback ranges"*, denies `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `100.64.0.0/10` (carrier-
grade NAT, often used by VPNs), `127.0.0.0/8`, `169.254.0.0/16`, `0.0.0.0/8`, `::1/128`, `::/128`,
`fc00::/7` and `fe80::/10`. To allow an internal service, add an `allow` rule *above* it.

Two things hold whatever the rules say. An IPv4 address written in IPv6 notation, such as
`::ffff:7f00:1` (which is `127.0.0.1`), is judged as the IPv4 address it stands for, because that is
where the connection goes. And `0.0.0.0` and `::`, which Linux treats as "this machine", are always
refused. The proxy then connects to exactly the address the policy judged, never to a name or a
text form that could be read differently.

### Eden AI, only in the EU

Both harnesses reach Eden AI only through its EU endpoint, `https://api.eu.edenai.run/v3`, which
only serves models hosted in the EU. Three things make that so:

- **pi.** Its Eden AI extension reads `EDENAI_BASE_URL` and `EDENAI_EU_ONLY`.
  `/etc/profile.d/oillamp.sh` sets them in every shell, after reading `runtime.env`, and oillamp
  never copies them from the host. So a host that points `EDENAI_BASE_URL` elsewhere changes
  nothing in the sandbox. With `EDENAI_EU_ONLY` set, pi offers only the EU models.
- **opencode.** It knows Eden AI already, but only the global endpoint, and it offers a model list
  from its own catalog, most of which the EU endpoint does not serve. While the image is built,
  `build/write-opencode-config.mjs` writes `/usr/local/share/oillamp/opencode/opencode.json`. That
  file sets the EU endpoint and replaces the model list with the one the EU endpoint gives.
  `OPENCODE_CONFIG` points opencode at it. The list is as fresh as the image; if it could not be
  fetched during the build, opencode still uses the EU endpoint and keeps its own list.
- **The network policy.** The shipped rule *"Eden AI only through its EU endpoint"* refuses
  `api.edenai.run`, for any tool that ignores those settings. It is an ordinary rule: removing it
  from `oillamp.toml` allows the global endpoint again. A lamp that lists its own rules and does
  not include it has no such rule, as for any other shipped rule.

### Forwards

A forward exposes one target at a fixed port inside the sandbox:

```toml
[[network.forwards]]
name   = "llm"
port   = 8000
target = "llm.corp.example.com:8000"
```

The supervisor listens on `sockets/host/fwd-llm.sock`; socat in the container listens on
`127.0.0.1:8000`. Each connection is opened from the host to the target, so your VPN and routing
apply. Forwards bypass the policy on purpose: you chose the target. They are listed in the agent
guide and logged like proxy connections. A forward cannot use port 3128, and names and ports must
be unique.

### The network log

`.oillamp/logs/network-<session>.jsonl`, one JSON object per connection:

```json
{"ts":"2026-09-22T14:20:11.412Z","channel":"proxy","method":"CONNECT","host":"repo1.maven.org","port":443,"address":"151.101.12.209","decision":"allow","rule":"(default)","bytesUp":1830,"bytesDown":2203114,"ms":841}
```

`rule` is `(default)` when no rule matched, `unresolved` when the name did not resolve, and
`forward` for forwards. Allowed connections are only logged when `network.log_allowed = true` (the
default). Denials are also printed in your terminal when `network.console_denied = true` (the
default). A name that did not resolve is logged with `"decision":"deny"` but not printed as a
denial, because no rule refused it.

### Outbound SSH and other tools

- `git@github.com:…` works because `/etc/ssh/ssh_config.d/50-oillamp-proxy.conf` sends every SSH
  connection except to `lamp-*` through the proxy with `CONNECT github.com:22`.
- pip is told to install into `~/.local` (`PIP_USER=1`, `PIP_BREAK_SYSTEM_PACKAGES=1`) because
  Debian refuses system-wide pip installs and the root filesystem is read-only anyway.
- Tools that do their own DNS lookups and ignore proxy variables fail. There is no DNS.
- Firefox has no proxy policy file yet. See STATUS.md.

---

## 9. Desktop, viewer, recording and GPU

### Desktop

sway runs headless with one virtual screen, `HEADLESS-1`, at `display.width` × `display.height`
and `display.scale`. sway starts Xwayland, the X11 server, at once and keeps it running
(`xwayland force`), so X11 applications, including Java Swing, work. It must keep running: by
default sway stops Xwayland 10 seconds after the last X11 client exits, and a new Xwayland would
forget that the agent is allowed to connect.

Windows float by default (`display.windows = "floating"`), as on most desktops: each opens at the
size its application asks for, moves by its title bar and resizes by its 4-pixel border.
`display.windows = "tiling"` makes sway split the screen between windows instead, so none covers
another, but a single window then fills the screen and cannot be moved or resized. The entrypoint
writes the choice to `/run/lamp/windows.conf`, which the sway config includes; it accepts only
those two words, because that file is compositor configuration. Dialogs float either way. The
agent's guide describes whichever is in effect.

No key binding exits sway or runs a command, because the agent can type into the desktop
and sway runs as the infra user. `TheSandboxImageSpec` checks the sway config for this.

The agent's environment points at the display with `WAYLAND_DISPLAY=/run/lamp/wayland-1` and
`DISPLAY=:0`. `_JAVA_AWT_WM_NONREPARENTING=1` is set because Swing otherwise misbehaves under a
tiling compositor.

### The `lamp` helper

A bash script at `/usr/local/bin/lamp`. Each subcommand wraps one tool:

| Command | Runs |
|---|---|
| `lamp screenshot [--region X,Y,W,H] [--out FILE]` | `grim`; default output `~/screenshots/screenshot-<time>.png`; prints the path |
| `lamp click X Y [left\|right\|middle] [double]` | `lamp-pointer click`: move to X,Y, press, release |
| `lamp move X Y`, `lamp drag X1 Y1 X2 Y2`, `lamp scroll [X Y] AMOUNT [HORIZONTAL]` | `lamp-pointer …` |
| `lamp type "text"` | `wtype -s 150 -- "text"` |
| `lamp key ctrl+shift+t` | `wtype -s 150 -M ctrl -M shift -k t` |
| `lamp wait-stable [SECONDS]` | takes a screenshot every 0.5 s until two are identical (default limit 10 s) |
| `lamp info` | size, renderer, output name and screenshot directory |

It is a script so that the agent can read it and call the tools directly when it needs something
the script does not do. There is no window list: that would need sway's control socket, which the
agent must not reach.

**Pointer input goes through VNC.** `/usr/local/lib/oillamp/lamp-pointer` is a small Python
program (standard library only) that connects to the desktop's VNC socket and sends pointer events:
absolute positions and button presses, the same way your viewer does. Coordinates are screen
pixels, as in a screenshot. It remembers the last position in `$XDG_RUNTIME_DIR`, so
`lamp scroll AMOUNT` scrolls where the pointer last went. The agent can reach the VNC socket, which
gives it nothing new: it can already see the screen and send input.

`wlrctl` was used before and did not work, for two reasons. `wlrctl pointer move DX DY` moves the
pointer *by* an offset, not *to* a position. And every `wlrctl` call creates a virtual mouse of its
own and removes it when it exits; when it disappears, the window under the pointer loses pointer
focus, so the next call's click reaches no window at all. Both apply to Wayland and X11
applications. The spike "The agent can click and type in an X11 application, using only lamp"
checks the result.

**Keyboard input waits 150 ms before the first key** (`wtype -s 150`). Each `wtype` call sends the
desktop a new keyboard layout, and X11 applications drop the key that arrives together with it, so
without the pause the first key of every `lamp type` was lost.

### Viewer

`vncviewer` connects straight to the Unix socket. `-SendPrimary=0` stops your X selection being
pushed into the sandbox, `-RemoteResize=0` stops the viewer resizing the desktop, and the clipboard
direction follows `viewer.clipboard` (default `to-agent`: you can paste in, the sandbox cannot
copy out).

### Recording

Off by default. With `recording.enabled = true`, wf-recorder writes
`.oillamp/recordings/<session>.mkv` at a constant `recording.max_fps` frames per second. A
Matroska file stays playable even if the recorder is killed.

Retention (`Retention.select`) runs at every session start and on `oillamp recordings --prune`.
A recording is deleted if it is older than `max_age_days` *or* falls outside the newest
`max_total_gb`. `0` disables either limit. Deletion goes through `podman unshare rm` because the
files belong to the infra user.

`oillamp recordings <dir>` lists recordings with duration and size. The duration comes from the
file's own timestamps (created when recording started, last modified when it stopped), so no
`ffmpeg` is needed on the host. When the filesystem keeps no creation time, the session id in the
file name is used as the start.

### GPU

`Gpu.decide` chooses between hardware rendering (`gles2`) and software rendering (`pixman`):

- `display.gpu = "off"`: always software.
- `"auto"` (default): hardware only if there is a `/dev/dri/renderD*` node, podman is available
  and uses crun, the node's driver is one of `i915`, `xe`, `amdgpu`, `radeon`, `nouveau`,
  `virtio_gpu`, and you are in the group that owns the node (usually `render`). Otherwise
  software, with the reason printed. If you are not in the group, the exact `usermod` command is
  printed. oillamp does not run it, because it changes your account and only takes effect after
  you log in again.
- `"on"`: like `auto`, but any unmet condition is an error (`OIL-GPU-003`).

crun is required because passing the render group into the container needs
`--group-add keep-groups`, which runc does not support. Ubuntu 24.04 installs runc by default,
which is why `crun` is a required host package.

Even with hardware chosen, the entrypoint falls back to software if sway cannot start with it.

---

## 10. Configuration reference

Configuration is TOML. Three layers are merged, later ones winning:

1. built-in defaults (`ConfigDefaults`),
2. `~/.config/oillamp/config.toml` (optional, applies to every lamp),
3. `<lamp>/oillamp.toml` (must contain `schema_version = 1`).

Tables merge key by key. **Arrays replace**: if a lamp lists `network.rules`, it gets exactly
those rules and none from the global file. Unknown keys are errors, with a suggestion for the
nearest known key, because a misspelled network rule key would otherwise silently not apply.

`ConfigLoader` reads the merged tree with `ConfigSection`, which records every problem with its
file, key path (for example `network.rules[0].cidrs`), value and expectation, and keeps reading.
All problems are reported together.

The "Used" column says whether the running code currently acts on the setting. Settings marked
"no" are accepted and validated, but have no effect yet. They are listed under "Known gaps" in
STATUS.md.

| Key | Default | Meaning | Used |
|---|---|---|---|
| `display.width`, `display.height` | 1920, 1080 | desktop size (640–7680 × 480–4320) | yes |
| `display.scale` | 1.0 | output scale (0.5–4.0) | yes |
| `display.gpu` | `"auto"` | `auto`, `on`, `off` | yes |
| `display.windows` | `"floating"` | `floating` (move and resize by dragging) or `tiling` (windows share the screen) | yes |
| `viewer.open_on_start` | true | open the viewer when the session starts | yes |
| `viewer.clipboard` | `"to-agent"` | `to-agent`, `both`, `none` | yes |
| `viewer.view_only` | false | viewer shows but does not send input | yes |
| `viewer.max_fps` | 30 | wayvnc frame rate limit (1–120) | yes |
| `terminal.profile` | `"auto"` | a terminal from the table, or `auto` | yes |
| `terminal.command` | (unset) | custom argv with `{cmd}` and optional `{title}` | yes |
| `recording.enabled` | false | record the desktop | yes |
| `recording.codec` | `"libx264"` | wf-recorder codec | yes |
| `recording.crf` | 30 | quality, 0–51, lower is better | yes |
| `recording.max_fps` | 10 | recording frame rate (1–60) | yes |
| `recording.max_age_days` | 14 | delete older recordings; 0 = no limit | yes |
| `recording.max_total_gb` | 20 | keep the newest up to this size; 0 = no limit | yes |
| `limits.memory` | `"16g"` | `podman --memory` | yes |
| `limits.cpus` | 0 | `podman --cpus`; 0 means host CPUs minus one | yes |
| `limits.pids` | 8192 | `podman --pids-limit` | yes |
| `network.default` | `"allow"` | decision when no rule matches | yes |
| `network.log_allowed` | true | write allowed connections to the network log | yes |
| `network.console_denied` | true | print denials in your terminal | yes |
| `[[network.rules]]` | two deny rules | see [The policy](#the-policy-policyjava) | yes |
| `[[network.forwards]]` | none | see [Forwards](#forwards) | yes |
| `llm.forward` | `""` | name of a forward; sets `OILLAMP_LLM_BASE_URL`, `OILLAMP_LLM_MODELS`, `OILLAMP_LLM_PROVIDER` | yes |
| `llm.base_path` | `"/v1"` | appended to the forward's URL | yes |
| `llm.models` | `[]` | put into `OILLAMP_LLM_MODELS` | yes |
| `llm.provider_name` | `"company"` | put into `OILLAMP_LLM_PROVIDER` | yes |
| `llm.api_key_env`, `llm.api_key_file` | | where to read an API key on the host | **no** |
| `agent_tools.install` | `["opencode","pi"]` | harnesses installed into the image (part of the image hash) | yes |
| `agent_tools.versions` | `latest` for both | versions to install | **no** (always latest) |
| `image.base` | `"docker.io/library/debian:trixie"` | base image | yes |
| `image.node_version` | `"24"` | Node.js major version | yes |
| `image.jdk_package` | `"temurin-25-jdk"` | JDK apt package | yes |
| `image.extra_apt_packages` | `[]` | extra apt packages in the image | yes |
| `host.auto_install` | true | allow installing host packages (`false` works like `--no-install`) | yes |
| `timeouts.container_ready_seconds` | 60 | wait for `ready.json`; twice as long straight after an image build | yes |
| `timeouts.terminal_connect_seconds` | 60 | wait for the terminal to connect | yes |
| `timeouts.stop_seconds` | 15 | `podman stop --time` | yes |

`oillamp config <dir> check` validates, `show-effective` prints a summary of the result, `path`
prints the file's path. `check` and `show-effective` merge the global file and the lamp's, as
`oillamp at` does (`LampPhase.configurationFiles`).

Environment variables `EDENAI_API_KEY` and `EDENAI_MAX_TOKENS` are copied from your environment
into the sandbox when set (`RuntimeEnv.INHERITED_FROM_HOST`). Their values are never logged.
`EDENAI_BASE_URL` and `EDENAI_EU_ONLY` are not copied: the sandbox sets them itself, so that Eden
AI is only used through its EU endpoint (see [Eden AI, only in the EU](#eden-ai-only-in-the-eu)).

### `runtime.env`

`RuntimeEnv.render` writes `.oillamp/session/runtime.env`, which the entrypoint and every login
shell read. Every value is single-quoted, a single quote inside is escaped as `'\''`, and values
containing a line break are refused, because this file is executed by a root shell. Keys are
sorted so the file is stable.

Contents: `OILLAMP_SESSION`, `OILLAMP_AGENT_ID`, `OILLAMP_LAMP_NAME`,
`OILLAMP_DISPLAY_WIDTH/HEIGHT/SCALE`, `OILLAMP_RENDERER`, `OILLAMP_VNC_MAX_FPS`,
`OILLAMP_RECORDING_ENABLED/CODEC/CRF/MAX_FPS`, `OILLAMP_PROXY_PORT`, `OILLAMP_FORWARDS`
(`name:port name:port`), the LLM variables when configured, and `EDENAI_API_KEY` and
`EDENAI_MAX_TOKENS` when they are set on the host.

---

## 11. Problem codes and exit codes

### Exit codes (`ExitStatus`)

| Code | Name | Meaning |
|---|---|---|
| 0 | `SUCCESS` | Success, or a session that ended normally. |
| 1 | `ERROR` | Any error not covered below. |
| 2 | `USAGE` | Bad command line or bad configuration. Also `remove` without `--yes`. |
| 3 | `PREREQUISITES_MISSING` | Host packages, subordinate ids or podman are not ready. |
| 4 | `LAMP_BUSY` | Another session holds this lamp, or `remove` found it still in use. |
| 5 | `SESSION_FAILED` | The container or the session failed. |
| 130 | `INTERRUPTED` | Interrupted before the session was running. |

### Problem codes

| Code | Meaning |
|---|---|
| `OIL-HOST-001` | Not Linux. |
| `OIL-HOST-002` | Not an apt-based distribution; install the listed packages yourself. |
| `OIL-HOST-003` | No graphical session (`WAYLAND_DISPLAY` and `DISPLAY` unset). |
| `OIL-HOST-010` | No subordinate uid/gid range of at least 65536 ids. |
| `OIL-PKG-001` | Required host packages are missing. |
| `OIL-PKG-002` | sudo cannot be used (needs a password and there is no terminal, or not installed). |
| `OIL-PKG-003` | `apt-get install` failed. |
| `OIL-PODMAN-001` | podman too old. |
| `OIL-PODMAN-002` | podman is not rootless. |
| `OIL-PODMAN-003` | `podman unshare true` fails. |
| `OIL-PODMAN-004` | Ubuntu's AppArmor restriction on user namespaces blocks podman. |
| `OIL-PODMAN-005` | podman is installed but did not answer when asked for its version. |
| `OIL-LAMP-001` | The lamp's path cannot be mounted by podman (it contains a colon). |
| `OIL-LAMP-002` | Directory is not empty and not a lamp (use `--init` to proceed anyway). |
| `OIL-LAMP-003` | Refused path: `/`, your home directory, or a system directory. |
| `OIL-LAMP-004` | Lamp created by a newer oillamp. |
| `OIL-LAMP-005` | Lamp is on a filesystem without Unix sockets (NFS, SMB, FAT, sshfs). |
| `OIL-LAMP-006` | Cannot write the lamp, or it is not a lamp (used for several "not a lamp" answers). |
| `OIL-LAMP-007` | `remove` refused: the lamp is in use. |
| `OIL-LAMP-008` | `remove` could not delete part of the lamp. |
| `OIL-LAMP-009` | `recordings --open` named a session with no recording. |
| `OIL-LAMP-010` | The desktop could not open a recording. |
| `OIL-LOCK-001` | Lamp already running. |
| `OIL-LOCK-002` | Warning: cleaned up after a previous session that crashed. |
| `OIL-CONFIG-001` | TOML syntax error. |
| `OIL-CONFIG-002` | Unknown key. |
| `OIL-CONFIG-003` | Wrong type. |
| `OIL-CONFIG-004` | Invalid value. |
| `OIL-GPU-001` | Information: GPU not used, and why. |
| `OIL-GPU-003` | `display.gpu = "on"` but the GPU cannot be used. |
| `OIL-SSH-001` | `ssh-keygen` failed. |
| `OIL-SSH-002` | Warning: a second connection to the primary SSH socket was refused. |
| `OIL-TERM-001` | No supported terminal emulator found. |
| `OIL-TERM-002` | The terminal window never connected. |
| `OIL-TERM-003` | The terminal emulator would not start. |
| `OIL-VIEW-001` | Warning: the viewer closed immediately. |
| `OIL-NET-001` | Socket path longer than 107 bytes. |
| `OIL-NET-002` | Cannot listen on a socket. |
| `OIL-NET-010` | Warning: a forward's target is unreachable. |
| `OIL-SESSION-001` | No session is running for this lamp. |
| `OIL-SESSION-002` | The session's control socket does not answer. |
| `OIL-SESSION-003` | The running session refused the request (for example, it is shutting down). |
| `OIL-IMAGE-001` | `podman build` failed. |
| `OIL-SANDBOX-001` | `podman run` failed. |
| `OIL-SANDBOX-002` | The container exited while starting. |
| `OIL-SANDBOX-003` | The container did not report ready in time. |
| `OIL-SANDBOX-004` | A sandbox socket refuses connections. |
| `OIL-SANDBOX-005` | The container could not be stopped or removed. |
| `OIL-SANDBOX-006` | Warning: podman did not say whether the sandbox is running; the session carries on. |
| `OIL-EXEC-001` | A required command is not on `PATH`. |
| `OIL-USAGE-001` | Invalid command line. |
| `OIL-INTERNAL-001` | A bug in oillamp. |

---

## 12. Tests

All tests are Spock specifications in `src/test/groovy/oillamp/`. There are three kinds.

### Scenarios (`./gradlew test`)

Each scenario describes a situation a user can be in and runs the real `OilLamp.run(...)` against
a `SimulatedMachine`. The helper `Sandbox.groovy` sets up a simulated Ubuntu machine with a temporary
home directory; a scenario changes what it needs:

```groovy
sandbox.machine { it.withoutPodman().withoutSubordinateIds() }
var outcome = sandbox.oillamp.run('at', sandbox.lampPath().toString(), '--dry-run')
outcome.reported('OIL-PKG-001')
```

Scenarios need no podman, no network and no display, and together they run in about two minutes.
Session scenarios run a real supervisor with real Unix sockets; only the container is simulated.

Each scenario starts with a `reportInfo` block that explains, in plain words, what the scenario is
about and why it matters. After `./gradlew test`, these are rendered as Markdown into
`build/spock-reports/`.

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
| `TheSandboxImageSpec` | static checks of the image files |
| `TheShapeOfTheCodeSpec` | the five public types and the deciding/doing split |

### Spikes (`./gradlew spikes`)

A simulation only knows what we already believe about podman, sway or sshd. Spikes check those
beliefs against the real tools: they pull images, build the real image (without the toolchain) and
start real containers. They are tagged `spike`, excluded from `test`, and skip themselves when
podman is not available.

| Spec | Checks |
|---|---|
| `VerifyingPodmanAssumptionsSpec` | rootless podman works on Ubuntu; the uid mapping; read-only root with writable mounts; Unix sockets work in both directions across the container boundary |
| `VerifyingTheImageBaseSpec` | every package the image needs exists in Debian trixie |
| `VerifyingTerminalProfilesSpec` | installed terminals accept the arguments oillamp gives them; terminal emulators return before their window closes |
| `VerifyingTheSandboxDesktopSpec` | the real image: readiness, SSH login as `agent`, absolute `WAYLAND_DISPLAY`, screen size and rendering, vncviewer on a Unix socket, a playable recording, a clean second session |

`Spike.groovy` runs commands through `Machine.real()`, the same code path oillamp uses.

The results of these checks, and what was tested by hand on real hardware, are in STATUS.md.

---

## 13. Packaging

`./gradlew singleFile` produces `build/dist/oillamp`, one executable of about 40 MB:

1. `jlinkRuntime` builds a reduced Java runtime with the modules `java.base`, `java.desktop` (used
   by Sprouts), `java.sql` (used by Jackson) and `jdk.charsets` (added by hand because character
   sets are loaded by name).
2. The runtime and all jars are packed into a reproducible `tar.gz` (sorted names, fixed times and
   owners), so the same source gives the same file.
3. `src/packaging/launcher.sh` is put in front of the archive, with the version and a 16-digit
   fingerprint of the archive filled in.

When run, the launcher unpacks itself once into `~/.cache/oillamp/<version>-<fingerprint>/`
(atomically, via a temporary directory and a rename) and then `exec`s the bundled Java. Using
`exec` means Ctrl-C reaches Java directly. `oillamp --where` prints the directory. Deleting the
file and that directory uninstalls oillamp.

`./gradlew installDist` produces a conventional `bin/` and `lib/` layout in `build/install/`, for
development. It needs a JDK 25 on the machine.
