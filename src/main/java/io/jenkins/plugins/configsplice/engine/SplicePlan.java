package io.jenkins.plugins.configsplice.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * An ordered set of non-overlapping replacements to apply to one document (SRS section 10.1).
 *
 * <p>Edits are applied in descending start-offset order so that each splice leaves the offsets of
 * every not-yet-applied edit valid. Overlap is rejected rather than resolved: two substitutions
 * competing for the same source range means the user's intent is ambiguous, and guessing would
 * silently discard one of them.
 */
public final class SplicePlan {

    /**
     * One replacement.
     *
     * @param range       the exact source range of the existing scalar token
     * @param replacement already-serialised replacement text for this context (JSON literal, or
     *                    XML-escaped attribute content)
     * @param label       a value-free identifier used only in diagnostics, typically the property path
     */
    public record Edit(SourceRange range, String replacement, String label) {
        public Edit {
            Objects.requireNonNull(range, "range");
            Objects.requireNonNull(replacement, "replacement");
            Objects.requireNonNull(label, "label");
        }
    }

    private final List<Edit> edits;

    private SplicePlan(List<Edit> edits) {
        this.edits = edits;
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<Edit> edits() {
        return Collections.unmodifiableList(edits);
    }

    public boolean isEmpty() {
        return edits.isEmpty();
    }

    /**
     * Applies every edit to {@code text}.
     *
     * <p>The caller is responsible for having built the plan against this exact text; ranges are
     * bounds-checked here so a mismatch fails loudly instead of producing a mangled file.
     */
    public String applyTo(String text) throws SpliceException {
        Objects.requireNonNull(text, "text");
        StringBuilder out = new StringBuilder(text);
        // Descending order: applying the last edit first keeps earlier offsets valid.
        for (int i = edits.size() - 1; i >= 0; i--) {
            Edit edit = edits.get(i);
            SourceRange range = edit.range();
            if (range.end() > text.length()) {
                throw new SpliceException(
                        ErrorCode.WRITE_FAILED,
                        "planned range for path '" + edit.label() + "' lies outside the document");
            }
            out.replace(range.start(), range.end(), edit.replacement());
        }
        return out.toString();
    }

    /** Collects edits and enforces the non-overlap invariant when the plan is built. */
    public static final class Builder {

        private final List<Edit> collected = new ArrayList<>();

        public Builder add(SourceRange range, String replacement, String label) {
            collected.add(new Edit(range, replacement, label));
            return this;
        }

        public SplicePlan build() throws SpliceException {
            List<Edit> sorted = new ArrayList<>(collected);
            sorted.sort((a, b) -> a.range().compareTo(b.range()));
            for (int i = 1; i < sorted.size(); i++) {
                Edit previous = sorted.get(i - 1);
                Edit current = sorted.get(i);
                if (previous.range().overlaps(current.range())) {
                    throw new SpliceException(
                            ErrorCode.WRITE_FAILED,
                            "paths '" + previous.label() + "' and '" + current.label()
                                    + "' resolve to overlapping source ranges");
                }
            }
            return new SplicePlan(sorted);
        }
    }
}
