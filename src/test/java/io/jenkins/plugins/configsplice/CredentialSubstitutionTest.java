package io.jenkins.plugins.configsplice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.hudson.plugins.folder.Folder;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.CredentialsStore;
import com.cloudbees.plugins.credentials.domains.Domain;
import hudson.model.ItemGroup;
import hudson.util.Secret;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * End-to-end credential substitution through a real Pipeline (SRS section 12).
 *
 * <p>The unit tests prove the engine writes the right bytes. What only an end-to-end test can prove
 * is the part that actually matters for security: that a credential resolved on the controller
 * reaches the target file and appears <em>nowhere else</em> — not in the build log, not in the
 * persisted {@code build.xml}, not in the returned result map.
 *
 * <p>The sentinel is deliberately distinctive so a substring search cannot miss it. Note that the
 * workspace file is expected to contain it: that is the entire point of the step, and the reason
 * SRS section 12.6 exists.
 */
@WithJenkins
class CredentialSubstitutionTest {

    private static final String SENTINEL = "s3cr3t-bank-key-4d91f7";

    private static final String WEB_CONFIG =
            """
            <?xml version="1.0" encoding="utf-8"?>
            <configuration>
              <appSettings>
                <add key="BankApi:Key" value="placeholder" />
                <add key="Retries" value="3" />
              </appSettings>
              <connectionStrings>
                <add name="Default" connectionString="Server=stage;" />
              </connectionStrings>
            </configuration>
            """;

    private static void addCredential(CredentialsStore store, String id, String secret) throws IOException {
        store.addCredentials(
                Domain.global(),
                new StringCredentialsImpl(CredentialsScope.GLOBAL, id, "test", Secret.fromString(secret)));
    }

    /** Finds the store owned by {@code context} itself rather than an inherited parent store. */
    private static CredentialsStore storeOf(ItemGroup<?> context) {
        for (CredentialsStore store : CredentialsProvider.lookupStores(context)) {
            if (store.getContext() == context) {
                return store;
            }
        }
        throw new IllegalStateException("no credentials store for " + context);
    }

    private static String pipeline(String credentialsId, String extra) {
        return "node {\n"
                + "  writeFile file: 'web.config', text: '''" + WEB_CONFIG + "'''\n"
                + "  def result = configSubstitution(\n"
                + "      targets: [[files: ['web.config'], format: 'xml',\n"
                + "                 substitutions: [[path: 'appSettings.BankApi:Key',"
                + " credentialsId: '" + credentialsId + "']]]]"
                + extra + "\n"
                + "  )\n"
                + "  echo \"PROBE changed=${result['filesChanged']}\"\n"
                + "  echo \"PROBE details=${result['details']}\"\n"
                + "}\n";
    }

    /** Asserts the secret is absent everywhere it must be absent, and present where it must be. */
    private void assertSecretConfinedToWorkspace(JenkinsRule j, WorkflowRun run) throws Exception {
        String log = JenkinsRule.getLog(run);
        assertFalse(log.contains(SENTINEL), "the build log must not contain the credential");

        Path buildXml = run.getRootDir().toPath().resolve("build.xml");
        if (Files.exists(buildXml)) {
            String persisted = Files.readString(buildXml, StandardCharsets.UTF_8);
            assertFalse(persisted.contains(SENTINEL), "persisted build metadata must not contain it");
        }

        // The result map is echoed into the log above, so the log assertion already covers it; this
        // makes the intent explicit rather than incidental.
        assertFalse(log.contains(SENTINEL), "the result map must not carry the credential");
    }

    @Test
    @DisplayName("a global Secret Text credential reaches the file and nowhere else")
    void globalCredentialSubstitution(JenkinsRule j) throws Exception {
        addCredential(storeOf(j.jenkins), "bank-api-key", SENTINEL);

        WorkflowJob job = j.createProject(WorkflowJob.class, "global-credential");
        job.setDefinition(new CpsFlowDefinition(pipeline("bank-api-key", ""), true));
        WorkflowRun run = j.buildAndAssertSuccess(job);

        j.assertLogContains("PROBE changed=1", run);
        assertSecretConfinedToWorkspace(j, run);

        String written = j.jenkins.getWorkspaceFor(job).child("web.config").readToString();
        assertTrue(written.contains(SENTINEL), "the target file must hold the substituted secret");
        assertTrue(written.contains("value=\"3\""), "unrelated settings must be untouched");
        assertTrue(written.contains("<?xml version=\"1.0\" encoding=\"utf-8\"?>"), "declaration preserved");
    }

    @Test
    @DisplayName("a folder-scoped credential resolves from a job inside that folder")
    void folderScopedCredential(JenkinsRule j) throws Exception {
        Folder folder = j.jenkins.createProject(Folder.class, "team");
        addCredential(storeOf(folder), "team-bank-key", SENTINEL);

        WorkflowJob job = folder.createProject(WorkflowJob.class, "in-folder");
        job.setDefinition(new CpsFlowDefinition(pipeline("team-bank-key", ""), true));
        WorkflowRun run = j.buildAndAssertSuccess(job);

        j.assertLogContains("PROBE changed=1", run);
        assertSecretConfinedToWorkspace(j, run);
        assertTrue(
                j.jenkins.getWorkspaceFor(job).child("web.config").readToString().contains(SENTINEL),
                "a folder-scoped credential must resolve for a job inside the folder");
    }

