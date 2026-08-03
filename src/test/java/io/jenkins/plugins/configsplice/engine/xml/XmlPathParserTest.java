package io.jenkins.plugins.configsplice.engine.xml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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

/** The generic XML path grammar of SRS section 8.4. */
class XmlPathParserTest {

    private static List<String> namesOf(XmlPath path) {
        return path.elements().stream().map(XmlPath.Element::name).toList();
    }

    @Nested
    @DisplayName("accepts")
    class Accepts {

        @Test
        void aChainOfStepsEndingInAnAttribute() throws Exception {
            XmlPath path = XmlPathParser.parse("configuration.appSettings.add.@value");

            assertEquals(List.of("configuration", "appSettings", "add"), namesOf(path));
            assertEquals("value", assertInstanceOf(XmlPath.Attribute.class, path.selector()).name());
            assertNull(path.elements().get(2).index(), "an unindexed step must stay unindexed");
        }

        @Test
        void theTextSelector() throws Exception {
            XmlPath path = XmlPathParser.parse("configuration.branding.title.#text");

            assertEquals(List.of("configuration", "branding", "title"), namesOf(path));
            assertInstanceOf(XmlPath.Text.class, path.selector());
        }

        @Test
        void occurrenceIndexes() throws Exception {
            XmlPath path = XmlPathParser.parse("configuration.location[0].handlers.add[12].@name");

            assertEquals(0, path.elements().get(1).index());
            assertEquals(12, path.elements().get(3).index());
            assertNull(path.elements().get(2).index());
        }

        @Test
        @DisplayName("a quoted name containing the step separator")
        void quotedNameWithDots() throws Exception {
            XmlPath path = XmlPathParser.parse("configuration.'system.webServer'.handlers.@name");

            // The whole quoted run is one step; without quotes this would be three.
            assertEquals(List.of("configuration", "system.webServer", "handlers"), namesOf(path));
        }

        @Test
        @DisplayName("a doubled quote inside a quoted name means one literal quote")
        void quotedNameWithEscapedQuote() throws Exception {
            assertEquals(
                    List.of("configuration", "it's"),
                    namesOf(XmlPathParser.parse("configuration.'it''s'.@value")));
        }

        @Test
        void anIndexAfterAQuotedName() throws Exception {
            XmlPath path = XmlPathParser.parse("configuration.'system.webServer'[1].@name");
            assertEquals(1, path.elements().get(1).index());
        }

        @Test
        @DisplayName("a namespace prefix is part of the name, not a separator")
        void prefixedNames() throws Exception {
            assertEquals(
                    List.of("configuration", "xdt:marker"),
                    namesOf(XmlPathParser.parse("configuration.xdt:marker.@xdt:Transform")));
            assertEquals(
                    "xdt:Transform",
                    assertInstanceOf(
                                    XmlPath.Attribute.class,
                                    XmlPathParser.parse("configuration.xdt:marker.@xdt:Transform").selector())
                            .name());
        }

        @Test
        void aQuotedAttributeName() throws Exception {
            assertEquals(
                    "odd name",
                    assertInstanceOf(
                                    XmlPath.Attribute.class,
                                    XmlPathParser.parse("configuration.a.@'odd name'").selector())
                            .name());
        }
    }

    @Nested
    @DisplayName("rejects")
    class Rejects {

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {
            "", // empty
            "configuration", // no selector at all
            "configuration.appSettings", // still no selector
            "@value", // selector with no element
            "#text", // same
            "configuration.@", // '@' with no attribute name
            "configuration..add.@value", // empty step
            ".configuration.add.@value", // leading separator
            "configuration.add.", // trailing separator, no selector
            "configuration.add.@value.extra", // text after the selector
            "configuration.add.#text.extra", // text after the selector
            "configuration.add[].@value", // empty index
            "configuration.add[-1].@value", // negative index
            "configuration.add[1.@value", // unclosed index
            "configuration.add[01].@value", // leading zero
            "configuration.add[99999999999].@value", // beyond int range
            "configuration.'unterminated.@value", // unterminated quote
            "configuration.''.@value", // empty quoted name
            "configuration.a b.@value", // unquoted whitespace
            "configuration.add.#txt", // not a selector, and '#txt' cannot be a step name here
        })
        void malformedPaths(String path) {
            SpliceException thrown =
                    assertThrows(SpliceException.class, () -> XmlPathParser.parse(path), path);
            assertEquals(ErrorCode.PATH_SYNTAX, thrown.code(), path);
        }

        @Test
        void aNullPath() {
            SpliceException thrown = assertThrows(SpliceException.class, () -> XmlPathParser.parse(null));
            assertEquals(ErrorCode.PATH_SYNTAX, thrown.code());
        }

        @Test
        @DisplayName("the message locates the problem without echoing a value")
        void messagesArePositional() {
            SpliceException thrown =
                    assertThrows(SpliceException.class, () -> XmlPathParser.parse("configuration.add[].@value"));
            org.junit.jupiter.api.Assertions.assertTrue(
                    thrown.getMessage().contains("character position"), thrown.getMessage());
        }
    }

    @Nested
    @DisplayName("canonical form")
    class Canonical {

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {
            "configuration.appSettings.add.@value",
            "configuration.branding.title.#text",
            "configuration.location[0].handlers.add[12].@name",
            "configuration.'system.webServer'.handlers.@name",
            "configuration.'it''s'.@value",
            "configuration.xdt:marker.@xdt:Transform",
            "configuration.a.@'odd name'",
        })
        @DisplayName("round-trips: a rendered path parses back to the same path")
        void roundTrips(String input) throws Exception {
            XmlPath parsed = XmlPathParser.parse(input);
            String rendered = parsed.canonical();

            // Diagnostics print canonical(), so it has to be pasteable straight back into a path.
            assertEquals(parsed, XmlPathParser.parse(rendered), "re-parsing " + rendered);
            assertEquals(input, rendered, "already-canonical input should render unchanged");
        }

        @Test
        @DisplayName("quotes a name that would otherwise be read as a selector")
        void quotesSelectorLookalikeNames() throws Exception {
            XmlPath path = new XmlPath(
                    List.of(new XmlPath.Element("configuration", null), new XmlPath.Element("#text", null)),
                    new XmlPath.Attribute("@odd"));

            assertEquals("configuration.'#text'.@'@odd'", path.canonical());
            assertEquals(path, XmlPathParser.parse(path.canonical()));
        }

        @Test
        void toStringIsTheCanonicalFormAndCarriesNoValue() throws Exception {
            XmlPath path = XmlPathParser.parse("configuration.appSettings.add[0].@value");
            assertEquals(path.canonical(), path.toString());
        }
    }
}
