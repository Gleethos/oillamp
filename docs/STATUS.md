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
| `oillamp remove <dir> --yes` | **Works.** Deletes the lamp, including the files owned by the sandbox's own users that `rm -rf` cannot touch. Without `--yes` it prints what would go and exits 2; it refuses while a sandbox is running. |
| the egress proxy | **Works.** Started with every session. `npm`, `pip`, `git` over HTTPS and `curl` all reach the internet from inside the sandbox; the host's loopback and private ranges are refused by rule and the refusal is printed where the user can see it. |
| `oillamp recordings <dir>` | **Works.** Lists each recording with how long it ran and what it cost. `--open <session>` plays one through the desktop's own player; `--prune` applies the configured retention now instead of at the next session start. |
| `oillamp completion bash` | **Works.** Prints a bash completion script for `eval`. |
| `image` | **Parses, then refuses.** Nothing needs it: the image is built automatically when its fingerprint changes, and there is no manual image management to do. |

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

Plus **98 fast scenarios** and **7 spikes**, all passing, rendered to readable Markdown at
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

### The sandbox can reach the internet (M5)

Verified from inside a real container, through the real proxy, on 22 September:

| Asked for | Result |
|---|---|
| `curl https://registry.npmjs.org/` | 200, via `CONNECT` |
| `curl http://deb.debian.org/debian/` | 200, absolute-form HTTP |
| `apt-get update` | reaches its mirrors, then fails to write — the root filesystem is read-only by design, as the agent guide says |
| `npm install left-pad` | installed |
| `pip install requests` | installed, into `~/.local` |
| `git clone https://github.com/...` | cloned |
| `curl http://192.168.1.1/` | **denied**, quoting the rule, printed on the supervisor console |

The container still runs `--network=none`. It has no route, no DNS and no interface but loopback;
everything above went through a Unix socket to a proxy on the host that resolved the name, checked
the policy and connected on the agent's behalf. Anything that ignores the proxy variables still
has no network at all, which is the intended failure.

**Why "allow by default" is the right default and not a shrug.** The decision is made per
*resolved address*, not per host name. A name is a claim its owner controls — `totally-normal.
example.com` can point at `127.0.0.1` whenever its DNS operator likes — so a proxy that trusted
names would wave that straight through to whatever is running on the user's own machine. Checking
every address the name resolves to against the deny rule catches it regardless. The agent gets the
open web; the host and the network behind it stay out of reach.

Every connection is in `.oillamp/logs/network-<session>.jsonl` with host, port, resolved address,
decision, deciding rule and byte counts — and never content, because a `CONNECT` tunnel is copied
without being read.

Not done in M5: the Firefox proxy policy file and the LLM preconfiguration from §19.5.

### JVM toolchains in the sandbox (M5.1)

The image ships one JDK, and that was the whole story: an agent doing JVM work could not install
another, because there is no `sudo`, no `apt` and a read-only root filesystem. SDKMAN is the tool
that fits those constraints — it installs into a directory — so it is now in the image.

It is built into `/usr/local/share/oillamp/sdkman` and copied to `~/.sdkman` the first time a lamp
starts, for three reasons at once: the agent's home is a bind mount that would hide anything the
build wrote there, SDKMAN writes as it works and so cannot run from a read-only `/usr/local`, and a
JDK installed in one session is then still there in the next. The login shell defines `sdk`; that
costs nothing, because `sdkman-init.sh` makes no network calls.

One upstream default is changed: `sdkman_auto_answer=true`. An agent reaching the sandbox over ssh
cannot answer *"Do you want java 25 to be set as default? (Y/n)"*, so every prompt would be a hung
command with a turn's worth of its thinking already spent.

Like the agent harnesses, it cannot fail the build. A sandbox without SDKMAN still has a JDK, a
desktop, a shell and ssh.

Verified in a real session, over the real proxy, on 22 September:

