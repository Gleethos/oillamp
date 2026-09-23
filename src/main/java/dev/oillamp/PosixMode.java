package dev.oillamp;

import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

/// File permission bits, written as the familiar octal number such as `0700`.
///
/// The exact modes matter. The state directory is `0700` so no other host user can reach
/// the sockets inside it, which is what allows the proxy socket inside it to be `0666` so the
/// container's infra user can connect. A wrong mode either breaks the sandbox or opens it up, so
/// modes are a type with named constants rather than bare integers.
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

    /// Parses an octal string such as `"0700"` or `"755"`.
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
