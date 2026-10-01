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
page takes a key of the user's own instead, or another place for the model: a model server
elsewhere, at an `https://` address with a key if it asks for one, such as Ollama behind a proxy;
or a model server on this computer, Ollama, LM Studio or llama.cpp's server, which needs no key.
Wherever it is, the Model field offers the models the service lists, from `Lamp.models`: asked
when the settings open, when another place is chosen, and on Look up. For Eden AI these are the
models served in the EU.

## Where things are

| What | Where | Kept by |
|---|---|---|
| the list of genies: each one's id and name | `~/.local/share/genies/genies.json` | Genies |
| the model settings: which place the model runs; Eden AI's key source, entered key and model; the server elsewhere's address, key and model; the model server on this computer's address and model | `~/.local/share/genies/settings.json`, readable by the user only | Genies |
| each genie's lamp: its home, its settings, its state | `~/.local/share/genies/lamps/<genie id>/` | oillamp |
| each genie's conversations, one file each, with every branch | in its home, `.pi/agent/sessions/<folder>/*.jsonl`, inside its lamp | pi, run by the lamp's session |
| the genie's instructions, and the model pi uses | in its home, `.pi/agent/APPEND_SYSTEM.md`, and `defaultProvider` and `defaultModel` in `.pi/agent/settings.json` | Genies writes them at every wake |
| the files a genie hands over | `~/outbox` in its home | the genie |
| the files the user gives a genie | `~/inbox` in its home | Genies puts them there |
| each genie's jobs | `.oillamp/schedule.json` in its lamp | oillamp; Genies changes it through `Lamp.Starting` |
| each job's past runs: which job, how it ended, what the genie said last, its conversation | the lamp's history, as `run` snapshots | oillamp |

`~/.local/share` is `$XDG_DATA_HOME` when that is set. The window itself keeps nothing: what it
shows is one value, `GeniesState`, rebuilt from the files above when Genies starts. All genies
start asleep, unless their lamp was left running: see "Closing Genies".

## What happens, and in what order

**Waking a genie** (`GenieRunner.wake`):

1. `Lamp.at(<lamp>).enableScheduling().modelService(<service>).modelKey(<key>).start()` starts oillamp's engine as a
   process of its own. The key goes into that process's environment, never onto its command line
   or into the lamp. The first time, the engine builds the sandbox image, which takes minutes; its
   progress becomes the genie's status line. `enableScheduling` turns the lamp's schedule on for
   this session, whatever its `oillamp.toml` says, so its jobs wake the genie while it is awake.
2. Once the lamp runs, `mkdir -p ~/outbox ~/inbox` runs in the sandbox, over the lamp's ssh command.
3. Genies writes the genie's instructions (`~/outbox`, `~/inbox`, its desktop being watched) to
   `~/.pi/agent/APPEND_SYSTEM.md`, and the model from the settings into `~/.pi/agent/settings.json`,
   keeping that file's other keys. pi reads both when the lamp's session starts it, at the first
   message.
4. The chat shows the conversation Genies was in, or else the genie's most recent one, read with
   `Lamp.conversations`.

Genies does not start pi. The lamp's session holds the genie's one pi, as it does for any lamp,
and every message, scheduled job and `oillamp ask` goes through it, one run at a time.

**A message** goes to the session with `Lamp.send` and a `Lamp.Question` that says where it goes:
a new conversation, after the entry the chat shows, or instead of a question. `send` returns the
run's id at once. The run's events come on the lamp's event stream: `RunProgress` with each piece
of the answer and of the model's thinking, each tool started and finished, each complete message,
and `RunFinished`. `GenieRunner` turns those of its own runs into `PiEvent` values, and `Transcript`
folds each into the chat. A message sent while the agent works on another run waits its turn.

`send` starts a short-lived engine process, so a run starts about a second after the message is
sent. Its first events can arrive before `send` returns; `GenieRunner` recognises them by the
message's text.

The model requests themselves leave the sandbox through oillamp's model relay, which adds the key
on the host. Inside the sandbox there is only a placeholder. For a model server, elsewhere or on
this computer, the lamp is given its address with the path of its API, such as
`http://127.0.0.1:11434/v1`, and its key, or a stand-in when it asks for none; the relay puts the server's path in place of the sandbox's `/v3`,
and pi is offered every model the server lists, where for Eden AI it keeps only those served in
the EU.

