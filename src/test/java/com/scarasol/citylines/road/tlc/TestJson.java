package com.scarasol.citylines.road.tlc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, strict JSON reader for the Citylines asset tests.
 *
 * <p>The asset tests must run without Minecraft on the classpath, so they cannot
 * rely on a JSON library that only ships with the game. Only the subset used by the
 * generated assets is supported (objects, arrays, strings, ints, floats, booleans,
 * null) and anything unexpected is rejected loudly, so a broken parser can never turn
 * into a silently passing test.
 */
final class TestJson {

    private final String text;
    private int index;

    private TestJson(String text) {
        this.text = text;
    }

    static Object read(String text) {
        TestJson reader = new TestJson(text);
        reader.skipWhitespace();
        Object value = reader.value();
        reader.skipWhitespace();
        if (reader.index != text.length()) {
            throw new IllegalArgumentException("trailing JSON at offset " + reader.index);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    static List<Object> array(Object value) {
        return (List<Object>) value;
    }

    static int intValue(Map<String, Object> object, String key) {
        Object value = object.get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("missing integer field '" + key + "'");
        }
        return number.intValue();
    }

    private Object value() {
        if (index >= text.length()) {
            throw new IllegalArgumentException("unexpected end of JSON");
        }
        char c = text.charAt(index);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object() {
        expect('{');
        Map<String, Object> result = new LinkedHashMap<>();
        skipWhitespace();
        if (peek() == '}') {
            index++;
            return result;
        }
        while (true) {
            skipWhitespace();
            String key = string();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            result.put(key, value());
            skipWhitespace();
            char c = next();
            if (c == '}') {
                return result;
            }
            if (c != ',') {
                throw new IllegalArgumentException("expected ',' or '}' at offset " + (index - 1));
            }
        }
    }

    private List<Object> array() {
        expect('[');
        List<Object> result = new ArrayList<>();
        skipWhitespace();
        if (peek() == ']') {
            index++;
            return result;
        }
        while (true) {
            skipWhitespace();
            result.add(value());
            skipWhitespace();
            char c = next();
            if (c == ']') {
                return result;
            }
            if (c != ',') {
                throw new IllegalArgumentException("expected ',' or ']' at offset " + (index - 1));
            }
        }
    }

    private String string() {
        expect('"');
        StringBuilder builder = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') {
                return builder.toString();
            }
            if (c == '\\') {
                char escape = next();
                switch (escape) {
                    case '"', '\\', '/' -> builder.append(escape);
                    case 'b' -> builder.append('\b');
                    case 'f' -> builder.append('\f');
                    case 'n' -> builder.append('\n');
                    case 'r' -> builder.append('\r');
                    case 't' -> builder.append('\t');
                    case 'u' -> {
                        builder.append((char) Integer.parseInt(text.substring(index, index + 4), 16));
                        index += 4;
                    }
                    default -> throw new IllegalArgumentException("bad escape at offset " + (index - 1));
                }
            } else {
                builder.append(c);
            }
        }
    }

    private Object number() {
        int start = index;
        while (index < text.length() && "+-0123456789.eE".indexOf(text.charAt(index)) >= 0) {
            index++;
        }
        String raw = text.substring(start, index);
        if (raw.isEmpty()) {
            throw new IllegalArgumentException("expected a value at offset " + start);
        }
        if (raw.indexOf('.') < 0 && raw.indexOf('e') < 0 && raw.indexOf('E') < 0) {
            return Integer.valueOf(raw);
        }
        return Double.valueOf(raw);
    }

    private Object literal(String literal, Object value) {
        if (!text.startsWith(literal, index)) {
            throw new IllegalArgumentException("bad literal at offset " + index);
        }
        index += literal.length();
        return value;
    }

    private char peek() {
        if (index >= text.length()) {
            throw new IllegalArgumentException("unexpected end of JSON");
        }
        return text.charAt(index);
    }

    private char next() {
        char c = peek();
        index++;
        return c;
    }

    private void expect(char expected) {
        char c = next();
        if (c != expected) {
            throw new IllegalArgumentException("expected '" + expected + "' at offset " + (index - 1));
        }
    }

    private void skipWhitespace() {
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
    }
}
