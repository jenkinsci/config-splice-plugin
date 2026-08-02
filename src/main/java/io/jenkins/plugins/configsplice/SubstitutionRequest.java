package io.jenkins.plugins.configsplice;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * The validated, credential-resolved plan sent to the agent.
 *
 * <p>Deliberately a plain data carrier of JDK types plus {@link ResolvedValue}: it crosses the
 * remoting boundary, so every field has to be serializable and JEP-200-safe. Credentials are resolved
 * on the controller before this is built, because credential lookup needs the {@code Run} context and
 * must not be attempted from an agent.
 */
final class SubstitutionRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    final List<Group> groups;

    final boolean dryRun;

    final Wire.Behavior noMatchBehavior;

    final Wire.Behavior missingPathBehavior;

    SubstitutionRequest(
            List<Group> groups,
            boolean dryRun,
            Wire.Behavior noMatchBehavior,
            Wire.Behavior missingPathBehavior) {
        this.groups = new ArrayList<>(groups);
        this.dryRun = dryRun;
        this.noMatchBehavior = noMatchBehavior;
        this.missingPathBehavior = missingPathBehavior;
    }

    /** True when any substitution carries a credential, which drives the security notice. */
    boolean hasCredentialBackedValues() {
        return groups.stream()
                .flatMap(group -> group.substitutions.stream())
                .anyMatch(substitution ->
                        substitution.value != null && substitution.value.fromCredential());
    }

    /** One target group: its globs, its declared format, and the substitutions scoped to it. */
    static final class Group implements Serializable {

        private static final long serialVersionUID = 1L;

        /** One-based, matching the indexes used in logs and in the result map. */
        final int index;

        final List<String> globs;

        final Wire.Format format;

        final List<Sub> substitutions;

        Group(int index, List<String> globs, Wire.Format format, List<Sub> substitutions) {
            this.index = index;
            this.globs = new ArrayList<>(globs);
            this.format = format;
            this.substitutions = new ArrayList<>(substitutions);
        }
    }

    /** One substitution with its value already resolved. */
    static final class Sub implements Serializable {

        private static final long serialVersionUID = 1L;

        /** The path exactly as the user wrote it; used verbatim in logs and the result map. */
        final String path;

        /** Null only when {@link #type} is {@code NULL}. */
        final ResolvedValue value;

        final Wire.ValueType type;

        Sub(String path, ResolvedValue value, Wire.ValueType type) {
            this.path = path;
            this.value = value;
            this.type = type;
        }
    }
}
