package io.jenkins.plugins.configsplice.engine.json;

import io.jenkins.plugins.configsplice.engine.ErrorCode;
import io.jenkins.plugins.configsplice.engine.SpliceException;
import java.util.ArrayList;
import java.util.List;

/**
 * Parser for the JSON property-path grammar of SRS section 6.2.
 *
 * <pre>
 * path     := member ( ('.' member) | index )*
 * member   := unquoted | quoted
 * unquoted := one or more characters other than '.', '[', ']', '\'' and whitespace
 * quoted   := '...'   with '' representing one literal single quote
 * index    := '[' digits ']'
 * </pre>
 *
 * <p>Backslash has no escaping meaning; {@code :}, {@code -} and {@code @} are ordinary characters
 * in an unquoted member. Diagnostics report the character position but never the document contents.
 */
public final class JsonPathParser {

    private final String input;
    private int position;

    private JsonPathParser(String input) {
        this.input = input;
    }

    public static JsonPath parse(String input) throws SpliceException {
        if (input == null || input.isEmpty()) {
            throw new SpliceException(ErrorCode.PATH_SYNTAX, "path must not be empty");
        }
        return new JsonPathParser(input).parsePath();
    }

    private JsonPath parsePath() throws SpliceException {
        List<JsonPath.Step> steps = new ArrayList<>();
        steps.add(new JsonPath.Property(parseMember()));

        while (position < input.length()) {
            char c = input.charAt(position);
            if (c == '.') {
                position++;
                steps.add(new JsonPath.Property(parseMember()));
            } else if (c == '[') {
                steps.add(parseIndex());
            } else {
                throw syntaxError("expected '.' or '[' ");
            }
        }
        return new JsonPath(steps);
    }

    private String parseMember() throws SpliceException {
        if (position >= input.length()) {
            throw syntaxError("expected a property name");
        }
        return input.charAt(position) == '\'' ? parseQuotedMember() : parseUnquotedMember();
    }

    private String parseQuotedMember() throws SpliceException {
        position++; // opening quote
        StringBuilder out = new StringBuilder();
        while (true) {
            if (position >= input.length()) {
                throw syntaxError("unterminated quoted property name");
            }
            char c = input.charAt(position);
            if (c == '\'') {
                // '' is one literal quote; a lone ' closes the member.
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
            throw syntaxError("quoted property name must not be empty");
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
            throw syntaxError("expected a property name");
        }
        return input.substring(start, position);
    }

    private JsonPath.Index parseIndex() throws SpliceException {
        position++; // '['
        int start = position;
        while (position < input.length() && Character.isDigit(input.charAt(position))) {
            position++;
        }
        if (position == start) {
            throw syntaxError("expected a zero-based array index");
        }
        String digits = input.substring(start, position);
        if (digits.length() > 1 && digits.charAt(0) == '0') {
            throw syntaxError("array index must not have a leading zero");
        }
        if (position >= input.length() || input.charAt(position) != ']') {
            throw syntaxError("expected ']' after the array index");
        }
        position++; // ']'
        try {
            return new JsonPath.Index(Integer.parseInt(digits));
        } catch (NumberFormatException e) {
            throw syntaxError("array index is out of range");
        }
    }

    private SpliceException syntaxError(String detail) {
        return new SpliceException(
                ErrorCode.PATH_SYNTAX, detail + " at character position " + position);
    }
}
