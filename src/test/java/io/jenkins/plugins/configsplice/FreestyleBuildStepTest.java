package io.jenkins.plugins.configsplice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Node;
import hudson.model.Result;
import hudson.util.Secret;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestBuilder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * The Freestyle build step (SRS section 4.7).
 *
 * <p>The adapter is thin by construction — it binds a form and calls {@link SubstitutionRunner} —
 * so these tests concentrate on the parts that are genuinely new rather than re-testing the engine:
 * that the form binds at all, that the shared runner really is shared (identical bytes from both
 * surfaces), that a failure aborts cleanly instead of printing a stack trace, and that the security
 * properties proven for Pipeline still hold on a surface that reaches them by a different path.
 */
@WithJenkins
class FreestyleBuildStepTest {

    private static final String SENTINEL = "s3cr3t-freestyle-key-8f21c4";

    private static final String APPSETTINGS =
            """
            {
              // keep me
              "Logging": { "LogLevel": { "Default": "Information" } },
              "ApiKey": "placeholder"
            }
            """;

    private static ConfigSubstitutionBuilder builderFor(Substitution... substitutions) {
        TargetGroup group = new TargetGroup(List.of("appsettings.json"), List.of(substitutions));
        group.setFormat("json");
        return new ConfigSubstitutionBuilder(List.of(group));
    }

    private static Substitution literal(String path, String value) {
        Substitution substitution = new Substitution(path);
        substitution.setValue(value);
        substitution.setType("string");
        return substitution;
    }

    /** Seeds the workspace, since a Freestyle job has no checkout in these tests. */
    private static TestBuilder writesAppSettings() {
        return new TestBuilder() {
            @Override
            public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
                    throws InterruptedException, IOException {
                assertNotNull(build.getWorkspace(), "workspace");
                build.getWorkspace().child("appsettings.json").write(APPSETTINGS, "UTF-8");
                return true;
            }
        };
    }

    private static String workspaceFileOf(AbstractBuild<?, ?> build) throws Exception {
        assertNotNull(build.getWorkspace(), "workspace");
        return build.getWorkspace().child("appsettings.json").readToString();
    }

    @Nested
    @DisplayName("configuration")
    class Configuration {

        @Test
        @DisplayName("every configured field survives a real form round trip")
        void surviveAFormRoundTrip(JenkinsRule j) throws Exception {
            ConfigSubstitutionBuilder before =
                    builderFor(literal("Logging.LogLevel.Default", "Warning"));
            before.setDryRun(true);
            before.setNoMatchBehavior("warn");
            before.setMissingPathBehavior("ignore");
            before.setAcknowledgeSecretLifecycle(true);

            FreeStyleProject project = j.createFreeStyleProject();
            project.getBuildersList().add(before);

            // Re-renders the real config.jelly, submits it and re-binds; a field that renders but does
            // not bind fails here rather than silently defaulting in production.
            ConfigSubstitutionBuilder after = j.configRoundtrip(project)
                    .getBuildersList()
                    .get(ConfigSubstitutionBuilder.class);

            assertNotNull(after, "the builder must survive the round trip at all");
            assertTrue(after.isDryRun(), "dryRun");
            assertEquals("warn", after.getNoMatchBehavior());
            assertEquals("ignore", after.getMissingPathBehavior());
            assertTrue(after.isAcknowledgeSecretLifecycle(), "acknowledgeSecretLifecycle");

            assertEquals(1, after.getTargets().size(), "target group count");
            TargetGroup group = after.getTargets().get(0);
            assertEquals("json", group.getFormat());
            assertEquals(List.of("appsettings.json"), group.getFiles(), "the List<String> files field");
            assertEquals(1, group.getSubstitutions().size());
            assertEquals("Logging.LogLevel.Default", group.getSubstitutions().get(0).getPath());
        }

        @Test
        @DisplayName("the builder is offered to Freestyle jobs")
        void isApplicableToFreestyle(JenkinsRule j) {
            ConfigSubstitutionBuilder.DescriptorImpl descriptor =
                    j.jenkins.getDescriptorByType(ConfigSubstitutionBuilder.DescriptorImpl.class);
            assertNotNull(descriptor);
            assertTrue(descriptor.isApplicable(FreeStyleProject.class));
        }
    }

    @Nested
    @DisplayName("running")
    class Running {

        @Test
        void substitutesInAFreestyleBuild(JenkinsRule j) throws Exception {
            FreeStyleProject project = j.createFreeStyleProject();
            project.getBuildersList().add(writesAppSettings());
            project.getBuildersList().add(builderFor(literal("Logging.LogLevel.Default", "Warning")));

            FreeStyleBuild build = j.buildAndAssertSuccess(project);
            String after = workspaceFileOf(build);

            assertTrue(after.contains("\"Default\": \"Warning\""), after);
            assertTrue(after.contains("// keep me"), "comments must survive: " + after);
            j.assertLogContains("Changed 1 file(s)", build);
        }

