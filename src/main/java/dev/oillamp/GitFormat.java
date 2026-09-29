package dev.oillamp;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

import dev.lamp.LampEvent.SaveKind;
import dev.lamp.LampEvent.Snapshot;

import sprouts.Tuple;

/// The parts of git's storage format that a lamp's history uses, as pure functions over bytes.
///
/// git stores everything as **objects**, each named by the SHA-1 of its content: a **blob** is
/// the content of one file, a **tree** is one directory (a sorted list of names, each with a
/// mode and the name of a blob or another tree), and a **commit** names one tree, the commit
/// before it, and a message. [History] writes these itself rather than running `git`, for two
/// reasons:
///
/// - `git add` stores a directory that holds its own `.git` as a bare pointer, not as its
///   files, and `git checkout` refuses to write a path through `.git`. The agent clones projects
///   into its home, so both would lose exactly the work a restore is for. Written this way, a
///   nested `.git` is stored like any other directory.
/// - The history is part of the lamp, which oillamp reads and writes directly, like every other
///   file in it. No `git` is needed on the host.
///
/// The result is an ordinary repository: `git log` and `git show` read it.
final class GitFormat {

    private GitFormat() {}

    /// The mode git writes for each kind of tree entry. git knows no other permissions than
    /// "executable or not"; [History] keeps the exact ones in a list of its own.
    enum EntryMode {
        FILE("100644"), EXECUTABLE("100755"), SYMLINK("120000"), DIRECTORY("40000");

        private final String octal;

        EntryMode(String octal) { this.octal = octal; }

        String octal() { return octal; }

        static Optional<EntryMode> parse(String octal) {
            for (EntryMode mode : values()) if (mode.octal.equals(octal)) return Optional.of(mode);
            return Optional.empty();
        }
    }

    /// One name in a tree.
    ///
    /// @param id the object it names, 40 hexadecimal characters
    record Entry(String name, EntryMode mode, String id) {
        public Entry {
            if (!isPlainName(name))
                throw new IllegalArgumentException("not a plain file name: \"" + name + "\"");
            if (!isObjectId(id))
                throw new IllegalArgumentException("not an object id: " + id);
        }

        boolean isDirectory() { return mode == EntryMode.DIRECTORY; }
    }

    /// A name that stays inside the directory it is in: not empty, not `.` or `..`, no `/` and no
    /// NUL. Checked for every name read back from the history, so that a damaged tree cannot make
    /// a restore write outside the lamp.
    static boolean isPlainName(String name) {
        return !name.isEmpty() && !name.equals(".") && !name.equals("..")
            && name.indexOf('/') < 0 && name.indexOf('\0') < 0;
    }

    static boolean isObjectId(String id) {
        return id.length() == 40 && id.chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'));
    }

    // ─── objects ───────────────────────────────────────────────────────────────────────────

    /// What precedes an object's content, both when it is named and when it is stored:
    /// for example `blob 12` and a NUL byte.
    static byte[] header(String type, long size) {
        return (type + " " + size + "\0").getBytes(StandardCharsets.US_ASCII);
    }

    /// The name of an object: the SHA-1 of its header and content.
    static String idOf(String type, byte[] content) {
        MessageDigest sha = sha1();
        sha.update(header(type, content.length));
        sha.update(content);
        return HexFormat.of().formatHex(sha.digest());
    }

