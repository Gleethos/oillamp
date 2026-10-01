package dev.oillamp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import dev.lamp.Problem;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import sprouts.Association;
import sprouts.Tuple;

/// Reads one table of the merged configuration, collecting mistakes instead of stopping at the
/// first.
///
/// When a value has the wrong type, each accessor records a problem and returns the default, so
/// reading continues. [#allowOnly] records any key that is not known. Because nothing throws,
/// a user with an unknown key, a bad address range and a duplicate forward sees all three in one
/// run.
///
/// Every problem names the file, the key path (such as `network.rules[0].cidrs`), the value
/// and what was expected.
final class ConfigSection {

    private final ConfigTree tree;
    private final String keyPath;
    private final JsonNode node;
    private final List<Problem> problems;

    private ConfigSection(ConfigTree tree, String keyPath, JsonNode node, List<Problem> problems) {
        this.tree = tree;
        this.keyPath = keyPath;
        this.node = node;
        this.problems = problems;
    }

    public static ConfigSection root(ConfigTree tree) {
        return new ConfigSection(tree, "", tree.root(), new ArrayList<>());
    }

    /// Everything that went wrong while reading, in the order it was discovered.
    public Tuple<Problem> problems() { return Tuple.of(Problem.class, problems); }

    public String keyPath() { return keyPath; }

    // ─── navigation ────────────────────────────────────────────────────────────────────────

    /// A sub-table. A missing table is not an error — it simply means "all defaults".
    public ConfigSection table(String key) {
        JsonNode child = node.get(key);
        String path = child(key);
        if (child == null || child.isNull())
            return new ConfigSection(tree, path, emptyObject(), problems);
        if (!child.isObject()) {
            report(Problems.configWrongType(fileOf(path), path, describe(child), "a table"));
            return new ConfigSection(tree, path, emptyObject(), problems);
        }
        return new ConfigSection(tree, path, child, problems);
    }

    /// An array of tables, as written `[[network.rules]]`.
    public Tuple<ConfigSection> tableArray(String key) {
        JsonNode child = node.get(key);
        String path = child(key);
        Tuple<ConfigSection> sections = Tuple.of(ConfigSection.class);
        if (child == null || child.isNull()) return sections;
        if (!child.isArray()) {
            report(Problems.configWrongType(fileOf(path), path, describe(child), "an array of tables"));
            return sections;
        }
        for (int i = 0; i < child.size(); i++) {
            JsonNode element = child.get(i);
            String elementPath = path + "[" + i + "]";
            if (!element.isObject()) {
                report(Problems.configWrongType(fileOf(elementPath), elementPath, describe(element), "a table"));
                continue;
            }
            sections = sections.add(new ConfigSection(tree, elementPath, element, problems));
        }
        return sections;
    }

    /// True when the table is absent or has no keys — used to distinguish "off" from "default".
    public boolean isEmpty() { return node.isEmpty(); }

    public boolean has(String key) { return node.hasNonNull(key); }

    // ─── scalars ───────────────────────────────────────────────────────────────────────────

