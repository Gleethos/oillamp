package dev.lamp;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import sprouts.Tuple;

/// How a [LampEvent] travels between processes: one JSON object per event, on one line.
///
/// The engine writes events this way to its standard output when an application started it; the
/// application reads them back into the same values. Both ends use this class, so they cannot
/// disagree about the format.
///
/// The format follows the records themselves, so a new kind of event needs no code here:
///
/// - a record becomes an object with one field per component, plus `"type"` (its simple name)
///   when it is one of the cases of a sealed interface, such as `{"type":"Ok","area":…,"text":…}`;
/// - a `Tuple` becomes an array, an `Optional` its value or nothing, an enum its name;
/// - a `Path` becomes its text, a `Duration` and an `Instant` their ISO-8601 text such as
///   `"PT1.5S"`.
///
/// Reading accepts only the types a [LampEvent] is made of, and anything it does not understand
/// (for example an event kind from a newer oillamp) is read as nothing rather than as an error.
final class Wire {

    private Wire() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    static String write(LampEvent event) {
        return encode(event).toString();
    }

    static Optional<LampEvent> read(String line) {
        try {
            JsonNode node = MAPPER.readTree(line);
            return Optional.of((LampEvent) decode(node, LampEvent.class));
        } catch (JacksonException | IllegalArgumentException | ClassCastException | NullPointerException unreadable) {
            return Optional.empty();
        }
    }

    // ─── writing ───────────────────────────────────────────────────────────────────────────

    private static JsonNode encode(Object value) {
        return switch (value) {
            case String text          -> NODES.textNode(text);
            case Integer number       -> NODES.numberNode(number);
            case Long number          -> NODES.numberNode(number);
            case Boolean flag         -> NODES.booleanNode(flag);
            case Enum<?> constant     -> NODES.textNode(constant.name());
            case Path path            -> NODES.textNode(path.toString());
            case Duration duration    -> NODES.textNode(duration.toString());
            case Instant instant      -> NODES.textNode(instant.toString());
            case Tuple<?> tuple       -> {
                ArrayNode array = NODES.arrayNode();
                for (Object element : tuple) array.add(encode(element));
                yield array;
            }
            case Record record        -> encodeRecord(record);
            default -> throw new IllegalArgumentException(
                    "a LampEvent contains a " + value.getClass().getName() + ", which has no JSON form");
        };
    }

    private static ObjectNode encodeRecord(Record record) {
        ObjectNode node = NODES.objectNode();
        if (isCaseOfSealedInterface(record.getClass()))
            node.put("type", record.getClass().getSimpleName());
        for (RecordComponent component : record.getClass().getRecordComponents()) {
            Object value = componentValue(component, record);
            if (value instanceof Optional<?> optional) {
                if (optional.isPresent()) node.set(component.getName(), encode(optional.get()));
            } else {
                node.set(component.getName(), encode(value));
            }
        }
        return node;
    }

    private static Object componentValue(RecordComponent component, Record record) {
        try {
            return component.getAccessor().invoke(record);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot read " + component.getName(), e);
        }
    }

    private static boolean isCaseOfSealedInterface(Class<?> type) {
        for (Class<?> implemented : type.getInterfaces())
            if (implemented.isSealed()) return true;
        return false;
    }

    // ─── reading ───────────────────────────────────────────────────────────────────────────

    private static Object decode(JsonNode node, Type type) {
        if (type instanceof ParameterizedType generic) {
            Class<?> raw = (Class<?>) generic.getRawType();
            Type element = generic.getActualTypeArguments()[0];
            if (raw == Optional.class)
                return node.isMissingNode() || node.isNull()
                        ? Optional.empty() : Optional.of(decode(node, element));
            if (raw == Tuple.class) return decodeTuple(node, element);
            throw new IllegalArgumentException("no JSON form for " + generic);
        }
        Class<?> target = (Class<?>) type;
        if (node.isMissingNode() || node.isNull())
            throw new IllegalArgumentException("missing value for " + target.getSimpleName());
        if (target == String.class)   return node.asText();
        if (target == int.class)      return node.asInt();
        if (target == long.class)     return node.asLong();
        if (target == boolean.class)  return node.asBoolean();
        if (target == Path.class)     return Path.of(node.asText());
        if (target == Duration.class) return Duration.parse(node.asText());
        if (target == Instant.class)  return Instant.parse(node.asText());
        if (target.isEnum())          return enumConstant(target, node.asText());
        if (target.isSealed())        return decode(node, caseNamed(target, node.path("type").asText()));
        if (target.isRecord())        return decodeRecord(node, target);
        throw new IllegalArgumentException("no JSON form for " + target.getName());
    }

    private static Tuple<?> decodeTuple(JsonNode node, Type element) {
        @SuppressWarnings("unchecked")
        Class<Object> elementClass = (Class<Object>) (element instanceof ParameterizedType generic
                ? generic.getRawType() : element);
        Tuple<Object> tuple = Tuple.of(elementClass);
        for (JsonNode item : node) tuple = tuple.add(decode(item, element));
        return tuple;
    }

    private static Object decodeRecord(JsonNode node, Class<?> type) {
        RecordComponent[] components = type.getRecordComponents();
        Class<?>[] types = new Class<?>[components.length];
        Object[] values = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            values[i] = decode(node.path(components[i].getName()), components[i].getGenericType());
        }
        try {
            Constructor<?> constructor = type.getDeclaredConstructor(types);
            return constructor.newInstance(values);
        } catch (InvocationTargetException invalid) {
            // The record's own constructor refused the values, as it would any invalid ones.
            throw new IllegalArgumentException(invalid.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("cannot build a " + type.getSimpleName(), e);
        }
    }

    private static Class<?> caseNamed(Class<?> sealed, String name) {
        for (Class<?> permitted : sealed.getPermittedSubclasses())
            if (permitted.getSimpleName().equals(name)) return permitted;
        throw new IllegalArgumentException(sealed.getSimpleName() + " has no case called " + name);
    }

    private static Object enumConstant(Class<?> type, String name) {
        for (Object constant : type.getEnumConstants())
            if (((Enum<?>) constant).name().equals(name)) return constant;
        throw new IllegalArgumentException(type.getSimpleName() + " has no constant " + name);
    }
}
