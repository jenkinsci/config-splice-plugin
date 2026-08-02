package io.jenkins.plugins.configsplice;

import io.jenkins.plugins.configsplice.engine.ErrorCode;
import io.jenkins.plugins.configsplice.engine.SpliceException;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * The string-valued enumerations of the Pipeline API (SRS sections 4.3 to 4.5).
 *
 * <p>Each is bound from the script as a lowercase string rather than as a Java enum. Jenkins'
 * {@code DescribableModel} converts an enum by {@code Enum.valueOf}, which would force the documented
 * {@code format: 'xml'} to be written {@code format: 'XML'}. Binding a {@code String} and parsing it
 * here keeps the API exactly as specified and lets an unknown value produce a helpful error instead
 * of a binding failure.
 */
final class Wire {

    private Wire() {
    }

    /** Effective file format for a target group. */
    enum Format {
        AUTO,
        JSON,
        XML;

        static Format parse(String raw) throws SpliceException {
            return lookup(Format.class, raw, "format");
        }
    }

    /** How the emitted scalar is typed (SRS section 7.2). */
    enum ValueType {
        AUTO,
        STRING,
        NUMBER,
        BOOLEAN,
        NULL;

        static ValueType parse(String raw) throws SpliceException {
            return lookup(ValueType.class, raw, "type");
        }

        /** SRS section 7.4: XML targets accept only these. */
        boolean validForXml() {
            return this == AUTO || this == STRING;
        }

        /** SRS section 7.3: a credential may only be emitted as a string. */
        boolean validForCredential() {
            return this == AUTO || this == STRING;
        }
    }

    /** Policy for a recoverable condition (SRS section 9.1). */
    enum Behavior {
        FAIL,
        WARN,
        IGNORE;

        static Behavior parse(String raw) throws SpliceException {
            return lookup(Behavior.class, raw, "behavior");
        }
    }

    private static <E extends Enum<E>> E lookup(Class<E> type, String raw, String what)
            throws SpliceException {
        if (raw == null || raw.isBlank()) {
            throw new SpliceException(ErrorCode.TYPE_INVALID, what + " must not be blank");
        }
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equalsIgnoreCase(raw.trim())) {
                return constant;
            }
        }
        String allowed = Arrays.stream(type.getEnumConstants())
                .map(c -> "'" + c.name().toLowerCase(Locale.ROOT) + "'")
                .collect(Collectors.joining(", "));
        throw new SpliceException(
                ErrorCode.TYPE_INVALID,
                "unsupported " + what + " '" + raw.trim() + "'; expected one of " + allowed);
    }
}
