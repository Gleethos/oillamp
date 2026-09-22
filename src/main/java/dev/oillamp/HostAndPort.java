package dev.oillamp;

/**
 * A TCP endpoint as written in configuration, e.g. {@code llm.corp.example.com:8000}.
 *
 * <p>Deliberately <b>package-private</b>: a parsed target for a forward or a policy rule.
 */
record HostAndPort(String host, int port) {

    public HostAndPort {
        if (host.isBlank())
            throw new IllegalArgumentException("A target needs a host");
        if (port < 1 || port > 65535)
            throw new IllegalArgumentException("Not a TCP port: " + port);
    }

    /** Parses {@code host:port}. IPv6 literals are written in brackets, as in URLs. */
    public static HostAndPort parse(String text) {
        int colon = text.lastIndexOf(':');
        if (colon < 0)
            throw new IllegalArgumentException("Expected host:port, got: '" + text + "'");
        String host = text.substring(0, colon);
        if (host.startsWith("[") && host.endsWith("]"))
            host = host.substring(1, host.length() - 1);
        int port;
        try {
            port = Integer.parseInt(text.substring(colon + 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Expected host:port, got: '" + text + "'");
        }
        return new HostAndPort(host, port);
    }

    @Override public String toString() {
        return host.contains(":") ? "[" + host + "]:" + port : host + ":" + port;
    }
}
