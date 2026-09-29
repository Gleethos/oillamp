# 🧞 Genies

Genies is a desktop chat app, in the spirit of Open WebUI or LM Studio, in which every
conversation partner is an AI agent with a sandboxed Linux desktop of its own. Each of these agents
is called a *genie*, and each genie lives in a lamp: an oillamp sandbox. The user can make as many
genies as they like, chat with each, give them files, take the files they make, and watch or use
their desktops.

Genies is also the test of oillamp's embedding API on a real application. It lives in the same
repository (`src/gui`, package `dev.gui`), but it uses oillamp only the way any application must:
through `dev.lamp`. `TheShapeOfTheCodeSpec` fails if it ever imports the engine, `dev.oillamp`.

```
./gradlew genies
```

Out of the box, a genie uses Eden AI's EU endpoint with the key in `EDENAI_API_KEY`. The settings
page takes another service and a key of the user's own instead, or a model server on this
computer: Ollama, LM Studio or llama.cpp's server, which needs no key. Look up asks such a server
which models it has.

## Where things are

| What | Where | Kept by |
|---|---|---|
| the list of genies: each one's id and name | `~/.local/share/genies/genies.json` | Genies |
| the model settings: which place the model runs, the hosted service with its key source, model and an entered key, and the model server's address and model | `~/.local/share/genies/settings.json`, readable by the user only | Genies |
| each genie's lamp: its home, its settings, its state | `~/.local/share/genies/lamps/<genie id>/` | oillamp |
| each genie's conversations, one file each, with every branch | in its home, `.pi/agent/sessions/--home-agent--/*.jsonl`, inside its lamp | pi, the harness |
| Genies' extension to pi, for moving within a conversation | `/usr/local/share/oillamp/genies/pi-genies.js` in the sandbox image | oillamp's image |
| the files a genie hands over | `~/outbox` in its home | the genie |
| the files the user gives a genie | `~/inbox` in its home | Genies puts them there |

`~/.local/share` is `$XDG_DATA_HOME` when that is set. The window itself keeps nothing: what it
shows is one value, `GeniesState`, rebuilt from the files above when Genies starts. All genies
start asleep.

## What happens, and in what order

**Waking a genie** (`GenieRunner.wake`):

1. `Lamp.at(<lamp>).modelService(<service>).modelKey(<key>).start()` starts oillamp's engine as a
   process of its own. The key goes into that process's environment, never onto its command line
   or into the lamp. The first time, the engine builds the sandbox image, which takes minutes; its
   progress becomes the genie's status line.
2. Once the lamp runs, `mkdir -p ~/outbox ~/inbox` runs in the sandbox, over the lamp's ssh command.
3. pi starts in the sandbox, over the same ssh command:
   `pi --extension <Genies' extension> --mode rpc --provider edenai --model <model> --continue --append-system-prompt <about Genies>`.
   `--continue` picks up the genie's last conversation; a genie woken into another one gets
   `--session <its file>` instead. The extra instructions tell the genie about `~/outbox`,
   `~/inbox` and its desktop being watched. A small shell adds `--extension` only when the file is
   there, because pi refuses to start with an extension it cannot find, and a sandbox image built
   before Genies had one must still give a working genie.
4. Genies asks pi for the conversation so far (`get_entries`) and shows the way from its first
   entry to the one pi continues from. It asks pi which commands it has (`get_commands`), to know
   whether the extension is there, and which conversation it has open (`get_state`).

**A message** goes to pi's standard input as one line of JSON, `{"type":"prompt","message":…}`. pi
answers with a stream of JSON lines: pieces of the answer, tools started and finished, the complete
answer with its token count, and finally `agent_settled`. `PiProtocol` reads them into `PiEvent`
values; `Transcript` folds each one into the conversation, and the chat follows. A message sent
while the genie still works is queued as a follow-up.

The model requests themselves leave the sandbox through oillamp's model relay, which adds the key
on the host. Inside the sandbox there is only a placeholder. For a model server on this computer,
the lamp is given its address with the path of its API, such as `http://127.0.0.1:11434/v1`, and a
stand-in key the server ignores; the relay puts the server's path in place of the sandbox's `/v3`,
and pi is offered every model the server lists, where for Eden AI it keeps only those served in
the EU.

**After each answer**, Genies lists `~/outbox`. A file that was not there before is announced in
the chat, with a Save button. It also asks pi for the conversation again, to learn pi's ids for the
questions just asked, and reads the genie's conversations again, since the tree has grown.