    static MessageDigest sha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java runtime has SHA-1", e);
        }
    }

    // ─── trees ─────────────────────────────────────────────────────────────────────────────

    /// A tree's content. git requires the entries sorted by name, with a directory sorting as if
    /// its name ended in `/`, so `a.txt` comes before the directory `a` but `a-b` after it.
    static byte[] tree(Tuple<Entry> entries) {
        java.util.List<Entry> sorted = new java.util.ArrayList<>();
        for (Entry entry : entries) sorted.add(entry);
        sorted.sort((a, b) -> java.util.Arrays.compareUnsigned(sortKey(a), sortKey(b)));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Entry entry : sorted) {
            out.writeBytes((entry.mode().octal() + " ").getBytes(StandardCharsets.US_ASCII));
            out.writeBytes(entry.name().getBytes(StandardCharsets.UTF_8));
            out.write(0);
            out.writeBytes(HexFormat.of().parseHex(entry.id()));
        }
        return out.toByteArray();
    }

    private static byte[] sortKey(Entry entry) {
        return (entry.isDirectory() ? entry.name() + "/" : entry.name()).getBytes(StandardCharsets.UTF_8);
    }

    /// Reads a tree's content back into its entries.
    ///
    /// @throws IllegalArgumentException when the content is not a tree oillamp can use
    static Tuple<Entry> parseTree(byte[] content) {
        Tuple<Entry> entries = Tuple.of(Entry.class);
        int at = 0;
        while (at < content.length) {
            int space = indexOf(content, (byte) ' ', at);
            int nul = indexOf(content, (byte) 0, space + 1);
            if (space < 0 || nul < 0 || nul + 21 > content.length)
                throw new IllegalArgumentException("a tree is cut short");
            String octal = new String(content, at, space - at, StandardCharsets.US_ASCII);
            String name = new String(content, space + 1, nul - space - 1, StandardCharsets.UTF_8);
            String id = HexFormat.of().formatHex(content, nul + 1, nul + 21);
            EntryMode mode = EntryMode.parse(octal).orElseThrow(() ->
                    new IllegalArgumentException("a tree holds the mode " + octal + ", which oillamp never writes"));
            entries = entries.add(new Entry(name, mode, id));
            at = nul + 21;
        }
        return entries;
    }

    private static int indexOf(byte[] bytes, byte wanted, int from) {
        for (int i = Math.max(0, from); i < bytes.length; i++) if (bytes[i] == wanted) return i;
        return -1;
    }

    // ─── commits ───────────────────────────────────────────────────────────────────────────

    /// The name and address on every commit in a history. oillamp made them, not the user.
    static final String AUTHOR = "oillamp <oillamp@localhost>";

    /// The last lines of a commit message, which say what made it. git calls lines like these
    /// trailers; `git log` shows them as part of the message.
    static final String KIND_TRAILER = "Oillamp-Save: ";
    static final String SESSION_TRAILER = "Oillamp-Session: ";

    /// A commit's content.
    static byte[] commit(String tree, Optional<String> parent, Instant at, String message) {
        String stamp = AUTHOR + " " + at.getEpochSecond() + " +0000";
        StringBuilder text = new StringBuilder("tree ").append(tree).append('\n');
        parent.ifPresent(p -> text.append("parent ").append(p).append('\n'));
        text.append("author ").append(stamp).append('\n')
            .append("committer ").append(stamp).append('\n')
            .append('\n').append(message);
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    /// The message of a commit that records a save. The first line is what `git log --oneline`
    /// shows, so it says the kind of save and what the person wrote.
    static String message(SaveKind kind, String written, Optional<SessionId> session) {
        String subject = written.isBlank() ? kind.label() : kind.label() + ": " + firstLine(written);
        StringBuilder text = new StringBuilder(subject).append("\n\n");
        if (written.strip().contains("\n")) text.append(written.strip()).append("\n\n");
        text.append(KIND_TRAILER).append(kind.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-')).append('\n');
        session.ifPresent(id -> text.append(SESSION_TRAILER).append(id.value()).append('\n'));
        return text.toString();
    }

    private static String firstLine(String text) {
        return text.strip().lines().findFirst().orElse("");
    }

    /// A commit read back.
    record Commit(String id, String tree, Optional<String> parent, Snapshot snapshot) {}

    /// Reads a commit this format wrote.
    ///
    /// @throws IllegalArgumentException when the content is not such a commit
    static Commit parseCommit(String id, byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        int blank = text.indexOf("\n\n");
        if (blank < 0) throw new IllegalArgumentException("a commit has no message");
        Optional<String> tree = Optional.empty();
        Optional<String> parent = Optional.empty();
        Optional<Instant> at = Optional.empty();
        for (String header : text.substring(0, blank).lines().toList()) {
            if (header.startsWith("tree ")) tree = Optional.of(header.substring(5));
            else if (header.startsWith("parent ") && parent.isEmpty()) parent = Optional.of(header.substring(7));
            else if (header.startsWith("committer ")) at = Optional.of(timeOf(header));
        }
        String message = text.substring(blank + 2);
        String treeId = tree.filter(GitFormat::isObjectId)
                .orElseThrow(() -> new IllegalArgumentException("a commit names no tree"));
        return new Commit(id, treeId, parent,
                new Snapshot(id, at.orElse(Instant.EPOCH), kindIn(message), writtenIn(message), sessionIn(message)));
    }

    private static Instant timeOf(String committerLine) {
        String[] parts = committerLine.split(" ", -1);
        try {
            return Instant.ofEpochSecond(Long.parseLong(parts[parts.length - 2]));
        } catch (RuntimeException unreadable) {
            return Instant.EPOCH;
        }
    }

    private static Tuple<String> trailerLines(String message) {
        Tuple<String> found = Tuple.of(String.class);
        for (String line : message.lines().toList())
            if (line.startsWith(KIND_TRAILER) || line.startsWith(SESSION_TRAILER)) found = found.add(line);
        return found;
    }

    /// A commit made by hand with git, without the trailer, counts as a save made while no session ran.
    private static SaveKind kindIn(String message) {
        for (String line : trailerLines(message))
            if (line.startsWith(KIND_TRAILER)) {
                String name = line.substring(KIND_TRAILER.length()).strip().toUpperCase(java.util.Locale.ROOT).replace('-', '_');
                for (SaveKind kind : SaveKind.values()) if (kind.name().equals(name)) return kind;
            }
        return SaveKind.IDLE;
    }

    private static Optional<String> sessionIn(String message) {
        for (String line : trailerLines(message))
            if (line.startsWith(SESSION_TRAILER)) return Optional.of(line.substring(SESSION_TRAILER.length()).strip());
        return Optional.empty();
    }

    /// What the person wrote: the message without the kind in front of it and without the trailers.
    private static String writtenIn(String message) {
        StringBuilder body = new StringBuilder();
        for (String line : message.lines().toList())
            if (!line.startsWith(KIND_TRAILER) && !line.startsWith(SESSION_TRAILER)) body.append(line).append('\n');
        String text = body.toString().strip();
        String subject = text.lines().findFirst().orElse("");
        String rest = text.substring(subject.length()).strip();
        for (SaveKind kind : SaveKind.values()) {
            if (subject.equals(kind.label())) return rest;
            if (subject.startsWith(kind.label() + ": ")) {
                String written = subject.substring(kind.label().length() + 2);
                return rest.isEmpty() ? written : rest;
            }
        }
        return text;
    }

    // ─── finding a snapshot by the start of its id ─────────────────────────────────────────

    /// The one snapshot whose id starts with `prefix`.
    ///
    /// @return the snapshot, or why there is none: too short, not hexadecimal, no match, or
    ///         several matches
    static Result<Snapshot> find(Tuple<Snapshot> snapshots, String prefix, java.nio.file.Path lamp) {
        String wanted = prefix.strip().toLowerCase(java.util.Locale.ROOT);
        if (wanted.length() < 4 || wanted.length() > 40
                || !wanted.chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')))
            return Result.err(Problems.noSuchSnapshot(prefix, lamp,
                    "a snapshot is named by at least the first four characters of its id, "
                  + "such as the eight `oillamp history` shows"));
        Tuple<Snapshot> matching = snapshots.retainIf(s -> s.id().startsWith(wanted));
        if (matching.isEmpty())
            return Result.err(Problems.noSuchSnapshot(prefix, lamp, "no snapshot of this lamp has an id starting with it"));
        if (matching.size() > 1)
            return Result.err(Problems.noSuchSnapshot(prefix, lamp,
                    matching.size() + " snapshots have an id starting with it; give more of the id"));
        return Result.ok(matching.first());
    }
}
