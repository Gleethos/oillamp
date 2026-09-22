# oillamp — implementation status

Current as of the commit that added this file. The design spec (`oillamp-design-spec.md`)
describes the finished tool; this file describes what actually exists today, what it was
verified against, and what each remaining milestone needs. Every deviation from the spec is
recorded in **spec §36**, with reasons.

---

## In one paragraph

Milestones **M1** (host prerequisites), **M2** (lamp and configuration), **M3** (image and
container) and **M4** (the supervisor) are implemented and **verified end to end on a real
Ubuntu 24.04 machine**. `oillamp at <dir>` now runs a whole session: it installs what the host
is missing, builds the lamp and the image, starts the sandbox, opens your shell in a new terminal
window and the desktop in a viewer, reports the sandbox's health in the terminal it was started
from, and takes everything down again when you are finished — whether you close the shell window,
press Ctrl-C, or run `oillamp stop`. All five session commands work. What is left is the network
(**M5**), the recording and agent tooling (**M6**) and packaging (**M7**); until M5 the sandbox
has no outbound network at all, which is the safe direction to be incomplete in.

---

## What works today

| Command | State |
|---|---|
| `oillamp doctor` | **Works.** Probes the host, reports every deficiency at once with fix instructions, exits 0 or 3. |
| `oillamp at <dir> --dry-run` | **Works.** Prints the complete plan — package installs, subuid allocation, every file and directory of the lamp with its mode — and changes nothing. |
| `oillamp at <dir>` | **Runs a session.** Installs prerequisites, builds the lamp and the image, starts the container, opens your shell in a new terminal window and the desktop in a viewer, then supervises until you are finished. Does not return until the session ends. |
| `oillamp at <dir> --init` | **Works.** Writes a commented `oillamp.toml` and stops. |
| `oillamp config check <dir>` | **Works.** Reports every configuration error in one pass, each with its key path and file. |
| `oillamp config show-effective <dir>` | **Works.** Prints the merged global + lamp configuration. |
| `oillamp config path <dir>` | **Works.** |
| `--verbose`, `--debug`, `--no-color`, `--no-install` | **Work.** |
| `oillamp view <dir> [--view-only]` | **Works.** Opens another window onto a running session's desktop. |
| `oillamp shell <dir>` | **Works.** An extra shell in the current terminal. Closing it does not end the session. |
| `oillamp stop <dir>` | **Works.** Asks the running session to shut down; cleans up after a crashed one if there is nobody to ask. |
| `oillamp status <dir>` | **Works.** State, uptime, container, desktop and attached shells, from the supervisor itself. |
| `oillamp list` | **Works.** Every oillamp sandbox running on this host, asked of podman. |
| `recordings` `image` | **Parse, then refuse**, because each needs the milestone that gives it meaning (M6, M7). |

### Verified end to end on real hardware

Ubuntu 24.04.5, GNOME on Wayland, on a machine that had none of the prerequisites. This is
the full sequence, not a simulation:

```
$ oillamp doctor                       # before anything was installed
→ names podman/uidmap/catatonit/socat as missing, offers the apt command, exits 3

$ oillamp at /tmp/lamp --dry-run
→ prints ~35 steps and creates nothing

$ oillamp at /tmp/lamp                 # the real thing
[host]    ✓ Ubuntu 24.04.5 LTS, Wayland (ubuntu:GNOME)
[sudo] password for <user>:            ← the only manual step, exactly once
[host]    ✓ Ubuntu 24.04.5 LTS, Wayland (ubuntu:GNOME)
[host]    ✓ podman 4.9.3, rootless, runc
[lamp]    ✓ config valid — network: default allow, 1 rule, no forwards
[lamp]    ✓ desktop 1920x1080, renderer pixman (software)
[lamp]    · the OCI runtime is runc, but passing the render group into the container needs crun
[lamp]    ✓ ready — agent 4ygi5nkf, desktop 1920x1080, renderer pixman
[session] · starting the sandbox container is the next milestone

$ oillamp doctor                       # afterwards
[host]    ✓ podman 4.9.3, rootless, runc
[host]    ✓ this machine can run oillamp sandboxes
```

This confirms the part of §34.1 that could not be checked before: oillamp installs its own
prerequisites, the probe→fix→**re-probe** cycle of §10.5 works (note the host line printed
twice, the second time with podman present), and the single sudo prompt is the whole of the
user's involvement.

It also produced the first finding that only real hardware could produce — see
*Known gaps on Ubuntu 24.04* below.

