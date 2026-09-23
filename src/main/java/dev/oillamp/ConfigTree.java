package dev.oillamp;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import sprouts.Association;

/**
 * The configuration files merged into one tree, remembering which file each value came from.
 *
 * <p><b>Tables merge key by key</b>: a later file overrides single values. <b>Arrays are replaced
 * as a whole</b>: a lamp that defines {@code network.rules} gets exactly the rules it lists, never
 * a combination with the global file's rules, which nobody could reason about. The template
 * written into each new lamp says this at the top.
 *
 * <p>The file of origin is kept for every key path, so a problem can name the file the bad value
 * came from.
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
        // Fall back to the nearest parent key, for example for an element inside an array of tables.
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
