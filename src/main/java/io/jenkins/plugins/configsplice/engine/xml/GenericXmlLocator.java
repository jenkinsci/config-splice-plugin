package io.jenkins.plugins.configsplice.engine.xml;

import io.jenkins.plugins.configsplice.engine.ErrorCode;
import io.jenkins.plugins.configsplice.engine.SourceRange;
import io.jenkins.plugins.configsplice.engine.SpliceException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Locates the source range addressed by a generic {@link XmlPath} (SRS section 8.4).
 *
 * <p>Same two-pass split as {@link DotNetAttributeLocator}: StAX proves the document well formed and
 * safe, then {@link XmlTagScanner} — which resolves no entities and loads nothing external — computes
 * exact ranges over the proven-good text.
 *
 * <p>The difference is navigation. Where the shorthands look for one known element shape, this walks
 * an arbitrary chain of element steps, and therefore has to decide what "the" element means when
 * several match. It never guesses: an unindexed step matching more than one sibling is an ambiguity
 * error, because silently taking the first would make the build depend on document ordering the user
 * cannot see.
 */
public final class GenericXmlLocator {

    /** A located target: an attribute value, or an element's text. */
    public record Located(SourceRange range, Kind kind, char quote, String rawValue) {

        public enum Kind {
            ATTRIBUTE,
            TEXT
        }

        /** The current value with entities resolved, for idempotency comparisons. */
        public String decodedValue() {
            return XmlAttributes.decode(rawValue);
        }
    }

    private GenericXmlLocator() {
    }

    /**
     * Finds the target addressed by {@code path}.
     *
     * @throws SpliceException {@link ErrorCode#PATH_MISSING} when a step, attribute or text target
     *                         does not exist, {@link ErrorCode#PATH_AMBIGUOUS} when an unindexed step
     *                         matches more than one sibling, {@link ErrorCode#PARSE_FAILED} on
     *                         malformed or unsafe input
     */
    public static Located locate(String text, XmlPath path) throws SpliceException {
        DotNetAttributeLocator.requireWellFormedAndSafe(text);

        List<Node> roots = buildTree(XmlTagScanner.scan(text));
        List<Node> candidates = roots;
        Node current = null;

        for (XmlPath.Element step : path.elements()) {
            current = select(candidates, step, path);
            candidates = current.children;
        }

        return path.selector() instanceof XmlPath.Attribute attribute
                ? locateAttribute(text, current, attribute, path)
                : locateText(text, current, path);
    }

    private static Node select(List<Node> siblings, XmlPath.Element step, XmlPath path)
            throws SpliceException {

        List<Node> matching = new ArrayList<>();
        for (Node sibling : siblings) {
            if (sibling.tag.name().equals(step.name())) {
                matching.add(sibling);
            }
        }
        if (matching.isEmpty()) {
            throw new SpliceException(
                    ErrorCode.PATH_MISSING,
                    "no element '" + step.name() + "' in path '" + path.canonical() + "'");
        }
        if (step.hasIndex()) {
            int index = step.index();
            if (index >= matching.size()) {
                throw new SpliceException(
                        ErrorCode.PATH_MISSING,
                        "element '" + step.name() + "[" + index + "]' does not exist; only "
                                + matching.size() + " sibling(s) match in path '" + path.canonical() + "'");
            }
            return matching.get(index);
        }
        if (matching.size() > 1) {
            // Ambiguity is fatal by design: picking the first would make behaviour depend on document
            // order. Adding an explicit [n] is how the user says which one they meant.
            throw new SpliceException(
                    ErrorCode.PATH_AMBIGUOUS,
                    matching.size() + " elements named '" + step.name() + "' match in path '"
                            + path.canonical() + "'; add an occurrence index such as '" + step.name()
                            + "[0]' to choose one");
        }
        return matching.get(0);
    }

    private static Located locateAttribute(String text, Node node, XmlPath.Attribute selector, XmlPath path)
            throws SpliceException {

        XmlTagScanner.Attribute attribute = node.tag.attribute(selector.name());
        if (attribute == null) {
            // SRS 8.4 rule 5: an absent attribute is a missing path, never a creation.
            throw new SpliceException(
                    ErrorCode.PATH_MISSING,
                    "element '" + node.tag.name() + "' has no '" + selector.name()
                            + "' attribute, for path '" + path.canonical() + "'");
        }
        SourceRange range = attribute.valueRange();
        return new Located(
                range,
                Located.Kind.ATTRIBUTE,
                attribute.quote(),
                text.substring(range.start(), range.end()));
    }

    /**
     * Computes an element's own text range.
     *
     * <p>SRS section 8.4 rule 6 admits {@code #text} only for one unambiguous scalar text range with
     * no mixed-content ambiguity, so anything other than plain characters between the tags is
     * refused. The check is for any {@code <} in the region, which covers child elements, comments,
     * CDATA and processing instructions in one test — replacing a range containing any of those would
     * delete markup the user never asked us to touch.
     */
    private static Located locateText(String text, Node node, XmlPath path) throws SpliceException {
        if (node.closeTag == null || node.tag.kind() == XmlTagScanner.Kind.SELF_CLOSING) {
            throw new SpliceException(
                    ErrorCode.PATH_MISSING,
                    "element '" + node.tag.name() + "' is self-closing and has no text, for path '"
                            + path.canonical() + "'");
        }
        int start = node.tag.end();
        int end = node.closeTag.start();
        String content = text.substring(start, end);
        if (content.indexOf('<') >= 0) {
            throw new SpliceException(
                    ErrorCode.PATH_AMBIGUOUS,
                    "element '" + node.tag.name() + "' contains markup as well as text, so '#text' is "
                            + "ambiguous for path '" + path.canonical() + "'");
        }
        return new Located(new SourceRange(start, end), Located.Kind.TEXT, '\0', content);
    }

    /** Builds an element tree from the flat tag list so steps can be resolved against direct children. */
    private static List<Node> buildTree(List<XmlTagScanner.Tag> tags) throws SpliceException {
        List<Node> roots = new ArrayList<>();
        Deque<Node> open = new ArrayDeque<>();

        for (XmlTagScanner.Tag tag : tags) {
            switch (tag.kind()) {
                case OPEN -> {
                    Node node = new Node(tag);
                    attach(roots, open, node);
                    open.push(node);
                }
                case SELF_CLOSING -> attach(roots, open, new Node(tag));
                case CLOSE -> {
                    if (open.isEmpty()) {
                        // StAX already proved the document well formed, so this cannot normally happen.
                        throw new SpliceException(
                                ErrorCode.PARSE_FAILED, "unbalanced end tag '" + tag.name() + "'");
                    }
                    open.pop().closeTag = tag;
                }
            }
        }
        return roots;
    }

    private static void attach(List<Node> roots, Deque<Node> open, Node node) {
        if (open.isEmpty()) {
            roots.add(node);
        } else {
            open.peek().children.add(node);
        }
    }

    /** One element and its direct children. */
    private static final class Node {

        private final XmlTagScanner.Tag tag;

        private final List<Node> children = new ArrayList<>();

        private XmlTagScanner.Tag closeTag;

        private Node(XmlTagScanner.Tag tag) {
            this.tag = tag;
            if (tag.kind() == XmlTagScanner.Kind.SELF_CLOSING) {
                this.closeTag = tag;
            }
        }
    }
}