Plus **82 fast scenarios** and **7 spikes**, all passing, rendered to readable Markdown at
`build/spock-reports/*.md` after `./gradlew test`.

### Findings from that run

| Finding | Effect | Resolution |
|---|---|---|
| Ubuntu 24.04 ships podman **4.9.3 with `runc`**, not `crun` | GPU passthrough degraded silently: the render group cannot be passed in, so the desktop fell back to software rendering (`pixman`) on the default `gpu = auto` | **Fixed.** `crun` is now a required package, installed with podman, and a scenario pins the reason. |
| `doctor` advised *"drop `--no-install`"* to a user who never passed it | Read as though oillamp could not install packages at all — the opposite of FR-60, and it did mislead a reader | **Fixed.** `Installing.DECLINED` vs `Installing.NEVER`; `doctor` now points at `oillamp at`. |

### A whole session, on real hardware (M4)

Run against real podman on this machine, with a non-graphical stand-in for the terminal so the
check could be automated. Everything else — the container, the relay, the ssh, the shutdown — was
the real thing:

```
[session] ✓ sandbox running — container oillamp-fxjl7ga4
[session] ✓ desktop and shell both answering — oillamp connected to each socket before handing it over
[session] ✓ opened your shell, in a new terminal window

— your session is up ———
  desktop        1920x1080, renderer pixman (software)
  viewer         open now — another with `oillamp view <dir>`
  shell          open now — extra shells with `oillamp shell <dir>`
  the agent sees <dir>/agent-lamp-fxjl7ga4 and nothing else of this lamp
  network        allow by default, 1 rule(s), 0 forward(s)
  this terminal  keeps reporting the sandbox's health until the session ends
  to finish      close the shell window, press Ctrl-C here, or run `oillamp stop <dir>`

[session] ✓ your shell is connected — closing that window ends the session
[health]  · desktop and shell both still answering
[session] · up 32s — oillamp-fxjl7ga4, 1920x1080, 0 extra shells
[session] · shutting down — you closed the terminal window

— session 20260922-200057 ———
  ended because   you closed the terminal window
  ran for         59s
  sandbox         oillamp-fxjl7ga4 (removed)
```

All three endings were checked separately — the shell window closing, `oillamp stop`, and
`SIGINT` — and each removed the container and left no `session.json`, no sockets and no lock.
`oillamp shell` opened a second shell that ran as `agent` in `/home/agent/workspace` and, when it
closed, the session carried on (D-09). `oillamp stop` on a lamp whose supervisor had been killed
found the orphaned container and cleaned up after it (FR-08).

### Findings from the M4 run

| Finding | Effect | Resolution |
|---|---|---|
| `oillamp list` used a `--format` template field podman 4.9 does not have | The command failed outright on the podman version Ubuntu ships, while working on podman 5 | **Fixed.** It reads `--format json`, which does not move between versions. |
| The startup briefing said a viewer was open under `--no-viewer` | A user told to look for a window that is not there | **Fixed.** It reports what is on screen, not what was configured. |
| A session that failed to start exited 5 in silence | The `Problem` explaining why was carried inside the shutdown reason and never emitted — the one failure mode this tool exists to avoid | **Fixed.** Every `StartupFailed` is announced before the session tidies itself away. |
| The session state was read by four threads and was not `volatile` | Nothing misbehaved, but the memory model allows the shutdown hook to watch a stale state until its own timeout — hard to spot, since everything it is responsible for would have worked | **Fixed.** |

---

### Findings from using it (22 September, after M4)

Four came out of one evening's use. Three were real; one was not.

| Finding | Effect | Resolution |
|---|---|---|
| A second Ctrl-C during shutdown reported `OIL-INTERNAL-001` on a session that had cleaned up perfectly | `podman stop` is a child of oillamp and shares the launching terminal's process group, so the second Ctrl-C killed it. It died silently, and oillamp printed *"the sandbox did not stop cleanly: "* — nothing after the colon — and told the user to file a bug | **Fixed, twice over.** Cleanup commands run shielded from the terminal's signals (`setsid --wait`), so a second Ctrl-C cannot reach them; and what is reported is now the end state — *is the container gone?* — rather than each command's opinion of itself. `podman stop` failing while `podman rm -f` succeeds is an ordinary shutdown and says so. Two scenarios pin it. |
| The sandbox banner said *"network via policy proxy"* in a sandbox with no network at all | The agent read it as a promise, tried, and failed for a reason the banner had denied | **Fixed.** The banner asks the proxy port whether anything is listening and says what it finds, so it stays true both before and after M5. |
| `ping`, `vim`, `htop`, `dig` and most everyday shell tools were absent | An agent that cannot diagnose its own environment burns turns guessing | **Fixed.** 30 packages of ordinary shell tooling, in the base layer rather than behind `WITH_TOOLCHAIN`, all verified to exist in trixie. |
| `pi` and `opencode` were missing from a running sandbox | Looked like the harness work had not taken effect | **Not a product bug.** The binary in `build/install/` predated the commit that added them by 24 minutes. Verified by rebuilding: the content hash moves (`76539a3b` → `fb14cd4c`) and both harnesses are present. Worth knowing as a trap: `./gradlew installDist` is what makes a change real for a locally-run oillamp. |

