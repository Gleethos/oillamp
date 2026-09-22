package dev.oillamp;

import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

/**
 * A POSIX permission bit set, as the octal number people actually think in.
 *
 * <p>The exact modes matter here: spec §9.2 makes the whole state directory {@code 0700} so
 * that other host users cannot reach the sockets inside it, and then relaxes individual socket
 * files to {@code 0666} so the container's infra user can connect. Getting one of those wrong
 * either breaks the sandbox or opens it up, so the mode is a typed value, not an int passed around.
 *
 * <p>Deliberately <b>package-private</b>: a permission bit set, so §9.2's modes are written as
 * names rather than octal literals scattered through the code.
 */
record PosixMode(int bits) {

    public static final PosixMode PRIVATE_DIR   = PosixMode.of(0700);
    public static final PosixMode PRIVATE_FILE  = PosixMode.of(0600);
    public static final PosixMode PUBLIC_DIR    = PosixMode.of(0755);
    public static final PosixMode PUBLIC_FILE   = PosixMode.of(0644);
    public static final PosixMode SHARED_SOCKET = PosixMode.of(0666);

    public PosixMode {
        if (bits < 0 || bits > 0777)
            throw new IllegalArgumentException("Not a permission mode: " + Integer.toOctalString(bits));
    }

    public static PosixMode of(int octalBits) { return new PosixMode(octalBits); }

    /** Parses the form people write in documentation and config, e.g. {@code "0700"}. */
    public static PosixMode parse(String octal) {
        return new PosixMode(Integer.parseInt(octal, 8));
    }

    public Set<PosixFilePermission> permissions() {
        Set<PosixFilePermission> out = EnumSet.noneOf(PosixFilePermission.class);
        if ((bits & 0400) != 0) out.add(PosixFilePermission.OWNER_READ);
        if ((bits & 0200) != 0) out.add(PosixFilePermission.OWNER_WRITE);
        if ((bits & 0100) != 0) out.add(PosixFilePermission.OWNER_EXECUTE);
        if ((bits & 0040) != 0) out.add(PosixFilePermission.GROUP_READ);
        if ((bits & 0020) != 0) out.add(PosixFilePermission.GROUP_WRITE);
        if ((bits & 0010) != 0) out.add(PosixFilePermission.GROUP_EXECUTE);
        if ((bits & 0004) != 0) out.add(PosixFilePermission.OTHERS_READ);
        if ((bits & 0002) != 0) out.add(PosixFilePermission.OTHERS_WRITE);
        if ((bits & 0001) != 0) out.add(PosixFilePermission.OTHERS_EXECUTE);
        return out;
    }

    @Override public String toString() { return String.format("%04o", bits); }
}
