package io.jenkins.plugins.configsplice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.scriptsecurity.scripts.ScriptApproval;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Decision gate 5 (SRS v0.6 section 20.5): does the step resolve by function name, and can a
 * <em>sandboxed</em> Pipeline read every documented result key without an administrator approving
 * anything?
 *
 * <p>This is the gate that killed the original design. Version 0.2 returned a plugin-defined result
 * object; reading a field off it in a sandboxed script requires a script-security approval, which
 * contradicted the SRS's own "no Script Approval" requirement. The result is now plain JDK
 * collections, and this test is what proves it — running a genuinely sandboxed script, then
 * asserting nothing landed in the pending-approval queue.
 */
@WithJenkins
class Gate5EvidenceTest {

    private static final List<String> OBSERVATIONS = new ArrayList<>();

    private static void record(String probe, String finding) {
        OBSERVATIONS.add(String.format("  %-52s %s", probe, finding));
    }

    private static final String PIPELINE =
            """
            node {
              writeFile file: 'appsettings.json', text: '''{
              // deployment settings
              "Logging": { "LogLevel": { "Default": "Information" } },
              "Port": 8080,
              "Enabled": false
            }'''
              def result = configSubstitution(
                  targets: [
                      [
                          files: ['appsettings.json'],
                          format: 'json',
                          substitutions: [
                              [path: 'Logging.LogLevel.Default', value: 'Warning'],
                              [path: 'Port', value: '9090'],
                              [path: 'Enabled', value: 'true']
                          ]
                      ]
                  ]
              )
              echo "PROBE bracket=${result['filesChanged']}"
              echo "PROBE property=${result.filesChanged}"
              echo "PROBE matched=${result['substitutionsMatched']}"
              echo "PROBE dryRun=${result['dryRun']}"

              int substitutions = 0
              int patterns = 0
              for (entry in result['details']) {
                if (entry['kind'] == 'substitution') { substitutions++ }
                if (entry['kind'] == 'pattern') { patterns++ }
              }
              echo "PROBE detailSubstitutions=${substitutions}"
              echo "PROBE detailPatterns=${patterns}"

              def text = readFile('appsettings.json')
              echo "PROBE warningWritten=${text.contains('\\"Warning\\"')}"
              echo "PROBE numberStayedNumber=${text.contains('9090') && !text.contains('\\"9090\\"')}"
              echo "PROBE booleanStayedBoolean=${text.contains('true') && !text.contains('\\"true\\"')}"
              echo "PROBE commentSurvived=${text.contains('// deployment settings')}"
            }
            """;

    @Test
    @DisplayName("gate 5: a sandboxed Pipeline reads the whole result map with no approvals")
    void sandboxedPipelineReadsResultMap(JenkinsRule j) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, "gate5");
        // sandbox = true is the entire point: an unsandboxed script would prove nothing.
        job.setDefinition(new CpsFlowDefinition(PIPELINE, true));

        WorkflowRun run = j.buildAndAssertSuccess(job);

        j.assertLogContains("PROBE bracket=1", run);
        j.assertLogContains("PROBE property=1", run);
        j.assertLogContains("PROBE matched=3", run);
        j.assertLogContains("PROBE dryRun=false", run);
        j.assertLogContains("PROBE detailSubstitutions=3", run);
        j.assertLogContains("PROBE detailPatterns=1", run);
        record("bracket access result['filesChanged']", "works in sandbox");
        record("property access result.filesChanged", "works in sandbox");
        record("iteration over result['details'] entries", "works in sandbox");

        j.assertLogContains("PROBE warningWritten=true", run);
        j.assertLogContains("PROBE numberStayedNumber=true", run);
        j.assertLogContains("PROBE booleanStayedBoolean=true", run);
        j.assertLogContains("PROBE commentSurvived=true", run);
        record("type inference end to end", "string/number/boolean each kept their JSON type");
        record("comment preservation end to end", "// comment survived a real Pipeline run");

        int pending = ScriptApproval.get().getPendingSignatures().size();
        assertEquals(0, pending, "no signature may need administrator approval");
        record("pending script-security approvals", pending + " (must be 0)");
    }

    @Test
    @DisplayName("gate 5: the step resolves by function name, not by @Symbol")
    void stepResolvesByFunctionName(JenkinsRule j) {
        ConfigSubstitutionStep.DescriptorImpl descriptor =
                j.jenkins.getDescriptorByType(ConfigSubstitutionStep.DescriptorImpl.class);

        assertEquals("configSubstitution", descriptor.getFunctionName());
        assertTrue(
                ConfigSubstitutionStep.class.getAnnotation(Symbol.class) == null,
                "the step must not depend on @Symbol for resolution");
        record("StepDescriptor.getFunctionName()", "configSubstitution, no @Symbol involved");
    }

    @Test
    @DisplayName("gate 5: dry run reports without writing")
    void dryRunWritesNothing(JenkinsRule j) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, "gate5-dryrun");
        job.setDefinition(new CpsFlowDefinition(
                """
                node {
                  writeFile file: 'appsettings.json', text: '{"Port": 8080}'
                  def result = configSubstitution(
                      dryRun: true,
                      targets: [[files: ['appsettings.json'], format: 'json',
                                 substitutions: [[path: 'Port', value: '9090']]]]
                  )
                  echo "PROBE planned=${result['filesPlanned']}"
                  echo "PROBE changed=${result['filesChanged']}"
                  echo "PROBE untouched=${readFile('appsettings.json').contains('8080')}"
                }
                """,
                true));

        WorkflowRun run = j.buildAndAssertSuccess(job);

        j.assertLogContains("PROBE planned=1", run);
        j.assertLogContains("PROBE changed=0", run);
        j.assertLogContains("PROBE untouched=true", run);
        record("dryRun", "reported 1 planned, changed 0, file untouched");
    }

    @AfterAll
    static void writeEvidence() throws IOException {
        List<String> report = new ArrayList<>();
        report.add("=== Gate 5 evidence: step resolution and sandbox-safe result map ===");
        report.add("  jenkins: " + Jenkins.VERSION + " / JDK "
                + System.getProperty("java.version"));
        report.addAll(OBSERVATIONS);

        Path directory = Path.of("target", "gate-evidence");
        Files.createDirectories(directory);
        Files.write(directory.resolve("gate-5-sandbox-result-map.txt"), report);
        report.forEach(System.out::println);
    }
}
