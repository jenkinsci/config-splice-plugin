package io.jenkins.plugins.configsplice.engine;

import java.util.Objects;

/**
 * The only exception type this engine throws across its public boundary.
 *
 * <p>Third-party parser exceptions are never propagated: Jackson and StAX both embed source
 * excerpts in their messages, and a source excerpt of a file mid-substitution can contain a
 * resolved credential. Callers catch this type and are guaranteed a value-free message
 * (SRS section 12.5).
 *
 * <p>A cause may be attached for controller-side diagnostics, but callers must never print a cause
 * chain to a build log.
 */
public class SpliceException extends Exception {

    private static final long serialVersionUID = 1L;

    private final ErrorCode code;

    public SpliceException(ErrorCode code, String valueFreeMessage) {
        this(code, valueFreeMessage, null);
    }

    public SpliceException(ErrorCode code, String valueFreeMessage, Throwable cause) {
        super(Objects.requireNonNull(code, "code").wireName() + ": "
                + Objects.requireNonNull(valueFreeMessage, "valueFreeMessage"), cause);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
