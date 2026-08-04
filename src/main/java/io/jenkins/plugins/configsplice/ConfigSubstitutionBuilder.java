package io.jenkins.plugins.configsplice;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Item;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.ListBoxModel;
import io.jenkins.plugins.configsplice.engine.SpliceException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import jenkins.model.Jenkins;
import jenkins.tasks.SimpleBuildStep;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.verb.POST;

/**
 * The Freestyle build step (SRS section 4.7).
 *
 * <p>An adapter, not a second implementation: it binds the same {@link TargetGroup}s from a form
 * instead of from a Groovy map, then hands them to {@link SubstitutionRunner}, which is the same code
 * the Pipeline step runs. Anything that changes about validation, credential resolution or logging
 * changes for both surfaces at once, because there is only one copy of it.
 *
 * <p><b>Deliberately carries no {@code @Symbol}.</b> Pipeline users have {@code configSubstitution},
 * which returns the result map; this class cannot return anything, because
 * {@link SimpleBuildStep#perform} is {@code void}. Publishing a symbol would offer Pipeline a second
 * entry point that silently lacks the return value the README tells people to read, so it is not
 * offered. The legacy {@code step([$class: 'ConfigSubstitutionBuilder', ...])} form still reaches it,
 * which is unavoidable for a {@code SimpleBuildStep} and obscure enough not to mislead.
 *
 * <p>Freestyle discards the result map. The counts still reach the user through the summary line the
 * runner logs, which is the only channel a Freestyle job has.
 */
public class ConfigSubstitutionBuilder extends Builder implements SimpleBuildStep {

    private final List<TargetGroup> targets;

    private boolean dryRun;

    private String noMatchBehavior = "fail";

    private String missingPathBehavior = "fail";

    private boolean acknowledgeSecretLifecycle;

    @DataBoundConstructor
    public ConfigSubstitutionBuilder(@CheckForNull List<TargetGroup> targets) {
        this.targets = targets == null ? List.of() : new ArrayList<>(targets);
    }

    @NonNull
    public List<TargetGroup> getTargets() {
        return List.copyOf(targets);
    }

    public boolean isDryRun() {
        return dryRun;
    }

    @DataBoundSetter
    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    @NonNull
    public String getNoMatchBehavior() {
        return noMatchBehavior;
    }

    @DataBoundSetter
    public void setNoMatchBehavior(@CheckForNull String noMatchBehavior) {
        this.noMatchBehavior = blankTo(noMatchBehavior, "fail");
    }

    @NonNull
    public String getMissingPathBehavior() {
        return missingPathBehavior;
    }

    @DataBoundSetter
    public void setMissingPathBehavior(@CheckForNull String missingPathBehavior) {
        this.missingPathBehavior = blankTo(missingPathBehavior, "fail");
    }

    public boolean isAcknowledgeSecretLifecycle() {
        return acknowledgeSecretLifecycle;
    }

    @DataBoundSetter
    public void setAcknowledgeSecretLifecycle(boolean acknowledgeSecretLifecycle) {
        this.acknowledgeSecretLifecycle = acknowledgeSecretLifecycle;
    }

    private static String blankTo(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    /**
     * Runs the substitution against the build's workspace.
     *
     * <p>Failures reach the log as a message and nothing else. Agent-side failures already arrive as
     * an {@link AbortException} from {@link SubstitutionCallable}, which is why they need no handling
     * here; this catch covers the controller-side half — validation and credential resolution — where
     * a {@link SpliceException} is still in flight and would otherwise print a stack trace. A stack
     * trace is both noise and a place where a cause chain could surface something the message
     * deliberately withholds.
     */
    @Override
    public void perform(
            @NonNull Run<?, ?> run,
            @NonNull FilePath workspace,
            @NonNull EnvVars env,
            @NonNull Launcher launcher,
            @NonNull TaskListener listener)
            throws InterruptedException, IOException {

        try {
            SubstitutionRunner.perform(configuration(), run, workspace, listener);
        } catch (SpliceException e) {
            throw new AbortException(e.getMessage());
        }
    }

    /** This step's configuration in the form both surfaces share (SRS section 4.7). */
    SubstitutionRunner.Configuration configuration() {
        return new SubstitutionRunner.Configuration(
                targets, dryRun, noMatchBehavior, missingPathBehavior, acknowledgeSecretLifecycle);
    }

    @Extension
    public static class DescriptorImpl extends BuildStepDescriptor<hudson.tasks.Builder> {

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return Messages.ConfigSubstitutionBuilder_DisplayName();
        }

        @POST
        public ListBoxModel doFillNoMatchBehaviorItems(@AncestorInPath Item item) {
            checkConfigurePermission(item);
            return behaviorItems();
        }

        @POST
        public ListBoxModel doFillMissingPathBehaviorItems(@AncestorInPath Item item) {
            checkConfigurePermission(item);
            return behaviorItems();
        }

        private static void checkConfigurePermission(@CheckForNull Item item) {
            if (item == null) {
                Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            } else {
                item.checkPermission(Item.CONFIGURE);
            }
        }

        private static ListBoxModel behaviorItems() {
            ListBoxModel items = new ListBoxModel();
            items.add(Messages.Behavior_fail(), "fail");
            items.add(Messages.Behavior_warn(), "warn");
            items.add(Messages.Behavior_ignore(), "ignore");
            return items;
        }
    }
}
