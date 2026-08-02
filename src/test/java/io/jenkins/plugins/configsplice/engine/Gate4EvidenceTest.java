package io.jenkins.plugins.configsplice.engine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Decision gate 4 (SRS v0.6 section 20.4): does a same-directory atomic move actually work, are
 * permissions preserved, and what is the safe fallback when it is not available?
 *
 * <p>Two categories of check, kept deliberately separate:
 *
 * <ul>
 *   <li><b>Safety invariants</b> are asserted on every platform. Whatever the OS does, a failed
 *       replacement must leave the original bytes intact and must not leak a temporary file.</li>
 *   <li><b>Platform behaviour</b> is measured and printed rather than asserted, because the answers
 *       legitimately differ between Windows and Linux and the point of the gate is to find out what
 *       they are.</li>
 * </ul>
 */
class Gate4EvidenceTest {

    private static final byte[] ORIGINAL = "original contents\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] REPLACEMENT = "replaced contents\r\n".getBytes(StandardCharsets.UTF_8);

    private static final List<String> OBSERVATIONS = new ArrayList<>();

    private static void record(String probe, String finding) {
        OBSERVATIONS.add(String.format("  %-46s %s", probe, finding));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static Path seed(Path directory, String name) throws IOException {
        Path file = directory.resolve(name);
        Files.write(file, ORIGINAL);
        return file;
    }

    /** Any leftover file matching the temp naming convention is a leak. */
    private static List<Path> strayTempFiles(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(p -> p.getFileName().toString().endsWith(".configsplice-tmp"))
                    .toList();
        }
    }

    @Test
    @DisplayName("gate 4: same-directory replacement succeeds and leaves no temporary file")
    void sameDirectoryReplacement(@TempDir Path workspace) throws Exception {
        Path target = seed(workspace, "web.config");

        AtomicFileWriter.Outcome outcome = AtomicFileWriter.replace(target, REPLACEMENT);

        assertArrayEquals(REPLACEMENT, Files.readAllBytes(target), "target must hold the new bytes");
        assertEquals(List.of(), strayTempFiles(workspace), "no temporary file may survive a success");

        record(
                "same-directory move",
                outcome.atomicMoveUsed()
                        ? "ATOMIC_MOVE supported and used"
                        : "ATOMIC_MOVE unavailable; fell back to REPLACE_EXISTING");
        record(
                "permissions explicitly transferred",
                outcome.permissionsPreserved()
                        ? "yes (POSIX permissions copied to the replacement)"
                        : "no (replacement inherits the directory ACL at creation)");
    }

    @Test
    @DisplayName("gate 4: the temporary file is a sibling, never the system temp directory")
    void temporaryFileIsASibling(@TempDir Path workspace) throws Exception {
        Path target = seed(workspace, "appsettings.json");
        Path systemTemp = Path.of(System.getProperty("java.io.tmpdir"));

        // Observe the temp file mid-flight by racing a directory listing against a large write.
        byte[] large = new byte[8 * 1024 * 1024];
        List<Path> seen = new ArrayList<>();
        Thread watcher = new Thread(() -> {
            for (int i = 0; i < 2000; i++) {
                try {
                    seen.addAll(strayTempFiles(workspace));
                    if (!seen.isEmpty()) {
                        return;
                    }
                } catch (IOException ignored) {
                    return;
                }
            }
        });
        watcher.start();
        AtomicFileWriter.replace(target, large);
        watcher.join(5000);

        assertEquals(List.of(), strayTempFiles(workspace), "no temporary file may survive");
        if (seen.isEmpty()) {
            record("temporary file location", "not observed mid-flight (write too fast to sample)");
        } else {
            Path observed = seen.get(0);
            assertEquals(workspace, observed.getParent(), "temp must be a sibling of the target");
            assertTrue(
                    !observed.toAbsolutePath().startsWith(systemTemp.toAbsolutePath()),
                    "temp must never be created in the system temp directory");
            record("temporary file location", "sibling of the target, as required");
        }
    }

