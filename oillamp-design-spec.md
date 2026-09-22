# oillamp — Design Specification

> *Rub the lamp, get a genie.*
> `oillamp at ~/lamps/my-feature` gives an AI coding agent its own sandboxed Linux machine with its own Wayland desktop, opens a terminal that is already SSH'd into it, and opens a window where you watch the agent's desktop live.

| | |
|---|---|
| Status | Draft 1 — complete enough to start implementation |
| Date | 2026-09-22 |
| Audience | The engineer or AI agent implementing oillamp from scratch, with no access to the conversation this spec came from |
| Host platform | Linux only. Primary target: Ubuntu (24.04 LTS and newer) with a Wayland desktop session |
| Implementation language | Java 25 (LTS), Gradle, Jackson, picocli, Sprouts |
| Container engine | Podman (rootless) |

---

## 0. How to read this document

Part I describes **what** oillamp must do and **why** (vision, workflow, requirements, and the decisions already taken with their rationale). Part II describes the **system architecture**: the on-disk layout, the container, the desktop stack, networking, and security. Part III describes the **Java implementation**: modules, programming model, domain types, error handling, and CLI. Part IV describes **delivery**: milestones, verification spikes, and acceptance criteria. The appendices contain concrete file templates.

Conventions used throughout:

- **MUST / MUST NOT / SHOULD / MAY** have their RFC 2119 meaning.
- Requirements are numbered (`FR-…` functional, `NFR-…` non-functional) and decisions are numbered (`D-…`) so code, tests, and commit messages can reference them.
- `<lamp>` is the directory the user passes to `oillamp at`. `<agentId>` is the lamp's generated identifier (§10.2). `<session>` is a session identifier (§10.3).
- **Status markers.** Requirements, milestones and acceptance criteria carry ✅ (built and verified), 🟡 (partly built — the bullet says which part) or ⬜ (not started). They describe the code as it stands, not the intent; `docs/STATUS.md` has the detail and the reasons.
- **§36 records every deviation taken while implementing this spec, and why.** The spec was written before the code; where the two disagree, §36 says which won and for what reason. Read it before trusting a detail in Part III.
- Items marked **⚠ VERIFY** are facts about third-party tools that the author is fairly but not fully sure about. They are collected again in §33 and MUST be confirmed with a quick spike before the dependent code is written. If a spike disproves an assumption, apply the listed fallback and update this spec.

### 0.1 Glossary

| Term | Meaning |
|---|---|
| **Lamp** | One sandbox location on disk (`<lamp>`), including its configuration, state, and the agent's persistent world. One lamp = at most one running sandbox. |
| **Agent dir** | `<lamp>/agent-lamp-<agentId>/`. The only part of the lamp the agent can see. Mounted as the agent's home directory. |
| **State dir** | `<lamp>/.oillamp/`. oillamp's metadata, keys, logs, recordings, sockets. Not visible to the agent (except the explicitly mounted sub-parts listed in §9). |
| **Session** | One run of `oillamp at` from start to shutdown. |
| **Supervisor** | The long-running `oillamp at` host process that owns a session. |
| **Infra user** | The container user `lamp` (uid 1001) that runs the compositor, VNC server, recorder, and port bridges. |
| **Agent user** | The container user `agent` (uid 1000, mapped to the host user) that the SSH session logs in as, and that runs the agent tools and the GUI apps under test. |
| **Primary session** | The SSH connection from the terminal window oillamp opened. Its end ends the session. |

---

# Part I — Vision and Requirements

## 1. Vision

Our company integrates agentic AI into its engineering workflow. We host our LLMs ourselves. Company laptops hold sensitive intellectual property, so any AI agent running locally MUST run in a sandbox that can see only what it is explicitly given.

We build **GUI applications (Java Swing)**. An agent that only edits code and runs unit tests is not enough: it must be able to **launch the GUI, look at it, interact with it, and debug it** in an environment that behaves like a real user's desktop. It therefore needs **its own independent graphical desktop**, and that desktop must be **native Wayland**, like our hosts, so the solution stays future-proof.

Humans stay in the loop: the developer MUST be able to **watch the agent's desktop live**, like a VNC client watching a VNC server, and everything the agent does on screen is **recorded**.

oillamp packages all of this into a single command:

```
oillamp at /path/to/lamp
```

It does *all* setup itself — installing host prerequisites, building the sandbox image, generating keys, writing configuration — logs everything it does, and reports problems in enough detail that the user can fix them without guessing. The tool is a console application first. It is written in modern, data-oriented Java so that a Swing GUI can later be placed on top of the same core as an orchestration layer.

## 2. Context and constraints

- **Host OS:** Linux only. Ubuntu with a Wayland session (GNOME by default) is the reference platform. Package installation targets APT-based systems (Ubuntu/Debian) in v1. Other distros MAY be supported later through the same abstraction (§11).
- **Container engine:** Podman, rootless. No Docker, no root daemon.
- **LLMs:** self-hosted, reachable over the company network (typically an OpenAI-compatible HTTP API). The endpoint may also be on the host itself (e.g. `127.0.0.1:11434`).
- **Agent tools:** terminal-based coding agents such as **OpenCode** and **pi**. The user starts them manually in the SSH terminal oillamp opens. oillamp preinstalls and preconfigures them but does not run them.
- **Apps under test:** Java Swing applications that depend on many **native libraries** the user builds on the host and copies into the agent dir. The agent also needs a **web browser**.
- **Fidelity:** The sandbox desktop does **not** need to look like GNOME. A lightweight wlroots compositor is acceptable (D-06).
- **Security posture:** The sandbox is a containment boundary against *mistakes and overreach* by an agent (and against prompt-injected instructions it might encounter on the web). It is not designed to withstand a determined kernel exploit. A stronger boundary (gVisor/microVM) is a planned option (§7).

## 3. The golden-path workflow

> **Status:** steps 1–3 are built and work today. Steps 4–10 need M3 and M4 (§32). The
> numbering is unmarked below because this section describes the finished experience, which is
> unchanged — see §4 for what exists.

This is the end-to-end user experience oillamp MUST deliver. Every other requirement serves it.

1. The user runs `oillamp at ~/lamps/feature-x` in any terminal.
2. oillamp checks the host. If prerequisites are missing (Podman, uidmap, socat, TigerVNC viewer, …), it prints what it will install and why, then installs them with `sudo apt-get`, streaming output and logging the commands. The user may be prompted for their sudo password once.
3. If `~/lamps/feature-x` does not exist or is empty, oillamp initializes a new lamp there: state dir, default `oillamp.toml`, a new `agentId`, the agent dir `agent-lamp-<agentId>/`, SSH keys. If it is an existing lamp, oillamp loads it. If another session already runs on it, oillamp refuses with a clear message.
4. If the sandbox image for the current configuration does not exist yet, oillamp builds it (first run: several minutes, with progress output).
5. oillamp starts the sandbox container: a headless Wayland compositor with a virtual monitor, a VNC server on a Unix socket, a screen recorder, the network proxy bridges, and an SSH endpoint on a Unix socket. It waits until everything reports ready.
6. **Two windows appear on the user's desktop:**
   - a **terminal** window already logged in via SSH to the sandbox as user `agent`, in `~/workspace` — where the user starts `opencode` or `pi`;
   - a **viewer** window showing the agent's desktop live. The user can watch passively or take over mouse and keyboard.
7. The terminal where the user typed `oillamp at` stays open and shows a compact live log (session status, network denials, warnings). This is the supervisor.
8. The agent works: edits code in `~/workspace`, builds, launches the Swing app on its desktop, takes screenshots, clicks through it, browses documentation through the policy-controlled proxy, talks to the self-hosted LLM through a dedicated forward.
9. **When the user closes the SSH terminal window (or exits the shell), the sandbox shuts down**: the recording is finalized, the container stops and is removed, logs are written, the lock is released, and the supervisor prints a short session summary and exits. The viewer window then shows "connection closed" and can be closed. Ctrl+C in the supervisor terminal also shuts everything down.
10. The next `oillamp at ~/lamps/feature-x` reuses the same agent dir, so the agent's home, repos, tool configs, and history persist.

`oillamp at ~/lamps/other` at the same time starts a second, fully independent sandbox.

## 4. Functional requirements

### 4.1 Command and lifecycle

- 🟡 **FR-01** `oillamp at <dir>` MUST perform all setup and start a session as described in §3. No manual configuration is required for the first run. *(Built: everything up to and including the lamp directory. Missing: the container and the session — M3/M4.)*
- ✅ **FR-02** If `<dir>` does not exist, oillamp MUST create it (including parents). If it exists and is empty, oillamp MUST initialize it as a lamp. If it exists, is non-empty, and is not a lamp, oillamp MUST refuse (Problem `OIL-LAMP-002`) unless `--init` is given.
- ✅ **FR-03** oillamp MUST refuse to use dangerous locations as a lamp: `/`, the user's home directory itself, and anything under `/bin /boot /dev /etc /lib* /proc /run /sbin /sys /usr /var` (Problem `OIL-LAMP-003`).
- ✅ **FR-04** At most **one session per lamp** may run at a time. A second `oillamp at` on a busy lamp MUST fail fast with `OIL-LOCK-001`, reporting the supervisor PID, start time, and the commands `oillamp view <dir>` / `oillamp shell <dir>` / `oillamp stop <dir>`.
- 🟡 **FR-05** Any number of lamps at different locations MAY run concurrently and MUST NOT interfere with each other. *(Lamps are independent by construction — separate directories, separate locks. Untestable until sessions exist.)*
- ⬜ **FR-06** The session MUST end when the **primary SSH session** ends (terminal closed or shell exited), when the user presses Ctrl+C in the supervisor terminal, when `oillamp stop <dir>` is run, or when a critical sandbox process dies.
- ⬜ **FR-07** Shutdown MUST finalize the screen recording, stop and remove the container, persist logs, release the lock, and print a session summary. Shutdown MUST also happen on SIGTERM/SIGHUP to the supervisor.
- 🟡 **FR-08** If a previous supervisor died without cleanup (crash, kill -9, power loss), the next `oillamp at` MUST detect the leftover container and stale state, clean it up, and say so. *(Built: stale lock detection. Missing: leftover container cleanup — M3.)*
- ⬜ **FR-09** The terminal window MUST open already connected to the sandbox via SSH, without password or host-key prompts, in `~/workspace`.
- ⬜ **FR-10** The viewer window MUST show the agent's desktop live, allow input from the user, and support host-to-agent clipboard (configurable).
- 🟡 **FR-11** Auxiliary commands MUST exist: `doctor`, `view`, `shell`, `stop`, `status`, `list`, `recordings`, `image` (§28). *(Built: `doctor` and `config`. The rest parse and then report that they need a running session.)*
- ✅ **FR-12** `oillamp at <dir> --dry-run` MUST print the complete setup plan (every step, command, file) without changing anything.

### 4.2 Sandbox

- ⬜ **FR-20** The sandbox MUST be a rootless Podman container with no access to the host filesystem except the explicitly listed mounts (§13.3). The only writable area the agent can see on the host is the agent dir.
- ⬜ **FR-21** The agent dir MUST be mounted as the agent's home directory (`/home/agent`) and MUST persist across sessions.
- ⬜ **FR-22** The sandbox MUST run its own **Wayland compositor** with a virtual monitor (default 1920×1080, scale 1). **Xwayland** MUST be available (Swing currently renders through X11/Xwayland, see D-07).
- ⬜ **FR-23** The agent MUST be able to start GUI applications, take screenshots, and inject mouse and keyboard input on its own desktop (§19.4).
- ⬜ **FR-24** A **web browser** (Firefox ESR) MUST be available on the agent's desktop and MUST use the network proxy.
- ⬜ **FR-25** The image MUST include a Java 25 JDK, Node.js LTS, common build tools, and the agent tools OpenCode and pi (configurable, §12).
- ⬜ **FR-26** Native libraries placed in `~/libs` (inside the agent dir) MUST be found automatically by native loaders (`LD_LIBRARY_PATH`) and Java (`java.library.path` via the environment).
- ⬜ **FR-27** GPU acceleration SHOULD be used when a suitable render node exists (`gpu = "auto"`), and the sandbox MUST fall back to software rendering automatically when it doesn't work.

### 4.3 Monitoring and recording

- ⬜ **FR-30** The VNC endpoint MUST be a Unix domain socket, never a TCP port on the host.
- ⬜ **FR-31** The viewer MUST be launched automatically at session start; `oillamp view <dir>` MUST re-open it at any time during the session.
- ⬜ **FR-32** The agent's screen MUST be **recorded by default** for the whole session to a video file in the state dir, invisible and unmodifiable for the agent.
- 🟡 **FR-33** Recording MUST be configurable (on/off, frame rate, quality, retention by age and total size). *(Built: the configuration and the retention calculation. Missing: the recorder — M6.)*

### 4.4 Network

- ⬜ **FR-40** The container MUST have **no direct network access** (`--network=none`). All outbound traffic goes through oillamp-controlled channels: the **egress proxy** and explicit **forwards**.
- 🟡 **FR-41** Egress MUST be governed by a **policy in the config file**. The shipped default is permissive: the public web is open, private/internal address ranges and the host's loopback are blocked (§18.4). *(Built: the policy model and its evaluation — rules, host patterns, CIDRs, first-match. Missing: the proxy that applies it — M5.)*
- ⬜ **FR-42** Every proxied connection MUST be logged (host, port, resolved address, decision, matched rule, bytes, duration). Denied requests MUST additionally be shown in the supervisor console.
- 🟡 **FR-43** **Forwards** MUST allow exposing a specific host-reachable TCP endpoint (e.g. the company LLM) at a fixed `127.0.0.1:<port>` inside the container, independent of the proxy policy. *(Built: configuration and validation. Missing: the listener — M5.)*
- 🟡 **FR-44** An `[llm]` config section MUST let oillamp preconfigure the agent tools to use the self-hosted LLM through a forward. *(Built: the config section and the URL it implies, which the agent guide already renders. Missing: writing the tool config files — M5.)*

### 4.5 Configuration

- ✅ **FR-50** Configuration MUST live in `<lamp>/oillamp.toml` (outside the agent dir, so the agent cannot change its own policy). On lamp init, oillamp writes a fully commented default.
- ✅ **FR-51** An optional user-global file `~/.config/oillamp/config.toml` MAY provide defaults for all lamps (typical use: company LLM endpoint). Precedence: built-in defaults < global < lamp.
- ✅ **FR-52** Invalid configuration MUST be reported with file, key path, the offending value, and the expected form. Unknown keys MUST be errors (typo protection).

### 4.6 Host setup

- ✅ **FR-60** oillamp MUST detect and **install missing host prerequisites itself** (APT), logging each command and its output.
- ✅ **FR-61** oillamp MUST detect and fix a missing subordinate UID/GID range for the user (`/etc/subuid`, `/etc/subgid`).
- ✅ **FR-62** oillamp MUST verify that rootless Podman actually works (functional check), including Ubuntu's AppArmor restriction on unprivileged user namespaces, and report precise remedies if not.
- ✅ **FR-63** `--no-install` (and config `host.auto_install = false`) MUST switch installation off: oillamp then reports the exact commands to run instead.

## 5. Non-functional requirements

- 🟡 **NFR-01 Out of the box.** A user with a stock Ubuntu desktop and sudo rights MUST get from zero to both windows open with one command and no manual edits. *(Built: the host and lamp preparation, including installing prerequisites. The windows need M3/M4.)*
- ✅ **NFR-02 Robustness.** Every failure MUST leave the system in a clean or recoverable state (no orphaned containers, no stale locks that block the next run). Every external command has a timeout.
- ✅ **NFR-03 Diagnosability.** Every error MUST be reported as a structured *Problem* (§27): what failed, why that matters, the evidence (command line, exit code, stderr excerpt, relevant paths), concrete fix steps, and where the full log is. No bare stack traces on the console (they go to the log file; `--debug` shows them).
- ✅ **NFR-04 Transparency.** Everything oillamp does to the host (installs, file writes, podman invocations) MUST be logged to the session log. `--dry-run` shows it in advance.
- ⬜ **NFR-05 Least privilege.** Rootless Podman, all Linux capabilities dropped except the few the container entrypoint needs to drop privileges (§13.2), `no-new-privileges`, read-only image root filesystem, no host network, no host display/D-Bus/Wayland sockets shared into the container.
- ⬜ **NFR-06 Monitoring cannot be disabled by the agent.** The compositor, VNC server, recorder, and network bridges run as a different container user than the agent, and the agent cannot signal, reconfigure, or read/write their private sockets or recordings.
- ⬜ **NFR-07 Startup time.** With the image already built, `oillamp at` SHOULD show both windows within ~10 seconds on a typical laptop.
- ✅ **NFR-08 GUI-ready core.** All decision logic MUST be pure and UI-independent; progress and state MUST be exposed as event streams, so a Swing GUI can drive the same core without changes (§23).
- ✅ **NFR-09 No preview features.** Only final Java language/library features of Java 25.
- ⬜ **NFR-10 Resource limits.** The container MUST run with configurable memory, CPU, and PID limits.

## 6. Decisions and rationale

These are the conclusions of the design discussion. Implementers MUST NOT silently change them. If one proves infeasible, document the reason and the replacement here.

