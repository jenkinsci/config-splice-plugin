package io.jenkins.plugins.configsplice.engine.xml;

import java.util.List;
import java.util.Objects;

/**
 * A generic XML path: a chain of element steps ending in a selector (SRS section 8.7).
 *
 * <pre>
 * configuration.'system.webServer'.security.requestFiltering.requestLimits.&#64;maxAllowedContentLength
 * configuration.location[0].'system.webServer'.handlers.add[1].&#64;name
 * configuration.appSettings.add[0].#text
 * </pre>
 *
 * <p>Note the quoting of {@code 'system.webServer'}: a dot always separates steps, so an element whose
 * name contains one has to be quoted or it would be read as two steps.</p>
 *
 * <p>The chain always starts at the document element, which is what distinguishes a generic path from
 * the {@link DotNetPath} shorthands: a path beginning {@code appSettings.} is shorthand, one beginning
 * {@code configuration.} is generic. That also means generic paths can reach content the shorthands
 * deliberately cannot, such as entries nested inside {@code <location>}.
 *
 * <p>Matching is entirely lexical. An element written {@code xdt:add} in the source is matched by the
 * path {@code xdt:add} and by nothing else; namespace URIs are never resolved and prefixes are never
 * treated as interchangeable (SRS section 8.7.2 rule 2).
 */
public record XmlPath(List<Element> elements, Selector selector) {

    /**
     * One element step.
     *
     * @param name  the qualified name exactly as written in the source, prefix included
     * @param index zero-based occurrence among same-name direct children, or {@code null} when the
     *              path did not specify one — in which case more than one candidate is an ambiguity
     *              error rather than a silent choice
     */
    public record Element(String name, Integer index) {
        public Element {
            Objects.requireNonNull(name, "name");
            if (index != null && index < 0) {
                throw new IllegalArgumentException("element index must not be negative: " + index);
            }
        }

        public boolean hasIndex() {
            return index != null;
        }
    }

    /** What the path finally targets: an attribute value or the element's text. */
    public sealed interface Selector permits Attribute, Text {}

    /** {@code .@name} — the value of that attribute on the resolved element. */
    public record Attribute(String name) implements Selector {
        public Attribute {
            Objects.requireNonNull(name, "name");
        }
    }

    /** {@code .#text} — the element's own text content. */
    public record Text() implements Selector {}

    public XmlPath {
        Objects.requireNonNull(elements, "elements");
        Objects.requireNonNull(selector, "selector");
        elements = List.copyOf(elements);
        if (elements.isEmpty()) {
            throw new IllegalArgumentException("a generic XML path needs at least the document element");
        }
    }

    /** Renders the path back into source form. Used in diagnostics; never contains a value. */
    public String canonical() {
        StringBuilder out = new StringBuilder();
        for (Element element : elements) {
            if (out.length() > 0) {
                out.append('.');
            }
            out.append(quoteIfNeeded(element.name()));
            if (element.hasIndex()) {
                out.append('[').append(element.index()).append(']');
            }
        }
        out.append('.');
        out.append(selector instanceof Attribute attribute ? "@" + quoteIfNeeded(attribute.name()) : "#text");
        return out.toString();
    }

    @Override
    public String toString() {
        return canonical();
    }

    /**
     * Quotes a name that would not survive being read back.
     *
     * <p>Covers the characters the grammar reserves, plus a leading {@code @} or {@code #}, which the
     * parser would otherwise read as the start of a selector. The point is that {@code canonical()}
     * always round-trips through {@link XmlPathParser}, so a diagnostic can be pasted straight back
     * into a path.
     */
    private static String quoteIfNeeded(String name) {
        boolean needsQuote = name.isEmpty() || name.charAt(0) == '@' || name.charAt(0) == '#';
        for (int i = 0; i < name.length() && !needsQuote; i++) {
            char c = name.charAt(i);
            needsQuote = c == '.' || c == '[' || c == ']' || c == '\'' || Character.isWhitespace(c);
        }
        return needsQuote ? "'" + name.replace("'", "''") + "'" : name;
    }
}
