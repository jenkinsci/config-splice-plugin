package io.jenkins.plugins.configsplice.engine;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Confines every file the plugin touches to the build's workspace (SRS section 13.1).
 *
 * <h2>Why link resolution, not just string checks</h2>
 *
 * <p>Rejecting {@code ..} and absolute paths stops the naive attack. It does nothing about a
 * workspace that <em>contains</em> a link pointing somewhere else — a symlink on Linux, a directory
 * junction on Windows. Both let a lexically innocent relative path such as
 * {@code config/web.config} resolve to a file outside the workspace entirely.
 *
 * <p>So confinement is decided on the <em>real</em> path, after the operating system has resolved
 * every component. Gate 3 measured why this matters on Windows: {@code Files.isSymbolicLink} returns
 * false for a directory junction, so an implementation that screened for symlinks alone would let
 * junctions through. {@link Path#toRealPath} resolves both.
 *
 * <p>This class deliberately uses only {@code java.nio}. The Jenkins step layer additionally calls
 * {@code FilePath.isDescendant} as defence in depth, but the rule enforced here must hold without a
 * Jenkins runtime so it can be tested exhaustively and cheaply.
 */
public final class WorkspaceGuard {

    private WorkspaceGuard() {
    }

    /**
     * Resolves a workspace-relative path and proves it is a regular file inside the workspace.
     *
     * @param workspaceRoot the build workspace; must exist
     * @param relativePath  a workspace-relative path as produced by glob expansion
     * @return the resolved real path, safe to read and replace
     * @throws SpliceException {@link ErrorCode#WORKSPACE_ESCAPE} if the path is absolute, traverses
     *                         out of the workspace, or resolves outside it through a link;
     *                         {@link ErrorCode#FILE_NOT_FOUND} if nothing is there
     */
    public static Path requireConfinedRegularFile(Path workspaceRoot, String relativePath)
            throws SpliceException {

        if (relativePath == null || relativePath.isBlank()) {
            throw new SpliceException(ErrorCode.WORKSPACE_ESCAPE, "file path must not be empty");
        }

        Path candidate = parse(relativePath);
        if (candidate.isAbsolute() || candidate.getRoot() != null) {
            // Also catches Windows drive-qualified and UNC paths, which are "absolute" in different ways.
            throw new SpliceException(
                    ErrorCode.WORKSPACE_ESCAPE, "file path must be workspace-relative, not absolute");
        }

        Path realWorkspace = realPathOf(workspaceRoot, "workspace");
        Path resolved = realWorkspace.resolve(candidate).normalize();

        // Cheap lexical screen first, so obvious traversal fails before touching the filesystem.
        if (!resolved.startsWith(realWorkspace)) {
            throw new SpliceException(
                    ErrorCode.WORKSPACE_ESCAPE, "file path traverses outside the workspace");
        }

        if (!Files.exists(resolved, LinkOption.NOFOLLOW_LINKS)) {
            throw new SpliceException(ErrorCode.FILE_NOT_FOUND, "file does not exist in the workspace");
        }

        // The decisive check: resolve every component, including links and junctions, then compare.
        Path realTarget = realPathOf(resolved, "file");
        if (!realTarget.startsWith(realWorkspace)) {
            throw new SpliceException(
                    ErrorCode.WORKSPACE_ESCAPE, "file resolves outside the workspace through a link");
        }

        // NOFOLLOW is load-bearing: it rejects symlinks, junctions, directories, devices and pipes in
        // one check. A link that stays inside the workspace is still refused, because the atomic
        // replacement renames over the target and would silently convert the link into a regular file.
        if (!Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) {
            throw new SpliceException(
                    ErrorCode.WORKSPACE_ESCAPE,
                    "target is not a regular file (a link, directory or special file is not supported)");
        }

        return realTarget;
    }

    private static Path parse(String relativePath) throws SpliceException {
        try {
            return Paths.get(relativePath);
        } catch (InvalidPathException e) {
            // The message can echo the offending path, which is a file name rather than a value, but
            // it is not worth the risk of a platform surprise; report it without the detail.
            throw new SpliceException(ErrorCode.WORKSPACE_ESCAPE, "file path is not valid on this platform");
        }
    }

    private static Path realPathOf(Path path, String what) throws SpliceException {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            throw new SpliceException(
                    ErrorCode.WORKSPACE_ESCAPE, "could not resolve the real path of the " + what, e);
        }
    }
}