        @Test
        @DisplayName("a dry run reports without writing")
        void dryRunWritesNothing(JenkinsRule j) throws Exception {
            ConfigSubstitutionBuilder builder = builderFor(literal("Logging.LogLevel.Default", "Warning"));
            builder.setDryRun(true);

            FreeStyleProject project = j.createFreeStyleProject();
            project.getBuildersList().add(writesAppSettings());
            project.getBuildersList().add(builder);

            FreeStyleBuild build = j.buildAndAssertSuccess(project);

            assertEquals(APPSETTINGS, workspaceFileOf(build), "the file must be untouched");
            j.assertLogContains("Dry run", build);
        }

        @Test
        @DisplayName("per-glob notes from the agent reach the build log")
        void agentLogLinesAreReplayed(JenkinsRule j) throws Exception {
            // The agent buffers its log lines and the controller replays them, so a NOTE produced
            // agent-side is only visible if that replay works on this surface too.
            TargetGroup group = new TargetGroup(
                    List.of("appsettings.json", "nothing-matches-this.json"),
                    List.of(literal("Logging.LogLevel.Default", "Warning")));
            group.setFormat("json");

            FreeStyleProject project = j.createFreeStyleProject();
            project.getBuildersList().add(writesAppSettings());
            project.getBuildersList().add(new ConfigSubstitutionBuilder(List.of(group)));

            FreeStyleBuild build = j.buildAndAssertSuccess(project);

            j.assertLogContains("NOTE", build);
            j.assertLogContains("nothing-matches-this.json", build);
        }

        @Test
        @DisplayName("type 'null' writes a JSON null through this surface")
        void nullTypeIsHonoured(JenkinsRule j) throws Exception {
            Substitution substitution = new Substitution("ApiKey");
            substitution.setType("null");

            FreeStyleProject project = j.createFreeStyleProject();
            project.getBuildersList().add(writesAppSettings());
            project.getBuildersList().add(builderFor(substitution));

            FreeStyleBuild build = j.buildAndAssertSuccess(project);

            assertTrue(workspaceFileOf(build).contains("\"ApiKey\": null"), workspaceFileOf(build));
        }

        @Test
        @DisplayName("unset options default to the documented values")
        void defaultsAreTheDocumentedOnes() {
            ConfigSubstitutionBuilder builder = new ConfigSubstitutionBuilder(null);

            assertEquals(List.of(), builder.getTargets(), "a null targets list binds as empty");
            assertEquals("fail", builder.getNoMatchBehavior());
            assertEquals("fail", builder.getMissingPathBehavior());
            assertFalse(builder.isDryRun());
            assertFalse(builder.isAcknowledgeSecretLifecycle());

            // A form submits "" for a select the user never touched, and a script may omit the field
            // entirely; neither must survive as "" or null.
            builder.setNoMatchBehavior("");
            builder.setMissingPathBehavior("   ");
            assertEquals("fail", builder.getNoMatchBehavior(), "blank must fall back, not persist");
            assertEquals("fail", builder.getMissingPathBehavior(), "blank must fall back, not persist");

            builder.setNoMatchBehavior(null);
            builder.setMissingPathBehavior(null);
            assertEquals("fail", builder.getNoMatchBehavior(), "null must fall back, not persist");
            assertEquals("fail", builder.getMissingPathBehavior(), "null must fall back, not persist");
        }

        @Test
        @DisplayName("both surfaces produce byte-identical output, which is what makes this an adapter")
        void freestyleAndPipelineAgree(JenkinsRule j) throws Exception {
            FreeStyleProject freestyle = j.createFreeStyleProject();
            freestyle.getBuildersList().add(writesAppSettings());
            freestyle.getBuildersList().add(builderFor(literal("Logging.LogLevel.Default", "Warning")));
            String fromFreestyle = workspaceFileOf(j.buildAndAssertSuccess(freestyle));

            WorkflowJob pipeline = j.createProject(WorkflowJob.class);
            pipeline.setDefinition(new CpsFlowDefinition(
                    "node {\n"
                            + "  writeFile file: 'appsettings.json', text: '''" + APPSETTINGS + "'''\n"
                            + "  configSubstitution(targets: [[files: ['appsettings.json'], format: 'json',\n"
                            + "    substitutions: [[path: 'Logging.LogLevel.Default', value: 'Warning',\n"
                            + "                     type: 'string']]]])\n"
                            + "}",
                    true));
            j.buildAndAssertSuccess(pipeline);

            FilePath pipelineWorkspace = j.jenkins.getWorkspaceFor(pipeline);
            assertNotNull(pipelineWorkspace, "pipeline workspace");
            String fromPipeline = pipelineWorkspace.child("appsettings.json").readToString();

            assertEquals(fromPipeline, fromFreestyle, "the two surfaces must not diverge");
        }
    }

