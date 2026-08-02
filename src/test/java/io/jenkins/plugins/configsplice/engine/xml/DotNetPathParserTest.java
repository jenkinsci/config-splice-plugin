package io.jenkins.plugins.configsplice.engine.xml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.jenkins.plugins.configsplice.engine.ErrorCode;
import io.jenkins.plugins.configsplice.engine.SpliceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The selector-disambiguation cases from SRS section 8.2, which are easy to get subtly wrong. */
class DotNetPathParserTest {

    private static void assertParses(String path, String expectedEntryName, String expectedAttribute)
            throws SpliceException {
        DotNetPath parsed = DotNetPathParser.parse(path);
        assertEquals(expectedEntryName, parsed.entryName(), "entry name of " + path);
        assertEquals(expectedAttribute, parsed.attribute(), "attribute of " + path);
    }

    @Test
    void plainKeyTargetsTheValueAttribute() throws Exception {
        assertParses("appSettings.ApiUrl", "ApiUrl", "value");
    }

    @Test
    void colonsAndDotsAreLiteralInTheKey() throws Exception {
        assertParses("appSettings.BankApi:Key", "BankApi:Key", "value");
        assertParses("appSettings.Logging.LogLevel.Default", "Logging.LogLevel.Default", "value");
    }

    @Test
    @DisplayName("appSettings.@value has no separator, so @value is the key")
    void atValueWithoutSeparatorIsTheKey() throws Exception {
        assertParses("appSettings.@value", "@value", "value");
    }

    @Test
    @DisplayName("only one trailing selector is recognised, greedily")
    void greedyTerminalSelector() throws Exception {
        assertParses("appSettings.Foo.@value.@value", "Foo.@value", "value");
    }

    @Test
    void quotingReachesAKeyThatEndsInASelector() throws Exception {
        assertParses("appSettings.'Literal.Key.Ending.@value'", "Literal.Key.Ending.@value", "value");
    }

    @Test
    void quotedKeyMayCarryAnExplicitSelector() throws Exception {
        assertParses("appSettings.'Foo'.@value", "Foo", "value");
    }

    @Test
    void doubledQuoteIsOneLiteralQuote() throws Exception {
        assertParses("appSettings.'it''s'", "it's", "value");
    }

    @Test
    void connectionStringSelectors() throws Exception {
        assertParses("connectionStrings.Default", "Default", "connectionString");
        assertParses("connectionStrings.Default.@connectionString", "Default", "connectionString");
        assertParses("connectionStrings.Default.@providerName", "Default", "providerName");
        assertParses("connectionStrings.Reporting.Primary", "Reporting.Primary", "connectionString");
    }

    @Test
    void twoAdjacentQuotedMembersAreInvalid() {
        SpliceException thrown =
                assertThrows(SpliceException.class, () -> DotNetPathParser.parse("appSettings.'A'.'B'"));
        assertEquals(ErrorCode.PATH_SYNTAX, thrown.code());
    }

    @Test
    void aGenericXmlPathIsUnsupportedInVersionOne() {
        SpliceException thrown = assertThrows(
                SpliceException.class,
                () -> DotNetPathParser.parse("configuration.system.webServer.handlers.@name"));
        assertEquals(ErrorCode.XML_PATH_UNSUPPORTED, thrown.code());
    }

    @Test
    void surroundingWhitespaceInAnUnquotedKeyIsRejected() {
        SpliceException thrown =
                assertThrows(SpliceException.class, () -> DotNetPathParser.parse("appSettings. Padded "));
        assertEquals(ErrorCode.PATH_SYNTAX, thrown.code());
    }

    @Test
    void anEmptyRemainderIsRejected() {
        SpliceException thrown =
                assertThrows(SpliceException.class, () -> DotNetPathParser.parse("appSettings."));
        assertEquals(ErrorCode.PATH_SYNTAX, thrown.code());
    }
}
