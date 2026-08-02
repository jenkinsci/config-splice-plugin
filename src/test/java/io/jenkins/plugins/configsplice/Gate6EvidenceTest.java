package io.jenkins.plugins.configsplice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Decision gate 6 (SRS v0.6 section 20.6): can a test deterministically mutate a file inside the
 * window between planning and the pre-commit digest check, without that seam existing in the public
 * API or doing anything in production?
 *
 * <p>Without a seam, SRS acceptance criterion 31 is untestable — the window is microseconds wide and
 * unreachable from outside. Untestable criteria get quietly skipped, so the seam is a requirement in
 * its own right.
 */
@WithJenkins
class Gate6EvidenceTest {

    private static final List<String> OBSERVATIONS = new ArrayList<>();

    private static void record(String probe, String finding) {
        OBSERVATIONS.add(String.format("  %-50s %s", probe, finding));
    }

    private static final String ORIGINAL = "{\n  \"Port\": 8080\n}\n";

    @AfterEach
    void clearSeam() {
        SubstitutionCallable.setPreCommitHookForTests(null);
    }

    private static SubstitutionRequest requestFor(String path, String value, boolean dryRun) {
        SubstitutionRequest.Sub substitution =
                new SubstitutionRequest.Sub(path, ResolvedValue.literal(value), Wire.ValueType.AUTO);
        SubstitutionRequest.Group group = new SubstitutionRequest.Group(
                1, List.of("appsettings.json"), Wire.Format.JSON, List.of(substitution));
        return new SubstitutionRequest(
                List.of(group), dryRun, Wire.Behavior.FAIL, Wire.Behavior.FAIL);
    }

    @Test
    @DisplayName("gate 6: the seam is inert when no test installs a hook")
    void seamIsInertByDefault(JenkinsRule j, @TempDir Path workspace) throws Exception {
        Files.write(workspace.resolve("appsettings.json"), ORIGINAL.getBytes(StandardCharsets.UTF_8));

        Map<String, Object> result = new SubstitutionCallable(requestFor("Port", "9090", false))
                .invoke(workspace.toFile(), null);

        assertEquals(1, result.get("filesChanged"));
        assertTrue(
                Files.readString(workspace.resolve("appsettings.json")).contains("9090"),
                "the substitution must happen normally with no hook installed");
        record("seam absent (production path)", "no effect; substitution committed normally");
    }

    @Test
    @DisplayName("gate 6: a file changed inside the window is detected and not overwritten")
    void sourceChangeInsideTheWindowIsDetected(JenkinsRule j, @TempDir Path workspace) throws Exception {
        Path file = workspace.resolve("appsettings.json");
        Files.write(file, ORIGINAL.getBytes(StandardCharsets.UTF_8));

        String interloper = "{\n  \"Port\": 7777\n}\n";
        SubstitutionCallable.setPreCommitHookForTests(target ->
                Files.write(target, interloper.getBytes(StandardCharsets.UTF_8)));

        IOException thrown = assertThrows(
                IOException.class,
                () -> new SubstitutionCallable(requestFor("Port", "9090", false))
                        .invoke(workspace.toFile(), null));

        assertTrue(
                thrown.getMessage().contains("CONFIG_SUBSTITUTION_SOURCE_CHANGED"),
                "must fail with the documented error code, got: " + thrown.getMessage());

        String onDisk = Files.readString(file);
        assertEquals(interloper, onDisk, "the concurrent writer's content must not be overwritten");
        assertFalse(onDisk.contains("9090"), "our replacement must not have been committed");
        record("file mutated between planning and commit", "detected; SOURCE_CHANGED, no overwrite");
        record("concurrent writer's content", "preserved intact");
    }

    @Test
    @DisplayName("gate 6: the seam leaves no temporary file behind when it triggers")
    void noResidueWhenTheSeamTriggers(JenkinsRule j, @TempDir Path workspace) throws Exception {
        Path file = workspace.resolve("appsettings.json");
        Files.write(file, ORIGINAL.getBytes(StandardCharsets.UTF_8));
        SubstitutionCallable.setPreCommitHookForTests(target ->
                Files.write(target, "{\n  \"Port\": 1\n}\n".getBytes(StandardCharsets.UTF_8)));

        assertThrows(
                IOException.class,
                () -> new SubstitutionCallable(requestFor("Port", "9090", false))
                        .invoke(workspace.toFile(), null));

        try (var entries = Files.list(workspace)) {
            List<Path> strays = entries
                    .filter(p -> p.getFileName().toString().endsWith(".configsplice-tmp"))
                    .toList();
            assertEquals(List.of(), strays, "no temporary file may survive a detected source change");
        }
        record("temporary files after a detected change", "none");
    }

    @Test
    @DisplayName("gate 6: the seam is not reachable from the Pipeline API")
    void seamIsNotPublicApi(JenkinsRule j) {
        // Package-private on a package-private class: unreachable from a Pipeline script, from
        // another plugin, and from the step's own public surface.
        assertFalse(
                java.lang.reflect.Modifier.isPublic(SubstitutionCallable.class.getModifiers()),
                "the callable itself must not be public");

        boolean anyPublicHookMethod = java.util.Arrays.stream(SubstitutionCallable.class.getDeclaredMethods())
                .filter(method -> method.getName().toLowerCase(java.util.Locale.ROOT).contains("hook"))
                .anyMatch(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()));
        assertFalse(anyPublicHookMethod, "the seam must not be exposed as a public method");

        record("seam visibility", "package-private on a package-private class");
    }

    @AfterAll
    static void writeEvidence() throws IOException {
        List<String> report = new ArrayList<>();
        report.add("=== Gate 6 evidence: pre-commit test seam ===");
        report.add("  jenkins: " + jenkins.model.Jenkins.VERSION + " / JDK "
                + System.getProperty("java.version"));
        report.addAll(OBSERVATIONS);

        Path directory = Path.of("target", "gate-evidence");
        Files.createDirectories(directory);
        Files.write(directory.resolve("gate-6-precommit-seam.txt"), report);
        report.forEach(System.out::println);
    }
}