    @Nested
    @DisplayName("on a real agent")
    class OnARealAgent {

        /**
         * Every other test here runs on the built-in node, where {@code workspace.act} is a local
         * call and nothing is serialised. That leaves the remoting half of this surface unproven, so
         * these three run a genuine Freestyle build on a real inbound agent.
         *
         * <p>Each asserts {@code getBuiltOn()} first. Without that, a misconfigured project would
         * quietly fall back to the controller and the test would pass while proving nothing.
         */
        private FreeStyleProject projectOn(JenkinsRule j, Node agent) throws Exception {
            FreeStyleProject project = j.createFreeStyleProject();
            project.setAssignedNode(agent);
            project.getBuildersList().add(writesAppSettings());
            return project;
        }

        @Test
        void substitutesInAWorkspaceOwnedByAnAgent(JenkinsRule j) throws Exception {
            Node agent = j.createOnlineSlave();
            FreeStyleProject project = projectOn(j, agent);
            project.getBuildersList().add(builderFor(literal("Logging.LogLevel.Default", "Warning")));

            FreeStyleBuild build = j.buildAndAssertSuccess(project);

            assertEquals(agent, build.getBuiltOn(), "the build must actually have run on the agent");
            String after = workspaceFileOf(build);
            assertTrue(after.contains("\"Default\": \"Warning\""), after);
            assertTrue(after.contains("// keep me"), "comments must survive remoting too: " + after);
        }

        @Test
        @DisplayName("a credential crosses to the agent, lands in the file, and comes back nowhere")
        void credentialDoesNotLeakBackOverTheChannel(JenkinsRule j) throws Exception {
            SystemCredentialsProvider.getInstance()
                    .getCredentials()
                    .add(new StringCredentialsImpl(
                            CredentialsScope.GLOBAL, "agent-key", "test", Secret.fromString(SENTINEL)));
            SystemCredentialsProvider.getInstance().save();

            Substitution substitution = new Substitution("ApiKey");
            substitution.setCredentialsId("agent-key");

            Node agent = j.createOnlineSlave();
            FreeStyleProject project = projectOn(j, agent);
            project.getBuildersList().add(builderFor(substitution));

            FreeStyleBuild build = j.buildAndAssertSuccess(project);

            assertEquals(agent, build.getBuiltOn(), "the build must actually have run on the agent");
            assertTrue(workspaceFileOf(build).contains(SENTINEL), "the agent's file is supposed to get it");
            assertFalse(JenkinsRule.getLog(build).contains(SENTINEL), "the build log must not carry it");
            assertFalse(
                    Files.readString(build.getRootDir().toPath().resolve("build.xml"), StandardCharsets.UTF_8)
                            .contains(SENTINEL),
                    "persisted build metadata must not carry it");
        }

        @Test
        @DisplayName("an agent-side failure survives serialisation and still aborts cleanly")
        void agentSideAbortSurvivesTheChannel(JenkinsRule j) throws Exception {
            // The whole point of SubstitutionCallable throwing AbortException is that it crosses the
            // channel and is rendered as a bare message. On the built-in node nothing is serialised,
            // so only this test actually exercises that.
            Node agent = j.createOnlineSlave();
            FreeStyleProject project = projectOn(j, agent);
            project.getBuildersList().add(builderFor(literal("Nope.Missing", "x")));

            FreeStyleBuild build = j.assertBuildStatus(Result.FAILURE, project.scheduleBuild2(0));
            String log = JenkinsRule.getLog(build);

            assertEquals(agent, build.getBuiltOn(), "the build must actually have run on the agent");
            assertTrue(log.contains("CONFIG_SUBSTITUTION_PATH_MISSING"), "the message must survive: " + log);
            assertFalse(
                    log.contains("\tat io.jenkins.plugins.configsplice"),
                    "an AbortException must not print a stack trace after remoting:\n" + log);
        }
    }

    @Nested
    @DisplayName("failure and disclosure")
    class FailureAndDisclosure {

