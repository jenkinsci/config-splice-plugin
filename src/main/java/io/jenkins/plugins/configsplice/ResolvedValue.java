package io.jenkins.plugins.configsplice;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.util.Secret;
import java.io.Serializable;
import java.util.Objects;

/**
 * A replacement value in flight, carried so that accidental string conversion cannot expose it.
 *
 * <p>Gate 2 measured two things that force this type to exist (ADR-003):
 *
 * <ul>
 *   <li>{@code Secret.toString()} returns the plaintext, so {@code "value=" + secret} leaks. The rule
 *       "never log a value" cannot be enforced by using {@code Secret} as the carrying type.</li>
 *   <li>{@code Secret} does not encrypt under Java serialization, so this offers no wire protection
 *       either. It is not trying to: confidentiality over remoting rests on the channel's transport
 *       security and on the agent being trusted.</li>
 * </ul>
 *
 * <p>What this type does buy is that the plaintext is reachable only through {@link #plainText()},
 * a name that is conspicuous in review and impossible to reach by accident. Literals are wrapped too,
 * not just credentials, so there is exactly one path through the code and no "is this the safe one?"
 * judgement at each call site.
 */
public final class ResolvedValue implements Serializable {

    private static final long serialVersionUID = 1L;

    /** What {@link #toString()} always returns, whatever the value is. */
    static final String MASK = "****";

    private final Secret value;

    private final boolean fromCredential;

    private ResolvedValue(Secret value, boolean fromCredential) {
        this.value = value;
        this.fromCredential = fromCredential;
    }

    /** A literal supplied in the Pipeline script. Wrapped identically to a credential. */
    public static ResolvedValue literal(@NonNull String literal) {
        return new ResolvedValue(Secret.fromString(Objects.requireNonNull(literal, "literal")), false);
    }

    /** A value resolved from a Secret Text credential. */
    public static ResolvedValue credential(@NonNull Secret secret) {
        return new ResolvedValue(Objects.requireNonNull(secret, "secret"), true);
    }

    /**
     * The plaintext.
     *
     * <p>Every call is a place a secret could escape. Callers must pass the result straight to
     * serialisation or comparison and must never place it in a message, log line or exception.
     */
    public String plainText() {
        return value.getPlainText();
    }

    /** True when this came from a credential, which drives the security notice and type rules. */
    public boolean fromCredential() {
        return fromCredential;
    }

    @Override
    public String toString() {
        return MASK;
    }
}
