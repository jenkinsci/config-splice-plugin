package io.jenkins.plugins.configsplice.engine.xml;

import java.util.Objects;

/**
 * A parsed .NET configuration shorthand path (SRS sections 8.2 and 8.3).
 *
 * <p>Version 1.0 supports exactly two collections. Both resolve to a single attribute on a single
 * {@code <add>} element that is a direct child of the top-level collection element:
 *
 * <pre>
 * appSettings.&lt;key&gt;                          -&gt; /configuration/appSettings/add[@key='...']/@value
 * connectionStrings.&lt;name&gt;                    -&gt; /configuration/connectionStrings/add[@name='...']/@connectionString
 * connectionStrings.&lt;name&gt;.&#64;providerName       -&gt; /configuration/connectionStrings/add[@name='...']/@providerName
 * </pre>
 *
 * @param collection which .NET collection the path addresses
 * @param entryName  the literal value of the identifying attribute to match, exactly and
 *                   case-sensitively. Named for what it holds rather than for one collection's
 *                   spelling of it: {@code appSettings} entries are identified by {@code key} and
 *                   {@code connectionStrings} entries by {@code name}, and this is either.
 * @param attribute  the attribute on the matched {@code <add>} whose value is replaced
 */
public record DotNetPath(Collection collection, String entryName, String attribute) {

    /** The two supported collections and the attribute each uses to identify an entry. */
    public enum Collection {
        APP_SETTINGS("appSettings", "key", "value"),
        CONNECTION_STRINGS("connectionStrings", "name", "connectionString");

        private final String elementName;
        private final String identifyingAttribute;
        private final String defaultTargetAttribute;

        Collection(String elementName, String identifyingAttribute, String defaultTargetAttribute) {
            this.elementName = elementName;
            this.identifyingAttribute = identifyingAttribute;
            this.defaultTargetAttribute = defaultTargetAttribute;
        }

        /** The unprefixed lexical element name, e.g. {@code appSettings}. */
        public String elementName() {
            return elementName;
        }

        /** {@code key} for appSettings, {@code name} for connectionStrings. */
        public String identifyingAttribute() {
            return identifyingAttribute;
        }

        /** The attribute targeted when the path carries no terminal selector. */
        public String defaultTargetAttribute() {
            return defaultTargetAttribute;
        }

        /** The path prefix that dispatches to this shorthand, including the trailing dot. */
        public String pathPrefix() {
            return elementName + ".";
        }
    }

    public DotNetPath {
        Objects.requireNonNull(collection, "collection");
        Objects.requireNonNull(entryName, "entryName");
        Objects.requireNonNull(attribute, "attribute");
    }

    /** The equivalent XPath, used in documentation and diagnostics. Contains no values. */
    public String equivalentXPath() {
        return "/configuration/" + collection.elementName() + "/add[@"
                + collection.identifyingAttribute() + "='" + entryName + "']/@" + attribute;
    }
}
