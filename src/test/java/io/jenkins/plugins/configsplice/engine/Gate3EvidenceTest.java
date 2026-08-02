package io.jenkins.plugins.configsplice.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Decision gate 3 (SRS v0.6 section 20.3): does workspace confinement actually hold against links,
 * junctions and reparse points on the supported Windows filesystem?
 *
 * <p>The interesting cases are the ones where a path is lexically innocent. {@code config/web.config}
 * contains no {@code ..} and is not absolute, yet resolves outside the workspace the moment
 * {@code config} is a junction. This gate exists to prove the guard catches that, and to record what
 * the JDK's attribute APIs report for each link flavour — because an implementation that screened on
 * those attributes instead of on the resolved path would have a hole.
 */
class Gate3EvidenceTest {

    private static final List<String> OBSERVATIONS = new ArrayList<>();

    private static void record(String probe, String finding) {
        OBSERVATIONS.add(String.format("  %-46s %s", probe, finding));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static Path seedFile(Path directory, String name, String content) throws IOException {
        Files.createDirectories(directory);
        Path file = directory.resolve(name);
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    /** Creates a Windows directory junction. Needs no elevation, unlike a symbolic link. */
    private static boolean createJunction(Path link, Path target) throws Exception {
        if (!isWindows()) {
            return false;
        }
        Process process = new ProcessBuilder(
                        "cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        return finished && process.exitValue() == 0 && Files.exists(link);
    }

    /** Symbolic links need elevation or Developer Mode on Windows, so this may legitimately fail. */
    private static boolean createSymbolicLink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            return false;
        }
    }

    @Test
    @DisplayName("gate 3: an ordinary workspace file is accepted")
    void ordinaryFileIsAccepted(@TempDir Path workspace) throws Exception {
        seedFile(workspace.resolve("src"), "web.config", "<configuration/>");

        Path resolved = WorkspaceGuard.requireConfinedRegularFile(workspace, "src/web.config");

        assertTrue(resolved.startsWith(workspace.toRealPath()), "must resolve inside the workspace");
        record("ordinary relative file", "accepted");
    }

    @Test
    @DisplayName("gate 3: absolute paths and lexical traversal are refused")
    void absoluteAndTraversalRefused(@TempDir Path workspace, @TempDir Path outside) throws Exception {
        seedFile(workspace, "keep.config", "inside");
        Path secret = seedFile(outside, "outside.config", "outside");

        SpliceException absolute = assertThrows(
                SpliceException.class,
                () -> WorkspaceGuard.requireConfinedRegularFile(workspace, secret.toString()));
        assertEquals(ErrorCode.WORKSPACE_ESCAPE, absolute.code());

        SpliceException traversal = assertThrows(
                SpliceException.class,
                () -> WorkspaceGuard.requireConfinedRegularFile(
                        workspace, "../" + outside.getFileName() + "/outside.config"));
        assertEquals(ErrorCode.WORKSPACE_ESCAPE, traversal.code());

        record("absolute path", "refused with WORKSPACE_ESCAPE");
        record("lexical .. traversal", "refused with WORKSPACE_ESCAPE");
    }

    @Test
    @DisplayName("gate 3: a junction escaping the workspace is refused, and what the JDK reports")
    void escapingJunctionRefused(@TempDir Path workspace, @TempDir Path outside) throws Exception {
        seedFile(outside, "web.config", "<escaped/>");
        Path junction = workspace.resolve("config");

        if (!createJunction(junction, outside)) {
            record("directory junction", isWindows()
                    ? "could not create a junction; probe INCONCLUSIVE"
                    : "not applicable on this platform");
            return;
        }

        // The path is lexically innocent: no "..", not absolute. Only real-path resolution catches it.
        SpliceException thrown = assertThrows(
                SpliceException.class,
                () -> WorkspaceGuard.requireConfinedRegularFile(workspace, "config/web.config"));
        assertEquals(ErrorCode.WORKSPACE_ESCAPE, thrown.code());
        record("escaping junction, lexically innocent path", "refused with WORKSPACE_ESCAPE");

        // The measurement that justifies resolving real paths rather than screening attributes.
        BasicFileAttributes attributes =
                Files.readAttributes(junction, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        record(
                "JDK view of a junction",
                String.format(
                        "isSymbolicLink=%s isDirectory=%s isOther=%s",
                        Files.isSymbolicLink(junction), attributes.isDirectory(), attributes.isOther()));
        record(
                "would an isSymbolicLink() screen catch it?",
                Files.isSymbolicLink(junction) ? "yes" : "NO - junctions are not symbolic links");
    }

    @Test
    @DisplayName("gate 3: a junction staying inside the workspace still resolves inside it")
    void internalJunctionStaysConfined(@TempDir Path workspace) throws Exception {
        Path real = workspace.resolve("real");
        seedFile(real, "web.config", "<inside/>");
        Path junction = workspace.resolve("alias");

        if (!createJunction(junction, real)) {
            record("internal junction", isWindows() ? "could not create; INCONCLUSIVE" : "not applicable");
            return;
        }

        Path resolved = WorkspaceGuard.requireConfinedRegularFile(workspace, "alias/web.config");

        assertTrue(resolved.startsWith(workspace.toRealPath()), "must stay inside the workspace");
        assertEquals(
                real.toRealPath().resolve("web.config"),
                resolved,
                "the real path must be reported, not the aliased one");
        record("internal junction", "accepted; resolved to its real path inside the workspace");
    }

    @Test
    @DisplayName("gate 3: a symlinked target file is refused even when it points inside")
    void symlinkedTargetRefused(@TempDir Path workspace) throws Exception {
        Path real = seedFile(workspace, "real.config", "<inside/>");
        Path link = workspace.resolve("link.config");

        if (!createSymbolicLink(link, real)) {
            record("symbolic link to a file", "could not create (needs elevation); probe INCONCLUSIVE");
            return;
        }

        // Refused even though it stays inside: the atomic replacement renames over the target, which
        // would silently convert the user's symlink into a regular file.
        SpliceException thrown = assertThrows(
                SpliceException.class,
                () -> WorkspaceGuard.requireConfinedRegularFile(workspace, "link.config"));
        assertEquals(ErrorCode.WORKSPACE_ESCAPE, thrown.code());
        record("symlinked target file (points inside)", "refused - rename would replace the link itself");
    }

    @Test
    @DisplayName("gate 3: a symlink escaping the workspace is refused")
    void escapingSymlinkRefused(@TempDir Path workspace, @TempDir Path outside) throws Exception {
        Path secret = seedFile(outside, "outside.config", "<escaped/>");
        Path link = workspace.resolve("sneaky.config");

        if (!createSymbolicLink(link, secret)) {
            record("symbolic link escaping the workspace", "could not create; probe INCONCLUSIVE");
            return;
        }

        SpliceException thrown = assertThrows(
                SpliceException.class,
                () -> WorkspaceGuard.requireConfinedRegularFile(workspace, "sneaky.config"));
        assertEquals(ErrorCode.WORKSPACE_ESCAPE, thrown.code());
        record("symbolic link escaping the workspace", "refused with WORKSPACE_ESCAPE");
    }

    @Test
    @DisplayName("gate 3: directories and missing files are refused distinctly")
    void directoriesAndMissingFiles(@TempDir Path workspace) throws Exception {
        Files.createDirectories(workspace.resolve("adirectory"));

        SpliceException directory = assertThrows(
                SpliceException.class,
                () -> WorkspaceGuard.requireConfinedRegularFile(workspace, "adirectory"));
        assertEquals(ErrorCode.WORKSPACE_ESCAPE, directory.code());

        SpliceException missing = assertThrows(
                SpliceException.class,
                () -> WorkspaceGuard.requireConfinedRegularFile(workspace, "nope.config"));
        assertEquals(ErrorCode.FILE_NOT_FOUND, missing.code(), "a missing file is not an escape attempt");

        record("directory as target", "refused with WORKSPACE_ESCAPE");
        record("missing file", "refused with FILE_NOT_FOUND (distinct from escape)");
    }

    @AfterAll
    static void writeEvidence() throws IOException {
        List<String> report = new ArrayList<>();
        report.add("=== Gate 3 evidence: workspace confinement against links ===");
        report.add("  platform: " + System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " / JDK " + System.getProperty("java.version"));
        report.addAll(OBSERVATIONS);

        Path directory = Path.of("target", "gate-evidence");
        Files.createDirectories(directory);
        Files.write(directory.resolve("gate-3-workspace-confinement.txt"), report);

        report.forEach(System.out::println);
    }
}
