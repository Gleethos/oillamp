package dev.oillamp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

import dev.lamp.LampEvent.SaveKind;
import dev.lamp.Problem;
import dev.lamp.LampEvent.Snapshot;

import sprouts.Association;
import sprouts.Tuple;

/// A lamp's history: snapshots of its agent directory and its `oillamp.toml`, kept as a git
/// repository in `.oillamp/history`.
///
/// Each snapshot is one commit. Its tree mirrors the lamp: the agent directory under its own name,
/// `oillamp.toml`, and `.oillamp-modes`, a list of the exact permissions git cannot hold (git
/// knows only "executable or not", and ssh refuses a private key that others may read). The
/// commits form one line, each the parent of the next, on the branch `main`. A restore never
/// removes a commit: it adds one whose tree is the older snapshot's.
///
/// Everything here is written in git's own format by this class, not by running `git`; why is
/// explained in [GitObjectUtil]. Objects are only ever added, each written to a temporary file and
/// then renamed, and the branch is moved last. A save that is interrupted, even by a power cut,
/// leaves the history as it was, plus some objects nothing refers to.
///
/// The history is outside the agent directory and never mounted into the container, so the agent
/// cannot rewrite its own past.
final class History {

    /// The name of the list of exact permissions, at the top of every snapshot's tree.
    static final String MODES_FILE = ".oillamp-modes";
    static final String CONFIG_FILE = "oillamp.toml";
    private static final String BRANCH = "refs/heads/main";
    /// How long a save waits for another save or restore of the same lamp to finish.
    private static final Duration LOCK_WAIT = Duration.ofMinutes(10);
    /// Files up to this size are read into memory once. Larger ones are read twice, once to name
    /// them and once to store them, and never held in memory whole.
    private static final long SMALL_FILE = 4L * 1024 * 1024;

    private final LampLayout layout;
    private final Path repository;

    History(LampLayout layout) {
        this.layout = layout;
        this.repository = layout.historyDir();
    }

    /// What a save did.
    ///
    /// @param made    the new snapshot, or empty when nothing had changed since the last one
    /// @param latest  the newest snapshot now: the one made, or the unchanged one before it.
    ///                Empty only for a lamp that has never been saved and holds nothing
    /// @param files   how many files the snapshot holds
    /// @param skipped paths left out, because they could not be read or kept changing
    record Saving(Optional<Snapshot> made, Optional<Snapshot> latest, int files, Tuple<String> skipped) {}

    /// What a restore did.
    ///
    /// @param safety the save made just before, so the restore can be undone; empty when the lamp
    ///               had not changed since its last snapshot, which is then the way back
    /// @param result the snapshot that records the restore; empty when the lamp already was as
    ///               `target` holds it
    record Restoring(Snapshot target, Optional<Snapshot> safety, Optional<Snapshot> result,
                     Tuple<String> skipped) {}

    // ─── the three operations ──────────────────────────────────────────────────────────────

    /// Saves the lamp as it is now, unless nothing changed since the last snapshot.
    Result<Saving> save(SaveKind kind, String message, Optional<SessionId> session, Instant now) {
        return save(kind, message, session, Association.between(String.class, String.class), false, now);
    }

    /// Saves the lamp as it is now.
    ///
    /// @param trailers more trailers for the commit's message, such as the run it belongs to
    /// @param always   whether to make a snapshot even when nothing changed. A run's last
    ///                 snapshot is made either way, because it records what the agent said
    Result<Saving> save(SaveKind kind, String message, Optional<SessionId> session,
                        Association<String, String> trailers, boolean always, Instant now) {
        return locked(() -> {
            try {
                return Result.ok(saveUnlocked(kind, message, session, trailers, always, now));
            } catch (IOException | RuntimeException e) {
                return Result.err(ProblemCatalogUtil.saveFailed(layout.root(), ProblemCatalogUtil.reason(e)));
            }
        });
    }

    /// Every commit, newest first, with the trailers that say what made each one.
    Result<Tuple<GitObjectUtil.Commit>> commitList() {
        try {
            return Result.ok(commits());
        } catch (IOException | RuntimeException e) {
            return Result.err(ProblemCatalogUtil.historyDamaged(repository, ProblemCatalogUtil.reason(e)));
        }
    }

