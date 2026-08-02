package io.jenkins.plugins.configsplice.engine.json;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * A parsed JSON property path (SRS section 6.2).
 *
 * <p>Paths are compared structurally, so {@code Serilog.'MinimumLevel.Default'} and a path built
 * programmatically from the same two steps are equal. That is what makes duplicate-path detection
 * (SRS section 4.6) work on canonical form rather than on the raw string the user typed.
 */
public record JsonPath(List<Step> steps) {

    /** One navigation step: either an object property or a zero-based array index. */
    public sealed interface Step permits Property, Index {
    }

    /** Selects an object property by exact, case-sensitive name. */
    public record Property(String name) implements Step {
        public Property {
            Objects.requireNonNull(name, "name");
        }
    }

    /** Selects a zero-based array element. */
    public record Index(int position) implements Step {
        public Index {
            if (position < 0) {
                throw new IllegalArgumentException("array index must not be negative: " + position);
            }
        }
    }

    public JsonPath {
        Objects.requireNonNull(steps, "steps");
        steps = List.copyOf(steps);
    }

    /**
     * Renders the path back into canonical source form, quoting members only where the grammar
     * requires it. Used in diagnostics; never contains a value.
     */
    public String canonical() {
        StringBuilder out = new StringBuilder();
        for (Step step : steps) {
            if (step instanceof Index index) {
                out.append('[').append(index.position()).append(']');
            } else {
                Property property = (Property) step;
                if (out.length() > 0) {
                    out.append('.');
                }
                out.append(quoteIfNeeded(property.name()));
            }
        }
        return out.toString();
    }

    private static String quoteIfNeeded(String name) {
        boolean needsQuote = name.isEmpty();
        for (int i = 0; i < name.length() && !needsQuote; i++) {
            char c = name.charAt(i);
            needsQuote = c == '.' || c == '[' || c == ']' || c == '\'' || Character.isWhitespace(c);
        }
        if (!needsQuote) {
            return name;
        }
        return "'" + name.replace("'", "''") + "'";
    }

    @Override
    public String toString() {
        return canonical();
    }

    /** Debug helper listing each step in order; used by test failure messages. */
    public String describeSteps() {
        return steps.stream()
                .map(step -> step instanceof Index index
                        ? "[" + index.position() + "]"
                        : "<" + ((Property) step).name() + ">")
                .collect(Collectors.joining(" "));
    }
}
