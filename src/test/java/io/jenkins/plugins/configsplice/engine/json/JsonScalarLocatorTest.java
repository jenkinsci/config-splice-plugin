package io.jenkins.plugins.configsplice.engine.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.jenkins.plugins.configsplice.engine.ErrorCode;
import io.jenkins.plugins.configsplice.engine.ExactPreservationOracle;
import io.jenkins.plugins.configsplice.engine.SourceDocument;
import io.jenkins.plugins.configsplice.engine.SplicePlan;
import io.jenkins.plugins.configsplice.engine.SpliceException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Gate 1 evidence for JSON: can we locate a scalar's exact source range and splice it? */
class JsonScalarLocatorTest {

    /** A realistic appsettings.json: comments, CRLF, mixed scalar types, nesting and an array. */
    private static final String APPSETTINGS = String.join("\r\n",
            "{",
            "  // Logging configuration for the API host.",
            "  \"Logging\": {",
            "    \"LogLevel\": {",
            "      \"Default\": \"Information\",",
            "      \"Microsoft.AspNetCore\": \"Warning\"",
            "    }",
            "  },",
            "  /* Feature flags are toggled per environment. */",
            "  \"FeatureFlags\": {",
            "    \"Payments\": { \"Enabled\": false, \"Timeout\": 30 }",
            "  },",
            "  \"Serilog\": { \"MinimumLevel.Default\": \"Information\" },",
            "  \"Services\": [",
            "    { \"Url\": \"https://one.example\", \"Port\": 8080 },",
            "    { \"Url\": \"https://two.example\", \"Port\": 9090 }",
            "  ],",
            "  \"Retired\": null",
            "}",
            "");

    private static String locateAndSplice(String json, String path, String replacement) throws SpliceException {
        JsonPath target = JsonPathParser.parse(path);
        JsonScalarLocator.Located located = JsonScalarLocator.locate(json, target);
        SplicePlan plan = SplicePlan.builder()
                .add(located.range(), replacement, path)
                .build();
        String output = plan.applyTo(json);
        ExactPreservationOracle.assertOnlyPlannedRangesChanged(json, plan, output);
        return output;
    }

    @Nested
    @DisplayName("locates every scalar kind")
    class ScalarKinds {

        @Test
        void string() throws Exception {
            JsonScalarLocator.Located located = JsonScalarLocator.locate(
                    APPSETTINGS, JsonPathParser.parse("Logging.LogLevel.Default"));
            assertEquals(JsonScalarLocator.ScalarKind.STRING, located.kind());
            assertEquals(
                    "\"Information\"",
                    APPSETTINGS.substring(located.range().start(), located.range().end()),
                    "the range must cover the complete string literal including both quotes");
        }

        @Test
        void number() throws Exception {
            JsonScalarLocator.Located located = JsonScalarLocator.locate(
                    APPSETTINGS, JsonPathParser.parse("FeatureFlags.Payments.Timeout"));
            assertEquals(JsonScalarLocator.ScalarKind.NUMBER, located.kind());
            assertEquals("30", APPSETTINGS.substring(located.range().start(), located.range().end()));
        }

        @Test
        void booleanValue() throws Exception {
            JsonScalarLocator.Located located = JsonScalarLocator.locate(
                    APPSETTINGS, JsonPathParser.parse("FeatureFlags.Payments.Enabled"));
            assertEquals(JsonScalarLocator.ScalarKind.BOOLEAN, located.kind());
            assertEquals("false", APPSETTINGS.substring(located.range().start(), located.range().end()));
        }

        @Test
        void nullValue() throws Exception {
            JsonScalarLocator.Located located =
                    JsonScalarLocator.locate(APPSETTINGS, JsonPathParser.parse("Retired"));
            assertEquals(JsonScalarLocator.ScalarKind.NULL, located.kind());
            assertEquals("null", APPSETTINGS.substring(located.range().start(), located.range().end()));
        }
    }

    @Nested
    @DisplayName("resolves the documented path forms")
    class PathForms {

        @Test
        void nestedProperties() throws Exception {
            String out = locateAndSplice(APPSETTINGS, "Logging.LogLevel.Default", "\"Warning\"");

            assertEquals(1, countOf(out, "\"Default\": \"Warning\""), "the target entry now reads Warning");
            assertEquals(
                    1,
                    countOf(out, "\"Microsoft.AspNetCore\": \"Warning\""),
                    "the sibling entry that already read Warning must be untouched");
            assertEquals(
                    1,
                    countOf(out, "\"MinimumLevel.Default\": \"Information\""),
                    "the similarly named Serilog entry must be untouched");
        }

        @Test
        void quotedLiteralDottedKey() throws Exception {
            JsonScalarLocator.Located located = JsonScalarLocator.locate(
                    APPSETTINGS, JsonPathParser.parse("Serilog.'MinimumLevel.Default'"));
            assertEquals(
                    "\"Information\"", APPSETTINGS.substring(located.range().start(), located.range().end()));
        }

        @Test
        void dottedKeyIsNotConfusedWithNesting() throws Exception {
            // Microsoft.AspNetCore is a literal key nested under LogLevel, reached by quoting.
            JsonScalarLocator.Located located = JsonScalarLocator.locate(
                    APPSETTINGS, JsonPathParser.parse("Logging.LogLevel.'Microsoft.AspNetCore'"));
            assertEquals("\"Warning\"", APPSETTINGS.substring(located.range().start(), located.range().end()));
        }

