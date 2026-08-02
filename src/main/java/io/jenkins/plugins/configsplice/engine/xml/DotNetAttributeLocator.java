package io.jenkins.plugins.configsplice.engine.xml;

import io.jenkins.plugins.configsplice.engine.ErrorCode;
import io.jenkins.plugins.configsplice.engine.SourceRange;
import io.jenkins.plugins.configsplice.engine.SpliceException;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Locates the source range of the attribute value addressed by a {@link DotNetPath}
 * (SRS sections 8.1 to 8.4).
 *
 * <p>Two passes, with a deliberate split of responsibilities:
 *
 * <ol>
 *   <li>StAX, configured to refuse DTDs and external entities, proves the document is well formed
 *       and safe. Nothing is located here; this pass exists so that malformed or hostile input is
 *       rejected before any hand-written code looks at the text.</li>
 *   <li>{@link XmlTagScanner} walks the proven-good text and computes exact ranges.</li>
 * </ol>
 */
public final class DotNetAttributeLocator {

    /** The document element every supported file must have (SRS section 8.4 rule 1). */
    private static final String ROOT_ELEMENT = "configuration";

    private static final String ADD_ELEMENT = "add";

    /**
     * A located attribute value.
     *
     * @param range    the range strictly between the quotes
     * @param quote    the quote character in use, which determines replacement escaping
     * @param rawValue the source text of the value, still escaped; decode before comparing to a literal
     */
    public record Located(SourceRange range, char quote, String rawValue) {

        /** The current value with entities resolved, for idempotency comparisons. */
        public String decodedValue() {
            return XmlAttributes.decode(rawValue);
        }
    }

    private DotNetAttributeLocator() {
    }

    /**
     * Finds the attribute addressed by {@code path}.
     *
     * @throws SpliceException {@link ErrorCode#PATH_MISSING} when no {@code <add>} matches or the
     *                         requested attribute is absent, {@link ErrorCode#PATH_AMBIGUOUS} when
     *                         more than one matches, {@link ErrorCode#PARSE_FAILED} on malformed or
     *                         unsafe input
     */
    public static Located locate(String text, DotNetPath path) throws SpliceException {
        requireWellFormedAndSafe(text);

        List<XmlTagScanner.Tag> tags = XmlTagScanner.scan(text);
        List<XmlTagScanner.Tag> matches = new ArrayList<>();
        Deque<String> openElements = new ArrayDeque<>();

        for (XmlTagScanner.Tag tag : tags) {
            if (tag.kind() == XmlTagScanner.Kind.CLOSE) {
                openElements.pollLast();
                continue;
            }
            if (ADD_ELEMENT.equals(tag.name()) && isDirectChildOfCollection(openElements, path)) {
                XmlTagScanner.Attribute identifier = tag.attribute(path.collection().identifyingAttribute());
                if (identifier != null && path.key().equals(decoded(text, identifier))) {
                    matches.add(tag);
                }
            }
            if (tag.kind() == XmlTagScanner.Kind.OPEN) {
                openElements.addLast(tag.name());
            }
        }

        if (matches.isEmpty()) {
            throw new SpliceException(
                    ErrorCode.PATH_MISSING,
                    "no <add> element matches " + path.equivalentXPath());
        }
        if (matches.size() > 1) {
            // Ambiguity is fatal by design: .NET itself takes the last entry, but silently picking
            // one would make the build's behaviour depend on file ordering the user cannot see.
            throw new SpliceException(
                    ErrorCode.PATH_AMBIGUOUS,
                    matches.size() + " <add> elements match " + path.equivalentXPath());
        }

        XmlTagScanner.Attribute target = matches.get(0).attribute(path.attribute());
        if (target == null) {
            // SRS section 8.3 rule 10: a missing attribute is a missing path, never a creation.
            throw new SpliceException(
                    ErrorCode.PATH_MISSING,
                    "the matched <add> element has no '" + path.attribute() + "' attribute");
        }
        return new Located(
                target.valueRange(),
                target.quote(),
                text.substring(target.valueRange().start(), target.valueRange().end()));
    }

    /**
     * Runs a strict StAX pass purely as a safety gate.
     *
     * <p>Fails closed: if the parser will not let us switch off DTD support or external entities, we
     * refuse to process the file rather than parse it with defaults.
     */
    static void requireWellFormedAndSafe(String text) throws SpliceException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        disable(factory, XMLInputFactory.SUPPORT_DTD);
        disable(factory, XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES);
        factory.setXMLResolver((publicId, systemId, baseUri, namespace) -> {
            throw new XMLStreamException("external resource resolution is disabled");
        });

        try {
            XMLStreamReader reader = factory.createXMLStreamReader(new StringReader(text));
            try {
                while (reader.hasNext()) {
                    reader.next();
                }
            } finally {
                reader.close();
            }
        } catch (XMLStreamException e) {
            // StAX messages quote source text, which may contain a resolved credential.
            throw new SpliceException(ErrorCode.PARSE_FAILED, "file is not valid or supported XML", e);
        }
    }

    private static void disable(XMLInputFactory factory, String property) throws SpliceException {
        try {
            factory.setProperty(property, Boolean.FALSE);
        } catch (IllegalArgumentException e) {
            throw new SpliceException(
                    ErrorCode.PARSE_FAILED,
                    "the available XML parser cannot disable '" + property + "'; refusing to parse",
                    e);
        }
    }

    private static boolean isDirectChildOfCollection(Deque<String> openElements, DotNetPath path) {
        if (openElements.size() != 2) {
            return false;
        }
        Iterator<String> outwardIn = openElements.iterator();
        return ROOT_ELEMENT.equals(outwardIn.next())
                && path.collection().elementName().equals(outwardIn.next());
    }

    private static String decoded(String text, XmlTagScanner.Attribute attribute) {
        SourceRange range = attribute.valueRange();
        return XmlAttributes.decode(text.substring(range.start(), range.end()));
    }
}
