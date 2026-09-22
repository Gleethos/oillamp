package dev.oillamp;

import java.util.Locale;

/**
 * A host name pattern in a network rule — spec §18.3.
 *
 * <p>Only three forms exist, and a wildcard deliberately does <em>not</em> match the apex:
 * {@code *.example.com} covers {@code docs.example.com} but not {@code example.com}. Allowing
 * a rule to quietly mean more than it says is exactly the kind of surprise a sandbox must not have.
 */
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

    /** Host names are case-insensitive and a trailing dot means the same name (§18.3). */
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