    @Test
    @DisplayName("gate 4: a read-only target is refused rather than silently unprotected")
    void readOnlyTargetIsRefused(@TempDir Path workspace) throws Exception {
        Path target = seed(workspace, "readonly.config");
        boolean marked = markReadOnly(target);
        if (!marked) {
            record("read-only target", "could not mark read-only on this platform; probe skipped");
            return;
        }

        try {
            SpliceException thrown =
                    assertThrows(SpliceException.class, () -> AtomicFileWriter.replace(target, REPLACEMENT));

            assertEquals(ErrorCode.WRITE_FAILED, thrown.code());
            assertArrayEquals(ORIGINAL, Files.readAllBytes(target), "original must be untouched");
            assertEquals(List.of(), strayTempFiles(workspace), "no temporary file may survive a refusal");
            record("read-only target", "refused with WRITE_FAILED; original intact");
        } finally {
            clearReadOnly(target);
        }
    }

    @Test
    @DisplayName("gate 4: behaviour when another handle holds the target open")
    void targetHeldOpenByAnotherHandle(@TempDir Path workspace) throws Exception {
        // The realistic Windows case: antivirus or IIS holding web.config open while the build runs.
        probeOpenHandle(
                workspace,
                "held-by-inputstream.config",
                "target open via FileInputStream",
                target -> new FileInputStream(target.toFile()));

        probeOpenHandle(
                workspace,
                "held-by-channel.config",
                "target open via FileChannel (READ)",
                target -> java.nio.channels.Channels.newInputStream(
                        FileChannel.open(target, StandardOpenOption.READ)));
    }

    private interface HandleOpener {
        InputStream open(Path target) throws IOException;
    }

    private void probeOpenHandle(Path workspace, String name, String probe, HandleOpener opener)
            throws Exception {
        Path target = seed(workspace, name);

        try (InputStream held = opener.open(target)) {
            assertTrue(held.read() >= 0, "the handle must really be open");

            String finding;
            try {
                AtomicFileWriter.replace(target, REPLACEMENT);
                assertArrayEquals(
                        REPLACEMENT, Files.readAllBytes(target), "a reported success must be real");
                finding = "replacement SUCCEEDED despite the open handle";
            } catch (SpliceException e) {
                assertEquals(ErrorCode.WRITE_FAILED, e.code());
                // The safety invariant is what matters: a refusal must not damage anything.
                assertArrayEquals(
                        ORIGINAL, Files.readAllBytes(target), "original must survive a blocked replace");
                finding = "replacement BLOCKED, failed cleanly with WRITE_FAILED";
            }

            assertEquals(List.of(), strayTempFiles(workspace), "no temporary file may survive");
            record(probe, finding);
        }
    }

    @Test
    @DisplayName("gate 4: a briefly-held handle is survived; a permanently-held one fails promptly")
    void retryNarrowsTheRaceWithoutHanging(@TempDir Path workspace) throws Exception {
        // Transient case: the handle is released while the writer is retrying.
        Path transientTarget = seed(workspace, "transient.config");
        InputStream briefly = new FileInputStream(transientTarget.toFile());
        Thread releaser = new Thread(() -> {
            try {
                Thread.sleep(60);
                briefly.close();
            } catch (Exception ignored) {
                // Nothing to do; the assertion below reports the outcome.
            }
        });
        releaser.start();

        String transientFinding;
        try {
            AtomicFileWriter.replace(transientTarget, REPLACEMENT);
            assertArrayEquals(REPLACEMENT, Files.readAllBytes(transientTarget));
            transientFinding = "recovered once the handle closed";
        } catch (SpliceException e) {
            assertArrayEquals(ORIGINAL, Files.readAllBytes(transientTarget), "original must survive");
            transientFinding = "still failed cleanly (handle outlived the retry budget)";
        } finally {
            releaser.join(5000);
            briefly.close();
        }
        record("briefly-held handle (released after 60ms)", transientFinding);

        // Permanent case. Whether this blocks is platform behaviour, not a requirement: Windows
        // refuses a replacement while any handle is open, while POSIX rename() succeeds and leaves
        // existing readers on the old inode. Asserting either outcome would hard-code one platform.
        // What must hold everywhere is that the call is bounded and leaves nothing damaged.
        Path heldTarget = seed(workspace, "held.config");
        long startedAt = System.nanoTime();
        String heldFinding;
        try (InputStream held = new FileInputStream(heldTarget.toFile())) {
            assertTrue(held.read() >= 0, "the handle must really be open");
            try {
                AtomicFileWriter.replace(heldTarget, REPLACEMENT);
                assertArrayEquals(
                        REPLACEMENT,
                        Files.readAllBytes(heldTarget),
                        "a reported success must really have replaced the content");
                heldFinding = "replacement SUCCEEDED (rename over an open file is permitted here)";
            } catch (SpliceException e) {
                assertEquals(ErrorCode.WRITE_FAILED, e.code());
                assertArrayEquals(
                        ORIGINAL, Files.readAllBytes(heldTarget), "original must survive a refusal");
                heldFinding = "replacement BLOCKED and failed cleanly";
            }
        }
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        assertTrue(
                elapsedMillis < 5_000,
                "the retry budget must stay bounded whatever the outcome, took " + elapsedMillis + "ms");
        assertEquals(List.of(), strayTempFiles(workspace), "no temporary file may survive");
        record("permanently-held handle", heldFinding + ", bounded at " + elapsedMillis + "ms");
    }

