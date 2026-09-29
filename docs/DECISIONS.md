# Design decisions

Each entry says what was decided, why, and what was given up. Read this before changing one of
these things. If you change one, update its entry instead of adding a contradicting one, and say
why in the commit message.

The original design specification numbered its decisions (`D-01` and so on). The old number is
given at the end of each entry, only so that old commit messages and the
[archived specification](archive/oillamp-design-spec.md) can be traced. Do not use the numbers in
new code or documents.

For how the resulting system works, see [ARCHITECTURE.md](ARCHITECTURE.md).

---

## Sandbox and security

### Rootless podman is the container engine

podman runs as your user, without a background service running as root. If something escapes the
container, it lands in your account, not in root's. podman can also use other container runtimes
later (gVisor, libkrun) for a stronger boundary. Docker was not chosen because its usual setup is a
root-owned daemon.

*Given up:* a container shares the host's kernel. A kernel bug reachable from inside is an escape
route. A virtual machine would be stronger and much slower to start. *(D-01)*

### The threat model is the host, not the web

oillamp protects your machine, your files, your keys, your other projects and your local network
from mistakes and overreach by an agent, including instructions it picks up from a web page. It
does not try to stop a compromised agent from talking to the internet, and it is not designed to
withstand a determined kernel exploit.

*Why:* an agent that cannot install packages or read documentation is of little use. The line is
drawn at the host. *(no number)*

### The container has no network interface at all

The container runs with `--network=none`. The internet is reached only through an HTTP proxy that
oillamp runs on the host, reached over a Unix socket.

*Why:* there is nothing to misconfigure. A firewall rule can be wrong and fail open; a missing
network interface cannot. Tools that ignore the proxy settings simply get no network. The proxy
can apply a policy by host name and address and log every connection, without root and without
nftables.

*Given up:* tools that do their own DNS lookups do not work in the sandbox. *(D-12)*

### The network policy is checked against resolved addresses, and allows the web by default

The shipped policy allows everything except private, loopback, link-local, carrier-grade NAT and
IPv6 local ranges. Each host name is resolved on the host and every resulting address is checked.

*Why:* checking addresses means a public name that points at `127.0.0.1` or at the intranet is
refused by where it points, not trusted by what it is called. That is what makes "allow the web"
safe for the host. *(part of D-12)*

### Forwards are an explicit exception to the policy

A forward connects one fixed port inside the sandbox to one target you configured, such as a
company LLM service, without going through the proxy policy.

*Why:* agent tools expect a plain base URL, and routing an internal service through a policy that
blocks internal addresses would need an exception anyway. Forwards are named in the config, listed
in the agent guide and logged. *(D-13)*

### Two users inside the container: the agent is you, the infrastructure is nobody

Container uid 1000 (`agent`) is mapped to your host user with `--userns=keep-id:uid=1000,gid=1000`.
Container uid 1001 (`lamp`) is mapped to a subordinate id that owns nothing on the host, and runs
the compositor, VNC server, recorder and network bridges.

*Why:* files the agent writes in its home belong to you, so you can read, edit and commit them
without changing ownership. The infrastructure runs as a different user, so the agent cannot
signal it, reach its control sockets or alter the recording.

*Given up:* the agent has your uid. Its isolation comes from what the container can see, not from
file permissions. A mistake that mounts the wrong directory gives the agent your rights to it.
*(D-14)*

### The lamp has two layers

`<lamp>/` holds the configuration and oillamp's state. `<lamp>/agent-lamp-<id>/` is mounted into
the container as `/home/agent`, and it is the only part of the lamp the agent can change freely. Of
the state directory, only the session files (read-only), the three socket directories and the
recordings are mounted, each on its own. The configuration, keys and logs never are.

*Why:* the network policy, keys, logs and recordings are outside everything the agent can reach, so
the agent cannot rewrite the rules that restrict it. The agent id in the directory name means a
copied agent directory is never mistaken for a lamp. *(D-10)*

### The image's filesystem is read-only; only the agent's home persists

The container runs with `--read-only`. Anything the agent installs system-wide is gone at the end
of the session. Anything in `/home/agent` stays.

