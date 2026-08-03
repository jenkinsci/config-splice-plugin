package io.jenkins.plugins.configsplice.engine.xml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.jenkins.plugins.configsplice.engine.ErrorCode;
import io.jenkins.plugins.configsplice.engine.ExactPreservationOracle;
import io.jenkins.plugins.configsplice.engine.SplicePlan;
import io.jenkins.plugins.configsplice.engine.SpliceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Generic XML element traversal (SRS section 8.4). */
class GenericXmlLocatorTest {

    /**
     * A web.config exercising the shapes generic paths exist for: settings outside the two .NET
     * collections, repeated same-name siblings, {@code <location>} nesting the shorthands cannot
     * reach, element text, a prefixed element, and mixed content.
     */
    private static final String WEB_CONFIG = String.join("\r\n",
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>",
            "<configuration>",
            "  <system.webServer>",
            "    <security>",
            "      <requestFiltering>",
            "        <requestLimits maxAllowedContentLength=\"30000000\" />",
            "      </requestFiltering>",
            "    </security>",
            "    <handlers>",
            "      <add name=\"first\" path=\"*.a\" />",
            "      <add name=\"second\" path=\"*.b\" />",
            "    </handlers>",
            "    <httpProtocol>",
            "      <customHeaders>",
            "        <add name=\"X-Frame\" value=\"DENY\" />",
            "      </customHeaders>",
            "    </httpProtocol>",
            "  </system.webServer>",
            "  <appSettings>",
            "    <add key=\"ApiUrl\" value=\"https://staging.example\" />",
            "  </appSettings>",
            "  <location path=\"Admin\">",
            "    <system.web>",
            "      <authorization>",
            "        <deny users=\"?\" />",
            "      </authorization>",
            "    </system.web>",
            "  </location>",
            "  <branding>",
            "    <title>Staging Portal</title>",
            "    <empty></empty>",
            "    <selfClosed/>",
            "    <mixed>text <b>and</b> markup</mixed>",
            "  </branding>",
            "  <xdt:marker xmlns:xdt=\"http://schemas.microsoft.com/XML-Document-Transform\" flag=\"on\" />",
            "</configuration>",
            "");

    private static GenericXmlLocator.Located locate(String path) throws SpliceException {
        return GenericXmlLocator.locate(WEB_CONFIG, XmlPathParser.parse(path));
    }

    private static String rawAt(GenericXmlLocator.Located located) {
        return WEB_CONFIG.substring(located.range().start(), located.range().end());
    }

    private static SpliceException reject(String path, ErrorCode expected) {
        SpliceException thrown = assertThrows(SpliceException.class, () -> locate(path), path);
        assertEquals(expected, thrown.code(), "for path " + path + ": " + thrown.getMessage());
        return thrown;
    }

    @Nested
    @DisplayName("reaches what the shorthands cannot")
    class Reach {

        @Test
        @DisplayName("a deeply nested attribute outside appSettings and connectionStrings")
        void deeplyNestedAttribute() throws Exception {
            GenericXmlLocator.Located located = locate(
                    "configuration.'system.webServer'.security.requestFiltering.requestLimits"
                            + ".@maxAllowedContentLength");
            assertEquals("30000000", rawAt(located));
            assertEquals(GenericXmlLocator.Located.Kind.ATTRIBUTE, located.kind());
        }

        @Test
        @DisplayName("an element name containing dots must be quoted")
        void dottedElementNameIsQuoted() throws Exception {
            // system.webServer is one element, not two steps; quoting is how the grammar says so.
            assertEquals(
                    "DENY",
                    rawAt(locate("configuration.'system.webServer'.httpProtocol.customHeaders.add.@value")));
        }

        @Test
        @DisplayName("content inside <location>, which the shorthand deliberately ignores")
        void locationScopedContent() throws Exception {
            assertEquals("?", rawAt(locate("configuration.location.'system.web'.authorization.deny.@users")));
        }

        @Test
        void elementText() throws Exception {
            GenericXmlLocator.Located located = locate("configuration.branding.title.#text");
            assertEquals("Staging Portal", rawAt(located));
            assertEquals(GenericXmlLocator.Located.Kind.TEXT, located.kind());
        }

        @Test
        void emptyElementTextIsAnEmptyRange() throws Exception {
            assertEquals(0, locate("configuration.branding.empty.#text").range().length());
        }

        @Test
        @DisplayName("a prefixed element matches only its exact lexical name")
        void prefixedElementMatchesLexically() throws Exception {
            assertEquals("on", rawAt(locate("configuration.'xdt:marker'.@flag")));
            reject("configuration.marker.@flag", ErrorCode.PATH_MISSING);
        }

        @Test
        @DisplayName("generic paths can also reach what the shorthand reaches")
        void overlapsWithShorthand() throws Exception {
            assertEquals(
                    "https://staging.example",
                    rawAt(locate("configuration.appSettings.add.@value")));
        }
    }

    @Nested
    @DisplayName("occurrence indexes")
    class Indexes {