    @Test
    @DisplayName("gate 4: repeated replacements leave no residue")
    void repeatedReplacementsLeaveNoResidue(@TempDir Path workspace) throws Exception {
        Path target = seed(workspace, "repeat.config");
        for (int i = 0; i < 50; i++) {
            AtomicFileWriter.replace(target, ("pass " + i).getBytes(StandardCharsets.UTF_8));
        }
        assertArrayEquals("pass 49".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(target));
        assertEquals(List.of(), strayTempFiles(workspace), "no temporary file may accumulate");
        assertEquals(1, Files.list(workspace).count(), "only the target may remain in the directory");
    }

    @Test
    @DisplayName("gate 4: POSIX permissions survive the replacement")
    void posixPermissionsSurvive(@TempDir Path workspace) throws Exception {
        Path target = seed(workspace, "perms.config");
        if (!target.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            record("POSIX permission preservation", "not applicable on this platform (no posix view)");
            return;
        }

        Set<PosixFilePermission> expected = PosixFilePermissions.fromString("rw-r-----");
        Files.setPosixFilePermissions(target, expected);

        AtomicFileWriter.replace(target, REPLACEMENT);

        assertEquals(
                expected,
                Files.getPosixFilePermissions(target),
                "the replacement must carry the original file's mode, not the temp file's");
        record("POSIX permission preservation", "verified: mode rw-r----- survived the move");
    }

    /**
     * Writes the evidence once, after every probe has run.
     *
     * <p>A file rather than stdout: this is an input to the architecture decision record and to the
     * cross-platform CI comparison, so it needs to survive the build as an artifact rather than
     * depend on how the test runner happens to capture console output.
     */
    @org.junit.jupiter.api.AfterAll
    static void writeEvidence() throws IOException {
        List<String> report = new ArrayList<>();
        report.add("=== Gate 4 evidence: atomic replacement ===");
        report.add("  platform: " + System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " / JDK " + System.getProperty("java.version"));
        report.addAll(OBSERVATIONS);

        Path directory = Path.of("target", "gate-evidence");
        Files.createDirectories(directory);
        Files.write(directory.resolve("gate-4-atomic-replacement.txt"), report);

        report.forEach(System.out::println);
    }

    private static boolean markReadOnly(Path target) throws IOException {
        DosFileAttributeView dos = Files.getFileAttributeView(target, DosFileAttributeView.class);
        if (dos != null) {
            dos.setReadOnly(true);
            return true;
        }
        if (target.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("r--r--r--"));
            return true;
        }
        return false;
    }

    private static void clearReadOnly(Path target) {
        try {
            DosFileAttributeView dos = Files.getFileAttributeView(target, DosFileAttributeView.class);
            if (dos != null) {
                dos.setReadOnly(false);
            } else if (target.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-r--r--"));
            }
        } catch (IOException ignored) {
            // Cleanup only; @TempDir removal will deal with what is left.
        }
    }
}
