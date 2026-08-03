package io.jenkins.plugins.configsplice.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.FilePath;
import hudson.remoting.VirtualChannel;
import hudson.util.Secret;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import jenkins.model.Jenkins;
import jenkins.agents.ControllerToAgentFileCallable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Decision gate 2 (SRS v0.6 section 20.2): what actually crosses the remoting channel when a
 * resolved credential is sent to an agent?
 *
 * <p>SRS section 12.4 forbids assuming that {@link Secret} guarantees ciphertext under Java
 * serialization. This gate settles it by measurement rather than by reading the class, because the
 * answer determines what the plugin may promise users about where their secrets travel.
 *
 * <p>The sentinel below is a fake value with a distinctive shape, searched for byte-by-byte in the
 * serialized payloads.
 */
@WithJenkins
class Gate2EvidenceTest {

    private static final String SENTINEL = "s3cr3t-sentinel-9f2a7c1e";

    private static final List<String> OBSERVATIONS = new ArrayList<>();

    private static void record(String probe, String finding) {
        OBSERVATIONS.add(String.format("  %-44s %s", probe, finding));
    }

    /** Serializes with plain Java serialization, exactly as Jenkins remoting does for a callable. */
    private static byte[] javaSerialize(Object object) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(buffer)) {
            out.writeObject(object);
        }
        return buffer.toByteArray();
    }

    /** True if the sentinel appears as raw bytes anywhere in the payload. */
    private static boolean containsPlaintext(byte[] payload, String needle) {
        byte[] target = needle.getBytes(StandardCharsets.UTF_8);
        outer:
        for (int i = 0; i + target.length <= payload.length; i++) {
            for (int j = 0; j < target.length; j++) {
                if (payload[i + j] != target[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    @Test
    @DisplayName("gate 2: does Secret encrypt itself under Java serialization?")
    void secretUnderJavaSerialization(JenkinsRule j) throws Exception {
        Secret secret = Secret.fromString(SENTINEL);
        assertEquals(SENTINEL, secret.getPlainText(), "sanity: the Secret holds our sentinel");

        String encryptedForm = secret.getEncryptedValue();
        assertNotNull(encryptedForm, "sanity: a ConfidentialStore is available");
        assertTrue(
                !encryptedForm.contains(SENTINEL),
                "sanity: the at-rest encrypted form must not contain the plaintext");

        byte[] payload = javaSerialize(secret);
        boolean leaks = containsPlaintext(payload, SENTINEL);

        record(
                "Secret via Java serialization",
                leaks
                        ? "PLAINTEXT PRESENT in the serialized bytes"
                        : "plaintext absent (serialized form is encrypted)");
        record(
                "Secret at-rest form (getEncryptedValue)",
                "encrypted, plaintext absent");

        // Recorded, not asserted either way: the gate exists to establish the fact, and the
        // threat model in ADR-003 is written against whatever it turns out to be.
        System.out.println("gate2: Secret serialization leaks plaintext = " + leaks);
    }

    @Test
    @DisplayName("gate 2: does a callable carrying a Secret differ from one carrying a String?")
    void callablePayloadComparison(JenkinsRule j) throws Exception {
        byte[] secretPayload = javaSerialize(new SecretCarryingCallable(Secret.fromString(SENTINEL)));
        byte[] stringPayload = javaSerialize(new StringCarryingCallable(SENTINEL));

        boolean secretLeaks = containsPlaintext(secretPayload, SENTINEL);
        boolean stringLeaks = containsPlaintext(stringPayload, SENTINEL);

        assertTrue(stringLeaks, "sanity: a plain String field must obviously carry its plaintext");

        record(
                "callable with a Secret field",
                secretLeaks ? "PLAINTEXT PRESENT in the callable payload" : "plaintext absent");
        record("callable with a String field", "plaintext present (expected; used as the control)");
        record(
                "does Secret protect the wire vs a String?",
                secretLeaks ? "NO - no wire-level difference" : "yes - payload is encrypted");
    }

    @Test
    @DisplayName("gate 2: can a Secret leak through ordinary string conversion?")
    void accidentalStringConversion(JenkinsRule j) {
        Secret secret = Secret.fromString(SENTINEL);

        // These are the shapes an accidental log statement takes. If any exposes the plaintext, then
        // SRS 12.5's "never log a value" cannot rely on the type alone and must be enforced by never
        // letting a Secret near a log call.
        boolean viaToString = secret.toString().contains(SENTINEL);
        boolean viaConcat = ("value=" + secret).contains(SENTINEL);
        boolean viaValueOf = String.valueOf(secret).contains(SENTINEL);

        record(
                "Secret.toString()",
                viaToString ? "EXPOSES plaintext" : "masks plaintext");
        record(
                "string concatenation / String.valueOf",
                (viaConcat || viaValueOf) ? "EXPOSES plaintext" : "masks plaintext");
        System.out.println("gate2: Secret.toString exposes plaintext = " + viaToString);
    }

    @Test
    @DisplayName("gate 2: a real agent round trip over a real remoting channel")
    void realAgentRoundTrip(JenkinsRule j) throws Exception {
        FilePath agentRoot = j.createOnlineSlave().getRootPath();
        assertNotNull(agentRoot, "agent must be online");

        FilePath workspace = agentRoot.child("gate2");
        workspace.mkdirs();

        // The agent has no access to the controller's $JENKINS_HOME/secrets, so if it can recover
        // the plaintext then the plaintext must have crossed the channel.
        String recovered = workspace.act(new SecretCarryingCallable(Secret.fromString(SENTINEL)));

        assertEquals(SENTINEL, recovered, "the agent must be able to use the secret");
        record(
                "agent recovers plaintext without controller keys",
                "yes - confirms the plaintext crosses the channel");

        workspace.deleteRecursive();
    }

    /** Mirrors the shape the production write callable will have. */
    private static final class SecretCarryingCallable
            implements ControllerToAgentFileCallable<String> {

        private static final long serialVersionUID = 1L;

        private final Secret secret;

        SecretCarryingCallable(Secret secret) {
            this.secret = secret;
        }

        @Override
        public String invoke(File file, VirtualChannel channel) throws IOException {
            return secret.getPlainText();
        }
    }

    /** Control case: the naive implementation, for comparison. */
    private static final class StringCarryingCallable
            implements ControllerToAgentFileCallable<String>, Serializable {

        private static final long serialVersionUID = 1L;

        private final String secret;

        StringCarryingCallable(String secret) {
            this.secret = secret;
        }

        @Override
        public String invoke(File file, VirtualChannel channel) throws IOException {
            return secret;
        }
    }

    @AfterAll
    static void writeEvidence() throws IOException {
        List<String> report = new ArrayList<>();
        report.add("=== Gate 2 evidence: secret handling over remoting ===");
        report.add("  jenkins: " + Jenkins.VERSION + " / JDK "
                + System.getProperty("java.version"));
        report.addAll(OBSERVATIONS);

        Path directory = Path.of("target", "gate-evidence");
        Files.createDirectories(directory);
        Files.write(directory.resolve("gate-2-secret-remoting.txt"), report);

        report.forEach(System.out::println);
    }
}
