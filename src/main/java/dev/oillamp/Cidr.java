package dev.oillamp;

import java.util.Optional;

/// An address range in CIDR notation, such as `10.0.0.0/8`, from a network rule's
/// `cidrs` list.
///
/// Address ranges are what keep the agent out of private networks. The default policy denies
/// the private, loopback, link-local and carrier-grade NAT ranges, so a connection is refused by
/// where it goes, whatever host name was used to get there.
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

    /// True when `candidate` lies in this network. Mixed address families never match.
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
