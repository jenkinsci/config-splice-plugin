package io.jenkins.plugins.configsplice.engine.xml;

import io.jenkins.plugins.configsplice.engine.ErrorCode;
import io.jenkins.plugins.configsplice.engine.SpliceException;
import java.util.List;

/**
 * Parser for the .NET shorthand grammar of SRS sections 8.2 and 8.3.
 *
 * <pre>
 * app-settings-path       := 'appSettings.' shorthand-key [ '.&#64;value' ]
 * connection-strings-path := 'connectionStrings.' shorthand-name [ '.&#64;connectionString' | '.&#64;providerName' ]
 * shorthand-key           := quoted | raw-literal
 * </pre>
 *
 * <p>The remainder after the prefix is deliberately <em>not</em> split on dots. A .NET application
 * setting is routinely named {@code Logging.LogLevel.Default} or {@code BankApi:Key}, and treating
 * those as navigation would make the most common real key shapes unaddressable.
 *
 * <p>Ambiguity between a literal key that ends in {@code .@value} and an explicit terminal selector
 * is resolved greedily — at most one trailing selector is recognised — with quoting as the escape
 * hatch. See the worked cases in {@link #parse(String)}.
 */
public final class DotNetPathParser {

    private DotNetPathParser() {
    }

    /**
     * Parses an XML shorthand path.
     *
     * <p>Worked cases from SRS section 8.2:
     * <ul>
     *   <li>{@code appSettings.ApiUrl} -&gt; key {@code ApiUrl}, attribute {@code value}</li>
     *   <li>{@code appSettings.Logging.LogLevel.Default} -&gt; key {@code Logging.LogLevel.Default}</li>
     *   <li>{@code appSettings.@value} -&gt; key {@code @value} (no dot separator, so no selector)</li>
     *   <li>{@code appSettings.Foo.@value.@value} -&gt; key {@code Foo.@value}, attribute {@code value}</li>
     *   <li>{@code appSettings.'Literal.Key.Ending.@value'} -&gt; that whole quoted string as the key</li>
     * </ul>
     *
     * @throws SpliceException {@link ErrorCode#XML_PATH_UNSUPPORTED} if the path is not one of the
     *                         two supported shorthands, {@link ErrorCode#PATH_SYNTAX} if it is
     *                         malformed
     */
    public static DotNetPath parse(String input) throws SpliceException {
        if (input == null || input.isEmpty()) {
            throw new SpliceException(ErrorCode.PATH_SYNTAX, "path must not be empty");
        }
        for (DotNetPath.Collection collection : DotNetPath.Collection.values()) {
            if (input.startsWith(collection.pathPrefix())) {
                return parseRemainder(collection, input.substring(collection.pathPrefix().length()));
            }
        }
        throw new SpliceException(
                ErrorCode.XML_PATH_UNSUPPORTED,
                "Version 1.0 supports only paths beginning with 'appSettings.' or 'connectionStrings.'");
    }

    private static DotNetPath parseRemainder(DotNetPath.Collection collection, String remainder)
            throws SpliceException {

        if (remainder.isEmpty()) {
            throw new SpliceException(ErrorCode.PATH_SYNTAX, "shorthand path is missing a key or name");
        }
        if (remainder.indexOf('\r') >= 0 || remainder.indexOf('\n') >= 0) {
            throw new SpliceException(ErrorCode.PATH_SYNTAX, "shorthand key must not contain a line break");
        }
        return remainder.charAt(0) == '\''
                ? parseQuotedRemainder(collection, remainder)
                : parseRawRemainder(collection, remainder);
    }

    private static DotNetPath parseQuotedRemainder(DotNetPath.Collection collection, String remainder)
            throws SpliceException {

        StringBuilder key = new StringBuilder();
        int i = 1;
        boolean closed = false;
        while (i < remainder.length()) {
            char c = remainder.charAt(i);
            if (c == '\'') {
                if (i + 1 < remainder.length() && remainder.charAt(i + 1) == '\'') {
                    key.append('\'');
                    i += 2;
                    continue;
                }
                i++;
                closed = true;
                break;
            }
            key.append(c);
            i++;
        }
        if (!closed) {
            throw new SpliceException(ErrorCode.PATH_SYNTAX, "unterminated quoted shorthand key");
        }
        if (key.length() == 0) {
            throw new SpliceException(ErrorCode.PATH_SYNTAX, "quoted shorthand key must not be empty");
        }

        String tail = remainder.substring(i);
        if (tail.isEmpty()) {
            return new DotNetPath(collection, key.toString(), collection.defaultTargetAttribute());
        }
        String attribute = terminalSelectorAttribute(collection, tail);
        if (attribute == null) {
            // Anything after the closing quote must be exactly one recognised selector; a second
            // quoted member such as appSettings.'A'.'B' lands here and is rejected.
            throw new SpliceException(
                    ErrorCode.PATH_SYNTAX,
                    "unexpected text after the quoted shorthand key; expected a single terminal selector");
        }
        return new DotNetPath(collection, key.toString(), attribute);
    }

    private static DotNetPath parseRawRemainder(DotNetPath.Collection collection, String remainder)
            throws SpliceException {

        String key = remainder;
        String attribute = collection.defaultTargetAttribute();

        // Greedy: recognise at most one trailing selector, so any earlier occurrence stays in the key.
        for (String selector : selectorsFor(collection)) {
            if (remainder.length() > selector.length() && remainder.endsWith(selector)) {
                key = remainder.substring(0, remainder.length() - selector.length());
                attribute = selector.substring(2); // drop the leading ".@"
                break;
            }
        }

        if (key.isEmpty()) {
            throw new SpliceException(ErrorCode.PATH_SYNTAX, "shorthand path is missing a key or name");
        }
        if (!key.equals(key.strip())) {
            throw new SpliceException(
                    ErrorCode.PATH_SYNTAX, "shorthand key must not have leading or trailing whitespace");
        }
        if (key.indexOf('\'') >= 0) {
            throw new SpliceException(
                    ErrorCode.PATH_SYNTAX, "an unquoted shorthand key must not contain a single quote");
        }
        return new DotNetPath(collection, key, attribute);
    }

    private static String terminalSelectorAttribute(DotNetPath.Collection collection, String tail) {
        for (String selector : selectorsFor(collection)) {
            if (tail.equals(selector)) {
                return selector.substring(2);
            }
        }
        return null;
    }

    /** Recognised terminal selectors, longest first so a prefix never shadows a longer selector. */
    private static List<String> selectorsFor(DotNetPath.Collection collection) {
        return collection == DotNetPath.Collection.APP_SETTINGS
                ? List.of(".@value")
                : List.of(".@connectionString", ".@providerName");
    }
}