**Sleeping** closes pi's input, then the lamp. The engine stops the container and exits. The
genie's home stays, with the conversation in it. Closing the window puts every genie to sleep
first. If Genies is killed instead, each engine notices that its standard input closed and shuts
down by itself.

**Deleting** a genie puts it to sleep, then `Lamp.at(<lamp>).remove()` has the engine delete the
lamp. The engine has to do it: part of a lamp belongs to the sandbox's own users, and neither Genies
nor `rm -rf` can delete those files.

## Conversations and their branches

pi keeps a conversation as a tree. Every entry, whether a question, an answer or a tool's result,
names the entry before it. Asking a question differently adds a new question after the same entry
the old one followed, so the conversation forks there, and pi keeps both sides. Each conversation
is a file of pi's; a genie can have any number of them.

Under each genie's card is a line saying how many conversations it has. Clicking it opens the tree
of them, which starts closed. It is SwingTree's `UI.trees(..)`, bound to the conversations as
one value: a row per conversation, and below it a row per branch. A branch is a run of questions in
which nothing was asked differently, titled by its first question; below it are the alternatives
where it ends. A conversation that never forked is a single row. The row the genie is on is
selected.

| The user | What happens |
|---|---|
| clicks a row | the genie goes to that row's last entry, and the chat shows the way there. A sleeping genie wakes into it |
| presses Edit under a question of theirs | a dialog holds the question; the changed one is asked instead, and what followed the old one stays as a branch |
| presses New | pi starts a new conversation; the others stay |
| presses Delete… | after asking, the conversation the genie is in is deleted for good, with all its branches, and the genie starts a new one |

pi's RPC mode can list a conversation's entries but has no command to move among them; only a
command of an extension may. So the sandbox image carries a small extension of Genies', and Genies
sends its two commands as prompts: `/genies-goto <entry>` continues after an entry, and
`/genies-edit <question> <text>` moves to just before an old question and asks the new text there,
in one step. The extension answers with a notification, `genies: moved` or `genies: could not
move: <why>`, which is how Genies knows when to ask for the conversation again. Genies checks with
`get_commands` that the extension is there before sending either: without it, pi would pass the
command on to the model as an ordinary question. A genie without it says in the chat that it
needs waking again, which rebuilds its sandbox with the extension.

pi works on commands side by side rather than one after the other, so going somewhere is a chain
of questions, each sent when the last was answered: open the conversation (`switch_session`), move
(`/genies-goto`), then ask where pi is (`get_state`) and what was said on the way (`get_entries`).

**Where the tree comes from.** Genies reads the conversation files straight from the genie's home in
its lamp, on this computer, so the tree is there while the genie sleeps. This is the one thing
Genies reads from the lamp directly instead of through the sandbox. The genie writes those files,
so they are read as its work: no link is followed, not even a directory on the way, a file over
64 MB is left out, and a line that is not JSON is skipped. Deleting a conversation deletes its
file the same way. `Lamp.agentHome(<lamp>)` says where the home is.

pi only notes which entry it continues from while it runs; after a restart it continues from the
entry it wrote last. So Genies remembers where the user went, and wakes a genie that slept there.
It remembers that only while it runs: after Genies starts again, a genie wakes in the conversation
pi wrote to last, at its end.

## Files, both ways

Files travel through the lamp's ssh command, as the bytes of a command's input or output:

| Direction | Command in the sandbox | On the host |
|---|---|---|
| genie → user | `cat -- "$HOME/outbox/<name>"` | written where the user chose in a file dialog, first under a temporary name |
| user → genie | `cat > "$HOME/inbox/<name>"` | read from the file the user picked |

Nothing of the host is mounted into the sandbox for this, and a genie cannot write anywhere on the
host: a file it hands over lands only where the user saves it. File names that are paths, or that
hold control characters, are refused.

## The chat

Answers are Markdown as models write it: headings, emphasis, lists, quotes, links, inline code,
fenced code blocks and tables, whose columns are lined up (`Markdown`, pure), set in the lamp's
colours (`Typeset`) and painted by
SwingTree's style engine, which wraps them to the room they get while they stream in. The newest
characters of a streaming answer fade in. A genie that works with nothing streaming yet shows a
moving bar; a model that shares its thoughts gets a row of its own, which opens to watch them, as
a tool's row opens to show what the tool printed. The conversation follows its end while the user
is there, and leaves them be when they scroll up to read.

