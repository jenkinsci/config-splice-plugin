package io.jenkins.plugins.configsplice.engine.json;

import io.jenkins.plugins.configsplice.engine.ErrorCode;
import io.jenkins.plugins.configsplice.engine.SourceRange;
import io.jenkins.plugins.configsplice.engine.SpliceException;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.json.JsonReadFeature;

/**
 * Locates the exact source range of a JSON scalar addressed by a {@link JsonPath}
 * (SRS sections 7.1 and 10.1).
 *
 * <h2>Why locate-then-splice, and why a hybrid</h2>
 *
 * <p>Reading the document into a tree and writing it back would destroy comments, indentation,
 * key order and newline style. So the parser is used only to <em>understand</em> the document; the
 * bytes are edited in place.
 *
 * <p>Jackson reports where a token started, but the position it reports after reading a token is
 * not reliably the token's last character: for unterminated-by-nature tokens such as numbers, the
 * scanner must look at the following delimiter before it knows the number ended. So the end offset
 * is computed lexically here, and the resulting candidate range is then <em>verified</em> by
 * decoding it and comparing against the value Jackson itself parsed. A range only becomes a splice
 * target once the two agree, which means a change in Jackson's offset conventions can make this
 * class fail loudly but cannot make it corrupt a file.
 */
public final class JsonScalarLocator {

    /** The four JSON scalar types (SRS section 3, "Scalar"). */
    public enum ScalarKind {
        STRING,
        NUMBER,
        BOOLEAN,
        NULL
    }

    /**
     * Diagnostic record of what the structural parser reported versus what was verified.
     * Consumed by the gate 1 evidence test and the resulting ADR; never surfaced to a build log.
     */
    public record OffsetEvidence(
            long parserReportedStart,
            int verifiedStart,
            int startAdjustment,
            long parserReportedEnd,
            int verifiedEnd) {

        public boolean startNeededAdjustment() {
            return startAdjustment != 0;
        }

        public boolean parserEndWasExact() {
            return parserReportedEnd == verifiedEnd;
        }
    }

    /** A located scalar: where it is, what it is, and how confidently we found it. */
    public record Located(SourceRange range, ScalarKind kind, OffsetEvidence evidence) {
    }

    /** How far either side of the reported offset a token start is searched for. */
    private static final int SEARCH_RADIUS = 8;

    private JsonScalarLocator() {
    }

    /**
     * Finds the scalar at {@code target}.
     *
     * <p>The whole document is always scanned, even after the target is found, because duplicate
     * property names anywhere in the document are a fatal ambiguity (SRS section 7.1 rule 8).
     *
     * @throws SpliceException {@link ErrorCode#PATH_MISSING} if the path does not resolve,
     *                         {@link ErrorCode#NON_SCALAR} if it resolves to an object or array,
     *                         {@link ErrorCode#DUPLICATE_JSON_KEY} on a duplicate property name,
     *                         {@link ErrorCode#PARSE_FAILED} on malformed input
     */
    public static Located locate(String text, JsonPath target) throws SpliceException {
        JsonFactory factory = JsonFactory.builder()
                // .NET configuration files commonly carry // and /* */ comments.
                .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
                .build();

        Located found = null;
        boolean resolvedToContainer = false;

        try (JsonParser parser = factory.createParser(new StringReader(text))) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new SpliceException(
                        ErrorCode.PARSE_FAILED, "document root must be a JSON object");
            }

