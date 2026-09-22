package dev.oillamp;

import java.util.Optional;

/**
 * A network in CIDR notation, e.g. {@code 10.0.0.0/8} — spec §18.3.
 *
 * <p>These are what keeps the agent out of the intranet. The shipped default policy denies the
 * private, loopback, link-local and CGNAT ranges (§18.4), so an agent that follows a link into
 * the company network is stopped by address, not by a host name it could have been tricked about.
 *
 * <p>Deliberately <b>package-private</b>: an address range for the policy engine, not a
 * general-purpose IP library. It implements exactly what {@code oillamp.toml} needs to express and
 * nothing more.
 */
record Cidr(IpAddress network, int prefixLength) {

    public Cidr {
        int limit = network.bitLength();
        if (prefixLength < 0 || prefixLength > limit)
            throw new IllegalArgumentException(
                    "A /" + prefixLength + " prefix is out of range for " + network.text());
    }

    public static Optional<Cidr> parse(String text) {
        int slash = text.lastIndexOf('/');
        if (slash < 0) return Optional.empty();
        Optional<IpAddress> address = IpAddress.parse(text.substring(0, slash));
        if (address.isEmpty()) return Optional.empty();
        int prefix;
        try { prefix = Integer.parseInt(text.substring(slash + 1).trim()); }
        catch (NumberFormatException e) { return Optional.empty(); }
        if (prefix < 0 || prefix > address.get().bitLength()) return Optional.empty();
        return Optional.of(new Cidr(masked(address.get(), prefix), prefix));
    }

    /** True when {@code candidate} lies in this network. Mixed address families never match. */
    public boolean contains(IpAddress candidate) {
        if (candidate.ipv6() != network.ipv6()) return false;
        IpAddress reduced = masked(candidate, prefixLength);
        return reduced.high() == network.high() && reduced.low() == network.low();
    }

    private static IpAddress masked(IpAddress address, int prefix) {
        if (!address.ipv6()) {
            long mask = prefix == 0 ? 0L : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
            return new IpAddress(0L, address.low() & mask, false);
        }
        long highMask = prefix >= 64 ? -1L : (prefix == 0 ? 0L : -1L << (64 - prefix));
        long lowMask  = prefix <= 64 ? 0L  : (prefix == 128 ? -1L : -1L << (128 - prefix));
        return new IpAddress(address.high() & highMask, address.low() & lowMask, true);
    }

    @Override public String toString() { return network.text() + "/" + prefixLength; }
}
