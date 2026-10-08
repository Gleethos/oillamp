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

The settings offer two ways to a model. The simple way, shown first, is Ollama on this computer,
which Genies installs, starts and gives a model: see "The first start, and the simple way to a
model". The advanced way, behind a link, has three places: Eden AI's EU endpoint, with the key in
`EDENAI_API_KEY` or one the user enters; a model server elsewhere, at an `https://` address with a
key if it asks for one, such as Ollama behind a proxy; or any model server on this computer,
Ollama, LM Studio or llama.cpp's server, which needs no key. There, the Model field offers the
models the service lists, from `Lamp.models`: asked when the settings open, when another place is
chosen, and on Look up. For Eden AI these are the models served in the EU. The Settings button
stays pressed in while the page is open; pressed again, like Done, it keeps the settings and goes
back to the genie's chat.

## The first start, and the simple way to a model

Without genies, Genies opens on the settings, without the list of genies, and welcomes the user:
the lamp lights, a genie takes form from its flame (see "The welcome's picture"), and a few lines
say what a genie is and that
it needs a model. Then the settings appear below. Once the genies can reach their model, "Your
first genie" makes one; from then on the list of genies is there, and the settings are plain. After
the last genie is deleted, the welcome comes back.

The first time, with no `settings.json` yet, the settings start where this computer has a model:
on Ollama, if `ollama` is on the `PATH` or in Genies' own folder; otherwise at Eden AI, if
`EDENAI_API_KEY` is set; otherwise on Ollama, which Genies then installs. The settings show the
advanced way first whenever the genies use something other than Ollama at
`http://127.0.0.1:11434/v1`.

The simple way says what the computer has, whether Ollama is installed and running, and suggests a
model, which the user can change to any Ollama model. One button does what is left, in this order
(`Genies.setUpOllama`, through `OllamaKeeper`):

1. **Install Ollama**, unless it is installed or something answers at `127.0.0.1:11434`: Ollama's
   official archive, `ollama-linux-<amd64|arm64>.tar.zst` from `ollama.com/download` (about
   1.4 GB), and the `-rocm` one too with an AMD card. It is unpacked as it downloads, in Java and
   with `tar`, into `ollama.part` beside the shelf's `ollama`, which it replaces once whole. No
   password, and nothing outside that folder.
2. **Start Ollama**, unless it runs: `setsid ollama serve` with `OLLAMA_HOST=127.0.0.1:11434`, its
   output appended to `ollama/serve.log`. In a session of its own, it outlives Genies, so a genie
   left running keeps its model. Waking a genie that uses Ollama starts it the same way.
3. **Download the model**, unless Ollama has it, through `/api/pull`. Stopped, Ollama keeps what
   it has, and the next download goes on from there.
4. **Prepare it**: `/api/show` must list `tools` among its capabilities, or a genie could not work
   with it. Then `/api/create` makes `genies/<model>` from it with a context of 32,768 tokens:
   Ollama's own few thousand would overflow with a genie's instructions and its tools' output.
5. **Try it**: one word through `/api/chat`, which loads it, for up to ten minutes.

The settings then name `genies/<model>` at `http://127.0.0.1:11434/v1`, and are kept. A step that
fails says why under the button; Stop ends any step.

The suggestion (`Hardware.suggested`) reads the memory from `/proc/meminfo`, NVIDIA cards from
`nvidia-smi` and AMD cards from `/sys/class/drm`; a card with less than 4 GB of its own counts as
none. A model's size comes from its name: `8b` is eight billion parameters, at 4 bits each unless
the name says `q8_0`, `fp16` or the like; `a3b` means three billion of them work on each word. A
model weighs about `billions × (0.1 + 0.13 × bits)` GB and needs 1.25 times that plus 3 GB to run.
It runs on the graphics card if that fits 90 % of the card's memory, on the processor if it fits
65 % of the computer's, and is too large otherwise. Genies suggests, from `qwen3:32b`,
`qwen3:30b-a3b`, `gpt-oss:20b`, `qwen3:14b`, `qwen3:8b`, `qwen3:4b` and `qwen3:1.7b` in that
order, the first that runs on the graphics card; otherwise the first that runs on the processor
with at most 8 billion parameters working on each word; otherwise `qwen3:1.7b`.