**Recording is now off by default.** It is a continuous screen recording of everything an agent
does — an audit trail a user opts into, not something to switch on for them. `recording.enabled
= true` per lamp turns it back on, and the session briefing says which of the two it is, so a
user who assumed wrong finds out at the start rather than afterwards.

---

## "Does it not install podman itself?"

**Yes — that is FR-01 and D-17, and it is implemented.** On a machine missing prerequisites,
`oillamp at <dir>` runs `sudo apt-get install -y …` itself and logs every command. `--no-install`
opts out and turns those into reported problems instead. Acceptance criterion §34.1 is explicit
about it: *"installs prerequisites (one sudo prompt)"* — one prompt is the entire manual step.

The catch is only ever about **who can type the password.** sudo requires one here, and an
automated agent session has no terminal to type it into, so the install step is the one thing an
agent cannot carry out on its own. A human runs `oillamp at <dir>` once, types the password, and
everything after that is automatic. That is exactly what happened in the transcript above.

The second half of the limit was real but is now cleared: **M3–M6 could not be meaningfully
tested until podman was present**, because the simulated machine deliberately does not fake it.
It answers `podman unshare chown` with a plausible success, which is fine for testing *planning*
and actively misleading for testing *execution*. podman 4.9.3 is now installed on this host, so
the container work can be written and verified against a real runtime rather than a simulation.

---

## What is left

| Milestone | Needs | Blocked on |
|---|---|---|
| **M3** Image and container | ✅ **done** — content-hash image tag, `podman build` as a step, the container spec, and a readiness protocol that connects to every socket before calling a session ready | — |
| **M4** Supervisor | ✅ **done** — session machine (§25.1) as a pure function, both SSH relays, the control socket, the §10.7 shutdown sequence, `view`/`shell`/`stop`/`status`/`list`, and health reporting that continues for the life of the session | — |
| **M5** Network | egress proxy, policy engine, forwards, network journal, in-container proxy env, Firefox policy, LLM preconfiguration. *The policy model, rules, CIDR and host-pattern matching are already written and tested* — what is missing is the proxy that applies them. | M3 |
| **M6** Recording and agent tooling | wf-recorder, retention (already written), `recordings` command, the `lamp` helper **script** (D-27 — no Java RFB client), GPU auto mode. *The harnesses are already installed into the image* — `opencode` and `pi`, the latter with the Eden AI provider extension | — |
| **M7** Packaging | jpackage `.deb`, completion scripts, README, E2E checklist | M3–M6 |

### The golden path, verified

`./gradlew spikes` now does this unattended in about a minute:

```
build the image  →  start the container  →  wait for ready.json
                 →  ssh in over a Unix socket as `agent`
                 →  launch a terminal on the Wayland desktop
                 →  screenshot it and count the pixels that are not background
                 →  connect vncviewer to the wayvnc socket
                 →  stop, and confirm the .mkv plays
```

Eleven of the fourteen §33 assumptions are confirmed, **and not one fallback was needed** —
vncviewer takes a Unix socket path directly, non-root `sshd -i` works on trixie, libwayland
accepts an absolute `WAYLAND_DISPLAY`, and wf-recorder leaves a playable file.

### Agent harnesses, confirmed against a real image

Built with `WITH_TOOLCHAIN=true` and checked inside the container, not assumed:

| | |
|---|---|
| `pi` | `@earendil-works/pi-coding-agent` → `/usr/bin/pi`, version 0.87.1 |
| `opencode` | `opencode-ai` → `/usr/bin/opencode`, version 1.18.32 |
| Eden AI provider | `pi install git:github.com/edenai/pi-edenai` — **not** `npm:pi-edenai`, which its README suggests and which returns 404 from the registry |

