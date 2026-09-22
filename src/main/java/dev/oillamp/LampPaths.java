package dev.oillamp;

import java.nio.file.Path;

import sprouts.Tuple;

/**
 * Decides whether a directory may be used as a lamp — spec FR-03.
 *
 * <p>A lamp <em>owns</em> its directory: it writes state into it and mounts part of it into the
 * container as the agent's home. Pointing that at {@code /etc}, or at the user's home directory
 * itself, would hand the agent the very files the sandbox exists to protect — so those are
 * refused outright, before anything is created.
 *
 * <p>The refusal is deliberately not configurable. A flag to override it would be used exactly
 * once, by someone in a hurry, on the wrong directory.
 */
final class LampPaths {

    private LampPaths() {}

    /** System directories a lamp may never live in or under (FR-03). */
    private static final Tuple<String> FORBIDDEN_PREFIXES = Tuple.of(String.class,
            "/bin", "/boot", "/dev", "/etc", "/lib", "/proc", "/run", "/sbin", "/sys", "/usr", "/var");

    /**
     * Validates an already-normalised, absolute path.
     *
     * <p>Symlink resolution happens in the shell before this is called, so that a symlink into
     * {@code /etc} cannot slip past the prefix check.
     */
    public static Result<Path> validate(Path requested, UserInfo user) {
        if (!requested.isAbsolute())
            return Result.err(Problems.lampForbiddenPath(requested, "is not an absolute path"));

        Path path = requested.normalize();

        if (path.getNameCount() == 0)
            return Result.err(Problems.lampForbiddenPath(path, "is the filesystem root"));

        if (path.equals(user.home()))
            return Result.err(Problems.lampForbiddenPath(path,
                    "is your home directory itself — a lamp would mount part of it into the sandbox; "
                  + "use a subdirectory such as " + user.home().resolve("lamps").resolve("my-feature")));

        String text = path.toString();
        for (String prefix : FORBIDDEN_PREFIXES) {
            // "/lib" must also catch "/lib64" and "/libexec" (spec writes it as /lib*).
            if (text.equals(prefix) || text.startsWith(prefix + "/")
                    || (prefix.equals("/lib") && text.matches("/lib[^/]*(/.*)?")))
                return Result.err(Problems.lampForbiddenPath(path, "is inside the system directory " + prefix));
        }
        return Result.ok(path);
    }
}