| Asked for | Result |
|---|---|
| `sdk version` in a login shell | `5.23.1`, native `0.7.34 (linux x86_64)` — the `sdk` function is defined |
| `sdk list java` | reached `api.sdkman.io` through the proxy |
| `sdk install java 21.0.12+1.1-tem` | installed, and set as default **without prompting** |
| a fresh login shell afterwards | `JAVA_HOME=/home/agent/.sdkman/candidates/java/current`, `java -version` → 21.0.12.1, in place of the image's 25 |
| `sdk install groovy`, `sdk install gradle` | Groovy 6.0.0 and Gradle 9.7.1, both running on the installed JDK |
| stop the session, start it again | a new container, and all three still there — the entrypoint saw `~/.sdkman` and left it alone |

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
| **M5** Network | ✅ **done** — egress proxy, policy engine, forwards, network journal, in-container proxy env. *Not in M5: the Firefox proxy policy file and the §19.5 LLM preconfiguration.* | — |
| **M6** Recording and agent tooling | ✅ **done** — wf-recorder honouring `crf` and `max_fps`, retention, the `recordings` command, the `lamp` helper **script** (D-27 — no Java RFB client), GPU auto mode on real hardware. *The harnesses were already installed into the image* — `opencode` and `pi`, the latter with the Eden AI provider extension | — |
| **M7** Packaging | ✅ **done** — a single self-contained executable (not the `.deb` of D-21; see below), bash completion, a README written for a reader who is not a virtualization specialist | — |

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
found and never the values. Since M5, api.edenai.run is reachable through the egress proxy, so
`pi install` works inside a session too.

### The agent's shell, and getting rid of a lamp

Two findings from using M5.1, both fixed.

**`ssh <lamp> 'some command'` had no environment at all.** `/etc/profile` is read by *login*
shells only. The terminal `oillamp at` opens gets one, so everything worked there — but a scripted
one-shot command, which is how an agent would drive a sandbox, got a shell with no proxy
variables, no `DISPLAY` and no `sdk`. Bash reads `~/.bashrc` in exactly that case, and the agent's
home had none. It does now, written once and then left alone.

The obvious guard against the profile then being read twice — *if already done, return* — turned
out to be wrong, and measuring rather than reasoning is what caught it: Debian's `/etc/profile`
**resets** `PATH` before the profile.d script runs, so skipping the second read left a nested
login shell with neither `~/.local/bin` nor any SDKMAN candidate on its path. Nothing is skipped
now; `PATH` and `LD_LIBRARY_PATH` check themselves, and SDKMAN is sourced only where `sdk` is not
already defined. Verified in all three shapes:

| Shell | Result |
|---|---|
| `ssh <lamp> 'echo $HTTP_PROXY; type -t sdk'` | proxy set, `sdk` is a function |
| `ssh <lamp> 'curl https://registry.npmjs.org/'` | **200** — the environment works, not just exists |
| `ssh <lamp> 'bash -l -c "echo \$PATH"'` | `~/.local/bin` survives `/etc/profile`'s reset |
| sourcing the profile twice in one shell | no duplicated `PATH` entries |

`~/AGENTS.md` was missing too. The banner has told every agent to read it since M3, and nothing
ever wrote it. It is there now, rewritten each session.

**A lamp could not be deleted.** `rm -rf <lamp>` removes most of it and then stops:

```
rm: cannot remove '…/.oillamp/sockets/infra/vnc.sock': Permission denied
rm: cannot remove '…/.oillamp/sockets/infra/ready.json': Permission denied
```

Those files are owned by uid **166536** on the host — the subordinate id that container uid 1001,
the sandbox's infra user, maps onto. That user is what stops the agent tampering with the
recording of its own screen, so the ownership is the feature; not being able to delete your own
directory is the accident.

`oillamp remove <dir> --yes` deletes it through `podman unshare`, which can reach those files.
Without `--yes` it prints exactly what would go — including the contents of the agent's home,
because one of those names is usually a repository — and exits 2. It refuses while a sandbox for
the lamp is still running, and it works on a lamp somebody already tried to delete by hand, which
is the commonest way to end up needing it. The lamp directory itself survives unless removal
leaves it empty.

That last case exposed a real hole during testing: with `lamp.json` already gone, the running-check
had no container name to ask about, and removal went ahead while a container was still up. It now
asks podman for a container carrying this lamp's *path* as a label — which works whether or not the
lamp can still say who it is.

### Recording, the desktop helper and the GPU (M6)

**The GPU works, and this is the first time it ever has.** Every earlier run reported
`renderer pixman (software)` for one reason: the user was not in the `render` group that owns
`/dev/dri/renderD128`, so the decision never reached its hardware branch. One `usermod` and a
fresh login later:

```
[lamp]    ✓ desktop 1920x1080, renderer gles2 (hardware)
```

`gles2` on its own would not have proved it — wlroots reports that name for llvmpipe too, which is
software GLES. What proves it is the compositor itself: the running sway process holds **ten file
descriptors on `/dev/dri/renderD128`** and has `libdrm_amdgpu.so` mapped, and no llvmpipe. The
`--device` and `--group-add keep-groups` flags reach podman, and `keep-groups` carries the host's
render group across the user namespace, which is why crun is a required host package.

