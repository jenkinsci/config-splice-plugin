package io.jenkins.plugins.configsplice.engine.xml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.jenkins.plugins.configsplice.engine.ErrorCode;
import io.jenkins.plugins.configsplice.engine.ExactPreservationOracle;
import io.jenkins.plugins.configsplice.engine.SplicePlan;
import io.jenkins.plugins.configsplice.engine.SpliceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Gate 1 evidence for XML: can we locate a .NET attribute value's exact range and splice it? */
class DotNetAttributeLocatorTest {

    /**
     * A web.config exercising the awkward shapes: a commented-out {@code <add>} with a colliding
     * key, single- and double-quoted values, an entity, {@code <clear/>}/{@code <remove/>}, a
     * prefixed element, and a {@code <location>}-scoped section that must stay invisible.
     */
    private static final String WEB_CONFIG = String.join("\r\n",
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>",
            "<configuration>",
            "  <!-- <add key=\"ApiUrl\" value=\"commented-out\" /> -->",
            "  <appSettings>",
            "    <clear />",
            "    <add key=\"ApiUrl\" value=\"https://staging.example\" />",
            "    <add key=\"BankApi:Key\" value=\"placeholder\" />",
            "    <add key=\"Logging.LogLevel.Default\" value='Information' />",
            "    <add key=\"Escaped\" value=\"a &amp; b\" />",
            "    <remove key=\"Legacy\" />",
            "    <xdt:add xmlns:xdt=\"http://schemas.microsoft.com/XML-Document-Transform\"",
            "             key=\"Prefixed\" value=\"ignored\" />",
            "  </appSettings>",
            "  <connectionStrings>",
            "    <add name=\"Default\" connectionString=\"Server=stage;\"",
            "         providerName=\"System.Data.SqlClient\" />",
            "  </connectionStrings>",
            "  <location path=\"Admin\">",
            "    <appSettings>",
            "      <add key=\"OnlyHere\" value=\"scoped\" />",
            "    </appSettings>",
            "  </location>",
            "</configuration>",
            "");

    private static DotNetAttributeLocator.Located locate(String path) throws SpliceException {
        return DotNetAttributeLocator.locate(WEB_CONFIG, DotNetPathParser.parse(path));
    }

    private static String rawAt(DotNetAttributeLocator.Located located) {
        return WEB_CONFIG.substring(located.range().start(), located.range().end());
    }

    @Nested
    @DisplayName("resolves the documented shorthand forms")
    class Shorthands {

        @Test
        void appSettingsKey() throws Exception {
            DotNetAttributeLocator.Located located = locate("appSettings.ApiUrl");
            assertEquals("https://staging.example", rawAt(located));
            assertEquals('"', located.quote());
        }

        @Test
        void appSettingsKeyContainingAColon() throws Exception {
            assertEquals("placeholder", rawAt(locate("appSettings.BankApi:Key")));
        }

        @Test
        void appSettingsKeyContainingDotsIsOneLiteralKey() throws Exception {
            // The dotted remainder must not be split into navigation steps.
            assertEquals("Information", rawAt(locate("appSettings.Logging.LogLevel.Default")));
        }

        @Test
        void explicitValueSelectorResolvesTheSameTarget() throws Exception {
            assertEquals(
                    locate("appSettings.ApiUrl").range(),
                    locate("appSettings.ApiUrl.@value").range());
        }

        @Test
        void singleQuotedAttributeIsReportedWithItsQuote() throws Exception {
            DotNetAttributeLocator.Located located = locate("appSettings.Logging.LogLevel.Default");
            assertEquals('\'', located.quote(), "replacement escaping depends on the source quote");
        }

        @Test
        void entitiesAreDecodedForComparisonButTheRawRangeIsReturned() throws Exception {
            DotNetAttributeLocator.Located located = locate("appSettings.Escaped");
            assertEquals("a &amp; b", rawAt(located), "the range covers the raw, still-escaped source");
            assertEquals("a & b", located.decodedValue());
        }

        @Test
        void connectionStringDefaultsToTheConnectionStringAttribute() throws Exception {
            assertEquals("Server=stage;", rawAt(locate("connectionStrings.Default")));
        }

        @Test
        void connectionStringProviderName() throws Exception {
            assertEquals("System.Data.SqlClient", rawAt(locate("connectionStrings.Default.@providerName")));
        }
    }

    @Nested
    @DisplayName("ignores what the SRS says it must ignore")
    class Exclusions {

        @Test
        void aCommentedOutAddWithACollidingKeyIsNotMarkup() throws Exception {
            // If comments were scanned as markup this would be reported as ambiguous instead.
            assertEquals("https://staging.example", rawAt(locate("appSettings.ApiUrl")));
        }

        @Test
        void removeElementsAreNotAddElements() {
            SpliceException thrown = assertThrows(SpliceException.class, () -> locate("appSettings.Legacy"));
            assertEquals(ErrorCode.PATH_MISSING, thrown.code());
        }

        @Test
        void locationScopedEntriesAreNotVisibleToTheShorthand() {
            SpliceException thrown = assertThrows(SpliceException.class, () -> locate("appSettings.OnlyHere"));
            assertEquals(ErrorCode.PATH_MISSING, thrown.code());
        }

