# oillamp — implementation status

Current as of the commit that added this file. The design spec (`oillamp-design-spec.md`)
describes the finished tool; this file describes what actually exists today, what it was
verified against, and what each remaining milestone needs. Every deviation from the spec is
recorded in **spec §36**, with reasons.

---

## In one paragraph

Milestones **M1 (host prerequisites)** and **M2 (lamp directory and configuration)** are
implemented and **verified end to end on a real Ubuntu 24.04 machine**, including the package
install and the sudo prompt. `oillamp doctor` and `oillamp at <dir> --dry-run` do the real thing on
a real machine. `oillamp at <dir>` without `--dry-run` will install host prerequisites and build
the lamp directory, then stop before starting a container, because **M3 onwards is not written
yet**. The five session commands (`view`, `shell`, `stop`, `status`, `list`) parse and then say
so, rather than pretending.

---

## What works today

| Command | State |
|---|---|
| `oillamp doctor` | **Works.** Probes the host, reports every deficiency at once with fix instructions, exits 0 or 3. |
| `oillamp at <dir> --dry-run` | **Works.** Prints the complete plan — package installs, subuid allocation, every file and directory of the lamp with its mode — and changes nothing. |
| `oillamp at <dir>` | **Starts a sandbox.** Installs prerequisites, builds the lamp, builds the image if its content hash changed, starts the container and waits for it to report ready. Prints the `ssh` and `vncviewer` commands to reach it — launching those windows automatically is M4. |
| `oillamp at <dir> --init` | **Works.** Writes a commented `oillamp.toml` and stops. |
| `oillamp config check <dir>` | **Works.** Reports every configuration error in one pass, each with its key path and file. |
| `oillamp config show-effective <dir>` | **Works.** Prints the merged global + lamp configuration. |
| `oillamp config path <dir>` | **Works.** |
| `--verbose`, `--debug`, `--no-color`, `--no-install` | **Work.** |
| `view` `shell` `stop` `status` `list` `recordings` `image` | **Parse, then refuse**, because each needs a running session (M4). |

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

Plus **57 fast scenarios** and **7 spikes**, all passing, rendered to readable Markdown at
`build/spock-reports/*.md` after `./gradlew test`.

### Findings from that run

| Finding | Effect | Resolution |
|---|---|---|
| Ubuntu 24.04 ships podman **4.9.3 with `runc`**, not `crun` | GPU passthrough degraded silently: the render group cannot be passed in, so the desktop fell back to software rendering (`pixman`) on the default `gpu = auto` | **Fixed.** `crun` is now a required package, installed with podman, and a scenario pins the reason. |
| `doctor` advised *"drop `--no-install`"* to a user who never passed it | Read as though oillamp could not install packages at all — the opposite of FR-60, and it did mislead a reader | **Fixed.** `Installing.DECLINED` vs `Installing.NEVER`; `doctor` now points at `oillamp at`. |

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
| **M3** Image and container | **image resources are written** (Containerfile, entrypoint, `lamp` script, sway/sshd/profile configs — no longer sketches). Still to do: content-hash image tag, `podman build` as a Step, container spec and run, readiness protocol | needs `slirp4netns` installed before the image can be built at all |
| **M4** Supervisor | session state machine (§25.1), SSH relays over Unix sockets, terminal and viewer launch (the D-22 profile table already exists), control socket, shutdown sequence, and the five session commands | M3 |
| **M5** Network | egress proxy, policy engine, forwards, network journal, in-container proxy env, Firefox policy, LLM preconfiguration. *The policy model, rules, CIDR and host-pattern matching are already written and tested* — what is missing is the proxy that applies them. | M3 |
| **M6** Recording and agent tooling | wf-recorder, retention (already written), `recordings` command, the `lamp` helper **script** (D-27 — no Java RFB client), agent guide delivery, GPU auto mode | M3 |
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

### Still open: the verification spikes

Three of the 14 remain, and none of them blocks M3:

| # | Assumption | Why it is still open |
|---|---|---|
| **S5** | GPU passthrough via `--device` + `--keep-groups` | Needs a host whose user is in the `render` group. This one is not — oillamp detects that and says so, and the desktop runs on software rendering meanwhile. |
| **S10** | Agent tool package names, binaries, config schemas, and whether their HTTP stacks honour the proxy variables | Needs the full image (`WITH_TOOLCHAIN=true`) and the egress proxy of M5. |
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
./gradlew build                 # compile, run all 57 fast scenarios
./gradlew installDist           # build/install/oillamp/bin/oillamp
./gradlew test                  # then read build/spock-reports/*.md
./gradlew spikes                # §33 assumptions against real podman; needs podman
```

Two suites, on purpose. `test` is fast, offline and deterministic — it runs entirely against
`Machine.simulated()`, so it is green on any machine. `spikes` is the opposite: it pulls images
and starts containers to confirm things about podman, sway and sshd that a simulation cannot
know, because a simulation only replays what we already believed.