*Why:* the environment is reproducible and the agent cannot permanently change system tools. Extra
system packages are added through `image.extra_apt_packages`, which rebuilds the image. Language
package managers (npm, pip with `--user`, SDKMAN, Maven) install into the home and persist.
*(D-11)*

### Some lamp files belong to the infrastructure user, so deleting a lamp needs a command

The infra sockets and recordings are owned by the infra user, which is a subordinate id on the
host. `rm -rf` cannot delete them. `oillamp remove` deletes them through `podman unshare`.

*Why:* this ownership is what stops the agent tampering with its recording. The inconvenience is
the price, and a command removes it. `remove` needs `--yes`, because it deletes the agent's work
and a script may be driving it. It refuses while a sandbox for the lamp is running, and it works on
a lamp someone already half-deleted by hand. *(added during implementation)*

### Paths that are too long for Unix sockets go through a short runtime directory

Host-side socket paths are addressed through `$XDG_RUNTIME_DIR/oillamp/<agent id>/`, which holds a
symlink to the lamp's socket directory and the host-only sockets.

*Why:* Linux limits socket paths to 107 bytes, and lamp paths can be longer. *(D-25)*

---

## Desktop

### A headless Wayland compositor inside the container, watched over VNC

The sandbox runs its own compositor with a virtual screen. You watch it through a VNC viewer.

*Why:* nesting the agent's compositor as a window in your desktop would make it a client of your
session, with access to your clipboard and more. A VNC connection is a narrow, well-understood
boundary, and it allows several viewers and recording. *(D-02)*

### sway is the compositor

*Why:* it runs headless, is small, and has the best automation tools: `wayvnc`, `grim`,
`wf-recorder`, `wtype`, `wlrctl`. The desktop does not need to look like GNOME. *(D-03, D-06)*

### wayvnc on a Unix socket; TigerVNC as the viewer

*Why:* no TCP port exists anywhere. Access is controlled by file permissions. TigerVNC's
`vncviewer` connects to a socket path directly (verified). A built-in Swing viewer is a possible
later replacement. *(D-04, D-05)*

### Xwayland is included, and the agent is allowed on it

*Why:* Java Swing applications still draw through X11. Testing Swing applications is a primary use.
sway starts Xwayland as the infra user, so the entrypoint explicitly allows the agent user to
connect (`xhost +si:localuser:agent`). An X11 client can see and send input to other X11 windows
on the same display, but those are all the agent's own windows; it cannot use this to control sway
or reach the infrastructure's processes. *(D-07, extended during implementation)*

### The desktop helper `lamp` is a shell script

`lamp screenshot`, `click`, `type` and so on wrap `grim`, `wtype` and a small Python helper,
`lamp-pointer`, that sends mouse input through the desktop's VNC server.

*Why:* the original plan was a Java VNC client. It would have needed a protocol implementation,
PNG encoding and a build step that puts a jar into the image, for nothing the standard tools do not
already do. A script can be read and fixed by the agent itself, and the agent can call the tools
directly when the script does not do what it needs. *(D-27)*

The mouse was first driven with `wlrctl`, which turned out not to work: its moves are relative, and
each call's virtual mouse disappears when the call ends, taking the window's pointer focus with it,
so clicks reached nothing. Sending pointer events over a VNC connection gives absolute positions
and a device that stays for the whole move-and-click, and it is the same path the human's viewer
uses. The helper implements only the few messages needed to send pointer events. *(changed during
implementation)*

### Recording is off by default

*Why:* a continuous screen recording is an audit trail a user should switch on deliberately, not
find already running. The original plan had it on. *(changed during implementation)*

### The GPU is used when possible and never blocks a session

With `display.gpu = "auto"`, the render node is passed into the container only if everything lines
up. Otherwise the desktop uses software rendering and says why. The entrypoint also falls back to
software if the compositor cannot start with the GPU. oillamp prints the `usermod` command for a
missing group membership but does not run it, because it changes your account and only takes effect
after you log in again. *(D-24)*

### Clipboard flows into the sandbox only, by default

*Why:* pasting into the sandbox is useful. Copying out of it is how agent-produced content could
leak onto your clipboard unnoticed. *(D-23)*

---

## Sessions

