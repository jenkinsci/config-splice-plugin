package io.jenkins.plugins.configsplice.engine;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Set;

/**
 * Replaces one file's contents through a same-directory temporary file (SRS section 13.2).
 *
 * <p>The guarantee callers depend on is not "the write succeeded" but "the target is either fully
 * old or fully new, never truncated". Writing in place cannot offer that; a crash or a failed write
 * halfway through leaves a config file that parses as neither.
 *
 * <p>The temporary file is a sibling of the target, never in the system temp directory. Two reasons,
 * and the second is the important one: a rename is only atomic within a filesystem, and the temp file
 * transiently holds the same content as the target — including any resolved credential — so it must
 * inherit the workspace's access controls rather than a world-traversable temp directory's.
 */
public final class AtomicFileWriter {

    /** What actually happened, for logging and for the gate 4 evidence record. */
    public record Outcome(boolean atomicMoveUsed, boolean permissionsPreserved) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Owner read/write only, for the window in which the temp file holds secret-bearing content. */
    private static final Set<PosixFilePermission> OWNER_ONLY =
            PosixFilePermissions.fromString("rw-------");

    private AtomicFileWriter() {
    }

    /**
     * Atomically replaces {@code target}'s contents with {@code content}.
     *
     * <p>On any failure the target keeps its original bytes and the temporary file is removed.
     *
     * @throws SpliceException {@link ErrorCode#WRITE_FAILED} on any I/O failure
     */
    public static Outcome replace(Path target, byte[] content) throws SpliceException {
        Path directory = target.toAbsolutePath().getParent();
        if (directory == null) {
            throw new SpliceException(ErrorCode.WRITE_FAILED, "target has no parent directory");
        }
        // getFileName() is null for a path with no name element, such as a filesystem root. Reaching
        // here with one would mean confinement let a directory through, so fail rather than assume.
        Path fileName = target.getFileName();
        if (fileName == null) {
            throw new SpliceException(ErrorCode.WRITE_FAILED, "target has no file name");
        }
        requireWritableRegularFile(target);

        Path temp = null;
        try {
            temp = createRestrictedSibling(directory, fileName.toString());
            writeAndSync(temp, content);
            boolean permissionsPreserved = copyPermissions(target, temp);
            boolean atomic = move(temp, target);
            temp = null; // consumed by the move; nothing left to clean up
            return new Outcome(atomic, permissionsPreserved);
        } catch (IOException e) {
            throw new SpliceException(
                    ErrorCode.WRITE_FAILED,
                    "could not replace '" + target.getFileName() + "'",
                    e);
        } finally {
            deleteQuietly(temp);
        }
    }

    /** Bounded retry for transient Windows sharing violations. Total added delay stays under ~1s. */
    private static final int MOVE_ATTEMPTS = 5;

    private static final long MOVE_BACKOFF_MILLIS = 50;

    /**
     * Moves the temporary file over the target.
     *
     * <p>Two distinct failure modes, and the second was only discovered by measuring (gate 4):
     *
     * <p><b>Atomic move unsupported.</b> Falls back to {@link StandardCopyOption#REPLACE_EXISTING}
     * alone, still a single filesystem operation. What we deliberately never do is delete the target
     * first and then rename: that trades a tiny window for one in which the file does not exist at
     * all, and a failure inside it destroys the user's configuration rather than leaving it intact.
     *
     * <p><b>Target momentarily held open.</b> On Windows any open handle to the target blocks the
     * replacement — measured for both {@code FileInputStream} and {@code FileChannel}. Antivirus
     * scanners and IIS open {@code web.config} routinely and briefly, so a single attempt would turn
     * a background scan into a failed build. Attempts are therefore retried a bounded number of
     * times with a short backoff. This narrows a transient race; it cannot help a handle that is held
     * for the duration, and it is capped so that such a case still fails promptly rather than hanging.
     *
     * @return true if the atomic variant was used
     */
    private static boolean move(Path temp, Path target) throws IOException {
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= MOVE_ATTEMPTS; attempt++) {
            try {
                return moveOnce(temp, target);
            } catch (IOException e) {
                lastFailure = e;
                if (attempt < MOVE_ATTEMPTS) {
                    sleepBriefly(MOVE_BACKOFF_MILLIS * attempt);
                }
            }
        }
        throw lastFailure;
    }

    private static boolean moveOnce(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            return false;
        }
    }

    private static void sleepBriefly(long millis) throws IOException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // A Pipeline abort must not be swallowed into a retry loop.
            throw new IOException("interrupted while waiting to replace the target file", e);
        }
    }

    private static void requireWritableRegularFile(Path target) throws SpliceException {
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new SpliceException(
                    ErrorCode.WRITE_FAILED, "target is not a regular file");
        }
        DosFileAttributeView dos = Files.getFileAttributeView(target, DosFileAttributeView.class);
        if (dos != null) {
            try {
                if (dos.readAttributes().isReadOnly()) {
                    // Clearing the read-only flag on the user's behalf would silently defeat a
                    // deliberate protection, so this is refused rather than worked around.
                    throw new SpliceException(
                            ErrorCode.WRITE_FAILED,
                            "target is marked read-only; clear the attribute to allow substitution");
                }
            } catch (IOException e) {
                throw new SpliceException(
                        ErrorCode.WRITE_FAILED, "could not read target file attributes", e);
            }
        }
        // Checked on every platform, not just Windows. A POSIX rename() only needs write permission
        // on the *directory*, so without this an unwritable 0444 config file would be silently
        // replaced on Linux while the same build failed on Windows.
        if (!Files.isWritable(target)) {
            throw new SpliceException(
                    ErrorCode.WRITE_FAILED, "target is not writable by the build user");
        }
    }

    /** Creates the sibling temp file, owner-only from the moment it exists where the OS allows it. */
    private static Path createRestrictedSibling(Path directory, String targetName) throws IOException {
        byte[] suffix = new byte[8];
        RANDOM.nextBytes(suffix);
        String name = "." + targetName + "." + HexFormat.of().formatHex(suffix) + ".configsplice-tmp";
        Path temp = directory.resolve(name);

        boolean posix = temp.getFileSystem().supportedFileAttributeViews().contains("posix");
        if (posix) {
            FileAttribute<Set<PosixFilePermission>> ownerOnly =
                    PosixFilePermissions.asFileAttribute(OWNER_ONLY);
            return Files.createFile(temp, ownerOnly);
        }
        // On Windows the file inherits the directory's ACL, which is the workspace's own protection.
        return Files.createFile(temp);
    }

    private static void writeAndSync(Path temp, byte[] content) throws IOException {
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(content));
            // Flush to the device before the rename, so a crash cannot leave the target pointing at
            // a file whose metadata exists but whose contents were never persisted.
            channel.force(true);
        }
    }

    /**
     * Copies the target's access controls onto the replacement.
     *
     * <p>A rename replaces the target inode, so without this the new file would carry the temp
     * file's permissions rather than the ones the user set on their config file.
     *
     * @return true if permissions were explicitly transferred
     */
    private static boolean copyPermissions(Path target, Path temp) throws IOException {
        if (!temp.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            // Windows: the replacement already inherited the directory ACL at creation, which is what
            // a file in this directory is expected to have. See ADR-002 for why explicit ACL copying
            // is not attempted.
            return false;
        }
        Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(target));
        return true;
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best effort: the write already failed and its error is the one worth reporting.
        }
    }
}
