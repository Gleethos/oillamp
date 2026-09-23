# Status

Version 0.1.0, as of 23 September 2026.

This page says what works, what has been checked on a real machine, and where the code and the
configuration do not yet match. Keep it current: when you close a gap, remove it here; when you
find one, add it.

For how the system works, see [ARCHITECTURE.md](ARCHITECTURE.md). For why, see
[DECISIONS.md](DECISIONS.md).

---

## Summary

Everything that was planned for the first version is implemented except three items: a proxy
setting for Firefox, configuring the agent harnesses for a company LLM, and a few configuration
keys that are read but not used (listed under [Known gaps](#known-gaps)).

`oillamp at <dir>` runs a complete session on Ubuntu 24.04: it installs missing host packages,
builds the image, starts the sandbox, opens a shell window and a desktop viewer, lets the agent
reach the internet through the policy proxy, optionally records the desktop, reports health in the
launching terminal, and removes the container when the session ends.

The fast test suite has 98 scenarios and passes. The spikes pass on the development machine.

---

## Commands

| Command | State |
|---|---|
| `oillamp at <dir>` | Works. Runs a session in the foreground until it ends. |
| `oillamp at <dir> --dry-run` | Works. Prints every step, including the full `podman run` command with `--verbose`, and changes nothing. |
| `oillamp at <dir> --init` | Works. Accepts a non-empty directory as a new lamp. |
| `oillamp at <dir> --no-install` | Works. Reports missing packages instead of installing them. |
| `oillamp at <dir> --no-viewer` | Works. |
| `oillamp doctor [<dir>]` | Works. Checks the host (and the lamp's configuration if given) and changes nothing. Exits 0 or 3. |
| `oillamp view <dir> [--view-only]` | Works. |
| `oillamp shell <dir>` | Works. An extra shell in the current terminal. |
| `oillamp stop <dir>` | Works. Also cleans up after a supervisor that was killed. |
| `oillamp status <dir>` | Works. |
| `oillamp list` | Works. |
| `oillamp remove <dir> [--yes]` | Works. Without `--yes`, lists what would be deleted and exits 2. |
| `oillamp recordings <dir> [--open <session>] [--prune]` | Works. |
| `oillamp config <dir> check \| show-effective \| path` | Works, but reads only the lamp's file, not the global one. See known gaps. |
| `oillamp completion bash` | Works. |
| `oillamp version`, `oillamp help` | Work. |
| `--verbose` | Works. |
| `--debug` | Accepted. Currently the same as `--verbose`. |
| `--no-color` | Accepted but has no effect. Use the `NO_COLOR` environment variable. |
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
- All three ways of ending it (closing the shell window, `oillamp stop`, Ctrl-C) removed the
  container and left no `session.json`, sockets or lock.
- `oillamp shell` opened a second shell as `agent` in `~/workspace`; closing it did not end the
  session.
- After the supervisor was killed with `kill -9`, `oillamp stop` found the orphaned container and
  removed it.
- A second Ctrl-C during shutdown no longer interrupts the cleanup.

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

### Desktop, recording, GPU

- `lamp info`, `screenshot`, `type`, `key` and `wait-stable` work against the real desktop.
  `lamp click`, `move`, `drag` and `scroll` work since pointer input goes through VNC (see the
  lessons table): a Swing button receives the click and fires its action, and an X11 program
  reports each press, release and scroll step at the requested position. `lamp type` delivers
  every character to a Swing text field, the first one included. The desktop spike checks this
  on every run: it clicks an X11 program and types into it using only `lamp`. A click can arrive
  one pixel short, because the compositor converts the position to a fraction of the screen and
  back.
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
- `host.auto_install = false` is ignored; only `--no-install` stops installation. (The
  "packages missing" message suggests the setting anyway.)
- `timeouts.container_ready_seconds` is ignored. The wait for readiness is fixed at 60 s, or 120 s
  when the image was just built.
- `--no-color` does nothing. `NO_COLOR=1` works.
- `--debug` does nothing beyond `--verbose`.

### Things that are described but do not exist

- No session log (`oillamp-<session>.log`), container log (`container-<session>.log`) or install
  log (`install-<time>.log`) is written. `LampLayout` has paths for them, but nothing writes them.
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

- oillamp never removes old sandbox images. Every change to anything that goes into the image (a
  new oillamp build, `image.extra_apt_packages`, `agent_tools.install`) creates a new image of
  about 3.5 GB, and the old one stays. On the development machine this reached about 25 GB in two
  days and filled the disk, so a build failed. Until this is fixed, remove old images by hand:
  `podman images localhost/oillamp/sandbox`, then `podman rmi <image>` for the ones no running
  sandbox uses, and `podman image prune` for untagged leftovers.
- The output of a failed image build is only shown in the error (its last 40 lines). It is not
  saved to `.oillamp/logs/`.

### Inconsistencies

- `oillamp config check` and `config show-effective` read only `<lamp>/oillamp.toml`, while
  `oillamp at` also merges `~/.config/oillamp/config.toml`. The two can disagree.
- Problem codes `OIL-LAMP-001` and `OIL-EXEC-002` are defined but never reported.
- `oillamp list` and `remove` recognise containers by the label `oillamp.lamp`; the archived
  specification calls it `oillamp.lamp-path`. The code is correct; the archive is not.
- Unused code: `Viewers.titleFor`, `HostProbe.canAutoInstall` and `Commands.noPlan` have no
  callers. The viewer's window title was meant to come from wayvnc's desktop name, which was never
  set up.

### Tests that check less than they say

- `SupervisingASessionSpec`, "Closing that terminal window ends the session…": the line
  `outcome.console().contains('podman stop') || true` always passes, so the scenario does not
  check that `podman stop` ran.
- `TheSessionCommandsSpec`, "stop cleans up after a supervisor that was killed…": accepts either
  success or error, so it only checks that `session.json` is gone.

### Not yet tested

- Ubuntu 26.04, and any distribution other than Ubuntu 24.04.
- A Java Swing modal dialog on the sandbox desktop. (A Swing window works; see the lessons table.)
- Loading a native library from `~/libs` with `System.loadLibrary`.
- Firefox loading a website through the proxy.
- A forward to a real LLM service, and opencode or pi using it.
- Two lamps running at the same time.
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
| A 403 sometimes arrived without the sentence naming the rule. | Head and body were written separately; some clients read once. | One write. |
| A lamp could not be deleted. | Infra-owned files are a subordinate id on the host. | `oillamp remove`. |
| `oillamp remove` deleted a lamp whose container was still running, when `lamp.json` was already gone. | It looked for the container by a name derived from `lamp.json`. | It asks podman for a container labelled with the lamp's path. |
| `pi` and `opencode` seemed to be missing. | Not a bug: the locally installed oillamp was older than the change. | Remember to run `./gradlew installDist` (or `singleFile`) after changing the image. |

---

## Running the tests and the program

```sh
./gradlew build          # compile and run the 98 scenarios and the architecture test
./gradlew test           # then read build/spock-reports/*.md
./gradlew spikes         # checks against real podman; slow, needs podman and a network
./gradlew installDist    # build/install/oillamp/bin/oillamp, for development
./gradlew singleFile     # build/dist/oillamp, the single-file executable
```