**After each run of its own**, Genies lists `~/outbox`. A file that was not there before is announced
in the chat, with a Save button. It reads the conversation again, to learn the entries' ids and
where the conversation now stands, and the tree. A run it did not start, such as a scheduled job's,
changes only the tree.

**Stop** cancels the run that answers the chat, with `Lamp.cancel`.

**Sleeping** closes the lamp. The engine stops the container and exits. The
genie's home stays, with the conversation in it. A lamp Genies joined (see below) is ended with
`Lamp.stop`, which is `oillamp stop`.

**Closing Genies** with genies awake or waking asks: *Put to sleep*, *Keep running* or *Cancel*.
*Put to sleep* puts every genie to sleep before the window goes. *Keep running* calls
`Lamp.leaveRunning` on each lamp: the engine keeps the sandbox, its schedule and the run in
progress going until `oillamp stop`. If Genies is killed instead, without being asked, each engine
notices that its standard input closed and shuts down by itself.

**Opening Genies** looks for a lamp left running under every genie (`Lamp.Starting.isRunning`) and
joins it (`Lamp.Starting.join`, which runs `oillamp follow <lamp> --embedded`). The session's
catch-up comes first and ends with an `AgentStatus`; `GenieRunner` holds those events back until
then. If the agent is answering a question of the user's, the chat shows that conversation as it
stood before the question, then the question, and the genie is working: the held-back run events
replay what it has written so far, and the rest arrives as it is written. Otherwise the chat shows
the conversation Genies was in. The lamp keeps the model settings it was started with until the
genie is put to sleep.

**Deleting** a genie puts it to sleep, then `Lamp.at(<lamp>).remove()` has the engine delete the
lamp. The engine has to do it: part of a lamp belongs to the sandbox's own users, and neither Genies
nor `rm -rf` can delete those files.

## Conversations and their branches

pi keeps a conversation as a tree. Every entry, whether a question, an answer or a tool's result,
names the entry before it. Asking a question differently adds a new question after the same entry
the old one followed, so the conversation forks there, and pi keeps both sides. Each conversation
is a file of pi's; a genie can have any number of them.

Under each genie's card are two trees, each opened by a line saying how many it holds, and each
starting closed: the conversations the user had, and the scheduled runs, one conversation per run
of a job. `Lamp.Conversation.job()` says which conversations a job's run had; oillamp names those
after the run and the job, such as `run-12 (job-3)`. The line for scheduled runs shows only once
there is one. Each tree is SwingTree's `UI.trees(..)`, bound to its conversations as one value: a
row per conversation, and below it a row per branch. A branch is a run of questions in which
nothing was asked differently, titled by its first question; below it are the alternatives where
it ends. A conversation that never forked is a single row. The row the genie is on is selected, in
whichever tree it is.

An open tree sits in an area that scrolls when the tree is taller. A grip under the area, a line
with a handle, makes it taller or shorter when dragged, from nothing up to 900 units; a tree
shorter than that takes only its own height. Let go with the area dragged to nothing, and the tree
closes; it opens again as tall as it was before that drag. Each tree keeps its height while
Genies runs.

| The user | What happens |
|---|---|
| clicks a row | the chat shows the way to that row's last entry, and the next message goes after it. A sleeping genie stays asleep |
| clicks a row while the genie answers | the chat shows that row; nothing can be sent from there until the answer is done. The conversation being answered keeps growing out of sight, and going back to it shows the answer so far. An answer that ends meanwhile leaves the chat where the user is |
| presses Edit under a question of theirs | a dialog holds the question; the changed one is asked instead, and what followed the old one stays as a branch |
| presses Shift and Return while writing | a new line in the message; Return alone sends it |
| presses New | the chat empties, and the next message starts a new conversation; the others stay |
| presses Delete… | after asking, the conversation the genie is in is deleted for good, with all its branches, and the genie starts a new one |

Moving within a conversation happens in the session, when the next message is asked: its
`Lamp.Question` names the conversation, and the entry to continue after or the question to ask
instead of. The session opens the conversation in pi and moves there first. Going to a row only
changes what the chat shows and where the next message goes.

While the genie answers and the chat shows another conversation, `Conversations.aside` holds the
one being answered: where in it the genie is, and its transcript, to which the run's events go.
Going back to it takes the transcript back. pi writes a new conversation to disk only once there
is an answer in it, so until then the aside has no file, and the user's tree shows it as a "New
conversation" row at the top.