            List<Frame> stack = new ArrayList<>();
            stack.add(Frame.object());

            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                switch (token) {
                    // Jackson 3 renamed FIELD_NAME to PROPERTY_NAME.
                    case PROPERTY_NAME -> {
                        Frame top = stack.get(stack.size() - 1);
                        String name = parser.currentName();
                        if (!top.keys.add(name)) {
                            throw new SpliceException(
                                    ErrorCode.DUPLICATE_JSON_KEY,
                                    "duplicate property name '" + name + "' in one JSON object");
                        }
                        top.field = name;
                    }
                    case START_OBJECT -> {
                        resolvedToContainer |= pathMatches(stack, target);
                        stack.add(Frame.object());
                    }
                    case START_ARRAY -> {
                        resolvedToContainer |= pathMatches(stack, target);
                        stack.add(Frame.array());
                    }
                    case END_OBJECT, END_ARRAY -> {
                        stack.remove(stack.size() - 1);
                        advanceArrayIndex(stack);
                    }
                    default -> {
                        if (found == null && pathMatches(stack, target)) {
                            found = locateScalarAt(text, parser, token, target);
                        }
                        advanceArrayIndex(stack);
                    }
                }
            }
        } catch (IOException | JacksonException e) {
            // Jackson messages embed source excerpts, which may contain a resolved secret.
            //
            // JacksonException is caught explicitly because Jackson 3 made its exceptions unchecked:
            // a malformed document raises StreamReadException, which the compiler will not remind
            // anyone to handle. Without this clause the raw parser message — quoting the offending
            // source text — propagates straight to the build log.
            throw new SpliceException(ErrorCode.PARSE_FAILED, "file is not valid JSON", e);
        }

        if (found != null) {
            return found;
        }
        if (resolvedToContainer) {
            throw new SpliceException(
                    ErrorCode.NON_SCALAR,
                    "path '" + target.canonical() + "' resolves to a JSON object or array, not a scalar");
        }
        throw new SpliceException(
                ErrorCode.PATH_MISSING, "path '" + target.canonical() + "' was not found");
    }

    private static Located locateScalarAt(String text, JsonParser parser, JsonToken token, JsonPath target)
            throws IOException, SpliceException {

        ScalarKind kind = kindOf(token);
        long reportedStart = parser.currentTokenLocation().getCharOffset();
        String parsedValue = parser.getText();

        int[] range = resolveVerifiedRange(text, (int) reportedStart, kind, parsedValue);
        if (range == null) {
            throw new SpliceException(
                    ErrorCode.PARSE_FAILED,
                    "could not verify the source range of the scalar at path '" + target.canonical() + "'");
        }
        long reportedEnd = parser.currentLocation().getCharOffset();
        OffsetEvidence evidence = new OffsetEvidence(
                reportedStart, range[0], range[0] - (int) reportedStart, reportedEnd, range[1]);
        return new Located(new SourceRange(range[0], range[1]), kind, evidence);
    }

    /**
     * Searches outward from the reported offset for a range that both looks like the right token and
     * decodes to the value the parser produced.
     *
     * <p>Verification is what makes the outward search safe. Without it, a reported offset landing
     * one character into {@code 8080} would still "look like" the start of a number and would splice
     * a wrong range.
     *
     * @return {@code {start, end}}, or {@code null} if no candidate verified
     */
    private static int[] resolveVerifiedRange(String text, int reported, ScalarKind kind, String parsedValue) {
        for (int candidate : candidateOffsets(reported)) {
            if (!looksLikeStart(text, candidate, kind)) {
                continue;
            }
            int end = lexicalEnd(text, candidate, kind);
            if (end < 0) {
                continue;
            }
            if (verifies(text, candidate, end, kind, parsedValue)) {
                return new int[] {candidate, end};
            }
        }
        return null;
    }

    /** The reported offset first, then alternating outward: 0, -1, +1, -2, +2, ... */
    private static int[] candidateOffsets(int reported) {
        int[] offsets = new int[1 + SEARCH_RADIUS * 2];
        offsets[0] = reported;
        int at = 1;
        for (int delta = 1; delta <= SEARCH_RADIUS; delta++) {
            offsets[at++] = reported - delta;
            offsets[at++] = reported + delta;
        }
        return offsets;
    }

    private static boolean looksLikeStart(String text, int at, ScalarKind kind) {
        if (at < 0 || at >= text.length()) {
            return false;
        }
        char c = text.charAt(at);
        return switch (kind) {
            case STRING -> c == '"';
            case NUMBER -> c == '-' || (c >= '0' && c <= '9');
            case BOOLEAN -> c == 't' || c == 'f';
            case NULL -> c == 'n';
        };
    }

    /** @return the exclusive end offset of the token starting at {@code start}, or -1 if malformed */
    private static int lexicalEnd(String text, int start, ScalarKind kind) {
        switch (kind) {
            case STRING -> {
                int i = start + 1;
                while (i < text.length()) {
                    char c = text.charAt(i);
                    if (c == '\\') {
                        i += 2;
                        continue;
                    }
                    if (c == '"') {
                        return i + 1;
                    }
                    i++;
                }
                return -1;
            }
            case NUMBER -> {
                int i = start;
                while (i < text.length() && isNumberChar(text.charAt(i))) {
                    i++;
                }
                return i > start ? i : -1;
            }
            case BOOLEAN -> {
                if (text.startsWith("true", start)) {
                    return start + 4;
                }
                if (text.startsWith("false", start)) {
                    return start + 5;
                }
                return -1;
            }
            case NULL -> {
                return text.startsWith("null", start) ? start + 4 : -1;
            }
            default -> {
                return -1;
            }
        }
    }

    private static boolean verifies(String text, int start, int end, ScalarKind kind, String parsedValue) {
        if (end > text.length()) {
            return false;
        }
        String raw = text.substring(start, end);
        return switch (kind) {
            case STRING -> {
                String decoded = JsonStrings.decode(raw.substring(1, raw.length() - 1));
                yield decoded != null && decoded.equals(parsedValue);
            }
            // For numbers Jackson's getText() is the number exactly as written in the source.
            case NUMBER -> raw.equals(parsedValue);
            case BOOLEAN, NULL -> raw.equals(parsedValue);
        };
    }

    private static boolean isNumberChar(char c) {
        return (c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E';
    }

    private static ScalarKind kindOf(JsonToken token) throws SpliceException {
        return switch (token) {
            case VALUE_STRING -> ScalarKind.STRING;
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> ScalarKind.NUMBER;
            case VALUE_TRUE, VALUE_FALSE -> ScalarKind.BOOLEAN;
            case VALUE_NULL -> ScalarKind.NULL;
            default -> throw new SpliceException(
                    ErrorCode.NON_SCALAR, "target is not a JSON scalar");
        };
    }

    private static boolean pathMatches(List<Frame> stack, JsonPath target) {
        List<JsonPath.Step> steps = target.steps();
        if (stack.size() != steps.size()) {
            return false;
        }
        for (int i = 0; i < stack.size(); i++) {
            Frame frame = stack.get(i);
            JsonPath.Step step = steps.get(i);
            if (frame.array) {
                if (!(step instanceof JsonPath.Index index) || index.position() != frame.index) {
                    return false;
                }
            } else {
                if (!(step instanceof JsonPath.Property property)
                        || frame.field == null
                        || !property.name().equals(frame.field)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void advanceArrayIndex(List<Frame> stack) {
        if (!stack.isEmpty()) {
            Frame top = stack.get(stack.size() - 1);
            if (top.array) {
                top.index++;
            }
        }
    }

    /** One level of container context while walking the token stream. */
    private static final class Frame {
        private final boolean array;
        private final Set<String> keys;
        private int index;
        private String field;

        private Frame(boolean array) {
            this.array = array;
            this.keys = array ? Set.of() : new HashSet<>();
        }

        static Frame object() {
            return new Frame(false);
        }

        static Frame array() {
            return new Frame(true);
        }
    }
}