        @Test
        void selectAmongSameNameSiblings() throws Exception {
            assertEquals("first", rawAt(locate("configuration.'system.webServer'.handlers.add[0].@name")));
            assertEquals("second", rawAt(locate("configuration.'system.webServer'.handlers.add[1].@name")));
        }

        @Test
        void anIndexPastTheEndIsAMissingPath() {
            reject("configuration.'system.webServer'.handlers.add[2].@name", ErrorCode.PATH_MISSING);
        }

        @Test
        @DisplayName("an index is accepted even when only one sibling matches")
        void indexOnASingleMatch() throws Exception {
            assertEquals("Staging Portal", rawAt(locate("configuration.branding.title[0].#text")));
        }
    }

    @Nested
    @DisplayName("refuses to guess")
    class Refusals {

        @Test
        @DisplayName("an unindexed step matching several siblings is ambiguous")
        void ambiguousStep() {
            SpliceException thrown =
                    reject("configuration.'system.webServer'.handlers.add.@name", ErrorCode.PATH_AMBIGUOUS);
            org.junit.jupiter.api.Assertions.assertTrue(
                    thrown.getMessage().contains("add[0]"),
                    "the message should suggest how to disambiguate: " + thrown.getMessage());
        }

        @Test
        @DisplayName("#text on an element containing markup is ambiguous, not a silent partial replace")
        void mixedContentText() {
            // Replacing this range would delete <b>and</b>, which the preservation guarantee forbids.
            reject("configuration.branding.mixed.#text", ErrorCode.PATH_AMBIGUOUS);
        }

        @Test
        void textOnASelfClosingElementIsMissing() {
            reject("configuration.branding.selfClosed.#text", ErrorCode.PATH_MISSING);
        }

        @Test
        void anAbsentAttributeIsMissingAndNeverCreated() {
            reject("configuration.branding.title.@nope", ErrorCode.PATH_MISSING);
        }

        @Test
        void anAbsentElementIsMissing() {
            reject("configuration.nosuch.@a", ErrorCode.PATH_MISSING);
            reject("configuration.branding.nosuch.#text", ErrorCode.PATH_MISSING);
        }

        @Test
        @DisplayName("a step only matches direct children, not descendants")
        void stepsAreNotDescendantSearches() {
            // requestLimits exists, but not as a direct child of configuration.
            reject("configuration.requestLimits.@maxAllowedContentLength", ErrorCode.PATH_MISSING);
        }

        @Test
        void externalEntityDeclarationsAreStillRejected() {
            String xxe = "<?xml version=\"1.0\"?>\n"
                    + "<!DOCTYPE foo [ <!ENTITY xxe SYSTEM \"file:///etc/passwd\"> ]>\n"
                    + "<configuration><a b=\"&xxe;\"/></configuration>";
            SpliceException thrown = assertThrows(
                    SpliceException.class,
                    () -> GenericXmlLocator.locate(xxe, XmlPathParser.parse("configuration.a.@b")));
            assertEquals(ErrorCode.PARSE_FAILED, thrown.code());
        }
    }

    @Nested
    @DisplayName("preserves everything it was not asked to change")
    class Preservation {

        @Test
        void splicingAnAttributeAndTextLeavesTheRestByteIdentical() throws Exception {
            GenericXmlLocator.Located limit = locate(
                    "configuration.'system.webServer'.security.requestFiltering.requestLimits"
                            + ".@maxAllowedContentLength");
            GenericXmlLocator.Located title = locate("configuration.branding.title.#text");

            SplicePlan plan = SplicePlan.builder()
                    .add(limit.range(), XmlAttributes.encode("60000000", limit.quote()), "limit")
                    .add(title.range(), XmlAttributes.encodeText("Production Portal"), "title")
                    .build();

            String output = plan.applyTo(WEB_CONFIG);
            ExactPreservationOracle.assertOnlyPlannedRangesChanged(WEB_CONFIG, plan, output);

            assertEquals(1, countOf(output, "<title>Production Portal</title>"));
            assertEquals(1, countOf(output, "maxAllowedContentLength=\"60000000\""));
            assertEquals(1, countOf(output, "<mixed>text <b>and</b> markup</mixed>"), "untouched");
            assertEquals(countOf(WEB_CONFIG, "\r\n"), countOf(output, "\r\n"));
        }

        @Test
        @DisplayName("text replacements are escaped for element content, not attribute content")
        void textEscapingUsesElementRules() throws Exception {
            GenericXmlLocator.Located title = locate("configuration.branding.title.#text");
            String hostile = "a < b & c > d \"quoted\"";

            SplicePlan plan = SplicePlan.builder()
                    .add(title.range(), XmlAttributes.encodeText(hostile), "title")
                    .build();
            String output = plan.applyTo(WEB_CONFIG);

            GenericXmlLocator.Located reread =
                    GenericXmlLocator.locate(output, XmlPathParser.parse("configuration.branding.title.#text"));
            assertEquals(hostile, reread.decodedValue(), "the value must round-trip intact");
            // Quotes carry no structural meaning in element content, so they stay readable.
            assertEquals(1, countOf(output, "\"quoted\""));
        }
    }

    private static int countOf(String haystack, String needle) {
        int count = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            count++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return count;
    }
}
