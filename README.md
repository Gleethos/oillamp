# oillamp

**oillamp gives an AI coding agent its own Linux computer, with a graphical desktop. It runs on
your machine, but it cannot reach your machine.**

You run one command. A few seconds later two new windows open: a terminal logged into a sandboxed
Linux system, and a viewer showing that system's desktop. An agent working in there can install
packages, run a browser, open GUI applications, take screenshots of its own screen and click on its
own windows. It can reach the internet. It cannot read your home directory, your SSH keys, your
other projects, or anything else on your computer.

When you are finished, one command removes the whole thing.

---

## Table of contents

1. [Who this document is for](#1-who-this-document-is-for)
2. [Getting it running](#2-getting-it-running)
3. [Reading the startup output, line by line](#3-reading-the-startup-output-line-by-line)
4. [The ideas you need](#4-the-ideas-you-need)
5. [What is in a lamp directory](#5-what-is-in-a-lamp-directory)
6. [The boundary: two users, and what separates them](#6-the-boundary-two-users-and-what-separates-them)
7. [The network](#7-the-network)
8. [The desktop](#8-the-desktop)
9. [What is inside the image](#9-what-is-inside-the-image)
10. [The code, briefly](#10-the-code-briefly)
11. [Building it yourself](#11-building-it-yourself)
12. [How it is tested](#12-how-it-is-tested)
13. [Taking ownership](#13-taking-ownership)

---

## 1. Who this document is for

You are a programmer. You are comfortable with a terminal, with Git and with a JVM language. You
have probably used Docker to run a database for a test suite.

You are **not** a Linux virtualization specialist. When somebody says "rootless container with a
uid map", you have a vague sense that it is about isolation, but no clear picture of how it works.

This document assumes exactly that. Every term is defined the first time it is used. Where oillamp
makes a choice, the reason is given, and where the choice is a trade-off, both sides are given.

Read this file first. It takes about forty minutes. Then:

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) explains how the code works, in detail.
- [docs/DECISIONS.md](docs/DECISIONS.md) lists the design decisions and their reasons.
- [docs/STATUS.md](docs/STATUS.md) says what has been verified on real hardware, and where the
  code does not yet do what its configuration suggests.
- [CONTRIBUTING.md](CONTRIBUTING.md) says how to build, test and document changes.

---

## 2. Getting it running

### 2.1 What you need on your machine

- **Linux on a 64-bit Intel or AMD processor.** oillamp has been developed and verified on
  Ubuntu 24.04. Other distributions should work but have not been tested.
- **A graphical desktop session**, because oillamp opens windows. Wayland and X11 both work.
- **`podman`**, the program that runs containers. If it is missing, oillamp installs it for you.

You do **not** need Java or Docker. You do not need to be root, although installing podman the
first time asks for your password, because installing system packages requires it.

### 2.2 Get the file

The whole program is one executable file of about 40 MB. Build it with:

```sh
./gradlew singleFile
```

This produces `build/dist/oillamp`. You can copy that file anywhere: another machine, a USB stick,
a shared drive. Nothing needs to be installed on the machine that receives it.

```sh
cp build/dist/oillamp ~/bin/oillamp      # or /usr/local/bin, or leave it where it is
```

### 2.3 Check your machine

```sh
oillamp doctor
```

This changes nothing. It inspects your system, reports everything that would stop a sandbox from
starting (all at once, not one problem per run) and gives the exact command that fixes each one.
On a healthy machine:

```
🪔 oillamp 0.1.0
[host]    ✓ Ubuntu 24.04.5 LTS, Wayland (ubuntu:GNOME)
[host]    ✓ podman 4.9.3, rootless, crun
[host]    ✓ this machine can run oillamp sandboxes
```

### 2.4 Start a sandbox

```sh
oillamp at ~/lamps/first
```

`~/lamps/first` is a directory that oillamp creates and fills. You choose the name. This directory
*is* the sandbox: everything belonging to it lives inside. oillamp calls such a directory a
**lamp**.

The first run takes several minutes, because it builds a container image: roughly 3.5 GB of
Debian, a Java toolchain, Node.js, a browser and a desktop. Later runs reuse that image and start in
a few seconds.

When it finishes, **two new windows open**: a terminal and a desktop viewer. The terminal you typed
in stays where it is and becomes a status display. It reports the sandbox's health until the
session ends.

If the directory already contains other files, oillamp refuses to use it unless you add `--init`,
so that a typo cannot turn one of your projects into a sandbox.

### 2.5 Finish

Any of these ends the session:

- close the terminal window that opened,
- press `Ctrl-C` in the terminal you started from,
- run `oillamp stop ~/lamps/first` from anywhere.

The sandbox shuts down, the container is removed, and the files in `~/lamps/first` stay on disk.
Running `oillamp at ~/lamps/first` again brings it all back, including whatever the agent
installed in its home directory and wrote.

To delete it permanently:

```sh
oillamp remove ~/lamps/first --yes
```

Without `--yes`, it prints exactly what would be deleted and stops. Section 6.4 explains why this
needs its own command instead of `rm -rf`.

### 2.6 Other commands

```sh
oillamp at <dir> --dry-run      # print everything oillamp would do, and do nothing
oillamp view <dir>              # open another viewer onto a running session
oillamp shell <dir>             # open an extra shell in this terminal
oillamp status <dir>            # what a running session is doing
oillamp list                    # every oillamp sandbox running on this machine
oillamp recordings <dir>        # list screen recordings, if recording is on
oillamp config <dir> check      # validate the lamp's configuration
```

### 2.7 Tab completion

oillamp is never installed anywhere, so there is no install step that could put a completion
script where bash looks for one. Instead it prints the script and you decide what to do with it:

```sh
eval "$(oillamp completion bash)"                              # this shell only
echo 'eval "$(oillamp completion bash)"' >> ~/.bashrc          # every future shell
```

It completes command names, option names, and directory names for the commands that take a lamp.

---

## 3. Reading the startup output, line by line

Here is a real session starting. Each line is explained below. This is the quickest way to see the
whole system at once.

```
🪔 oillamp 0.1.0 — /home/you/lamps/first
[host]    ✓ Ubuntu 24.04.5 LTS, Wayland (ubuntu:GNOME)
[host]    ✓ podman 4.9.3, rootless, crun
[lamp]    ✓ config valid — network: default allow, 1 rule, no forwards
[lamp]    ✓ desktop 1920x1080, renderer gles2 (hardware)
[lamp]    ✓ ready — agent v4elchzj, desktop 1920x1080, renderer gles2
[image]   ✓ sandbox image ready — localhost/oillamp/sandbox:0a6f7ffa4c89ca64
[session] ✓ sandbox running — container oillamp-v4elchzj
[session] ✓ desktop and shell both answering — oillamp connected to each socket before handing it over
[session] ✓ the desktop is up — renderer gles2
[session] ✓ opened your shell, in a new terminal window
[session] ✓ opened the desktop viewer
```

The tag in brackets is the **phase**. oillamp runs its phases in order, and each finishes before
the next begins. This is the actual structure of the program, not just formatting.

**`[host]`: what is this machine, and can it run a sandbox?**

`Ubuntu 24.04.5 LTS, Wayland (ubuntu:GNOME)` is the operating system and the kind of graphical
session you are logged into. oillamp needs to know the session type because it opens windows.

`podman 4.9.3, rootless, crun` is three facts. *podman 4.9.3* is the container program and its
version. *rootless* means podman runs as you, not as the system administrator (section 4.2).
*crun* is the low-level program podman uses to create containers. oillamp requires crun because it
is the one that can pass your graphics card into the container (section 8.4).

**`[lamp]`: what is in this directory, and what does its configuration ask for?**

`config valid — network: default allow, 1 rule, no forwards` means the `oillamp.toml` file in the
lamp directory was read without errors, and summarises the network policy it describes
(section 7).

`desktop 1920x1080, renderer gles2 (hardware)` means the desktop will be 1920 × 1080 pixels and
will be drawn by your real graphics card. `gles2` is the name of the drawing method that uses the
graphics card; the alternative is `pixman`, which draws with the processor. `pixman` is slower but
always works.

`ready — agent v4elchzj` names the **agent id**, a random identifier created with the lamp. It
names the container and the agent's home directory, and it never changes for the life of the lamp.

**`[image]`: is the container image built?**

An **image** is a frozen filesystem that a container starts from (section 4.5). The long
hexadecimal string at the end is not a version number. It is a fingerprint of everything that goes
into building the image: the build recipe, every script, every configuration file and the package
list. Change any of them and the fingerprint changes, so oillamp builds a new image. Change nothing
and it reuses the existing one. This is why you cannot end up running an out-of-date image by
accident.

**`[session]`: start everything, and watch it.**

`sandbox running — container oillamp-v4elchzj` means the container has started.

`desktop and shell both answering` is a deliberate check. oillamp does not check that the
desktop's socket *file exists*. It opens a connection to it, and to the shell's socket, before it
tells you the session is ready. A crashed previous session leaves a socket file with nothing behind
it, and checking only for the file is how a tool reports "ready" and then fails.

`opened your shell, in a new terminal window` and `opened the desktop viewer` are the two windows.
They are new windows on purpose: the terminal you typed in is not taken over, because it is where
the health reports go for the rest of the session.

---

## 4. The ideas you need

This section explains the mechanisms oillamp is built from. If you already know what a user
namespace is, skip to section 5.

### 4.1 A container is not a virtual machine

A **virtual machine** simulates a whole computer, with its own kernel, memory management and
virtual disks. Starting one takes tens of seconds and uses hundreds of megabytes of memory before
anything useful happens.

A **container** is different, and the difference matters for oillamp's security. There is only one
kernel: yours. A process in a container is an ordinary process on your machine; you can see it
with `ps` on the host. What makes it a container is that the kernel has been told to give it a
different view of a few things:

- **which files exist** (it sees a different root directory),
- **which other processes exist** (it sees only its own),
- **which network interfaces exist**,
- **which user ids mean what.** This one matters most here; section 4.3 is about it.

Each of these different views is a kernel feature called a **namespace**. A container is a process
started with its own set of namespaces.

The consequence: containers start in milliseconds and cost almost nothing, but the isolation is
only as good as the kernel's enforcement of those namespaces. A virtual machine offers a smaller
attack surface. A container is far easier to use. oillamp uses a container, and section 6.5 says
what that means for security.

### 4.2 Rootless

Traditionally, running containers required a background service running as the system
administrator (root). Asking that service to start a container was almost the same as asking it to
run a command as root on your machine.

**Rootless** containers remove that. podman runs as *you*, as an ordinary program, with no
background service and no special privileges. The container's processes are your processes. If
something escapes the container, it escapes into *your* account, not into root.

oillamp requires rootless podman and refuses to run any other way. This is the most important
single thing between an agent and your machine: with a root-owned container service, a container
escape would compromise the whole computer. Rootless reduces the worst case to "the agent can do
what you can do", and the uid map in section 4.3 reduces it further.

### 4.3 User namespaces and the uid map

This is the central mechanism of oillamp. Read it slowly.

On Linux, every file is owned by a number, and every process runs as a number. `/etc/passwd` maps
those numbers to names for convenience, but the kernel only compares numbers. Your account is
probably number 1000 or 1001.

A **user namespace** gives a process its own set of numbers, plus a translation table to the real
ones. The table is called the **uid map**. A process inside the namespace sees itself as one number;
when it tries to open a file, the kernel checks permissions using the translated number.

Here is the uid map oillamp uses, read from the kernel on a real machine:

| Number inside the container | Name inside | Number on your machine | What that number can do on your machine |
|---|---|---|---|
| 0 | `root` | **165536** | Nothing. It owns no file on your system. |
| 1000 | `agent` | **1001** (your account) | Everything *you* can do. |
| 1001 | `lamp` | **166536** | Nothing. It owns no file on your system. |

Three things follow from this table. Together they are the security design.

**First, "root" inside the container is not root.** A process running as uid 0 in the container
believes it is the administrator. Inside the container it can install packages, edit `/etc` and
create users. When it touches anything that belongs to the real system, the kernel translates its
identity to 165536, and 165536 owns nothing and may do nothing.

**Second, the agent is you, on purpose.** Container uid 1000 maps to your real account. When the
agent writes a file in its home directory, that file appears on your machine owned by *you*. You
can read it, edit it in your own editor and commit it to Git without changing ownership. This is a
convenience with a cost, which section 6.5 explains.

**Third, the infrastructure is a stranger to both of you.** Container uid 1001 runs the desktop
compositor, the screen recorder and the network bridges. It maps to 166536, which is neither you
nor the agent. The agent cannot stop those processes, cannot use their private control sockets, and
cannot change or delete the recording of its own screen. Section 6 shows this being tested.

### 4.4 Subordinate uids: where 165536 comes from

The numbers 165536 and 166536 are not invented by podman. They come from a file on your system:

```
$ grep $USER /etc/subuid
dnepp:165536:65536
```

This line says: *the user `dnepp` may use the 65536 user ids starting at 165536.* These are
**subordinate user ids**: a block of numbers given to your account for exactly this purpose. They
belong to no real user and no login, and own no file anywhere unless one of your containers creates
it.

Most distributions create this line when your account is created. If it is missing, rootless
containers cannot work. oillamp then adds a free range for you with `sudo usermod`, or prints the
command if it is not allowed to.

Now the table in section 4.3 can be read completely. podman is started with
`--userns=keep-id:uid=1000,gid=1000`, which means *keep my identity, but call me 1000 inside*. So:

- container uid 1000 becomes your real uid, because of that instruction;
- every other container uid is taken in order from your subordinate block, so container 0 becomes
  165536 and container 1001 becomes 166536.

You can see this for yourself:

```sh
# Create two files inside a container, as two different container users
podman run --rm --userns=keep-id:uid=1000,gid=1000 --user 0:0 -v /tmp/out:/out \
    --entrypoint /bin/sh localhost/oillamp/sandbox:<tag> -c '
        touch /out/by-root
        setpriv --reuid=agent --regid=agent --init-groups -- touch /out/by-agent'

# Now look at who owns them on your machine
stat -c '%n  %u (%U)' /tmp/out/*
#   /tmp/out/by-agent   1001 (dnepp)      ← you
#   /tmp/out/by-root  165536 (UNKNOWN)    ← nobody at all
```

The file created by the container's "root" belongs to a user your system cannot even name.

### 4.5 Images, layers, and why the tag is a fingerprint

An **image** is a frozen filesystem plus a note saying which program to start. A **container** is
one running instance of an image. The relationship is like a program on disk and a running
process: many containers can run from one image, and the image itself never changes.

Images are built from a recipe file. oillamp's is
[`src/main/resources/image/Containerfile`](src/main/resources/image/Containerfile). Each instruction
in it produces a **layer**: a record of the changes that instruction made to the filesystem.
Layers are cached, so changing the last instruction rebuilds only the last layer. That is why the
slowest and least-changed parts (the base system, the desktop packages) come first in the file,
and the parts that change during development come last.

Images are usually named by hand, like `myapp:latest`. oillamp instead computes a fingerprint of
every input to the build (the recipe, every file copied in, the package list, the base image name)
and uses it as the tag:

```
localhost/oillamp/sandbox:0a6f7ffa4c89ca64
```

If an image with that tag exists, it was built from exactly these inputs, so the build is skipped.
If it does not exist, something changed, so oillamp builds it. The code is
[`ImageResources.hashOf`](src/main/java/dev/oillamp/ImageResources.java).

### 4.6 Wayland, compositors, and "headless"

The agent needs a graphical desktop: to run a browser, to test a GUI application, to see what it
is doing. Providing one inside a container requires one piece of Linux graphics knowledge.

On Linux there is no single graphics system built into the kernel. A program, running as an
ordinary process, owns the screen. Every application that wants to draw connects to that program
over a socket, sends it pictures, and receives keyboard and mouse events back. That program is
called a **compositor**, because it combines (composites) all the application windows into the one
image that goes to the monitor.

**Wayland** is the protocol they use to talk. It is not a program; it is a specification of the
messages. The compositor is the server and the applications are its clients.

oillamp's compositor is **sway**. Normally sway drives a real monitor. oillamp starts it in
**headless** mode, where it creates a screen that exists only in memory. There is no monitor and
no cable, but to every application inside, there is an ordinary 1920 × 1080 display called
`HEADLESS-1`. A browser inside the container neither knows nor cares that its pixels go into a
buffer instead of onto a screen.

### 4.7 VNC, and how you see the desktop

The desktop exists only in the container's memory. To show it to you, oillamp runs **wayvnc**, a
program that connects to sway as a Wayland client, reads the screen contents and serves them using
**VNC**, an old, simple and widely supported protocol for sending a remote screen as images and
receiving keyboard and mouse input back.

Your viewer window is an ordinary VNC client. It does not connect over the network, because the
container has none (section 7). It connects through a **Unix domain socket**: a special file that
two processes on the same machine use like a network connection. The file is
`<lamp>/.oillamp/sockets/infra/vnc.sock`. It is created by the container's infrastructure user,
and wayvnc's control socket (which could disconnect viewers) is kept where the agent cannot reach
it.

---

## 5. What is in a lamp directory

A **lamp** is one directory. Everything about one sandbox lives in it. Here is a real one:

```
~/lamps/first/
├── oillamp.toml                 the configuration you edit
├── README.txt                   a note to whoever finds this directory
│
├── .oillamp/                    oillamp's own state; the agent never sees this directory
│   ├── lamp.json                the lamp's identity: its agent id and format version
│   ├── session.json             details of the running session, if there is one
│   ├── lock                     held while a session runs, so that two cannot start at once
│   ├── keys/                    the SSH key pair for reaching the sandbox
│   ├── ssh_config               a ready-made SSH configuration pointing at this sandbox
│   ├── known_hosts              so your SSH client trusts this sandbox and no other
│   ├── session/                 files given to the container, read-only, for this run
│   ├── image/                   the image build files, extracted from the program when needed
│   ├── sockets/                 the Unix sockets that connect host and container
│   │   ├── host/                owned by you: the network proxy
│   │   ├── infra/               owned by the container's infrastructure user: the desktop
│   │   └── agent/               the SSH entry point
│   ├── recordings/              screen recordings, owned by the infrastructure user
│   └── logs/                    the network log of each session
│
└── agent-lamp-v4elchzj/         THE AGENT'S ENTIRE WORLD
    ├── AGENTS.md                written each session: how this sandbox works
    ├── .bashrc                  so that a scripted `ssh` command gets a working environment
    ├── workspace/               where repositories go
    ├── libs/                    native libraries, on the library search path
    └── screenshots/             where `lamp screenshot` writes
```

The split into two levels is the point of the layout:

**The agent sees `agent-lamp-v4elchzj/` and nothing else.** That one directory is attached to the
container as `/home/agent`. The configuration file with the network policy, the keys, the logs and
the recordings are all *beside* it, never inside it. The agent cannot rewrite the rules that
restrict it, because they are not in any directory it can reach.

The `agent-lamp-<id>` name has a second purpose: if you copy that directory somewhere else, it is
obviously an agent's home belonging to a particular lamp, so it cannot be mistaken for a lamp.

### 5.1 The configuration file

`oillamp.toml` is created with every setting present and commented. A fragment:

```toml
[display]
width  = 1920
height = 1080
gpu    = "auto"           # "auto" | "on" | "off"

[recording]
enabled        = false    # true to record the desktop for the whole session
codec          = "libx264"
crf            = 30       # quality, lower is better and bigger
max_fps        = 10
max_age_days   = 14       # delete recordings older than this
max_total_gb   = 20       # delete the oldest until the total fits

[network]
default        = "allow"  # applies when no rule matches
```

Recording is **off** unless you turn it on. A tool that watches a screen should not start
watching because nobody read the defaults.

A few settings are accepted but not used yet. The full list of settings, and which ones those are,
is in [ARCHITECTURE.md, section 10](docs/ARCHITECTURE.md#10-configuration-reference).

An optional file `~/.config/oillamp/config.toml` sets defaults for all your lamps. A lamp's own
file wins over it, key by key. Lists (such as network rules) are replaced, not merged: if a lamp
lists its own rules, it gets exactly those rules.

---

## 6. The boundary: two users, and what separates them

Section 4.3 introduced the uid map. This section is about what it achieves.

### 6.1 Who runs what

Inside the container there are two accounts:

**`agent` (uid 1000, which is you)** runs everything the AI agent does: the shell you log into over
SSH, the coding harnesses, a browser, any GUI application.

**`lamp` (uid 1001, which is 166536 on your machine)** runs the infrastructure: the sway
compositor, the wayvnc server, the screen recorder, and the relays that carry network traffic. It
is called the **infrastructure user** in these documents.

They share a container and a filesystem, but they are different users. The container's first
program starts each of these processes with **no Linux capabilities** (the special permissions that
let a process act as another user or override file permissions), and the image contains no setuid
programs (programs that switch to their owner's identity when run). So there is no way for the
agent to become `lamp` or to gain privileges.

### 6.2 What that prevents, tested

These are real results from a running sandbox, run as `agent`:

| The agent tried | What happened |
|---|---|
| `pkill -9 sway` (kill the compositor) | `Operation not permitted` |
| `kill -9 <wf-recorder>` (kill the recorder) | `Operation not permitted` |
| `ls /run/lamp/` (reach sway's control socket) | `Permission denied` |
| overwrite a screen recording | refused |
| `rm` a screen recording | `Permission denied` |
| create a file among the recordings | `Permission denied` |
| **read** a recording of its own screen | **succeeded** |

The last row is deliberate. The recordings directory is readable by everyone, because *you* need
to read it, and to the infrastructure user you and the agent are the same kind of outsider. Making
it unreadable to the agent would make it unreadable to you. Reading a recording gives the agent
nothing: it cannot change or delete it, so the record stays trustworthy.

### 6.3 How the container is started

The exact command, with the security-relevant flags explained:

```sh
podman run --detach --name oillamp-v4elchzj \
    --network=none \                              # no network interfaces at all
    --read-only \                                 # the image's filesystem cannot be changed
    --user 0:0 \                                  # start as container root, which is nobody on the host
    --userns=keep-id:uid=1000,gid=1000 \          # the uid map of section 4.3
    --tmpfs /run:rw,mode=755 \                    # writable scratch space, in memory, gone at exit
    --tmpfs /tmp:rw,mode=1777 \
    --memory 16g --cpus 15 --pids-limit 8192 \    # resource limits
    --label oillamp.agent-id=v4elchzj ... \       # so oillamp can find its containers again
    --volume <lamp>/.oillamp/session:/oillamp/session:ro \
    --volume <lamp>/.oillamp/sockets:/oillamp/sockets \
    --volume <lamp>/.oillamp/recordings:/oillamp/recordings \
    --volume <lamp>/agent-lamp-v4elchzj:/home/agent \
    localhost/oillamp/sandbox:0a6f7ffa4c89ca64
```

`oillamp at <dir> --dry-run --verbose` prints the exact command for your lamp.

`--read-only` deserves attention. The image's filesystem cannot be changed while the container
runs. The only writable places are the attached directories and the two in-memory `tmpfs` areas.
So anything the agent installs system-wide is gone when the session ends, and anything it puts in
its home directory stays. The kernel enforces this.

A **volume** (the `--volume` lines, also called a bind mount) attaches a directory from your
machine into the container at a chosen path. `/home/agent` is one of these. Anything the image
itself put at `/home/agent` when it was built is therefore *hidden* at run time, underneath the
attached directory. This is a real trap, and it is why SDKMAN and pi's settings are installed into
`/usr/local/share/oillamp/` during the build and copied into the agent's home when a session
starts.

There is no podman flag that removes capabilities. Container root keeps the default capabilities
it needs to create the users' runtime directories and switch users, and the container's first
program (the entrypoint) removes all capabilities from every process it starts.

### 6.4 Why deleting a lamp needs a command

Some files in a lamp belong to host uid 166536, the infrastructure user. You are uid 1001. You
cannot delete another user's files, and you are not root, so:

```
$ rm -rf ~/lamps/first
rm: cannot remove '.../.oillamp/sockets/infra/vnc.sock': Permission denied
```

This is the protection of section 6.2 working, seen from the one angle where it is inconvenient.
The answer is `oillamp remove`, which deletes those files from inside podman's user namespace,
where your account *is* allowed to act as its subordinate ids.

```sh
oillamp remove ~/lamps/first --yes
```

Without `--yes` it prints what would be deleted and exits without touching anything. It refuses
while a sandbox is running. It deletes only what oillamp created, so your own notes beside
`oillamp.toml` survive, unless removing oillamp's files leaves the directory empty, in which case
the directory is removed too. It also works on a lamp you already tried to delete with `rm -rf`.

### 6.5 What this does not protect against

Knowing the limits is part of owning the tool.

- **The agent runs as you.** Container uid 1000 is your real account. The isolation comes from what
  the container can see, not from file permissions. A flaw in the kernel's container isolation, or
  a mistake that attaches the wrong directory, gives the agent your rights: not root's, but yours.
- **This is a container, not a virtual machine.** There is one kernel, shared. A kernel
  vulnerability reachable from inside is a real way out. A virtual machine would be stronger and
  much less convenient.
- **The agent can reach the internet by default.** This is deliberate: an agent that cannot
  install a package or read documentation is of little use. The policy is configurable
  (section 7). But the default is "the open web", and data the agent can read, it can send.
- **The resource limits are ceilings, not guarantees.** `--memory`, `--cpus` and `--pids-limit`
  stop a runaway process from taking the machine down. They do not stop the agent from using
  everything up to those limits.
- **The threat model is your machine, not the web.** oillamp is built to stop an agent reaching
  your files, your keys and your other projects. It is not built to stop an attacker who already
  controls the agent from talking to the internet.

---

## 7. The network

### 7.1 The problem

Two requirements seem to contradict each other:

1. The agent must reach the internet. It needs to run `npm install`, `pip install` and
   `git clone`, and read documentation.
2. The agent must not reach *your* machine or network: not your other containers, not a database
   on `localhost`, not your router's admin page, not a service on your company network.

A normal container gets a virtual network interface, and with it an address that can reach your
local network. Firewall rules could restrict that, but they are machine-wide settings, they need
root to install, and a mistake in them fails open.

### 7.2 The solution: no network at all, plus a proxy

oillamp starts the container with **`--network=none`**. This is not a firewall rule. The container
has no network interfaces except loopback. There is no address it could send a packet to, and
nothing to misconfigure.

The internet is reached by a different route:

```
 a program in the sandbox (curl, npm, pip)
   │   HTTPS_PROXY=http://127.0.0.1:3128
   ▼
 127.0.0.1:3128  inside the container: a socat relay, run by `lamp`
   │
   │   a Unix domain socket: a file, not a network connection
   ▼
 <lamp>/.oillamp/sockets/host/proxy.sock
   │
   ▼
 oillamp, running on your machine as you
   │   1. resolve the host name to addresses
   │   2. check each address against the policy in oillamp.toml
   │   3. connect, or refuse with 403 and the name of the rule
   ▼
 the internet
```

Every program in the container is told, through environment variables, to send its web traffic to
`127.0.0.1:3128`. A small program called `socat` listens there and forwards each connection into a
Unix socket. The container still has no network.

On the other side of that socket is oillamp itself, which applies the policy.

### 7.3 Why the policy is checked against the resolved address

A rule that says "deny 192.168.0.0/16" must not be bypassed by a host name that *resolves* to
192.168.1.5. So oillamp resolves the name first and applies the policy to each address it got. A
host name that points into your private network is refused because of where it points, not
because of what it is called.

A refusal is an HTTP 403 whose body names the rule that refused it, and it is printed in your
status terminal as it happens. A denial that looked like a network timeout would waste the agent's
time and yours.

### 7.4 Configuring it

```toml
[network]
default = "allow"                         # or "deny"

[[network.rules]]
label  = "company artifact mirror"
action = "allow"
hosts  = ["nexus.corp.example.com"]
ports  = [443]

[[network.rules]]
label  = "block private, internal and loopback ranges"
action = "deny"
cidrs  = ["10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "100.64.0.0/10",
          "127.0.0.0/8", "169.254.0.0/16", "0.0.0.0/8",
          "::1/128", "fc00::/7", "fe80::/10"]
```

Rules are checked from top to bottom and the first match wins. The shipped configuration contains
the second rule above, which blocks private networks and your machine's own loopback. To allow an
internal service, put an `allow` rule *above* it. If you write your own list of rules, include the
blocking rule again: your list replaces the default list rather than adding to it.

Every connection, allowed or denied, is written to `.oillamp/logs/network-<session>.jsonl`, one
JSON object per line.

### 7.5 Forwards, for things that are not the web

A **forward** makes one specific service reachable inside the container on a fixed local port,
typically a language-model service on your own machine or company network. It uses the same socket
mechanism, but point to point: the container gets `127.0.0.1:<port>` connected to exactly one
destination, with no policy check, because you chose the destination in the configuration file.

```toml
[[network.forwards]]
name   = "llm"
port   = 8000
target = "llm.corp.example.com:8000"
```

---

## 8. The desktop

### 8.1 The processes

Inside the container, these programs run as the infrastructure user `lamp`:

| Program | What it does |
|---|---|
| **sway** | The Wayland compositor. Creates the in-memory display `HEADLESS-1` and manages windows. |
| **swaybg** | Draws the wallpaper. |
| **wayvnc** | Connects to sway, and serves the screen over VNC to your viewer. |
| **wf-recorder** | Connects to sway, and writes the screen to an `.mkv` file. Only if recording is enabled. |

The agent's programs connect to sway as ordinary Wayland clients, through a socket the agent can
reach. What the agent cannot reach is sway's *control* socket, which would let it command the
compositor: move windows, change the screen, or run commands as `lamp`.

### 8.2 Driving the desktop from a shell

An agent works through a terminal, so it needs a way to use a desktop without a mouse. That is
`lamp`, a small shell script inside the sandbox:

```sh
lamp screenshot                    # capture the screen, print the path
lamp screenshot --region 0,0,800,600
lamp click 960 540                 # click at screen coordinates, as in a screenshot
lamp type "hello"                  # type text
lamp key ctrl+shift+t              # press a key combination
lamp wait-stable 10                # wait until the screen stops changing
lamp info                          # size, renderer, where screenshots go
```

It is deliberately a small shell script: each command is one line wrapping a tool (`grim` for
screenshots, `wtype` for the keyboard, and a small helper that sends mouse input through the
desktop's VNC server, the same way your viewer does). An agent can read it with
`cat $(command -v lamp)` and call those tools directly when the script does not do what it needs.
It works the same for Wayland and X11 applications, including Java Swing.

`wait-stable` is the command that makes automation reliable. It takes two screenshots half a second
apart and returns when they are identical. That is a much better way to know an application has
finished starting than waiting a guessed number of seconds.

### 8.3 Recording

When `recording.enabled = true`, `wf-recorder` records the whole session to
`.oillamp/recordings/<session>.mkv`. The file is owned by `lamp`, so the agent cannot change it.

```sh
oillamp recordings ~/lamps/first                         # list them
oillamp recordings ~/lamps/first --open 20260923-085055  # play one
oillamp recordings ~/lamps/first --prune                 # apply the retention limits now
```

Retention has two independent limits, an age and a total size. A recording is deleted if *either*
says so. Retention runs automatically at the start of every session, so a lamp used every day does
not slowly fill your disk.

### 8.4 The graphics card

By default (`gpu = "auto"`) oillamp uses your graphics card if it can and falls back to drawing
with the processor if it cannot, saying which and why. It never refuses to start a session because
of graphics.

Using the card requires three things: a render device at `/dev/dri/renderD128`; your user being a
member of the group that owns it (usually `render`); and podman using **crun**, because passing a
group into a container across a user namespace needs `--group-add keep-groups`, which only crun
supports.

If you are not in the group, oillamp prints the exact command, and explains that a new group
membership only applies to programs started after you log in again:

```
[lamp]    · you are not in the 'render' group that owns /dev/dri/renderD128
           run this on this machine, in your own terminal, then log out and back in — a new group only reaches processes started after a fresh login:
               sudo usermod -aG render dnepp
```

oillamp does not run that command itself. It changes your account rather than the lamp, it needs
root, and it has no effect until you log in again, so running it silently would change your system
and still leave the session drawing with the processor.

---

## 9. What is inside the image

The recipe is [`src/main/resources/image/Containerfile`](src/main/resources/image/Containerfile),
about 120 lines with comments. The built image is roughly 3.5 GB.

**The base** is Debian 13 "trixie". Debian rather than Ubuntu for one concrete reason: Ubuntu ships
Firefox as a snap package, and snaps do not work inside containers. Debian ships it as an ordinary
package.

**An ordinary shell:** `bash`, `coreutils`, `procps`, `less`, `grep`, `sed`, `awk`, `tar`, `ping`,
`dnsutils`, `vim`, `nano`, `htop`, `tmux`, `tree`, `lsof`, `man` and more. These are always
installed. An agent that cannot inspect its own environment wastes its turns guessing, and a
sandbox without `ping` or a process viewer feels broken rather than minimal.

**The desktop:** `sway`, `xwayland` (so X11 applications, including Java Swing, still work),
`wayvnc`, `wf-recorder`, `grim` and `slurp` (screenshots), `wtype` and `wlrctl` (keyboard and mouse
input), `foot` (a terminal emulator), Mesa graphics drivers and fonts.

**Development tools:** `git`, `build-essential`, `cmake`, `gdb`, `strace`, `ripgrep`, `jq`,
Python 3, Node.js, Firefox ESR and a Temurin JDK.

**SDKMAN**, at `~/.sdkman`, so an agent can install whatever JDK, Groovy or Gradle version a project
needs:

```sh
sdk install java 21.0.12+1.1-tem
sdk install gradle 9.7.1
```

It is configured to answer its own questions, because an agent connected over SSH cannot answer
"Do you want java 21 to be set as default? (Y/n)", and every such question would be a command that
never finishes.

**AI coding harnesses:** `opencode` and `pi` are installed during the build. If installing them
fails, the build still succeeds: a sandbox without a harness still has a desktop, a shell and a
working SSH, and losing all of that because a package registry was briefly unreachable would be a
poor trade.

**Deliberately absent:** no program in the image is setuid. The build removes the setuid bit from
every file. oillamp's guarantees come from the user namespace, and a setuid program inside the
container could let the agent become `lamp`.

---

## 10. The code, briefly

About 12,400 lines of Java 25 in one package, `dev.oillamp`.
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) describes it in detail; this is the outline.

### 10.1 Five public types

The whole package is package-private except five types. A test keeps it that way.

| Type | What it is |
|---|---|
| `OilLamp` | The entry point: `OilLamp.on(machine).run(args)`. |
| `Machine` | The interface to the outside world. Every effect the program can have goes through it. |
| `LampEvent` | Something oillamp reports, as a value. |
| `Problem` | Something that went wrong, with evidence and suggested fixes. |
| `ExitStatus` | The process exit code, by name. |

Everything else is an internal detail that should stay free to change. If `LampPlanner` were
public, its shape would become a promise to the outside world.

### 10.2 Deciding and doing are separate

Most of the code *decides*: pure functions that take values and return values, with no file
access, no clock, no randomness and no processes. Given the same inputs they always produce the
same output, so they can be tested thoroughly without a container.

A small number of classes *do* things, and they go through `Machine` wherever possible.

A test named *"The code that decides things does not also perform them"* fails the build if a class
outside an explicit list uses files (`java.nio.file.Files`), processes, threads, `System` or
`SecureRandom`. The list is in `TheShapeOfTheCodeSpec.groovy`, with the reason for each entry.

This is why the test suite runs in seconds without podman.

### 10.3 Phases, plans and steps

Starting a session happens in phases, in order. Each has the same shape:

1. **Probe:** ask the machine questions, and collect the answers into a record of facts.
2. **Plan:** a pure function turns those facts into a `Plan`, a list of `Step` values that describe
   what should happen. Nothing has happened yet.
3. **Run:** `StepRunner` carries out the steps.

| Phase | What it does |
|---|---|
| `HostPhase` | Checks this machine; installs missing packages and adds subordinate ids if needed. |
| `LampPhase` | Checks the directory; creates the layout, generates keys, applies recording retention, writes the session files. |
| `SandboxPhase` | Builds the image if the fingerprint requires it; starts the container; waits until every socket answers. |
| `Supervisor` | Runs the session: relays SSH, runs the proxy, reports health, shuts everything down. |

This is what makes `--dry-run` trustworthy. Because a `Plan` is data, and the only place that
carries steps out is `StepRunner`, `oillamp at <dir> --dry-run` prints the complete plan (every
package, every file with its permissions, every command) and changes nothing. There is no separate
preview code that could drift away from what really happens.

### 10.4 The `Machine` interface

`Machine` has two implementations:

- **`RealMachine`** runs real commands and reads real system files.
- **`SimulatedMachine`** pretends to be a machine described in a test: a particular Linux
  distribution, a particular podman version, some packages installed, some commands failing.

This lets the tests describe situations precisely:

```groovy
sandbox.machine { it.debian('13').withoutPodman().processors(2) }
```

One exception: **files in the lamp directory are real, even in tests.** oillamp's isolation is
made of permission bits, ownership and symbolic links, and a simulated filesystem that approximated
those would hide bugs exactly where they matter most. Tests write to a real temporary directory.

### 10.5 Where to start reading

In this order:

1. **`OilLamp.java`**: the entry point, 160 lines.
2. **`Machine.java`**: every effect the program can have. Read it as a list of everything oillamp
   is able to do to your computer.
3. **`Step.java`**: every kind of change oillamp can make during setup.
4. **`LampPlanner.java`**: a pure function turning facts into a plan. The clearest example of the
   style of the whole code base.
5. **`SandboxPhase.java`**: where the container command is built, including the security flags of
   section 6.3.
6. **`src/main/resources/image/rootfs/usr/local/lib/oillamp/entrypoint`**: the shell script that
   is the container's first process. It starts every process in section 8 and watches them.

---

## 11. Building it yourself

You need a JDK 25. Nothing else: Gradle downloads itself through the wrapper.

```sh
./gradlew build          # compile, run the 98 scenarios, check the architecture rules
./gradlew singleFile     # produce build/dist/oillamp, the whole program in one file
./gradlew installDist    # a conventional bin/lib layout, for development
./gradlew spikes         # the tests that need real podman (slow, need a network)
```

### 11.1 How the single file works

`build/dist/oillamp` is a shell script with a compressed archive attached to the end. The script is
[`src/packaging/launcher.sh`](src/packaging/launcher.sh), about 90 lines, and it is meant to be
read. To print just that part of the built file:

```sh
sed -n '1,/^__OILLAMP_PAYLOAD_BELOW__$/p' build/dist/oillamp
```

When you run it, it unpacks itself once into `~/.cache/oillamp/<version>-<fingerprint>/` and then
replaces itself with the Java runtime it unpacked. After the first run, starting oillamp takes
about 140 milliseconds.

The directory name contains a fingerprint of that exact file, so two builds never share an
unpacked copy, and upgrading means replacing the file. `oillamp --where` prints the directory.
Deleting the file and that directory removes oillamp completely; nothing else is written outside
your cache directory.

The bundled Java runtime is built with `jlink` and contains four modules (`java.base`,
`java.desktop`, `java.sql`, `jdk.charsets`) rather than a whole JDK. That is why the file is 40 MB
rather than 300.

---

## 12. How it is tested

### 12.1 Scenarios

The tests are written in Spock, a Groovy testing framework. They are called **scenarios** because
each one describes a situation a user can be in, not a method's return value. They live in
`src/test/groovy/oillamp/`, in a package *outside* `dev.oillamp`, so they can only use the five
public types, like any other caller.

Each scenario starts with a `reportInfo` block explaining what it is about and why it matters.
When you run the build, these are written as Markdown to `build/spock-reports/`, so they can be
read without opening the code.

```groovy
def 'A lamp whose sandbox is still up is not removed out from under it'() {
    reportInfo """
        Deleting the agent's home while a container has it mounted would leave the session
        working in directories that no longer exist, and the container would outlive everything
        that describes it, leaving a sandbox with nothing left to stop it with.

        The answer names `oillamp stop`, which is also what clears up a container left behind
        by a supervisor that died. Both cases need the same instruction, so they get the same
        message.
    """
    ...
}
```

These blocks are written for a reader who has not seen any other document. They explain terms
such as *subordinate user id* rather than assuming them.

The 98 scenarios run in well under a minute. They need no podman, no network and no graphical
session, because they run against `SimulatedMachine`.

### 12.2 Spikes

A simulation only knows what its author already believed. A second suite, the **spikes**, checks
assumptions about third-party tools that no simulation can confirm: that sway accepts a custom
headless screen size, that an `.mkv` file can still be played after the recorder is stopped, that a
particular podman flag does what its documentation says.

These pull images and start containers, so they are slow and need a network. They run with
`./gradlew spikes`, separately from `build`, so that the normal build stays fast and does not need
podman.

### 12.3 Verification on real hardware

[docs/STATUS.md](docs/STATUS.md) records what has been run on a real machine, and the bugs that
turned up: a recording setting that was read and silently ignored, a shell that got no environment
when a command was run over SSH from a script, a lamp that could be deleted while its container was
still running. Each says what was wrong and what changed.

---

## 13. Taking ownership

A short guide to making this code base yours.

**Start by running it with `--dry-run`.** `oillamp at /tmp/x --dry-run --verbose` prints every
single thing a real run would do, including the full `podman run` command, and changes nothing.
Read that output next to `LampPlanner.java` and the program stops being a black box.

**Then read `Machine.java` from start to end.** It lists the effects oillamp can have on your
computer. The main exceptions are the lamp directory's files (`Filesystem.java`) and the sockets a
running session uses (`Relay.java`, `Control.java`, `Egress.java`). It takes about fifteen minutes.

**The architecture test protects the design.** Adding file or process access to a deciding class
fails the build with a message naming the class. If you decide a rule is wrong, the list is in
`TheShapeOfTheCodeSpec.groovy`, and changing it is a deliberate, visible act.

**Adding a `Step` forces a decision.** `Step.detail()` and `StepRunner` use `switch` statements
without a `default` branch, so a new kind of step does not compile until you have said how it is
carried out and how it is described to the user.

**The difficult parts**, where reality is more awkward than the design:

- **The `entrypoint`** is a 290-line bash script running as the container's first process. It is
  the least type-checked part of the system and the one with the biggest consequences when it
  breaks. `TheSandboxImageSpec` at least checks that it parses, and the spikes run it for real.
- **Unix socket paths are limited to 107 bytes** by the kernel. Lamp directories can be anywhere,
  and their paths are easily longer. So the sockets are reached through a short directory under
  `$XDG_RUNTIME_DIR`, which contains a symbolic link into the lamp. Any change that moves a socket
  must respect this.
- **`/home/agent` is a bind mount**, so anything the image puts there at build time is hidden when
  the container runs. SDKMAN and pi's settings are affected already and are copied in by the
  entrypoint; any new tool that installs into the home directory will need the same treatment.
- **The agent runs as your uid.** Re-read section 6.5 before changing anything about the uid map.

**Record your decisions.** When you change a design decision, update
[docs/DECISIONS.md](docs/DECISIONS.md). When you find a gap between what the code does and what
anything says it does, add it to [docs/STATUS.md](docs/STATUS.md). The value is that the reasons for
the current design can be recovered years later.

---

## Version and status

Version 0.1.0. Everything planned for the first version works on Ubuntu 24.04, except a proxy
setting for Firefox and configuring the agent harnesses for a company LLM. See
[docs/STATUS.md](docs/STATUS.md) for exactly what has been verified and what is still open.
