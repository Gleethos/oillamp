package dev.oillamp;

import java.nio.file.Path;

import sprouts.Tuple;

/// Refuses paths that must never become a lamp: `/`, the user's home directory itself, and
/// system directories such as `/etc` or `/usr`.
///
/// A lamp writes state into its directory and mounts part of it into the container. Doing that
/// to a system directory or to the whole home directory would give the agent exactly the files the
/// sandbox exists to protect.
///
/// There is deliberately no option to override this. It would only ever be used by mistake.
final class LampLocationUtil {

    private LampLocationUtil() {}

    /// System directories a lamp may never be in or under.
    private static final Tuple<String> FORBIDDEN_PREFIXES = Tuple.of(String.class,
            "/bin", "/boot", "/dev", "/etc", "/lib", "/proc", "/run", "/sbin", "/sys", "/usr", "/var");

    /// Validates an already-normalised, absolute path.
    ///
    /// Symlink resolution happens in the shell before this is called, so that a symlink into
    /// `/etc` cannot slip past the prefix check.
    public static Result<Path> validate(Path requested, UserInfo user) {
        if (!requested.isAbsolute())
            return Result.err(ProblemCatalogUtil.lampForbiddenPath(requested, "is not an absolute path"));

        Path path = requested.normalize();

        if (path.getNameCount() == 0)
            return Result.err(ProblemCatalogUtil.lampForbiddenPath(path, "is the filesystem root"));

        if (path.equals(user.home()))
            return Result.err(ProblemCatalogUtil.lampForbiddenPath(path,
                    "is your home directory itself — a lamp would mount part of it into the sandbox; "
                  + "use a subdirectory such as " + user.home().resolve("lamps").resolve("my-feature")));

        String text = path.toString();
        if (text.contains(":"))
            return Result.err(ProblemCatalogUtil.lampInvalidPath(path, "contains a colon"));
        for (String prefix : FORBIDDEN_PREFIXES) {
            // "/lib" must also catch "/lib64" and "/libexec".
            if (text.equals(prefix) || text.startsWith(prefix + "/")
                    || (prefix.equals("/lib") && text.matches("/lib[^/]*(/.*)?")))
                return Result.err(ProblemCatalogUtil.lampForbiddenPath(path, "is inside the system directory " + prefix));
        }
        return Result.ok(path);
    }
}
