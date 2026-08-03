package io.jenkins.plugins.configsplice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.util.ListBoxModel;
import java.util.List;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.steps.StepConfigTester;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.springframework.security.access.AccessDeniedException;

/**
 * Verifies the form views actually bind, not merely parse.
 *
 * <p>The plugin parent already checks that every Jelly file is well formed. That says nothing about
 * whether a submitted form reconstructs the object. {@link StepConfigTester} renders the real
 * {@code config.jelly}, submits it and re-binds the result, so a field that renders but does not
 * round-trip fails here.
 *
 * <p>The field most at risk is {@code TargetGroup.files}: it is a {@code List<String>}, which Jenkins
 * form binding handles awkwardly, and getting the repeatable wrong silently produces an empty or
 * mangled list rather than an error.
 */
@WithJenkins
class ConfigRoundTripTest {

    private static ConfigSubstitutionStep sampleStep() {
        Substitution literal = new Substitution("Logging.LogLevel.Default");
        literal.setValue("Warning");
        literal.setType("string");

        Substitution credential = new Substitution("appSettings.BankApi:Key");
        credential.setCredentialsId("bank-api-key");

        TargetGroup group = new TargetGroup(
                List.of("**/web.config", "src/**/appsettings.json"), List.of(literal, credential));
        group.setFormat("xml");

        ConfigSubstitutionStep step = new ConfigSubstitutionStep(List.of(group));
        step.setDryRun(true);
        step.setNoMatchBehavior("warn");
        step.setMissingPathBehavior("ignore");
        step.setAcknowledgeSecretLifecycle(true);
        return step;
    }

    @Test
    @DisplayName("every configured field survives a real form round trip")
    void stepSurvivesAFormRoundTrip(JenkinsRule j) throws Exception {
        ConfigSubstitutionStep after = new StepConfigTester(j).configRoundTrip(sampleStep());

        assertTrue(after.isDryRun(), "dryRun");
        assertEquals("warn", after.getNoMatchBehavior());
        assertEquals("ignore", after.getMissingPathBehavior());
        assertTrue(after.isAcknowledgeSecretLifecycle(), "acknowledgeSecretLifecycle");

        assertEquals(1, after.getTargets().size(), "target group count");
        TargetGroup group = after.getTargets().get(0);
        assertEquals("xml", group.getFormat());

        // The risky binding: a List<String> rendered through a repeatable.
        assertEquals(
                List.of("**/web.config", "src/**/appsettings.json"),
                group.getFiles(),
                "file patterns must survive the round trip in order");

        assertEquals(2, group.getSubstitutions().size(), "substitution count");
        Substitution literal = group.getSubstitutions().get(0);
        assertEquals("Logging.LogLevel.Default", literal.getPath());
        assertEquals("Warning", literal.getValue());
        assertEquals("string", literal.getType());

        Substitution credential = group.getSubstitutions().get(1);
        assertEquals("appSettings.BankApi:Key", credential.getPath());
        assertEquals("bank-api-key", credential.getCredentialsId());
    }

    @Test
    @DisplayName("defaults survive a round trip without being invented or dropped")
    void defaultsSurviveARoundTrip(JenkinsRule j) throws Exception {
        Substitution substitution = new Substitution("Port");
        substitution.setValue("9090");
        TargetGroup group = new TargetGroup(List.of("appsettings.json"), List.of(substitution));
        ConfigSubstitutionStep step = new ConfigSubstitutionStep(List.of(group));

        ConfigSubstitutionStep after = new StepConfigTester(j).configRoundTrip(step);

        assertEquals("fail", after.getNoMatchBehavior(), "default no-match policy");
        assertEquals("fail", after.getMissingPathBehavior(), "default missing-path policy");
        assertEquals("auto", after.getTargets().get(0).getFormat(), "default format");
        assertEquals("auto", after.getTargets().get(0).getSubstitutions().get(0).getType(), "default type");
        assertEquals(List.of("appsettings.json"), after.getTargets().get(0).getFiles());
    }

    @Test
    @DisplayName("multiple target groups keep their order and contents")
    void multipleGroupsSurvive(JenkinsRule j) throws Exception {
        Substitution xmlSub = new Substitution("appSettings.ApiUrl");
        xmlSub.setValue("https://production.example");
        TargetGroup xmlGroup = new TargetGroup(List.of("**/web.config"), List.of(xmlSub));
        xmlGroup.setFormat("xml");

        Substitution jsonSub = new Substitution("Logging.LogLevel.Default");
        jsonSub.setValue("Warning");
        TargetGroup jsonGroup = new TargetGroup(List.of("**/appsettings.json"), List.of(jsonSub));
        jsonGroup.setFormat("json");

        ConfigSubstitutionStep after = new StepConfigTester(j)
                .configRoundTrip(new ConfigSubstitutionStep(List.of(xmlGroup, jsonGroup)));

        assertEquals(2, after.getTargets().size());
        assertEquals("xml", after.getTargets().get(0).getFormat());
        assertEquals("json", after.getTargets().get(1).getFormat());
        assertEquals(List.of("**/web.config"), after.getTargets().get(0).getFiles());
        assertEquals(List.of("**/appsettings.json"), after.getTargets().get(1).getFiles());
    }

