package io.jenkins.plugins.configsplice.engine.xml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.jenkins.plugins.configsplice.engine.ErrorCode;
import io.jenkins.plugins.configsplice.engine.SpliceException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The lexical tag scanner.
 *
 * <p>This class decides which bytes get replaced, and it is the reason markup inside a comment or a
 * CDATA section is never mistaken for a real element. Those skip paths are exactly the ones a
 * document-level test does not reach, so they are exercised here directly.
 */
class XmlTagScannerTest {

    private static List<XmlTagScanner.Tag> scan(String xml) throws SpliceException {
        return XmlTagScanner.scan(xml);
    }

    private static String valueOf(String xml, XmlTagScanner.Attribute attribute) {
        return xml.substring(attribute.valueRange().start(), attribute.valueRange().end());
    }

    @Nested
    @DisplayName("recognises tag shapes")
    class TagShapes {

        @Test
        void openCloseAndSelfClosing() throws Exception {
            List<XmlTagScanner.Tag> tags = scan("<a><b/></a>");

            assertEquals(3, tags.size());
            assertEquals(XmlTagScanner.Kind.OPEN, tags.get(0).kind());
            assertEquals("a", tags.get(0).name());
            assertEquals(XmlTagScanner.Kind.SELF_CLOSING, tags.get(1).kind());
            assertEquals("b", tags.get(1).name());
            assertEquals(XmlTagScanner.Kind.CLOSE, tags.get(2).kind());
        }

        @Test
        void qualifiedNamesKeepTheirPrefix() throws Exception {
            // Matching is lexical, so xdt:add must never be mistaken for add.
            assertEquals("xdt:add", scan("<xdt:add/>").get(0).name());
        }

        @Test
        void attributesInBothQuoteStyles() throws Exception {
            String xml = "<add key=\"double\" name='single' />";
            XmlTagScanner.Tag tag = scan(xml).get(0);

            assertEquals('"', tag.attribute("key").quote());
            assertEquals("double", valueOf(xml, tag.attribute("key")));
            assertEquals('\'', tag.attribute("name").quote());
            assertEquals("single", valueOf(xml, tag.attribute("name")));
        }

        @Test
        void whitespaceAroundTheEqualsSignIsTolerated() throws Exception {
            String xml = "<add key = \"spaced\" />";
            assertEquals("spaced", valueOf(xml, scan(xml).get(0).attribute("key")));
        }

        @Test
        void anEmptyAttributeValueHasAnEmptyRange() throws Exception {
            String xml = "<add key=\"\" />";
            XmlTagScanner.Attribute attribute = scan(xml).get(0).attribute("key");
            assertEquals(0, attribute.valueRange().length());
        }

        @Test
        void anAbsentAttributeIsReportedAsNull() throws Exception {
            assertNull(scan("<add key=\"a\"/>").get(0).attribute("value"));
            assertNotNull(scan("<add key=\"a\"/>").get(0).attribute("key"));
        }
    }

    @Nested
    @DisplayName("skips non-markup regions")
    class SkippedRegions {

        @Test
        @DisplayName("an element inside a comment is not markup")
        void comments() throws Exception {
            List<XmlTagScanner.Tag> tags = scan("<r><!-- <add key=\"ghost\"/> --><add key=\"real\"/></r>");

            assertEquals(3, tags.size(), "the commented-out add must not appear");
            assertEquals("add", tags.get(1).name());
            assertEquals("real", valueOf("<r><!-- <add key=\"ghost\"/> --><add key=\"real\"/></r>",
                    tags.get(1).attribute("key")));
        }

        @Test
        void cdataSections() throws Exception {
            List<XmlTagScanner.Tag> tags = scan("<r><![CDATA[ <add key=\"ghost\"/> ]]></r>");
            assertEquals(2, tags.size(), "only <r> and </r>");
        }

        @Test
        void processingInstructionsAndTheXmlDeclaration() throws Exception {
            List<XmlTagScanner.Tag> tags =
                    scan("<?xml version=\"1.0\"?><?php echo '<add/>'; ?><r/>");
            assertEquals(1, tags.size());
            assertEquals("r", tags.get(0).name());
        }

        @Test
        void doctypeIncludingAnInternalSubset() throws Exception {
            // The '>' inside the internal subset must not be taken as the end of the declaration.
            List<XmlTagScanner.Tag> tags =
                    scan("<!DOCTYPE r [ <!ELEMENT r (#PCDATA)> ]><r/>");
            assertEquals(1, tags.size());
            assertEquals("r", tags.get(0).name());
        }

        @Test
        void trailingTextAfterTheLastTagIsIgnored() throws Exception {
            assertEquals(2, scan("<r></r>trailing text").size());
        }
    }

    @Nested
    @DisplayName("fails on malformed input rather than guessing")
    class Malformed {

        @ParameterizedTest(name = "{0}")
        @ValueSource(
                strings = {
                    "<r><!-- never closed",
                    "<r><![CDATA[ never closed",
                    "<r><?pi never closed",
                    "<!DOCTYPE never closed",
                    "<r><add key=\"unterminated />",
                    "<r><add key= />",
                    "<r><add key=unquoted />",
                    "<r><add key",
                    "</>",
                    "<r></"
                })
        void malformedInputIsRejected(String xml) {
            SpliceException thrown = assertThrows(SpliceException.class, () -> scan(xml));
            assertEquals(ErrorCode.PARSE_FAILED, thrown.code());
        }

        @Test
        void aMalformedMessageNamesTheProblemWithoutQuotingContent() {
            SpliceException thrown = assertThrows(
                    SpliceException.class, () -> scan("<add key=\"s3cr3t-token\" "));
            org.junit.jupiter.api.Assertions.assertFalse(
                    thrown.getMessage().contains("s3cr3t-token"),
                    "scanner diagnostics must not echo document content");
        }
    }
}
