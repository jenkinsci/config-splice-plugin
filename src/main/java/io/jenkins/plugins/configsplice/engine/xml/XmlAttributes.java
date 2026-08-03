package io.jenkins.plugins.configsplice.engine.xml;

/**
 * Escaping and unescaping of XML attribute values.
 *
 * <p>Both directions are needed. Decoding lets the locator compare a user-supplied key such as
 * {@code A&B} against source text written as {@code A&amp;B}. Encoding produces a replacement that
 * is correct for the quote character actually used at the target site, so a value containing a
 * quote can never break out of the attribute — which is the injection concern here, and the reason
 * escaping is driven by the observed source quote rather than by a fixed convention.
 */
public final class XmlAttributes {

    private XmlAttributes() {
    }

    /**
     * Escapes {@code value} for insertion between {@code quote} characters.
     *
     * <p>{@code &} and {@code <} are always escaped because they are never legal literally in an
     * attribute value. {@code >} is escaped defensively. Only the delimiting quote is escaped, so
     * an apostrophe inside a double-quoted attribute stays readable.
     */
    public static String encode(String value, char quote) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append(quote == '"' ? "&quot;" : "\"");
                case '\'' -> out.append(quote == '\'' ? "&apos;" : "'");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Escapes {@code value} for use as element text rather than as an attribute value.
     *
     * <p>Quotes are left alone: inside element content they carry no structural meaning, and escaping
     * them would churn bytes for no reason. {@code &} and {@code <} must be escaped because they are
     * never legal literally; {@code >} is escaped defensively so that no accidental {@code ]]>}
     * sequence can form.
     */
    public static String encodeText(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Decodes the predefined XML entities and numeric character references.
     *
     * <p>Custom entities are not decoded: DTDs are disabled outright, so a document that defines one
     * has already been rejected before this runs. Anything unrecognised is left verbatim rather than
     * guessed at.
     */
    public static String decode(String raw) {
        if (raw.indexOf('&') < 0) {
            return raw;
        }
        StringBuilder out = new StringBuilder(raw.length());
        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (c != '&') {
                out.append(c);
                i++;
                continue;
            }
            int semicolon = raw.indexOf(';', i + 1);
            if (semicolon < 0) {
                out.append(c);
                i++;
                continue;
            }
            String entity = raw.substring(i + 1, semicolon);
            String decoded = decodeEntity(entity);
            if (decoded == null) {
                out.append(c);
                i++;
            } else {
                out.append(decoded);
                i = semicolon + 1;
            }
        }
        return out.toString();
    }

    private static String decodeEntity(String entity) {
        switch (entity) {
            case "amp":
                return "&";
            case "lt":
                return "<";
            case "gt":
                return ">";
            case "quot":
                return "\"";
            case "apos":
                return "'";
            default:
                break;
        }
        if (entity.length() > 1 && entity.charAt(0) == '#') {
            try {
                int codePoint = entity.charAt(1) == 'x' || entity.charAt(1) == 'X'
                        ? Integer.parseInt(entity.substring(2), 16)
                        : Integer.parseInt(entity.substring(1));
                if (Character.isValidCodePoint(codePoint)) {
                    return new String(Character.toChars(codePoint));
                }
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