## Where things are

| What | Where | Kept by |
|---|---|---|
| the list of genies: each one's id and name | `~/.local/share/genies/genies.json` | Genies |
| the model settings: which place the model runs; Eden AI's key source, entered key and model; the server elsewhere's address, key and model; the model server on this computer's address and model | `~/.local/share/genies/settings.json`, readable by the user only | Genies |
| each genie's lamp: its home, its settings, its state | `~/.local/share/genies/lamps/<genie id>/` | oillamp |
| what went wrong that Genies did not expect, with stack traces; past 1 MB it becomes `errors.log.1` | `~/.local/share/genies/errors.log` | Genies |
| Ollama, when Genies installed it, and what Ollama printed when Genies started it | `~/.local/share/genies/ollama/`, with `bin/ollama` and `serve.log` | Genies |
| Ollama's models, and Genies' `genies/<model>` versions of them | `~/.ollama/models` | Ollama |
| each genie's conversations, one file each, with every branch | in its home, `.pi/agent/sessions/<folder>/*.jsonl`, inside its lamp | pi, run by the lamp's session |
| the genie's instructions, and the model pi uses | in its home, `.pi/agent/APPEND_SYSTEM.md`, and `defaultProvider` and `defaultModel` in `.pi/agent/settings.json` | Genies writes them at every wake |
| the files a genie hands over | `~/outbox` in its home | the genie |
| the files the user gives a genie | `~/inbox` in its home | Genies puts them there |
| each genie's jobs | `.oillamp/schedule.json` in its lamp | oillamp; Genies changes it through `Lamp.Starting` |
| each job's past runs: which job, how it ended, what the genie said last, its conversation | the lamp's history, as `run` snapshots | oillamp |
| the moments a genie's home was saved at, which it can go back to | the lamp's history, as snapshots | oillamp; Genies saves and goes back through `Lamp.Starting` |

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
starting closed: the scheduled runs, one conversation per run of a job, and below them the
conversations the user had, whose line ends in ＋ for a new one. Delete…, under both trees while
either is open, deletes the conversation the genie is in, whichever tree holds it. `Lamp.Conversation.job()` says which conversations a job's run had; oillamp names those
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
| presses ＋ | the chat empties, and the next message starts a new conversation; the others stay |
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
moving bar that says what the user waits for: that the genie thinks, or, while a job of its
schedule runs, that it does that job first, with the job's title and how long it has run. A message
sent meanwhile waits for the job, since the lamp runs one thing at a time; a job the genie missed
while asleep runs as soon as it wakes. The line under the genie's name then says "doing a scheduled
job". Both come from `Genie.waitingOn` and `Genie.status`, which read `Schedule.running`. A model that shares its thoughts gets a row of its own, which opens to watch them, as
a tool's row opens to show what the tool printed. The conversation follows its end while the user
is there, and leaves them be when they scroll up to read.

When the model cannot answer, the chat says why as a problem in red, in plain words, from the
error pi got (`Transcript.explained`): oillamp could not reach the model service, the connection
to it broke off, or the service refused the key, took no more requests, refused the request, or
had trouble of its own, each with the service's own message. A retry pi makes by itself adds
"Trying again, 1 of 3." A genie the user stopped ends with a grey notice instead. A conversation
read again from pi's session file shows its failures the same way, and keeps for each answer
whether the genie used tools after it; it leaves out the tools and thoughts themselves.

Genies brings its own fonts, Inter for text and JetBrains Mono for code, so it looks the same
whatever font the desktop uses. Where JetBrains Mono is also installed on the desktop, in weights
that make Java draw it bold, Genies uses Roboto Mono, which it also brings, instead.

The chat and the desktop share a responsive grid: side by side in a wide window, the desktop below
the chat in a narrow one. The settings form is a grid too. Its settings come in two halves, side by
side once the card is wider than 760 units: what this computer has and the model, or where the
model runs and how to reach it. Each half is a grid of its own, with its labels above the fields
when the half is narrow.