    /// One path that differs between two snapshots.
    ///
    /// @param kind `added`, `changed` or `removed`
    /// @param path as the agent sees it, such as `~/workspace/app.py`; a directory that is new or
    ///             gone as a whole ends in `/` and is listed once, not file by file
    record Change(String kind, String path) {}

    /// What differs between two snapshots' trees, as far as `limit` paths.
    ///
    /// @param more whether there were more than `limit`
    record Changes(Tuple<Change> shown, boolean more) {}

    /// Lists what differs from the tree `before` (or from nothing) to the tree `after`.
    Result<Changes> changes(Optional<String> before, String after, int limit) {
        try {
            List<Change> found = new ArrayList<>();
            boolean more = compare(before, Optional.of(after), "", found, limit, true);
            return Result.ok(new Changes(Tuple.of(Change.class, found), more));
        } catch (IOException | RuntimeException e) {
            return Result.err(ProblemCatalogUtil.historyDamaged(repository, ProblemCatalogUtil.reason(e)));
        }
    }

    /// Adds what differs between two trees to `found`. True once there were more than `limit`.
    private boolean compare(Optional<String> before, Optional<String> after, String prefix,
                            List<Change> found, int limit, boolean top) throws IOException {
        Association<String, GitObjectUtil.Entry> old = entriesOf(before);
        Association<String, GitObjectUtil.Entry> now = entriesOf(after);
        SortedSet<String> names = new TreeSet<>();
        for (GitObjectUtil.Entry entry : old.values()) names.add(entry.name());
        for (GitObjectUtil.Entry entry : now.values()) names.add(entry.name());
        for (String name : names) {
            if (top && name.equals(MODES_FILE)) continue;
            // The agent's home, whatever its name in the lamp, is `~` to the agent.
            String path = top && name.startsWith(LampLayout.AGENT_DIR_PREFIX) ? "~"
                        : top ? name : prefix + "/" + name;
            Optional<GitObjectUtil.Entry> was = old.get(name);
            Optional<GitObjectUtil.Entry> is = now.get(name);
            if (was.equals(is)) continue;
            boolean wasDirectory = was.filter(GitObjectUtil.Entry::isDirectory).isPresent();
            boolean isDirectory = is.filter(GitObjectUtil.Entry::isDirectory).isPresent();
            if (wasDirectory && isDirectory) {
                if (compare(was.map(GitObjectUtil.Entry::id), is.map(GitObjectUtil.Entry::id), path, found, limit, false))
                    return true;
                continue;
            }
            if (found.size() >= limit) return true;
            if (was.isEmpty()) found.add(new Change("added", path + (isDirectory ? "/" : "")));
            else if (is.isEmpty()) found.add(new Change("removed", path + (wasDirectory ? "/" : "")));
            else found.add(new Change("changed", path + (isDirectory ? "/" : "")));
        }
        return false;
    }

    /// Every snapshot, newest first. Empty for a lamp that has never been saved.
    Result<Tuple<Snapshot>> snapshots() {
        try {
            Tuple<Snapshot> found = Tuple.of(Snapshot.class);
            for (GitObjectUtil.Commit commit : commits()) found = found.add(commit.snapshot());
            return Result.ok(found);
        } catch (IOException | RuntimeException e) {
            return Result.err(ProblemCatalogUtil.historyDamaged(repository, ProblemCatalogUtil.reason(e)));
        }
    }