### Your shell is SSH over a Unix socket

Each lamp has its own ed25519 key pair and a pinned host key.

*Why:* SSH gives the agent tools a standard login environment, works with `--network=none`, and
would still work across a future virtual-machine boundary. Your own SSH keys are never offered to
the sandbox and you are never asked to accept a host key. *(D-08)*

### A session ends in the terminal it was started from, not in the shell window

The session ends on Ctrl-C in the terminal `oillamp at` was started from, on closing that terminal,
or on `oillamp stop`. Closing the shell window or the viewer ends nothing; the user opens new ones
with `oillamp shell` and `oillamp view`.

The supervisor still relays the shell window's SSH connection itself, rather than watching the
terminal program: the session counts as up once that connection arrives, and `oillamp status`
reports when it has closed.

*Why:* the first design ended the session when the shell window closed. In use, that was wrong: a
user whose program hung in the shell closed the window to get a fresh shell, and lost the whole
sandbox instead. The terminal oillamp was started from is where the session reports what it is
doing, so it is the natural place to end it. Many terminal emulators (GNOME Terminal, Ptyxis) hand
the new window to a server process and exit immediately, so watching the terminal program would
say nothing about the window. *(D-09, changed on 2026-09-23)*

### Two new windows; the launching terminal is never taken over

`oillamp at` opens a new terminal window with the shell and a new viewer window. The terminal you
typed the command in stays in the foreground and keeps reporting the sandbox's health until the
session ends.

*Why:* that running report is where you look when something goes wrong. Replacing it with the shell
would hide it. Ctrl-C there is an obvious way to stop everything. *(D-16, refined during
implementation)*

### The terminal emulator is chosen from a table

oillamp knows nine terminal emulators and prefers your desktop's own. `terminal.profile` or
`terminal.command` override it.

*Why:* terminal emulators disagree on how to set a title and run a command. Keeping the differences
as data means adding one is a new table row. *(D-22)*

### A lamp keeps a history, and goes back to it

A lamp can be saved (`oillamp save`), listed (`oillamp history`) and brought back to an earlier
snapshot (`oillamp restore`). oillamp also saves as each session starts and as it ends, when
anything changed. A snapshot holds the agent directory and `oillamp.toml`, never `.oillamp/`.

*Why:* the sandbox keeps an agent away from the host, but inside its home an agent can still
delete a project or break its tools, and that work is lost. Nobody remembers to save before the
mistake, so the saves at the start and end of a session happen by themselves. *(added on
2026-09-29)*

### The history is a git repository that oillamp writes itself

The history is a bare git repository in `.oillamp/history/`, never mounted into the container.
oillamp writes git's objects directly, in Java, and never runs `git`.

*Why:* git's format gives content-addressed storage that keeps each version of a file once, and a
repository anyone can read with `git log`. But git's own commands cannot do the job: `git add`
stores a directory with its own `.git` as a pointer and drops its files, and `git checkout` refuses
to write a path through `.git`. Agents clone projects into their home, so those would be lost on a
restore. Writing the objects directly also means the host needs no `git`, and the history is part
of the lamp like every other file oillamp reads and writes itself.

*Given up:* git packs objects to save space and delta-compresses similar files; oillamp writes one
compressed file per object and reads only those, so the history is larger than git's would be, and
running `git gc` on it by hand would make it unreadable to oillamp. File times, owners and hard
links are not kept; exact permissions are, in a list of their own. *(added on 2026-09-29)*

### A restore needs the session stopped; a save does not

`oillamp restore` refuses while a session runs (`OIL-HISTORY-005`). `oillamp save` works either
way, and a save made while a session runs is marked as a running save. A restore always saves
first, and records itself as a new snapshot, so the history only grows and every restore can be
undone.

*Why:* rewriting the agent's home under running programs would leave them working in files that
changed beneath them. Saving while the session runs is the case that matters most, just before
letting the agent try something risky, and the worst it can do is catch a file half written, which
the mark warns about. *(added on 2026-09-29)*

---

## Host setup

### oillamp installs its own prerequisites with apt

On a Debian or Ubuntu host, missing packages are installed with `sudo apt-get`, after showing the
list and the reason for each. `--no-install` turns this off. A missing subordinate id range is
added with `sudo usermod`.