`pi install` writes a `git/` clone and a `settings.json` into pi's agent directory — not into an
`extensions/` directory, which is what the extension's README implies. The build therefore
installs into `/usr/local/share/oillamp/pi` (pi's `PI_CODING_AGENT_DIR` relocates it there) and
the entrypoint copies the whole directory into the agent's home at session start, entry by entry,
never over anything the agent already has. Confirmed by running the real installer and the real
seeding function in a container: the files land owned by `agent`, and a second run leaves an
edited `settings.json` untouched.

The tolerance is not theoretical either. The first toolchain build hit an npm idle timeout while
fetching `pi` — and finished successfully anyway, with `opencode` installed and a warning in the
log. That is the required behaviour: a harness is a convenience, while the desktop, the shell,
the recording and ssh are the product.

**`EDENAI_API_KEY`** (and `EDENAI_BASE_URL`, `EDENAI_EU_ONLY`, `EDENAI_MAX_TOKENS`) are passed
from the host environment into the session if they are set there, so a user who has already
configured Eden AI does not have to do it again inside a sandbox. oillamp logs which names it
found and never the values. **Reaching api.edenai.run still needs M5** — until the egress proxy
exists the sandbox has no outbound network at all.

### Still open: the verification spikes

Three of the 14 remain, and none of them blocks M3:

| # | Assumption | Why it is still open |
|---|---|---|
| **S5** | GPU passthrough via `--device` + `--keep-groups` | Needs a host whose user is in the `render` group. This one is not — oillamp detects that and says so, and the desktop runs on software rendering meanwhile. |
| **S10** | Whether their HTTP stacks honour the proxy variables | **Half resolved.** The package names, binaries and install are now confirmed against a real toolchain image (see below). What is still open is the proxy half, which needs M5. |
| **S14** | wayvnc can set the desktop name shown in the viewer's title bar | Cosmetic; §33's fallback is "ignore". |

What these were guarding against turned out not to happen. The load-bearing one was S13 — whether
`--userns=keep-id` maps the way the whole two-user boundary assumes — and it does, exactly.

---

## Finding your way around the code

75 classes, one package, five of them public. The rule and its reasons are in
`src/main/java/dev/oillamp/package-info.java`; every class states in its Javadoc whether it is
public or package-private **and why**.

```
src/main/java/dev/oillamp/
  OilLamp Machine LampEvent Problem ExitStatus   ← the entire public API
  Invocation Commands ConsoleRenderer Context    ← command line in, console out
  HostProbe HostFacts HostPlanner HostPhase      ← phase A: is this machine usable?
  LampClassifier LampLayout LampPlanner LampPhase← phase B: build the lamp directory
  ConfigLoader ConfigTree ConfigSection LampConfig← TOML: merge, validate, locate errors
  NetworkPolicy Rule HostPattern Cidr IpAddress  ← the policy engine (written, not yet applied)
  Plan Step StepRunner                           ← every effect is described before it is done
  Problems Result                                ← the problem catalogue and error accumulation
  RealMachine SimulatedMachine Filesystem        ← the effects, confined to an allowlist
src/test/groovy/oillamp/                         ← a DIFFERENT package, deliberately (§22.2)
```

Two ideas carry most of the design:

1. **Everything is planned as data before it is done.** `Step` describes an effect; `StepRunner`
   performs it. `--dry-run` is the same code path with the performing left out, which is why the
   plan it prints is necessarily the plan that would have run.
2. **One seam for all effects.** `Machine` is the only way to run a command, read a system file,
   ask the time or get randomness. A scenario describes a machine in a sentence
   (`ubuntu("24.04").waylandSession("GNOME").withoutPodman().sudoNeedsPassword()`) and the real
   entry point runs against it. The lamp directory itself is a real temp dir, because its security
   story is POSIX modes and symlinks and a faked filesystem would test nothing.

---

## Running it

```bash
./gradlew build                 # compile, run all 82 fast scenarios
./gradlew installDist           # build/install/oillamp/bin/oillamp
./gradlew test                  # then read build/spock-reports/*.md
./gradlew spikes                # §33 assumptions against real podman; needs podman
```

Two suites, on purpose. `test` is fast, offline and deterministic — it runs entirely against
`Machine.simulated()`, so it is green on any machine. `spikes` is the opposite: it pulls images
and starts containers to confirm things about podman, sway and sshd that a simulation cannot
know, because a simulation only replays what we already believed.
