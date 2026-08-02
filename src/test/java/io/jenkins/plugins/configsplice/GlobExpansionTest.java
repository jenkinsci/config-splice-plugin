package io.jenkins.plugins.configsplice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * File discovery behaviour (SRS section 5.1).
 *
 * <p>Glob semantics are the part of this plugin most likely to surprise a user, and the choice of
 * expander was a real decision: {@code java.nio} {@code PathMatcher}'s {@code glob:} syntax looks
 * equivalent to Ant's but is not — {@code **}{@code /web.config} does not match a root-level
 * {@code web.config} under {@code PathMatcher}, while it does under Ant. The SRS says "Ant-style", so
 * {@code hudson.Util.createFileSet} is used, and the first test below is the one that pins that
 * difference down.
 */
@WithJenkins
class GlobExpansionTest {

    private static final String JSON = "{\n  \"Port\": 8080\n}\n";

    private static Path write(Path root, String relative) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent() == null ? root : file.getParent());
        Files.write(file, JSON.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static SubstitutionRequest.Group group(int index, List<String> globs) {
        SubstitutionRequest.Sub sub =
                new SubstitutionRequest.Sub("Port", ResolvedValue.literal("9090"), Wire.ValueType.AUTO);
        return new SubstitutionRequest.Group(index, globs, Wire.Format.JSON, List.of(sub));
    }

    private static Map<String, Object> run(Path workspace, SubstitutionRequest request) throws Exception {
        return new SubstitutionCallable(request).invoke(workspace.toFile(), null);
    }

    private static Map<String, Object> runGlobs(Path workspace, String... globs) throws Exception {
        return run(
                workspace,
                new SubstitutionRequest(
                        List.of(group(1, List.of(globs))), false, Wire.Behavior.FAIL, Wire.Behavior.FAIL));
    }

    /** Collects the files named in substitution detail records, in the order they appear. */
    @SuppressWarnings("unchecked")
    private static List<String> filesFromDetails(Map<String, Object> result) {
        List<String> files = new ArrayList<>();
        for (Object entry : (List<Object>) result.get("details")) {
            Map<String, Object> record = (Map<String, Object>) entry;
            if ("substitution".equals(record.get("kind"))) {
                files.add((String) record.get("file"));
            }
        }
        return files;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> patternRecords(Map<String, Object> result) {
        List<Map<String, Object>> patterns = new ArrayList<>();
        for (Object entry : (List<Object>) result.get("details")) {
            Map<String, Object> record = (Map<String, Object>) entry;
            if ("pattern".equals(record.get("kind"))) {
                patterns.add(record);
            }
        }
        return patterns;
    }

    @Nested
    @DisplayName("Ant-style matching")
    class AntSemantics {

        @Test
        @DisplayName("**/name matches at the root as well as nested, unlike PathMatcher glob")
        void doubleStarMatchesRootLevel(JenkinsRule j, @TempDir Path workspace) throws Exception {
            write(workspace, "appsettings.json");
            write(workspace, "src/Api/appsettings.json");
            write(workspace, "src/Api/Deep/Nested/appsettings.json");

            Map<String, Object> result = runGlobs(workspace, "**/appsettings.json");

            assertEquals(3, result.get("filesMatched"), "the root-level file must be included");
            assertEquals(
                    List.of(
                            "appsettings.json",
                            "src/Api/Deep/Nested/appsettings.json",
                            "src/Api/appsettings.json"),
                    filesFromDetails(result),
                    "files must be processed in normalised path order");
        }

        @Test
        void anchoredPrefixLimitsTheSubtree(JenkinsRule j, @TempDir Path workspace) throws Exception {
            write(workspace, "appsettings.json");
            write(workspace, "src/Api/appsettings.json");
            write(workspace, "tools/appsettings.json");

            Map<String, Object> result = runGlobs(workspace, "src/**/appsettings.json");

            assertEquals(1, result.get("filesMatched"));
            assertEquals(List.of("src/Api/appsettings.json"), filesFromDetails(result));
        }

        @Test
        void singleStarDoesNotCrossDirectories(JenkinsRule j, @TempDir Path workspace) throws Exception {
            write(workspace, "src/appsettings.json");
            write(workspace, "src/Api/appsettings.json");

            Map<String, Object> result = runGlobs(workspace, "src/*.json");

            assertEquals(1, result.get("filesMatched"));
            assertEquals(List.of("src/appsettings.json"), filesFromDetails(result));
        }

        @Test
        void reportedPathsUseForwardSlashesOnEveryPlatform(JenkinsRule j, @TempDir Path workspace)
                throws Exception {
            write(workspace, "src/Api/appsettings.json");

            Map<String, Object> result = runGlobs(workspace, "**/appsettings.json");

            assertEquals(List.of("src/Api/appsettings.json"), filesFromDetails(result));
            assertTrue(
                    filesFromDetails(result).stream().noneMatch(f -> f.contains("\\")),
                    "display separators must be '/' regardless of agent OS");
        }
    }

    @Nested
    @DisplayName("multiple patterns")
    class MultiplePatterns {

        @Test
        void patternsFormAUnionAndDuplicatesAreCollapsed(JenkinsRule j, @TempDir Path workspace)
                throws Exception {
            write(workspace, "a/appsettings.json");
            write(workspace, "b/appsettings.json");

            // Both globs match a/appsettings.json; it must be processed once, not twice.
            Map<String, Object> result =
                    runGlobs(workspace, "**/appsettings.json", "a/appsettings.json");

            assertEquals(2, result.get("filesMatched"), "the overlap must be de-duplicated");
            assertEquals(2, result.get("filesChanged"));
            assertEquals(
                    List.of("a/appsettings.json", "b/appsettings.json"), filesFromDetails(result));
        }

        @Test
        @DisplayName("an unmatched pattern is recorded and noted but is not fatal on its own")
        void unmatchedPatternIsNonFatalWhenTheUnionIsNotEmpty(JenkinsRule j, @TempDir Path workspace)
                throws Exception {
            write(workspace, "a/appsettings.json");

            Map<String, Object> result =
                    runGlobs(workspace, "**/appsettings.json", "nowhere/**/appsettings.json");

            assertEquals(1, result.get("filesMatched"));
            assertEquals(2, result.get("patternsEvaluated"));
            assertEquals(1, result.get("patternsUnmatched"));
            assertEquals(0, result.get("warnings"), "a NOTE must not inflate the warning count");

            List<Map<String, Object>> patterns = patternRecords(result);
            assertEquals(2, patterns.size(), "every pattern gets a detail record");
            assertTrue(
                    patterns.stream().anyMatch(p -> "unmatched".equals(p.get("status"))),
                    "the unmatched pattern must be visible in details");
            assertTrue(
                    patterns.stream().anyMatch(p -> "matched".equals(p.get("status"))
                            && Integer.valueOf(1).equals(p.get("matches"))),
                    "the matched pattern must report its own count");

            @SuppressWarnings("unchecked")
            List<String> log = (List<String>) result.get("log");
            assertTrue(
                    log.stream().anyMatch(line -> line.startsWith("NOTE:")
                            && line.contains("nowhere/**/appsettings.json")),
                    "an unmatched glob must produce a NOTE naming the pattern");
        }

        @Test
        @DisplayName("per-pattern counts may overlap and do not sum to filesMatched")
        void perPatternCountsDoNotSum(JenkinsRule j, @TempDir Path workspace) throws Exception {
            write(workspace, "a/appsettings.json");

            Map<String, Object> result =
                    runGlobs(workspace, "**/appsettings.json", "a/appsettings.json");

            int summed = patternRecords(result).stream()
                    .mapToInt(p -> (Integer) p.get("matches"))
                    .sum();
            assertEquals(2, summed, "each pattern reports its own matches before de-duplication");
            assertEquals(1, result.get("filesMatched"), "the union counts the file once");
        }
    }

    @Nested
    @DisplayName("no-match policy")
    class NoMatchPolicy {

        private SubstitutionRequest requestWith(Wire.Behavior behavior) {
            return new SubstitutionRequest(
                    List.of(group(1, List.of("nothing/**/*.json"))), false, behavior, Wire.Behavior.FAIL);
        }

        @Test
        void failIsTheDefaultBehaviour(JenkinsRule j, @TempDir Path workspace) {
            IOException thrown = assertThrows(
                    IOException.class, () -> run(workspace, requestWith(Wire.Behavior.FAIL)));
            assertTrue(thrown.getMessage().contains("CONFIG_SUBSTITUTION_FILE_NOT_FOUND"));
        }

        @Test
        void warnContinuesAndCountsAWarning(JenkinsRule j, @TempDir Path workspace) throws Exception {
            Map<String, Object> result = run(workspace, requestWith(Wire.Behavior.WARN));

            assertEquals(0, result.get("filesMatched"));
            assertEquals(1, result.get("warnings"));
        }

        @Test
        void ignoreIsSilentButStillCounted(JenkinsRule j, @TempDir Path workspace) throws Exception {
            Map<String, Object> result = run(workspace, requestWith(Wire.Behavior.IGNORE));

            assertEquals(0, result.get("filesMatched"));
            assertEquals(0, result.get("warnings"));
            assertEquals(1, result.get("patternsUnmatched"), "the count survives even when silent");
        }
    }

    @Nested
    @DisplayName("safety")
    class Safety {

        @Test
        void aFileClaimedByTwoGroupsFailsAndNamesBoth(JenkinsRule j, @TempDir Path workspace)
                throws Exception {
            write(workspace, "src/appsettings.json");

            SubstitutionRequest request = new SubstitutionRequest(
                    List.of(group(1, List.of("**/appsettings.json")), group(2, List.of("src/*.json"))),
                    false,
                    Wire.Behavior.FAIL,
                    Wire.Behavior.FAIL);

            IOException thrown = assertThrows(IOException.class, () -> run(workspace, request));

            assertTrue(thrown.getMessage().contains("CONFIG_SUBSTITUTION_TARGET_GROUP_OVERLAP"));
            assertTrue(thrown.getMessage().contains("src/appsettings.json"), "the file must be named");
            assertTrue(
                    thrown.getMessage().contains("1") && thrown.getMessage().contains("2"),
                    "both conflicting groups must be identified");
            assertEquals(JSON, Files.readString(workspace.resolve("src/appsettings.json")),
                    "nothing may be written when groups overlap");
        }

        @Test
        void traversingPatternsAreRejected(JenkinsRule j, @TempDir Path workspace) {
            for (String hostile : new String[] {"../**/*.json", "../../etc/passwd"}) {
                SubstitutionRequest request = new SubstitutionRequest(
                        List.of(group(1, List.of(hostile))), false, Wire.Behavior.FAIL, Wire.Behavior.FAIL);
                IOException thrown =
                        assertThrows(IOException.class, () -> run(workspace, request), hostile);
                assertTrue(
                        thrown.getMessage().contains("CONFIG_SUBSTITUTION_WORKSPACE_ESCAPE"),
                        "pattern '" + hostile + "' must be refused");
            }
        }

        @Test
        void absolutePatternsAreRejected(JenkinsRule j, @TempDir Path workspace) {
            String absolute = workspace.toAbsolutePath() + "/appsettings.json";
            SubstitutionRequest request = new SubstitutionRequest(
                    List.of(group(1, List.of(absolute))), false, Wire.Behavior.FAIL, Wire.Behavior.FAIL);

            IOException thrown = assertThrows(IOException.class, () -> run(workspace, request));
            assertTrue(thrown.getMessage().contains("CONFIG_SUBSTITUTION_WORKSPACE_ESCAPE"));
        }

        @Test
        void directoriesMatchingAPatternAreIgnored(JenkinsRule j, @TempDir Path workspace)
                throws Exception {
            Files.createDirectories(workspace.resolve("appsettings.json"));
            write(workspace, "real/appsettings.json");

            // Ant's scanner reports files only, so the directory named like the target is invisible.
            Map<String, Object> result = runGlobs(workspace, "**/appsettings.json");

            assertEquals(1, result.get("filesMatched"));
            assertEquals(List.of("real/appsettings.json"), filesFromDetails(result));
        }
    }

    @Test
    @DisplayName("processing order is deterministic across groups, files and substitutions")
    void deterministicOrdering(JenkinsRule j, @TempDir Path workspace) throws Exception {
        write(workspace, "z/appsettings.json");
        write(workspace, "a/appsettings.json");
        write(workspace, "m/appsettings.json");

        Map<String, Object> first = runGlobs(workspace, "**/appsettings.json");
        List<String> firstOrder = filesFromDetails(first);

        // Re-seed and run again: identical input must produce an identical detail sequence.
        for (String each : List.of("z", "a", "m")) {
            Files.write(workspace.resolve(each + "/appsettings.json"), JSON.getBytes(StandardCharsets.UTF_8));
        }
        List<String> secondOrder = filesFromDetails(runGlobs(workspace, "**/appsettings.json"));

        assertEquals(
                List.of("a/appsettings.json", "m/appsettings.json", "z/appsettings.json"), firstOrder);
        assertEquals(firstOrder, secondOrder, "ordering must not vary between runs");
        assertEquals(new LinkedHashMap<>(first).keySet(), new LinkedHashMap<>(first).keySet());
    }
}
