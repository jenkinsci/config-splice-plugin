package io.jenkins.plugins.configsplice;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.FilePath;
import hudson.model.Item;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.ListBoxModel;
import io.jenkins.plugins.configsplice.engine.ErrorCode;
import io.jenkins.plugins.configsplice.engine.SpliceException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.verb.POST;

/**
 * The {@code configSubstitution} Pipeline step (SRS section 4).
 *
 * <pre>{@code
 * def result = configSubstitution(
 *     targets: [[files: ['**' + '/web.config'], format: 'xml',
 *                substitutions: [[path: 'appSettings.ApiUrl', value: 'https://production.com']]]]
 * )
 * echo "Changed ${result['filesChanged']} file(s)"
 * }</pre>
 *
 * <p>The step resolves credentials on the controller, where the {@code Run} context is available,
 * then hands a fully resolved request to the agent that owns the workspace. It returns a value-free
 * map of plain JDK collections so that a sandboxed Pipeline can read it without Script Approval.
 */
public class ConfigSubstitutionStep extends Step {

    private final List<TargetGroup> targets;

    private boolean dryRun;

    private String noMatchBehavior = "fail";

    private String missingPathBehavior = "fail";

    private boolean acknowledgeSecretLifecycle;

    @DataBoundConstructor
    public ConfigSubstitutionStep(@NonNull List<TargetGroup> targets) {
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

    @Override
    public StepExecution start(StepContext context) {
        return new Execution(context, this);
    }

    /**
     * Runs the substitution.
     *
     * <p>Synchronous but non-blocking: the work is filesystem-bound on an agent, so it must not hold
     * a CPS VM thread, but it needs no callback machinery either.
     */
    static class Execution extends SynchronousNonBlockingStepExecution<Map<String, Object>> {

        private static final long serialVersionUID = 1L;

        private final transient ConfigSubstitutionStep step;

        Execution(StepContext context, ConfigSubstitutionStep step) {
            super(context);
            this.step = step;
        }

        @Override
        protected Map<String, Object> run() throws Exception {
            StepContext context = getContext();
            Run<?, ?> run = context.get(Run.class);
            FilePath workspace = context.get(FilePath.class);
            TaskListener listener = context.get(TaskListener.class);
            PrintStream logger = listener.getLogger();

            SubstitutionRequest request = buildRequest(run);

            if (!step.dryRun && !step.acknowledgeSecretLifecycle && request.hasCredentialBackedValues()) {
                logger.println("[configSubstitution] SECURITY NOTICE: This step writes credential-backed "
                        + "values to workspace files. Do not archive or stash those files after "
                        + "substitution. Set acknowledgeSecretLifecycle: true only after reviewing "
                        + "this risk.");
            }

            Map<String, Object> result = workspace.act(new SubstitutionCallable(request));

            // The agent buffers its log lines so ordering is deterministic; print them here and keep
            // them out of the documented result schema.
            Object lines = result.remove("log");
            if (lines instanceof List<?> logLines) {
                for (Object line : logLines) {
                    logger.println("[configSubstitution] " + line);
                }
            }
            summarise(logger, result);
            return new LinkedHashMap<>(result);
        }

        private void summarise(PrintStream logger, Map<String, Object> result) {
            if (Boolean.TRUE.equals(result.get("dryRun"))) {
                logger.printf(
                        "[configSubstitution] Dry run: %s file(s) would change; no files were written.%n",
                        result.get("filesPlanned"));
                return;
            }
            logger.printf(
                    "[configSubstitution] Changed %s file(s); %s unchanged; %s warning(s).%n",
                    result.get("filesChanged"), result.get("filesUnchanged"), result.get("warnings"));
        }

        /** Validates the configuration and resolves every credential before anything leaves the controller. */
        private SubstitutionRequest buildRequest(Run<?, ?> run) throws SpliceException {
            if (step.targets.isEmpty()) {
                throw new SpliceException(ErrorCode.FILE_NOT_FOUND, "targets must contain at least one group");
            }
            Wire.Behavior noMatch = Wire.Behavior.parse(step.noMatchBehavior);
            Wire.Behavior missingPath = Wire.Behavior.parse(step.missingPathBehavior);

            List<SubstitutionRequest.Group> groups = new ArrayList<>();
            for (int i = 0; i < step.targets.size(); i++) {
                TargetGroup target = step.targets.get(i);
                int oneBased = i + 1;
                target.validate(oneBased);

                List<SubstitutionRequest.Sub> resolved = new ArrayList<>();
                for (Substitution substitution : target.getSubstitutions()) {
                    Wire.ValueType type = substitution.validateAndParseType();
                    resolved.add(new SubstitutionRequest.Sub(
                            substitution.getPath(), resolveValue(run, substitution, type), type));
                }
                groups.add(new SubstitutionRequest.Group(
                        oneBased, target.getFiles(), Wire.Format.parse(target.getFormat()), resolved));
            }
            return new SubstitutionRequest(groups, step.dryRun, noMatch, missingPath);
        }

        @CheckForNull
        private ResolvedValue resolveValue(Run<?, ?> run, Substitution substitution, Wire.ValueType type)
                throws SpliceException {
            if (type == Wire.ValueType.NULL) {
                return null;
            }
            String credentialsId = substitution.getCredentialsId();
            if (credentialsId == null || credentialsId.isBlank()) {
                String literal = substitution.getValue();
                if (literal == null) {
                    // validateAndParseType() should have caught this already; enforcing it here too
                    // keeps the invariant local to the call that depends on it rather than relying on
                    // a check three frames away staying correct.
                    throw new SpliceException(
                            ErrorCode.TYPE_INVALID,
                            "path '" + substitution.getPath() + "' has neither value nor credentialsId");
                }
                return ResolvedValue.literal(literal);
            }
            // findCredentialById resolves against the run's context, so folder-scoped credentials work,
            // and it registers the usage with the credentials API for us.
            StringCredentials credentials =
                    CredentialsProvider.findCredentialById(credentialsId, StringCredentials.class, run);
            if (credentials == null) {
                // Deliberately does not distinguish "absent" from "not permitted": saying which would
                // let an unauthorised user probe for credential IDs.
                throw new SpliceException(
                        ErrorCode.CREDENTIAL_UNAVAILABLE,
                        "no accessible Secret Text credential is available for path '"
                                + substitution.getPath() + "'");
            }
            return ResolvedValue.credential(credentials.getSecret());
        }
    }

    @Extension
    public static class DescriptorImpl extends StepDescriptor {

        /**
         * The Groovy function name.
         *
         * <p>This, not {@code @Symbol}, is what names a custom {@code Step}: Jenkins resolves a
         * {@code StepDescriptor} through {@code getFunctionName()} and only falls back to a symbol for
         * descriptors that are not steps (ADR: gate 5 / SRS section 4.2).
         */
        @Override
        public String getFunctionName() {
            return "configSubstitution";
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return Messages.ConfigSubstitutionStep_DisplayName();
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Set.of(Run.class, FilePath.class, TaskListener.class);
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
