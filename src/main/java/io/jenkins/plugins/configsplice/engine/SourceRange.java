package io.jenkins.plugins.configsplice.engine;

/**
 * A half-open {@code [start, end)} range of {@code char} offsets into a decoded document.
 *
 * <p>Offsets are {@code char} offsets rather than byte offsets deliberately. Both Jackson and StAX
 * report positions against the character stream they were handed, so keeping one unit throughout
 * removes an entire class of conversion bug. {@link SourceDocument} owns the single decode/encode
 * boundary where characters become bytes again.
 *
 * @param start inclusive start offset
 * @param end   exclusive end offset
 */
public record SourceRange(int start, int end) implements Comparable<SourceRange> {

    public SourceRange {
        if (start < 0) {
            throw new IllegalArgumentException("start must not be negative: " + start);
        }
        if (end < start) {
            throw new IllegalArgumentException("end (" + end + ") must not precede start (" + start + ")");
        }
    }

    public int length() {
        return end - start;
    }

    /** Returns true if this range shares at least one character position with {@code other}. */
    public boolean overlaps(SourceRange other) {
        return start < other.end && other.start < end;
    }

    @Override
    public int compareTo(SourceRange other) {
        int byStart = Integer.compare(start, other.start);
        return byStart != 0 ? byStart : Integer.compare(end, other.end);
    }
}