**Where the tree comes from.** `Lamp.conversations(<lamp>)` reads pi's files from the genie's
home in its lamp, on this computer, so the tree is there while the genie sleeps. It follows no
link, leaves out files over 64 MB, and skips lines that are not entries. `Lamp.forget` deletes one.

Genies remembers where the user went only while it runs: after Genies starts again, a genie wakes
in its most recent conversation, at its end.

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
fenced code blocks and tables, whose columns are lined up (`MarkdownParsingUtil`, pure), set in the lamp's
colours (`MarkdownStylingUtil`) and painted by
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

## The schedule

Each genie has a second page beside its chat, switched to in its header: its schedule. The jobs
on it wake the genie at their times, but only while it is awake; a job whose time came while it
slept runs once, as soon as it wakes. The page says which of these holds.

**Reading it.** When the page comes on show, `ScheduleKeeper` asks the lamp for its schedule
(`Lamp.Starting.schedule()`) and its history (`history()`), awake or asleep: the schedule lives in
the lamp, not in the sandbox. Each call starts a short-lived engine process, so a reading takes a
second or two. While the genie is awake, the lamp's events keep the page current: `RunStarted` of a
job shows it running, and `RunFinished`, `JobAdded`, `JobRemoved` or `ScheduleChanged` read it again.

**The week** is a timeline (`Timeline`, pure), day by day: the last runs of the week, with how each
ended, what the genie said last, and a link that opens its conversation in the chat; now; and each
time a job runs over the coming seven days, as oillamp lists them in `LampEvent.Job.upcoming`. A
job that runs four times or more on one day is one line for that day. Clicking a time or a job's
tile picks the job: its times stand out and the others step back.

**The jobs** are tiles beside the timeline, or under it in a narrow window: when each runs in words
(`Recurrence.describe`), its task, when it runs next, and a switch that turns it off without taking
it off the schedule. Its menu changes or removes it.

**The editor** takes the whole page, with the task beside when it runs, or above it when narrow. A
job runs once, on a day picked in a calendar or with a quick pick such as "Tomorrow morning", or
again and again: every hour, every day, weekdays, some days of the week, or a cron expression
written by hand. A repeating job can end on a day. `JobDraft` says in a sentence what still stands
in the way and, once the time can be read, when the job will first run. Saving adds the job with
`Lamp.Starting.once` or `repeat`; changing one adds the new job, then removes the old one, so a job
oillamp refuses leaves the old one, and the editor says why in oillamp's words.

## The desktop

The desktop is served by wayvnc inside the sandbox, on a Unix socket that `Lamp.desktop()` names.
Only the user can open that socket, so it asks for no password. Genies draws it with a VNC client
of its own, `RfbConnection` (RFB 3.8, about 300 lines), because the protocol is simple for a local
socket: raw 32-bit pixels in exactly the layout of a Java image, copy-rect, and desktop resizes.
The pointer and keys go back as RFB events, keys as X11 key symbols (`X11KeysymUtil`).

The bar above the desktop says how it is shown:

| Choice | What happens |
|---|---|
| **Panel**, the default | The desktop takes the size of the panel, in the screen's own pixels, and is drawn pixel for pixel. `DesktopScreen` asks for that size with RFB's "set desktop size" once the panel kept its size for 250 ms, and the bar shows the size, such as `644 × 728`. A fullscreen window on the desktop follows by itself. Never smaller than 400 × 300: a smaller panel shows the desktop shrunk |
| **Fit** | The desktop at its own size, shrunk into the panel |
| −, + | The desktop at its own size, at a scale from 50% to 300%, in a scroll pane; also Control and the mouse wheel on it. Not offered at the panel's size, where there is nothing to zoom |

The desktop gets its own size back, `display.width` × `display.height` from `SessionOpened`, when
it is no longer shown at the panel's size: on Fit or a scale, when the panel closes, when another
genie is shown, and when Genies closes with the genies kept running. The connection stays until the
desktop has that size, or for 3 s, because wayvnc finishes a change of size only while a viewer is
connected. A recorded desktop keeps its size (wayvnc answers "not allowed"); it is then drawn
fitted, and the bar says "Recorded, so it keeps its own size".

Clicking the desktop gives it the keyboard, and a flame-coloured frame says so.