*Why:* one command from a stock desktop to a working sandbox. Other distributions get the package
list to install themselves. *(D-17)*

### `crun` and `slirp4netns` are required host packages

*Why:* Ubuntu 24.04 installs podman with `runc`, which cannot pass the GPU render group into the
container (`--group-add keep-groups`); without `crun`, the GPU is silently never used. And rootless
podman on Ubuntu 24.04 has no network backend without `slirp4netns`, which building the image needs
even though the sandbox itself runs without a network. Both were found on the first real-hardware
run. *(added during implementation)*

---

## Code

### Java 25, one Gradle module, the engine in one package, few public types

The engine, `dev.oillamp`, exposes two types: `OilLamp` and `Machine`. The events, problems and
exit codes it speaks in are public too, in `dev.lamp`, where an application that embeds oillamp
finds them.

*Why:* the public API is the part that cannot change without breaking someone, so it is kept small.
A single package lets the compiler enforce it: package-private classes cannot be used from outside.
The tests sit in a different package and so can only use the public API. Eight Gradle modules were
planned and dropped: one ArchUnit test enforces the same rules with far less build machinery.
*(D-26)*

### Decide in pure functions, act in a few named classes

Planning is done by functions that take values and return values. Only an allowlist of classes
touches files, processes, the clock or randomness, and a test enforces it.

*Why:* the logic can be tested quickly without podman, and `--dry-run` shows exactly what a real run
would do, because both build the same plan. A future Swing interface can drive the same core.
*(programming model of the original spec)*

### Other applications embed oillamp through `dev.lamp`, with the engine in its own process

A Java application, such as the Tribalism game, uses oillamp through a second package, `dev.lamp`,
in the same jar. Its `Lamp` type starts the engine (`dev.oillamp`) as a separate Java process with
`oillamp at <dir> --embedded`, using the classpath it was itself loaded from, and talks to it:
events arrive as JSON lines on the engine's standard output, requests go to the control socket, and
commands in the sandbox run over the existing SSH relay. `dev.lamp` never imports `dev.oillamp`.

An embedded session opens no windows and waits for no terminal. It ends when the application closes
the engine's standard input, which the operating system also does when the application dies.

*Why:* the supervisor is the sandbox's proxy, relays and shutdown sequence. Inside the
application's own process, a crash of the application would cut the agent's network mid-task and
skip the cleanup. As a separate process it shuts down properly, and `oillamp status`, `shell` and
`view` work on a lamp an application started. Starting the engine from the same jar means the
application and the engine are always the same version, so nothing has to be installed.

*Given up:* a second process per lamp, and the control protocol is no longer only spoken between
two copies of the command-line tool. *(added on 2026-09-28)*

### Libraries: Jackson 2 for TOML and JSON, Sprouts for collections, no CLI library

*Why:* Jackson 3 has no TOML module. Sprouts provides immutable collections that fit records.
picocli was dropped because the command line is small and hand-written parsing makes exit code 2
for every usage error easy to guarantee. There is no logging framework; oillamp reports through
events. *(D-19, D-20)*

### Configuration is TOML; arrays replace instead of merging

*Why:* TOML supports comments and has no YAML indentation traps. When a lamp lists network rules,
it gets exactly those rules, never a merge with the global file's rules, which nobody could reason
about. Unknown keys are errors, because a misspelled rule key would silently not apply. *(D-20)*

### The configuration file is read by walking the parsed tree, not by binding it to classes

*Why:* walking the tree keeps the key path (`network.rules[0].cidrs`), so every error can say
exactly where it is. *(changed during implementation)*

---

## Distribution

### One self-extracting file instead of a `.deb`

`./gradlew singleFile` builds one executable: a shell script with an archive of a reduced Java
runtime and the program attached. It unpacks itself into your cache directory on first run.

*Why:* a `.deb` must be installed as root and only suits Debian-family systems. The goal was a file
you can copy to a colleague and run. The original reason for bundling a runtime, "no JDK needed on
the host", still holds. *(D-21, changed during implementation)*

### No `oillamp image` command