| ID | Decision | Rationale |
|---|---|---|
| **D-01** | Rootless **Podman** is the container engine. | User preference; daemonless; rootless by default; supports alternative OCI runtimes (gVisor, krun) for a stronger boundary later. |
| **D-02** | The sandbox desktop is a **headless Wayland compositor inside the container**, exposed via **VNC**. | A VNC boundary is much safer than nesting the agent's compositor as a window on the host: a nested compositor would be a client of the host session with access to host clipboard and protocols. VNC also enables recording, multiple viewers, and a future built-in viewer. |
| **D-03** | The compositor is **sway (wlroots) with the headless backend**. | Best tooling ecosystem for automation: `wayvnc`, `grim`, `wf-recorder`, `wtype`, `wlrctl`; light and fast; scriptable config. |
| **D-04** | The VNC server is **wayvnc** listening on a **Unix domain socket**. | No network port exists at all; access control by filesystem permissions. TigerVNC's `vncviewer` can connect to a socket path directly. |
| **D-05** | Host viewer in v1: **TigerVNC `vncviewer`**. v2: built-in Swing viewer. | TigerVNC supports Unix sockets and clipboard direction flags. JDK 16+ supports Unix domain sockets natively, making a Swing viewer straightforward later. |
| **D-06** | No GNOME/Mutter fidelity mode. | Explicitly not needed. Keeps the image small and the startup fast. |
| **D-07** | **Xwayland is included**. | Stock OpenJDK Swing still renders via X11 (native Wayland AWT is experimental, e.g. in JetBrains Runtime / project Wakefield). The apps under test are Swing. |
| **D-08** | The user's shell into the sandbox is **SSH over a Unix socket** (no TCP), with per-lamp generated ed25519 keys and a pinned host key. | User requirement (SSH). SSH over Unix socket preserves `--network=none`, works across a future VM boundary, and gives agent tools a standard login environment. |
| **D-09** | Session end is detected by the supervisor **relaying the primary SSH connection** itself (not by watching the terminal process). | Many terminal emulators (gnome-terminal, Ptyxis) hand the window to a server process and exit immediately, so their PID is meaningless. The relay knows exactly when the SSH connection closes. |
| **D-10** | The lamp is **two-layered**: `<lamp>/` holds config and state; `<lamp>/agent-lamp-<agentId>/` is the agent's world. | Keeps keys, logs, recordings, and — critically — the **network policy** out of the agent's reach. The agent cannot rewrite its own jail. |
| **D-11** | The agent dir is mounted as `/home/agent`. The container's **root filesystem is not exposed** as a host directory; it comes from a read-only image. | An immutable image keeps the environment reproducible and prevents the agent from persisting modifications to system tools. Anything that must persist lives in the home directory. Extra system packages are added through config and an image rebuild (§12.4). |
| **D-12** | **No direct network**; egress via an **HTTP(S) proxy implemented in the supervisor** (Java), controlled by a policy in `oillamp.toml`. Default: open web, internal ranges blocked. | Fail-closed (tools that ignore the proxy get no network), hostname-level policy and logging without TLS interception, pure-function policy evaluation, no root or nftables needed. |
| **D-13** | **Forwards** provide fixed `127.0.0.1:<port>` endpoints in the container for specific targets (the LLM). | Agent tools expect a plain base URL; forwards bypass the proxy and its policy explicitly and visibly. |
| **D-14** | Two container users: **`agent`** (uid 1000, mapped to the host user via `--userns=keep-id:uid=1000,gid=1000`) and **`lamp`** (uid 1001, mapped to a subordinate uid). | Files the agent creates in its home belong to the host user (easy to inspect and edit). Infra processes run as a user the agent cannot signal or impersonate, which protects monitoring and recording (NFR-06). |
| **D-15** | Recording with **wf-recorder** into **Matroska (.mkv)** as the infra user, into a state-dir mount the agent cannot see. | MKV stays playable if the recorder is killed abruptly. The agent cannot tamper with the recording. |
| **D-16** | **Supervisor stays in the foreground** of the terminal where `oillamp at` was typed and shows a live log. | Simple, transparent, and Ctrl+C is an obvious "stop everything". A `--detach` mode is a later extension. |
| **D-17** | Host prerequisites are **installed automatically via `sudo apt-get`**, with every command logged. | User requirement: out-of-the-box experience. Opt-out via `--no-install`. |
| **D-18** | **Base image: Debian 13 "trixie"**. JDK: Eclipse Temurin 25. Browser: Firefox ESR. Node.js: official LTS tarball. | Debian is close to Ubuntu (same tooling), and — unlike Ubuntu — ships Firefox as a normal `.deb` (Ubuntu's Firefox is a snap, which does not work in containers). |
| **D-19** | Java stack: **Java 25, Gradle (Groovy DSL), Jackson 2.x (JSON + TOML), Sprouts** (persistent collections), **Spock** scenarios, ArchUnit. *Amended in implementation (§36): Groovy DSL rather than Kotlin, and no picocli.* | User choice (Gradle, Jackson, Sprouts). Sprouts' `Tuple`/`Association`/`ValueSet` give immutable, structurally shared collections for large value types; Sprouts also underpins SwingTree, a natural fit for the future GUI. Groovy DSL because the test sources are Groovy anyway, so the build uses one language less. picocli was dropped because the CLI is nine subcommands and hand-rolled parsing keeps exit code 2 (usage) exactly where §28 wants it — one fewer dependency on the golden path. |
| **D-20** | Configuration format: **TOML**. | Human-friendly, supports comments, no YAML indentation/typing traps; parsed via Jackson's TOML module. |
| **D-21** | Distribution: **`jpackage` `.deb`** with a bundled `jlink` runtime. No GraalVM native image. | No JDK needed on the host; keeps the Swing path open (native-image and AWT/Swing don't mix well). |
| **D-22** | Terminal emulator: **auto-detected** from a built-in table of profiles, overridable in config. | Robustness across GNOME Terminal, Ptyxis, Console, Konsole, kitty, foot, Alacritty, WezTerm, xterm. |
| **D-23** | Clipboard default: **host → agent only**. | Pasting into the sandbox is useful. Copying *out* of it is where accidental leaks of agent-produced content into the host could happen. Configurable. |
| **D-24** | GPU: `gpu = "auto"` passes the DRM render node only if present, owned by a group the user belongs to, and driven by an open Mesa driver; otherwise software rendering. The entrypoint falls back to software rendering if the GPU renderer fails. | GPU is welcome but must never block the session. |
| **D-25** | Host-side socket paths go through a short directory under `$XDG_RUNTIME_DIR/oillamp/<agentId>/`. | Unix socket paths are limited to 107 bytes. Lamp paths can be long. |
| **D-26** | **One Gradle module, one package `dev.oillamp`, exactly five public types** (§22). Everything else is package-private, and every class states in its Javadoc *why* it is public or package-private. | The public API is the thing that can never be changed without breaking someone, so it is the thing to keep small. One package means the compiler — not a convention or a review — enforces the boundary: a package-private class simply cannot be referenced from outside. The tests live in package `oillamp`, outside `dev.oillamp`, so they physically cannot reach an internal and are forced to describe user-visible behaviour. Eight modules were tried first and bought nothing: the dependency rules they enforced are enforced here by one ArchUnit test, at a fraction of the build complexity. |
| **D-27** | **No Java RFB client and no `lamp-helper` module.** The in-container `lamp` helper is a shell script over `grim`, `wtype`, `wlrctl` and `slurp`, which §12.1 already installs. | Writing an RFB 3.8 client to take a screenshot, when the image already contains tools that do it natively, costs a protocol implementation, PNG encoding, an AppCDS tuning step and a build-time coupling that bakes a jar into the image context — in exchange for nothing the native tools do not already do. Absolute pointer coordinates, the original motivation, are available from `wlrctl pointer move`. An agent that needs something the script does not cover can call the underlying tools directly; the generated environment guide (§19.3) names them for exactly that reason. |

## 7. Out of scope for v1 (planned extensions)

- **Swing GUI** front-end (lamp list, start/stop, embedded viewer, live network log). The core is designed for it (NFR-08); SwingTree + Sprouts is the suggested UI stack.
- **Built-in Swing VNC viewer** replacing TigerVNC. This needs an RFB 3.8 client, which v1 deliberately does not have (D-27); it would be written then, for the viewer, not for the `lamp` helper.
- **Stronger isolation runtimes**: `--runtime=runsc` (gVisor) or `--runtime=krun` (libkrun microVM). Unix-socket mounts behave differently across a VM boundary, so this needs its own design pass.
- **Accessibility-tree access** (AT-SPI) for the agent, e.g. `lamp a11y tree`, using `java-atk-wrapper` for Swing apps.
- **MCP server** exposing the desktop tools (screenshot/click/type) to agent tools natively.
- **`--detach` mode** and a background service.
- Non-APT host distributions (Fedora/dnf, Arch/pacman).
- Multiple virtual monitors, audio.
- TLS interception or content inspection in the proxy (deliberately not planned).

---

# Part II — System Architecture

## 8. Overview

```
HOST (Ubuntu, user's Wayland session)
│
│  terminal #1 (where the user typed `oillamp at`)
│  └── oillamp supervisor (Java) ──────────────────────────────────────────┐
│        • lock + session state machine                                  │
│        • SSH relay  (primary + extra shells)                           │
│        • HTTP(S) egress proxy + policy        ← oillamp.toml           │
│        • forwards (e.g. LLM)                                           │
│        • podman control, logs, recordings retention                    │
│                                                                        │
│  terminal window (spawned) ── ssh ─ProxyCommand socat─▶ run/ssh-primary.sock ─┐
│  viewer window (spawned)   ── vncviewer ─────────────▶ sockets/infra/vnc.sock  │
│                                                                              │
│  <lamp>/                                                                     │
│   ├── oillamp.toml            (config: policy, display, recording, llm, …)   │
│   ├── .oillamp/               (state: ids, keys, logs, recordings, sockets)  │
│   └── agent-lamp-<agentId>/   ──mounted as──▶ /home/agent                    │
│                                                                              │
└──────────── rootless podman, --network=none, read-only image ────────────────┘
   CONTAINER  oillamp-<agentId>
   PID 1 catatonit → /usr/local/lib/oillamp/entrypoint (root in userns, few caps)
     as `lamp` (uid 1001, subuid):   sway (headless, HEADLESS-1) + Xwayland
                                     wayvnc  → /oillamp/sockets/infra/vnc.sock
                                     wf-recorder → /oillamp/recordings/*.mkv
                                     socat 127.0.0.1:3128 → /oillamp/sockets/host/proxy.sock
                                     socat 127.0.0.1:<p>  → /oillamp/sockets/host/fwd-<name>.sock
     as `agent` (uid 1000 = host user):
                                     socat /oillamp/sockets/agent/ssh.sock → sshd -i
                                     dbus-daemon (session bus)
                                     login shells, opencode / pi, Swing apps, Firefox
```

Data flows:

- **User shell:** terminal → `ssh` → `ProxyCommand socat` → supervisor's host-only socket `run/ssh-primary.sock` → supervisor relay → `sockets/agent/ssh.sock` → in-container `socat` → `sshd -i` (as `agent`).
- **Viewing:** `vncviewer` → `sockets/infra/vnc.sock` → wayvnc → sway.
- **Web:** agent process → `HTTP(S)_PROXY=127.0.0.1:3128` → in-container `socat` → `sockets/host/proxy.sock` → supervisor proxy → policy → internet.
- **LLM:** agent tool → `127.0.0.1:<port>` → in-container `socat` → `sockets/host/fwd-llm.sock` → supervisor → TCP to the configured target.

## 9. Lamp directory layout

### 9.1 Tree

```
<lamp>/
├── oillamp.toml                      lamp config (user-editable; agent cannot see it)
├── README.txt                        short human explanation of this directory (written on init)
├── .oillamp/                         state dir, mode 0700
│   ├── lamp.json                     identity + schema version (§10.1)
│   ├── lock                          lock file held by the supervisor (FileChannel lock)
│   ├── session.json                  present while a session runs (§10.3)
│   ├── keys/                         mode 0700
│   │   ├── client_ed25519(.pub)      user's SSH key for this lamp
│   │   └── host_ed25519(.pub)        sandbox SSH host key (pinned)
│   ├── ssh_config                    generated OpenSSH client config for this lamp
│   ├── known_hosts                   pinned host key
│   ├── session/                      rendered per session; mounted READ-ONLY at /oillamp/session
│   │   ├── runtime.env               environment for entrypoint and login shells
│   │   ├── authorized_keys           client public key
│   │   ├── ssh_host_ed25519_key      copy of host key (0600) for sshd
│   │   └── agent-guide.md            generated guide for the agent (§19.3)
│   ├── image/                        rendered build context + hash of the last built image
│   ├── sockets/                      mounted READ-WRITE at /oillamp/sockets
│   │   ├── host/     (owner: host user)      sockets created by the supervisor: proxy.sock, fwd-*.sock
│   │   ├── infra/    (owner: container lamp) vnc.sock, ready.json
│   │   └── agent/    (owner: host user)      ssh.sock
│   ├── recordings/                   (owner: container lamp) mounted READ-WRITE at /oillamp/recordings (infra only)
│   └── logs/
│       ├── oillamp-<session>.log     supervisor log (human readable, full detail)
│       ├── container-<session>.log   podman container stdout/stderr (k8s-file log driver)
│       ├── network-<session>.jsonl   one JSON object per proxied/forwarded connection
│       └── install-<timestamp>.log   host package installation transcripts
└── agent-lamp-<agentId>/             agent's persistent world; mounted READ-WRITE at /home/agent
    ├── workspace/                    put repos here (terminal opens here)
    ├── libs/                         put native libraries here (on LD_LIBRARY_PATH)
    ├── .config/ .local/ .cache/ …    agent tool configs and caches (persist)
    └── …

$XDG_RUNTIME_DIR/oillamp/<agentId>/   short-path runtime dir (D-25), mode 0700
├── sockets -> <lamp>/.oillamp/sockets   symlink; all host-side socket paths go through it
└── run/                                  host-only sockets, never mounted into the container
    ├── control.sock                      supervisor control channel (JSON lines, §26.6)
    ├── ssh-primary.sock                  accepts exactly one connection: the terminal window
    └── ssh.sock                          extra shells (`oillamp shell`)
```

### 9.2 Ownership and permission rules

| Path | Host owner | Mode | Visible in container | Why |
|---|---|---|---|---|
| `<lamp>` | user | 0755 (unchanged if existing) | no | |
| `oillamp.toml` | user | 0600 | **no** | agent must not change its own policy (D-10) |
| `.oillamp/` | user | **0700** | partially (below) | blocks other host users from all sockets inside |
| `.oillamp/keys/*` | user | 0600 | no | |
| `.oillamp/session/` | user | 0755, files 0644, host key copy 0600 | ro at `/oillamp/session` | host key copy is readable by container uid 1000 = user |
| `.oillamp/sockets/host/` | user | 0755; sockets chmod 0666 | rw | `lamp` (a subuid, "other" on host) must connect; safe because the parent `.oillamp` is 0700 on the host |
| `.oillamp/sockets/infra/` | subuid of container uid 1001 | 0755 | rw | wayvnc (as `lamp`) creates `vnc.sock` here with mode 0777; host user connects |
| `.oillamp/sockets/agent/` | user | 0755 | rw | ssh listener runs as `agent` |
| `.oillamp/recordings/` | subuid of container uid 1001 | 0755, files 0644 | rw mount, but only `lamp` can write | tamper protection (NFR-06); the host user reads the files as "other" |
| `agent-lamp-<agentId>/` | user | 0755 | rw at `/home/agent` | |

**Chowning to the container's `lamp` uid** is done with `podman unshare chown 1001:1001 <path>` (this maps container uid 1001 to the right subuid automatically). Deleting recordings (retention) likewise uses `podman unshare rm -f <files>`.

> Note on agent visibility of `/oillamp/recordings`: both container users share one mount namespace, so the agent can *list and read* the recordings of its own screen. That is harmless. It cannot modify or delete them: they belong to uid 1001, and the agent has no capabilities. Making the directory unreadable for the agent (0700) would also make it unreadable for the host user without `podman unshare`, so this is deliberately not done.

### 9.3 Why two layers (D-10)

The user's `<lamp>` path is the unit the user thinks in ("my sandbox for feature X"). The agent only ever sees `agent-lamp-<agentId>/`. Everything that controls or observes the agent — policy, keys, logs, recordings — sits next to it, never inside it. The `agentId` suffix makes the agent dir recognizable and guarantees that a copied/moved agent dir is not mistaken for another lamp's.

## 10. Identity, state, and lifecycle

### 10.1 `lamp.json`

```json
{
  "schemaVersion": 1,
  "agentId": "k3v9x2ab",
  "createdAt": "2026-09-22T14:15:03Z",
  "createdBy": "oillamp 0.1.0",
  "lastSessionAt": "2026-09-22T16:40:10Z"
}
```

- `agentId`: 8 characters from the RFC 4648 base32 lowercase alphabet (`a-z2-7`), from `SecureRandom`. Generated once on init, never changes.
- `schemaVersion` enables forward migration: oillamp MUST refuse a newer schema than it knows (`OIL-LAMP-004`, "upgrade oillamp") and MUST migrate older ones with a logged, pure migration function.

### 10.2 Derived names (pure function of `agentId`)

| Thing | Value |
|---|---|
| Container name | `oillamp-<agentId>` |
| Container hostname | `lamp-<agentId>` |
| Container labels | `oillamp.agent-id=<agentId>`, `oillamp.lamp-path=<absolute lamp path>`, `oillamp.version=<version>` |
| Agent dir | `<lamp>/agent-lamp-<agentId>` |
| Runtime dir | `$XDG_RUNTIME_DIR/oillamp/<agentId>` |
| SSH host alias | `lamp-<agentId>` |

### 10.3 `session.json` and session IDs

`<session>` = UTC timestamp `yyyyMMdd-HHmmss` of the session start (unique per lamp because sessions don't overlap). While a session runs, `session.json` records: session id, supervisor PID, start time, container name, image tag, runtime dir, and the socket paths. It is written after the lock is acquired and deleted on clean shutdown. It is informational; **the lock file, not this file, is the source of truth** for "is a session running".

### 10.4 Locking

The supervisor acquires an exclusive `FileChannel.tryLock()` on `<lamp>/.oillamp/lock` and holds it for the whole session. The OS releases it automatically if the process dies, so a crashed supervisor never blocks the next run.

- Lock not acquired → `OIL-LOCK-001` with details from `session.json`.
- Lock acquired but `session.json` exists or a container labeled `oillamp.agent-id=<agentId>` exists → the previous session crashed. Clean up (`podman rm -f`, delete stale `session.json`, remove stale sockets and runtime dir) and report `OIL-LOCK-002` as a *warning*, then continue.

### 10.5 Startup phases

Startup is split into phases. Each phase is: **probe facts (effectful) → plan (pure) → execute plan (effectful)**. Re-probing between phases is required because earlier phases change the host.

| Phase | Probes | Plans/does |
|---|---|---|
| **A. Host** | OS release, graphical session, packages, subuid/subgid, podman version/info, userns functional test, terminal emulators, viewer, GPU nodes | install packages, add subuid/subgid ranges, `podman system migrate`, re-probe, verify |
| **B. Lamp** | lamp dir state, `lamp.json`, config files | create skeleton or load/migrate, validate config, acquire lock, stale cleanup, generate keys if missing, render `session/`, `ssh_config`, runtime dir + symlink, chown infra dirs, recording retention |
| **C. Image** | image tag for current build-input hash exists? | render build context, `podman build` if needed |
| **D. Session** | — | start proxy/forward listeners, `podman run`, wait for `ready.json`, start SSH relays, launch viewer, launch terminal, then enter the supervision loop |

### 10.6 Session state machine

The supervision loop is driven by a pure state machine (§25.5):

```
Starting ──ContainerReady──▶ AwaitingTerminal ──PrimaryConnected──▶ Running
   │                              │                                   │
   │ ContainerExited/Timeout      │ PrimaryTimeout(60s)/ContainerExited│ PrimaryDisconnected / Interrupt /
   ▼                              ▼                                   ▼ StopRequested / ContainerExited
                              ShuttingDown(reason) ──ShutdownComplete──▶ Stopped
```

- `PrimaryTimeout`: the terminal window never connected (e.g. the terminal emulator failed to start). Reported as `OIL-TERM-002` with the terminal command, its exit code and stderr.
- A `PrimaryDisconnected` always shuts down, even if extra shells (`oillamp shell`) are still connected; those are closed.
- The viewer's lifetime is independent. The viewer exiting does nothing (the user can re-open it with `oillamp view`), except that a viewer exit within 3 seconds with a non-zero code is reported as a warning (`OIL-VIEW-001`).

### 10.7 Shutdown sequence

1. Stop accepting new relay/proxy connections; close the primary and extra SSH relays.
2. `podman stop --time 15 oillamp-<agentId>` (the entrypoint finalizes the recording on SIGTERM, §13.5).
3. `podman rm -f oillamp-<agentId>` (also if stop failed).
4. Close proxy and forward listeners; delete host-created sockets; delete the runtime dir.
5. Flush and close the network log; delete `session.json`; update `lamp.json.lastSessionAt`.
6. Print the session summary (duration, recording file and size, proxy stats: allowed/denied counts, top 5 hosts, warnings).
7. Release the lock; exit with the session's exit code (§27.5).

Each step runs even if an earlier one failed; failures are collected and reported together. A JVM shutdown hook triggers the same sequence on SIGTERM/SIGHUP/SIGINT; the sequence is idempotent.

## 11. Host prerequisites and automatic installation

### 11.1 Required host packages (APT)

| Package | Needed for |
|---|---|
| `podman` | container engine (≥ 4.9; Ubuntu 24.04 ships 4.9.x) |
| `uidmap` | `newuidmap`/`newgidmap` for rootless Podman |
| `catatonit` | init process for `podman run --init` |
| `socat` | SSH `ProxyCommand` to a Unix socket |
| `openssh-client` | `ssh`, `ssh-keygen` |
| `tigervnc-viewer` | the viewer window (v1) |

The package set is data (`HostRequirements` record), keyed by distro family, so dnf/pacman variants can be added later.

### 11.2 Checks and fixes

| Check | How | Fix (automatic unless `--no-install`) | Problem code |
|---|---|---|---|
| Linux | `os.name` | — (fatal) | `OIL-HOST-001` |
| APT-based distro | `/etc/os-release` `ID`/`ID_LIKE` contains `debian` or `ubuntu` | — (fatal; print required packages) | `OIL-HOST-002` |
| Graphical session | `WAYLAND_DISPLAY` or `DISPLAY` set | — (fatal for `at`/`view`/`shell`; `doctor` still runs) | `OIL-HOST-003` |
| Packages installed | `dpkg-query -W -f='${Status}' <pkg>` | `sudo apt-get update` then `sudo apt-get install -y --no-install-recommends <missing…>` | `OIL-PKG-001` |
| sudo usable | `sudo -n true` first; if a password is needed and stdin is a TTY, run sudo interactively; else fail | — | `OIL-PKG-002` |
| subuid/subgid | parse `/etc/subuid` and `/etc/subgid` for the user name (and uid) with ≥ 65536 ids | pick the first free 65536-aligned range ≥ 100000 not overlapping any existing entry (pure function), `sudo usermod --add-subuids A-B --add-subgids A-B <user>`, then `podman system migrate` | `OIL-HOST-010` |
| Podman version | `podman version --format json` | — (report required version) | `OIL-PODMAN-001` |
| Podman rootless info | `podman info --format json`: `host.security.rootless == true`, OCI runtime, storage driver | — | `OIL-PODMAN-002` |
| Userns functional | `podman unshare true` | see below | `OIL-PODMAN-003` |
| AppArmor userns restriction | `/proc/sys/kernel/apparmor_restrict_unprivileged_userns == 1` **and** the functional test fails | report cause and remedies (below) | `OIL-PODMAN-004` |
| Socket filesystem | `Files.getFileStore(<lamp>).type()` not in {`nfs`, `nfs4`, `cifs`, `smb3`, `vfat`, `exfat`, `fuse.sshfs`} | — (fatal, suggest a local path) | `OIL-LAMP-005` |
| GPU (only if `gpu != "off"`) | `/dev/dri/renderD*`, group membership, driver name from `/sys/class/drm/renderD*/device/driver` symlink | — (fall back to software) | `OIL-GPU-001` (info) |
| Terminal emulator | profile table, `PATH` lookup | — (fatal; list supported terminals and the config key) | `OIL-TERM-001` |

**AppArmor remedy text (`OIL-PODMAN-004`)**: Ubuntu ≥ 23.10 restricts unprivileged user namespaces to programs with an AppArmor profile that allows them. Ubuntu's AppArmor package ships such profiles for podman and related tools (**⚠ VERIFY** exact profile names on 24.04 and 26.04: expected under `/etc/apparmor.d/`, e.g. `podman`, `crun`, `buildah`). Remedies to print, in this order: (1) ensure the Ubuntu-packaged podman is used (not a binary in another path that the profile doesn't match); (2) reload profiles `sudo systemctl reload apparmor`; (3) as a last resort, and only with explicit user consent, `sudo sysctl kernel.apparmor_restrict_unprivileged_userns=0` (explain the security trade-off). oillamp MUST NOT change this sysctl automatically.

### 11.3 Install UX

Before installing, oillamp prints a table of missing items with a one-line reason each, then runs the commands with inherited stdio (so `sudo` can prompt and APT output is visible), and additionally captures output into `logs/install-<timestamp>.log` (tee). Every command line is echoed first, prefixed with `$`. After installation, Phase A re-probes and must pass; otherwise the Problem includes the install log path.

## 12. Container image

### 12.1 Base and contents

Base: `docker.io/library/debian:trixie` (configurable `image.base`, for internal registry mirrors).

| Group | Packages / sources |
|---|---|
| Core | `ca-certificates bash coreutils util-linux procps less file locales tzdata` (locale `en_US.UTF-8` generated) |
| Desktop | `sway xwayland wayvnc wf-recorder grim slurp wtype wlrctl foot dbus at-spi2-core xdg-utils` (**⚠ VERIFY** `wlrctl` availability in trixie; fallback: `wtype` covers keyboard and `grim`+`slurp` cover capture, so only pointer control is lost — see §19.4) |
| Graphics | `mesa-utils libgl1-mesa-dri libegl1 libgles2 mesa-vulkan-drivers` |
| Fonts/themes | `fonts-dejavu fonts-liberation2 fonts-noto-core adwaita-icon-theme` |
| SSH & bridges | `openssh-server socat` |
| Dev tools | `git curl wget unzip zip jq ripgrep fd-find build-essential cmake pkg-config gdb strace python3 python3-venv python3-pip` |
| Java | Eclipse Temurin 25 JDK from the Adoptium APT repository (`temurin-25-jdk`), plus `libatk-wrapper-java` (future a11y) |
| Browser | `firefox-esr` with an enterprise policy file configuring the proxy (§18.6) |
| Node.js | official Node.js 24 LTS linux-x64 tarball into `/opt/node` (version configurable), symlinked into `/usr/local/bin` |
| Agent tools | `npm install -g` of each configured tool: OpenCode (`opencode-ai`), pi (`@mariozechner/pi-coding-agent`) (**⚠ VERIFY** package names and binaries `opencode`, `pi`) |
| oillamp payload | `/usr/local/lib/oillamp/entrypoint`, `/etc/oillamp/sway/config`, `/etc/oillamp/sshd_config`, `/etc/profile.d/oillamp.sh`, `/etc/ssh/ssh_config.d/50-oillamp-proxy.conf`, `/usr/local/bin/lamp` (+ its jar) |
| Extra | `image.extra_apt_packages` from config |

### 12.2 Users and filesystem

- `agent`: uid 1000, gid 1000, home `/home/agent`, shell `/bin/bash`. Not in any privileged group. No sudo installed in the image at all.
- `lamp`: uid 1001, gid 1001, home `/var/lib/lamp`, shell `/usr/sbin/nologin`.
- Pre-created mount points (the root filesystem is read-only at runtime): `/oillamp/session`, `/oillamp/sockets`, `/oillamp/recordings`, `/home/agent`.
- The image MUST NOT contain setuid/setgid binaries usable by `agent` (strip with `find / -xdev -perm /6000 -type f -exec chmod ug-s {} +` at build end); `no-new-privileges` makes them inert anyway, this is defense in depth.

### 12.3 Build inputs, hashing, tags

The build context is extracted from the oillamp jar's resources (`/image/**`) into `<lamp>/.oillamp/image/context/`. The **build-input hash** is SHA-256 over: every context file's relative path and bytes (sorted by path) + the sorted `--build-arg` key/value pairs (base image, Node version, JDK package, agent tools with versions, extra packages). Tag: `localhost/oillamp/sandbox:<first 16 hex chars>`. The image is shared by all lamps with identical inputs.

- Image exists (`podman image exists <tag>`) → skip build.
- Otherwise `podman build --pull=missing --tag <tag> --label oillamp.version=<v> --build-arg … <context>` with streamed output (prefixed `[image]` on the console; full output in the session log). Build failure → `OIL-IMAGE-001` with the last 40 lines and the log path.
- `oillamp image rebuild [<dir>]` forces `--pull=always --no-cache`. `oillamp image prune` removes `localhost/oillamp/sandbox:*` images not used by any existing lamp's last session and older than the newest.

### 12.4 Extending the image

The image is immutable at runtime (D-11). To add system packages, the user edits `image.extra_apt_packages` in `oillamp.toml`; the next `oillamp at` rebuilds because the hash changed. Language-level dependencies (Maven, npm, pip `--user`, venvs) install into the persistent home through the proxy.

## 13. Container runtime

### 13.1 `podman run` invocation

Rendered by a pure function from the `ContainerSpec` record. Canonical form (values in `<>` come from config/state):

```
podman run --detach
  --name oillamp-<agentId> --hostname lamp-<agentId>
  --label oillamp.agent-id=<agentId> --label oillamp.lamp-path=<lamp> --label oillamp.version=<v>
  --init
  --userns=keep-id:uid=1000,gid=1000
  --network=none
  --read-only
  --cap-drop=all --cap-add=CHOWN,FOWNER,SETUID,SETGID,SETPCAP,KILL
  --security-opt=no-new-privileges
  --memory=<limits.memory> --cpus=<limits.cpus> --pids-limit=<limits.pids>
  --shm-size=1g
  --log-driver=k8s-file --log-opt path=<lamp>/.oillamp/logs/container-<session>.log
  --stop-timeout=15
  --volume <lamp>/agent-lamp-<agentId>:/home/agent:rw
  --volume <lamp>/.oillamp/session:/oillamp/session:ro
  --volume <lamp>/.oillamp/sockets:/oillamp/sockets:rw
  --volume <lamp>/.oillamp/recordings:/oillamp/recordings:rw
  [--device /dev/dri/renderD128 --group-add keep-groups]          (GPU mode only)
  --env OILLAMP_SESSION=<session>
  <image tag>
```

Notes:

- `--read-only` makes Podman mount tmpfs on `/run`, `/tmp`, `/var/tmp`, `/dev/shm` automatically (`--read-only-tmpfs` default true).
- No `:Z`/`:z` relabeling: Ubuntu uses AppArmor, not SELinux. On an SELinux host, the renderer MUST add `:Z` to the agent dir and session mounts and `:z` to sockets — keep this as a `HostFacts.selinuxEnabled` switch.
- `--group-add keep-groups` requires the `crun` runtime; if the runtime is not crun, GPU mode is unavailable (`OIL-GPU-002`, info).
- The capabilities are needed only by the entrypoint (running as container root, which is an unprivileged subuid on the host) to create runtime dirs, chmod the Wayland socket, switch users, and signal children on shutdown. All long-running processes run as `lamp` or `agent` and lose all capabilities on the user switch (§13.2).
- Resource defaults: memory `16g`, cpus = number of host CPUs minus 1 (min 1), pids `8192`.

### 13.2 Privilege dropping

All child processes are started via:

```
setpriv --reuid=<user> --regid=<user> --init-groups \
        --inh-caps=-all --ambient-caps=-all --bounding-set=-all -- <command…>
```

In GPU mode, `--init-groups` is replaced by `--keep-groups` so the host's render group (kept via `--group-add keep-groups`, visible as an unmapped group in the container) survives; the Wayland socket sharing does not rely on groups (§14.2), so no group membership is lost that matters. (**⚠ VERIFY** that `--keep-groups` combined with `--regid` behaves as expected under keep-groups; fallback: GPU mode for `lamp` only, software GL for agent apps.)

### 13.3 Mount summary (the complete list of host paths the container can see)

| Host | Container | Mode | Purpose |
|---|---|---|---|
| `<lamp>/agent-lamp-<agentId>` | `/home/agent` | rw | agent's persistent world |
| `<lamp>/.oillamp/session` | `/oillamp/session` | ro | runtime env, ssh keys for sshd, agent guide |
| `<lamp>/.oillamp/sockets` | `/oillamp/sockets` | rw | Unix sockets between host and container |
| `<lamp>/.oillamp/recordings` | `/oillamp/recordings` | rw (writable only by uid 1001) | screen recordings |
| `/dev/dri/renderD*` (optional) | same | device | GPU |

Nothing else. In particular: **no** host `$XDG_RUNTIME_DIR`, Wayland socket, X11 socket, D-Bus socket, SSH agent socket, home directory, or `/etc` files.

### 13.4 Entrypoint behaviour

`/usr/local/lib/oillamp/entrypoint` is a Bash script (`set -Eeuo pipefail`), run as container root under `catatonit`. It MUST:

1. Source `/oillamp/session/runtime.env` (variables in §20.3 / Appendix B).
2. Create `/run/lamp` (owner `lamp`, **0711**), `/run/lamp/private` (owner `lamp`, 0700), `/run/agent` (owner `agent`, 0700).
3. Start **sway** as `lamp` with `umask 077`, env `XDG_RUNTIME_DIR=/run/lamp WLR_BACKENDS=headless WLR_LIBINPUT_NO_DEVICES=1 WLR_RENDERER=<gles2|pixman> WLR_HEADLESS_OUTPUTS=1`, config `/etc/oillamp/sway/config`, stdout/stderr prefixed `[sway]`.
4. Wait (≤ 20 s) for `/run/lamp/wayland-1`. If sway exits and the renderer was `gles2`, restart once with `pixman` and record `"renderer":"pixman","gpuFallback":true`. Otherwise exit with code 70 and a clear message.
5. `chmod 0666 /run/lamp/wayland-1` (sway's IPC socket stays 0700, so the agent can never run `swaymsg exec` as `lamp`).
6. Start **wayvnc** as `lamp` with `umask 000`: `wayvnc --unix-socket --socket=/run/lamp/private/wayvncctl --render-cursor --max-fps=<fps> --output=HEADLESS-1 /oillamp/sockets/infra/vnc.sock`. The control socket is in the private dir so the agent cannot use `wayvncctl` to disconnect viewers or stop wayvnc.
7. If recording is enabled, start **wf-recorder** as `lamp` with `umask 022`: `wf-recorder --output=HEADLESS-1 --codec=<codec> --file=/oillamp/recordings/<session>.mkv <extra args>` (**⚠ VERIFY** flag names for frame-rate limiting and codec params in the packaged wf-recorder version).
8. Start the **bridges** as `lamp`: `socat TCP-LISTEN:3128,bind=127.0.0.1,reuseaddr,fork UNIX-CONNECT:/oillamp/sockets/host/proxy.sock` and one per forward.
9. Start as `agent`: a D-Bus session bus (`dbus-daemon --session --address=unix:path=/run/agent/bus --nofork --nopidfile`), and the **SSH listener** `socat UNIX-LISTEN:/oillamp/sockets/agent/ssh.sock,fork,unlink-early,mode=600 EXEC:"/usr/sbin/sshd -i -f /etc/oillamp/sshd_config"`.
10. Write `/oillamp/sockets/infra/ready.json` (as `lamp`): `{"session":…, "renderer":…, "gpuFallback":…, "output":"HEADLESS-1", "size":"1920x1080", "versions":{…}}`.
11. Supervise: `wait -n` on the infra PIDs (sway, wayvnc, recorder, bridges). If any exits, log which one and its code, then run the shutdown routine and exit 70. The agent-side processes are *not* critical: if they die, log a warning (the agent may have killed its own SSH listener; existing sessions stay alive).
12. On SIGTERM/SIGINT (trap): send SIGINT to wf-recorder and wait ≤ 10 s so it finalizes the file, then SIGTERM everything else, wait ≤ 3 s, exit 0.

All entrypoint output goes to the container log (`logs/container-<session>.log`), prefixed per process.

### 13.5 Readiness

The supervisor polls every 200 ms for `sockets/infra/ready.json` (timeout configurable, default 45 s). On each poll it also checks `podman container inspect --format json` state; if the container exited, readiness fails immediately with `OIL-CONTAINER-002`, including the exit code and the last 60 lines of the container log. After `ready.json` appears, the supervisor additionally verifies it can connect to `infra/vnc.sock` and `agent/ssh.sock` (`OIL-CONTAINER-003` otherwise).

## 14. Desktop stack

### 14.1 sway configuration (essentials)

- One headless output `HEADLESS-1`, mode from config (`display.width`×`display.height`, custom mode), `scale` from config.
- A plain background colour; no bar; no idle/lock; no screen blanking.
- `xwayland enable`.
- `input * accel_profile flat` (predictable pointer motion for automation).
- `focus_follows_mouse no`, `default_border normal`, titles on (window titles visible in screenshots help the agent).
- `floating_modifier` default; a rule to make dialogs float (`for_window [window_type="dialog"] floating enable`) so Swing dialogs behave like on a normal desktop.
- Keybindings: none that can exit sway or run commands (the agent's keyboard input must not be able to `exec` as `lamp`). The config MUST NOT contain any `bindsym … exec …` or `exit` bindings. Only focus/move/layout bindings are allowed.

See Appendix C for a template.

### 14.2 Sharing the display with the agent user

- `WAYLAND_DISPLAY=/run/lamp/wayland-1` (absolute path; libwayland clients accept absolute socket paths — **⚠ VERIFY** with the trixie libwayland; fallback: symlink `/run/agent/wayland-1 → /run/lamp/wayland-1` and set `WAYLAND_DISPLAY=wayland-1`).
- `DISPLAY=:0` for X11 clients via Xwayland (socket `/tmp/.X11-unix/X0`, world-connectable by default).
- `XDG_RUNTIME_DIR=/run/agent` for agent processes.
- `XDG_SESSION_TYPE=wayland`, `GDK_BACKEND=wayland,x11`, `QT_QPA_PLATFORM=wayland;xcb`, `MOZ_ENABLE_WAYLAND=1`.
- Java/Swing: `_JAVA_AWT_WM_NONREPARENTING=1` (required for correct Swing behaviour under tiling compositors via Xwayland). Future: allow a JetBrains Runtime with `-Dawt.toolkit.name=WLToolkit` as an option.

### 14.3 GPU

`gpu = "auto" | "on" | "off"` (default `auto`).

- `auto`: use GPU mode iff a `/dev/dri/renderD*` node exists, the user is in the node's owning group, the driver (from sysfs) is one of `i915`, `xe`, `amdgpu`, `radeon`, `nouveau`, `virtio_gpu`, and the OCI runtime is crun. Otherwise software.
- `on`: like `auto` but failures to meet the conditions are errors (`OIL-GPU-003`).
- `off`: `WLR_RENDERER=pixman`, `LIBGL_ALWAYS_SOFTWARE=1`.
- The decision and the reason are printed in the startup log and written to `ready.json`.

## 15. Monitoring (viewer)

- v1 viewer command (profile `tigervnc`):
  `vncviewer -Shared=1 -AcceptClipboard=<0|1> -SendClipboard=<0|1> -SendPrimary=0 -RemoteResize=0 -geometry <w>x<h> $XDG_RUNTIME_DIR/oillamp/<agentId>/sockets/infra/vnc.sock`
  (**⚠ VERIFY** option spellings for the packaged TigerVNC version.)
- `viewer.clipboard`: `"to-agent"` (default: `SendClipboard=1 AcceptClipboard=0`), `"both"`, `"none"`.
- `viewer.view_only = false` by default (the user can take over). `oillamp view <dir> --view-only` passes `-ViewOnly=1`.
- The viewer is launched detached (`ProcessBuilder`, stdio redirected to the session log). Its PID is recorded but not used for lifecycle.
- The window title is set by the server's desktop name. Configure wayvnc's desktop name as `oillamp · <lamp dir name>` if the packaged version supports it (**⚠ VERIFY**; otherwise ignore).
- Wayland note: clients cannot position their windows; oillamp does not try to arrange the two windows.

## 16. Recording

| Key | Default | Meaning |
|---|---|---|
| `recording.enabled` | `false` | Off by default: a continuous screen recording of everything the agent does is an audit trail a user opts into, not a thing to switch on for them. Turn it on per lamp. |
| `recording.codec` | `"libx264"` | passed to wf-recorder; `h264_vaapi` possible in GPU mode |
| `recording.crf` | `30` | quality (x264 CRF) |
| `recording.max_fps` | `10` | caps CPU cost; screen content rarely needs more |
| `recording.max_age_days` | `14` | retention |
| `recording.max_total_gb` | `20` | retention (oldest deleted first) |

- File: `recordings/<session>.mkv`.
- Retention runs in Phase B (before the new recording starts) via a pure function `RetentionPolicy.select(Tuple<RecordingFile>, policy, now) → Tuple<RecordingFile>` and `podman unshare rm -f`.
- wf-recorder only emits frames on damage, so idle periods cost almost nothing — the default is about consent, not cost.
- The recorder runs as `lamp`, so the agent can neither stop it nor read what it wrote.
- `oillamp recordings <dir>` lists recordings (time, duration if cheaply available, size) and `--open <session>` opens one with `xdg-open`.

## 17. Terminal and SSH

### 17.1 Keys and configs (generated in Phase B if missing)

- `ssh-keygen -t ed25519 -N "" -C "oillamp-<agentId>-client" -f .oillamp/keys/client_ed25519`
- `ssh-keygen -t ed25519 -N "" -C "oillamp-<agentId>-host" -f .oillamp/keys/host_ed25519`
- `known_hosts`: `lamp-<agentId> <host public key>`
- `.oillamp/ssh_config` (Appendix D):

```
Host lamp-<agentId>
  User agent
  HostName lamp-<agentId>
  IdentityFile <lamp>/.oillamp/keys/client_ed25519
  IdentitiesOnly yes
  UserKnownHostsFile <lamp>/.oillamp/known_hosts
  StrictHostKeyChecking yes
  RequestTTY yes
  ServerAliveInterval 15
  ForwardAgent no
  ForwardX11 no
  ClearAllForwardings yes
  LogLevel ERROR
```

The `ProxyCommand` target differs per use: the terminal window uses `$XDG_RUNTIME_DIR/oillamp/<agentId>/run/ssh-primary.sock`; `oillamp shell` uses `…/run/ssh.sock`. Therefore oillamp passes `-o ProxyCommand=…` on the command line and keeps the shared settings in the file. The remote command is `cd ~/workspace && exec bash -l` (`RemoteCommand` via `-t` + command argument).

### 17.2 sshd in the container

`sshd -i` (inetd mode) runs as `agent` per connection (non-root sshd can only log in as its own user, which is exactly what we want). Config `/etc/oillamp/sshd_config` (Appendix D): host key `/oillamp/session/ssh_host_ed25519_key`, `AuthorizedKeysFile /oillamp/session/authorized_keys`, pubkey only, `AllowUsers agent`, `UsePAM no`, `PermitUserEnvironment no`, no forwarding of any kind, `PrintMotd no`, `PidFile none`, `Subsystem sftp internal-sftp`.
**⚠ VERIFY** non-root `sshd -i` with OpenSSH ≥ 9.8 (split `sshd-session` binary). Fallback: `dropbear -i` with equivalent options.

### 17.3 The relay (D-09)

- The supervisor listens on `run/ssh-primary.sock` (accepts exactly one connection per session; later connections are rejected with an immediate close and a log warning) and on `run/ssh.sock` (unlimited, for `oillamp shell`).
- For each accepted connection: connect to `sockets/agent/ssh.sock`, then copy bytes in both directions with two virtual threads. When either side closes, close both and emit `PrimaryDisconnected` (primary) or `ShellDisconnected`.
- Because these two sockets live in the host-only runtime dir, the agent cannot connect to them and cannot "steal" the primary slot.

### 17.4 Terminal profiles (D-22)

Each profile: executable name, how to pass a title, how to run a command. The launcher runs `<terminal> <title args> <exec args> ssh -F <lamp>/.oillamp/ssh_config -o ProxyCommand=… -t lamp-<agentId> 'cd ~/workspace && exec bash -l'`.

| Profile | Detect | Argument template (**⚠ VERIFY** each in a smoke test) |
|---|---|---|
| `ptyxis` | `ptyxis` | `ptyxis --new-window -- <cmd…>` |
| `gnome-terminal` | `gnome-terminal` | `gnome-terminal --title=<t> -- <cmd…>` |
| `kgx` (GNOME Console) | `kgx` | `kgx --title=<t> -- <cmd…>` |
| `konsole` | `konsole` | `konsole -p tabtitle=<t> -e <cmd…>` |
| `kitty` | `kitty` | `kitty --title <t> <cmd…>` |
| `foot` | `foot` | `foot --title=<t> <cmd…>` |
| `alacritty` | `alacritty` | `alacritty --title <t> -e <cmd…>` |
| `wezterm` | `wezterm` | `wezterm start -- <cmd…>` |
| `xterm` | `xterm` | `xterm -T <t> -e <cmd…>` |

Detection order: `terminal.profile` from config → the default terminal of the desktop (GNOME: `ptyxis` if installed, else `gnome-terminal`, else `kgx`; KDE: `konsole`) → the rest of the table in order. A custom terminal can be configured as `terminal.command = ["myterm", "--exec", "{cmd}"]` where `{cmd}` expands to the argv and `{title}` to the title.

Title: `oillamp · <lamp dir name>`.

## 18. Network: egress proxy, policy, forwards

### 18.1 Principles

- The container has `--network=none`: only a loopback interface. There is no route, no DNS.
- Two channels exist, both terminating in the supervisor on the host:
  1. the **egress proxy** at `127.0.0.1:3128` inside the container (HTTP/1.1 forward proxy with `CONNECT`), governed by the **policy**;
  2. **forwards** at `127.0.0.1:<port>`, each piping to one fixed target, *not* governed by the policy (they are an explicit, visible exception).
- Anything that ignores the proxy settings simply has no network. This is intended (fail-closed).
- No TLS interception. The proxy sees host names (from `CONNECT host:port` or the absolute URI) and resolved IP addresses, not content.

### 18.2 Proxy protocol support

| Request | Behaviour |
|---|---|
| `CONNECT host:port HTTP/1.1` | evaluate policy → resolve → connect → `HTTP/1.1 200 Connection Established` → bidirectional copy until either side closes |
| `GET/POST/… http://host[:port]/path HTTP/1.1` (absolute form) | evaluate policy → resolve → connect → forward the request in origin form with hop-by-hop headers removed (`Proxy-*`, `Connection`, `Keep-Alive`, `TE`, `Trailer`, `Upgrade` unless websocket, `Transfer-Encoding` preserved) and `Connection: close` → stream the response back → close. (v1: one request per client connection. Keep-alive is a later optimization.) |
| Origin-form request (`GET /path`) | `400 Bad Request` explaining that this is a proxy |
| Denied | `403 Forbidden`, `Content-Type: text/plain`, body: `oillamp: connection to <host>:<port> denied by rule "<label>" in oillamp.toml` — deliberately readable by the agent so it can report *why* something failed |
| DNS failure | `502 Bad Gateway`, body names the host |
| Connect timeout (10 s) / refused | `504 Gateway Timeout` / `502 Bad Gateway` |

Limits: header section ≤ 64 KiB, idle timeout 5 minutes per tunnel (configurable), concurrent connections ≤ 512 per lamp.

### 18.3 Policy model and evaluation

```toml
[network]
default = "allow"            # "allow" | "deny" — applies when no rule matches
log_allowed = true           # write allowed connections to network-<session>.jsonl
console_denied = true        # print denied connections in the supervisor console

[[network.rules]]
label  = "company artifact mirror"
action = "allow"
hosts  = ["nexus.corp.example.com"]
ports  = [443]

[[network.rules]]
label  = "block private and internal ranges"
action = "deny"
cidrs  = ["10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "100.64.0.0/10",
          "127.0.0.0/8", "169.254.0.0/16", "0.0.0.0/8",
          "::1/128", "fc00::/7", "fe80::/10"]
```

A rule has `label` (required, shown in logs and 403 bodies), `action` (`allow`/`deny`), and any combination of criteria. All criteria present in a rule must match (AND); a rule with no criteria matches everything.

| Criterion | Matches when |
|---|---|
| `hosts` | the requested host name matches any pattern. Patterns: exact (`example.com`), leading wildcard (`*.example.com` matches any subdomain, not the apex), or `*`. Case-insensitive, trailing dot ignored. IP literals are compared as strings after normalization. |
| `ports` | the requested port is in the list. Ranges allowed as strings: `"8000-8100"`. |
| `cidrs` | the **candidate address** (below) lies in any listed network. |

Evaluation (pure function, §25.3):

1. Parse the request into `(host, port)`. If `host` is an IP literal, the candidate addresses are that single address; else resolve on the host with the JVM resolver (`InetAddress.getAllByName`) → candidate addresses.
2. For **each candidate address** in resolver order, evaluate the rules top to bottom with `(host, port, address)`; the first matching rule decides; no match → `default`.
3. Connect to the first candidate whose decision is `allow`. If none is allowed, deny, reporting the decision for the first candidate.

Evaluating per address prevents DNS tricks (a public name resolving to an internal address is caught by the CIDR deny rule), and makes the result deterministic and testable.

### 18.4 Shipped default policy

`default = "allow"`, with one built-in deny rule for loopback, link-local, private (RFC 1918), CGNAT (often used by corporate VPNs), and IPv6 ULA ranges, exactly as in the example above. This gives the agent the open web but keeps it away from the intranet and from services on the host's own loopback. Users add `allow` rules for specific internal services **above** the deny rule. The default config file contains commented examples for: an internal Maven/npm mirror, a "deny everything except allow-list" setup, and blocking specific public sites.

### 18.5 Forwards

```toml
[[network.forwards]]
name   = "llm"                        # [a-z0-9-]+, unique; socket name fwd-<name>.sock
port   = 8000                         # 127.0.0.1:<port> inside the container (1024–65535, ≠ 3128)
target = "llm.corp.example.com:8000"  # host:port reachable from the host (may be 127.0.0.1)
```

- The supervisor listens on `sockets/host/fwd-<name>.sock`; per connection it opens TCP to `target` and pipes bytes. Resolution and connection happen on the host, so VPN/intranet routing of the host applies.
- Every forwarded connection is logged in `network-<session>.jsonl` with `"channel":"forward:<name>"`.
- Target unreachable at connection time → the agent sees a closed connection; the supervisor console prints a warning (rate-limited to one per 30 s per forward).
- At session start, oillamp performs a non-fatal TCP reachability check for each forward target and prints a warning if unreachable (`OIL-NET-010`).

### 18.6 Making the sandbox use the proxy

Set in `runtime.env` and applied to login shells via `/etc/profile.d/oillamp.sh`:

```
HTTP_PROXY=http://127.0.0.1:3128   http_proxy=…   HTTPS_PROXY=…   https_proxy=…
NO_PROXY=localhost,127.0.0.1,::1   no_proxy=…
JAVA_TOOL_OPTIONS=-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=3128 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=3128 -Dhttp.nonProxyHosts=localhost|127.0.0.1
NODE_USE_ENV_PROXY=1               (**⚠ VERIFY** Node ≥ 24 honours it for fetch; npm honours the env vars anyway)
```

- **Git over HTTPS** honours `https_proxy`. **SSH** (e.g. `git@github.com:`) goes through `/etc/ssh/ssh_config.d/50-oillamp-proxy.conf`: `Host * !lamp-*` → `ProxyCommand socat - PROXY:127.0.0.1:%h:%p,proxyport=3128` (the policy sees `CONNECT github.com:22`).
- **Gradle/Maven** inside the sandbox get the proxy through `JAVA_TOOL_OPTIONS` (the JVM prints "Picked up JAVA_TOOL_OPTIONS" to stderr — accepted).
- **Firefox ESR**: enterprise policy file (`/usr/lib/firefox-esr/distribution/policies.json`, **⚠ VERIFY** path for the Debian package) with `Proxy: { Mode: "manual", HTTPProxy: "127.0.0.1:3128", UseHTTPProxyForAllProtocols: true, Locked: true }`, plus `DisableTelemetry`, `DisableAppUpdate`, `DontCheckDefaultBrowser`.
- **No DNS**: tools that insist on resolving names themselves fail. The agent guide (§19.3) explains this.

### 18.7 Network log format

One JSON object per line:

```json
{"ts":"2026-09-22T14:20:11.412Z","channel":"proxy","method":"CONNECT","host":"repo1.maven.org","port":443,
 "address":"151.101.12.209","decision":"allow","rule":"(default)","bytesUp":1830,"bytesDown":2203114,"ms":841}
```

## 19. The agent's experience inside the sandbox

### 19.1 Home layout (created on lamp init, never overwritten afterwards)

```
/home/agent/
├── workspace/     the terminal opens here; put repos here
├── libs/          native libraries; on LD_LIBRARY_PATH; also passed to Java via JAVA_TOOL_OPTIONS -Djava.library.path
├── screenshots/   default output dir of `lamp screenshot`
└── AGENTS.md      symlink to /oillamp/session/agent-guide.md (created only if absent)
```

`LD_LIBRARY_PATH=/home/agent/libs${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}` and `-Djava.library.path=/home/agent/libs` are appended in `/etc/profile.d/oillamp.sh`. The workflow for native libraries: the user builds them on the host and copies them into `<lamp>/agent-lamp-<agentId>/libs/` — they are instantly visible in the sandbox.

### 19.2 Login environment

`/etc/profile.d/oillamp.sh` exports everything from `runtime.env` meant for the agent (display, proxy, LLM, library paths, `DBUS_SESSION_BUS_ADDRESS=unix:path=/run/agent/bus`, `XDG_RUNTIME_DIR=/run/agent`), sets `PS1` to `\[\e[33m\]🪔 lamp-<agentId>\[\e[0m\]:\w\$ ` and prints a 3-line banner (lamp name, desktop size and renderer, network default and number of rules, "read ~/AGENTS.md").

### 19.3 Generated agent guide

`/oillamp/session/agent-guide.md` is rendered per session (pure template function) and mounted into the agent's home as `~/AGENTS.md`. It is **the sandbox's description of itself**: an agent that does not know where it is will try `sudo apt install`, wonder why DNS does not resolve, and report "the network is broken" when in fact one policy rule refused one host. Every one of those is a wasted hour that a paragraph of text prevents.

It states, concretely, and always from the live configuration rather than from prose written once:

- that this is a sandbox, that a human is watching the desktop, and that the screen is being recorded;
- **what persists** (`/home/agent` only) and what does not (system packages, `/tmp`, everything from the read-only image);
- where to put code (`~/workspace`), where native libraries go (`~/libs`, already on `LD_LIBRARY_PATH` and `java.library.path`);
- that there is a real graphical desktop, its size, and that Xwayland is present so Swing and X11 apps work;
- **what it can do to that desktop** — the tools of §19.4, with examples;
- that there is no DNS and no direct network; the proxy variables that are already set; the *actual* default decision and rule count from the merged config; and the exact shape of the 403 body that names the refusing rule, so the agent reports it verbatim instead of retrying;
- the LLM base URL and any direct forwards, if configured;
- that `sudo` and `apt` are absent, and that the human adds packages via `image.extra_apt_packages` and a rebuild;
- **what it cannot do, and why** — it cannot signal the infra user's processes, read their sockets, or alter the recording of its own screen.

That last section is deliberately not hidden. An agent that understands the boundary works inside it and reports accurately when it hits one; an agent that does not will guess, and its guesses will be wrong in ways that cost the human time.

Additionally, oillamp installs it as the tools' global instruction files **if those files do not exist yet** (**⚠ VERIFY** locations: OpenCode `~/.config/opencode/AGENTS.md`, pi `~/.pi/agent/AGENTS.md`).

### 19.4 The `lamp` desktop helper

A CLI inside the image, `/usr/local/bin/lamp`, for agents (and humans) to operate the desktop. It is a **shell script** over the tools §12.1 already installs — `grim` (capture), `slurp` (regions), `wtype` (keyboard), `wlrctl` (pointer) — talking to the compositor through the agent's own `WAYLAND_DISPLAY` (D-27).

It is a convenience, not a gate. Everything it does, the agent may also do by calling `grim`/`wtype`/`wlrctl` itself, and the generated guide (§19.3) says so. This matters: an agent that hits a case the script does not cover should reach for the underlying tool, not conclude that screenshots are impossible.

| Command | Behaviour |
|---|---|
| `lamp screenshot [-o FILE] [--region X,Y,W,H]` | `grim` → PNG (default `~/screenshots/<timestamp>.png`); prints the path |
| `lamp click X Y [--button left\|middle\|right] [--double]` | `wlrctl pointer move` then `click` |
| `lamp move X Y` / `lamp drag X1 Y1 X2 Y2` | `wlrctl pointer` |
| `lamp scroll X Y (up\|down\|left\|right) [N]` | `wlrctl pointer scroll` |
| `lamp type "text"` | `wtype`, which takes UTF-8 directly |
| `lamp key ctrl+shift+t` | `wtype -M ctrl -M shift -k t` |
| `lamp info` | desktop size, renderer, output name (from `ready.json`) |
| `lamp wait-stable [--timeout S]` | two `grim` captures 500 ms apart until they are byte-identical (useful after launching an app) |

Exit code 0 on success, 2 on usage error, 3 if the compositor cannot be reached, with a one-line error on stderr. Being a script it starts in milliseconds and can be read — and fixed — from inside the sandbox.

### 19.5 Agent tools and LLM configuration

```toml
[agent_tools]
install = ["opencode", "pi"]                       # known ids → npm packages (table in code), pinned via versions
versions = { opencode = "latest", pi = "latest" }  # "latest" is resolved at image build time

[llm]
forward     = "llm"          # name of a [[network.forwards]] entry; empty = no LLM preconfiguration
base_path   = "/v1"          # → base URL http://127.0.0.1:<port>/v1
api_key_env = "OILLAMP_LLM_API_KEY"   # read on the HOST at session start; empty = no key
api_key_file = ""            # alternative: path on the host
models      = ["qwen3-coder-480b"]    # model ids offered to the tools; first = default
provider_name = "company"
```

At session start oillamp exports `OPENAI_BASE_URL`, `OPENAI_API_KEY` (if configured), `OILLAMP_LLM_BASE_URL`, `OILLAMP_LLM_MODELS` in the agent environment and writes tool configuration files into the agent home **only if absent** (never clobbering the user's or agent's edits): OpenCode `~/.config/opencode/opencode.json` with a custom OpenAI-compatible provider, pi `~/.pi/agent/models.json` with a custom provider (**⚠ VERIFY** both schemas against the installed tool versions; keep the templates as resources so they can be fixed without code changes). `oillamp at --reset-tool-config` rewrites them.

> Security note: the API key becomes visible to the agent (it must use it). If per-sandbox keys are available from the LLM gateway, prefer those.

## 20. Configuration

### 20.1 Files and precedence

1. Built-in defaults (resource `defaults.toml` in the jar; the same effective values as the template in §20.2).
2. `~/.config/oillamp/config.toml` (optional, user-global; same schema).
3. `<lamp>/oillamp.toml` (written on init from the commented template resource `oillamp.toml.template`).

Merge semantics: tables merge key by key (later wins); **arrays replace** as a whole (so a lamp's `network.rules` fully replaces the global list — documented at the top of the template). Merging is a pure function over a generic TOML tree before binding to records.

`schema_version = 1` is required in lamp files. Unknown keys → `OIL-CONFIG-002`. Type errors → `OIL-CONFIG-003`. Semantic errors (e.g. forward port collides with 3128, duplicate forward names, `llm.forward` refers to an unknown forward, invalid CIDR, width not in 640–7680) → `OIL-CONFIG-004`. All config problems are collected and reported together, each with file, key path (`network.rules[2].cidrs[0]`), value, and expectation.

### 20.2 Full default `oillamp.toml`

```toml
# oillamp lamp configuration. Edited by you, never visible to the agent.
# Changes take effect at the next `oillamp at`. Arrays replace (not merge with) values
# from ~/.config/oillamp/config.toml.
schema_version = 1

[display]
width  = 1920
height = 1080
scale  = 1.0              # 1.0, 1.25, 1.5, 2.0 …
gpu    = "auto"           # "auto" | "on" | "off"

[viewer]
open_on_start = true
clipboard     = "to-agent" # "to-agent" | "both" | "none"
view_only     = false
max_fps       = 30

[terminal]
profile = "auto"          # "auto" | "ptyxis" | "gnome-terminal" | "kgx" | "konsole" | "kitty" | "foot" | "alacritty" | "wezterm" | "xterm"
# command = ["myterm", "--title", "{title}", "--", "{cmd}"]   # custom terminal; overrides profile

[recording]
enabled        = true
codec          = "libx264"
crf            = 30
max_fps        = 10
max_age_days   = 14
max_total_gb   = 20

[limits]
memory = "16g"
cpus   = 0                # 0 = host CPUs minus one
pids   = 8192

[network]
default        = "allow"  # applies when no rule matches
log_allowed    = true
console_denied = true

# Rules are evaluated top to bottom; the first match wins. Put allow-exceptions ABOVE the deny rule.
# [[network.rules]]
# label  = "internal maven mirror"
# action = "allow"
# hosts  = ["nexus.corp.example.com"]
# ports  = [443]

[[network.rules]]
label  = "block private, internal and loopback ranges"
action = "deny"
cidrs  = ["10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "100.64.0.0/10",
          "127.0.0.0/8", "169.254.0.0/16", "0.0.0.0/8",
          "::1/128", "fc00::/7", "fe80::/10"]

# Example: allow-list mode — set default = "deny" above and list what is allowed:
# [[network.rules]]
# label  = "package registries"
# action = "allow"
# hosts  = ["registry.npmjs.org", "repo1.maven.org", "*.pythonhosted.org", "pypi.org"]

# Forwards expose one fixed target at 127.0.0.1:<port> inside the sandbox (not subject to rules).
# [[network.forwards]]
# name   = "llm"
# port   = 8000
# target = "llm.corp.example.com:8000"

[llm]
forward       = ""        # e.g. "llm" — enables preconfiguration of the agent tools
base_path     = "/v1"
api_key_env   = "OILLAMP_LLM_API_KEY"
api_key_file  = ""
models        = []
provider_name = "company"

[agent_tools]
install  = ["opencode", "pi"]
versions = { opencode = "latest", pi = "latest" }

[image]
base               = "docker.io/library/debian:trixie"
node_version       = "24"          # major LTS line; exact version resolved at build
jdk_package        = "temurin-25-jdk"
extra_apt_packages = []

[host]
auto_install = true       # install missing host packages with sudo apt-get (logged)

[timeouts]
container_ready_seconds  = 45
terminal_connect_seconds = 60
stop_seconds             = 15
```

### 20.3 `runtime.env` (rendered per session, read by entrypoint and login shells)

Plain `KEY=value` lines, values single-quoted with shell escaping (pure renderer with tests for quotes, spaces, and newlines — newlines are rejected). Contents: `OILLAMP_SESSION`, `OILLAMP_AGENT_ID`, `OILLAMP_LAMP_NAME`, `OILLAMP_DISPLAY_WIDTH/HEIGHT/SCALE`, `OILLAMP_RENDERER` (`gles2|pixman`), `OILLAMP_VNC_MAX_FPS`, `OILLAMP_RECORDING_*`, `OILLAMP_FORWARDS` (`name:port` list), proxy variables, LLM variables, `OILLAMP_PROXY_PORT=3128`.

---

# Part III — Java Design

## 21. Technology stack

| Concern | Choice | Notes |
|---|---|---|
| Language | **Java 25** (LTS), no preview features | records, sealed interfaces, pattern matching for `switch`, record patterns, virtual threads, Unix domain socket channels (JDK 16+) |
| Build | **Gradle** (current 9.x), Kotlin DSL, version catalog `gradle/libs.versions.toml`, Java toolchain 25 | |
| CLI | **picocli** 4.7.x | subcommands, help, completion script generation |
| JSON / TOML | **Jackson** — `jackson-databind` + `jackson-dataformat-toml` (Jackson 3.x, package `tools.jackson.*`; **⚠ VERIFY** TOML module availability for 3.x, else use 2.x `com.fasterxml.jackson.*` consistently) | wire model only (§24.6) |
| Persistent collections | **Sprouts** (`io.github.globaltcad:sprouts`, latest 2.x) — `Tuple`, `ValueSet`, `Association` | the only collection types in domain records (§23) |
| Tests | JUnit Jupiter, AssertJ, ArchUnit; optional jqwik for property tests of the policy engine | |
| Packaging | `jlink` + `jpackage` → `.deb` | §31 |
| No other runtime dependencies | no logging framework, no HTTP client/server library, no SSH library, no DI container | keeps the jlink image small and the behaviour explicit |

## 22. Project structure and the public API

> **Amended in implementation (D-26, §36).** This section originally specified eight Gradle
> modules. One module replaced them. The rules the modules were there to enforce are unchanged —
> they are now enforced by ArchUnit inside a single source set, which is where they are actually
> checked rather than merely declared.

```
oillamp/
├── build.gradle                    one module: Java 25 toolchain, Error Prone + NullAway, Spock
├── settings.gradle
├── docs/                           SproutsCheatSheet.md, STATUS.md
├── src/main/java/dev/oillamp/      all production code — one package, 75 classes, 5 of them public
├── src/test/groovy/oillamp/        Spock scenarios — a DIFFERENT package, deliberately (see below)
└── src/main/resources/image/       Containerfile, entrypoint, sway config, sshd_config, templates
```

### 22.1 The public API is five types

| Type | Why it is public |
|---|---|
| `OilLamp` | The entry point. `OilLamp.on(machine).run(argv)` is the whole tool; `main` is a three-line wrapper around it. |
| `Machine` | The single seam through which effects happen. Public because a caller — a test, or a future GUI — must be able to supply one. |
| `LampEvent` | The progress stream. Public because NFR-08 requires a GUI to be able to drive the same core and render its own view of it. |
| `Problem` | What went wrong, structured (NFR-03). Public because a caller has to be able to inspect and re-render failures, not just read English. |
| `ExitStatus` | The process exit codes of §28, so a caller can act on them by name instead of by number. |

**Everything else is package-private**, and *every class says in its Javadoc which of the two it is
and why*. That sentence is not decoration: it is the record of a decision that is otherwise
invisible, and the thing a later contributor needs in order to widen the surface on purpose rather
than by reflex. "Deliberately package-private: this is the TOML merge, and the merge rule — tables
merge key-by-key, arrays replace wholesale — is a choice we may need to revisit; nothing outside
should depend on it" is a sentence that prevents a future mistake. Its absence invites one.

### 22.2 Why one package

A package-private class cannot be referenced from another package. That is a compiler rule, not a
review convention, and it is the cheapest enforcement available. One package therefore buys the
largest possible number of package-private classes — 70 of 75 — and the boundary holds without
anyone having to remember it.

The tests are the other half of the same mechanism. They live in package **`oillamp`**, outside
`dev.oillamp`, so they *cannot* reach an internal even by accident. A scenario has no choice but
to go through `OilLamp.run(...)` and assert on events, problems and exit codes — which is to say,
it has no choice but to describe something a user could recognise. This is why the scenarios read
like *"Every missing prerequisite is reported in one run, not one per attempt"* rather than
`HostPlannerTest.testCombine()`.

The cost is real and accepted: no sub-package structure, one directory with 75 files, and internal
helpers that are only distinguishable from domain types by reading them. For a tool this size that
is a better trade than eight build units, eight `module-info.java` files and a dependency graph to
keep honest.

### 22.3 The rules that survived from the module split

Enforced by `TheShapeOfTheCodeSpec`:

- **Exactly five public types.** A sixth fails the build. Adding one is a deliberate act with a
  test to change.
- **No internal type in a public signature.** The API cannot leak by accident — a public method
  returning a package-private type would be unusable anyway, but one returning `Plan` or `Result`
  would drag the internals into the contract.
- **Effects are confined to an allowlist.** Only `RealMachine`, `SimulatedMachine`, `Filesystem`,
  `LampLock`, `HostProbe`, `StepRunner`, `LampPhase`, `HostPhase`, `Commands`, `ConsoleRenderer`,
  `OilLamp`, `Invocation` and `Machine` may touch `ProcessBuilder`, `Files`, sockets, threads,
  `System.getenv`, `Instant.now()` or `SecureRandom`. This is the original `oillamp-core` purity
  rule (it was the point of the module split) applied per class instead of per module, and it is
  strictly stronger: it names the exceptions rather than granting a whole module the privilege.

Root Java package: `dev.oillamp`. JPMS is not used; `jlink`/`jpackage` run in classpath mode
(S11's fallback), which removes the question of whether Sprouts is a proper module.

## 23. Programming model (rules for all code)

oillamp follows **data-oriented programming** with a **functional core and an imperative shell**:

1. **Data is immutable values.** Domain data are `record`s. Validation happens in compact constructors; an invalid record cannot exist. Records hold only immutable types: primitives, `String`, `Path`, `Instant`, `Duration`, `Optional`, enums, other records, sealed types, and Sprouts `Tuple` / `ValueSet` / `Association`. Never `java.util.List/Set/Map`, never arrays.
2. **Alternatives are sealed interfaces** with record implementations (sum types). Code branches on them with exhaustive `switch` + record patterns, **without a `default` branch**, so adding a case produces compile errors everywhere it must be handled.
3. **Logic is pure static functions** in final utility classes or as record methods that only depend on the record's state and parameters. Given the same inputs they return the same output, with no side effects.
4. **Effects live at the edges.** The shell gathers facts (probes), calls pure functions to decide, then interprets the resulting data (plans, actions) by performing effects. Effects report back as data (results, events).
5. **No `null`.** Absence is `Optional` (record components may be `Optional`). Nullable values from libraries are converted at the boundary.
6. **No exceptions for expected failures.** Expected failures are values: `Result<T>` (§27). Exceptions are only for bugs and truly unexpected conditions; the shell catches them at the top of each thread and converts them to `OIL-INTERNAL-001` problems with the stack trace in the log.
7. **No mutable shared state.** The only mutable state is the supervisor's current `SessionState` reference, owned by the single event-loop thread.
8. **No inheritance hierarchies** beyond sealed interfaces. Composition via records and functions.
9. **Identifiers are types**: `AgentId`, `SessionId`, `ImageTag`, `ContainerName`, `ProblemCode` instead of raw strings.
10. **Every pure function has unit tests**; every renderer has golden-file tests.

Sprouts guidance: use `Tuple<T>` for ordered sequences (plans, rules, argv), `ValueSet<T>` for sets (installed packages), `Association<K,V>` for maps (environment variables, config tables). Builders in pure functions add elements by returning new collections (structural sharing makes this cheap). **⚠ VERIFY** the exact factory/method names of the Sprouts version used (e.g. `Tuple.of(Class, …)`, `Association.between(K.class, V.class)`, `put`, `add`) and write a short `SproutsCheatSheet.md` in the repo for contributors.

## 24. Domain model (key types)

The sketches below are normative for names and structure; method bodies and minor components are up to the implementer.

### 24.1 Identity and layout

```java
public record AgentId(String value) {
    public AgentId { if (!value.matches("[a-z2-7]{8}")) throw new IllegalArgumentException(...); }
}
public record SessionId(String value) { /* yyyyMMdd-HHmmss */ }
public record ImageTag(String value) { }
public record ContainerName(String value) {
    public static ContainerName of(AgentId id) { return new ContainerName("oillamp-" + id.value()); }
}

/** All paths are derived purely from the lamp root, agent id and the host runtime dir. */
public record LampLayout(Path root, AgentId agentId, Path xdgRuntimeDir) {
    public Path config()        { return root.resolve("oillamp.toml"); }
    public Path stateDir()      { return root.resolve(".oillamp"); }
    public Path agentDir()      { return root.resolve("agent-lamp-" + agentId.value()); }
    public Path sessionDir()    { return stateDir().resolve("session"); }
    public Path socketsDir()    { return stateDir().resolve("sockets"); }
    public Path recordingsDir() { return stateDir().resolve("recordings"); }
    public Path logsDir()       { return stateDir().resolve("logs"); }
    public Path runtimeDir()    { return xdgRuntimeDir.resolve("oillamp").resolve(agentId.value()); }
    public Path shortSockets()  { return runtimeDir().resolve("sockets"); }   // symlink → socketsDir()
    public Path vncSocket()     { return shortSockets().resolve("infra/vnc.sock"); }
    public Path primarySshSocket() { return runtimeDir().resolve("run/ssh-primary.sock"); }
    // … every path in §9 has exactly one method here
}

public sealed interface LampState {
    record Missing(Path root)                          implements LampState {}
    record Empty(Path root)                            implements LampState {}
    record Foreign(Path root, Tuple<String> sampleEntries) implements LampState {}
    record Existing(Path root, LampMeta meta)          implements LampState {}
    record Unreadable(Path root, String reason)        implements LampState {}
}
public record LampMeta(int schemaVersion, AgentId agentId, Instant createdAt, String createdBy, Optional<Instant> lastSessionAt) {}
```

### 24.2 Host facts

```java
public record HostFacts(
    OsRelease os,                       // id, idLike, versionId, prettyName
    UserInfo user,                      // name, uid, gid, home, groups (ValueSet<String>)
    Optional<Path> xdgRuntimeDir,
    GraphicalSession session,           // sealed: Wayland(display, desktop) | X11(display, desktop) | None
    ValueSet<String> installedPackages, // among the required ones
    SubIdFacts subIds,                  // sealed: Present(range) | Missing(existingRanges: Tuple<IdRange>)
    Optional<PodmanFacts> podman,       // version, rootless, ociRuntime, storageDriver, cgroupVersion
    UsernsFacts userns,                 // sealed: Works | Fails(CommandEvidence, apparmorRestricted: boolean)
    boolean selinuxEnabled,
    Tuple<TerminalCandidate> terminals, // detected profiles with absolute paths
    Optional<Path> vncViewer,
    GpuFacts gpu,                       // Tuple<RenderNode(path, group, driver)>
    int cpuCount,
    SudoFacts sudo,                     // sealed: Passwordless | NeedsPassword(isTty) | Unavailable
    String fileSystemTypeOfLamp
) {}
```

### 24.3 Configuration (bound, validated)

```java
public record LampConfig(DisplayConfig display, ViewerConfig viewer, TerminalConfig terminal,
                         RecordingConfig recording, Limits limits, NetworkPolicy network,
                         Tuple<Forward> forwards, Optional<LlmConfig> llm, AgentToolsConfig agentTools,
                         ImageConfig image, HostConfig host, Timeouts timeouts) {}

public record DisplayConfig(int width, int height, double scale, GpuMode gpu) {}
public enum GpuMode { AUTO, ON, OFF }
public enum ClipboardMode { TO_AGENT, BOTH, NONE }

public sealed interface TerminalConfig {
    record Auto() implements TerminalConfig {}
    record Profile(TerminalProfileId id) implements TerminalConfig {}
    record Custom(Tuple<String> template) implements TerminalConfig {}   // with {cmd} and {title}
}

public record NetworkPolicy(Decision defaultDecision, Tuple<Rule> rules, boolean logAllowed, boolean consoleDenied) {}
public enum Decision { ALLOW, DENY }
public record Rule(String label, Decision action, Tuple<HostPattern> hosts, Tuple<PortRange> ports, Tuple<Cidr> cidrs) {}
public sealed interface HostPattern {
    record Exact(String host) implements HostPattern {}
    record AnySubdomainOf(String suffix) implements HostPattern {}  // "*.example.com"
    record Any() implements HostPattern {}
}
public record PortRange(int from, int to) {}
public record Cidr(IpAddress network, int prefixLength) {}   // IpAddress: own record wrapping 4 or 16 bytes as two longs/ints — no arrays in records
public record Forward(String name, int port, HostAndPort target) {}
```

### 24.4 Plans and steps

```java
public record Plan(Phase phase, Tuple<Step> steps) {}
public enum Phase { HOST, LAMP, IMAGE, SESSION }

public sealed interface Step {
    record InstallPackages(DistroFamily family, ValueSet<String> packages, Association<String,String> reasons) implements Step {}
    record AddSubIds(String user, IdRange range) implements Step {}
    record PodmanMigrate() implements Step {}
    record CreateDirectory(Path path, PosixMode mode) implements Step {}
    record WriteFile(Path path, String content, PosixMode mode, WritePolicy policy) implements Step {}  // ALWAYS | IF_ABSENT
    record CreateSymlink(Path link, Path target) implements Step {}
    record ChownForContainer(Path path, int containerUid, int containerGid, PosixMode mode) implements Step {}
    record GenerateSshKey(Path privateKey, String comment) implements Step {}
    record ExtractImageContext(Path targetDir) implements Step {}
    record BuildImage(ImageTag tag, Path context, Association<String,String> buildArgs, boolean noCache) implements Step {}
    record RemoveContainer(ContainerName name, String reason) implements Step {}
    record DeleteContainerOwnedFiles(Tuple<Path> files, String reason) implements Step {}
    record RemovePath(Path path, String reason) implements Step {}
    record WriteLampMeta(LampMeta meta) implements Step {}
}
```

Every `Step` has a pure `describe()` (one line, used for console and `--dry-run`) and a pure `detail()` (multi-line, used in the log). The `StepRunner` in `oillamp-app` interprets steps with an exhaustive switch.

### 24.5 Session

```java
public sealed interface SessionState {
    record Starting(Instant since) implements SessionState {}
    record AwaitingTerminal(Instant since, ReadyInfo ready) implements SessionState {}
    record Running(Instant since, ReadyInfo ready, int extraShells) implements SessionState {}
    record ShuttingDown(ShutdownReason reason, Instant since) implements SessionState {}
    record Stopped(ShutdownReason reason, ExitStatus exit) implements SessionState {}
}
public sealed interface ShutdownReason {
    record TerminalClosed() implements ShutdownReason {}
    record UserInterrupt(String signal) implements ShutdownReason {}
    record StopCommand(String source) implements ShutdownReason {}
    record ContainerDied(int exitCode) implements ShutdownReason {}
    record StartupFailed(Problem problem) implements ShutdownReason {}
}
public sealed interface SessionEvent {
    record Tick(Instant now) implements SessionEvent {}
    record ContainerReady(ReadyInfo info) implements SessionEvent {}
    record ContainerExited(int exitCode) implements SessionEvent {}
    record PrimaryConnected() implements SessionEvent {}
    record PrimaryDisconnected() implements SessionEvent {}
    record ShellConnected() implements SessionEvent {}
    record ShellDisconnected() implements SessionEvent {}
    record Interrupted(String signal) implements SessionEvent {}
    record StopRequested(String source) implements SessionEvent {}
    record ActionFailed(Action action, Problem problem) implements SessionEvent {}
    record ShutdownCompleted(Tuple<Problem> problems) implements SessionEvent {}
}
public sealed interface Action {
    record LaunchViewer() implements Action {}
    record LaunchTerminal() implements Action {}
    record CloseShells() implements Action {}
    record BeginShutdown(ShutdownReason reason) implements Action {}
    record Emit(LampEvent event) implements Action {}
    record Exit(ExitStatus status) implements Action {}
}
public record Transition(SessionState next, Tuple<Action> actions) {}
```

### 24.6 Wire model vs domain model

Jackson binds files and podman JSON into **wire records** (`dev.oillamp.core.wire`) that mirror the external format exactly (`java.util.List`/`Map` allowed here, made unmodifiable, `@JsonIgnoreProperties(ignoreUnknown = true)` for podman output, strict for config). Pure mapper functions convert wire → domain (with validation producing `Problem`s) and domain → wire. Domain records never carry Jackson annotations. This keeps Sprouts types out of Jackson and gives exact error locations for config.

Config binding detail: parse TOML text to a Jackson tree (`JsonNode`), merge trees (§20.1), check unknown keys against the wire schema, then bind to wire records, then map to domain with semantic validation. Each stage returns `Result`.

## 25. Pure core: the important functions

| Function (module `oillamp-core`) | Signature (sketch) | Notes |
|---|---|---|
| Lamp path validation | `LampPaths.validate(Path requested, UserInfo user) → Result<Path>` | normalizes (absolute, `..` resolved, symlinks resolved by the shell beforehand), FR-03 |
| Lamp classification | `LampClassifier.classify(Path root, DirListing listing, Optional<String> lampJson) → LampState` | the shell reads the directory and file; the function decides |
| Host plan | `HostPlanner.plan(HostFacts, HostRequirements, HostConfig, CliOptions) → Result<Plan>` | emits install/subid steps or problems |
| Sub-ID allocation | `SubIdAllocator.allocate(Tuple<IdRange> existing, int size) → IdRange` | first free 65536-aligned block ≥ 100000 |
| Config pipeline | `ConfigLoader.load(Tuple<ConfigSource> sources) → Result<LampConfig>` | sources = (origin path, text) |
| Lamp plan | `LampPlanner.plan(LampState, LampLayout, LampConfig, HostFacts, SessionId, Instant now, Tuple<RecordingFile> recordings, Optional<ContainerState> leftover) → Result<Plan>` | skeleton/migrations/keys/session files/retention/stale cleanup |
| Image inputs | `ImageInputs.of(LampConfig, Tuple<ContextFile>) → ImageSpec(tag, buildArgs)` and `BuildHash.compute(...)` | hashing via `MessageDigest` is pure |
| Container spec | `ContainerSpecs.of(LampLayout, LampConfig, HostFacts, SessionId, ImageTag, GpuDecision) → ContainerSpec` | |
| podman argv | `PodmanArgs.run(ContainerSpec) → Tuple<String>` (+ `stop`, `rm`, `build`, `inspect`) | golden tests |
| GPU decision | `Gpu.decide(GpuMode, GpuFacts, UserInfo, PodmanFacts) → GpuDecision` | sealed: `Hardware(node)` / `Software(reason)` / `Error(Problem)` |
| Terminal choice | `Terminals.choose(TerminalConfig, Tuple<TerminalCandidate>, GraphicalSession) → Result<TerminalProfile>` | |
| Terminal argv | `Terminals.command(TerminalProfile, String title, Tuple<String> sshArgv) → Tuple<String>` | |
| SSH argv/config | `Ssh.clientArgv(LampLayout, SocketRole) → Tuple<String>`; `Ssh.renderConfig(LampLayout) → String`; `Ssh.renderSshdConfig()` (static resource) | |
| Viewer argv | `Viewer.command(ViewerConfig, LampLayout, DisplayConfig, boolean viewOnly) → Tuple<String>` | |
| runtime.env | `RuntimeEnv.render(Association<String,String>) → Result<String>` | quoting rules, rejects newlines |
| Agent guide | `AgentGuide.render(LampConfig, LampLayout, ReadyInfo?) → String` | |
| Policy | `PolicyEngine.decide(NetworkPolicy, Target(host, port), IpAddress candidate) → Verdict(decision, ruleLabel)` | §18.3 |
| Retention | `Retention.select(Tuple<RecordingFile>, RecordingConfig, Instant now) → Tuple<RecordingFile>` | |
| Session machine | `SessionMachine.step(SessionState, SessionEvent, SessionSettings) → Transition` | timeouts via `Tick` |
| Problem rendering | `ProblemRenderer.console(Problem, boolean color) → String`, `.log(Problem) → String` | |
| Summary | `SessionSummary.of(SessionStats) → String` | |

### 25.1 Session machine transition table (normative)

| State | Event | Next state | Actions |
|---|---|---|---|
| Starting | ContainerReady(r) | AwaitingTerminal | Emit(ready), LaunchViewer (if `open_on_start`), LaunchTerminal |
| Starting | ContainerExited(c) | ShuttingDown(StartupFailed) | BeginShutdown |
| Starting | Tick(now) past ready timeout | ShuttingDown(StartupFailed(OIL-CONTAINER-004)) | BeginShutdown |
| AwaitingTerminal | PrimaryConnected | Running | Emit(running) |
| AwaitingTerminal | Tick past terminal timeout | ShuttingDown(StartupFailed(OIL-TERM-002)) | BeginShutdown |
| Running | PrimaryDisconnected | ShuttingDown(TerminalClosed) | CloseShells, BeginShutdown |
| Running | ShellConnected / ShellDisconnected | Running (±1) | Emit |
| any non-final | Interrupted(sig) | ShuttingDown(UserInterrupt) | CloseShells, BeginShutdown |
| any non-final | StopRequested(src) | ShuttingDown(StopCommand) | CloseShells, BeginShutdown |
| Starting/AwaitingTerminal/Running | ContainerExited(c) | ShuttingDown(ContainerDied(c)) | CloseShells, BeginShutdown |
| any | ActionFailed(LaunchViewer, p) | unchanged | Emit(warning p) |
| any | ActionFailed(LaunchTerminal, p) | ShuttingDown(StartupFailed(p)) | BeginShutdown |
| ShuttingDown | ShutdownCompleted(ps) | Stopped | Emit(summary), Exit(status from reason + ps) |
| ShuttingDown | anything else | unchanged | (ignored, logged) |
| Stopped | anything | unchanged | — |

## 26. Imperative shell

### 26.1 ProcessRunner

```java
public record CommandSpec(Tuple<String> argv, Association<String,String> env, Optional<Path> workDir,
                          Duration timeout, IoMode io, String label) {}
public sealed interface IoMode {
    record Capture() implements IoMode {}                    // stdout/stderr collected (bounded, 4 MiB each; tail kept)
    record Inherit() implements IoMode {}                    // for sudo prompts / apt output
    record Stream(String sourceTag) implements IoMode {}     // each line → LampEvent.Output(sourceTag, line) + captured tail
    record Detached(Path logFile) implements IoMode {}       // GUI programs: stdout/stderr appended to a log file, not awaited
}
public record CommandResult(CommandSpec spec, int exitCode, String stdoutTail, String stderrTail, Duration took) {}
```

Rules: never invoke a shell (`/bin/sh -c`) — always argv. Log every command line (with secrets redacted: any env var or argument whose name contains `KEY`, `TOKEN`, `PASSWORD` is replaced by `***`) before running. On timeout: destroy the process and its descendants (`ProcessHandle.descendants()`), return a `Result.Err` with `OIL-EXEC-002`. A non-zero exit code is a normal `CommandResult`; the calling adapter decides whether it's a problem and wraps it with context (`CommandEvidence`).

### 26.2 Adapters (oillamp-host)

- `HostProbe.probe(Path lampPathHint) → HostFacts`: all probes run in parallel on virtual threads with individual timeouts (5 s each); each probe failure becomes a fact (e.g. `podman = Optional.empty()` plus an evidence record), never an exception.
- `Podman`: `version()`, `info()`, `unshareTrue()`, `imageExists(tag)`, `build(spec, sink)`, `run(argv)`, `inspect(name)`, `stop(name, timeout)`, `rm(name)`, `waitFor(name) → exit code` (blocking; run on a virtual thread), `unshare(argv)`, `listByLabel(label)`. All JSON outputs parsed via wire records.
- `Apt`: `installed(Set)`, `install(packages, sink)` (uses `sudo`; `Inherit` IO when interactive).
- `Files`-based helpers: atomic writes (write to temp in same dir + `ATOMIC_MOVE`), permissions via `PosixFilePermissions`, symlinks.
- `SshKeygen.generate(path, comment)`.
- `TerminalLauncher.launch(argv, logFile)`, `ViewerLauncher.launch(argv, logFile)`: `Detached` mode; they return a `LaunchResult(pid, earlyExit: Optional<Integer>)` after waiting 1.5 s to catch immediate failures (missing display, bad arguments).

### 26.3 Unix sockets (oillamp-net)

- Listening: delete a stale socket file, `ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(UnixDomainSocketAddress.of(path))`, then chmod (0666 for `sockets/host/*`, 0600 for `run/*`). Paths are validated ≤ 107 bytes by a pure check before binding (`OIL-NET-001` otherwise).
- Byte copying: `ByteCopier.pipe(ReadableByteChannel, WritableByteChannel, counter)` with a 64 KiB direct buffer; two virtual threads per connection; half-close handling via `shutdownOutput()` where supported; on any error both channels are closed.

### 26.4 EgressProxy

- Accept loop on `sockets/host/proxy.sock` (virtual thread); each connection on its own virtual thread.
- Minimal HTTP/1.1 parser for the request line and headers (no library), limits per §18.2.
- DNS resolution on the host (`InetAddress.getAllByName`, run with a 5 s timeout via a virtual thread).
- Decision by `PolicyEngine`; outcome recorded as a `ConnectionRecord` value → `NetworkJournal` (JSONL file writer, single writer thread fed by a queue) and, if denied and `console_denied`, a `LampEvent.NetworkDenied` to the console.

### 26.5 Supervisor (oillamp-app)

- One **event-loop virtual thread** takes `SessionEvent`s from a `LinkedBlockingQueue`, calls `SessionMachine.step`, stores the new state, publishes `LampEvent.SessionStateChanged`, and executes the returned actions via `ActionRunner`. Actions that may block (launching, shutdown) run on their own virtual threads and report back with events.
- Event producers: `Ticker` (1 s `Tick`), `ContainerWatcher` (`podman wait`), `SshRelay` (connect/disconnect), `ControlServer`, the JVM shutdown hook (`Interrupted`), `ReadinessWatcher`.
- The **shutdown hook** posts `Interrupted("SIGINT/SIGTERM")` and blocks until the state is `Stopped` or 30 s pass (then runs the idempotent `ShutdownSequence` directly as a last resort).

### 26.6 Control socket

`run/control.sock` accepts JSON lines: `{"op":"status"}` → state + session info; `{"op":"stop"}` → `StopRequested("oillamp stop")`; `{"op":"view"}` → launch another viewer; `{"op":"shell"}` → returns the argv for an extra shell (the `shell` command then runs ssh itself in the calling terminal). Responses are JSON lines. This is also the future GUI's integration point.

### 26.7 Events for UIs

```java
public sealed interface LampEvent {
    record PhaseStarted(Phase phase) implements LampEvent {}
    record StepStarted(Step step) implements LampEvent {}
    record StepSucceeded(Step step, Duration took) implements LampEvent {}
    record StepSkipped(Step step, String why) implements LampEvent {}
    record Output(String sourceTag, String line) implements LampEvent {}
    record Warning(Problem problem) implements LampEvent {}
    record Failure(Problem problem) implements LampEvent {}
    record SessionStateChanged(SessionState state) implements LampEvent {}
    record NetworkDenied(ConnectionRecord record) implements LampEvent {}
    record Summary(SessionStats stats) implements LampEvent {}
}
```

`EventBus` fans out to subscribers: `ConsoleRenderer` (cli), `Journal` (log file), and later the Swing GUI. Before the lamp's log directory exists, the `Journal` buffers events in memory and flushes them once it can write.

## 27. Error handling and reporting

### 27.1 Result type

```java
public sealed interface Result<T> {
    record Ok<T>(T value, Tuple<Problem> warnings) implements Result<T> {}
    record Err<T>(Tuple<Problem> problems) implements Result<T> {}
    // map, flatMap, combine (collects ALL problems of independent results), orElseThrow for tests
}
```

Independent validations are **combined**, so the user sees every problem at once (e.g. all config errors, all missing prerequisites), not one per run.

### 27.2 Problem

```java
public record Problem(ProblemCode code, Severity severity, String title,
                      String whatHappened, String whyItMatters,
                      Tuple<Evidence> evidence, Tuple<Fix> fixes, Optional<Path> logFile) {}
public enum Severity { INFO, WARNING, ERROR }
public sealed interface Evidence {
    record CommandEvidence(Tuple<String> argv, int exitCode, String stderrTail, Duration took) implements Evidence {}
    record FileEvidence(Path path, String note) implements Evidence {}
    record ValueEvidence(String name, String value) implements Evidence {}
    record ExcerptEvidence(String title, String text) implements Evidence {}   // e.g. container log tail
    record ConfigEvidence(Path file, String keyPath, String value, String expected) implements Evidence {}
}
public record Fix(String description, Optional<String> command) {}
```

### 27.3 Problem code catalog (initial)

| Code | Title |
|---|---|
| OIL-HOST-001 | Unsupported operating system |
| OIL-HOST-002 | Unsupported Linux distribution (no APT) |
| OIL-HOST-003 | No graphical session found |
| OIL-HOST-010 | No subordinate UID/GID range for the user |
| OIL-PKG-001 | Required host packages missing |
| OIL-PKG-002 | Cannot run sudo |
| OIL-PKG-003 | Package installation failed |
| OIL-PODMAN-001 | Podman too old or not found |
| OIL-PODMAN-002 | Podman is not running rootless |
| OIL-PODMAN-003 | Rootless user namespaces do not work |
| OIL-PODMAN-004 | Blocked by Ubuntu's AppArmor user-namespace restriction |
| OIL-LAMP-001 | Invalid lamp path |
| OIL-LAMP-002 | Directory is not empty and not a lamp |
| OIL-LAMP-003 | Refusing to use a system or home directory as a lamp |
| OIL-LAMP-004 | Lamp created by a newer oillamp |
| OIL-LAMP-005 | Lamp is on a filesystem without Unix socket support |
| OIL-LAMP-006 | Cannot write to the lamp directory |
| OIL-LOCK-001 | Lamp is already running |
| OIL-LOCK-002 | Cleaned up after a previous crashed session (warning) |
| OIL-CONFIG-001 | Config file cannot be parsed (TOML syntax) |
| OIL-CONFIG-002 | Unknown config key |
| OIL-CONFIG-003 | Wrong config value type |
| OIL-CONFIG-004 | Invalid config value |
| OIL-IMAGE-001 | Image build failed |
| OIL-IMAGE-002 | Base image cannot be pulled |
| OIL-CONTAINER-001 | Container failed to start |
| OIL-CONTAINER-002 | Container exited during startup |
| OIL-CONTAINER-003 | Container ready but sockets unreachable |
| OIL-CONTAINER-004 | Container not ready in time |
| OIL-CONTAINER-005 | Container died during the session |
| OIL-GPU-001/002/003 | GPU not used (info) / needs crun (info) / required but unavailable (error) |
| OIL-SSH-001 | Key generation failed |
| OIL-TERM-001 | No supported terminal emulator found |
| OIL-TERM-002 | Terminal window did not connect |
| OIL-VIEW-001 | Viewer exited immediately |
| OIL-NET-001 | Socket path too long |
| OIL-NET-002 | Cannot listen on a socket |
| OIL-NET-010 | Forward target unreachable (warning) |
| OIL-REC-001 | Recording could not be started / finalized (warning) |
| OIL-EXEC-001 | Command not found |
| OIL-EXEC-002 | Command timed out |
| OIL-INTERNAL-001 | Unexpected internal error (please report) |

Each code has a fixed default title, a `whyItMatters` text, and default fixes in `ProblemCatalog` (pure data), which adapters extend with the concrete evidence.

### 27.4 Console output

Normal startup (colors when stdout is a TTY and `NO_COLOR` is unset):

```
🪔 oillamp 0.1.0 — lamp ~/lamps/feature-x (agent k3v9x2ab)
[host]    ✓ Ubuntu 26.04 LTS, Wayland (GNOME)
[host]    ✓ podman 5.4.2, rootless, crun
[lamp]    ✓ config valid — network: default allow, 1 rule, 1 forward (llm → llm.corp.example.com:8000)
[image]   ✓ localhost/oillamp/sandbox:3f2a9c1e0b7d4a55 (cached)
[session] ✓ container ready in 2.8 s — 1920×1080, renderer gles2, recording on
[session] ✓ viewer opened (TigerVNC)
[session] ✓ terminal opened (ptyxis), connected
[session] ● running — close the terminal window or press Ctrl+C to stop
14:22:05 [net] ✗ denied CONNECT 10.2.3.4:5432 — rule "block private, internal and loopback ranges"
```

A problem:

```
✗ OIL-CONTAINER-002  The sandbox container exited during startup
  What happened  container oillamp-k3v9x2ab stopped with exit code 70 before reporting ready
  Why it matters without the container there is no desktop and no shell
  Evidence       $ podman run --detach --name oillamp-k3v9x2ab …   (full command in the log)
                 container log, last 6 lines:
                   [entrypoint] sway exited (code 1) with renderer gles2 — retrying with pixman
                   [sway] … [ERROR] …
  Try            1. oillamp doctor ~/lamps/feature-x
                 2. set display.gpu = "off" in ~/lamps/feature-x/oillamp.toml and run again
  Full log       ~/lamps/feature-x/.oillamp/logs/oillamp-20260922-141503.log
```

`--verbose` additionally prints every step and command; `--debug` prints stack traces and all process output.

### 27.5 Exit codes

| Code | Meaning |
|---|---|
| 0 | success / clean session end |
| 1 | error (any `ERROR` problem not covered below) |
| 2 | usage or configuration error |
| 3 | host prerequisites missing and not installed |
| 4 | lamp busy (`OIL-LOCK-001`) |
| 5 | container/session failure |
| 130 | interrupted by the user before the session was running |

## 28. CLI specification

```
oillamp [--verbose] [--debug] [--no-color] <command>

  at <dir> [--init] [--dry-run] [--no-install] [--no-viewer] [--rebuild] [--reset-tool-config]
        Set up (if needed) and start a session. Foreground supervisor.
  doctor [<dir>]
        Run all host checks (and lamp/config checks if <dir> is given); print a report; change nothing.
  view <dir> [--view-only]
        Open another viewer for the running session.
  shell <dir>
        Open an additional SSH shell in the current terminal (does not end the session when closed).
  stop <dir>
        Ask the running supervisor to shut the session down (via control socket). Falls back to cleanup
        of a crashed session if no supervisor is running.
  status <dir>
        Print session state, uptime, container, renderer, network stats.
  list
        List running oillamp sessions on this host (podman ps by label oillamp.agent-id).
  recordings <dir> [--open <session>] [--prune]
        List recordings; open one; apply retention now.
  image (rebuild [<dir>] | prune | show [<dir>])
        Manage sandbox images.
  config <dir> (check | show-effective | path)
        Validate config; print the merged effective config; print file paths.
  version
```

`oillamp at` with the lamp already running prints `OIL-LOCK-001` with the hints `oillamp view <dir>`, `oillamp shell <dir>`, `oillamp stop <dir>`.

## 29. Concurrency model summary

- Virtual threads everywhere (`Executors.newVirtualThreadPerTaskExecutor()` per subsystem, closed on shutdown).
- The session state is owned by one event-loop thread; everything else communicates through the event queue or immutable values.
- The network journal and session log each have one writer thread fed by a queue.
- No `synchronized` blocks around I/O (not a pinning problem on Java 25, but unnecessary with this design).
- Structured concurrency is a preview API in Java 25 and MUST NOT be used (NFR-09).

## 30. Testing strategy

| Level | What | Tooling |
|---|---|---|
| **Scenarios (primary)** | user-visible behaviour only, driven through `OilLamp.run(argv)` against a described `Machine`: checking a machine, setting up a lamp, configuring one, using the command line. Each carries a `reportInfo` block saying *why the scenario exists*, and `./gradlew test` renders them to `build/spock-reports/*.md` as readable documentation. | **Spock**, from package `oillamp` — outside `dev.oillamp`, so internals are unreachable (§22.2) |
| Unit (pure) | reached *through* the scenarios rather than directly: config validation, policy engine, sub-ID allocation, retention, session machine, runtime.env quoting. A pure function with no scenario that needs it is a function with no user. | Spock |
| Golden files | `podman run` argv, `ssh_config`, agent guide, runtime.env, terminal commands per profile, viewer command, default config template round-trip, problem rendering | `src/test/resources/golden/**`, update with `-Dgolden.update=true` |
| Architecture | the five-public-types rule, no internals in public signatures, and the effects allowlist (§22.3) | ArchUnit, inside a Spock spec |
| Adapter | ProcessRunner (timeouts, descendants killed, tails), Unix socket relay, proxy against a local `com.sun.net.httpserver` + a local TCP echo server, JSONL journal | JUnit (no podman) |
| Integration (`@Tag("podman")`) | image builds; container becomes ready; `ssh … echo ok` via relay; `vncviewer` connects to `vnc.sock`; `lamp screenshot` returns a PNG of the right size; `curl https://example.com` inside succeeds via proxy; `curl http://10.0.0.1` gets 403; forward to a host-side test server works; `podman stop` produces a playable non-empty `.mkv`; agent cannot `kill` wf-recorder (`kill` returns EPERM) and cannot connect to sway IPC | run with `./gradlew integrationTest` on a real Ubuntu host |
| Manual E2E checklist | golden path §3 on Ubuntu 24.04 and 26.04 GNOME; terminal closed → cleanup; Ctrl+C; kill -9 of supervisor → next run cleans up; two lamps concurrently; long lamp path (> 150 chars) | `docs/e2e-checklist.md` |

## 31. Packaging and distribution

- `./gradlew :oillamp-cli:installDist` for development (`build/install/oillamp/bin/oillamp`).
- `./gradlew :oillamp-cli:jpackageDeb` produces `oillamp_<version>_amd64.deb`: bundled runtime via `jlink` (`--strip-debug --no-header-files --no-man-pages`, modules determined with `jdeps`; `java.desktop` is added once the GUI arrives), installed to `/opt/oillamp`, with a `postinst` symlink `/usr/bin/oillamp → /opt/oillamp/bin/oillamp`. The `.deb` has no hard dependencies; oillamp installs its prerequisites itself (FR-60).
- The `lamp-helper` jar is built first and bundled into `oillamp-image` resources; changing it changes the image hash and triggers a rebuild automatically.
- Versioning: SemVer; `oillamp version` prints oillamp, Java runtime, and the image context hash.

---

# Part IV — Delivery

## 32. Milestones

Each milestone ends with its tests green and a short demo.

**M1–M5 are built and verified. M0, M6 and M7 are not started.** `docs/STATUS.md` has the
current detail; the Status column here is the summary.

| # | Milestone | Status | Scope | Done when |
|---|---|---|---|---|
| **M0** | Spikes | ⬜ not started — **S11 resolved** (§36.3), 13 spikes open | all items in §33, as throwaway scripts in `spikes/` | every ⚠ VERIFY item is confirmed or replaced by its fallback, and this spec is updated |
| **M1** | Skeleton and host | ✅ **built and verified** | Gradle setup with ArchUnit rules (single module, D-26); `Result`/`Problem`/catalog; ProcessRunner; HostProbe; HostPlanner incl. APT install and subuid fix; `doctor`; ConsoleRenderer; Journal | `oillamp doctor` on a fresh Ubuntu VM reports and (via `at --dry-run`) plans the right fixes; unit + golden tests |
| **M2** | Lamp and config | ✅ **built and verified** | lamp classification/init/migration, layout, lock + stale detection, TOML config pipeline with all validations, key generation, rendering of `session/`, `ssh_config`, runtime dir | `oillamp at <new dir> --dry-run` prints a complete, correct plan; config errors are reported all at once with key paths |
| **M3** | Image and container | ✅ **done** — `oillamp at` builds the image (content-hash tag), starts the container and waits for `ready.json`. `--dry-run` shows the whole plan including every podman flag | image resources, build hash, `podman build`; container spec + run; entrypoint with sway, wayvnc, ssh listener, readiness | `oillamp at` starts a container that reaches ready; `vncviewer <socket>` shows the desktop; ssh via socket works |
| **M4** | Supervisor | ✅ **done** — `oillamp at` runs a session: two new windows, both relays, the control socket, the §10.7 shutdown, and the five session commands. Verified on real podman for all three endings, plus `kill -9` recovery | session machine, SSH relays, terminal and viewer launchers with profiles, control socket, shutdown sequence, `view`/`shell`/`stop`/`status`/`list` | the full golden path §3 works without network features; closing the terminal cleans up everything; kill -9 recovery works |
| **M5** | Network | ✅ **done** — the egress proxy is up: `CONNECT` and absolute-form HTTP, per-address policy, forwards, the JSONL journal and denials on the console. Verified on real podman: `npm install`, `pip install` and `git clone` over HTTPS all work from inside the sandbox, while the host's loopback and the LAN are refused by rule. *Firefox policy and LLM preconfiguration are not done* | proxy, policy engine, forwards, network journal, in-container proxy env, Firefox policy, ssh-over-proxy, LLM preconfiguration | integration tests for allow/deny/forward pass; OpenCode and pi talk to the configured LLM |
| **M6** | Recording and agent tooling | ⬜ not started — *retention already built* | wf-recorder, retention, `recordings` command; `lamp` helper script (D-27); agent guide; GPU auto mode with fallback | `lamp screenshot/click/type` work; recordings are playable; the agent cannot kill infra processes |
| **M7** | Packaging and polish | ⬜ not started | jpackage `.deb`, completion scripts, README, E2E checklist on Ubuntu 24.04 + 26.04 | acceptance criteria §34 all pass |

## 33. Verification spikes (⚠ VERIFY items)

**Eleven of the fourteen are resolved — S1, S2, S3, S4, S6, S7, S8, S9, S11, S12, S13 — and
every one of them held.** Not a single fallback in the right-hand column was needed. S5 (GPU),
S10 (agent tools) and S14 (wayvnc desktop name) remain open.

They are now executable rather than historical: `./gradlew spikes` builds the real image, starts
the real container, logs in over SSH and drives the desktop, in about a minute. They are
scenarios rather than throwaway scripts precisely because an assumption that was true once can
stop being true — a podman or trixie upgrade is exactly the event that should re-ask these
questions. They are tagged `spike` and excluded from `test`, which stays fast, offline and green
without them.

| # | Status | Assumption | How to verify | Fallback if false |
|---|---|---|---|---|
| S1 | ✅ **confirmed** — vncviewer takes the socket path directly; no TCP bridge needed | TigerVNC `vncviewer` accepts a Unix socket path as the server argument (documented in its man page) and supports `AcceptClipboard`, `SendClipboard`, `SendPrimary`, `Shared`, `ViewOnly`, `RemoteResize` in the Ubuntu-packaged version | run against wayvnc `-u` socket | bridge with `socat TCP-LISTEN:<random>,bind=127.0.0.1 UNIX-CONNECT:…` owned by the supervisor (loopback only, per-session random port, VNC password enabled) |
| S2 | ✅ **confirmed** — non-root `sshd -i` works; no dropbear needed | Non-root `sshd -i` works with trixie's OpenSSH (sshd-session split) for the same user | spike container | `dropbear -i -s -j -k` with equivalent restrictions |
| S3 | ✅ **confirmed** — absolute `WAYLAND_DISPLAY` accepted | Clients accept `WAYLAND_DISPLAY` as an absolute path | run `foot`, GTK, and Firefox as `agent` | symlink into `/run/agent` and relative name |
| S4 | ✅ **confirmed** — custom mode honoured, windows render | sway headless honours `output HEADLESS-1 mode --custom WxH` and `scale`; Xwayland starts on demand; a Swing app renders and receives input via VNC with `_JAVA_AWT_WM_NONREPARENTING=1` | spike with a Swing hello-world incl. a modal dialog | `WLR_HEADLESS_OUTPUTS` + `swaymsg create_output`/`output` from the entrypoint (entrypoint as `lamp` may use the IPC socket) |
| S5 | ⬜ open | GPU mode: `--device` render node + `--group-add keep-groups` + `setpriv --keep-groups` lets `lamp` and `agent` open the render node; `WLR_RENDERER=gles2` works headless | spike on Intel/AMD laptop | GPU only for `lamp` (compositor), software GL for agent apps; or `gpu=off` default |
| S6 | ✅ **confirmed** — `.mkv` playable after shutdown; no segmenting needed | wf-recorder flags (`--codec`, frame-rate limit, codec params) and that `.mkv` is playable after SIGINT and after SIGKILL | spike | adjust flags; if unplayable after SIGKILL, segment recordings (restart recorder every N minutes) |
| S7 | ✅ **confirmed** — all present, `wlrctl` 0.2.2 included | trixie has `sway xwayland wayvnc wf-recorder grim wtype wlrctl firefox-esr`; Adoptium APT repo supports trixie; Firefox ESR policies path | `podman run debian:trixie apt-cache policy …` | drop `wlrctl`; install Temurin from tarball; find policies path with `dpkg -L firefox-esr` |
| S8 | ✅ **confirmed** | Rootless Podman works out of the box on Ubuntu 24.04 and 26.04 with the AppArmor userns restriction (profiles shipped) | fresh VMs | document remedies in `OIL-PODMAN-004` text precisely |
| S9 | 🟡 **verified for the terminals on the dev host**; the rest are still guesses | Terminal argument templates (§17.4) | smoke-test each installed terminal | adjust the profile table (it's data) |
| S10 | 🟡 **half resolved** — package names, binaries and pi's agent-directory layout confirmed against a real image (`@earendil-works/pi-coding-agent` → `/usr/bin/pi` 0.87.1; `opencode-ai` → `/usr/bin/opencode` 1.18.32; the Eden AI extension only via `git:`, not `npm:`). The proxy half needs M5 | OpenCode/pi npm package names, binaries, global config/instruction file paths and schemas; whether their HTTP stacks honour `HTTPS_PROXY`/`NODE_USE_ENV_PROXY` | install in the image, run against a test server | adjust templates; for tools ignoring proxy env, document that only forwards reach them |
| S11 | ✅ **resolved** — Jackson 2.x, classpath mode | Jackson 3.x provides a TOML dataformat module; Sprouts API names; Sprouts usable as a JPMS (automatic) module with jlink/jpackage | build a hello-world with jpackage | Jackson 2.x; classpath mode for jpackage |
| S12 | ✅ **confirmed** — both socket directions | Bind mounts onto pre-created mount points work with `--read-only`; Unix sockets in bind-mounted dirs are connectable across the user namespace in both directions with the permissions of §9.2 | spike | adjust modes (e.g. 0777 dirs inside the 0700 state dir) |
| S13 | ✅ **confirmed** | `--userns=keep-id:uid=1000,gid=1000` maps container 1000 → host user, and `podman unshare chown 1001:1001` produces the subuid container uid 1001 sees as its own | spike | compute subuid manually from `/etc/subuid` and use `podman unshare` with numeric ids |
| S14 | ⬜ open | wayvnc can set the desktop name (window title of the viewer) | read man page of the packaged version | ignore |

## 34. Acceptance criteria

Three of the fifteen can be checked today (12, 13, and the non-integration half of 15). The
other twelve all need a running container, so they are gated on M3 and M4. None has been
weakened; they are the same criteria the finished tool must meet.

1. ⬜ On a fresh Ubuntu 24.04 (and 26.04) GNOME installation with only the `.deb` installed, `oillamp at ~/lamps/a` installs prerequisites (one sudo prompt), builds the image, and opens a terminal (logged in as `agent` in `~/workspace`) and a viewer showing a 1920×1080 desktop. No manual step besides the sudo password.
2. ⬜ A second `oillamp at ~/lamps/a` while the first runs fails with `OIL-LOCK-001` and exit code 4; `oillamp at ~/lamps/b` starts an independent second sandbox.
3. ⬜ Closing the terminal window stops and removes the container within 20 s, finalizes a playable `.mkv`, removes sockets and the runtime dir, prints a summary, and exits 0.
4. ⬜ `kill -9` of the supervisor, followed by `oillamp at` on the same lamp, cleans up the leftover container with a `OIL-LOCK-002` warning and starts normally.
5. ⬜ In the sandbox, `ls /` shows no host files; the only host paths visible are those of §13.3. Files created by the agent in `~` appear on the host in `agent-lamp-<id>/` owned by the host user.
6. ⬜ As `agent`: `kill` of any `lamp`-owned process fails; connecting to sway's IPC socket fails; `wayvncctl` cannot reach wayvnc; writing to `/oillamp/recordings` fails; reading `oillamp.toml` is impossible (not mounted).
7. 🟡 `curl -I https://example.com` succeeds; `curl -I http://10.0.0.1` and `curl -I http://<host LAN IP>:22` return 403 with the rule label; `curl` with `--noproxy '*'` fails (no network). All are in `network-<session>.jsonl`; denials appear on the supervisor console.
8. ⬜ With a forward `llm` configured, `curl http://127.0.0.1:<port>/v1/models` inside reaches the target; OpenCode and pi, started without further setup, can chat with the configured model.
9. ⬜ A Swing test application (with a modal dialog) started from the SSH terminal appears on the agent's desktop and in the viewer; `lamp screenshot` produces a PNG showing it; `lamp click` on its button triggers the action; `lamp type` enters text into a text field.
10. ⬜ Firefox ESR opens on the desktop and loads a public website through the proxy.
11. ⬜ A native library copied to `agent-lamp-<id>/libs/` is loadable by the Swing app via `System.loadLibrary` without extra flags.
12. ✅ An invalid `oillamp.toml` (unknown key + bad CIDR + duplicate forward) produces exactly three `OIL-CONFIG-*` problems in one run, each with file, key path, value, and expectation; exit code 2.
13. ✅ `oillamp at <dir> --dry-run` on a new directory prints every step (including the podman argv) and changes nothing on disk.
14. ⬜ With `display.gpu = "on"` on a machine without a usable render node, a clear `OIL-GPU-003` is shown; with `auto`, the session starts in software mode and says why.
15. 🟡 All unit, golden, architecture, and adapter tests pass in CI; integration tests pass on a real Ubuntu host. *(89 fast scenarios, 7 spikes and the architecture rules pass. Integration tests need podman.)*

## 35. Remaining open points (non-blocking)

- Company-specific values: Java root package name, LLM endpoint(s) and auth, internal registry mirror for the base image, internal package mirrors to allow in the default policy. These are configuration, not design; ship neutral defaults.
- Whether to add a company-wide `~/.config/oillamp/config.toml` distribution mechanism (e.g. shipped in the `.deb` under `/etc/oillamp/config.toml` as a system-level layer between built-in and user-global). Easy to add to §20.1 if wanted.

---

# Appendices — file templates

These are starting points. Spike results (§33) take precedence.

## 36. Implementation amendments

This section is the record required by §6: *"Implementers MUST NOT silently change these
decisions. If one proves infeasible, document the reason and the replacement here."* Every
deviation taken while building M1–M4 is listed below with the reason. Nothing here was
changed for convenience alone.

### 36.1 Structure

| Was | Is | Why |
|---|---|---|
| 8 Gradle modules (§22) | 1 module | The module boundaries existed to enforce purity and dependency direction. One ArchUnit spec enforces both, per class rather than per module, which is stricter. The modules cost eight build files, eight `module-info.java` files and a JPMS question (S11) for no enforcement the tests do not already provide. **D-26** |
| ~12 packages, ~50 public types | 1 package, **5 public types** | A small public API is only real if the compiler enforces it. See §22.1–22.3. **D-26** |
| Kotlin DSL | Groovy DSL | The test sources are Groovy (Spock). One language fewer in the build. **D-19** |
| picocli | ~120 lines of hand-rolled parsing | Nine subcommands, and §28 needs exit code 2 on precisely the usage errors it lists. One fewer dependency on the golden path. **D-19** |
| Wire model layer (§24.6) — Jackson binds to wire records, then validate | The config reader walks the merged JSON tree directly | Walking the tree *keeps the key path* (`network.rules[0].cidrs`), so a problem can say exactly where it is. Bind-then-validate loses that and needs a second parallel set of record types to get it back. Less code, better errors. |
| `oillamp-rfb` + `lamp-helper` (a Java RFB 3.8 client) | A shell script over `grim`/`wtype`/`wlrctl` | **D-27.** The image already installs the tools; the Java path bought nothing and cost a protocol implementation, PNG encoding, AppCDS tuning and a jar-into-image-context build coupling. |

### 36.2 Behaviour

- **`LampEvent.Answer` added.** `config show-effective` and similar commands answer a question the
  user asked. That is not "progress output" and must not be hidden behind `--verbose` — otherwise
  the command prints nothing, which is what it did before this event type existed.
- **Phase B plans in two passes.** Keys must exist on disk before `authorized_keys` and
  `known_hosts` can contain them. A single pass would force a step to compute its own content at
  execution time, which would make `--dry-run` a lie — it would print a plan whose contents did
  not yet exist. §10.5's phase list is unchanged; only the planning within Phase B is split.
- **`--dry-run` no longer requires working sudo.** A dry run changes nothing, so the state of
  `sudo` is irrelevant to producing a plan. Previously `oillamp at --dry-run` on a machine whose
  sudo needs a password was refused with `OIL-PKG-002` — refusing to *describe* work on the
  grounds that it could not be *performed*. `HostPlanner.Options.willExecute` distinguishes them.
- **Podman checks are skipped when podman is among the missing packages.** `doctor` reported both
  `OIL-PKG-001` ("podman is not installed") and `OIL-PODMAN-001` ("podman did not answer") for the
  same single fact. One fact, one problem.
- **A directory containing only `oillamp.toml` is not "foreign".** §10.1 classifies a non-empty
  directory as someone else's. But writing the config *before* the first `oillamp at` is the
  documented way to configure a lamp, so `oillamp.toml` and `README.txt` are ignored when deciding
  whether a directory is empty.
- **`Problems.crash` and a top-level catch.** NFR-03 says no bare stack traces on the console. A
  bug in oillamp itself is still an error the user sees, so `RuntimeException` and
  `StackOverflowError` become `OIL-INTERNAL-001`, with the advice to re-run with `--debug`.

- **Three problem codes added to §27.3.** `OIL-TERM-003` (the terminal emulator would not start
  at all, which is a different failure from `OIL-TERM-002`, the terminal that started and never
  connected — and has a different fix), and `OIL-SESSION-001` / `OIL-SESSION-002` for the
  commands of §26.6, which need to distinguish "no session is running here" from "a session's
  control socket is there but nothing answers it", because only the second means a supervisor
  died without tidying up.
- **`Machine` gained one method: `launch(Command, Stdio)`.** §26.1 describes running a command to
  completion, which is the wrong shape for a terminal window, a viewer or an interactive shell —
  all three outlive the call and have no timeout that would mean anything. Keeping them on `run`
  would have meant oillamp blocking for the length of a session on a command it started.
- **The closing summary is emitted by the supervisor, not returned by the session machine.**
  §25.1 lists `Emit(summary)` as an action of the final transition. The machine knows the
  *reason* a session ended, which is what the exit code needs, but the duration, the recording
  and what the shutdown managed to clean up are facts only the imperative side has. The machine
  stays pure; the summary is assembled where its inputs are.
- **`ShutdownReason.UserInterrupt` carries whether the session had reached `Running`.** §27.5
  distinguishes "interrupted before the session was running" (130) from a normal end (0), and
  that distinction has to be captured when the interrupt happens rather than looked up later,
  when the state has already moved on.
- **`agent_tools.install` is now passed to the image build.** The Containerfile always had an
  `AGENT_TOOLS` argument and oillamp never supplied it, so a lamp that configured its harnesses
  was silently building the default. It is part of the content hash now, which is the only way a
  changed list can reach a sandbox that has no network to install anything with.
- **The harnesses are installed at build time, and pi's Eden AI extension with them.** §19.5
  describes configuring tools that are present; it does not say how they get there. Since FR-40
  leaves the sandbox with no network of its own, `pi install` cannot work at runtime — so the
  extension is fetched during the build into `/usr/local/share/oillamp/pi` (via pi's own
  `PI_CODING_AGENT_DIR`) and copied into the agent's home at session start, because that home is
  a bind mount and would otherwise hide anything the image put there. None of it may fail the
  build or the session: a sandbox without a harness still has a desktop, a shell and a recording.

- **`recording.enabled` now defaults to `false` (§16).** The specified default was `true`. A
  continuous screen recording of everything an agent does is an audit trail a user should switch
  on deliberately, not find already running; the cost argument the old default rested on (frames
  only on damage) is true but answers the wrong question. Every other recording default is
  unchanged, and the session briefing states which of the two is in force.
- **Everyday shell tooling is part of the base image layer.** §15.2 lists the desktop and the
  toolchain and takes the ordinary shell for granted, so the image had no `ping`, no editor and
  no way to look at a process. Thirty packages of it now install before the desktop layer and
  outside `WITH_TOOLCHAIN`, because an agent that cannot diagnose its own environment spends its
  turns guessing at it.
- **Cleanup commands run shielded from the terminal's signals.** §10.7 describes the shutdown
  sequence without saying who may interrupt it. oillamp's children share the launching terminal's
  process group, so a second Ctrl-C — the natural response to a shutdown that takes a moment —
  killed the `podman stop` that was finalising the recording. `podman stop` and `podman rm` now
  run under `setsid --wait`, and the sequence is judged by whether the container is gone rather
  than by each command's exit code, with a new `OIL-SANDBOX-005` for the case where it is not.
- **The in-sandbox banner asks before claiming network.** Appendix E stated "network via policy
  proxy" unconditionally, which is false in every build before M5 and whenever the proxy is not
  listening. It now probes the proxy port and reports what it finds.

- **`pip` needs `PIP_USER` and `PIP_BREAK_SYSTEM_PACKAGES` set (§18.6, Appendix E).** §18.6 lists
  the proxy variables and assumes that is enough for a package manager to work. It is not enough
  for pip on Debian: PEP 668 marks the system Python externally-managed and refuses to install
  into it, and the container's read-only root filesystem rules out the usual escape of installing
  system-wide anyway. Both variables are now set in the login environment, which sends pip into
  `~/.local` — the agent's own home, so writable and kept between sessions.
- **The agent guide claimed `pi install` could not work.** True before M5 and false after it, and
  it named an `extensions/` directory that pi does not create. Corrected to the real layout
  (`settings.json` plus `git/`) and to the fact that installing now works and persists.

### 36.3 Defects found in this specification

- **§10.2's example `agentId` `k3v9x2ab` is invalid under its own alphabet.** The identifier is
  specified as RFC 4648 lowercase base32, which is `[a-z2-7]` — it has no `8` or `9`. Examples
  using it were changed to `k3v7x2ab`.
- **S11 is resolved, in favour of the fallback.** Jackson 3.x has no TOML dataformat module;
  Jackson 2.20 does. The build uses Jackson 2.x, and `jpackage` runs in classpath mode.
- **§15.3 assumes `crun`, but Ubuntu 24.04 ships podman 4.9.3 with `runc`.** Found on the first
  real-hardware run, not in any simulation. GPU passthrough needs `crun` for `--keep-groups`, so
  on a stock install of the *primary target platform* the desktop silently fell back to software
  rendering — on the default setting (`display.gpu = auto`), with nothing obviously wrong to
  search for. **Resolved:** `crun` is now a required package in §11.1, installed alongside podman,
  with the reason carried in the step detail. A scenario pins it so it is not later removed as
  redundant — which it looks like, because podman does run without it.
- **`--verbose` parsed correctly and did nothing.** `ConsoleRenderer.verbose(…)` returned a
  *copy*, and the event sink was holding the original, so the flag had never once changed the
  output. Found while trying to read the `podman run` arguments a dry run had just planned. The
  renderer now carries the flag itself, and a planned step prints its full detail under
  `--verbose`, which is where those arguments live.
- **`--dry-run` stopped before the part most worth inspecting.** It returned after Phase B, so the
  container was never even planned — the `podman run` flags *are* the sandbox's guarantees, and
  someone checking that `--network=none` is really passed should not have to read the source. It
  now plans Phase C too, and still takes no lock, so it cannot block a running session.

- **`setpriv` changes the user but not the environment.** Every process the entrypoint drops
  inherited root's `HOME=/root`, which is mode 0700 and owned by root, so each one was denied its
  own home directory. wayvnc called this *"Failed to load config. Permission denied"* and
  fontconfig called it *"No writable cache directories"*; neither mentioned `HOME`. Appendix B's
  `drop` now sets `HOME`, `XDG_CACHE_HOME` and `XDG_CONFIG_HOME` per user.
- **`${gpu_fallback:+…}` tests for a non-empty string, and `"false"` is one.** A session that
  never attempted the GPU reported that it had fallen back from it — a lie about the one thing
  §19.3 tells the agent to trust about its renderer. Now compared as `= true`.
- **sshd forwards no locale, and the image's `ENV LANG` does not reach a login shell.** Every GUI
  application in the sandbox started with *"'C' is not a UTF-8 locale"*. Found by reading the
  first screenshot ever taken inside the sandbox. Appendix E now sets it.
- **`lamp info` promised a window list it cannot produce.** sway's IPC socket is mode 0700 and
  owned by `lamp`, deliberately (§16) — reaching it would mean running commands as the user that
  owns the recording. The command now says so instead of printing an empty heading.

- **Rootless podman on Ubuntu 24.04 has no networking backend.** `podman run` fails outright
  with *"could not find slirp4netns, the network namespace can't be configured"*. The sandbox
  runs `--network=none` (FR-40) and does not care, but **building the image does**, because that
  is where apt-get runs — so this would have looked like "M3 works here" and failed for every
  user who had not built the image before. `slirp4netns` is now a required package in §11.1.
- **A directory handed to the infra user cannot be removed with plain `rm -rf`.** After
  `podman unshare chown 1001:1001`, the host user does not own the resulting subuid and gets
  *"Operation not permitted"*. Teardown of a lamp, and any cleanup path in M4, must go through
  `podman unshare rm -rf` — and so must the advice given to a user removing a lamp by hand.

- **The remedy for missing packages named a flag the user had not passed.** `doctor` suppressed
  installing through the same boolean that `--no-install` sets, so it advised the user to "drop
  --no-install" — implying oillamp could not install packages at all, the opposite of FR-60. The
  two reasons are now distinct (`Installing.DECLINED` vs `Installing.NEVER`) and `doctor` points
  at `oillamp at` instead. A boolean that answers "may I?" cannot also answer "why not?".

### 36.4 Status

**M1 and M2 are implemented and verified end to end on real hardware — a stock Ubuntu 24.04.5
machine with none of the prerequisites installed, through the sudo prompt to a populated lamp
directory. M0 and M3–M7 are not started.**

Status is recorded in three places, all of which describe the code as it stands rather than the
intent:

- **§4 and §5** — every requirement carries ✅, 🟡 or ⬜, and each 🟡 says which part is built.
- **§32, §33 and §34** — milestones, verification spikes and acceptance criteria, same markers.
- **`docs/STATUS.md`** — the detail: what each command does today, what each milestone still
  needs, how the 75 classes are laid out, and how to run what exists.

Of the fifteen acceptance criteria, three can be checked today; the other twelve need a running
container. None has been weakened to fit what was built.

## Appendix A — `Containerfile` (sketch)

```dockerfile
ARG BASE_IMAGE=docker.io/library/debian:trixie
FROM ${BASE_IMAGE}

ARG JDK_PACKAGE=temurin-25-jdk
ARG NODE_MAJOR=24
ARG AGENT_TOOLS=""            # e.g. "opencode-ai@latest @mariozechner/pi-coding-agent@latest"
ARG EXTRA_APT_PACKAGES=""
ENV DEBIAN_FRONTEND=noninteractive LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8

RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl gnupg locales \
 && sed -i 's/^# *en_US.UTF-8/en_US.UTF-8/' /etc/locale.gen && locale-gen \
 && install -d -m 0755 /etc/apt/keyrings \
 && curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public | gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg \
 && echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb trixie main" \
      > /etc/apt/sources.list.d/adoptium.list

RUN apt-get update && apt-get install -y --no-install-recommends \
      bash coreutils util-linux procps less file tzdata \
      sway xwayland wayvnc wf-recorder grim slurp wtype wlrctl foot dbus at-spi2-core xdg-utils \
      mesa-utils libgl1-mesa-dri libegl1 libgles2 mesa-vulkan-drivers \
      fonts-dejavu fonts-liberation2 fonts-noto-core adwaita-icon-theme \
      openssh-server socat \
      git wget unzip zip jq ripgrep fd-find build-essential cmake pkg-config gdb strace \
      python3 python3-venv python3-pip \
      firefox-esr libatk-wrapper-java \
      ${JDK_PACKAGE} ${EXTRA_APT_PACKAGES} \
 && rm -rf /var/lib/apt/lists/*

COPY build/ /tmp/oillamp-build/
RUN /tmp/oillamp-build/install-node.sh "${NODE_MAJOR}" \
 && /tmp/oillamp-build/install-agent-tools.sh ${AGENT_TOOLS} \
 && rm -rf /tmp/oillamp-build

RUN groupadd -g 1000 agent && useradd -u 1000 -g 1000 -d /home/agent -M -s /bin/bash agent \
 && groupadd -g 1001 lamp  && useradd -u 1001 -g 1001 -d /var/lib/lamp -M -s /usr/sbin/nologin lamp \
 && mkdir -p /oillamp/session /oillamp/sockets /oillamp/recordings /home/agent /var/lib/lamp \
 && chown lamp:lamp /var/lib/lamp

COPY rootfs/ /
# rootfs/ contains: usr/local/lib/oillamp/entrypoint, usr/local/lib/oillamp/lamp-helper.jar,
# usr/local/bin/lamp, etc/oillamp/sway/config, etc/oillamp/sshd_config, etc/profile.d/oillamp.sh,
# etc/ssh/ssh_config.d/50-oillamp-proxy.conf, usr/lib/firefox-esr/distribution/policies.json

RUN java -XX:ArchiveClassesAtExit=/usr/local/lib/oillamp/lamp-helper.jsa -jar /usr/local/lib/oillamp/lamp-helper.jar --selftest \
 ; find / -xdev -perm /6000 -type f -exec chmod ug-s {} + \
 ; rm -f /etc/ssh/ssh_host_*

ENTRYPOINT ["/usr/local/lib/oillamp/entrypoint"]
```

## Appendix B — `entrypoint` (sketch)

```bash
#!/usr/bin/env bash
set -Eeuo pipefail
source /oillamp/session/runtime.env
log() { printf '[entrypoint] %s\n' "$*"; }

# drop <user> <umask> <tag> <cmd...>
# Starts <cmd> in the background as <user>, output prefixed with [tag]. Sets LAST_PID to the PID of <cmd> itself.
# Must NOT be called inside $(...): the process has to be a direct child of this shell so `wait -n` works.
drop() {
  local user=$1 mask=$2 tag=$3; shift 3
  local groups_opt=--init-groups; [[ "${OILLAMP_RENDERER}" == gles2 ]] && groups_opt=--keep-groups
  ( umask "$mask"; exec setpriv --reuid="$user" --regid="$user" $groups_opt \
      --inh-caps=-all --ambient-caps=-all --bounding-set=-all -- "$@" ) \
    > >(sed -u "s/^/[$tag] /") 2>&1 &
  LAST_PID=$!
}

install -d -o lamp  -g lamp  -m 0711 /run/lamp
install -d -o lamp  -g lamp  -m 0700 /run/lamp/private
install -d -o agent -g agent -m 0700 /run/agent
printf 'output HEADLESS-1 mode --custom %sx%s scale %s\n' \
  "$OILLAMP_DISPLAY_WIDTH" "$OILLAMP_DISPLAY_HEIGHT" "$OILLAMP_DISPLAY_SCALE" > /run/lamp/output.conf

start_sway() {
  drop lamp 077 sway env XDG_RUNTIME_DIR=/run/lamp WLR_BACKENDS=headless WLR_LIBINPUT_NO_DEVICES=1 \
       WLR_RENDERER="$1" sway -c /etc/oillamp/sway/config
  SWAY_PID=$LAST_PID
}
renderer=$OILLAMP_RENDERER; gpu_fallback=false
start_sway "$renderer"
# wait for /run/lamp/wayland-1 (≤ 20 s). If sway exits first and renderer == gles2:
#   renderer=pixman; gpu_fallback=true; start_sway pixman; wait again. Otherwise: log and exit 70.
chmod 0666 /run/lamp/wayland-1           # sway's IPC socket stays 0700

drop lamp 000 wayvnc env XDG_RUNTIME_DIR=/run/lamp WAYLAND_DISPLAY=wayland-1 \
     wayvnc --unix-socket --socket=/run/lamp/private/wayvncctl --render-cursor \
            --max-fps="$OILLAMP_VNC_MAX_FPS" --output=HEADLESS-1 /oillamp/sockets/infra/vnc.sock
VNC_PID=$LAST_PID

REC_PID=""
if [[ "$OILLAMP_RECORDING_ENABLED" == true ]]; then
  drop lamp 022 rec env XDG_RUNTIME_DIR=/run/lamp WAYLAND_DISPLAY=wayland-1 \
       wf-recorder --output=HEADLESS-1 --codec="$OILLAMP_RECORDING_CODEC" \
                   --file="/oillamp/recordings/${OILLAMP_SESSION}.mkv"   # + fps/crf flags per spike S6
  REC_PID=$LAST_PID
fi

BRIDGE_PIDS=()
drop lamp 077 proxy socat TCP-LISTEN:3128,bind=127.0.0.1,reuseaddr,fork UNIX-CONNECT:/oillamp/sockets/host/proxy.sock
BRIDGE_PIDS+=("$LAST_PID")
for fwd in $OILLAMP_FORWARDS; do           # "name:port name:port …" (names validated [a-z0-9-]+)
  name=${fwd%%:*}; port=${fwd##*:}
  drop lamp 077 "fwd-$name" socat "TCP-LISTEN:$port,bind=127.0.0.1,reuseaddr,fork" \
       "UNIX-CONNECT:/oillamp/sockets/host/fwd-$name.sock"
  BRIDGE_PIDS+=("$LAST_PID")
done

AGENT_PIDS=()
drop agent 077 dbus dbus-daemon --session --address=unix:path=/run/agent/bus --nofork --nopidfile
AGENT_PIDS+=("$LAST_PID")
drop agent 077 sshd socat UNIX-LISTEN:/oillamp/sockets/agent/ssh.sock,fork,unlink-early,mode=600 \
     EXEC:"/usr/sbin/sshd -i -f /etc/oillamp/sshd_config"
AGENT_PIDS+=("$LAST_PID")

# wait until vnc.sock and ssh.sock exist (≤ 10 s), then write ready.json as lamp:
# setpriv --reuid=lamp --regid=lamp --init-groups -- sh -c 'cat > /oillamp/sockets/infra/ready.json' <<< "{…renderer, gpu_fallback, size…}"

CRITICAL=("$SWAY_PID" "$VNC_PID" ${REC_PID:+"$REC_PID"} "${BRIDGE_PIDS[@]}")

stop_all() {   # stop_all <exit code>
  trap - TERM INT
  if [[ -n "$REC_PID" ]]; then
    kill -INT "$REC_PID" 2>/dev/null || true
    timeout 10 tail --pid="$REC_PID" -f /dev/null || log "recorder did not finish in time"
  fi
  kill -TERM "${CRITICAL[@]}" "${AGENT_PIDS[@]}" 2>/dev/null || true
  sleep 2   # grace period; catatonit reaps and kills the rest when this script exits
  exit "$1"
}
trap 'log "shutdown requested"; stop_all 0' TERM INT

set +e
wait -n "${CRITICAL[@]}"; code=$?
log "a critical process exited (code $code) — stopping sandbox"
stop_all 70
```

## Appendix C — `/etc/oillamp/sway/config`

```
include /run/lamp/output.conf
output * bg #1f2430 solid_color
xwayland enable
default_border normal
default_floating_border normal
focus_follows_mouse no
input * accel_profile flat
for_window [window_type="dialog"] floating enable
for_window [window_role="dialog"] floating enable
for_window [window_role="pop-up"] floating enable
set $mod Mod4
bindsym $mod+Left  focus left
bindsym $mod+Right focus right
bindsym $mod+Up    focus up
bindsym $mod+Down  focus down
bindsym $mod+f     fullscreen toggle
bindsym $mod+space floating toggle
# NO exec, NO exit, NO reload bindings — keyboard input must never run commands as `lamp`.
```

## Appendix D — SSH

`/etc/oillamp/sshd_config`:

```
HostKey /oillamp/session/ssh_host_ed25519_key
AuthorizedKeysFile /oillamp/session/authorized_keys
AllowUsers agent
PubkeyAuthentication yes
PasswordAuthentication no
KbdInteractiveAuthentication no
UsePAM no
PermitRootLogin no
PermitUserEnvironment no
StrictModes no
AllowTcpForwarding no
AllowStreamLocalForwarding no
AllowAgentForwarding no
X11Forwarding no
PermitTunnel no
PrintMotd no
PidFile none
Subsystem sftp internal-sftp
```

`<lamp>/.oillamp/ssh_config`:

```
Host lamp-<agentId>
  User agent
  HostName lamp-<agentId>
  IdentityFile <lamp>/.oillamp/keys/client_ed25519
  IdentitiesOnly yes
  UserKnownHostsFile <lamp>/.oillamp/known_hosts
  StrictHostKeyChecking yes
  ServerAliveInterval 15
  ForwardAgent no
  ForwardX11 no
  ClearAllForwardings yes
  LogLevel ERROR
```

Terminal command (argv, rendered by `Ssh.clientArgv`):

```
ssh -F <lamp>/.oillamp/ssh_config
    -o ProxyCommand="socat - UNIX-CONNECT:$XDG_RUNTIME_DIR/oillamp/<agentId>/run/ssh-primary.sock"
    -t lamp-<agentId> "cd ~/workspace && exec bash -l"
```

`/etc/ssh/ssh_config.d/50-oillamp-proxy.conf` (outbound SSH from inside the sandbox):

```
Host * !lamp-*
  ProxyCommand socat - PROXY:127.0.0.1:%h:%p,proxyport=3128
```

## Appendix E — `/etc/profile.d/oillamp.sh` (sketch)

```bash
[ -r /oillamp/session/runtime.env ] && set -a && . /oillamp/session/runtime.env && set +a
export XDG_RUNTIME_DIR=/run/agent
export WAYLAND_DISPLAY=/run/lamp/wayland-1 DISPLAY=:0 XDG_SESSION_TYPE=wayland
export GDK_BACKEND=wayland,x11 QT_QPA_PLATFORM='wayland;xcb' MOZ_ENABLE_WAYLAND=1
export _JAVA_AWT_WM_NONREPARENTING=1
export DBUS_SESSION_BUS_ADDRESS=unix:path=/run/agent/bus
export HTTP_PROXY=http://127.0.0.1:${OILLAMP_PROXY_PORT} HTTPS_PROXY=http://127.0.0.1:${OILLAMP_PROXY_PORT}
export http_proxy=$HTTP_PROXY https_proxy=$HTTPS_PROXY NO_PROXY=localhost,127.0.0.1,::1 no_proxy=localhost,127.0.0.1,::1
export NODE_USE_ENV_PROXY=1
export LD_LIBRARY_PATH="$HOME/libs${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export JAVA_TOOL_OPTIONS="-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=${OILLAMP_PROXY_PORT} -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=${OILLAMP_PROXY_PORT} -Dhttp.nonProxyHosts=localhost|127.0.0.1 -Djava.library.path=$HOME/libs"
export PATH="$HOME/.local/bin:$PATH"
[ -t 1 ] && [ -z "${OILLAMP_BANNER_SHOWN:-}" ] && export OILLAMP_BANNER_SHOWN=1 && cat <<EOB
🪔 lamp ${OILLAMP_LAMP_NAME} — desktop ${OILLAMP_DISPLAY_WIDTH}x${OILLAMP_DISPLAY_HEIGHT} (${OILLAMP_RENDERER}), network via policy proxy
   Read ~/AGENTS.md for how this sandbox works. Put repos in ~/workspace, native libs in ~/libs.
EOB
```

## Appendix F — Firefox ESR `policies.json`

```json
{
  "policies": {
    "Proxy": { "Mode": "manual", "HTTPProxy": "127.0.0.1:3128", "UseHTTPProxyForAllProtocols": true, "Locked": true },
    "DisableTelemetry": true,
    "DisableAppUpdate": true,
    "DontCheckDefaultBrowser": true,
    "OverrideFirstRunPage": "",
    "NoDefaultBookmarks": true
  }
}
```

*End of specification.*