A window narrower than 820 units is one column, as on a phone: the list of genies on top, the
selected genie below. Crossing the line takes a margin of a tenth either way, so dragging the
window's edge across it does not flicker. Becoming narrow folds the list away. Open, the list is
a row with New and the settings, then the genies' cards in an area 180 units tall, which scrolls;
a grip under the area makes it taller or shorter, as under a tree of conversations, and dragged
to nothing, folds the list away. A window wide again has the list beside the genie, shown.

☰ shows and hides the list, in either arrangement. It is always in the window's top left corner:
in the list's top row while the list is shown, in the genie's header while it is not. So it stays
under the pointer, and pressing it again undoes the first press. When something went wrong that
Genies did not expect, anywhere in it, the list says so; while the list is hidden, a red ⚠ with
the count does, between ☰ and the genie's lamp, and pressed, shows what happened.

## The schedule

Each genie has two pages beside its chat, switched to in its header: its schedule, and its
history (see below). The header's buttons have a sign beside their words, and it makes room in
steps: below 780 units of width, the buttons and the switch show their signs alone; below 540, the
switch steps aside, and the pages are in the menu behind "⋯" instead. A right-click on the genie's
card opens that menu too. Menus open as their button is pressed, and a second press closes them.

The jobs on the schedule wake the genie at their times, but only while it is awake; a job whose
time came while it slept runs once, as soon as it wakes. The page says which of these holds.

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

## The history

oillamp saves a genie's home, its files with its conversations, and the lamp's `oillamp.toml`
as they are: when the lamp is lit, before a run if something changed since the last save, after
every run, when the lamp goes out, and when the user saves by hand. A save that finds nothing
changed makes nothing. Each save is a moment the genie can go back to. Its schedule is not part of
one: it is kept in the lamp, outside the home, and stays as it is.

**The page** lists the moments newest first, day by day, under "now", on a rail like the
schedule's week (`History`, pure): what each was, such as "Answered: Plot the sales figures",
"before the big refactor" or "Woke up", and, after a run, what the genie said last. A run is titled
by its conversation, or a job's run by its job. The newest 40 show; a button shows the rest.

**Reading it.** When the page comes on show, `HistoryKeeper` asks the lamp for its history
(`Lamp.Starting.history()`), awake or asleep, and `ScheduleKeeper` for the schedule, for the jobs'
titles. While the genie is awake, `Saved` and `RunFinished` read it again, and so does the lamp
going out.

**Saving by hand.** "Save now…", on the page and in the genie's menu, asks for a few words to
remember the moment by, which may be left out, and calls `Lamp.Starting.save`. The page picks the
new moment, and the chat says it was saved; when nothing changed, both say so instead.

**Going back.** Clicking a moment opens it, with "Go back to this moment…" and, after a run whose
conversation is still there, a link that opens it in the chat. After asking, `Genies.goBack` has
the genie's runner put its lamp out, since oillamp restores no lamp that is lit, and call
`Lamp.Starting.restore`. oillamp saves the home first, if it changed, so nothing is lost. The
conversations are read again; the chat stays in its conversation if it is still there, and goes
to the most recent one otherwise. A genie that was awake wakes again. Going back is not offered
while the genie wakes or works, or a job's run is going.

**Undoing.** While going back is the last thing that changed the genie, a banner above the moments
says where it went, with Undo: going back to the moment that was the newest before. Waking,
sleeping and a save before a run do not count; an answer or a save by hand ends the offer.
Undoing is itself a going back: it adds a moment "Undid going back to …", and the banner does not
come back for it, since both ways are now moments to go back to. A going back counts as an undo
when it goes to the moment the newest going back left.

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

## The genie's picture

Each genie is drawn as Pip, a pixel spirit twenty pixels square with a big head, two big eyes,
short arms and a tail (`GenieSvgUtil`, SVG text that SwingTree draws). Its body colour, one of
eight, and what it wears follow from its id, so nothing about its looks is kept: a hat three times
in four (a turban, a fez, a topknot or a flame), and a vest, cuffs and an earring, each once in
three.

Its pose follows from its phase:

