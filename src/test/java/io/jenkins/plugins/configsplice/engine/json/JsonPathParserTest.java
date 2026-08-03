package io.jenkins.plugins.configsplice.engine.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.configsplice.engine.ErrorCode;
import io.jenkins.plugins.configsplice.engine.SpliceException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The JSON property-path grammar of SRS section 6.2.
 *
 * <p>The parser's error branches matter as much as its accepting ones: a path that is silently
 * mis-parsed targets the wrong property, which is a worse outcome than a rejected build.
 */
class JsonPathParserTest {

    private static List<JsonPath.Step> stepsOf(String path) throws SpliceException {
        return JsonPathParser.parse(path).steps();
    }

    private static JsonPath.Property property(String name) {
        return new JsonPath.Property(name);
    }

    private static JsonPath.Index index(int position) {
        return new JsonPath.Index(position);
    }

    @Nested
    @DisplayName("accepts the documented forms")
    class Accepted {

        @Test
        void singleAndNestedMembers() throws Exception {
            assertEquals(List.of(property("Port")), stepsOf("Port"));
            assertEquals(
                    List.of(property("Logging"), property("LogLevel"), property("Default")),
                    stepsOf("Logging.LogLevel.Default"));
        }

        @Test
        @DisplayName("a quoted member may contain dots, brackets and spaces")
        void quotedMembers() throws Exception {
            assertEquals(
                    List.of(property("Serilog"), property("MinimumLevel.Default")),
                    stepsOf("Serilog.'MinimumLevel.Default'"));
            assertEquals(List.of(property("key with spaces")), stepsOf("'key with spaces'"));
            assertEquals(List.of(property("has[brackets]")), stepsOf("'has[brackets]'"));
        }

        @Test
        @DisplayName("'' inside a quoted member is one literal quote")
        void doubledQuoteEscape() throws Exception {
            assertEquals(List.of(property("it's")), stepsOf("'it''s'"));
            assertEquals(List.of(property("'")), stepsOf("''''"));
        }

        @Test
        void arrayIndexes() throws Exception {
            assertEquals(List.of(property("Services"), index(0), property("Url")), stepsOf("Services[0].Url"));
            assertEquals(List.of(property("A"), index(0), index(1)), stepsOf("A[0][1]"));
            assertEquals(List.of(property("A"), index(10)), stepsOf("A[10]"));
        }

        @Test
        @DisplayName("only . [ ] ' and whitespace are special; everything else is an ordinary character")
        void charactersThatAreNotSeparators() throws Exception {
            assertEquals(List.of(property("Bank:Key")), stepsOf("Bank:Key"));
            assertEquals(List.of(property("some-key")), stepsOf("some-key"));
            assertEquals(List.of(property("@odata")), stepsOf("@odata"));
            // Nothing else is reserved, however punctuation-like it looks.
            assertEquals(List.of(property("A)B")), stepsOf("A)B"));
            assertEquals(List.of(property("a/b?c=d")), stepsOf("a/b?c=d"));
        }
    }

    @Nested
    @DisplayName("rejects malformed paths")
    class Rejected {

        private SpliceException reject(String path) {
            SpliceException thrown = assertThrows(
                    SpliceException.class, () -> JsonPathParser.parse(path), "should reject: " + path);
            assertEquals(ErrorCode.PATH_SYNTAX, thrown.code());
            return thrown;
        }

        @Test
        void emptyInput() {
            reject("");
            reject(null);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"A..B", "A.", ".A", "A.'B'.", "A. B"})
        @DisplayName("a missing member around a separator")
        void missingMember(String path) {
            reject(path);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"'unterminated", "A.'no closing quote", "'it''s"})
        void unterminatedQuotedMember(String path) {
            reject(path);
        }

        @Test
        void emptyQuotedMember() {
            reject("''");
            reject("A.''");
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"A[]", "A[x]", "A[-1]", "A[0", "A[0)"})
        void malformedIndex(String path) {
            reject(path);
        }

        @Test
        @DisplayName("a leading zero is rejected rather than silently accepted")
        void leadingZeroIndex() {
            // [01] and [1] would address the same element; allowing both invites a path that looks
            // deliberate but is a typo.
            reject("A[01]");
            reject("A[00]");
        }

        @Test
        void indexOutOfIntRange() {
            reject("A[99999999999999999999]");
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"A]B", "A B"})
        @DisplayName("a member terminated by something that cannot continue the path")
        void unexpectedSeparator(String path) {
            reject(path);
        }

        @Test
        void diagnosticsReportAPositionButNeverDocumentContent() {
            SpliceException thrown = reject("A[x]");
            assertTrue(
                    thrown.getMessage().contains("character position"),
                    "the message should locate the problem: " + thrown.getMessage());
        }
    }

    @ParameterizedTest(name = "round trip: {0}")
    @ValueSource(
            strings = {
                "Port",
                "Logging.LogLevel.Default",
                "Serilog.'MinimumLevel.Default'",
                "'key with spaces'",
                "'it''s'",
                "Services[0].Url",
                "A[0][1]",
                "Bank:Key"
            })
    @DisplayName("canonical form re-parses to the same path")
    void canonicalFormRoundTrips(String path) throws Exception {
        // The property that makes canonical() usable for duplicate-path detection: two paths are the
        // same path exactly when their canonical forms agree.
        JsonPath parsed = JsonPathParser.parse(path);
        assertEquals(parsed, JsonPathParser.parse(parsed.canonical()));
    }
}