        @Test
        @DisplayName("a failure aborts with the value-free message and no stack trace")
        void failureIsCleanlyAborted(JenkinsRule j) throws Exception {
            FreeStyleProject project = j.createFreeStyleProject();
            project.getBuildersList().add(writesAppSettings());
            project.getBuildersList().add(builderFor(literal("Nope.Missing", "x")));

            FreeStyleBuild build =
                    j.assertBuildStatus(Result.FAILURE, project.scheduleBuild2(0));
            String log = JenkinsRule.getLog(build);

            assertTrue(log.contains("CONFIG_SUBSTITUTION_PATH_MISSING"), log);
            // AbortException is the difference between a readable failure and a wall of frames --
            // and a cause chain is somewhere a withheld detail could resurface.
            assertFalse(
                    log.contains("\tat io.jenkins.plugins.configsplice"),
                    "an AbortException must not print a stack trace:\n" + log);
        }

        @Test
        @DisplayName("a controller-side failure aborts just as cleanly as an agent-side one")
        void controllerSideFailureIsAlsoAborted(JenkinsRule j) throws Exception {
            // The agent-side path arrives already wrapped as an AbortException by the callable. This
            // is the other half -- credential resolution, which never leaves the controller -- and it
            // is the only reason ConfigSubstitutionBuilder catches SpliceException at all.
            Substitution substitution = new Substitution("ApiKey");
            substitution.setCredentialsId("no-such-credential");

            FreeStyleProject project = j.createFreeStyleProject();
            project.getBuildersList().add(writesAppSettings());
            project.getBuildersList().add(builderFor(substitution));

            FreeStyleBuild build = j.assertBuildStatus(Result.FAILURE, project.scheduleBuild2(0));
            String log = JenkinsRule.getLog(build);

            assertTrue(log.contains("CONFIG_SUBSTITUTION_CREDENTIAL_UNAVAILABLE"), log);
            // Section 12.1: absent and inaccessible must be indistinguishable, so the message must
            // not name the credential ID back to the caller.
            assertFalse(log.contains("no-such-credential"), "must not echo the credential ID:\n" + log);
            assertFalse(
                    log.contains("\tat io.jenkins.plugins.configsplice"),
                    "controller-side failures must not print a stack trace either:\n" + log);
        }

        @Test
        @DisplayName("an empty target list is rejected before anything runs")
        void emptyTargetsIsRejected(JenkinsRule j) throws Exception {
            FreeStyleProject project = j.createFreeStyleProject();
            project.getBuildersList().add(writesAppSettings());
            project.getBuildersList().add(new ConfigSubstitutionBuilder(null));

            FreeStyleBuild build = j.assertBuildStatus(Result.FAILURE, project.scheduleBuild2(0));

            assertEquals(APPSETTINGS, workspaceFileOf(build), "nothing may be written");
            j.assertLogContains("targets must contain at least one group", build);
        }

        @Test
        @DisplayName("a credential reaches the file and nothing else")
        void credentialDoesNotLeak(JenkinsRule j) throws Exception {
            SystemCredentialsProvider.getInstance()
                    .getCredentials()
                    .add(new StringCredentialsImpl(
                            CredentialsScope.GLOBAL, "api-key", "test", Secret.fromString(SENTINEL)));
            SystemCredentialsProvider.getInstance().save();

            Substitution substitution = new Substitution("ApiKey");
            substitution.setCredentialsId("api-key");

            FreeStyleProject project = j.createFreeStyleProject();
            project.getBuildersList().add(writesAppSettings());
            project.getBuildersList().add(builderFor(substitution));

            FreeStyleBuild build = j.buildAndAssertSuccess(project);

            assertTrue(workspaceFileOf(build).contains(SENTINEL), "the file is supposed to get the value");

            String log = JenkinsRule.getLog(build);
            assertFalse(log.contains(SENTINEL), "the build log must not carry it");

            String persisted = Files.readString(
                    build.getRootDir().toPath().resolve("build.xml"), StandardCharsets.UTF_8);
            assertFalse(persisted.contains(SENTINEL), "persisted build metadata must not carry it");
        }

        @Test
        @DisplayName("the credential lifecycle notice is printed on this surface too")
        void lifecycleNoticeIsPrinted(JenkinsRule j) throws Exception {
            SystemCredentialsProvider.getInstance()
                    .getCredentials()
                    .add(new StringCredentialsImpl(
                            CredentialsScope.GLOBAL, "notice-key", "test", Secret.fromString(SENTINEL)));
            SystemCredentialsProvider.getInstance().save();

            Substitution substitution = new Substitution("ApiKey");
            substitution.setCredentialsId("notice-key");

            FreeStyleProject project = j.createFreeStyleProject();
            project.getBuildersList().add(writesAppSettings());
            project.getBuildersList().add(builderFor(substitution));

            j.assertLogContains("SECURITY NOTICE", j.buildAndAssertSuccess(project));
        }
    }
}
