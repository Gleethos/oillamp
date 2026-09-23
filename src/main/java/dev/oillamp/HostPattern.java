package dev.oillamp;

import java.util.Locale;

/// A host name pattern in a network rule's `hosts` list.
///
/// There are three forms: an exact name, `*.example.com`, and `*`. The wildcard form
/// does not match the bare domain: `*.example.com` matches `docs.example.com` but not
/// `example.com`. A rule should not quietly cover more than it says.
sealed interface HostPattern {

    record Exact(String host)            implements HostPattern {}
    record AnySubdomainOf(String suffix) implements HostPattern {}
    record Any()                         implements HostPattern {}

    static HostPattern parse(String text) {
        String value = normalise(text);
        if (value.equals("*"))         return new Any();
        if (value.startsWith("*."))    return new AnySubdomainOf(value.substring(2));
        return new Exact(value);
    }

    /// Lower-cases a host name and removes trailing dots, which do not change its meaning.
    static String normalise(String host) {
        String value = host.trim().toLowerCase(Locale.ROOT);
        while (value.endsWith(".")) value = value.substring(0, value.length() - 1);
        return value;
    }

    default boolean matches(String host) {
        String value = normalise(host);
        return switch (this) {
            case Any ignored          -> true;
            case Exact e              -> e.host().equals(value);
            case AnySubdomainOf a     -> value.endsWith("." + a.suffix());
        };
    }

    default String text() {
        return switch (this) {
            case Any ignored      -> "*";
            case Exact e          -> e.host();
            case AnySubdomainOf a -> "*." + a.suffix();
        };
    }
}
