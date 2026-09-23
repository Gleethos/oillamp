# Working on oillamp

## Build and test

You need a JDK 25. Gradle downloads itself through the wrapper.

```sh
./gradlew build          # compile, run the scenarios, check the architecture rules
./gradlew spikes         # checks against real podman; slow, needs podman and a network
./gradlew installDist    # build/install/oillamp/bin/oillamp
./gradlew singleFile     # build/dist/oillamp
```

`./gradlew build` must stay fast and must pass on a machine without podman. Anything that needs a
real container belongs in a spike (tag it `spike`).

After changing anything under `src/main/resources/image/`, rebuild with `installDist` or
`singleFile` before trying it. The image tag is a hash of those files, so the next session builds a
new image automatically, but only if you run the new build of oillamp.

## Where things are explained

| Question | Document |
|---|---|
| What is oillamp, and what are containers, uid maps, Wayland? | [README.md](README.md) |
| How does the code work? | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) |
| Why is it built this way? | [docs/DECISIONS.md](docs/DECISIONS.md) |
| What works, what is verified, what is missing? | [docs/STATUS.md](docs/STATUS.md) |
| How do I use Sprouts collections? | [docs/SproutsCheatSheet.md](docs/SproutsCheatSheet.md) |

`docs/archive/oillamp-design-spec.md` is the original design, kept for history. It is out of date.
Do not cite it.

When you change a design decision, update its entry in DECISIONS.md. When you find or close a gap
between what the code does and what anything says it does, update STATUS.md.

## Writing comments, documentation and test descriptions

The people maintaining oillamp are programmers, not container or Linux-graphics specialists. Write
for them.

- **Say the thing.** Do not point at it. "Unix socket paths are limited to 107 bytes, so sockets are
  reached through the short runtime directory" is useful. "See D-25" or "per §9.2" is not.
  Requirement codes, section numbers, spike numbers (S1, S2, …) and milestone names (M3, M4, …) mean
  nothing to a reader who was not there.
- **Name the concept.** Write "the infra user (container uid 1001)", not "the other user" or "the
  one described above".
- **Explain the reason, once, where it applies.** A comment that says *why* is worth keeping; a
  comment that restates the code is not.
- **Keep it true.** A comment that describes how things used to be is worse than none. When you
  change behaviour, search for comments and documents that describe it.
- **Plain sentences.** Short sentences, ordinary words, few dashes. Avoid figures of speech and
  words like "load-bearing".
- **Define a term the first time** in a document if a reader might not know it (user namespace,
  subordinate id, bind mount, compositor).
- **Doc comments are Markdown** (`///`, supported since Java 23), not HTML Javadoc: `code` in
  backticks, `[OtherType#method]` for a link, a blank `///` line between paragraphs, `-` for a
  list. Groovy has no Markdown comments, so the Spock specs keep `/** */`.
- **Javadoc:** the first sentence says what the type or method is. Explain visibility only for the
  five public types; everything else is package-private by default and needs no comment about it.
- **Scenarios:** the name says what the user experiences. The `reportInfo` block explains, without
  referring to other documents, why the behaviour matters. It is rendered into the test report and
  should make sense on its own.

## Architecture rules the build enforces

`TheShapeOfTheCodeSpec` fails the build if:

- a sixth type in `dev.oillamp` becomes `public`;
- a public method uses a package-private type in its signature;
- a class outside the allowlist uses files, processes, threads, `System` or `SecureRandom`.

If you need to change one of these rules, change the test deliberately and say why in the commit.

## Commit messages

Say what changed and why, in plain words. If a change fixes something found on real hardware, add
it to "Lessons from real hardware" in STATUS.md.
