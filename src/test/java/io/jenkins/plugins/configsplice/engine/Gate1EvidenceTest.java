package io.jenkins.plugins.configsplice.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.configsplice.engine.json.JsonPath;
import io.jenkins.plugins.configsplice.engine.json.JsonPathParser;
import io.jenkins.plugins.configsplice.engine.json.JsonScalarLocator;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Decision gate 1 (SRS section 20.1): does a structural parser give us offsets precise enough to
 * splice, and if not, does the hybrid locator close the gap?
 *
 * <p>This test does two jobs. It <em>asserts</em> that every documented target resolves to a
 * verified range across the awkward contexts — comments, CRLF, BOM, nesting, arrays. And it
 * <em>records</em> what the parser actually reported versus what verification concluded, printing a
 * table for the architecture decision record. The recorded numbers are evidence, not requirements:
 * if a future Jackson changes its conventions the assertions still hold, and the table simply shows
 * different deltas.
 */
class Gate1EvidenceTest {

    private record Case(String description, String json, String path) {
    }

    private static final String CRLF_WITH_COMMENTS = String.join("\r\n",
            "{",
            "  // leading line comment",
            "  \"Str\": \"value\", /* trailing block comment */",
            "  \"Num\": 8080,",
            "  \"Neg\": -12.5e3,",
            "  \"Bool\": true,",
            "  \"Nil\": null,",
            "  \"Nested\": { \"Deep\": { \"Leaf\": \"here\" } },",
            "  \"Arr\": [ \"zero\", \"one\", \"two\" ],",
            "  \"Esc\": \"quote \\\" and backslash \\\\ and unicode \\u00e9\"",
            "}");

    private static final String LF_COMPACT =
            "{\"Str\":\"value\",\"Num\":8080,\"Bool\":false,\"Nil\":null,\"Arr\":[1,2,3]}";

    private static final List<Case> CASES = List.of(
            new Case("string, comments either side, CRLF", CRLF_WITH_COMMENTS, "Str"),
            new Case("integer, CRLF", CRLF_WITH_COMMENTS, "Num"),
            new Case("negative exponent number, CRLF", CRLF_WITH_COMMENTS, "Neg"),
            new Case("boolean true, CRLF", CRLF_WITH_COMMENTS, "Bool"),
            new Case("null, CRLF", CRLF_WITH_COMMENTS, "Nil"),
            new Case("deeply nested string, CRLF", CRLF_WITH_COMMENTS, "Nested.Deep.Leaf"),
            new Case("array element 0, CRLF", CRLF_WITH_COMMENTS, "Arr[0]"),
            new Case("array element 2, CRLF", CRLF_WITH_COMMENTS, "Arr[2]"),
            new Case("string with escapes, CRLF", CRLF_WITH_COMMENTS, "Esc"),
            new Case("string, compact LF", LF_COMPACT, "Str"),
            new Case("integer, compact LF", LF_COMPACT, "Num"),
            new Case("boolean false, compact LF", LF_COMPACT, "Bool"),
            new Case("null, compact LF", LF_COMPACT, "Nil"),
            new Case("array element 1, compact LF", LF_COMPACT, "Arr[1]"));

    @Test
    @DisplayName("gate 1: every documented target resolves to a verified, spliceable range")
    void everyTargetResolvesToAVerifiedRange() throws Exception {
        List<String> rows = new ArrayList<>();
        int startAdjustments = 0;
        int inexactParserEnds = 0;

        for (Case testCase : CASES) {
            JsonPath path = JsonPathParser.parse(testCase.path());
            JsonScalarLocator.Located located = JsonScalarLocator.locate(testCase.json(), path);
            JsonScalarLocator.OffsetEvidence evidence = located.evidence();

            // The verified range must be a self-consistent, complete token.
            String raw = testCase.json().substring(located.range().start(), located.range().end());
            assertTrue(located.range().length() > 0, "empty range for " + testCase.description());
            assertFalse(
                    Character.isWhitespace(raw.charAt(0)),
                    "range starts on whitespace for " + testCase.description());
            assertFalse(
                    Character.isWhitespace(raw.charAt(raw.length() - 1)),
                    "range ends on whitespace for " + testCase.description());

            // Splicing a sentinel must leave everything else identical.
            String sentinel = sentinelFor(located.kind());
            SplicePlan plan = SplicePlan.builder()
                    .add(located.range(), sentinel, testCase.path())
                    .build();
            String output = plan.applyTo(testCase.json());
            ExactPreservationOracle.assertOnlyPlannedRangesChanged(testCase.json(), plan, output);

            if (evidence.startNeededAdjustment()) {
                startAdjustments++;
            }
            if (!evidence.parserEndWasExact()) {
                inexactParserEnds++;
            }
            rows.add(String.format(
                    "  %-38s kind=%-7s start: reported=%3d verified=%3d delta=%+d | end: reported=%3d verified=%3d %s",
                    testCase.description(),
                    located.kind(),
                    evidence.parserReportedStart(),
                    evidence.verifiedStart(),
                    evidence.startAdjustment(),
                    evidence.parserReportedEnd(),
                    evidence.verifiedEnd(),
                    evidence.parserEndWasExact() ? "(exact)" : "(PARSER OVERSHOT)"));
        }

        System.out.println();
        System.out.println("=== Gate 1 evidence: JSON source-range location ===");
        rows.forEach(System.out::println);
        System.out.printf(
                "  --> %d/%d cases needed a start adjustment; %d/%d had an inexact parser end offset.%n",
                startAdjustments, CASES.size(), inexactParserEnds, CASES.size());
        System.out.println("  --> " + conclusion(startAdjustments, inexactParserEnds));
        System.out.println();

        assertEquals(CASES.size(), rows.size(), "every case must produce evidence");
    }