*Why:* the image tag is a hash of everything that goes into it, so a changed input always produces a
rebuild and an unchanged one never does. There is nothing left to manage by hand. *(changed during
implementation)*

---

## The agent's environment

### Everyday shell tools are in the base image

`ping`, `vim`, `htop`, `dig`, `tmux` and about thirty others are always installed.

*Why:* an agent that cannot inspect its own environment wastes its turns guessing. *(added during
implementation)*

### Agent harnesses and SDKMAN are installed at build time and never fail the build

`opencode` and `pi` (with pi's Eden AI extension) and SDKMAN are installed while the image is built,
into `/usr/local/share/oillamp/`, and copied into the agent's home at session start.

*Why:* `/home/agent` is a bind mount, so anything the image puts there is hidden at run time, and
SDKMAN must be writable. A failure to install one of them only prints a warning: the desktop, shell
and SSH are the product, the harnesses are a convenience. *(added during implementation)*

### The model key stays on the host; the sandbox reaches Eden AI through oillamp

The Eden AI key is never put into the sandbox: not into its environment, not into
`runtime.env`, not into any file the agent can read. Inside the sandbox, the harnesses send their
requests in plain HTTP to `http://127.0.0.1:3129/v3`, with a placeholder key. oillamp receives
each request on the host, replaces the placeholder with the real key, and sends it over HTTPS to
Eden AI's EU endpoint, `https://api.eu.edenai.run`, and nowhere else. The key comes from
`EDENAI_API_KEY` in the environment oillamp is started from, and stays in oillamp's memory.

*Why:* an agent can read everything in its sandbox, and whatever it can read, it can send
anywhere. Instructions picked up from a web page are enough to make it do so. With the key in the
sandbox, one such page could take the key away, to be used elsewhere for as long as it stays
valid. With the key on the host, the most a misled agent can do is use the model while its session
runs, through a channel oillamp sees. It also means the key, the endpoint and the model service
can change without rebuilding the image, and that every model request can be counted.

*Given up:* the harnesses speak plain HTTP inside the sandbox. That traffic never leaves the
container's loopback and the host's Unix socket, and the connection to Eden AI is HTTPS. *(added
on 2026-09-28, replacing the earlier design, in which the key was copied into the sandbox)*

### Eden AI is used only through its EU endpoint

oillamp forwards model requests to `https://api.eu.edenai.run` unless the user names another
service on the host (`model.service`, or `--model-service` and `Lamp.modelService` for one
session); nothing inside the sandbox can. With Eden AI, the harnesses offer only the models the EU
endpoint serves, and the shipped network policy refuses the global endpoint, `api.edenai.run`, for
anything in the sandbox that tries it directly (it would have no key anyway).

A service of the user's own, such as a model server on their machine, is not filtered by region:
its model list names no regions, so the filter would leave nothing, and where it runs is the
user's decision. oillamp tells the sandbox which applies, in `OILLAMP_MODEL_EU_ONLY`.

*Why:* the team requires its model traffic to stay in the EU. Holding the key on the host makes
this a property of oillamp rather than a setting inside the sandbox: a request can only be sent
with the key by oillamp, and oillamp only sends it to the EU. *(added on 2026-09-24, changed on
2026-09-28 when the key moved to the host, and again when services of the user's own were
allowed)*

### One environment for every kind of shell

oillamp writes `~/.bashrc` (once) that reads `/etc/profile.d/oillamp.sh`.

*Why:* `/etc/profile` is only read by login shells. Without the `.bashrc`, `ssh <lamp> 'command'`,
which is how scripts and agents drive the sandbox, got no proxy settings, no display and no `sdk`.
The profile script can be read more than once safely, because Debian's `/etc/profile` resets `PATH`
and a simple "already done" guard would lose entries. *(added during implementation)*

### The sandbox describes itself to the agent

`~/AGENTS.md` is rewritten each session from the live configuration. It says what persists, how to
use the desktop, that there is no DNS, what the proxy policy's default is, how a denial looks, and
what the agent cannot do.

*Why:* an agent that does not know where it is will try `sudo apt install`, conclude the network is
broken, or retry a denied request. A paragraph of accurate text prevents that. *(from the original
spec)*