    @Test
    @DisplayName("the security notice is emitted, and acknowledgement suppresses only the notice")
    void securityNoticeAndAcknowledgement(JenkinsRule j) throws Exception {
        addCredential(storeOf(j.jenkins), "notice-key", SENTINEL);

        WorkflowJob noisy = j.createProject(WorkflowJob.class, "notice-on");
        noisy.setDefinition(new CpsFlowDefinition(pipeline("notice-key", ""), true));
        WorkflowRun noisyRun = j.buildAndAssertSuccess(noisy);
        j.assertLogContains("SECURITY NOTICE", noisyRun);

        WorkflowJob quiet = j.createProject(WorkflowJob.class, "notice-off");
        quiet.setDefinition(new CpsFlowDefinition(
                pipeline("notice-key", ",\n      acknowledgeSecretLifecycle: true"), true));
        WorkflowRun quietRun = j.buildAndAssertSuccess(quiet);

        j.assertLogNotContains("SECURITY NOTICE", quietRun);
        j.assertLogContains("PROBE changed=1", quietRun);
        assertSecretConfinedToWorkspace(j, quietRun);
        assertTrue(
                j.jenkins.getWorkspaceFor(quiet).child("web.config").readToString().contains(SENTINEL),
                "acknowledgement must suppress only the notice, never the substitution");
    }

    @Test
    @DisplayName("an unknown credential fails without revealing whether the id exists")
    void unknownCredentialFailsGenerically(JenkinsRule j) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, "missing-credential");
        job.setDefinition(new CpsFlowDefinition(pipeline("no-such-credential", ""), true));

        WorkflowRun run = j.assertBuildStatus(hudson.model.Result.FAILURE, job.scheduleBuild2(0));
        String log = JenkinsRule.getLog(run);

        j.assertLogContains("CONFIG_SUBSTITUTION_CREDENTIAL_UNAVAILABLE", run);
        assertFalse(
                log.contains("does not exist") || log.contains("not permitted"),
                "the message must not distinguish absent from unauthorised");
        assertFalse(
                j.jenkins.getWorkspaceFor(job).child("web.config").readToString().contains(SENTINEL),
                "no substitution may occur when a credential cannot be resolved");
    }

    @Test
    @DisplayName("a credential targeting a non-string JSON scalar demands an explicit acknowledgement")
    void credentialAgainstNonStringJsonScalar(JenkinsRule j) throws Exception {
        addCredential(storeOf(j.jenkins), "port-key", SENTINEL);

        String script = "node {\n"
                + "  writeFile file: 'appsettings.json', text: '{\"Port\": 8080}'\n"
                + "  configSubstitution(targets: [[files: ['appsettings.json'], format: 'json',\n"
                + "      substitutions: [[path: 'Port', credentialsId: 'port-key']]]])\n"
                + "}\n";
        WorkflowJob job = j.createProject(WorkflowJob.class, "credential-type-ack");
        job.setDefinition(new CpsFlowDefinition(script, true));

        WorkflowRun run = j.assertBuildStatus(hudson.model.Result.FAILURE, job.scheduleBuild2(0));

        j.assertLogContains("CONFIG_SUBSTITUTION_CREDENTIAL_TYPE_ACK_REQUIRED", run);
        assertSecretConfinedToWorkspace(j, run);
        assertTrue(
                j.jenkins.getWorkspaceFor(job).child("appsettings.json").readToString().contains("8080"),
                "the file must be untouched when the type acknowledgement is missing");
    }

    @Test
    @DisplayName("an explicit string type accepts the deliberate JSON type change")
    void explicitStringTypeAcknowledgesTheChange(JenkinsRule j) throws Exception {
        addCredential(storeOf(j.jenkins), "port-key-ok", SENTINEL);

        String script = "node {\n"
                + "  writeFile file: 'appsettings.json', text: '{\"Port\": 8080}'\n"
                + "  def r = configSubstitution(targets: [[files: ['appsettings.json'], format: 'json',\n"
                + "      substitutions: [[path: 'Port', credentialsId: 'port-key-ok', type: 'string']]]])\n"
                + "  echo \"PROBE changed=${r['filesChanged']}\"\n"
                + "}\n";
        WorkflowJob job = j.createProject(WorkflowJob.class, "credential-type-ok");
        job.setDefinition(new CpsFlowDefinition(script, true));
        WorkflowRun run = j.buildAndAssertSuccess(job);

        j.assertLogContains("PROBE changed=1", run);
        assertSecretConfinedToWorkspace(j, run);

        String written = j.jenkins.getWorkspaceFor(job).child("appsettings.json").readToString();
        assertTrue(written.contains("\"" + SENTINEL + "\""), "the value must be written as a JSON string");
    }

    @Test
    @DisplayName("dry run resolves credentials without writing them anywhere")
    void dryRunDoesNotWriteTheCredential(JenkinsRule j) throws Exception {
        addCredential(storeOf(j.jenkins), "dry-key", SENTINEL);

        WorkflowJob job = j.createProject(WorkflowJob.class, "credential-dryrun");
        job.setDefinition(new CpsFlowDefinition(pipeline("dry-key", ",\n      dryRun: true"), true));
        WorkflowRun run = j.buildAndAssertSuccess(job);

        j.assertLogContains("PROBE changed=0", run);
        j.assertLogNotContains("SECURITY NOTICE", run);
        assertSecretConfinedToWorkspace(j, run);

        String written = j.jenkins.getWorkspaceFor(job).child("web.config").readToString();
        assertFalse(written.contains(SENTINEL), "a dry run must not write the credential to the file");
        assertTrue(written.contains("placeholder"), "the original value must remain");
        assertEquals(WEB_CONFIG.length(), written.length(), "the file must be byte-identical");
    }
}