        @Test
        void aPrefixedElementDoesNotMatchTheUnprefixedShorthand() {
            SpliceException thrown = assertThrows(SpliceException.class, () -> locate("appSettings.Prefixed"));
            assertEquals(ErrorCode.PATH_MISSING, thrown.code());
        }

        @Test
        void aMissingRequestedAttributeIsAMissingPathNotACreation() {
            String xml = "<configuration><connectionStrings>"
                    + "<add name=\"Bare\" connectionString=\"Server=x;\" />"
                    + "</connectionStrings></configuration>";
            SpliceException thrown = assertThrows(
                    SpliceException.class,
                    () -> DotNetAttributeLocator.locate(
                            xml, DotNetPathParser.parse("connectionStrings.Bare.@providerName")));
            assertEquals(ErrorCode.PATH_MISSING, thrown.code());
        }
    }

    @Nested
    @DisplayName("fails safely")
    class Failures {

        @Test
        void duplicateAddElementsAreAmbiguous() {
            String xml = "<configuration><appSettings>"
                    + "<add key=\"Dup\" value=\"one\" />"
                    + "<add key=\"Dup\" value=\"two\" />"
                    + "</appSettings></configuration>";
            SpliceException thrown = assertThrows(
                    SpliceException.class,
                    () -> DotNetAttributeLocator.locate(xml, DotNetPathParser.parse("appSettings.Dup")));
            assertEquals(ErrorCode.PATH_AMBIGUOUS, thrown.code());
        }

        @Test
        void externalEntityDeclarationsAreRejected() {
            String xxe = "<?xml version=\"1.0\"?>\n"
                    + "<!DOCTYPE foo [ <!ENTITY xxe SYSTEM \"file:///etc/passwd\"> ]>\n"
                    + "<configuration><appSettings><add key=\"A\" value=\"&xxe;\" /></appSettings></configuration>";
            SpliceException thrown = assertThrows(
                    SpliceException.class,
                    () -> DotNetAttributeLocator.locate(xxe, DotNetPathParser.parse("appSettings.A")));
            assertEquals(ErrorCode.PARSE_FAILED, thrown.code());
        }

        @Test
        void malformedXmlDoesNotLeakSourceText() {
            String xml = "<configuration><appSettings>"
                    + "<add key=\"A\" value=\"s3cr3t-token\" >"
                    + "</appSettings>";
            SpliceException thrown = assertThrows(
                    SpliceException.class,
                    () -> DotNetAttributeLocator.locate(xml, DotNetPathParser.parse("appSettings.A")));
            assertEquals(ErrorCode.PARSE_FAILED, thrown.code());
            assertFalse(
                    thrown.getMessage().contains("s3cr3t-token"),
                    "parser messages must never reach the caller verbatim");
        }

        @Test
        void anUnsupportedGenericPathIsRejectedBeforeAnyFileIsRead() {
            SpliceException thrown = assertThrows(
                    SpliceException.class,
                    () -> DotNetPathParser.parse("configuration.system.webServer.security.@enabled"));
            assertEquals(ErrorCode.XML_PATH_UNSUPPORTED, thrown.code());
        }
    }

    @Nested
    @DisplayName("preserves everything it was not asked to change")
    class Preservation {

        @Test
        void twoSplicesInOneDocumentLeaveEverythingElseByteIdentical() throws Exception {
            DotNetAttributeLocator.Located apiUrl = locate("appSettings.ApiUrl");
            DotNetAttributeLocator.Located provider = locate("connectionStrings.Default.@providerName");

            SplicePlan plan = SplicePlan.builder()
                    .add(
                            apiUrl.range(),
                            XmlAttributes.encode("https://production.example", apiUrl.quote()),
                            "appSettings.ApiUrl")
                    .add(
                            provider.range(),
                            XmlAttributes.encode("Microsoft.Data.SqlClient", provider.quote()),
                            "connectionStrings.Default.@providerName")
                    .build();

            String output = plan.applyTo(WEB_CONFIG);
            ExactPreservationOracle.assertOnlyPlannedRangesChanged(WEB_CONFIG, plan, output);

            assertEquals(1, countOf(output, "<!-- <add key=\"ApiUrl\" value=\"commented-out\" /> -->"));
            assertEquals(1, countOf(output, "value='Information'"), "single quotes must survive");
            assertEquals(1, countOf(output, "<clear />"));
            assertEquals(countOf(WEB_CONFIG, "\r\n"), countOf(output, "\r\n"));
            assertEquals(1, countOf(output, "<?xml version=\"1.0\" encoding=\"utf-8\"?>"));
        }

        @Test
        void aReplacementContainingTheDelimitingQuoteIsEscapedNotBrokenOut() throws Exception {
            DotNetAttributeLocator.Located located = locate("appSettings.ApiUrl");
            String hostile = "\" onload=\"alert(1)";

            SplicePlan plan = SplicePlan.builder()
                    .add(
                            located.range(),
                            XmlAttributes.encode(hostile, located.quote()),
                            "appSettings.ApiUrl")
                    .build();
            String output = plan.applyTo(WEB_CONFIG);

            // The document must still be well formed and the value must round-trip intact.
            DotNetAttributeLocator.Located reread = DotNetAttributeLocator.locate(
                    output, DotNetPathParser.parse("appSettings.ApiUrl"));
            assertEquals(hostile, reread.decodedValue());
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