One red herring worth recording, because it looks alarming in the container log and is not:

```
amdgpu: amdgpu_cs_ctx_create2 failed. (-13)
```

`-13` is `EACCES`, and it arrives right after two `eglQueryDeviceStringEXT` failures — a probe of a
device that is not the render node. The real context is created immediately afterwards.

**`lamp screenshot`, `click` and `type` all work** — M6's acceptance criterion, and spike **S7**'s
doubt about `wlrctl` is settled: it is in trixie and installed. Driven over the real socket relay,
not simulated:

| What | Result |
|---|---|
| `lamp info` | `1920x1080`, renderer `gles2`, output `HEADLESS-1` |
| `lamp screenshot` | a real 1920x1080 PNG of the desktop |
| `lamp type` + `lamp key Return` | the command appeared in the on-screen terminal **and ran** |
| `lamp click` / `move` / `scroll` | all accepted by `wlrctl` |
| `lamp wait-stable` | returns `stable` once the screen settles |

**`recording.crf` and `recording.max_fps` were being silently ignored.** Both were read from
`oillamp.toml`, both were passed into the container, and the entrypoint used neither — so a
recording ran at whatever the compositor did, which measured **60 fps**. FR-33 asks for frame rate
and quality to be configurable, so this was a gap, and spike **S6** had flagged the flag names as
unverified. They are `--framerate` and `-p <name>=<value>`, and the parameter's *name* depends on
the encoder: the software encoders call it `crf`, the hardware ones `qp`. Getting that wrong is
not cosmetic — the recorder is a critical process, so a rejected parameter would take the whole
session down. Now:

```
wf-recorder --output=HEADLESS-1 --codec=libx264 --framerate=10 -p crf=30 --file=…
```

and `ffprobe` reports `10 fps, 10 tbr` on the result.

Fixing it also fixed the listing. wf-recorder's default is variable frame rate with damage
tracking — no frames at all while the screen is still — so a 62-second session produced a
50-second video, and any duration taken from the clock overstated it by a quarter. At a constant
frame rate the two agree to within **1.6 seconds** on a 100-second recording, which is the
recorder's own startup and finalisation.

**`oillamp recordings <dir>`** lists what is there, with how long each ran and what it cost:

```
  20260923-085055.mkv  1m40s      402 kB    2026-09-23T08:53:15Z

  1 recording(s), 402 kB in total
```

The duration costs nothing to produce: the file is created when the recorder opens it and written
to until it is interrupted, so its own timestamps are the answer, and oillamp never has to require
`ffmpeg` on the host. Where a filesystem keeps no creation time the session in the name is the
fallback, a few seconds early. A file whose timestamps contradict each other — a copy or a restore
— is listed with no duration rather than a made-up one.

`--prune` applies the same `Retention` decision the next session start would, brought forward;
both call one function, because two implementations of "keep 14 days" would eventually disagree.
Verified against the real files, which belong to uid 166536 and therefore go out through
`podman unshare`. `--open <session>` hands the file to `xdg-open` — VLC here — and an unknown
session id is answered with the ids that do exist.

One cosmetic bug fell out of this: `[recordings]` is the longest area tag oillamp has, and it
overran the column, printing `[recordings]✓`. The tag now always keeps its separator.

**The agent cannot switch the monitoring off (NFR-06).** Tested as `agent`, in a live sandbox,
against the running infra rather than by reading the permission bits:

| What the agent tried | What happened |
|---|---|
| `pkill -9 sway`, and the same on `swaybg` | `Operation not permitted` |
| `kill -9 <wf-recorder pid>` | `Operation not permitted` |
| `ls /run/lamp/` — where sway's IPC socket lives | `Permission denied` |
| write over a recording | refused |
| `rm` a recording | `Permission denied` |
| `touch` a new file in `/oillamp/recordings` | `Permission denied` |
| **read** its own recording | **works** — 8 bytes, and intended: §9.2 says so |

That last row is the design, not a gap. Making the directory unreadable to the agent would also
make it unreadable to the human who owns the lamp, since both are "other" to the infra uid.

### Packaging, and a README for somebody who is not a virtualization specialist (M7)

**One file, and nothing to install.** `./gradlew singleFile` produces `build/dist/oillamp`: an
executable of 40 MB that contains the program, its libraries, and a Java runtime. Copy it to a
machine with no Java at all and run it.

