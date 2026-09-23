# oillamp

**oillamp gives an AI coding agent its own Linux computer — with a graphical desktop — that runs
on your machine but cannot reach your machine.**

You run one command. A few seconds later you have two new windows: a terminal logged into a
sandboxed Linux system, and a viewer showing that system's desktop. An agent working in there can
install packages, run a browser, open a GUI application, take screenshots of its own screen and
click on its own windows. It can reach the internet. It cannot read your home directory, your SSH
keys, your other projects, or anything else on your computer.

When you are finished, one command removes the whole thing.

---

## Table of contents

1. [Who this document is for](#1-who-this-document-is-for)
2. [Getting it running](#2-getting-it-running)
3. [Reading the startup output, line by line](#3-reading-the-startup-output-line-by-line)
4. [The ideas you need](#4-the-ideas-you-need)
5. [The anatomy of a lamp](#5-the-anatomy-of-a-lamp)
6. [The boundary: two users, and what separates them](#6-the-boundary-two-users-and-what-separates-them)
7. [The network](#7-the-network)
8. [The desktop](#8-the-desktop)
9. [What is inside the image](#9-what-is-inside-the-image)
10. [The code](#10-the-code)
11. [Building it yourself](#11-building-it-yourself)
12. [How it is tested](#12-how-it-is-tested)
13. [Taking ownership](#13-taking-ownership)

---

## 1. Who this document is for

You are a programmer. You are comfortable with a terminal, with Git, and with a JVM language. You
have probably used Docker to run a database for a test suite.

You are **not** a Linux virtualization specialist. You have not read the kernel's user-namespace
documentation. When somebody says "rootless container with a uid map", you have a vague sense that
this is about isolation and no clear picture of the mechanism.

This document assumes exactly that. Every term is defined the first time it is used. Nothing is
left as "as discussed above" or "the usual approach". Where oillamp makes a choice, the reason is
stated, and where the reason is a trade-off, both sides are given.

There is a second document, [`oillamp-design-spec.md`](oillamp-design-spec.md), which is the
detailed specification: every requirement, every decision with an identifier, the exact contents of
every configuration file. It is a reference, not a tutorial — it is written for somebody who has
already read this README. There is also [`docs/STATUS.md`](docs/STATUS.md), which records what has
actually been built and verified on real hardware, including the bugs found along the way.

Read this file first. It should take about forty minutes, and at the end you should be able to
open any source file in this repository and know what you are looking at.

---

## 2. Getting it running

### 2.1 What you need on your machine

- **Linux, on a 64-bit Intel or AMD processor.** oillamp has been developed and verified on
  Ubuntu 24.04. Other distributions should work; they have not been tested.
- **A graphical desktop session**, because oillamp opens windows. Wayland or X11 both work.
- **`podman`**, which is the program that actually runs containers. If it is missing, oillamp
  offers to install it for you.

You do **not** need Java installed. You do **not** need Docker. You do not need to be root,
although installing podman the first time will ask for your password, because installing system
packages requires it.

### 2.2 Get the file

The whole program is one executable file of about 40 MB. Build it with:

```sh
./gradlew singleFile
```

which produces `build/dist/oillamp`. Copy that file anywhere — another machine, a USB stick, a
shared drive. Nothing needs to be installed on the machine that receives it.

```sh
cp build/dist/oillamp ~/bin/oillamp      # or /usr/local/bin, or leave it where it is
```

### 2.3 Check your machine

```sh
oillamp doctor
```

This changes nothing. It inspects your system and reports everything that would stop a sandbox
from starting, all at once rather than one failure at a time, and tells you the exact command that
fixes each one. On a healthy machine:

```
🪔 oillamp 0.1.0
[host]    ✓ Ubuntu 24.04.5 LTS, Wayland (ubuntu:GNOME)
[host]    ✓ podman 4.9.3, rootless, crun
[host]    ✓ this machine can run oillamp sandboxes
```

### 2.4 Start a sandbox

```sh
oillamp at ~/lamps/first --init
```

`~/lamps/first` is a directory that oillamp will create and fill. The name is yours to choose;
this directory is the sandbox, and everything belonging to it lives inside. The `--init` flag
means "this is a new one, please set it up".

The first run takes several minutes, because it builds a container image — roughly 3.5 GB of
Debian, a Java toolchain, Node.js, a browser and a desktop. Later runs on the same machine reuse
that image and start in a few seconds.

When it finishes, **two new windows open**: a terminal and a desktop viewer. The terminal you
typed in stays where it is and becomes a status display, reporting the sandbox's health until the
session ends.

### 2.5 Finish

Any one of these ends the session:

- close the terminal window that opened,
- press `Ctrl-C` in the terminal you started from,
- run `oillamp stop ~/lamps/first` from anywhere.

The sandbox shuts down, the container is removed, and the files in `~/lamps/first` stay on disk.
Running `oillamp at ~/lamps/first` again — without `--init` this time — brings it all back,
including whatever the agent installed and wrote.

To delete it permanently:

```sh
oillamp remove ~/lamps/first --yes
```

Without `--yes`, it prints exactly what would be deleted and stops. Section 6.4 explains why this
needs its own command instead of `rm -rf`.

### 2.6 Tab completion, if you want it

oillamp is never installed anywhere, so there is no package step that could put a completion
script where bash looks for one. Instead it prints the script and you decide what to do with it:

```sh
eval "$(oillamp completion bash)"                              # this shell only
echo 'eval "$(oillamp completion bash)"' >> ~/.bashrc          # every future shell
```

It completes command names, option names, and directory names for the commands that take a lamp.

---

## 3. Reading the startup output, line by line

Here is a real session starting. Every line is explained below it. This is the fastest way to see
the whole system at once.

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

The tag in brackets is the **phase**. oillamp runs four of them in order, and each one finishes
completely before the next begins. This is not decoration: it is the actual structure of the
program, and section 10.3 shows the code.

**`[host]`** — *What is this machine, and can it do the job?*

`Ubuntu 24.04.5 LTS, Wayland (ubuntu:GNOME)` — the operating system, and the kind of graphical
session you are logged into. oillamp needs to know this because opening a window is done
differently on Wayland than on X11.

`podman 4.9.3, rootless, crun` — three separate facts.
*podman 4.9.3* is the container program and its version.
*rootless* means podman is running as you, not as the system administrator — section 4.2.
*crun* is the low-level program that podman uses to actually create containers. oillamp requires
crun specifically, because it is the only one that supports passing your graphics card into the
container; section 8.4.

**`[lamp]`** — *What is in this directory, and what does its configuration ask for?*

"Lamp" is this project's word for one sandbox: a single directory holding the configuration, the
state, and the agent's files. Section 5 takes it apart.

`config valid — network: default allow, 1 rule, no forwards` — the `oillamp.toml` file in the lamp
directory parsed correctly, and here is a summary of the network policy it describes. Section 7.

`desktop 1920x1080, renderer gles2 (hardware)` — the desktop will be 1920×1080 pixels, and it will
be drawn by your actual graphics card. `gles2` is the name of the drawing back-end; the
alternative is `pixman`, which draws with the processor instead and is slower but always works.

`ready — agent v4elchzj` — `v4elchzj` is the **agent id**, a random identifier generated when the
lamp is created. It names the container and the agent's home directory, and it never changes for
the life of the lamp.

**`[image]`** — *Is the container image built?*

`sandbox image ready — localhost/oillamp/sandbox:0a6f7ffa4c89ca64` — an **image** is a frozen
filesystem that a container starts from; section 4.5. The long hexadecimal string is not a version
number. It is a fingerprint of everything that goes into building the image: the `Containerfile`,
every script, every configuration file, and the list of packages. Change any one of them and the
fingerprint changes, and oillamp builds a new image. Change nothing and it reuses the old one
instantly. This is why you cannot end up running a stale image by accident.

**`[session]`** — *Start everything, and watch it.*

`sandbox running — container oillamp-v4elchzj` — the container has been started.

`desktop and shell both answering — oillamp connected to each socket before handing it over` —
this is a deliberate design choice worth pausing on. oillamp does not check that the desktop's
socket *file exists* and then declare success. It opens a connection to it, and to the shell's
socket, and only then tells you the session is ready. A socket file that exists with nothing
listening behind it is exactly what a crashed previous session leaves behind, and testing for the
file rather than the service is how you get a session that reports "ready" and then fails.

`opened your shell, in a new terminal window` / `opened the desktop viewer` — the two windows.
They are new windows, deliberately: the terminal you typed in is never taken over, because it is
where the health reporting goes for the rest of the session.

---

## 4. The ideas you need

This section defines the mechanisms oillamp is built from. If you already know what a user
namespace is, skip to section 5.

### 4.1 A container is not a virtual machine

A **virtual machine** simulates a whole computer. It has its own kernel, its own memory
management, its own virtual disks. Starting one takes tens of seconds and costs hundreds of
megabytes of RAM before anything useful happens.

A **container** is different, and the difference matters for understanding oillamp's security
properties. There is only one kernel: yours. A process in a container is an ordinary process on
your machine — you can see it in `ps` on the host. What makes it a container is that the kernel
has been asked to *lie to it* about a few specific things:

- **which files exist** (it sees a different root directory),
- **which other processes exist** (it sees only its own),
- **which network interfaces exist**,
- **which user ids mean what** — this is the one that matters most here, and section 4.3 is
  entirely about it.

Each of those lies is a separate kernel feature called a **namespace**. A container is a process
started with a fresh set of namespaces.

The practical consequence: containers start in milliseconds and cost almost nothing, but the
isolation is only as good as the kernel's enforcement of those namespaces. A virtual machine has a
smaller attack surface. A container is far more pleasant to use. oillamp chooses the container, and
section 6.5 is honest about what that means.

### 4.2 Rootless

Historically, running containers required a background service running as the system
administrator, and asking it to start a container was very nearly the same as asking it to run a
command as root on your machine.

**Rootless** containers do away with that. podman runs as *you*, as an ordinary program, with no
background service and no elevated privileges. The container's processes are your processes. If
something escapes the container, it escapes into *your* account — not into root.

oillamp requires rootless podman and refuses to run any other way. This is the single most
important thing standing between an agent and your machine, and it is worth being concrete about
why: if oillamp used a root-owned container service, a container escape would be a full compromise
of the computer. Rootless reduces the worst case to "the agent can do what you can do", and
section 4.3 then reduces it much further than that.

### 4.3 User namespaces and the uid map — the central mechanism

This is the heart of oillamp. Read it slowly.

On Linux, every file is owned by a number, and every process runs as a number. `/etc/passwd` maps
those numbers to names for human convenience, but the kernel only ever deals in numbers. Your
account is probably number 1000 or 1001.

A **user namespace** lets a process have *a different set of numbers* from the rest of the system,
with a translation table between the two. The table is called the **uid map**. A process inside
the namespace sees itself as one number; the kernel, when deciding whether it may open a file,
uses the translated number.

Here is the actual map oillamp uses, obtained by asking the kernel directly:

| Inside the container | Name inside | On your machine, really | What that number can do |
|---|---|---|---|
| 0 | `root` | **165536** | Nothing. Owns no file on your system. |
| 1000 | `agent` | **1001** | Everything *you* can do. This is you. |
| 1001 | `lamp` | **166536** | Nothing. Owns no file on your system. |

Three things follow from this table, and together they are the whole security design.

**First: "root" inside the container is not root.** A process running as uid 0 in the container
believes it is the administrator. It can install packages, edit `/etc`, and create users — all
inside the container, because inside the container it genuinely is uid 0. The moment it touches
anything that belongs to the real system, the kernel translates its identity to 165536, and 165536
owns nothing and may do nothing. It is a number with no meaning on your machine at all.

**Second: the agent is you, and that is on purpose.** Container uid 1000 maps to your real account.
When the agent writes a file in its home directory, that file appears on your machine owned by
*you*, with your permissions. You can read it, edit it in your usual editor, and commit it to Git
without any ownership dance. This is a usability decision with a cost, and section 6.2 gives the
cost.

**Third: the infrastructure is a stranger to both of you.** Container uid 1001 — which runs the
desktop compositor, the screen recorder and the network bridges — maps to 166536, which is neither
you nor the agent. The agent cannot signal those processes, cannot read their private sockets, and
cannot modify or delete the recording of its own screen. Section 6 shows this being tested.

### 4.4 Subordinate uids: where 165536 comes from

The numbers 165536 and 166536 are not arbitrary, and they are not invented by podman. They come
from a file on your system:

```
$ grep $USER /etc/subuid
dnepp:165536:65536
```

This says: *the user `dnepp` is permitted to act as though they own the 65536 consecutive user ids
starting at 165536.* These are **subordinate user ids** — a block of numbers handed to your
account for exactly this purpose. They correspond to no real user, no real login, and no file
anywhere on the system unless a container of yours creates one.

Your distribution usually creates this line when your account is created. If it is missing,
rootless containers cannot map anything and oillamp will tell you the exact `usermod` command that
fixes it.

Now the earlier table can be read completely. podman was told
`--userns=keep-id:uid=1000,gid=1000`, which means *keep my identity, but call me 1000 inside*. So:

- container uid 1000 → your real uid, by the explicit instruction;
- every other container uid → taken in order from your subordinate block, so container 0 lands on
  165536 and container 1001 lands on 166536.

You can watch this happen yourself:

```sh
# Create three files inside a container, as three different container users
podman run --rm --userns=keep-id:uid=1000,gid=1000 --user 0:0 -v /tmp/out:/out \
    localhost/oillamp/sandbox:<tag> --entrypoint /bin/sh -c '
        touch /out/by-root
        setpriv --reuid=agent --regid=agent --init-groups -- touch /out/by-agent'

# Now look at who owns them on your machine
stat -c '%n  %u (%U)' /tmp/out/*
#   /tmp/out/by-agent   1001 (dnepp)      ← you
#   /tmp/out/by-root  165536 (UNKNOWN)    ← nobody at all
```

The file created by the container's "root" belongs to a user your system cannot even name.

### 4.5 Images, layers and why the tag is a fingerprint

An **image** is a frozen filesystem plus a note saying which program to start. A **container** is
one running instance of an image. The relationship is the same as between a program on disk and a
process: many containers can run from one image, and the image itself is never modified.

Images are built from a recipe file. oillamp's is
[`src/main/resources/image/Containerfile`](src/main/resources/image/Containerfile). Each
instruction in it produces a **layer** — a record of the filesystem changes that instruction made.
Layers are cached, so changing the last instruction rebuilds only the last layer.

This is why oillamp's `Containerfile` is ordered the way it is: the slowest and least frequently
changed things (the base system, the desktop packages) come first, and the things that change
during development come last.

Images are normally identified by a name you choose, like `myapp:latest`. oillamp does not do
that, because `latest` is a promise nobody keeps. Instead it computes a fingerprint over every
input that affects the build — the `Containerfile` text, every script that gets copied in, the
package list, the base image name — and uses that fingerprint as the tag:

```
localhost/oillamp/sandbox:0a6f7ffa4c89ca64
```

If that image exists, the build is skipped entirely, because the fingerprint proves it was built
from exactly these inputs. If it does not exist, a build is required, because something changed.
There is no third case and no way to be wrong. The code is
[`ImageResources.hashOf`](src/main/java/dev/oillamp/ImageResources.java).

### 4.6 Wayland, compositors, and "headless"

The agent needs a graphical desktop — to run a browser, to test a GUI application, to see what it
is doing. Providing one inside a container requires understanding one piece of Linux graphics.

On Linux there is no "the graphics system" built into the kernel in the way there is on Windows or
macOS. There is a program, running in user space, that owns the screen. Every application that
wants to draw connects to that program over a socket, sends it pictures, and receives keyboard and
mouse events back. That program is called a **compositor**, because its main job is compositing —
combining many application windows into the single image that goes to the monitor.

**Wayland** is the protocol they speak. It is not a program; it is a specification for the
messages. The compositor is the server, and applications are clients.

oillamp's compositor is **sway**. Normally sway drives a real monitor. oillamp starts it in
**headless** mode, which means it creates an output that exists only in memory. There is no
monitor, no graphics cable and no physical screen — but as far as every application inside is
concerned, there is a perfectly ordinary 1920×1080 display called `HEADLESS-1`.

That is the trick that makes the whole thing work. A browser inside the container does not know or
care that its pixels go into a buffer instead of to a screen.

### 4.7 VNC, and how you see the desktop

The desktop exists in the container's memory. To look at it, oillamp runs **wayvnc**, a program
that connects to sway as a Wayland client, asks for the screen contents, and serves them over the
**VNC** protocol — an old, simple, well-supported standard for sending a remote screen as images
and receiving keyboard and mouse events in return.

Your viewer window is an ordinary VNC client connecting to that server. With one unusual detail:
it does not connect over the network, because the container has no network (section 7). It
connects through a **Unix domain socket** — a file on disk that behaves like a network connection
but never leaves the machine. The file is
`<lamp>/.oillamp/sockets/infra/vnc.sock`, and it is owned by container user `lamp`, so the agent
cannot connect to it and cannot see what you see.

---

## 5. The anatomy of a lamp

A **lamp** is one directory. Everything about one sandbox lives in it. Here is a real one, with
every entry explained:

```
~/lamps/first/
├── oillamp.toml                 the configuration you edit
├── README.txt                   a note to whoever finds this directory
│
├── .oillamp/                    oillamp's own state — the agent never sees any of this
│   ├── lamp.json                the lamp's identity: its agent id and schema version
│   ├── session.json             what the currently running session is doing
│   ├── lock                     held while a session runs, so two cannot start at once
│   ├── keys/                    the SSH key pair for reaching the sandbox
│   ├── ssh_config               a ready-made SSH configuration pointing at this sandbox
│   ├── known_hosts              so your SSH client trusts this sandbox and no other
│   ├── session/                 files handed to the container, read-only, for this run
│   ├── image/                   the build context extracted from the program's own jar
│   ├── sockets/                 the Unix sockets that connect host and container
│   │   ├── host/                owned by you — the network proxy
│   │   ├── infra/               owned by container `lamp` — the desktop
│   │   └── agent/               the SSH entry point
│   ├── recordings/              screen recordings, owned by container `lamp`
│   └── logs/                    session log, container log, network journal
│
└── agent-lamp-v4elchzj/         THE AGENT'S ENTIRE WORLD
    ├── AGENTS.md                written each session: how this sandbox works
    ├── .bashrc                  so a scripted `ssh` command gets a working environment
    ├── workspace/               where repositories go
    ├── libs/                    native libraries, on the library search path
    └── screenshots/             where `lamp screenshot` writes
```

The two-level split is the point of the whole layout, and it is worth stating plainly:

**The agent sees `agent-lamp-v4elchzj/` and nothing else.** That one directory is attached to the
container as `/home/agent`. The configuration file that defines the network policy, the keys, the
logs and the recordings are all *siblings* of it, never inside it. An agent cannot rewrite the
rules that constrain it, because those rules are not in any directory it can reach.

The `agent-lamp-<id>` naming has a second purpose: if you copy that directory somewhere else, it
is obviously an agent's home and obviously belongs to a particular lamp, so it cannot be mistaken
for a lamp itself.

### 5.1 The configuration file

`oillamp.toml` is created with every setting present and commented. A fragment:

```toml
[display]
size = "1920x1080"
gpu  = "auto"        # auto | on | off

[recording]
enabled      = false  # true to record the desktop for the whole session
codec        = "libx264"
crf          = 30     # quality, lower is better and bigger
max_fps      = 10
max_age_days = 14     # delete recordings older than this
max_total_gb = 20     # delete oldest until the total fits

[network]
default = "allow"     # allow | deny
```

Recording is **off** unless you turn it on. A tool that watches a screen should not start watching
because nobody read the defaults.

---

## 6. The boundary: two users, and what separates them

Section 4.3 gave the uid map. This section is about what it buys.

### 6.1 Who runs what

Inside the container there are two accounts:

**`agent` (uid 1000 → you)** runs everything the AI agent does: the login shell you get over SSH,
the coding harnesses, a browser, any GUI application.

**`lamp` (uid 1001 → 166536)** runs all the infrastructure: the sway compositor, the wayvnc
server, the screen recorder, and the socket bridges that carry the network.

They share a container and a filesystem. They are different users, and the agent has no
capabilities — the container is started with every Linux capability dropped, so there is no
mechanism by which the agent can become `lamp` or gain privileges.

### 6.2 What that stops, tested

These are real results from a running sandbox, run as `agent`:

| The agent tried | What happened |
|---|---|
| `pkill -9 sway` — kill the compositor | `Operation not permitted` |
| `kill -9 <wf-recorder>` — kill the recorder | `Operation not permitted` |
| `ls /run/lamp/` — reach sway's control socket | `Permission denied` |
| overwrite a screen recording | refused |
| `rm` a screen recording | `Permission denied` |
| create a file among the recordings | `Permission denied` |
| **read** a recording of its own screen | **succeeded** |

The last row is deliberate, not a gap. The recordings directory is readable by everyone, because
*you* need to read it, and to the container's infrastructure user you and the agent are equally
"somebody else". Making it unreadable to the agent would make it unreadable to you as well.
Reading a recording gives the agent nothing: it cannot alter or delete it, so the record stays
trustworthy.

### 6.3 What the container is started with

The exact command, with the security-relevant flags explained:

```sh
podman run --detach --name oillamp-v4elchzj \
    --network=none \                              # no network interfaces at all
    --read-only \                                 # the image's filesystem cannot be written
    --user 0:0 \                                  # start as container-root, which is nobody
    --userns=keep-id:uid=1000,gid=1000 \          # the uid map of section 4.3
    --tmpfs /run:rw,mode=755 \                    # writable scratch, in memory, gone at exit
    --tmpfs /tmp:rw,mode=1777 \
    --memory 16g --cpus 15 --pids-limit 8192 \    # resource ceilings
    --volume <lamp>/.oillamp/session:/oillamp/session:ro \
    --volume <lamp>/.oillamp/sockets:/oillamp/sockets \
    --volume <lamp>/.oillamp/recordings:/oillamp/recordings \
    --volume <lamp>/agent-lamp-v4elchzj:/home/agent \
    localhost/oillamp/sandbox:0a6f7ffa4c89ca64
```

`--read-only` is worth dwelling on. The image's filesystem is immutable at run time. The only
writable places are the four attached directories and the two in-memory `tmpfs` mounts. This means
anything the agent installs system-wide is gone when the session ends, and anything it puts in its
home directory survives — which is exactly the distinction you want, and it is enforced by the
kernel rather than by convention.

A **volume** (the `--volume` lines) attaches a directory from your machine into the container at a
chosen path. Note that `/home/agent` is one of these. Whatever the image itself put at
`/home/agent` when it was built is therefore *invisible* at run time, hidden underneath the
attached directory. This is a real trap, and it is why tools like SDKMAN are installed into
`/usr/local/share/oillamp/` during the build and copied into the agent's home by the entrypoint on
first run.

### 6.4 Why deleting a lamp needs a command

Some files in a lamp belong to host uid 166536 — the sandbox's infrastructure user. You are uid
1001. You cannot delete another user's files, and you are not root, so:

```
$ rm -rf ~/lamps/first
rm: cannot remove '.../.oillamp/sockets/infra/vnc.sock': Permission denied
```

This is the tamper protection working correctly, seen from an angle where it is inconvenient. The
answer is `oillamp remove`, which does the deletion from inside podman's user namespace — where,
as section 4.3 explained, your account *is* allowed to act as those subordinate ids.

```sh
oillamp remove ~/lamps/first --yes
```

Without `--yes` it prints what would go and exits without touching anything. It refuses while a
sandbox is running. It deletes only what oillamp created, so your own notes beside `oillamp.toml`
survive — unless removing oillamp's files leaves the directory empty, in which case the directory
goes too rather than being left as litter.

### 6.5 What this does not protect against — read this

Being precise about the limits is part of owning the tool.

- **The agent runs as you.** Container uid 1000 is your real account. The isolation comes from the
  container's view of the filesystem, not from file permissions. A flaw in the kernel's container
  isolation, or a misconfiguration that attaches the wrong directory, gives the agent your
  privileges — not root's, but yours.
- **This is a container, not a virtual machine.** One kernel, shared. A kernel vulnerability
  reachable from inside is a real escape route. A virtual machine would be stronger and much less
  pleasant.
- **The agent can reach the internet by default.** This is deliberate — an agent that cannot
  install a package or read documentation is of little use — and the policy is configurable
  (section 7). But the default is "the open web", and data the agent can read, it can send.
- **`--memory`, `--cpus` and `--pids-limit` are ceilings, not guarantees.** They stop a runaway
  process from taking the machine down. They do not stop the agent from using everything up to
  those ceilings.
- **The threat model is your host, not the web.** oillamp is built to stop an agent reaching your
  files, your keys and your other projects. It is not built to stop a determined attacker who
  already controls the agent from talking to the internet.

---

## 7. The network

### 7.1 The problem

Two requirements that appear to contradict each other:

1. The agent must reach the internet. It needs to `npm install`, `pip install`, `git clone`, and
   read documentation.
2. The agent must not reach *your* machine — not your other containers, not a database on
   `localhost`, not your router's admin page, not a service on your company network.

A normal container gets a virtual network interface, and with it an address that can reach your
local network. Firewall rules could restrict that, but they are per-machine state, they need root
to install, and getting them wrong fails open.

### 7.2 The solution: no network at all, plus a proxy

oillamp starts the container with **`--network=none`**. This is not a firewall rule. The container
has no network interfaces except loopback. There is no address it could send a packet to. Nothing
to misconfigure and nothing that can fail open.

The internet then arrives by a different route entirely:

```
 an agent's process (curl, npm, pip)
   │   HTTPS_PROXY=http://127.0.0.1:3128
   ▼
 127.0.0.1:3128  ── inside the container, a socat listener run by `lamp`
   │
   │   a Unix domain socket — a file, not a network connection
   ▼
 <lamp>/.oillamp/sockets/host/proxy.sock
   │
   ▼
 the oillamp supervisor, running on your machine as you
   │   ① resolve the hostname to an address
   │   ② check that address against the policy in oillamp.toml
   │   ③ connect, or refuse with 403 and the name of the rule
   ▼
 the internet
```

Every program in the container is told, through environment variables, to send its HTTP traffic to
`127.0.0.1:3128`. A small relay program called `socat` listens there and forwards each connection
into a Unix socket. A Unix socket is a file: it carries bytes between two processes on one machine
and has no address, no port, and no route. The container's lack of a network is completely intact.

On the other side of that file is oillamp itself, running as you, which applies the policy.

### 7.3 Why the policy is checked against the resolved address

A rule that says "deny 192.168.0.0/16" must not be defeated by a hostname that *resolves* to
192.168.1.5. So oillamp resolves the name first and applies the policy to the address it actually
got. A hostname pointing into your private network is refused on the strength of where it points,
not what it is called.

A refusal is an HTTP 403 whose body names the rule that refused it, and it is printed on your
status terminal as it happens. A denial that looks like a network timeout wastes an agent's turn
and your afternoon.

### 7.4 Configuring it

```toml
[network]
default = "allow"                         # or "deny"

[[network.rules]]
name   = "no metadata services"
action = "deny"
hosts  = ["169.254.169.254"]
```

Private ranges and your machine's own loopback are refused by a built-in rule that is always
present. Every decision, allowed or denied, is written to
`.oillamp/logs/network-<session>.jsonl`, one JSON object per line.

### 7.5 Forwards, for things that are not the web

A **forward** exposes one specific TCP service inside the container on a fixed local port —
typically a language model API running on your own machine. It is the same socket mechanism, but
point-to-point: the container gets `127.0.0.1:<port>` connected to exactly one destination, with
no name resolution and no policy, because the destination was chosen by you in the configuration
file.

---

## 8. The desktop

### 8.1 The processes

Inside the container, four programs run as user `lamp`:

| Program | What it does |
|---|---|
| **sway** | The Wayland compositor. Creates the in-memory display `HEADLESS-1` and manages windows. |
| **swaybg** | Draws the wallpaper. |
| **wayvnc** | Connects to sway as a client, serves the screen over VNC to your viewer. |
| **wf-recorder** | Connects to sway as a client, writes the screen to an `.mkv` file. Only if recording is enabled. |

The agent's programs connect to sway as ordinary Wayland clients, through a socket the agent can
reach. What the agent cannot reach is sway's *control* socket, which would allow commanding the
compositor — moving windows, changing outputs, or running commands as `lamp`.

### 8.2 Driving the desktop from a shell

An agent works through a terminal, so it needs a way to interact with a desktop that has no mouse
in its hand. That is `lamp`, a small shell script inside the sandbox:

```sh
lamp screenshot                    # capture the screen, print the path
lamp screenshot --region 0,0,800,600
lamp click 960 540                 # click at absolute coordinates
lamp type "hello"                  # type text
lamp key ctrl+shift+t              # press a combination
lamp wait-stable 10                # block until the screen stops changing
lamp info                          # size, renderer, where screenshots go
```

It is deliberately a shell script and deliberately thin — each command is one line wrapping a
standard Wayland tool (`grim`, `wtype`, `wlrctl`). An agent can read it with
`cat $(command -v lamp)` and call those tools directly when the wrapper does not do what it needs.
A binary the agent cannot read would be a worse tool than a script it can.

`wait-stable` deserves a note, because it is the one that makes automation reliable: it takes two
screenshots half a second apart and returns when they are identical. That is a far better way to
know an application has finished launching than sleeping for a guessed number of seconds.

### 8.3 Recording

When `recording.enabled = true`, `wf-recorder` writes the entire session to
`.oillamp/recordings/<session>.mkv`, owned by `lamp` so the agent cannot alter it.

```sh
oillamp recordings ~/lamps/first                       # list them
oillamp recordings ~/lamps/first --open 20260923-085055  # play one
oillamp recordings ~/lamps/first --prune               # apply retention now
```

Retention has two independent limits — an age and a total size — and a recording is deleted if
*either* says so. It runs automatically at the start of every session, so a lamp used daily does
not quietly fill your disk.

### 8.4 The graphics card

By default (`gpu = "auto"`) oillamp uses your graphics card if it can and falls back to software
rendering if it cannot, saying which and why. It never refuses to start a session over graphics.

Using the card requires three things to line up: a render device at `/dev/dri/renderD128`; your
user being a member of the group that owns it (usually `render`); and podman using **crun**,
because passing a group into a container across a user namespace needs `--group-add keep-groups`,
which only crun implements.

If you are not in the group, oillamp prints the exact command and explains that a new group
membership only reaches processes started after a fresh login:

```
[lamp]    · you are not in the 'render' group that owns /dev/dri/renderD128
           run this on this machine, in your own terminal, then log out and back in:
               sudo usermod -aG render dnepp
```

oillamp does not run that command itself. It changes your account rather than this lamp, it needs
root, and it cannot take effect until you log in again — so doing it silently would alter your
system and still leave the session on software rendering.

---

## 9. What is inside the image

This is the part that is fairly described as a small Linux distribution in a bottle. The recipe is
[`src/main/resources/image/Containerfile`](src/main/resources/image/Containerfile), about 110 lines
with a comment on every decision. Roughly 3.5 GB built.

**The base** is Debian 13 "trixie". Debian rather than Ubuntu for one concrete reason: Ubuntu ships
Firefox as a snap package, and snaps do not work inside containers. Debian ships it as an ordinary
package.

**A shell a person would recognise** — `bash`, `coreutils`, `procps`, `less`, `grep`, `sed`, `awk`,
`tar`, `ping`, `dnsutils`, `vim`, `nano`, `htop`, `tmux`, `tree`, `lsof`, `man`. These are not
gated behind any option, and that is a deliberate choice: an agent that cannot diagnose its own
environment burns its turns guessing. A sandbox without `ping` or a process viewer feels broken
rather than minimal.

**The desktop** — `sway`, `xwayland` (so X11 applications still work), `wayvnc`, `wf-recorder`,
`grim` and `slurp` (screenshots), `wtype` and `wlrctl` (synthetic keyboard and mouse), `foot` (a
terminal emulator), Mesa graphics drivers, fonts.

**Development tooling** — `git`, `build-essential`, `cmake`, `gdb`, `strace`, `ripgrep`, `jq`,
Python 3, Node.js, Firefox ESR, and a Temurin JDK.

**SDKMAN**, at `~/.sdkman`, so an agent can install whatever JDK, Groovy or Gradle version a
project actually requires:

```sh
sdk install java 21.0.12+1.1-tem
sdk install gradle 9.7.1
```

It is configured to answer its own prompts, because an agent connected over SSH cannot respond to
"Do you want java 21 to be set as default? (Y/n)" and every prompt would be a hung command.

**AI coding harnesses** — `opencode` and `pi` are installed during the build. If installing them
fails, the build still succeeds: a sandbox with no harness still has a desktop, a shell and a
working SSH, and losing all of that because a package registry was briefly unreachable would be a
poor trade.

**Deliberately absent:** nothing in the image is setuid. The build strips the setuid bit from every
file it finds. oillamp's guarantees come from the user namespace, and a setuid binary inside the
container would be a route for the agent to become `lamp`.

---

## 10. The code

About 12,400 lines of Java 25 in a single package, `dev.oillamp`.

### 10.1 Five public types, and why

The entire package is package-private except for five types. A rule enforced by an automated test
keeps it that way.

| Type | What it is |
|---|---|
| `OilLamp` | The entry point. `OilLamp.on(machine).run(args)`. |
| `Machine` | The interface to the outside world — every effect the program can have. |
| `LampEvent` | Something that happened, reported as a value. |
| `Problem` | Something that went wrong, with evidence and fixes. |
| `ExitStatus` | The process exit code, named. |

The reason for the severity is that everything else is an implementation detail that should stay
free to change. If `LampPlanner` were public, its shape would become a contract with the outside
world, and it is precisely the sort of thing that should be rewritten when a better idea arrives.

### 10.2 Functional core, imperative shell

The program is divided in two, and the division is checked automatically.

**The core decides.** Pure functions, taking values and returning values. No files, no clock, no
randomness, no subprocesses. Given the same inputs they always produce the same output, which is
why they can be tested exhaustively without a container anywhere in sight.

**The shell performs.** A small number of classes that actually touch the world, all of them going
through `Machine`.

An architecture test, written with ArchUnit and named *"The code that decides things does not also
perform them"*, fails the build if a class outside an explicit allowlist calls `java.io`,
`java.nio.file`, `Instant.now()`, `Random`, or `ProcessBuilder`. The allowlist is short and every
entry is justified in a comment.

This is why the test suite runs in seconds with no podman: almost all the logic is pure.

### 10.3 The four phases

Running a session is four phases, in order. Each has the same three-step shape:

1. **Probe** — ask the world questions, and collect the answers into a record of plain facts.
2. **Plan** — a pure function turns those facts into a `Plan`: a list of `Step` values describing
   what should happen. Nothing has happened yet.
3. **Run** — `StepRunner` executes the steps.

| Phase | What it establishes |
|---|---|
| `HostPhase` | Does this machine have what it needs? Install missing packages, allocate subordinate uids. |
| `LampPhase` | Does this directory hold a valid lamp? Create the layout, generate keys, apply retention. |
| `SandboxPhase` | Build the image if the fingerprint demands it; start the container; wait until every socket answers. |
| `Supervisor` | Run the session: relay SSH, serve the proxy, report health, shut everything down. |

The payoff is `--dry-run`. Because a `Plan` is data, and because the decision to execute lives in
exactly one place inside `StepRunner.run`, `oillamp at <dir> --dry-run` prints the complete plan —
every package, every file with its permissions, every command — and changes nothing. There is no
separate code path that might drift away from the real one.

### 10.4 The `Machine` seam

`Machine` is the only way the program can affect anything. It has two implementations:

- **`RealMachine`** runs real commands and touches real files.
- **`SimulatedMachine`** is a configurable fake: a machine with a particular Linux distribution,
  a particular podman version, certain packages installed, certain commands that fail.

This is what lets the scenarios describe complicated situations precisely:

```groovy
sandbox.machine { it.debian('13').withoutPodman().processors(2) }
```

One qualification, because it matters: the **filesystem is real even in tests**. oillamp's
isolation is made of permission bits, ownership and symbolic links, and a simulated filesystem
that approximated those would hide bugs in exactly the place they matter most. Tests write to a
real temporary directory.

### 10.5 Where to start reading

In this order:

1. **`OilLamp.java`** — the entry point, 160 lines. The whole program in outline.
2. **`Machine.java`** — the complete list of effects the program can have. Read it as an inventory
   of everything oillamp is capable of doing to your computer.
3. **`Step.java`** — the vocabulary of actions. Every kind of change oillamp can make.
4. **`LampPlanner.java`** — a pure function turning facts into a plan. The best example of the
   style the whole codebase aims at.
5. **`SandboxPhase.java`** — where the container command is assembled, and where the security
   flags of section 6.3 come from.
6. **`entrypoint`** (in `src/main/resources/image/rootfs/usr/local/lib/oillamp/`) — the shell
   script that is process 1 inside the container. It starts every process discussed in section 8
   and supervises them.

---

## 11. Building it yourself

You need a JDK 25. Nothing else: Gradle downloads itself through the wrapper.

```sh
./gradlew build          # compile, run the 98 scenarios, check the architecture rules
./gradlew singleFile     # produce build/dist/oillamp, the whole program in one file
./gradlew installDist    # a conventional bin/lib layout, for development
./gradlew spikes         # the tests that need real podman (slow, online)
```

### 11.1 How the single file works

`build/dist/oillamp` is a shell script with a compressed archive appended to it. The script is
[`src/packaging/launcher.sh`](src/packaging/launcher.sh), it is about 90 lines, and it is meant to
be read. To print just that part of the built file, stopping where the archive begins:

```sh
sed -n '1,/^__OILLAMP_PAYLOAD_BELOW__$/p' build/dist/oillamp
```

When you run it, it unpacks itself once into
`~/.cache/oillamp/<version>-<fingerprint>/` and then replaces itself with the Java runtime it just
unpacked. Afterwards, starting oillamp costs about 140 milliseconds.

The directory name contains a fingerprint of that exact file, so two builds never share an unpacked
copy and upgrading is nothing more than replacing the file. `oillamp --where` prints the directory.
Deleting the file and that directory uninstalls oillamp completely; nothing else is ever written
outside your cache.

The bundled Java runtime is built with `jlink` and contains four modules —
`java.base`, `java.desktop`, `java.sql`, `jdk.charsets` — rather than a whole JDK. That is why the
file is 40 MB rather than 300.

---

## 12. How it is tested

### 12.1 Scenarios, not unit tests

The tests are written in Spock, a Groovy testing framework, and they are called **scenarios**
because each one describes a situation a user can be in rather than a method's return value. They
live in `src/test/groovy/oillamp/`, in a package *outside* `dev.oillamp` — so they can only use the
five public types, exactly as any other caller would.

Each scenario opens with a `reportInfo` block explaining what it is about and why that matters.
Those blocks are rendered to readable HTML in `build/spock-reports/` when you run the build, and
they are intended to be readable on their own, by somebody who has not read the code.

```groovy
def 'A lamp whose sandbox is still up is not removed out from under it'() {
    reportInfo """
        Removing a lamp deletes the agent's home directory. Doing that while a container is
        still running would leave the session working in directories that no longer exist, and
        the container would outlive everything that describes it - a sandbox with no lamp to
        stop it with.
    """
    ...
}
```

98 scenarios run in about 50 seconds. They need no podman, no network and no graphical session,
because they run against `SimulatedMachine`.

### 12.2 Spikes

A simulation only knows what its author already believed. A second, quite different suite —
**spikes** — checks assumptions about third-party tools that no simulation can confirm: that sway
honours a custom headless resolution, that a `.mkv` is playable after the recorder is interrupted,
that a particular podman flag does what the documentation says.

These pull images, start containers, and are slow and online. They are a separate task
(`./gradlew spikes`) so that `build` stays fast and green, and running them is a deliberate act.

### 12.3 Verification on real hardware

`docs/STATUS.md` records what has actually been run on a real machine, including the bugs that
found: a recording setting that was read and silently ignored, a shell that got no environment when
a command was scripted over SSH, a lamp that was deleted while its container was still running.
Each entry says what was wrong, how it was noticed and what changed.

---

## 13. Taking ownership

A short guide to making this codebase yours.

**Start by running it with `--dry-run`.** `oillamp at /tmp/x --init --dry-run` prints every single
thing a real run would do, and changes nothing. Read that output next to `LampPlanner.java` and the
program stops being a black box.

**Then read `Machine.java` end to end.** It is the complete list of effects oillamp can have. If
something is not in that interface, oillamp cannot do it. That is a strong statement about the
blast radius of the whole program, and it takes fifteen minutes to verify for yourself.

**The architecture test is your safety net.** Adding I/O to a core class fails the build with a
message naming the class and the rule. If you decide the rule is wrong, the allowlist is in
`TheShapeOfTheCodeSpec.groovy` and changing it is a deliberate, visible act.

**Adding a `Step` forces a decision.** `Step.detail()` is an exhaustive switch, so a new kind of
step does not compile until you have said how it is reported to the user. This is intentional:
silent actions are how a tool loses its user's trust.

**Where the bodies are buried**, in the honest sense — the places where reality is more awkward
than the design:

- **`entrypoint`** is a 290-line bash script running as process 1 inside the container. It is the
  least type-checked part of the system and the most consequential.
- **Unix socket paths are limited to 107 bytes** by the kernel. Lamp directories are wherever the
  user wants them, and easily longer. So the sockets live under `$XDG_RUNTIME_DIR` with a symbolic
  link into the lamp. Any change that moves a socket must respect this.
- **`/home/agent` is a volume**, so anything the image writes there at build time is hidden at run
  time. Two tools are already affected; a third will be too.
- **The agent runs as your uid.** Re-read section 6.5 before changing anything about the uid map.

**The specification is a living document.** `oillamp-design-spec.md` ends with a section called
*Implementation amendments*, which records every place reality disagreed with the plan and what was
decided as a result. When you change a design decision, add an entry. The value of that section is
that it makes the reasons for the current shape recoverable years later.

---

## Licence and status

Version 0.1.0. Milestones M1 through M7 are built and verified on real hardware; see
`docs/STATUS.md` for exactly what that means and what is still open.
