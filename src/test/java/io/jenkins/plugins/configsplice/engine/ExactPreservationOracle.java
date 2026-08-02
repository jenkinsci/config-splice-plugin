package io.jenkins.plugins.configsplice.engine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

/**
 * The primary source-preservation acceptance oracle (SRS section 10.4).
 *
 * <p>Deliberately does <em>not</em> work by calling {@link SplicePlan#applyTo(String)} and comparing
 * — that would only prove the splicer agrees with itself. Instead it walks the original and the
 * output in parallel, asserting that every region outside a planned range is character-identical and
 * that every planned range contains exactly the intended replacement.
 */
public final class ExactPreservationOracle {

    private ExactPreservationOracle() {
    }

    /** Asserts that {@code output} differs from {@code original} only inside the planned ranges. */
    public static void assertOnlyPlannedRangesChanged(String original, SplicePlan plan, String output) {
        List<SplicePlan.Edit> edits = plan.edits(); // ascending by range

        // Independent length invariant: the output may grow or shrink only by the planned deltas.
        int expectedLength = original.length();
        for (SplicePlan.Edit edit : edits) {
            expectedLength += edit.replacement().length() - edit.range().length();
        }
        assertEquals(expectedLength, output.length(), "output length must differ only by the planned deltas");

        int originalAt = 0;
        int outputAt = 0;

        for (SplicePlan.Edit edit : edits) {
            int gap = edit.range().start() - originalAt;
            assertEquals(
                    original.substring(originalAt, originalAt + gap),
                    output.substring(outputAt, outputAt + gap),
                    "text before '" + edit.label() + "' must be untouched");
            originalAt = edit.range().end();
            outputAt += gap;

            assertEquals(
                    edit.replacement(),
                    output.substring(outputAt, outputAt + edit.replacement().length()),
                    "spliced text for '" + edit.label() + "'");
            outputAt += edit.replacement().length();
        }

        assertEquals(
                original.substring(originalAt),
                output.substring(outputAt),
                "text after the final replacement must be untouched");
    }

    /**
     * Asserts the full byte-level round trip: decode, splice, re-encode, and confirm that only the
     * planned ranges moved and that BOM state survived.
     */
    public static void assertByteLevelPreservation(byte[] originalBytes, SplicePlan plan, String splicedText)
            throws SpliceException {

        SourceDocument document = SourceDocument.of(originalBytes);
        assertOnlyPlannedRangesChanged(document.text(), plan, splicedText);

        byte[] rendered = document.render(splicedText);
        EncodingSupport.Bom bom = EncodingSupport.detectBom(originalBytes);
        assertEquals(bom, EncodingSupport.detectBom(rendered), "BOM state must be preserved");

        if (plan.isEmpty()) {
            assertArrayEquals(originalBytes, rendered, "an empty plan must reproduce the input exactly");
        }
    }
}
