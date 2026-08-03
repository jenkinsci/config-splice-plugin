package io.jenkins.plugins.configsplice.engine.xml;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Escaping and unescaping of XML attribute values.
 *
 * <p>{@link XmlAttributes#encode} is the boundary that stops a replacement value from breaking out of
 * the attribute it is written into, so its branches are worth exercising individually rather than
 * only through the locator.
 */
class XmlAttributesTest {

    @Nested
    @DisplayName("encode")
    class Encode {

        @ParameterizedTest(name = "{0} is always escaped")
        @CsvSource({"&,&amp;", "<,&lt;", ">,&gt;"})
        void charactersNeverLegalInAnAttribute(String raw, String expected) {
            assertEquals(expected, XmlAttributes.encode(raw, '"'));
            assertEquals(expected, XmlAttributes.encode(raw, '\''));
        }

        @Test
        @DisplayName("only the delimiting quote is escaped")
        void quotingDependsOnTheDelimiter() {
            // Escaping both would be safe but needlessly unreadable; escaping the wrong one breaks out.
            assertEquals("&quot;", XmlAttributes.encode("\"", '"'));
            assertEquals("\"", XmlAttributes.encode("\"", '\''));
            assertEquals("&apos;", XmlAttributes.encode("'", '\''));
            assertEquals("'", XmlAttributes.encode("'", '"'));
        }

        @Test
        void ordinaryTextPassesThroughUnchanged() {
            assertEquals("https://example.com/a-b_c", XmlAttributes.encode("https://example.com/a-b_c", '"'));
            assertEquals("", XmlAttributes.encode("", '"'));
            assertEquals("São Paulo", XmlAttributes.encode("São Paulo", '"'));
        }

        @Test
        @DisplayName("a break-out attempt is neutralised in both quote contexts")
        void breakOutAttemptIsNeutralised() {
            String hostile = "\" onload=\"alert(1)";
            assertEquals("&quot; onload=&quot;alert(1)", XmlAttributes.encode(hostile, '"'));
            // Harmless inside single quotes: the double quotes cannot terminate the attribute there.
            assertEquals("\" onload=\"alert(1)", XmlAttributes.encode(hostile, '\''));
        }
    }

    @Nested
    @DisplayName("decode")
    class Decode {

        @Test
        void textWithoutAnAmpersandIsReturnedUnchanged() {
            assertEquals("plain value", XmlAttributes.decode("plain value"));
        }

        @Test
        @DisplayName("the five predefined entities decode")
        void predefinedEntities() {
            assertEquals("&", XmlAttributes.decode("&amp;"));
            assertEquals("<", XmlAttributes.decode("&lt;"));
            assertEquals(">", XmlAttributes.decode("&gt;"));
            assertEquals("\"", XmlAttributes.decode("&quot;"));
            assertEquals("'", XmlAttributes.decode("&apos;"));
        }

        @ParameterizedTest(name = "{0} decodes to A")
        @ValueSource(strings = {"&#65;", "&#x41;", "&#X41;"})
        void numericAndHexReferences(String raw) {
            assertEquals("A", XmlAttributes.decode(raw));
        }

        @Test
        void referencesAboveTheBasicPlaneDecodeToASurrogatePair() {
            assertEquals("😀", XmlAttributes.decode("&#x1F600;"));
        }

        @ParameterizedTest(name = "{0} is left verbatim")
        @ValueSource(
                strings = {
                    "&unknown;", // not a predefined entity and no DTD is ever loaded
                    "&#xZZ;", // unparseable hex
                    "&#99999999999;", // out of int range
                    "&#1114112;", // one past the maximum code point
                    "a & b", // a bare ampersand with no terminator
                    "trailing &"
                })
        void anythingUnrecognisedIsLeftAlone(String raw) {
            assertEquals(raw, XmlAttributes.decode(raw));
        }

        @Test
        void mixedContentDecodesOnlyTheRecognisedParts() {
            assertEquals("a & b < c", XmlAttributes.decode("a &amp; b &lt; c"));
            assertEquals("keep &nope; drop &", XmlAttributes.decode("keep &nope; drop &amp;"));
        }
    }

    @ParameterizedTest(name = "round trip: {0}")
    @ValueSource(
            strings = {
                "Server=db;User=sa;Password=p@ss&word",
                "a < b > c",
                "\" onload=\"alert(1)",
                "it's",
                "mixed \" and ' quotes",
                "&amp;",
                ""
            })
    @DisplayName("encode then decode returns the original, for both quote styles")
    void roundTrip(String original) {
        // The property that matters: whatever is written into the file reads back as what the user
        // asked for, regardless of which quote style the source document happened to use.
        assertEquals(original, XmlAttributes.decode(XmlAttributes.encode(original, '"')));
        assertEquals(original, XmlAttributes.decode(XmlAttributes.encode(original, '\'')));
    }
}
