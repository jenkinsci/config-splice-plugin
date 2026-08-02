package io.jenkins.plugins.configsplice;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import io.jenkins.plugins.configsplice.engine.ErrorCode;
import io.jenkins.plugins.configsplice.engine.SpliceException;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

/**
 * One replacement: a property path plus exactly one source of the new value (SRS section 4.5).
 *
 * <p>Only {@code path} is mandatory in the constructor; everything else is a
 * {@link DataBoundSetter}, which is what keeps the Pipeline map syntax optional per field and lets
 * new options be added later without breaking existing scripts.
 */
public class Substitution extends AbstractDescribableImpl<Substitution> {

    private final String path;

    private String value;

    private String credentialsId;

    private String type = "auto";

    @DataBoundConstructor
    public Substitution(@NonNull String path) {
        this.path = path;
    }

    @NonNull
    public String getPath() {
        return path;
    }

    @CheckForNull
    public String getValue() {
        return value;
    }

    /**
     * A literal replacement.
     *
     * <p>Never put a secret here: literal step arguments are persisted and displayed by Pipeline
     * metadata (SRS section 12.3). Use {@link #setCredentialsId} instead.
     */
    @DataBoundSetter
    public void setValue(@CheckForNull String value) {
        this.value = value;
    }

    @CheckForNull
    public String getCredentialsId() {
        return credentialsId;
    }

    @DataBoundSetter
    public void setCredentialsId(@CheckForNull String credentialsId) {
        this.credentialsId = credentialsId;
    }

    @NonNull
    public String getType() {
        return type;
    }

    @DataBoundSetter
    public void setType(@CheckForNull String type) {
        this.type = (type == null || type.isBlank()) ? "auto" : type;
    }

    /**
     * Validates the combination of sources against SRS section 4.6.
     *
     * <p>Format-dependent checks (section 7.4's XML type restriction) cannot happen here because the
     * effective format is not known until the target group's files have been discovered on the agent.
     */
    Wire.ValueType validateAndParseType() throws SpliceException {
        if (path == null || path.isBlank()) {
            throw new SpliceException(ErrorCode.PATH_SYNTAX, "substitution path must not be blank");
        }
        Wire.ValueType parsed = Wire.ValueType.parse(type);

        boolean hasValue = value != null && !value.isEmpty();
        boolean hasCredential = credentialsId != null && !credentialsId.isBlank();

        if (hasValue && hasCredential) {
            throw new SpliceException(
                    ErrorCode.TYPE_INVALID,
                    "path '" + path + "' sets both value and credentialsId; exactly one is required");
        }
        if (parsed == Wire.ValueType.NULL) {
            if (hasValue || hasCredential) {
                throw new SpliceException(
                        ErrorCode.TYPE_INVALID,
                        "path '" + path + "' uses type 'null', which forbids value and credentialsId");
            }
            return parsed;
        }
        if (!hasValue && !hasCredential) {
            throw new SpliceException(
                    ErrorCode.TYPE_INVALID,
                    "path '" + path + "' needs either value or credentialsId");
        }
        if (hasCredential && !parsed.validForCredential()) {
            throw new SpliceException(
                    ErrorCode.TYPE_INVALID,
                    "path '" + path + "' uses a credential, which supports only type 'auto' or 'string'");
        }
        return parsed;
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<Substitution> {

        @NonNull
        @Override
        public String getDisplayName() {
            return "Substitution";
        }

        public FormValidation doCheckPath(@QueryParameter String value) {
            return (value == null || value.isBlank())
                    ? FormValidation.error("A property path is required.")
                    : FormValidation.ok();
        }
    }
}