    @Test
    @DisplayName("gate 1: a decode/splice/encode round trip is byte-exact")
    void roundTripIsByteExact() throws Exception {
        byte[] bytes = CRLF_WITH_COMMENTS.getBytes(EncodingSupport.charset());
        SourceDocument document = SourceDocument.of(bytes);

        assertTrue(document.rendersIdentically(), "decode/encode must be lossless before any edit");

        JsonScalarLocator.Located located =
                JsonScalarLocator.locate(document.text(), JsonPathParser.parse("Nested.Deep.Leaf"));
        SplicePlan plan = SplicePlan.builder()
                .add(located.range(), "\"there\"", "Nested.Deep.Leaf")
                .build();

        ExactPreservationOracle.assertByteLevelPreservation(
                bytes, plan, plan.applyTo(document.text()));
    }

    @Test
    @DisplayName("gate 1: offsets stay exact past the parser's internal buffer boundary")
    void offsetsRemainExactInALargeDocument() throws Exception {
        // Jackson reads in chunks (8 KB by default). If reported offsets were ever chunk-relative
        // rather than absolute, a target early in the file would look fine and a target late in a
        // large file would splice into the wrong place. This is the case that would expose it.
        StringBuilder json = new StringBuilder("{\n");
        for (int i = 0; i < 4000; i++) {
            json.append("  \"filler").append(i).append("\": \"padding value ").append(i).append("\",\n");
        }
        json.append("  \"Target\": \"find me\"\n}");
        String text = json.toString();
        // Jackson's default read buffer is 8000 chars; this is more than ten times that.
        assertTrue(text.length() > 100_000, "fixture must comfortably exceed the parser buffer");

        JsonScalarLocator.Located located =
                JsonScalarLocator.locate(text, JsonPathParser.parse("Target"));

        assertEquals(
                "\"find me\"",
                text.substring(located.range().start(), located.range().end()),
                "a target beyond the buffer boundary must still resolve to its own bytes");
        assertEquals(
                0, located.evidence().startAdjustment(), "start offset must remain absolute, not chunk-relative");
        assertTrue(located.evidence().parserEndWasExact(), "end offset must remain absolute");

        SplicePlan plan = SplicePlan.builder().add(located.range(), "\"found\"", "Target").build();
        ExactPreservationOracle.assertOnlyPlannedRangesChanged(text, plan, plan.applyTo(text));
    }

    /** Derives the gate's conclusion from the measurements rather than asserting it in advance. */
    private static String conclusion(int startAdjustments, int inexactParserEnds) {
        if (startAdjustments == 0 && inexactParserEnds == 0) {
            return "Conclusion: parser-reported start AND end offsets were exact for every case. "
                    + "Locate-then-splice is viable on the parser alone; the lexical scan and value "
                    + "verification are retained as a guard, not a workaround.";
        }
        if (startAdjustments == 0) {
            return "Conclusion: reported starts are exact but reported ends are not. "
                    + "The lexical end scan is load-bearing and must stay.";
        }
        return "Conclusion: reported offsets required adjustment. The hybrid locator is load-bearing "
                + "and its verification step is the only thing making the outward search safe.";
    }

    private static String sentinelFor(JsonScalarLocator.ScalarKind kind) {
        return switch (kind) {
            case STRING -> "\"SPLICED\"";
            case NUMBER -> "4242";
            case BOOLEAN -> "true";
            case NULL -> "null";
        };
    }
}