| Phase | Pose |
|---|---|
| asleep or waking | asleep: eyes shut, a z above its head |
| ready | awake |
| working, while the newest entry of the chat is a tool that runs | working: it looks down and raises one arm, then the other, with a sparkle |
| working, otherwise | thinking: it looks up while dots rise |
| broken | dizzy: crossed eyes and stars |

Working and thinking are animations, moved by the loop that moves the thinking bars, which runs
while any genie works. Where the genie shows what it did earlier, they stand still in their last
frame.

| Where | What it shows |
|---|---|
| beside the answer it writes now, the last since the user's last message, 30 units square | its pose, moving |
| beside each older answer | working, when it used tools after that answer, which then said what it was doing; awake otherwise |
| beside each of its thoughts | thinking, moving while the thought is written |
| beside a problem in the chat, such as a model that could not answer | dizzy |
| beside the bar that says what the user waits for | its pose |
| the top right corner of its card, 40 units square, painted on the card | its pose, while it is ready or working; asleep, waking or broken it is in its lamp, and the card shows only the lamp |

Everywhere else, the header, the empty chat and the window's icon, the lamp stays.

One more pose, waving, is only the welcome's: the genie looks at the user and waves one hand.

### The welcome's picture

`WelcomeScene` paints the welcome from the moment alone: its picture, 360 units tall, with the
lamp in the middle, 25 of the genie's pixels wide, the genie's pixels a 50th of the picture's
height; and its light, painted on the whole page behind the welcome, so that only the window's
edges cut it off. In a narrow window the picture's empty top is cut: none of it while the welcome
is 760 units wide or wider, 100 units once it is 360 or narrower, evenly in between; the lamp and
the genie keep their size and place at the bottom. The page's clock moves it from when the welcome
is shown for as long as it is shown.

The welcome is as wide as the settings' card, and lays out its words and its picture on a grid of
its own, whose reference width is 760 units. From four fifths of that on, it is wide: at 8.0 seconds
the lamp and the genie move, over one second, from the middle to the right five twelfths, the
words fade in on the left from 8.8 seconds over 1.2, and the settings' card appears at 9.8.
Narrower, the words fade in below the picture from 7.4 seconds over 0.8, and the card appears at
8.2. Past 760 units, the words grow as much larger as the welcome is wider, up to 1.35 times their
size. When the window changes width later, the picture moves aside or back at the same pace, and
the words beside it wait until it has moved more than halfway.

| Seconds | What happens |
|---|---|
| 0 – 1.0 | the lamp fades in, dark and cold, with a wisp of smoke |
| 1.2 – 3.6 | the flame catches on the wick, slowly, and lights the lamp |
| 3.6 – 4.6 | the flame burns calmly, in the lamp's amber |
| 4.6 – 6.0 | the flame turns to pixels on the genie's own grid, which grow to the genie's size; until 6.2, the flame, its light and the lamp's shine turn from amber to the genie's colour |
| 6.1 – 6.4 | the flame draws itself in |
| 6.4 – 6.9 | it bursts, and sparks fly |
| 6.7 – 7.7 | the genie takes form from the flame, its pixels fading in from its tail up |
| 7.8 on | the genie lives: its tail is a flame on the wick, its body flickers in the flame's colours above it; it floats a pixel up and down every 0.8 seconds, sways a pixel left and right every 7, blinks, and waves, thinks, works and rests by turns |

The light comes from the flame, and then from the genie: a round glow, a touch wider than tall,
whose middle is the flame's middle, rising as the flame grows, and then the genie's, as it takes
form, floats and sways. Its radius is 2.4 times the flame's height, flicker and all; the genie
shines as a flame of 12 of its pixels, its flicker too.

Each play has a genie of its own, from a random id, as a new genie has. Once it has taken form, a
click on it or on the lamp makes it vanish: dizzy, in a flash, its pixels fly apart in a puff of
smoke, which clears in 1.1 seconds. The next play then begins with another genie, the lamp
already there; the words and the card stay.

The lamp is drawn as SVG, without its flame (`LampSvgUtil.cold`), darkened while no flame burns
and shining in the flame's colour, strongest at the wick. Per-pixel chances come from a hash of the
pixel and the moment: Java's `Random`, seeded with neighbouring numbers, starts with nearly the
same number, and whole rows would flicker alike.