    /// Brings the lamp back to the snapshot whose id starts with `prefix`.
    ///
    /// Saves the lamp first, so the restore itself can be undone. Only call it while no session
    /// runs: the caller holds the lamp's lock.
    Result<Restoring> restore(String prefix, Instant now) {
        return locked(() -> {
            Tuple<GitObjectUtil.Commit> commits;
            try {
                commits = commits();
            } catch (IOException | RuntimeException e) {
                return Result.err(ProblemCatalogUtil.historyDamaged(repository, ProblemCatalogUtil.reason(e)));
            }
            Tuple<Snapshot> snapshots = Tuple.of(Snapshot.class);
            for (GitObjectUtil.Commit commit : commits) snapshots = snapshots.add(commit.snapshot());
            Result<Snapshot> found = GitObjectUtil.find(snapshots, prefix, layout.root());
            if (!(found instanceof Result.Ok<Snapshot> ok)) return Result.err(found.problems());
            Snapshot target = ok.value();
            GitObjectUtil.Commit wanted = commits.stream().filter(c -> c.id().equals(target.id())).findFirst().orElseThrow();

            Saving safety;
            try {
                safety = saveUnlocked(SaveKind.BEFORE_RESTORE, "before restoring " + target.shortId(),
                                      Optional.empty(), now);
            } catch (IOException | RuntimeException e) {
                return Result.err(ProblemCatalogUtil.saveFailed(layout.root(),
                        "the save before the restore failed, so nothing was restored: " + ProblemCatalogUtil.reason(e)));
            }
            // The newest snapshot now holds exactly what is on disk, apart from any skipped files.
            GitObjectUtil.Commit current;
            try {
                current = commits().first();
            } catch (IOException | RuntimeException e) {
                return Result.err(ProblemCatalogUtil.historyDamaged(repository, ProblemCatalogUtil.reason(e)));
            }
            Tuple<Problem> warnings = safety.skipped().isEmpty() ? Tuple.of(Problem.class)
                    : Tuple.of(Problem.class, ProblemCatalogUtil.filesNotSaved(layout.root(), safety.skipped()));
            if (current.tree().equals(wanted.tree()))
                return Result.ok(new Restoring(target, safety.made(), Optional.empty(), safety.skipped()), warnings);
            try {
                bringBack(wanted.tree(), current.tree());
                String message = GitObjectUtil.message(SaveKind.RESTORE, "back to " + target.shortId()
                        + " (" + target.kind().label() + ", " + target.at() + ")", Optional.empty());
                Snapshot result = commit(wanted.tree(), Optional.of(current.id()), now, message);
                return Result.ok(new Restoring(target, safety.made(), Optional.of(result), safety.skipped()), warnings);
            } catch (IOException | RuntimeException e) {
                return Result.err(ProblemCatalogUtil.restoreFailed(layout.root(), ProblemCatalogUtil.reason(e),
                        current.snapshot().shortId()));
            }
        });
    }

    // ─── saving ────────────────────────────────────────────────────────────────────────────

    /// Counts what a save found, as it walks the agent directory.
    private static final class Tally {
        int files;
        Tuple<String> skipped = Tuple.of(String.class);
        /// One line per path whose permissions are not what git restores by default, as
        /// `0600 agent-lamp-x/.ssh/id_ed25519` and a NUL byte, since a name may hold a newline.
        final StringBuilder modes = new StringBuilder();
    }

    private Saving saveUnlocked(SaveKind kind, String message, Optional<SessionId> session, Instant now)
            throws IOException {
        return saveUnlocked(kind, message, session, Association.between(String.class, String.class), false, now);
    }

    private Saving saveUnlocked(SaveKind kind, String message, Optional<SessionId> session,
                                Association<String, String> trailers, boolean always, Instant now)
            throws IOException {
        ensureRepository();
        Tally tally = new Tally();
        Tuple<GitObjectUtil.Entry> top = Tuple.of(GitObjectUtil.Entry.class);
        Path agent = layout.agentDir();
        String agentName = fileName(agent);
        if (Files.isDirectory(agent, LinkOption.NOFOLLOW_LINKS))
            top = top.add(new GitObjectUtil.Entry(agentName, GitObjectUtil.EntryMode.DIRECTORY,
                    storeDirectory(agent, agentName, tally)));
        if (Files.isRegularFile(layout.config(), LinkOption.NOFOLLOW_LINKS))
            top = top.add(new GitObjectUtil.Entry(CONFIG_FILE, GitObjectUtil.EntryMode.FILE,
                    storeSmall("blob", Files.readAllBytes(layout.config()))));
        top = top.add(new GitObjectUtil.Entry(MODES_FILE, GitObjectUtil.EntryMode.FILE,
                storeSmall("blob", tally.modes.toString().getBytes(StandardCharsets.UTF_8))));
        String tree = storeSmall("tree", GitObjectUtil.tree(top));

        Optional<GitObjectUtil.Commit> head = headCommit();
        if (!always && head.isPresent() && head.get().tree().equals(tree))
            return new Saving(Optional.empty(), head.map(GitObjectUtil.Commit::snapshot), tally.files, tally.skipped);
        Snapshot made = commit(tree, head.map(GitObjectUtil.Commit::id), now,
                GitObjectUtil.message(kind, message, session, trailers));
        return new Saving(Optional.of(made), Optional.of(made), tally.files, tally.skipped);
    }

