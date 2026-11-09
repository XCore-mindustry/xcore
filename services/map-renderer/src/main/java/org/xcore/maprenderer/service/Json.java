package org.xcore.maprenderer.service;

import java.util.Collection;
import java.util.Map;

/** Writes the generated protocol models' {@code toPayload()} maps as JSON. */
final class Json {
    static String write(Object value) {
        var out = new StringBuilder(256);
        write(out, value);
        return out.toString();
    }

    private static void write(StringBuilder out, Object value) {
        switch (value) {
            case null -> out.append("null");
            case Map<?, ?> map -> {
                out.append('{');
                boolean first = true;
                for (var entry : map.entrySet()) {
                    if (!first) out.append(',');
                    first = false;
                    string(out, String.valueOf(entry.getKey()));
                    out.append(':');
                    write(out, entry.getValue());
                }
                out.append('}');
            }
            case Collection<?> items -> {
                out.append('[');
                boolean first = true;
                for (Object item : items) {
                    if (!first) out.append(',');
                    first = false;
                    write(out, item);
                }
                out.append(']');
            }
            case Number number -> out.append(number);
            case Boolean bool -> out.append(bool);
            default -> string(out, value.toString());
        }
    }

    private static void string(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }

    private Json() {}
}