**A genie shows the user something** by opening it on its desktop, fullscreen where it can, and
running `lamp show "what it is"`. Its instructions say so. The lamp reports
`LampEvent.LookAtDesktop`; `GenieRunner` turns it into `Genie.shows(what)`, which opens the genie's
desktop next to the chat, with a line above it: "Rex shows you: the chart you asked for". A genie
that is not on screen says it on its card in the sidebar, until the user is at its chat. Closing
the desktop forgets it.

## How the code is arranged

| Package | What is in it | Touches the outside world |
|---|---|---|
| `dev.gui.model` | `GeniesState`, `Genie`, `Transcript`, `Entry`, `Settings`, `Handout`, `Conversations`, `Conversation` and `Talk` for the tree, and `Schedule`, `JobDraft`, `Recurrence`, `Timeline` and `DateWordingUtil` for the schedule: records with withers, every change a pure method | no |
| `dev.gui.pi` | `PiEvent` (what the chat is told) | no |
| `dev.gui.desktop` | `RfbConnection`, `X11KeysymUtil`, `Desktop` (its socket and its own size) | `RfbConnection` only |
| `dev.gui.genie` | `GenieRunner` (one genie's life), `LampLighter` (lamps through `dev.lamp`), `GenieFileTransferUtil` (files), `LampApiConversionUtil` (Lamp events and conversations as `PiEvent` values and tree rows), `GeniePiSetupUtil` (pi's instructions and model in the genie's home), `ScheduleKeeper` (the schedule, through the lamp), `Shelf` (what is kept on disk) | yes |
| `dev.gui.view` | `GeniesView` (the window, bound to `Var<GeniesState>` through lenses), `SchedulePage`, `SettingsPage`, `DesktopScreen`, the look | Swing only |
| `dev.gui` | `Genies`: the entry point, and the `Actions` the window calls | ties it together |

The window never changes a genie by itself. It changes `GeniesState` through lenses (a draft, the
settings, the selected genie), and asks `Actions` for everything that starts, stops or moves
something. A `GenieRunner` never touches the window: it reports each change as a function
`Genie → Genie`, which `Genies` applies to the state on Swing's event thread. So every change goes
through one place, one at a time.

## Tests

| Spec | What it pins |
|---|---|
| `FollowingAConversationSpec` | how events become the chat, and asking a question differently |
| `BranchingAConversationSpec` | how pi's entries become the tree of conversations and branches |
| `ReadingAGeniesMarkdownSpec` | Markdown as models write it, and the fade of a streaming answer |
| `ZoomingIntoAGeniesDesktopSpec` | the panel's size as the default, and the zoom steps |
| `KeepingManyGeniesSpec` | the list of genies, the settings for the three places and their model lists, a narrow window |
| `KeepingGeniesBetweenRunsSpec` | the shelf |
| `WatchingAGeniesDesktopSpec` | the VNC client, against a stand-in desktop playing wayvnc's part byte by byte, asking for a size and being told no included |
| `KeepingAGenieAliveSpec` | a genie's life through the Lamp API, with oillamp's engine in the test's JVM on a simulated machine and a stand-in pi that writes conversations as pi does; `lamp show` opening its desktop |
| `PlanningAGeniesWeekSpec` | the ways a job repeats and their cron expressions, the editor's checks and sentence, and the timeline |
| `KeepingAGeniesScheduleSpec` | the schedule read and changed through the lamp, asleep and awake, and a job's run followed on the page, against the engine in the test's JVM |
| `RunningARealGenieSpec` (spike) | the same with a real lamp, real pi, real wayvnc and, with a key, the real model |
| `UsingGeniesForRealSpec` (spike) | the app's actions as the buttons call them, against real lamps, and with Ollama when it has the spike's model |

## What it does not do yet

- Only pi is used as the harness; opencode would need its own protocol (ACP).
- A model server elsewhere must be reached over `https://`; plain `http://` on the local network
  is refused, because the key would cross it readable.
- A genie's token count starts from zero each time Genies starts.
- Images the user gives a genie arrive as files in `~/inbox`, not as images in the prompt.
- A question is changed in a dialog, not in place in the chat.
- Conversations cannot be renamed from Genies; one named in pi is titled by its name.
- A job's run cannot be followed live in the chat; its conversation opens there once it ended.
- The timeline looks one week ahead and one week back.
