package dev.oillamp;

import java.util.Locale;
import java.util.Optional;

/// An IPv4 or IPv6 address, used by the network policy.
///
/// Stored as two `long`s rather than a byte array, because records in this code base hold
/// only immutable values, and arrays are neither immutable nor compared by content. An IPv4 address
/// is in the low 32 bits of [#low()].
///
/// The policy compares addresses, not just names, so that a public name that resolves into a
/// private network is still refused. That only works if address comparison is exact, which is why
/// this is its own tested type instead of string handling.
record IpAddress(long high, long low, boolean ipv6) implements Comparable<IpAddress> {

    public static IpAddress ofV4(int a, int b, int c, int d) {
        checkOctet(a); checkOctet(b); checkOctet(c); checkOctet(d);
        long value = ((long) a << 24) | ((long) b << 16) | ((long) c << 8) | d;
        return new IpAddress(0L, value, false);
    }

    private static void checkOctet(int octet) {
        if (octet < 0 || octet > 255)
            throw new IllegalArgumentException("Not an IPv4 octet: " + octet);
    }

    public int bitLength() { return ipv6 ? 128 : 32; }

    /// Parses a literal address. Returns empty for a host name, which the caller must resolve.
    public static Optional<IpAddress> parse(String text) {
        String value = text.trim();
        if (value.startsWith("[") && value.endsWith("]"))
            value = value.substring(1, value.length() - 1);
        if (value.isEmpty()) return Optional.empty();
        return value.indexOf(':') >= 0 ? parseV6(value) : parseV4(value);
    }

    private static Optional<IpAddress> parseV4(String text) {
        String[] parts = text.split("\\.", -1);
        if (parts.length != 4) return Optional.empty();
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            if (parts[i].isEmpty() || parts[i].length() > 3) return Optional.empty();
            for (int c = 0; c < parts[i].length(); c++)
                if (!Character.isDigit(parts[i].charAt(c))) return Optional.empty();
            octets[i] = Integer.parseInt(parts[i]);
            if (octets[i] > 255) return Optional.empty();
        }
        return Optional.of(ofV4(octets[0], octets[1], octets[2], octets[3]));
    }

    private static Optional<IpAddress> parseV6(String text) {
        String head = text;
        String tail = "";
        int doubleColon = text.indexOf("::");
        if (doubleColon >= 0) {
            if (text.indexOf("::", doubleColon + 1) >= 0) return Optional.empty();
            head = text.substring(0, doubleColon);
            tail = text.substring(doubleColon + 2);
        }
        int[] groups = new int[8];
        int headCount = fillGroups(head, groups, 0);
        if (headCount < 0) return Optional.empty();
        if (doubleColon < 0) {
            if (headCount != 8) return Optional.empty();
        } else {
            int[] tailGroups = new int[8];
            int tailCount = fillGroups(tail, tailGroups, 0);
            if (tailCount < 0 || headCount + tailCount > 8) return Optional.empty();
            for (int i = 0; i < tailCount; i++)
                groups[8 - tailCount + i] = tailGroups[i];
        }
        long high = 0L;
        long low = 0L;
        for (int i = 0; i < 4; i++) high = (high << 16) | groups[i];
        for (int i = 4; i < 8; i++) low = (low << 16) | groups[i];
        return Optional.of(new IpAddress(high, low, true));
    }

    private static int fillGroups(String text, int[] groups, int from) {
        if (text.isEmpty()) return 0;
        String[] parts = text.split(":", -1);
        int index = from;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 4) return -1;
            int value;
            try { value = Integer.parseInt(part, 16); } catch (NumberFormatException e) { return -1; }
            if (index >= 8) return -1;
            groups[index++] = value;
        }
        return index - from;
    }

    /// The canonical text form, as it appears in the network log and in denial messages.
    public String text() {
        if (!ipv6)
            return ((low >> 24) & 0xFF) + "." + ((low >> 16) & 0xFF) + "." + ((low >> 8) & 0xFF) + "." + (low & 0xFF);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            long source = i < 4 ? high : low;
            int shift = (3 - (i % 4)) * 16;
            out.append(i == 0 ? "" : ":").append(Long.toHexString((source >> shift) & 0xFFFF));
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }

    @Override public int compareTo(IpAddress other) {
        if (ipv6 != other.ipv6) return ipv6 ? 1 : -1;
        int byHigh = Long.compareUnsigned(high, other.high);
        return byHigh != 0 ? byHigh : Long.compareUnsigned(low, other.low);
    }

    @Override public String toString() { return text(); }
}