Genies brings its own fonts, Inter for text and JetBrains Mono for code, so it looks the same
whatever font the desktop uses. Where JetBrains Mono is also installed on the desktop, in weights
that make Java draw it bold, Genies uses Roboto Mono, which it also brings, instead.

The chat and the desktop share a responsive grid: side by side in a wide window, the desktop below
the chat in a narrow one. The settings form is a grid too, with its labels above the fields when
the card is narrow.

## The desktop

The desktop is served by wayvnc inside the sandbox, on a Unix socket that `Lamp.desktop()` names.
Only the user can open that socket, so it asks for no password. Genies draws it with a VNC client
of its own, `RfbConnection` (RFB 3.8, about 250 lines), because the protocol is simple for a local
socket: raw 32-bit pixels in exactly the layout of a Java image, copy-rect, and desktop resizes.
The pointer and keys go back as RFB events, keys as X11 key symbols (`Keysyms`).

The desktop is fitted into the room next to the chat, or shown at a scale from 50% to 300% in a
scroll pane (the buttons above it, or Control and the mouse wheel on it). Clicking it gives it the
keyboard, and a flame-coloured frame says so.

A genie shows the user something graphical by opening it on its desktop. Its instructions say so,
and the user watches with the Desktop button.

## How the code is arranged

| Package | What is in it | Touches the outside world |
|---|---|---|
| `dev.gui.model` | `GeniesState`, `Genie`, `Transcript`, `Entry`, `Settings`, `Handout`, and `Conversations`, `Conversation` and `Talk` for the tree: records with withers, every change a pure method | no |
| `dev.gui.pi` | `PiProtocol` (lines ⇄ `PiEvent`), `PiSession` (pi's process) | `PiSession` only |
| `dev.gui.desktop` | `RfbConnection`, `Keysyms` | `RfbConnection` only |
| `dev.gui.genie` | `GenieRunner` (one genie's life), `LampLighter` (lamps through `dev.lamp`), `Handouts` (files), `SessionFiles` (pi's conversations in the genie's home), `Shelf` (what is kept on disk) | yes |
| `dev.gui.view` | `GeniesView` (the window, bound to `Var<GeniesState>` through lenses), `DesktopScreen`, the look | Swing only |
| `dev.gui` | `Genies`: the entry point, and the `Actions` the window calls | ties it together |

The window never changes a genie by itself. It changes `GeniesState` through lenses (a draft, the
settings, the selected genie), and asks `Actions` for everything that starts, stops or moves
something. A `GenieRunner` never touches the window: it reports each change as a function
`Genie → Genie`, which `Genies` applies to the state on Swing's event thread. So every change goes
through one place, one at a time.

## Tests

| Spec | What it pins |
|---|---|
| `SpeakingPisProtocolSpec` | pi's JSON lines, both ways |
| `FollowingAConversationSpec` | how events become the chat, and asking a question differently |
| `BranchingAConversationSpec` | how pi's entries become the tree of conversations and branches |
| `ReadingAGeniesConversationsSpec` | reading and deleting pi's conversation files, without following links |
| `ReadingAGeniesMarkdownSpec` | Markdown as models write it, and the fade of a streaming answer |
| `ZoomingIntoAGeniesDesktopSpec` | the desktop's zoom steps |
| `KeepingManyGeniesSpec` | the list of genies, the settings for both places, a narrow window |
| `AskingAModelServerForItsModelsSpec` | Look up, against a stand-in server answering as Ollama does |
| `KeepingGeniesBetweenRunsSpec` | the shelf |
| `WatchingAGeniesDesktopSpec` | the VNC client, against a stand-in desktop playing wayvnc's part byte by byte |
| `KeepingAGenieAliveSpec` | a genie's life through real processes and pipes, with a stand-in lamp and a shell script as pi, going between conversations too |
| `RunningARealGenieSpec` (spike) | the same with a real lamp, real pi, real wayvnc and, with a key, the real model |
| `UsingGeniesForRealSpec` (spike) | the app's actions as the buttons call them, against real lamps, and with Ollama when it has the spike's model |

## What it does not do yet

- Only pi is used as the harness; opencode would need its own protocol (ACP).
- For a hosted service, the model list in the settings is a short list of EU models plus free
  text; only a model server on this computer is asked for its list.
- A genie's token count starts from zero each time Genies starts.
- Images the user gives a genie arrive as files in `~/inbox`, not as images in the prompt.
- A question is changed in a dialog, not in place in the chat.
- Conversations cannot be renamed from Genies; one named in pi is titled by its name.