    @Test
    @DisplayName("untouched optional fields do not survive as empty strings")
    void blankOptionalFieldsBecomeNull(JenkinsRule j) throws Exception {
        // An HTML text input always submits something, so a field the user never filled arrives as "".
        // If that is kept, the Snippet Generator emits noise like credentialsId: '' for fields nobody
        // set. Verified in the real Snippet Generator before being pinned here.
        Substitution substitution = new Substitution("Port");
        substitution.setValue("9090");
        substitution.setCredentialsId("");

        TargetGroup group = new TargetGroup(List.of("appsettings.json"), List.of(substitution));
        ConfigSubstitutionStep after =
                new StepConfigTester(j).configRoundTrip(new ConfigSubstitutionStep(List.of(group)));

        Substitution result = after.getTargets().get(0).getSubstitutions().get(0);
        assertEquals("9090", result.getValue());
        assertEquals(null, result.getCredentialsId(), "a blank credentialsId must not survive as \"\"");

        // And the mirror case: a credential set, value left blank.
        Substitution credentialOnly = new Substitution("appSettings.Key");
        credentialOnly.setCredentialsId("some-credential");
        credentialOnly.setValue("");

        ConfigSubstitutionStep afterCredential = new StepConfigTester(j)
                .configRoundTrip(new ConfigSubstitutionStep(
                        List.of(new TargetGroup(List.of("web.config"), List.of(credentialOnly)))));

        Substitution credentialResult =
                afterCredential.getTargets().get(0).getSubstitutions().get(0);
        assertEquals("some-credential", credentialResult.getCredentialsId());
        assertEquals(null, credentialResult.getValue(), "a blank value must not survive as \"\"");
    }

    @Test
    @DisplayName("dropdowns offer exactly the documented options")
    void dropdownsOfferDocumentedOptions(JenkinsRule j) {
        ConfigSubstitutionStep.DescriptorImpl stepDescriptor =
                j.jenkins.getDescriptorByType(ConfigSubstitutionStep.DescriptorImpl.class);
        assertEquals(
                List.of("fail", "warn", "ignore"),
                values(stepDescriptor.doFillNoMatchBehaviorItems(null)),
                "no-match policy options");
        assertEquals(
                List.of("fail", "warn", "ignore"),
                values(stepDescriptor.doFillMissingPathBehaviorItems(null)),
                "missing-path policy options");

        TargetGroup.DescriptorImpl groupDescriptor =
                j.jenkins.getDescriptorByType(TargetGroup.DescriptorImpl.class);
        assertEquals(List.of("auto", "json", "xml"), values(groupDescriptor.doFillFormatItems(null)));

        Substitution.DescriptorImpl substitutionDescriptor =
                j.jenkins.getDescriptorByType(Substitution.DescriptorImpl.class);
        assertEquals(
                List.of("auto", "string", "number", "boolean", "null"),
                values(substitutionDescriptor.doFillTypeItems(null)),
                "value type options must match Section 4.5 exactly");
    }

    @Test
    @DisplayName("form validation endpoints refuse an unauthorised caller")
    void formValidationRequiresPermission(JenkinsRule j) throws Exception {
        // Every doCheck/doFill endpoint is reachable by anyone who can guess the URL, so each one
        // checks permission even when it only inspects a string. Verified rather than assumed,
        // because the Jenkins security scan flags exactly this and a missing check is silent.
        lockDown(j);

        Substitution.DescriptorImpl substitutions =
                j.jenkins.getDescriptorByType(Substitution.DescriptorImpl.class);
        ConfigSubstitutionStep.DescriptorImpl step =
                j.jenkins.getDescriptorByType(ConfigSubstitutionStep.DescriptorImpl.class);
        TargetGroup.DescriptorImpl groups = j.jenkins.getDescriptorByType(TargetGroup.DescriptorImpl.class);

        try (ACLContext ignored = ACL.as2(Jenkins.ANONYMOUS2)) {
            assertThrows(
                    AccessDeniedException.class,
                    () -> substitutions.doCheckPath(null, "anything"),
                    "doCheckPath must refuse an unauthorised caller");
            assertThrows(
                    AccessDeniedException.class,
                    () -> substitutions.doCheckValue(null, "v", ""),
                    "doCheckValue must refuse an unauthorised caller");
            assertThrows(
                    AccessDeniedException.class,
                    () -> substitutions.doFillTypeItems(null),
                    "doFillTypeItems must refuse an unauthorised caller");
            assertThrows(
                    AccessDeniedException.class,
                    () -> step.doFillNoMatchBehaviorItems(null),
                    "doFillNoMatchBehaviorItems must refuse an unauthorised caller");
            assertThrows(
                    AccessDeniedException.class,
                    () -> step.doFillMissingPathBehaviorItems(null),
                    "doFillMissingPathBehaviorItems must refuse an unauthorised caller");
            assertThrows(
                    AccessDeniedException.class,
                    () -> groups.doFillFormatItems(null),
                    "doFillFormatItems must refuse an unauthorised caller");
        }
    }

    /** Leaves the instance secured so descriptors are queried without ADMINISTER. */
    private static void lockDown(JenkinsRule j) throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(
                new MockAuthorizationStrategy().grant(Jenkins.READ).everywhere().toAuthenticated());
    }

    @Test
    @DisplayName("the credential picker does not enumerate credentials for an anonymous caller")
    void credentialPickerRespectsPermissions(JenkinsRule j) throws Exception {
        lockDown(j);

        Substitution.DescriptorImpl descriptor =
                j.jenkins.getDescriptorByType(Substitution.DescriptorImpl.class);

        ListBoxModel items;
        try (ACLContext ignored = ACL.as2(Jenkins.ANONYMOUS2)) {
            items = descriptor.doFillCredentialsIdItems(null, "already-selected");
        }

        assertNotNull(items);
        // Only the currently selected value may come back; nothing may be enumerated.
        assertTrue(
                values(items).stream().allMatch(v -> v == null || v.isEmpty() || "already-selected".equals(v)),
                "an unauthorised caller must not receive a list of credential IDs, got: " + values(items));
    }

    private static List<String> values(ListBoxModel model) {
        return model.stream().map(option -> option.value).toList();
    }
}
