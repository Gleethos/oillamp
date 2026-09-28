# Status

Version 0.2.0, as of 29 September 2026.

This page says what works, what has been checked on a real machine, and where the code and the
configuration do not yet match. Keep it current: when you close a gap, remove it here; when you
find one, add it.

For how the system works, see [ARCHITECTURE.md](ARCHITECTURE.md). For why, see
[DECISIONS.md](DECISIONS.md). For how to run the tests, see [CONTRIBUTING.md](../CONTRIBUTING.md).

---

## Summary

Everything that was planned for the first version is implemented except three items: a proxy
setting for Firefox, configuring the agent harnesses for a company LLM, and a few configuration
keys that are read but not used (listed under [Known gaps](#known-gaps)).

`oillamp at <dir>` runs a complete session on Ubuntu 24.04: it installs missing host packages,
builds the image, starts the sandbox, opens a shell window and a desktop viewer, lets the agent
reach the internet through the policy proxy, optionally records the desktop, reports health in the
launching terminal, and removes the container when the session ends.

The fast test suite passes. The spikes pass on the development machine.

---

## Commands

| Command | State |
|---|---|
| `oillamp at <dir>` | Works. Runs a session in the foreground until it ends. |
| `oillamp at <dir> --dry-run` | Works. Prints every step, including the full `podman run` command with `--verbose`, and changes nothing. |
| `oillamp at <dir> --init` | Works. Accepts a non-empty directory as a new lamp. |
| `oillamp at <dir> --no-install` | Works. Reports missing packages instead of installing them. |
| `oillamp at <dir> --no-viewer` | Works. |
| `oillamp at <dir> --no-windows` | Works in the simulated scenarios. No window opens and no display is needed; the session ends as usual. Not yet tried under a real tmux. |
| `oillamp at <dir> --embedded` | Works in the simulated scenarios, also driven through `dev.lamp.Lamp`. No window opens; the session ends when standard input closes, unless the application wrote `leave-running` first. Not yet tried with a real application. |
| `oillamp doctor [<dir>]` | Works. Checks the host (and the lamp's configuration if given) and changes nothing. Exits 0 or 3. |
| `oillamp view <dir> [--view-only]` | Works. |
| `oillamp shell <dir>` | Works. An extra shell in the current terminal. |
| `oillamp stop <dir>` | Works. Also cleans up after a supervisor that was killed. |
| `oillamp status <dir>` | Works. |
| `oillamp follow <dir> [--embedded]` | Works in the simulated scenarios: catches up on the run in progress and the runs waiting, then reports every event until the session ends. Embedded, it stops when standard input closes, and the session goes on. |
| `oillamp list` | Works. |
| `oillamp remove <dir> [--yes]` | Works. Without `--yes`, lists what would be deleted and exits 2. |
| `oillamp recordings <dir> [--open <session>] [--prune]` | Works. |
| `oillamp save <dir> [--message <text>]` | Works in the scenarios and on a lamp made by hand (see below). |
| `oillamp history <dir>` | Works. |
| `oillamp restore <dir> <snapshot>` | Works in the scenarios and on a lamp made by hand. Refused while a session runs. |
| `oillamp schedule <dir> [add \| remove \| enable \| disable \| pause \| resume]` | Works in the scenarios and in a real session (see "Runs and the schedule"). |
| `oillamp ask <dir> "<prompt>"` | Works in the scenarios and in a real session with pi. `--in`, `--after`, `--instead-of` and `--no-wait` work in the scenarios, against a stand-in pi that writes session files as pi does; not yet tried with the real pi. |
| `oillamp cancel <dir> [<run>]` | Works in the scenarios. |
| `oillamp conversations <dir> [<conversation>]` | Works in the scenarios, on session files written in pi's format. |
| `oillamp config <dir> check \| show-effective \| path` | Works. Reads the global file and the lamp's, as `at` does. |
| `oillamp completion bash` | Works. |
| `oillamp version`, `oillamp help`, `oillamp guide`, `oillamp about` | Work. |
| `--verbose` | Works. |
| `--debug` | Accepted. Currently the same as `--verbose`. |
| `--no-color` | Works, as does the `NO_COLOR` environment variable. |
| `oillamp image` | Removed on purpose; see DECISIONS.md. Refused with a usage error. |

---

## Verified on real hardware

All on Ubuntu 24.04.5, GNOME on Wayland, podman 4.9.3, AMD Phoenix graphics.

### Host setup

- On a machine with none of the prerequisites, `doctor` named the missing packages and exited 3.
- `oillamp at` installed them with a single sudo password prompt, probed the host again, and
  continued.
- `--dry-run` printed about 35 steps and created nothing.

### Sessions

- A whole session: image, container, both windows, relays, control socket, shutdown.
- All three ways of ending it (Ctrl-C, closing the terminal `oillamp at` runs in, `oillamp stop`)
  removed the container and left no `session.json`, sockets or lock. Closing the shell window left
  the session running, and `oillamp shell` then opened a new shell into it (checked on 2026-09-23).
- `oillamp shell` opened a second shell as `agent` in `~/workspace`; closing it did not end the
  session.
- After the supervisor was killed with `kill -9`, `oillamp stop` found the orphaned container and
  removed it.
- A second Ctrl-C during shutdown no longer interrupts the cleanup.

### Runs and the schedule

Checked on 2026-09-29, in a real session started with `oillamp at <dir> --no-windows`, with
`schedule.enabled = true` and the sandbox's own pi:

| What | Result |
|---|---|
| `oillamp ask` with pi's default model | pi started over ssh and took the prompt; the model service refused the model (403, the account's guardrail), and the run was reported as failed with that message and saved as a `run` snapshot |
| the same, with `defaultModel` set in the lamp's pi settings | pi, restarted for the next run, created the file asked for and answered in 4 seconds; the agent also wrote `~/workspace/NOTES.md` without being asked, as `AGENTS.md` tells it to |
| asking the agent to schedule a job for itself `in 1m` with `schedule_add` | the host accepted it, with an end 14 days out, and `oillamp schedule` listed it as the agent's |
| waiting | a minute later the job woke the agent in a new conversation; it did the work, the job came off the schedule, and the run was saved |
| `oillamp stop` | no run was in progress; the session ended as usual, with nothing left to save |

`oillamp history` then read as a log of the runs, and `git show --stat` of the last run's snapshot
listed its trailers, the agent's last message, the files it changed and pi's own record of the
conversation. Not tried for real: a run stopped by `schedule.max_run_minutes`, a run interrupted
by the session ending, and Ctrl-C during a run; the scenarios cover all three against a stand-in pi.

### The agent cannot reach the infrastructure

Tested as `agent` in a running sandbox:

| The agent tried | Result |
|---|---|
| `pkill -9 sway`, same for `swaybg` | Operation not permitted |
| `kill -9` on wf-recorder | Operation not permitted |
| `ls /run/lamp/` (sway's control socket) | Permission denied |
| overwrite, delete or create a file in `/oillamp/recordings` | refused |
| read a recording | works, as intended |

### Network

From inside a real sandbox, through the real proxy:

| Request | Result |
|---|---|
| `curl https://registry.npmjs.org/` | 200, via `CONNECT` |
| `curl http://deb.debian.org/debian/` | 200, plain HTTP |
| `npm install left-pad` | installed |
| `pip install requests` | installed into `~/.local` |
| `git clone https://github.com/…` | cloned |
| `curl http://192.168.1.1/` | denied, with the rule quoted, and printed in the supervisor's terminal |
| `apt-get update` | reaches its mirrors, then cannot write: the root filesystem is read-only |

### The agent's shell

| Check | Result |
|---|---|
| `ssh <lamp> 'echo $HTTP_PROXY; type -t sdk'` | proxy set, `sdk` defined |
| `ssh <lamp> 'curl https://registry.npmjs.org/'` | 200 |
| `ssh <lamp> 'bash -l -c "echo \$PATH"'` | `~/.local/bin` still present |
| the profile read twice in one shell | no duplicate `PATH` entries |
| `sdk install java 21.0.12+1.1-tem`, then a new shell | Java 21 active, installed without prompting, still there after a restart |
| `sdk install groovy`, `sdk install gradle` | both work |

### The model key stays on the host

`KeepingTheModelKeyOutOfARealSandboxSpec` (a spike) starts `oillamp at` with a real
`EDENAI_API_KEY` and a windowless terminal (checked on 2026-09-28):

| Check | Result |
|---|---|
| the lamp directory and oillamp's own output, during and after the session | the key is in none of it |
| every file the agent can read (home, session settings, sockets, `/tmp`, `/run`, `/etc`), and the environment and command line of every process it can see | the key is in none of it |
| `EDENAI_BASE_URL` and `EDENAI_API_KEY` in the sandbox; opencode's configuration | the relay, `http://127.0.0.1:3129/v3`, and the placeholder |
| the placeholder sent straight to `api.eu.edenai.run`; `api.edenai.run` | refused by Eden AI; refused by the network policy |
| a chat request through the relay, with the placeholder | answered by `mistral/mistral-small-latest` |
| `pi -p` and `opencode run`, holding only the placeholder | both get an answer from the model |
| the network log | records the model requests, never the key |

The bundled Java runtime of the single-file build completes a TLS 1.3 handshake with
`api.eu.edenai.run`, checking the certificate against the host name, with no modules beyond those
it already has.

`UsingAModelOnThisMachineSpec` (a spike) starts a lamp through `dev.lamp.Lamp` with
`modelService("http://127.0.0.1:11434/v1")` and Ollama running `qwen2.5:0.5b` (checked on
2026-09-28):

| Check | Result |
|---|---|
| `EDENAI_BASE_URL` and `EDENAI_EU_ONLY` in the sandbox | the relay's address; EU only not set |
| `/v3/models` through the relay | Ollama's list, from `/v1/models` |
| a chat request through the relay | answered by the local model |
| `pi -p` | answers with the local model |

opencode's model list is Eden AI's, written when the image was built, so it does not offer a
local model's name.

### An application holding a lamp

`RunningALampForAnApplicationSpec` (a spike) runs one lamp through `dev.lamp.Lamp`, with the engine
started as a separate process from the test's own classpath, as an application would start it
(checked on 2026-09-28):

| Check | Result |
|---|---|
| a lamp created from nothing reports host, lamp, image and session, then `SessionOpened` | yes; no window opens |
| `Lamp.exec` runs as `agent` (uid 1000) in `/home/agent`, with the proxy and display set | yes |
| arguments with spaces, quotes and `$` arrive unchanged; exit code and error output are kept apart | yes |
| a program in the sandbox answers JSON messages one at a time while still running | yes |
| 8 MB of random bytes into the sandbox and back | identical, by SHA-256 |
| a file written by the agent is on the host, owned by the user | yes |
| `https://example.com` through the proxy; `http://192.168.1.1` | 200; 403 naming the rule, reported to the application and in the network log |
| a lookup or connection that ignores the proxy | fails: only `lo`, no name service |
| the user's home, the lamp directory, `oillamp.toml`, writing to `/usr` | none visible; not writable |
| `oillamp status` and `oillamp list` on the lamp | both see it |
| `close()` with `sleep 600` running | returns in seconds; container, `session.json` and lock gone |
| opening the lamp again | same agent id, its files still there |

`WhenAnApplicationOrItsSandboxDiesSpec` (a spike) ends a lamp without asking:

| What happened | Result |
|---|---|
| the process holding the engine's standard input killed with `kill -9` | the engine shuts down within seconds, exits 0 and says the application let go; no container, `session.json` or lock left |
| the container killed with `podman kill` | the application is told within seconds, the engine exits 5, the container is removed, and `Lamp` refuses further commands |

`TwoLampsAtOnceSpec` (a spike) holds two lamps at once:

| Check | Result |
|---|---|
| two lamps started together | two agent ids, two containers, both running |
| each agent writes a file in its home | each reads only its own; neither finds the other's home or file |
| one lamp closed | it ends cleanly; the other keeps running and answering |
| the same lamp opened a second time | refused with `OIL-LOCK-001` and exit 4; the running one is unaffected |

### Genies, a chat app on lamps

`RunningARealGenieSpec` and `UsingGeniesForRealSpec` (spikes) run [Genies](GENIES.md) without its
window against real lamps, with a real Eden AI key (checked on 2026-09-28):

| Check | Result |
|---|---|
| a new genie wakes by itself: its lamp is lit through `dev.lamp`, pi starts in RPC mode | yes; the image was already built, so waking took seconds |
| its desktop over its own VNC client, against the real wayvnc | no password asked, full screen drawn |
| a file given to the genie | arrives in `~/inbox` |
| a message, answered through the model relay; a tool call writing `~/outbox/hello.txt` | the answer streams in; the file is announced and saved on the host where asked, unchanged |
| the genie put to sleep and woken again | the conversation comes back from pi |
| deleting the genie | its lamp is removed through the engine; nothing left |
| the key in the spikes' output | never |

The window was driven for real on 2026-09-28: a small program clicked its buttons, typed into it
and painted it into images, against a real lamp. A new genie woke from one click; a message typed
and sent with return was answered, and the file the genie wrote was announced with a Save button;
with Desktop pressed, the genie opened a terminal running `htop` on its desktop and the window showed
it live; Sleep and Delete left no container and no lamp. That run found three bugs the scenarios had
not, all fixed: rows in the list and the chat were not redrawn when their genie or answer changed; a
message sent right after typing could go out before the draft had the last keystroke; and a message
sent while pi was still starting was replaced by the conversation pi sent back late. The window has
not yet been used by a person.

### Desktop, recording, GPU

- `lamp info`, `screenshot`, `type`, `key` and `wait-stable` work against the real desktop.
  `lamp click`, `move`, `drag` and `scroll` work since pointer input goes through VNC (see the
  lessons table): a Swing button receives the click and fires its action, and an X11 program
  reports each press, release and scroll step at the requested position. `lamp type` delivers
  every character to a Swing text field, the first one included. The desktop spike checks this
  on every run: it clicks an X11 program and types into it using only `lamp`. A click can arrive
  one pixel short, because the compositor converts the position to a fraction of the screen and
  back.
- Windows float by default (`display.windows`). The desktop spike opens an X11 window, checks it
  opens at the size it asked for, moves it by its title bar and widens it by its right edge, all
  with `lamp drag` (checked on 2026-09-23). Before, the desktop tiled, and a single window filled
  the screen and could be neither moved nor resized. The `tiling` choice has no real-desktop check.
- GPU: after adding the user to the `render` group, sway held ten file descriptors on
  `/dev/dri/renderD128` with the AMD driver loaded and no software renderer. (The log line
  `amdgpu_cs_ctx_create2 failed (-13)` appears during start-up and is harmless: it is a probe of a
  different device.)
- Recording: `wf-recorder --framerate=10 -p crf=30` produced a 10 fps file (checked with
  `ffprobe`), and its duration matched the session within 1.6 s.
- `oillamp recordings`, `--open` (played in VLC) and `--prune` work on real infra-owned files.

### Packaging

| Measurement | Value |
|---|---|
| size of `build/dist/oillamp` | 40.2 MB |
| first run, including unpacking | 0.69 s |
| later runs | 0.14 s |
| with `PATH=/nonexistent` and an empty environment | runs |
| a truncated copy | refuses with an explanation, exit 70 |

### Assumptions about third-party tools

Checked by the spike tests (`./gradlew spikes`) and by hand. None of the fallbacks the original
design prepared was needed.

| Assumption | Result |
|---|---|
| Rootless podman works on Ubuntu 24.04 despite the AppArmor restriction on user namespaces | confirmed |
| `keep-id` maps your user to container uid 1000, and `podman unshare chown 1001:1001` gives a directory to the infra user | confirmed |
| A read-only container can still write to its bind mounts | confirmed |
| Unix sockets in a bind-mounted directory work in both directions across the user namespace | confirmed |
| Every package the image needs exists in Debian trixie, including `wlrctl` | confirmed |
| A non-root `sshd -i` works with trixie's OpenSSH | confirmed |
| Wayland clients accept an absolute path in `WAYLAND_DISPLAY` | confirmed |
| sway honours a custom headless screen size and applications render on it | confirmed |
| `vncviewer` connects to a Unix socket path directly | confirmed |
| A `.mkv` from wf-recorder is playable after the container stops | confirmed |
| Passing the GPU with `--device` and `--group-add keep-groups` works | confirmed (needs crun and the `render` group) |
| The npm package names for `pi` and `opencode`, and pi's extension install | confirmed: `@earendil-works/pi-coding-agent`, `opencode-ai`; the Eden AI extension installs only via `git:`, not `npm:` |
| pi and opencode reach Eden AI only through its EU endpoint | confirmed in a real sandbox, with the host pointing `EDENAI_BASE_URL` at the global endpoint: both answered through `api.eu.edenai.run`, offered its 270 models, and the proxy refused `api.edenai.run` |
| Terminal argument templates | confirmed only for the terminals on the development machine |
| wayvnc can set the viewer's window title | not checked; cosmetic |

---

## Known gaps

Places where the code does less than the configuration, the help text or the original design
suggests. Each is a decision for the team: implement it, or remove the option.

### Settings that are accepted but have no effect

- `llm.api_key_env` and `llm.api_key_file` are never read. No API key reaches the sandbox.
- No `OPENAI_BASE_URL` or `OPENAI_API_KEY` is set, and no configuration files are written for
  opencode or pi. Only `OILLAMP_LLM_BASE_URL`, `OILLAMP_LLM_MODELS` and `OILLAMP_LLM_PROVIDER` are
  set when `llm.forward` names a forward.
- `agent_tools.versions` is ignored; the newest versions are always installed.
- `--debug` does nothing beyond `--verbose`.

### Things that are described but do not exist

- No session log (`oillamp-<session>.log`) or container log (`container-<session>.log`) is
  written. `LampLayout` has paths for them, but nothing writes them.
  The only file in `.oillamp/logs/` is the network log. The container's own output is available
  with `podman logs oillamp-<id>` while the container exists; oillamp includes the last lines in
  the relevant problems.
- Firefox has no enterprise policy file, so it is not set to use the proxy. Whether it picks up the
  proxy environment variables on its own has not been checked.
- On a host with SELinux, the volume mounts would need `:Z`/`:z` labels. The host probe records
  whether SELinux is on, but nothing uses it. Only Ubuntu (AppArmor) has been tested.
- `catatonit` is a required host package, described as "the init process inside the container",
  but `podman run` is not given `--init`, so it is not used. The entrypoint is process 1.

### Disk space

- The history keeps every version of every file ever saved, and nothing removes old snapshots.
  There is no way yet to leave parts of the agent's home out of a snapshot, such as `~/.cache`.

- oillamp never removes old sandbox images. Every change to anything that goes into the image (a
  new oillamp build, `image.extra_apt_packages`, `agent_tools.install`) creates a new image of
  about 3.5 GB, and the old one stays. On the development machine this reached about 25 GB in two
  days and filled the disk, so a build failed. Until this is fixed, remove old images by hand:
  `podman images localhost/oillamp/sandbox`, then `podman rmi <image>` for the ones no running
  sandbox uses, and `podman image prune` for untagged leftovers.
- The output of a failed image build is only shown in the error (its last 40 lines). It is not
  saved to `.oillamp/logs/`.

### The network

- The default rules block loopback and the private ranges, but not the host's own public IPv4 or
  global IPv6 addresses. A service on the host that listens on every address can be reached at
  them. The proxy could add the host's addresses to the denied ones when a session starts; that is
  undecided. Until then, the README tells users to add a deny rule themselves.

### Inconsistencies

- `oillamp list` and `remove` recognise containers by the label `oillamp.lamp`; the archived
  specification calls it `oillamp.lamp-path`. The code is correct; the archive is not.

### Not yet tested

- The startup and shutdown saves in a real session with podman. Scenarios cover both with a
  simulated container, ending the session with `oillamp stop`. After Ctrl-C the same shutdown
  sequence runs, with the save in it; that was checked by reading the code, not by a test.
  `save`, `history` and `restore` were tried on a lamp directory made by hand, with a JDK
  (330 MB, 5,000 files) and a cloned git repository in the agent directory: the first save took 5
  seconds, a save with nothing changed 0.6 seconds, a restore 1.2 seconds. The JDK came back
  byte-identical and the cloned repository with a clean `git status`.
- Saving or restoring a home with hundreds of thousands of files, such as several `node_modules`.
- The Genies app has no buttons for the history yet; `dev.lamp.Lamp` offers `save`, `history` and
  `restore`.
- Genies still starts its own pi, and does not use the session's. A lamp that Genies holds and
  that has jobs on its schedule runs two pis in one home. The Lamp API now has what Genies needs
  (`conversations`, `send` with a `Question`, `RunProgress`, `cancel`, `agentStatus`); Genies does
  not use it yet.
- The scheduling tools are pi's only. Another harness in the sandbox, such as opencode, has none.
- A run uses whatever model pi is configured with in the sandbox. A lamp whose pi has no default
  model, or one the model service refuses, fails every run, and says so; oillamp has no setting
  of its own for the model of a run.

- Ubuntu 26.04, and any distribution other than Ubuntu 24.04.
- A Java Swing modal dialog on the sandbox desktop. (A Swing window works; see the lessons table.)
- Loading a native library from `~/libs` with `System.loadLibrary` in a real sandbox. (A fast
  scenario checks that the agent's shell puts `~/libs` and the system directories on Java's
  library path.)
- Firefox loading a website through the proxy.
- A forward to a real LLM service, and opencode or pi using it.
- A lamp copied with `cp -a`. The copy has the original's agent id, and the container name, runtime
  directory and ssh alias are all derived from it. Reading the code, starting the copy while the
  original runs would remove the original's container as "left over from an earlier session".
  Nothing checks that an agent id is unique. Undecided: refuse such a lamp, or give it a new id.
- A lamp path longer than 150 characters.
- Terminal emulators other than the ones on the development machine.

---

## Lessons from real hardware

Bugs that no simulation found, what caused them, and what changed. They explain several things in
the code that would otherwise look unnecessary.

| What happened | Cause | Change |
|---|---|---|
| The GPU was never used on a stock Ubuntu 24.04. | Ubuntu installs podman with runc, which cannot pass the render group into the container. | `crun` is a required package. |
| Building the image failed on a fresh machine. | Rootless podman on Ubuntu 24.04 has no network backend without `slirp4netns`. | `slirp4netns` is a required package. |
| `doctor` told users to "drop `--no-install`", a flag they never passed. | One boolean meant both "the user declined" and "this command never installs". | The `Installing` enum separates the two. |
| `--verbose` changed nothing. | The console renderer was copied, and the copy received the flag. | The renderer carries the flag itself. |
| `--dry-run` refused to run when sudo needed a password. | It planned as if it would execute. | A dry run no longer needs working sudo. |
| `oillamp list` failed on podman 4.9. | A `--format` template field that podman 4 does not have. | It reads `--format json`. |
| A session that failed to start exited with code 5 and said nothing. | The problem was stored in the shutdown reason and never printed. | Every start-up failure is printed before shutdown. |
| A second session on the same lamp had a working shell and a dead desktop. | The socket directory outlives the container, and wayvnc cannot bind a path that already exists. | The entrypoint and the host both delete the previous session's sockets and `ready.json`, the entrypoint checks that each server accepts a connection, and the host checks `ready.json` carries this session's id. |
| A second Ctrl-C during shutdown was reported as a bug in oillamp. | `podman stop` shares the terminal's process group, so it received the Ctrl-C. | Cleanup commands run under `setsid --wait`, and success is judged by whether the container is gone. |
| wayvnc said "Failed to load config. Permission denied". | `setpriv` keeps root's `HOME=/root`. | The entrypoint sets `HOME` and XDG directories per user. |
| Every session claimed it had fallen back from the GPU. | `${var:+…}` treats the string `false` as set. | Compared with `= true`. |
| GUI applications warned "'C' is not a UTF-8 locale". | sshd does not pass a locale, and the image's `ENV` does not reach a login shell. | The profile sets `LANG`. |
| `ssh <lamp> 'command'` had no proxy, display or `sdk`. | Only login shells read `/etc/profile`. | oillamp writes `~/.bashrc`, and the profile script can be read twice safely. |
| `~/AGENTS.md` did not exist, although the banner said to read it. | Nothing wrote it. | Written each session. |
| The agent guide said the screen was recorded when it was not. | Written before recording became opt-in. | It says which is the case. |
| `recording.crf` and `recording.max_fps` were ignored; recordings ran at 60 fps. | The entrypoint never passed them to wf-recorder. | Passed as `--framerate` and `-p crf=`/`-p qp=`. |
| Java Swing applications could not open a window: "Authorization required" / "Can't connect to X11 window server". | sway starts Xwayland as the infra user, and Xwayland only accepts its own user. The X11 socket directory was also `lamp`-only (sway runs with umask 077). No test had ever started an X11 application. | sway keeps Xwayland running (`xwayland force`), and the entrypoint creates `/tmp/.X11-unix` with mode 1777, opens the socket and runs `xhost +si:localuser:agent`. A spike and a static scenario check it; the agent guide says what to do if it ever fails again. |
| `lamp click` never clicked where it was told, and its clicks reached no window, in Swing or anywhere else. `lamp type` lost the first key in X11 applications. | `wlrctl pointer move` is relative, and each `wlrctl` call's virtual mouse disappears when it exits, taking pointer focus with it. Each `wtype` call sends a new keyboard layout, and Xwayland drops the key that comes with it. The earlier check only confirmed that `wlrctl` accepted the commands. | Pointer input goes through the desktop's VNC server (`lamp-pointer`), keyboard input waits 150 ms before the first key. A spike clicks and types into a real X11 application and checks what it received. |
| The new click-and-type spike failed on its first run although `lamp` worked. | The scenario before it leaves a terminal open, sway tiles the windows side by side, and the fixed click position was on the terminal. | The scenario asks X11 where the test window is and clicks its centre. |
| A 403 sometimes arrived without the sentence naming the rule. | Head and body were written separately; some clients read once. | One write. |
| A lamp could not be deleted. | Infra-owned files are a subordinate id on the host. | `oillamp remove`. |
| `oillamp remove` deleted a lamp whose container was still running, when `lamp.json` was already gone. | It looked for the container by a name derived from `lamp.json`. | It asks podman for a container labelled with the lamp's path. |
| The first real run failed with a 403: "Model(s) 'vertex/claude-sonnet-5-5' not allowed by your guardrail policy". | pi's default model, in a lamp whose pi had no model set, was one the account's Eden AI policy refuses. | Not a bug in oillamp: the run reported it, with the service's message. Set `defaultProvider` and `defaultModel` in the agent's `~/.pi/agent/settings.json`. |
| `pi` and `opencode` seemed to be missing. | Not a bug: the locally installed oillamp was older than the change. | Remember to run `./gradlew installDist` (or `singleFile`) after changing the image. |

