package io.jenkins.plugins.configsplice.engine.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The JSON string literal codec.
 *
 * <p>{@link JsonStrings#encode} is what stops a replacement value from breaking out of the string it
 * is written into, and {@link JsonStrings#decode} is what lets the locator prove a candidate source
 * range really is the token the parser reported. Both are worth exercising directly.
 */
class JsonStringsTest {

    @Nested
    @DisplayName("encode")
    class Encode {

        @Test
        void wrapsInQuotesAndEscapesTheStructuralCharacters() {
            assertEquals("\"plain\"", JsonStrings.encode("plain"));
            assertEquals("\"say \\\"hi\\\"\"", JsonStrings.encode("say \"hi\""));
            assertEquals("\"C:\\\\path\"", JsonStrings.encode("C:\\path"));
        }

        @Test
        @DisplayName("the named control characters use their short escapes")
        void shortEscapesForTheNamedControlCharacters() {
            assertEquals("\"\\b\"", JsonStrings.encode("\b"));
            assertEquals("\"\\f\"", JsonStrings.encode("\f"));
            assertEquals("\"\\n\"", JsonStrings.encode("\n"));
            assertEquals("\"\\r\"", JsonStrings.encode("\r"));
            assertEquals("\"\\t\"", JsonStrings.encode("\t"));
        }

        @Test
        @DisplayName("other control characters use the \\u form")
        void remainingControlCharactersUseUnicodeEscapes() {
            assertEquals("\"\\u0000\"", JsonStrings.encode("\u0000"));
            assertEquals("\"\\u001f\"", JsonStrings.encode("\u001f"));
            // 0x20 is a printable space and must not be escaped.
            assertEquals("\" \"", JsonStrings.encode(" "));
        }

        @Test
        void nonAsciiIsEmittedVerbatimRatherThanEscaped() {
            // The document is UTF-8, so there is no reason to escape these and every reason not to:
            // escaping would change bytes the user did not ask us to change.
            assertEquals("\"São Paulo\"", JsonStrings.encode("São Paulo"));
        }

        @Test
        void emptyString() {
            assertEquals("\"\"", JsonStrings.encode(""));
        }

        @Test
        @DisplayName("a break-out attempt is neutralised")
        void breakOutAttemptIsNeutralised() {
            assertEquals("\"\\\",\\\"injected\\\":\\\"\"", JsonStrings.encode("\",\"injected\":\""));
        }
    }

    @Nested
    @DisplayName("decode")
    class Decode {

        @Test
        void plainBodyIsReturnedUnchanged() {
            assertEquals("plain", JsonStrings.decode("plain"));
            assertEquals("", JsonStrings.decode(""));
        }

        @Test
        @DisplayName("every recognised escape decodes")
        void everyRecognisedEscape() {
            assertEquals("\"", JsonStrings.decode("\\\""));
            assertEquals("\\", JsonStrings.decode("\\\\"));
            assertEquals("/", JsonStrings.decode("\\/"));
            assertEquals("\b", JsonStrings.decode("\\b"));
            assertEquals("\f", JsonStrings.decode("\\f"));
            assertEquals("\n", JsonStrings.decode("\\n"));
            assertEquals("\r", JsonStrings.decode("\\r"));
            assertEquals("\t", JsonStrings.decode("\\t"));
        }

        @Test
        void unicodeEscapes() {
            assertEquals("A", JsonStrings.decode("\\u0041"));
            assertEquals("é", JsonStrings.decode("\\u00e9"));
            assertEquals("mixed A here", JsonStrings.decode("mixed \\u0041 here"));
        }

        @ParameterizedTest(name = "{0} is rejected")
        @ValueSource(
                strings = {
                    "\\", // trailing backslash with nothing after it
                    "\\q", // not a recognised escape
                    "\\u12", // too short to be a code unit
                    "\\uZZZZ" // not hexadecimal
                })
        @DisplayName("a malformed body returns null rather than guessing")
        void malformedBodiesReturnNull(String body) {
            // Returning null is what lets the locator reject a candidate range instead of splicing
            // something it does not understand.
            assertNull(JsonStrings.decode(body));
        }
    }

    @ParameterizedTest(name = "round trip: {0}")
    @ValueSource(
            strings = {
                "plain",
                "say \"hi\"",
                "C:\\path\\to\\file",
                "line1\nline2\ttabbed",
                "\",\"injected\":\"",
                "São Paulo",
                ""
            })
    @DisplayName("encode then decode returns the original")
    void roundTrip(String original) {
        String encoded = JsonStrings.encode(original);
        String body = encoded.substring(1, encoded.length() - 1);
        assertEquals(original, JsonStrings.decode(body));
    }
}