This is a deliberate departure from **D-21**, which specified a `jpackage` `.deb`. A `.deb` must be
installed with root before it can be run and only suits Debian-family systems; the requirement here
was a file that runs where it lands, including off a USB stick. D-21's *reason* — "no JDK needed on
the host" — is unchanged, because the runtime is bundled either way.

How it works: the file is a POSIX shell script with a compressed archive appended to it. The script
is `src/packaging/launcher.sh`, it is 90 lines, and it is written to be read — the header explains
what the rest of the file is and how to print only the readable part. On first run it unpacks itself
into `~/.cache/oillamp/<version>-<fingerprint>/` and then `exec`s the bundled Java. `exec` rather
than a child process, because a session ends on Ctrl-C and the signal must reach the program, not a
wrapper that would have to forward it.

| Measured | |
|---|---|
| size | 40.2 MB |
| first run, including unpacking | 0.69 s |
| every run after that | 0.14 s |
| bundled runtime | `java.base`, `java.desktop`, `java.sql`, `jdk.charsets` |
| with `PATH=/nonexistent` and no environment | runs |
| a deliberately truncated copy | refuses, explains why, exits 70 |
| a full session — image, container, GPU, desktop, shell | runs |

The fingerprint in the directory name is a hash of that exact file, so two builds never share an
unpacked copy and upgrading is nothing more than replacing the file. `oillamp --where` prints the
directory; deleting the file and that directory removes oillamp completely.

**`oillamp completion bash`** prints a completion script rather than installing one, which is the
only shape that makes sense for a program that is never installed. Verified to be accepted by bash
and to complete command names, option names and directories.

**The README** is new, and it is the deliverable the rest of M7 exists to serve: this repository now
has to be auditable by programmers who are not virtualization specialists. It defines every term at
first use — container, rootless, user namespace, uid map, subordinate uid, image layer, compositor,
headless, Wayland, VNC — and builds up to the design rather than assuming it. Two parts are worth
naming:

- **Section 4.3** gives the actual uid map as a table, with the real numbers read from the kernel,
  and derives the three security properties from it rather than asserting them.
- **Section 6.5**, "What this does not protect against", states the limits plainly: the agent runs
  as your uid, this is a container and not a virtual machine, and the threat model is the host
  rather than the web.

**The scenario prose was rewritten too.** 35 of the 84 `reportInfo` blocks referred to the design
specification by section number, or used a requirement code such as `FR-60` or `D-09` as though it
were an explanation, or used terms like *subuid*, *userns* and *keep-id* without ever saying what
they mean. Every one of those is now written out in words. The scenario that verifies the uid
mapping — the most load-bearing assumption in the design — now opens by explaining that Linux
identifies users by number and that a container can be given a private set of those numbers, before
saying what is being checked.

Six scenario *titles* were renamed for the same reason, because the title is the first thing a
reader meets: "S13: keep-id maps the host user onto the container user, and the infra uid onto 1001"
is now "S13: the container sees the human as its own user, and the infrastructure as a different
one".

### The verification spikes that are left

S5 and S14; S10 closed with M5. Neither of the two blocks anything:

| # | Assumption | Why it is still open |
|---|---|---|
| **S5** | GPU passthrough via `--device` + `--keep-groups` | Needs a host whose user is in the `render` group that owns `/dev/dri/renderD128`. This one is not, so the desktop runs on software rendering. oillamp now prints the exact command — `sudo usermod -aG render <you>`, on the host, in your own terminal — and the part people miss: a new group only reaches processes started after a fresh login, so the running session will not pick it up. |
| **S10** | Whether their HTTP stacks honour the proxy variables | **Resolved.** Package names, binaries and install were confirmed against a real toolchain image (see below); M5 settled the proxy half, with `npm`, `pip`, `git` and `pi install` all working through it from inside a container. |
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
./gradlew build                 # compile, run all 98 fast scenarios
./gradlew installDist           # build/install/oillamp/bin/oillamp
./gradlew test                  # then read build/spock-reports/*.md
./gradlew spikes                # §33 assumptions against real podman; needs podman
```

Two suites, on purpose. `test` is fast, offline and deterministic — it runs entirely against
`Machine.simulated()`, so it is green on any machine. `spikes` is the opposite: it pulls images
and starts containers to confirm things about podman, sway and sshd that a simulation cannot
know, because a simulation only replays what we already believed.
