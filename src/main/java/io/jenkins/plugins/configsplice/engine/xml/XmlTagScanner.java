package io.jenkins.plugins.configsplice.engine.xml;

import io.jenkins.plugins.configsplice.engine.ErrorCode;
import io.jenkins.plugins.configsplice.engine.SourceRange;
import io.jenkins.plugins.configsplice.engine.SpliceException;
import java.util.ArrayList;
import java.util.List;

/**
 * A minimal lexical scanner that reports every element tag and the exact source range of each
 * attribute value.
 *
 * <h2>Why not StAX for this</h2>
 *
 * <p>StAX reports one {@code Location} per event, positioned at the event, and offers no way at all
 * to ask where an individual attribute's value sits in the source. Exact-byte splicing needs
 * precisely that. So the division of labour is: {@link DotNetAttributeLocator} runs StAX first as a
 * well-formedness and XXE gate, and this scanner — which never resolves entities, never loads
 * external resources and never interprets a DTD — computes the ranges.
 *
 * <p>The scanner is intentionally not a general XML parser. It skips comments, CDATA sections,
 * processing instructions and declarations so that a {@code <add>} inside a comment is never
 * mistaken for markup, and it reads raw qualified names so that {@code xdt:add} never matches
 * {@code add} (SRS section 8.4). Anything it cannot make sense of is a hard failure, because by the
 * time it runs the document has already been proven well-formed.
 */
public final class XmlTagScanner {

    /** What kind of tag was found. */
    public enum Kind {
        OPEN,
        CLOSE,
        SELF_CLOSING
    }

    /**
     * One attribute of a tag.
     *
     * @param name       the raw qualified attribute name as written
     * @param valueRange the range strictly between the quotes, so splicing never disturbs them
     * @param quote      the quote character used, which decides how a replacement must be escaped
     */
    public record Attribute(String name, SourceRange valueRange, char quote) {
    }

    /**
     * One element tag.
     *
     * @param name the raw qualified element name as written, prefix included
     */
    public record Tag(Kind kind, String name, int start, int end, List<Attribute> attributes) {

        /** Finds an attribute by exact, case-sensitive raw name. */
        public Attribute attribute(String attributeName) {
            for (Attribute attribute : attributes) {
                if (attribute.name().equals(attributeName)) {
                    return attribute;
                }
            }
            return null;
        }
    }

    private XmlTagScanner() {
    }

    /** Scans the whole document. */
    public static List<Tag> scan(String text) throws SpliceException {
        List<Tag> tags = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            int open = text.indexOf('<', i);
            if (open < 0) {
                break;
            }
            if (text.startsWith("<!--", open)) {
                i = skipTo(text, open, "-->", "comment");
            } else if (text.startsWith("<![CDATA[", open)) {
                i = skipTo(text, open, "]]>", "CDATA section");
            } else if (text.startsWith("<?", open)) {
                i = skipTo(text, open, "?>", "processing instruction");
            } else if (text.startsWith("<!", open)) {
                i = skipDeclaration(text, open);
            } else {
                i = readTag(text, open, tags);
            }
        }
        return tags;
    }

    private static int readTag(String text, int start, List<Tag> tags) throws SpliceException {
        int i = start + 1;
        boolean closing = i < text.length() && text.charAt(i) == '/';
        if (closing) {
            i++;
        }

        int nameStart = i;
        while (i < text.length() && !isNameBoundary(text.charAt(i))) {
            i++;
        }
        if (i == nameStart) {
            throw malformed("an element tag has no name");
        }
        String name = text.substring(nameStart, i);

        if (closing) {
            int close = text.indexOf('>', i);
            if (close < 0) {
                throw malformed("an end tag is not terminated");
            }
            tags.add(new Tag(Kind.CLOSE, name, start, close + 1, List.of()));
            return close + 1;
        }

        List<Attribute> attributes = new ArrayList<>();
        while (true) {
            i = skipWhitespace(text, i);
            if (i >= text.length()) {
                throw malformed("a start tag is not terminated");
            }
            char c = text.charAt(i);
            if (c == '>') {
                tags.add(new Tag(Kind.OPEN, name, start, i + 1, List.copyOf(attributes)));
                return i + 1;
            }
            if (c == '/') {
                if (i + 1 >= text.length() || text.charAt(i + 1) != '>') {
                    throw malformed("a start tag is not terminated");
                }
                tags.add(new Tag(Kind.SELF_CLOSING, name, start, i + 2, List.copyOf(attributes)));
                return i + 2;
            }
            i = readAttribute(text, i, attributes);
        }
    }

    private static int readAttribute(String text, int at, List<Attribute> attributes) throws SpliceException {
        int nameStart = at;
        int i = at;
        while (i < text.length() && !isNameBoundary(text.charAt(i)) && text.charAt(i) != '=') {
            i++;
        }
        if (i == nameStart) {
            throw malformed("an attribute has no name");
        }
        String name = text.substring(nameStart, i);

        i = skipWhitespace(text, i);
        if (i >= text.length() || text.charAt(i) != '=') {
            throw malformed("attribute '" + name + "' has no value");
        }
        i = skipWhitespace(text, i + 1);
        if (i >= text.length()) {
            throw malformed("attribute '" + name + "' has no value");
        }
        char quote = text.charAt(i);
        if (quote != '"' && quote != '\'') {
            throw malformed("attribute '" + name + "' has an unquoted value");
        }
        int valueStart = i + 1;
        int valueEnd = text.indexOf(quote, valueStart);
        if (valueEnd < 0) {
            throw malformed("attribute '" + name + "' has an unterminated value");
        }
        attributes.add(new Attribute(name, new SourceRange(valueStart, valueEnd), quote));
        return valueEnd + 1;
    }

    /** Skips {@code <!DOCTYPE ...>}, including a bracketed internal subset if present. */
    private static int skipDeclaration(String text, int start) throws SpliceException {
        int i = start + 2;
        int depth = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
            } else if (c == '>' && depth <= 0) {
                return i + 1;
            }
            i++;
        }
        throw malformed("a declaration is not terminated");
    }

    private static int skipTo(String text, int start, String terminator, String what) throws SpliceException {
        int end = text.indexOf(terminator, start);
        if (end < 0) {
            throw malformed("a " + what + " is not terminated");
        }
        return end + terminator.length();
    }

    private static int skipWhitespace(String text, int at) {
        int i = at;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    private static boolean isNameBoundary(char c) {
        return Character.isWhitespace(c) || c == '>' || c == '/' || c == '=';
    }

    private static SpliceException malformed(String detail) {
        return new SpliceException(ErrorCode.PARSE_FAILED, detail);
    }
}
