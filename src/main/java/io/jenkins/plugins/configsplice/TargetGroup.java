package io.jenkins.plugins.configsplice;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import io.jenkins.plugins.configsplice.engine.ErrorCode;
import io.jenkins.plugins.configsplice.engine.SpliceException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/**
 * A set of file globs, one format, and the substitutions that apply only to those files
 * (SRS section 4.4).
 *
 * <p>Grouping is the whole reason the API is shaped this way. A flat list of files crossed with a
 * flat list of substitutions would apply XML paths to JSON files and fail under the default error
 * policy; scoping substitutions to the files they belong to is what makes the canonical example work.
 */
public class TargetGroup extends AbstractDescribableImpl<TargetGroup> {

    private final List<String> files;

    private final List<Substitution> substitutions;

    private String format = "auto";

    @DataBoundConstructor
    public TargetGroup(@NonNull List<String> files, @NonNull List<Substitution> substitutions) {
        this.files = files == null ? List.of() : new ArrayList<>(files);
        this.substitutions = substitutions == null ? List.of() : new ArrayList<>(substitutions);
    }

    @NonNull
    public List<String> getFiles() {
        return List.copyOf(files);
    }

    /**
     * The file patterns as newline-separated text, for the form view only.
     *
     * <p>Not a configuration property: {@code DescribableModel} derives properties from the
     * {@code @DataBoundConstructor} parameters and {@code @DataBoundSetter} methods, so this getter is
     * invisible to Pipeline binding and to the Snippet Generator. It exists because Jenkins form
     * binding has no workable idiom for a {@code List<String>}, so the form uses a textarea and
     * {@link DescriptorImpl#newInstance} converts it back. The Pipeline API stays
     * {@code files: ['a', 'b']}.
     */
    @NonNull
    public String getFilesText() {
        return String.join("\n", files);
    }

    @NonNull
    public List<Substitution> getSubstitutions() {
        return List.copyOf(substitutions);
    }

    @NonNull
    public String getFormat() {
        return format;
    }

    @DataBoundSetter
    public void setFormat(@CheckForNull String format) {
        this.format = (format == null || format.isBlank()) ? "auto" : format;
    }

    /** Validates everything knowable before the agent expands globs (SRS section 4.6). */
    void validate(int oneBasedIndex) throws SpliceException {
        String where = "target group " + oneBasedIndex;
        if (files.isEmpty()) {
            throw new SpliceException(ErrorCode.FILE_NOT_FOUND, where + " has no file patterns");
        }
        for (String glob : files) {
            if (glob == null || glob.isBlank()) {
                throw new SpliceException(ErrorCode.WORKSPACE_ESCAPE, where + " has a blank file pattern");
            }
        }
        if (substitutions.isEmpty()) {
            throw new SpliceException(ErrorCode.PATH_SYNTAX, where + " has no substitutions");
        }

        Wire.Format.parse(format);

        // Duplicate canonical paths within one group are fatal. Canonicalisation happens per format,
        // so the raw path is used here and the agent re-checks once the format is known.
        Set<String> seen = new HashSet<>();
        for (Substitution substitution : substitutions) {
            substitution.validateAndParseType();
            if (!seen.add(substitution.getPath())) {
                throw new SpliceException(
                        ErrorCode.PATH_SYNTAX,
                        where + " repeats path '" + substitution.getPath() + "'");
            }
        }
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<TargetGroup> {

        @NonNull
        @Override
        public String getDisplayName() {
            return Messages.TargetGroup_DisplayName();
        }

        /**
         * Converts the textarea's newline-separated patterns into the {@code List<String>} the
         * constructor expects.
         *
         * <p>Only the form submits {@code files} as a single string; a Pipeline script already
         * supplies a list, and that case passes through untouched.
         */
        @Override
        public TargetGroup newInstance(
                org.kohsuke.stapler.StaplerRequest2 req, net.sf.json.JSONObject formData)
                throws FormException {

            Object submitted = formData.opt("files");
            if (submitted instanceof String text) {
                net.sf.json.JSONArray patterns = new net.sf.json.JSONArray();
                text.lines()
                        .map(String::trim)
                        .filter(line -> !line.isEmpty())
                        .forEach(patterns::add);
                formData.put("files", patterns);
            }
            return (TargetGroup) super.newInstance(req, formData);
        }

        /** Same permission model as every other web method here; see Substitution.DescriptorImpl. */
        private static void checkConfigurePermission(@CheckForNull hudson.model.Item item) {
            if (item == null) {
                jenkins.model.Jenkins.get().checkPermission(jenkins.model.Jenkins.ADMINISTER);
            } else {
                item.checkPermission(hudson.model.Item.CONFIGURE);
            }
        }

        @org.kohsuke.stapler.verb.POST
        public hudson.util.ListBoxModel doFillFormatItems(
                @org.kohsuke.stapler.AncestorInPath hudson.model.Item item) {
            checkConfigurePermission(item);
            hudson.util.ListBoxModel items = new hudson.util.ListBoxModel();
            items.add(Messages.TargetGroup_Format_auto(), "auto");
            items.add(Messages.TargetGroup_Format_json(), "json");
            items.add(Messages.TargetGroup_Format_xml(), "xml");
            return items;
        }
    }
}
