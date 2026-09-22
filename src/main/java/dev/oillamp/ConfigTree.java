package dev.oillamp;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import sprouts.Association;

/**
 * Several configuration files merged into one tree, remembering where each value came from.
 *
 * <p>Merge semantics are spec §20.1: <b>tables merge key by key</b> (a later file overrides
 * single values) but <b>arrays replace as a whole</b>. That asymmetry is deliberate and
 * documented at the top of the shipped template: a lamp that defines {@code network.rules} gets
 * exactly the rules it lists, and never a silent union with the user-global file's rules. A
 * half-merged policy would be a policy nobody can reason about.
 *
 * <p>Origins are tracked per key path so that a problem can name the file the offending value
 * actually came from, which matters as soon as two files are in play.
 *
 * <p>Deliberately <b>package-private</b>: a thin walk over Jackson's tree, and the only place
 * Jackson is named. That is only true while it stays internal — which is the point.
 */
record ConfigTree(JsonNode root, Association<String, Path> origins) {

    public static ConfigTree of(JsonNode root, Path origin) {
        return new ConfigTree(root, recordOrigins(Association.between(String.class, Path.class), "", root, origin));
    }

    /** Merges {@code later} on top of this tree. */
    public ConfigTree mergedWith(JsonNode later, Path origin) {
        JsonNode merged = merge(root, later);
        return new ConfigTree(merged, recordOrigins(origins, "", later, origin));
    }

    /** Which file last set this key path, if any. */
    public Optional<Path> originOf(String keyPath) {
        Optional<Path> exact = origins.get(keyPath);
        if (exact.isPresent()) return exact;
        // Fall back to the nearest ancestor - e.g. an element inside an array of tables.
        String path = keyPath;
        while (true) {
            int cut = Math.max(path.lastIndexOf('.'), path.lastIndexOf('['));
            if (cut <= 0) return Optional.empty();
            path = path.substring(0, cut);
            Optional<Path> ancestor = origins.get(path);
            if (ancestor.isPresent()) return ancestor;
        }
    }

    private static JsonNode merge(JsonNode base, JsonNode later) {
        if (!(base instanceof ObjectNode baseObject) || !(later instanceof ObjectNode laterObject))
            return later;                                  // scalars and arrays replace wholesale
        ObjectNode result = baseObject.deepCopy();
        for (Map.Entry<String, JsonNode> field : laterObject.properties()) {
            JsonNode existing = result.get(field.getKey());
            result.set(field.getKey(), existing == null ? field.getValue() : merge(existing, field.getValue()));
        }
        return result;
    }

    private static Association<String, Path> recordOrigins(Association<String, Path> origins,
                                                           String keyPath, JsonNode node, Path origin) {
        Association<String, Path> result = origins;
        if (!keyPath.isEmpty()) result = result.put(keyPath, origin);
        if (node instanceof ObjectNode object) {
            for (Map.Entry<String, JsonNode> field : object.properties()) {
                String child = keyPath.isEmpty() ? field.getKey() : keyPath + "." + field.getKey();
                result = recordOrigins(result, child, field.getValue(), origin);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++)
                result = recordOrigins(result, keyPath + "[" + i + "]", node.get(i), origin);
        }
        return result;
    }
}
