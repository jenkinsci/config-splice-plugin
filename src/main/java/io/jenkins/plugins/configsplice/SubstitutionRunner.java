package io.jenkins.plugins.configsplice;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.FilePath;
import hudson.model.Run;
import hudson.model.TaskListener;
import io.jenkins.plugins.configsplice.engine.ErrorCode;
import io.jenkins.plugins.configsplice.engine.SpliceException;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;

/**
 * Everything the Pipeline step and the Freestyle build step do identically (SRS section 4.7).
 *
 * <p>The two surfaces differ only in how their configuration is bound and what they can hand back:
 * the Pipeline step returns the result map, and Freestyle has nowhere to put one. Everything between
 * those two points — validation, credential resolution, the lifecycle notice, dispatch to the agent,
 * log replay and the summary line — is here, so there is exactly one copy of it.
 *
 * <p>That matters most for {@link #resolveValue}. It carries the rule that an inaccessible credential
 * and a non-existent one produce the same message, so that an unauthorised user cannot probe for
 * credential IDs. A second copy of that logic is precisely how one copy would eventually lose the
 * rule, so the Freestyle adapter was written to call this rather than to mirror it.
 *
 * <p>Deliberately not in the {@code engine} package: it needs {@link Run}, {@link FilePath} and the
 * credentials API, and the engine's freedom from Jenkins imports is what keeps the bulk of the suite
 * runnable without a Jenkins harness.
 */
final class SubstitutionRunner {

    private SubstitutionRunner() {
    }

    /**
     * The step configuration, independent of which UI bound it.
     *
     * <p>Holds {@link TargetGroup}s, which reach a {@link Substitution#getValue()} that may be a
     * hard-coded literal secret. Neither is a record and neither overrides {@code toString()}, so a
     * generated {@code toString()} here would print their identity hashes rather than their contents
     * — but this is exactly the shape the CONTRIBUTING rule is about, so it masks explicitly instead
     * of depending on that staying true.
     */
    record Configuration(
            @NonNull List<TargetGroup> targets,
            boolean dryRun,
            @NonNull String noMatchBehavior,
            @NonNull String missingPathBehavior,
            boolean acknowledgeSecretLifecycle) {

        @Override
        public String toString() {
            return "Configuration[" + targets.size() + " target group(s), dryRun=" + dryRun + "]";
        }
    }

    /**
     * Resolves credentials on the controller, runs the substitution on the agent, and logs.
     *
     * @return the value-free result map, which the Pipeline step returns and Freestyle discards
     */
    static Map<String, Object> perform(
            Configuration config, Run<?, ?> run, FilePath workspace, TaskListener listener)
            throws IOException, InterruptedException, SpliceException {

        PrintStream logger = listener.getLogger();
        SubstitutionRequest request = buildRequest(config, run);

        if (!config.dryRun() && !config.acknowledgeSecretLifecycle() && request.hasCredentialBackedValues()) {
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

    private static void summarise(PrintStream logger, Map<String, Object> result) {
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
    private static SubstitutionRequest buildRequest(Configuration config, Run<?, ?> run)
            throws SpliceException {

        if (config.targets().isEmpty()) {
            throw new SpliceException(ErrorCode.FILE_NOT_FOUND, "targets must contain at least one group");
        }
        Wire.Behavior noMatch = Wire.Behavior.parse(config.noMatchBehavior());
        Wire.Behavior missingPath = Wire.Behavior.parse(config.missingPathBehavior());

        List<SubstitutionRequest.Group> groups = new ArrayList<>();
        for (int i = 0; i < config.targets().size(); i++) {
            TargetGroup target = config.targets().get(i);
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
        return new SubstitutionRequest(groups, config.dryRun(), noMatch, missingPath);
    }

    @CheckForNull
    private static ResolvedValue resolveValue(Run<?, ?> run, Substitution substitution, Wire.ValueType type)
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
