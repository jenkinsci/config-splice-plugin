package io.jenkins.plugins.configsplice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.configsplice.engine.SpliceException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Which XML path grammar a path is routed to (SRS section 6.1 rules 2 to 4).
 *
 * <p>The two grammars coexist, so dispatch is the part most likely to break a working job: a path that
 * used to reach a .NET collection must keep reaching it, and adding generic traversal must not change
 * the meaning of anything already written. These tests exercise the real callable end to end rather
 * than the locators directly, because the routing decision lives in the callable.
 */
@WithJenkins
class XmlPathDispatchTest {

    /** Distinctive enough that its appearance in any diagnostic would be unmistakable. */
    private static final String SENTINEL_VALUE = "s3cr3t-sentinel-value";

    private static final String WEB_CONFIG = String.join("\r\n",
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>",
            "<configuration>",
            "  <appSettings>",
            "    <!-- keep me -->",
            "    <add key=\"ApiUrl\" value=\"https://staging.example\" />",
            "  </appSettings>",
            "  <system.webServer>",
            "    <security>",
            "      <requestFiltering>",
            "        <requestLimits maxAllowedContentLength=\"30000000\" />",
            "      </requestFiltering>",
            "    </security>",
            "    <handlers>",
            "      <add name=\"first\" />",
            "      <add name=\"second\" />",
            "    </handlers>",
            "  </system.webServer>",
            "  <branding>",
            "    <title>Staging Portal</title>",
            "  </branding>",
            "</configuration>",
            "");

    @TempDir
    private Path workspace;

    private Path writeConfig() throws IOException {
        Path file = workspace.resolve("web.config");
        Files.write(file, WEB_CONFIG.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private Map<String, Object> substitute(String path, String value, Wire.Behavior missingPathBehavior)
            throws Exception {

        SubstitutionRequest.Sub sub =
                new SubstitutionRequest.Sub(path, ResolvedValue.literal(value), Wire.ValueType.AUTO);
        SubstitutionRequest request = new SubstitutionRequest(
                List.of(new SubstitutionRequest.Group(1, List.of("web.config"), Wire.Format.XML, List.of(sub))),
                false,
                Wire.Behavior.FAIL,
                missingPathBehavior);
        return new SubstitutionCallable(request).invoke(workspace.toFile(), null);
    }

    private Map<String, Object> substitute(String path, String value) throws Exception {
        return substitute(path, value, Wire.Behavior.FAIL);
    }

    private String contents(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("a path starting with a .NET collection name still uses the shorthand")
    void shorthandPathsAreUnaffected() throws Exception {
        Path file = writeConfig();

        substitute("appSettings.ApiUrl", "https://prod.example");

        String result = contents(file);
        assertTrue(
                result.contains("<add key=\"ApiUrl\" value=\"https://prod.example\" />"),
                "the shorthand should still resolve key= to value=");
        assertTrue(result.contains("<!-- keep me -->"), "comments survive");
        assertTrue(result.startsWith("<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n"), "prologue and CRLF survive");
    }

    @Test
    @DisplayName("a generic path reaches an attribute no shorthand can address")
    void genericAttributePath() throws Exception {
        Path file = writeConfig();

        substitute(
                "configuration.'system.webServer'.security.requestFiltering.requestLimits"
                        + ".@maxAllowedContentLength",
                "60000000");

        String result = contents(file);
        assertTrue(result.contains("maxAllowedContentLength=\"60000000\""), result);
        // Everything else is byte-identical, including the untouched appSettings entry.
        assertEquals(
                WEB_CONFIG.replace("30000000", "60000000"),
                result,
                "only the targeted attribute may change");
    }

    @Test
    void genericTextPath() throws Exception {
        Path file = writeConfig();

        substitute("configuration.branding.title.#text", "Production Portal");

        assertEquals(
                WEB_CONFIG.replace("<title>Staging Portal</title>", "<title>Production Portal</title>"),
                contents(file));
    }

    @Test
    @DisplayName("an occurrence index picks one of several same-name siblings")
    void genericIndexedPath() throws Exception {
        Path file = writeConfig();

        substitute("configuration.'system.webServer'.handlers.add[1].@name", "renamed");

        String result = contents(file);
        assertTrue(result.contains("<add name=\"first\" />"), "the sibling not addressed is untouched");
        assertTrue(result.contains("<add name=\"renamed\" />"), result);
    }

    @Test
    @DisplayName("ambiguity fails the build even when missing paths are tolerated")
    void ambiguityIsNotAMissingPath() throws Exception {
        Path file = writeConfig();

        // IGNORE applies to PATH_MISSING only. Ambiguity means we do not know what the user meant, so
        // it must never be quietly downgraded into "nothing to do".
        IOException thrown = assertThrows(
                IOException.class,
                () -> substitute(
                        "configuration.'system.webServer'.handlers.add.@name",
                        SENTINEL_VALUE,
                        Wire.Behavior.IGNORE));

        assertTrue(thrown.getMessage().contains("add[0]"), thrown.getMessage());
        assertValueFree(thrown);
        assertEquals(WEB_CONFIG, contents(file), "a failed run must not have written anything");
    }

    @Test
    @DisplayName("a genuinely missing generic path is tolerated when configured to be")
    void missingGenericPathIsTolerated() throws Exception {
        Path file = writeConfig();

        substitute("configuration.branding.subtitle.#text", "x", Wire.Behavior.IGNORE);

        assertEquals(WEB_CONFIG, contents(file), "a missing path never creates anything");
    }

    @Test
    @DisplayName("a malformed generic path is a syntax error, not a missing path")
    void malformedPathIsNotTolerated() throws Exception {
        Path file = writeConfig();

        IOException thrown = assertThrows(
                IOException.class,
                () -> substitute("configuration.branding.title", SENTINEL_VALUE, Wire.Behavior.IGNORE));

        assertTrue(thrown.getMessage().contains("#text"), "should name the selectors: " + thrown.getMessage());
        assertValueFree(thrown);
        assertEquals(WEB_CONFIG, contents(file), "a failed run must not have written anything");
    }

    /**
     * The channel boundary rewrites {@link SpliceException} into a plain {@link IOException} carrying
     * an already value-free message and no cause, so a replacement value must not appear anywhere in
     * what reaches the build log (ADR-003).
     */
    private static void assertValueFree(IOException thrown) {
        assertNull(thrown.getCause(), "no cause may cross the channel");
        assertFalse(thrown.getMessage().contains(SENTINEL_VALUE), "the message must not echo the value");
    }
}