        @Test
        void arrayIndex() throws Exception {
            JsonScalarLocator.Located first = JsonScalarLocator.locate(
                    APPSETTINGS, JsonPathParser.parse("Services[0].Port"));
            JsonScalarLocator.Located second = JsonScalarLocator.locate(
                    APPSETTINGS, JsonPathParser.parse("Services[1].Port"));
            assertEquals("8080", APPSETTINGS.substring(first.range().start(), first.range().end()));
            assertEquals("9090", APPSETTINGS.substring(second.range().start(), second.range().end()));
        }
    }

    @Nested
    @DisplayName("preserves everything it was not asked to change")
    class Preservation {

        @Test
        void commentsIndentationAndCrlfSurviveASplice() throws Exception {
            String out = locateAndSplice(APPSETTINGS, "FeatureFlags.Payments.Enabled", "true");

            assertEquals(
                    countOf(APPSETTINGS, "\r\n"), countOf(out, "\r\n"), "CRLF line endings must survive");
            assertEquals(1, countOf(out, "// Logging configuration for the API host."));
            assertEquals(1, countOf(out, "/* Feature flags are toggled per environment. */"));
            assertEquals(
                    APPSETTINGS.length() + "true".length() - "false".length(),
                    out.length(),
                    "only the target token's length may change");
        }

        @Test
        void utf8BomIsPreservedAcrossTheByteRoundTrip() throws Exception {
            byte[] withBom = withUtf8Bom(APPSETTINGS);
            SourceDocument document = SourceDocument.of(withBom);

            JsonScalarLocator.Located located = JsonScalarLocator.locate(
                    document.text(), JsonPathParser.parse("Logging.LogLevel.Default"));
            SplicePlan plan = SplicePlan.builder()
                    .add(located.range(), "\"Warning\"", "Logging.LogLevel.Default")
                    .build();

            ExactPreservationOracle.assertByteLevelPreservation(
                    withBom, plan, plan.applyTo(document.text()));
        }

        @Test
        void anEmptyPlanReproducesTheInputByteForByte() throws Exception {
            byte[] withBom = withUtf8Bom(APPSETTINGS);
            SplicePlan empty = SplicePlan.builder().build();
            ExactPreservationOracle.assertByteLevelPreservation(
                    withBom, empty, SourceDocument.of(withBom).text());
        }
    }

    @Nested
    @DisplayName("fails safely")
    class Failures {

        @Test
        void missingPath() {
            SpliceException thrown = assertThrows(
                    SpliceException.class,
                    () -> JsonScalarLocator.locate(APPSETTINGS, JsonPathParser.parse("Nope.Missing")));
            assertEquals(ErrorCode.PATH_MISSING, thrown.code());
        }

        @Test
        void objectTargetIsNotAScalar() {
            SpliceException thrown = assertThrows(
                    SpliceException.class,
                    () -> JsonScalarLocator.locate(APPSETTINGS, JsonPathParser.parse("Logging.LogLevel")));
            assertEquals(ErrorCode.NON_SCALAR, thrown.code());
        }

        @Test
        void arrayTargetIsNotAScalar() {
            SpliceException thrown = assertThrows(
                    SpliceException.class,
                    () -> JsonScalarLocator.locate(APPSETTINGS, JsonPathParser.parse("Services")));
            assertEquals(ErrorCode.NON_SCALAR, thrown.code());
        }

        @Test
        void duplicateKeyOnTheRequestedPath() {
            String json = "{ \"A\": { \"B\": \"one\", \"B\": \"two\" } }";
            SpliceException thrown = assertThrows(
                    SpliceException.class,
                    () -> JsonScalarLocator.locate(json, JsonPathParser.parse("A.B")));
            assertEquals(ErrorCode.DUPLICATE_JSON_KEY, thrown.code());
        }

        @Test
        void duplicateKeyUnrelatedToTheRequestedPathStillFails() {
            // SRS 7.1 rule 8: ambiguity anywhere in the document is fatal, because .NET's binder and
            // our locator would not necessarily agree on which entry wins.
            String json = "{ \"Wanted\": \"x\", \"Other\": { \"D\": 1, \"D\": 2 } }";
            SpliceException thrown = assertThrows(
                    SpliceException.class,
                    () -> JsonScalarLocator.locate(json, JsonPathParser.parse("Wanted")));
            assertEquals(ErrorCode.DUPLICATE_JSON_KEY, thrown.code());
        }

        @Test
        void malformedJsonDoesNotLeakSourceText() {
            String json = "{ \"Secret\": \"s3cr3t-token\", }}}";
            SpliceException thrown = assertThrows(
                    SpliceException.class,
                    () -> JsonScalarLocator.locate(json, JsonPathParser.parse("Secret")));
            assertEquals(ErrorCode.PARSE_FAILED, thrown.code());
            assertFalse(
                    thrown.getMessage().contains("s3cr3t-token"),
                    "parser messages must never reach the caller verbatim");
        }

        @Test
        void rootArrayIsOutOfScope() {
            SpliceException thrown = assertThrows(
                    SpliceException.class,
                    () -> JsonScalarLocator.locate("[1,2,3]", JsonPathParser.parse("A")));
            assertEquals(ErrorCode.PARSE_FAILED, thrown.code());
        }
    }

    private static byte[] withUtf8Bom(String text) {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[3 + body.length];
        out[0] = (byte) 0xEF;
        out[1] = (byte) 0xBB;
        out[2] = (byte) 0xBF;
        System.arraycopy(body, 0, out, 3, body.length);
        return out;
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
