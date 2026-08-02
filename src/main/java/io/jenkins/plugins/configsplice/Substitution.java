package io.jenkins.plugins.configsplice;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.model.Queue;
import hudson.model.queue.Tasks;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import io.jenkins.plugins.configsplice.engine.ErrorCode;
import io.jenkins.plugins.configsplice.engine.SpliceException;
import java.util.List;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

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
        this.value = blankToNull(value);
    }

    @CheckForNull
    public String getCredentialsId() {
        return credentialsId;
    }

    @DataBoundSetter
    public void setCredentialsId(@CheckForNull String credentialsId) {
        this.credentialsId = blankToNull(credentialsId);
    }

    /**
     * Normalises an untouched form field to {@code null}.
     *
     * <p>An HTML text input always submits a string, so a field the user never filled in arrives as
     * {@code ""} rather than absent. Without this, the Snippet Generator emits noise such as
     * {@code credentialsId: ''} for fields that were never set, because {@code DescribableModel} only
     * omits values equal to the declared default.
     *
     * <p>Nothing is lost: {@link #validateAndParseType()} already treats an empty string as "no value
     * supplied", so blank and null were never distinguishable. Substituting a deliberately empty
     * string is therefore not expressible in Version 1.0 — see the note on this method's caller.
     */
    @CheckForNull
    private static String blankToNull(@CheckForNull String raw) {
        return (raw == null || raw.isBlank()) ? null : raw;
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
            return Messages.Substitution_DisplayName();
        }

        /**
         * Requires configure permission on the item being edited, or overall administer when there is
         * no item context yet.
         *
         * <p>Applied to every web method here, including the ones that only inspect a string. Form
         * validation endpoints are reachable by anyone who can guess the URL, so "this one is cheap
         * and leaks nothing" is a judgement that has to be re-made correctly every time the method
         * changes. Checking unconditionally removes that judgement.
         */
        private static void checkConfigurePermission(@CheckForNull Item item) {
            if (item == null) {
                Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            } else {
                item.checkPermission(Item.CONFIGURE);
            }
        }

        @POST
        public FormValidation doCheckPath(@AncestorInPath Item item, @QueryParameter String value) {
            checkConfigurePermission(item);
            if (value == null || value.isBlank()) {
                return FormValidation.error(Messages.Substitution_PathRequired());
            }
            return FormValidation.ok();
        }

        /**
         * Warns when a literal looks like it might be a secret.
         *
         * <p>Advisory only. Literal step arguments are persisted and displayed by Pipeline metadata,
         * so a secret placed here is disclosed regardless of what this check says (SRS section 12.3).
         */
        @POST
        public FormValidation doCheckValue(
                @AncestorInPath Item item,
                @QueryParameter String value,
                @QueryParameter String credentialsId) {
            checkConfigurePermission(item);
            boolean hasCredential = credentialsId != null && !credentialsId.isBlank();
            if (value != null && !value.isEmpty() && hasCredential) {
                return FormValidation.error(Messages.Substitution_BothSources());
            }
            return FormValidation.ok();
        }

        public ListBoxModel doFillTypeItems() {
            ListBoxModel items = new ListBoxModel();
            items.add(Messages.Substitution_Type_auto(), "auto");
            items.add(Messages.Substitution_Type_string(), "string");
            items.add(Messages.Substitution_Type_number(), "number");
            items.add(Messages.Substitution_Type_boolean(), "boolean");
            items.add(Messages.Substitution_Type_null(), "null");
            return items;
        }

        /**
         * Populates the Secret Text credential picker (SRS section 12.2).
         *
         * <p>The permission checks are the point of this method, not the convenience. A caller who
         * may not use credentials in this context gets back only the value already configured, so the
         * dropdown cannot be used to enumerate credential IDs the caller is not entitled to see.
         */
        @POST
        public ListBoxModel doFillCredentialsIdItems(
                @AncestorInPath Item item, @QueryParameter String credentialsId) {

            StandardListBoxModel model = new StandardListBoxModel();
            if (item == null) {
                if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                    return model.includeCurrentValue(credentialsId);
                }
            } else if (!item.hasPermission(Item.EXTENDED_READ)
                    && !item.hasPermission(CredentialsProvider.USE_ITEM)) {
                return model.includeCurrentValue(credentialsId);
            }
            return model.includeEmptyValue()
                    .includeMatchingAs(
                            item instanceof Queue.Task task
                                    ? Tasks.getAuthenticationOf2(task)
                                    : ACL.SYSTEM2,
                            item,
                            StringCredentials.class,
                            List.of(),
                            CredentialsMatchers.always())
                    .includeCurrentValue(credentialsId);
        }

        /** Confirms the selected credential is resolvable, without revealing anything if it is not. */
        @POST
        public FormValidation doCheckCredentialsId(
                @AncestorInPath Item item, @QueryParameter String value) {

            if (value == null || value.isBlank()) {
                return FormValidation.ok();
            }
            if (item == null) {
                if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                    return FormValidation.ok();
                }
            } else if (!item.hasPermission(Item.EXTENDED_READ)
                    && !item.hasPermission(CredentialsProvider.USE_ITEM)) {
                return FormValidation.ok();
            }
            boolean resolvable = !CredentialsProvider.listCredentialsInItem(
                            StringCredentials.class,
                            item,
                            item instanceof Queue.Task task
                                    ? Tasks.getAuthenticationOf2(task)
                                    : ACL.SYSTEM2,
                            List.of(),
                            CredentialsMatchers.withId(value))
                    .isEmpty();
            return resolvable
                    ? FormValidation.ok()
                    : FormValidation.error(Messages.Substitution_CredentialNotFound());
        }
    }
}