    /// Stores a directory and everything in it, and returns the name of its tree.
    ///
    /// Links are stored as links and never followed. Sockets, pipes and devices are not files
    /// anyone restores, so they are passed over without a word.
    private String storeDirectory(Path directory, String relative, Tally tally) throws IOException {
        Tuple<GitObjectUtil.Entry> entries = Tuple.of(GitObjectUtil.Entry.class);
        List<Path> children = new ArrayList<>();
        try (DirectoryStream<Path> listing = Files.newDirectoryStream(directory)) {
            for (Path child : listing) children.add(child);
        }
        for (Path child : children) {
            String name = fileName(child);
            String path = relative + "/" + name;
            PosixFileAttributes attributes;
            try {
                attributes = Files.readAttributes(child, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException gone) {
                continue;   // deleted while the save ran
            }
            int permissions = bitsOf(attributes.permissions());
            try {
                if (attributes.isSymbolicLink()) {
                    byte[] target = Files.readSymbolicLink(child).toString().getBytes(StandardCharsets.UTF_8);
                    entries = entries.add(new GitObjectUtil.Entry(name, GitObjectUtil.EntryMode.SYMLINK, storeSmall("blob", target)));
                } else if (attributes.isDirectory()) {
                    if (!Files.isReadable(child) || !Files.isExecutable(child)) {
                        tally.skipped = tally.skipped.add(path + "/ (cannot be read)");
                        continue;
                    }
                    entries = entries.add(new GitObjectUtil.Entry(name, GitObjectUtil.EntryMode.DIRECTORY,
                            storeDirectory(child, path, tally)));
                    if (permissions != 0755) tally.modes.append(octal(permissions)).append(' ').append(path).append('\0');
                } else if (attributes.isRegularFile()) {
                    Optional<String> blob = storeFile(child, attributes.size());
                    if (blob.isEmpty()) {
                        tally.skipped = tally.skipped.add(path + " (kept changing while it was read)");
                        continue;
                    }
                    boolean executable = (permissions & 0100) != 0;
                    entries = entries.add(new GitObjectUtil.Entry(name,
                            executable ? GitObjectUtil.EntryMode.EXECUTABLE : GitObjectUtil.EntryMode.FILE, blob.get()));
                    tally.files++;
                    if (permissions != (executable ? 0755 : 0644))
                        tally.modes.append(octal(permissions)).append(' ').append(path).append('\0');
                }
            } catch (AccessDeniedException unreadable) {
                tally.skipped = tally.skipped.add(path + " (cannot be read)");
            } catch (NoSuchFileException gone) {
                // deleted while the save ran
            }
        }
        return storeSmall("tree", GitObjectUtil.tree(entries));
    }

    /// Stores one file's content, and returns the name of its blob. Empty when the file kept
    /// changing while it was read, three times in a row.
    private Optional<String> storeFile(Path file, long size) throws IOException {
        if (size <= SMALL_FILE) {
            byte[] content = Files.readAllBytes(file);
            return Optional.of(storeSmall("blob", content));
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            long expected = Files.size(file);
            Optional<String> id = hashFile(file, expected);
            if (id.isEmpty()) continue;
            if (Files.exists(objectPath(id.get()))) return id;
            if (writeLarge(file, expected, id.get())) return id;
        }
        return Optional.empty();
    }

    /// The name a file of `size` bytes would have as a blob. Empty when it is no longer that size.
    private static Optional<String> hashFile(Path file, long size) throws IOException {
        MessageDigest sha = GitObjectUtil.sha1();
        sha.update(GitObjectUtil.header("blob", size));
        long read = 0;
        byte[] buffer = new byte[1 << 16];
        try (InputStream in = Files.newInputStream(file)) {
            for (int n; (n = in.read(buffer)) > 0; read += n) sha.update(buffer, 0, n);
        }
        return read == size ? Optional.of(HexFormat.of().formatHex(sha.digest())) : Optional.empty();
    }

    /// Writes a large file as the object `id`, naming it again while it is written. False when
    /// what was written is not what was named, because the file changed in between.
    private boolean writeLarge(Path file, long size, String id) throws IOException {
        Path temporary = Files.createTempFile(repository.resolve("objects"), "incoming-", ".tmp");
        try {
            MessageDigest sha = GitObjectUtil.sha1();
            long read = 0;
            Deflater deflater = new Deflater(Deflater.BEST_SPEED);
            try (InputStream in = Files.newInputStream(file);
                 OutputStream out = new DeflaterOutputStream(Files.newOutputStream(temporary), deflater, 1 << 16)) {
                byte[] header = GitObjectUtil.header("blob", size);
                sha.update(header);
                out.write(header);
                byte[] buffer = new byte[1 << 16];
                for (int n; (n = in.read(buffer)) > 0 && read + n <= size; read += n) {
                    sha.update(buffer, 0, n);
                    out.write(buffer, 0, n);
                }
            } finally {
                deflater.end();
            }
            if (read != size || !HexFormat.of().formatHex(sha.digest()).equals(id)) return false;
            place(temporary, id);
            return true;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /// Stores an object held in memory, unless it is there already, and returns its name.
    private String storeSmall(String type, byte[] content) throws IOException {
        String id = GitObjectUtil.idOf(type, content);
        if (Files.exists(objectPath(id))) return id;
        Path temporary = Files.createTempFile(repository.resolve("objects"), "incoming-", ".tmp");
        // A stream given its own Deflater does not release it, so it is released here, rather
        // than whenever the garbage collector gets to thousands of them.
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try {
            try (OutputStream out = new DeflaterOutputStream(Files.newOutputStream(temporary), deflater)) {
                out.write(GitObjectUtil.header(type, content.length));
                out.write(content);
            }
            place(temporary, id);
        } finally {
            deflater.end();
            Files.deleteIfExists(temporary);
        }
        return id;
    }

    /// Moves a finished object into place. git keeps objects read-only, and so does this.
    private void place(Path temporary, String id) throws IOException {
        Path target = objectPath(id);
        Files.createDirectories(target.getParent());
        FilesystemUtil.setMode(temporary, PosixMode.of(0444));
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private Snapshot commit(String tree, Optional<String> parent, Instant now, String message) throws IOException {
        String id = storeSmall("commit", GitObjectUtil.commit(tree, parent, now, message));
        moveBranch(parent, id);
        return GitObjectUtil.parseCommit(id, readObject(id, "commit")).snapshot();
    }

    /// Points the branch at `id`, the last step of every save.
    ///
    /// The history lock is held, so nothing else moves it at the same time. Checking it still
    /// points at `parent` catches someone committing to this repository by hand meanwhile.
    private void moveBranch(Optional<String> parent, String id) throws IOException {
        Optional<String> now = branch();
        if (!now.equals(parent))
            throw new IOException("the history changed while this save ran; it now ends at "
                    + now.orElse("nothing") + ", not " + parent.orElse("nothing"));
        FilesystemUtil.writeFile(repository.resolve(BRANCH), id + "\n", PosixMode.PRIVATE_FILE);
    }

    /// Creates an empty repository, the first time a lamp is saved.
    private void ensureRepository() throws IOException {
        if (Files.isRegularFile(repository.resolve("HEAD"))) return;
        FilesystemUtil.createDirectories(repository.resolve("objects"), PosixMode.PRIVATE_DIR);
        FilesystemUtil.createDirectories(repository.resolve("refs").resolve("heads"), PosixMode.PRIVATE_DIR);
        FilesystemUtil.createDirectories(repository.resolve("refs").resolve("tags"), PosixMode.PRIVATE_DIR);
        // `gc.auto = 0` keeps git itself, run here by hand, from packing the objects into a
        // form oillamp does not read.
        FilesystemUtil.writeFile(repository.resolve("config"), """
                [core]
                \trepositoryformatversion = 0
                \tbare = true
                [gc]
                \tauto = 0
                """, PosixMode.PRIVATE_FILE);
        FilesystemUtil.writeFile(repository.resolve("description"),
                "The history of the oillamp lamp at " + layout.root() + "\n", PosixMode.PRIVATE_FILE);
        FilesystemUtil.writeFile(repository.resolve("HEAD"), "ref: " + BRANCH + "\n", PosixMode.PRIVATE_FILE);
    }

    // ─── reading ───────────────────────────────────────────────────────────────────────────

    /// Where the branch points: in its own file, or in `packed-refs` if git packed it.
    private Optional<String> branch() throws IOException {
        Path loose = repository.resolve(BRANCH);
        if (Files.isRegularFile(loose)) return Optional.of(checkedId(Files.readString(loose).strip()));
        Path packed = repository.resolve("packed-refs");
        if (Files.isRegularFile(packed))
            for (String line : Files.readAllLines(packed))
                if (line.endsWith(" " + BRANCH)) return Optional.of(checkedId(line.substring(0, 40)));
        return Optional.empty();
    }

    private static String checkedId(String id) throws IOException {
        if (!GitObjectUtil.isObjectId(id)) throw new IOException("the branch names '" + id + "', not a commit");
        return id;
    }

    private Optional<GitObjectUtil.Commit> headCommit() throws IOException {
        Optional<String> id = branch();
        return id.isEmpty() ? Optional.empty() : Optional.of(GitObjectUtil.parseCommit(id.get(), readObject(id.get(), "commit")));
    }

    /// Every commit, newest first, following each one's parent.
    private Tuple<GitObjectUtil.Commit> commits() throws IOException {
        if (!Files.isRegularFile(repository.resolve("HEAD"))) return Tuple.of(GitObjectUtil.Commit.class);
        Tuple<GitObjectUtil.Commit> found = Tuple.of(GitObjectUtil.Commit.class);
        Optional<String> next = branch();
        Set<String> seen = new HashSet<>();
        while (next.isPresent() && seen.add(next.get())) {
            GitObjectUtil.Commit commit = GitObjectUtil.parseCommit(next.get(), readObject(next.get(), "commit"));
            found = found.add(commit);
            next = commit.parent();
        }
        return found;
    }

    private Path objectPath(String id) {
        return repository.resolve("objects").resolve(id.substring(0, 2)).resolve(id.substring(2));
    }

    /// An object's content, for the small ones: commits, trees and the list of permissions.
    private byte[] readObject(String id, String type) throws IOException {
        try (InputStream in = openObject(id, type)) {
            return in.readAllBytes();
        }
    }

    /// An object's content as a stream, after checking its header says `type`.
    private InputStream openObject(String id, String type) throws IOException {
        Path file = objectPath(id);
        if (!Files.isRegularFile(file))
            throw new IOException("the object " + id + " is missing from " + repository.resolve("objects"));
        // The stream's own Inflater, which it releases when it is closed.
        InputStream in = new InflaterInputStream(Files.newInputStream(file));
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        for (int b; (b = in.read()) > 0; ) header.write(b);
        String text = header.toString(StandardCharsets.US_ASCII);
        if (!text.startsWith(type + " ")) {
            in.close();
            throw new IOException("the object " + id + " is not a " + type + " but '" + text + "'");
        }
        return in;
    }

    private Association<String, GitObjectUtil.Entry> entriesOf(Optional<String> tree) throws IOException {
        Association<String, GitObjectUtil.Entry> byName = Association.between(String.class, GitObjectUtil.Entry.class);
        if (tree.isEmpty()) return byName;
        for (GitObjectUtil.Entry entry : GitObjectUtil.parseTree(readObject(tree.get(), "tree")))
            byName = byName.put(entry.name(), entry);
        return byName;
    }

    // ─── restoring ─────────────────────────────────────────────────────────────────────────

    /// Makes the lamp hold what the tree `target` holds, knowing it now holds what `current`
    /// holds. Only what differs is touched: a directory whose tree has not changed is skipped
    /// whole, which is what makes restoring a large home quick.
    private void bringBack(String target, String current) throws IOException {
        Association<String, GitObjectUtil.Entry> wanted = entriesOf(Optional.of(target));
        Association<String, GitObjectUtil.Entry> now = entriesOf(Optional.of(current));
        String agentName = fileName(layout.agentDir());
        // A snapshot holds the agent directory under the name it had when it was saved. That is
        // the same name unless the lamp was made again with a new agent id; then take its one
        // agent directory, whatever it was called.
        Optional<GitObjectUtil.Entry> agentThen = wanted.get(agentName).or(() -> {
            for (GitObjectUtil.Entry entry : wanted.values())
                if (entry.name().startsWith(LampLayout.AGENT_DIR_PREFIX) && entry.isDirectory())
                    return Optional.of(entry);
            return Optional.empty();
        });
        Optional<GitObjectUtil.Entry> agentNow = now.get(agentName);
        if (agentThen.isPresent()) {
            if (!agentNow.equals(agentThen))
                restoreDirectory(layout.agentDir(), agentThen.get().id(),
                        agentNow.filter(GitObjectUtil.Entry::isDirectory).map(GitObjectUtil.Entry::id));
        } else {
            deleteAnything(layout.agentDir());
        }

        Optional<GitObjectUtil.Entry> configThen = wanted.get(CONFIG_FILE);
        if (configThen.isPresent() && !configThen.equals(now.get(CONFIG_FILE)))
            FilesystemUtil.writeBytes(layout.config(), readObject(configThen.get().id(), "blob"), PosixMode.PRIVATE_FILE);

        applyModes(modesIn(now.get(MODES_FILE)), modesIn(wanted.get(MODES_FILE)),
                   agentThen.map(GitObjectUtil.Entry::name).orElse(agentName));
    }

    private void restoreDirectory(Path directory, String target, Optional<String> current) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            deleteAnything(directory);
            Files.createDirectory(directory);
            current = Optional.empty();
        }
        makeWritable(directory);
        Association<String, GitObjectUtil.Entry> wanted = entriesOf(Optional.of(target));
        Association<String, GitObjectUtil.Entry> now = entriesOf(current);
        for (GitObjectUtil.Entry old : now.values())
            if (!wanted.containsKey(old.name())) deleteAnything(directory.resolve(old.name()));
        for (GitObjectUtil.Entry entry : wanted.values()) {
            Optional<GitObjectUtil.Entry> was = now.get(entry.name());
            if (was.isPresent() && was.get().equals(entry)) continue;
            Path path = directory.resolve(entry.name());
            switch (entry.mode()) {
                case DIRECTORY -> restoreDirectory(path, entry.id(),
                        was.filter(GitObjectUtil.Entry::isDirectory).map(GitObjectUtil.Entry::id));
                case SYMLINK -> {
                    deleteAnything(path);
                    Files.createSymbolicLink(path, Path.of(new String(readObject(entry.id(), "blob"), StandardCharsets.UTF_8)));
                }
                case FILE, EXECUTABLE -> {
                    deleteAnything(path);
                    writeBlob(entry.id(), path, entry.mode() == GitObjectUtil.EntryMode.EXECUTABLE ? 0755 : 0644);
                }
            }
        }
    }

    /// Writes a blob to `path`, streaming it, through a temporary file beside it.
    private void writeBlob(String id, Path path, int permissions) throws IOException {
        Path parent = path.getParent();
        Path temporary = Files.createTempFile(parent == null ? path : parent, ".oillamp-", ".tmp");
        try {
            try (InputStream in = openObject(id, "blob")) {
                Files.copy(in, temporary, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.setPosixFilePermissions(temporary, permissionsOf(permissions));
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /// Deletes a file, link or whole directory, never following a link. The agent may have made
    /// directories read-only, as Go does with its module cache, so each is made writable first.
    private static void deleteAnything(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            makeWritable(path);
            List<Path> children = new ArrayList<>();
            try (DirectoryStream<Path> listing = Files.newDirectoryStream(path)) {
                for (Path child : listing) children.add(child);
            }
            for (Path child : children) deleteAnything(child);
        }
        Files.delete(path);
    }

    private static void makeWritable(Path directory) throws IOException {
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS);
        if (permissions.containsAll(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                                            PosixFilePermission.OWNER_EXECUTE))) return;
        Set<PosixFilePermission> more = EnumSet.copyOf(permissions);
        more.addAll(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        Files.setPosixFilePermissions(directory, more);
    }

    // ─── exact permissions ─────────────────────────────────────────────────────────────────

    private Association<String, Integer> modesIn(Optional<GitObjectUtil.Entry> file) throws IOException {
        Association<String, Integer> modes = Association.between(String.class, Integer.class);
        if (file.isEmpty()) return modes;
        String text = new String(readObject(file.get().id(), "blob"), StandardCharsets.UTF_8);
        for (String line : text.split("\0", -1)) {
            int space = line.indexOf(' ');
            if (space < 0) continue;
            try {
                modes = modes.put(line.substring(space + 1), Integer.parseInt(line.substring(0, space), 8) & 07777);
            } catch (NumberFormatException ignored) {
                // A damaged line costs one path its exact permissions, nothing more.
            }
        }
        return modes;
    }

    /// Gives every path the permissions the target snapshot recorded, and puts back git's
    /// default on paths that had other permissions only in the snapshot being left.
    ///
    /// Directories come last and deepest first, because a directory made read-only first would
    /// stop the permissions of what is inside it from being changed.
    ///
    /// @param savedAs the agent directory's name in the target snapshot, which paths in its list
    ///                start with; they are applied to the agent directory as it is named now
    private void applyModes(Association<String, Integer> before, Association<String, Integer> after,
                            String savedAs) throws IOException {
        String agentName = fileName(layout.agentDir());
        List<Path> directories = new ArrayList<>();
        Map<Path, Integer> wanted = new HashMap<>();
        for (var pair : after)
            resolveInLamp(pair.first(), savedAs, agentName).ifPresent(path -> wanted.put(path, pair.second()));
        // -1: the path had its own permissions before, and should get git's default back.
        for (var pair : before)
            resolveInLamp(pair.first(), agentName, agentName).ifPresent(path -> wanted.putIfAbsent(path, -1));
        for (var mode : wanted.entrySet()) {
            Path path = mode.getKey();
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) continue;
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) { directories.add(path); continue; }
            int bits = mode.getValue() >= 0 ? mode.getValue()
                    : Files.isExecutable(path) ? 0755 : 0644;
            Files.setPosixFilePermissions(path, permissionsOf(bits));
        }
        directories.sort((a, b) -> Integer.compare(b.getNameCount(), a.getNameCount()));
        for (Path directory : directories) {
            int bits = wanted.getOrDefault(directory, -1);
            Files.setPosixFilePermissions(directory, permissionsOf(bits >= 0 ? bits : 0755));
        }
    }

    /// A path from the list of permissions, in the lamp. Empty for one that does not lie inside
    /// the agent directory, which a list oillamp wrote never holds.
    private Optional<Path> resolveInLamp(String listed, String savedAs, String agentName) {
        if (!listed.startsWith(savedAs + "/")) return Optional.empty();
        String rest = listed.substring(savedAs.length() + 1);
        for (String part : rest.split("/", -1)) if (!GitObjectUtil.isPlainName(part)) return Optional.empty();
        return Optional.of(layout.root().resolve(agentName).resolve(rest));
    }

    // ─── small helpers ─────────────────────────────────────────────────────────────────────

    /// Runs `work` while holding the history lock, waiting for another save or restore of this
    /// lamp to finish first.
    private <T> Result<T> locked(Supplier<Result<T>> work) {
        Duration pause = Duration.ofMillis(200);
        try {
            for (long waited = 0; ; waited++) {
                Optional<LampLock> lock = LampLock.tryAcquire(layout.historyLock());
                if (lock.isPresent()) {
                    try {
                        return work.get();
                    } finally {
                        lock.get().close();
                    }
                }
                if (pause.multipliedBy(waited).compareTo(LOCK_WAIT) > 0)
                    return Result.err(ProblemCatalogUtil.historyBusy(layout.root(), LOCK_WAIT));
                Thread.sleep(pause);
            }
        } catch (IOException e) {
            return Result.err(ProblemCatalogUtil.saveFailed(layout.root(), ProblemCatalogUtil.reason(e)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.err(ProblemCatalogUtil.saveFailed(layout.root(), "interrupted while waiting for another save"));
        }
    }

    private static String fileName(Path path) {
        Path name = path.getFileName();
        return name == null ? path.toString() : name.toString();
    }

    private static int bitOf(PosixFilePermission permission) {
        return switch (permission) {
            case OWNER_READ     -> 0400;
            case OWNER_WRITE    -> 0200;
            case OWNER_EXECUTE  -> 0100;
            case GROUP_READ     -> 040;
            case GROUP_WRITE    -> 020;
            case GROUP_EXECUTE  -> 010;
            case OTHERS_READ    -> 04;
            case OTHERS_WRITE   -> 02;
            case OTHERS_EXECUTE -> 01;
        };
    }

    private static int bitsOf(Set<PosixFilePermission> permissions) {
        int bits = 0;
        for (PosixFilePermission permission : permissions) bits |= bitOf(permission);
        return bits;
    }

    private static Set<PosixFilePermission> permissionsOf(int bits) {
        Set<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
        for (PosixFilePermission permission : PosixFilePermission.values())
            if ((bits & bitOf(permission)) != 0) permissions.add(permission);
        return permissions;
    }

    private static String octal(int bits) {
        return String.format("%04o", bits);
    }
}