Gradients are painted once, into pictures that are kept, and each moment draws the pictures,
stretched and faded as needed: the light, the lamp's shine, the lamp itself, dark and not, and the
poof's flash. On screen, Java hands a kept picture to the graphics system from its second
drawing on, after which drawing it costs next to nothing; a gradient painted anew each moment
over the whole welcome took about 48 ms, longer than a frame, and made the window slow to
resize. So the light and the shine never take a colour between amber and the genie's, which
would be a new picture each moment: the amber one fades out as the genie's fades in. Painted
over the welcome at 1400 by 720 pixels, a moment now takes under 1 ms, 3 at most. The genie's pixels are
painted without smoothing, which does nothing for squares on whole pixels but slow them down.

## How the code is arranged

| Package | What is in it | Touches the outside world |
|---|---|---|
| `dev.gui.model` | `GeniesState`, `Genie`, `Transcript`, `Entry`, `Settings`, `OllamaSetup` and `Hardware` for the simple way to a model, `Handout`, `Conversations`, `Conversation` and `Talk` for the tree, `Fold` for the trees and the list of genies above a narrow window's genie, `Schedule`, `JobDraft`, `Recurrence`, `Timeline` and `DateWordingUtil` for the schedule, and `History` for the history: records with withers, every change a pure method | no |
| `dev.gui.pi` | `PiEvent` (what the chat is told) | no |
| `dev.gui.desktop` | `RfbConnection`, `X11KeysymUtil`, `Desktop` (its socket and its own size) | `RfbConnection` only |
| `dev.gui.genie` | `GenieRunner` (one genie's life), `LampLighter` (lamps through `dev.lamp`), `GenieFileTransferUtil` (files), `LampApiConversionUtil` (Lamp events and conversations as `PiEvent` values and tree rows), `GeniePiSetupUtil` (pi's instructions and model in the genie's home), `ScheduleKeeper` (the schedule, through the lamp), `HistoryKeeper` (the history, read and saved to through the lamp), `OllamaKeeper` (Ollama found, installed, started, and a model readied), `Shelf` (what is kept on disk) | yes |
| `dev.gui.view` | `GeniesView` (the window, bound to `Var<GeniesState>` through lenses), `SchedulePage`, `HistoryPage`, `SettingsPage`, `DesktopScreen`, the look | Swing only |
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
| `DrawingAGenieSpec` | a genie's appearance from its id, its pose from its phase, that every appearance, pose and frame can be drawn, and the welcome's picture at moments of its play |
| `ZoomingIntoAGeniesDesktopSpec` | the panel's size as the default, and the zoom steps |
| `KeepingManyGeniesSpec` | the list of genies, the settings for the three places and their model lists, a narrow window |
| `KeepingGeniesBetweenRunsSpec` | the shelf |
| `SettingUpAModelSpec` | model sizes and where they run, the suggestion, the welcome's page, and what setting up Ollama has left to do |
| `FindingOllamaSpec` | the memory and NVIDIA cards, read as Linux and `nvidia-smi` print them |
| `WatchingAGeniesDesktopSpec` | the VNC client, against a stand-in desktop playing wayvnc's part byte by byte, asking for a size and being told no included |
| `KeepingAGenieAliveSpec` | a genie's life through the Lamp API, with oillamp's engine in the test's JVM on a simulated machine and a stand-in pi that writes conversations as pi does; `lamp show` opening its desktop |
| `PlanningAGeniesWeekSpec` | the ways a job repeats and their cron expressions, the editor's checks and sentence, and the timeline |
| `KeepingAGeniesScheduleSpec` | the schedule read and changed through the lamp, asleep and awake, and a job's run followed on the page, against the engine in the test's JVM |
| `KeepingAGeniesHistorySpec` | the history read and saved to, going back and undoing it, against the engine in the test's JVM; how a long history folds, and when undo is offered |
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
- Going back brings the whole home back; a single file cannot be taken from a moment. A moment
  cannot be renamed or deleted from Genies.