    public String string(String key, String fallback) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) return fallback;
        if (!value.isTextual()) return wrongType(key, value, "a string", fallback);
        return value.asText();
    }

    public int integer(String key, int fallback) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) return fallback;
        if (!value.isIntegralNumber()) return wrongType(key, value, "a whole number", fallback);
        return value.asInt();
    }

    public double number(String key, double fallback) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) return fallback;
        if (!value.isNumber()) return wrongType(key, value, "a number", fallback);
        return value.asDouble();
    }

    public boolean bool(String key, boolean fallback) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) return fallback;
        if (!value.isBoolean()) return wrongType(key, value, "true or false", fallback);
        return value.asBoolean();
    }

    public Tuple<String> strings(String key, Tuple<String> fallback) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) return fallback;
        if (!value.isArray()) return wrongType(key, value, "an array of strings", fallback);
        Tuple<String> out = Tuple.of(String.class);
        for (int i = 0; i < value.size(); i++) {
            JsonNode element = value.get(i);
            if (!element.isTextual()) {
                String path = child(key) + "[" + i + "]";
                report(Problems.configWrongType(fileOf(path), path, describe(element), "a string"));
                continue;
            }
            out = out.add(element.asText());
        }
        return out;
    }

    /// Reads an array whose elements may be numbers or strings, as a rule's `ports` may be.
    public Tuple<String> scalarsAsText(String key) {
        JsonNode value = node.get(key);
        Tuple<String> out = Tuple.of(String.class);
        if (value == null || value.isNull()) return out;
        if (!value.isArray()) return wrongType(key, value, "an array", out);
        for (int i = 0; i < value.size(); i++) {
            JsonNode element = value.get(i);
            if (element.isObject() || element.isArray()) {
                String path = child(key) + "[" + i + "]";
                report(Problems.configWrongType(fileOf(path), path, describe(element), "a number or a string"));
                continue;
            }
            out = out.add(element.asText());
        }
        return out;
    }

    /// A key/value table of strings, as `agent_tools.versions` is.
    public Association<String, String> stringTable(String key) {
        Association<String, String> out = Association.betweenSorted(String.class, String.class);
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) return out;
        if (!value.isObject()) return wrongType(key, value, "a table of strings", out);
        for (Map.Entry<String, JsonNode> field : ((ObjectNode) value).properties()) {
            if (!field.getValue().isTextual()) {
                String path = child(key) + "." + field.getKey();
                report(Problems.configWrongType(fileOf(path), path, describe(field.getValue()), "a string"));
                continue;
            }
            out = out.put(field.getKey(), field.getValue().asText());
        }
        return out;
    }

    /// Reads a value from a fixed set of spellings, e.g. `gpu = "auto" | "on" | "off"`.
    public <E> E oneOf(String key, E fallback, Function<String, Optional<E>> parse, String expected) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) return fallback;
        if (!value.isTextual()) return wrongType(key, value, expected, fallback);
        Optional<E> parsed = parse.apply(value.asText());
        if (parsed.isEmpty()) {
            invalid(key, "\"" + value.asText() + "\"", "expected " + expected);
            return fallback;
        }
        return parsed.get();
    }

    /// Like [#oneOf] but for a key with no sensible default: a missing or unusable value
    /// yields an empty result, so the caller can skip the whole entry instead of inventing one.
    /// Silently defaulting a rule's `action` would be the worst possible guess.
    public <E> Optional<E> requiredOneOf(String key, Function<String, Optional<E>> parse, String expected) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) {
            invalid(key, "(missing)", "expected " + expected);
            return Optional.empty();
        }
        if (!value.isTextual()) {
            String path = child(key);
            report(Problems.configWrongType(fileOf(path), path, describe(value), expected));
            return Optional.empty();
        }
        Optional<E> parsed = parse.apply(value.asText());
        if (parsed.isEmpty()) invalid(key, "\"" + value.asText() + "\"", "expected " + expected);
        return parsed;
    }

    // ─── schema enforcement ────────────────────────────────────────────────────────────────

    /// Reports every key in this table that is not one of `knownKeys`.
    ///
    /// Unknown keys are errors, not warnings, because a misspelled key would otherwise be ignored,
    /// and an ignored network rule setting is a hole in the sandbox. The message suggests the nearest
    /// known key, because the cause is almost always a typo.
    public ConfigSection allowOnly(String... knownKeys) {
        for (Map.Entry<String, JsonNode> field : ((ObjectNode) node).properties()) {
            String key = field.getKey();
            boolean known = false;
            for (String candidate : knownKeys) if (candidate.equals(key)) { known = true; break; }
            if (known) continue;
            String path = child(key);
            report(Problems.configUnknownKey(fileOf(path), path, suggestionFor(key, knownKeys)));
        }
        return this;
    }

    // ─── reporting ─────────────────────────────────────────────────────────────────────────

    /// Reports a value that parsed but cannot work, e.g. a width outside the supported range.
    public void invalid(String key, String value, String expectation) {
        String path = child(key);
        report(Problems.configInvalidValue(fileOf(path), path, value, expectation));
    }

    /// Reports a problem about this table as a whole, e.g. two forwards sharing a port.
    public void invalidHere(String value, String expectation) {
        report(Problems.configInvalidValue(fileOf(keyPath), keyPath, value, expectation));
    }

    public void report(Problem problem) { problems.add(problem); }

    // ─── helpers ───────────────────────────────────────────────────────────────────────────

    private <T> T wrongType(String key, JsonNode value, String expected, T fallback) {
        String path = child(key);
        report(Problems.configWrongType(fileOf(path), path, describe(value), expected));
        return fallback;
    }

    private String child(String key) { return keyPath.isEmpty() ? key : keyPath + "." + key; }

    private Path fileOf(String path) { return tree.originOf(path).orElse(ConfigSource.BUILT_IN); }

    private static ObjectNode emptyObject() {
        return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
    }

    private static String describe(JsonNode value) {
        if (value.isTextual()) return "\"" + value.asText() + "\"";
        if (value.isArray())   return "an array";
        if (value.isObject())  return "a table";
        return value.asText();
    }

    /// The nearest known key by edit distance, so "netwrok" gets "did you mean 'network'?".
    private static String suggestionFor(String typo, String[] knownKeys) {
        String best = "";
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : knownKeys) {
            int distance = editDistance(typo.toLowerCase(Locale.ROOT), candidate.toLowerCase(Locale.ROOT));
            if (distance < bestDistance) { bestDistance = distance; best = candidate; }
        }
        boolean plausible = bestDistance <= Math.max(2, typo.length() / 3);
        if (plausible && !best.isEmpty()) return "did you mean '" + best + "'?";
        return knownKeys.length == 0 ? "no keys are allowed here"
                                     : "one of: " + String.join(", ", knownKeys);
    }

    private static int editDistance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), substitution);
            }
            int[] swap = previous; previous = current; current = swap;
        }
        return previous[b.length()];
    }
}
