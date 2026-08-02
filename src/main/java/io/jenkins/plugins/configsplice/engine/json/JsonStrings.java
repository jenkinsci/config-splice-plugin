package io.jenkins.plugins.configsplice.engine.json;

/**
 * Minimal JSON string literal codec.
 *
 * <p>Needed in both directions: {@link #encode(String)} serialises a replacement, and
 * {@link #decode(String)} lets the locator prove that a candidate source range really is the token
 * the structural parser reported, by decoding it and comparing against the parser's own value.
 * That check is what turns a parser-reported offset from a hint into a verified fact.
 */
public final class JsonStrings {

    private JsonStrings() {
    }

    /** Serialises {@code value} as a complete JSON string literal, including the surrounding quotes. */
    public static String encode(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
        return out.toString();
    }

    /**
     * Decodes the <em>body</em> of a JSON string literal, i.e. the text between the quotes.
     *
     * @return the decoded value, or {@code null} if the body is not well-formed
     */
    public static String decode(String body) {
        StringBuilder out = new StringBuilder(body.length());
        int i = 0;
        while (i < body.length()) {
            char c = body.charAt(i);
            if (c != '\\') {
                out.append(c);
                i++;
                continue;
            }
            i++;
            if (i >= body.length()) {
                return null;
            }
            char escape = body.charAt(i++);
            switch (escape) {
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                case '/' -> out.append('/');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    if (i + 4 > body.length()) {
                        return null;
                    }
                    try {
                        out.append((char) Integer.parseInt(body.substring(i, i + 4), 16));
                    } catch (NumberFormatException e) {
                        return null;
                    }
                    i += 4;
                }
                default -> {
                    return null;
                }
            }
        }
        return out.toString();
    }
}
