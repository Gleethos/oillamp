package dev.gui.genie;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;

import dev.gui.model.Handout;

import sprouts.Tuple;

/// Files passed between a genie and its user, through two directories in the genie's home:
/// `~/outbox`, where the genie puts what it hands over, and `~/inbox`, where the user's files
/// arrive.
///
/// Every file travels through the lamp's ssh command, as the bytes of a command's input or
/// output. Nothing of the host is shared with the sandbox, and nothing the genie hands over is
/// written anywhere on the host but where the user chose to save it.
final class Handouts {

    private Handouts() {}

    static final String MAKE_DIRECTORIES = "mkdir -p \"$HOME/outbox\" \"$HOME/inbox\"";
    static final String LIST = "find \"$HOME/outbox\" -maxdepth 1 -type f -printf '%s\\t%f\\n'";
    static final String READ = "cat -- \"$HOME/outbox/$1\"";
    static final String WRITE = "mkdir -p \"$HOME/inbox\" && cat > \"$HOME/inbox/$1\"";

    static void makeDirectories(Lighter.Lit lamp) throws IOException, InterruptedException {
        Process making = lamp.exec("sh", "-c", MAKE_DIRECTORIES);
        making.getOutputStream().close();
        finish(making, "could not make ~/outbox and ~/inbox");
    }

    /// What is in the outbox, by name.
    static Tuple<Handout> list(Lighter.Lit lamp) throws IOException, InterruptedException {
        Process listing = lamp.exec("sh", "-c", LIST);
        listing.getOutputStream().close();
        String text = new String(listing.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        finish(listing, "could not look into ~/outbox");
        return parse(text);
    }

    /// Reads `find -printf '%s\t%f\n'`: one file per line, its size, a tab, and its name. A
    /// line that does not fit, such as a name with a line break, is left out.
    static Tuple<Handout> parse(String listing) {
        Tuple<Handout> files = Tuple.of(Handout.class);
        for (String line : listing.lines().toList()) {
            int tab = line.indexOf('\t');
            if (tab <= 0) continue;
            try {
                String name = line.substring(tab + 1);
                if (isPlainName(name)) files = files.add(new Handout(name, Long.parseLong(line.substring(0, tab))));
            } catch (NumberFormatException notAListingLine) {
                // Left out.
            }
        }
        return files.sort((a, b) -> a.name().compareTo(b.name()));
    }

    /// Copies the outbox file `name` to `target` on the host, which the user chose. The copy
    /// arrives under a temporary name first, so a failed transfer leaves no half file.
    static void fetch(Lighter.Lit lamp, String name, Path target) throws IOException, InterruptedException {
        if (!isPlainName(name)) throw new IOException("not a file name: " + name);
        Path partial = target.resolveSibling("." + target.getFileName() + ".part");
        Process reading = lamp.exec("sh", "-c", READ, "sh", name);
        reading.getOutputStream().close();
        try (InputStream bytes = reading.getInputStream()) {
            Files.copy(bytes, partial, StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            finish(reading, "could not read ~/outbox/" + name);
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    /// Puts the host file `source` into the genie's `~/inbox`, under the same name.
    static void give(Lighter.Lit lamp, Path source) throws IOException, InterruptedException {
        String name = source.getFileName().toString();
        if (!isPlainName(name)) throw new IOException("not a file name: " + name);
        Process writing = lamp.exec("sh", "-c", WRITE, "sh", name);
        try (OutputStream into = writing.getOutputStream()) {
            Files.copy(source, into);
        }
        finish(writing, "could not put " + name + " into ~/inbox");
    }

    /// A name, not a path: no slash, not `.` or `..`, no control characters.
    static boolean isPlainName(String name) {
        return !name.isEmpty() && !name.equals(".") && !name.equals("..") && !name.contains("/")
            && name.chars().noneMatch(Character::isISOControl);
    }

    private static void finish(Process process, String failure) throws IOException, InterruptedException {
        if (!process.waitFor(10, TimeUnit.MINUTES)) {
            process.destroy();
            throw new IOException(failure + ": it took too long");
        }
        if (process.exitValue() != 0) {
            String said = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            throw new IOException(failure + (said.isEmpty() ? "" : ": " + said));
        }
    }
}
