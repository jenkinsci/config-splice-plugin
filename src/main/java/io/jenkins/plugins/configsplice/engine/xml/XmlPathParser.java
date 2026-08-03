package io.jenkins.plugins.configsplice.engine.xml;

import io.jenkins.plugins.configsplice.engine.ErrorCode;
import io.jenkins.plugins.configsplice.engine.SpliceException;
import java.util.ArrayList;
import java.util.List;

/**
 * Parser for the generic XML path grammar of SRS section 8.4.
 *
 * <pre>
 * path     := element ( '.' element )* '.' selector
 * element  := member index?
 * member   := unquoted | quoted
 * unquoted := one or more characters other than '.', '[', ']', '\'' and whitespace
 * quoted   := '...'   with '' representing one literal single quote
 * index    := '[' digits ']'
 * selector := '&#64;' member | '#text'
 * </pre>
 *
 * <p>The same lexical rules as the JSON grammar, with two additions: an element step may carry an
 * occurrence index, and the path must end in a selector. Requiring the terminal selector is what
 * keeps the grammar unambiguous — without it, {@code configuration.appSettings} could mean either the
 * element or something on it, and XML has no single obvious "value of an element".
 */
public final class XmlPathParser {

    private static final String TEXT_SELECTOR = "#text";

    private final String input;

    private int position;

    private XmlPathParser(String input) {
        this.input = input;
    }

    /**
     * Parses a generic XML path.
     *
     * @throws SpliceException {@link ErrorCode#PATH_SYNTAX} if the path is malformed
     */
    public static XmlPath parse(String input) throws SpliceException {
        if (input == null || input.isEmpty()) {
            throw new SpliceException(ErrorCode.PATH_SYNTAX, "path must not be empty");
        }
        return new XmlPathParser(input).parsePath();
    }

    private XmlPath parsePath() throws SpliceException {
        List<XmlPath.Element> elements = new ArrayList<>();
        XmlPath.Selector selector = null;

        while (true) {
            if (startsSelector()) {
                selector = parseSelector();
                break;
            }
            elements.add(parseElement());
            if (position >= input.length()) {
                break;
            }
            if (input.charAt(position) != '.') {
                throw syntaxError("expected '.' between path steps");
            }
            position++;
        }

        if (selector == null) {
            throw syntaxError("a generic XML path must end in '.@attribute' or '." + TEXT_SELECTOR + "'");
        }
        if (elements.isEmpty()) {
            throw syntaxError("a generic XML path needs at least the document element before the selector");
        }
        if (position < input.length()) {
            throw syntaxError("unexpected text after the selector");
        }
        return new XmlPath(elements, selector);
    }

    private boolean startsSelector() {
        if (position >= input.length()) {
            return false;
        }
        char c = input.charAt(position);
        return c == '@' || (c == '#' && input.startsWith(TEXT_SELECTOR, position));
    }

    private XmlPath.Selector parseSelector() throws SpliceException {
        if (input.startsWith(TEXT_SELECTOR, position)) {
            position += TEXT_SELECTOR.length();
            return new XmlPath.Text();
        }
        position++; // '@'
        String name = parseMember();
        return new XmlPath.Attribute(name);
    }

    private XmlPath.Element parseElement() throws SpliceException {
        String name = parseMember();
        Integer index = null;
        if (position < input.length() && input.charAt(position) == '[') {
            index = parseIndex();
        }
        return new XmlPath.Element(name, index);
    }

    private String parseMember() throws SpliceException {
        if (position >= input.length()) {
            throw syntaxError("expected an element name");
        }
        return input.charAt(position) == '\'' ? parseQuotedMember() : parseUnquotedMember();
    }

    private String parseQuotedMember() throws SpliceException {
        position++; // opening quote
        StringBuilder out = new StringBuilder();
        while (true) {
            if (position >= input.length()) {
                throw syntaxError("unterminated quoted element name");
            }
            char c = input.charAt(position);
            if (c == '\'') {
                if (position + 1 < input.length() && input.charAt(position + 1) == '\'') {
                    out.append('\'');
                    position += 2;
                    continue;
                }
                position++;
                break;
            }
            out.append(c);
            position++;
        }
        if (out.length() == 0) {
            throw syntaxError("quoted element name must not be empty");
        }
        return out.toString();
    }

    private String parseUnquotedMember() throws SpliceException {
        int start = position;
        while (position < input.length()) {
            char c = input.charAt(position);
            if (c == '.' || c == '[' || c == ']' || c == '\'' || Character.isWhitespace(c)) {
                break;
            }
            position++;
        }
        if (position == start) {
            throw syntaxError("expected an element name");
        }
        return input.substring(start, position);
    }

    private Integer parseIndex() throws SpliceException {
        position++; // '['
        int start = position;
        while (position < input.length() && Character.isDigit(input.charAt(position))) {
            position++;
        }
        if (position == start) {
            throw syntaxError("expected a zero-based occurrence index");
        }
        String digits = input.substring(start, position);
        if (digits.length() > 1 && digits.charAt(0) == '0') {
            throw syntaxError("occurrence index must not have a leading zero");
        }
        if (position >= input.length() || input.charAt(position) != ']') {
            throw syntaxError("expected ']' after the occurrence index");
        }
        position++; // ']'
        try {
            return Integer.valueOf(digits);
        } catch (NumberFormatException e) {
            throw syntaxError("occurrence index is out of range");
        }
    }

    private SpliceException syntaxError(String detail) {
        return new SpliceException(ErrorCode.PATH_SYNTAX, detail + " at character position " + position);
    }
}
