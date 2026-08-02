/*
 * CI for ci.jenkins.io, via the Jenkins infrastructure pipeline library.
 * Reference: https://github.com/jenkins-infra/pipeline-library
 *
 * Why this particular matrix
 * --------------------------
 * Most plugins can get away with a single platform. This one cannot: decision gates 3 and 4 found
 * behaviour that genuinely differs between Windows and Linux, and both differences would have
 * shipped as bugs if only one platform had been tested.
 *
 *   - Files.isSymbolicLink() returns false for a Windows directory junction, so a symlink-based
 *     confinement check would let a junction escape the workspace (ADR-004).
 *   - An open file handle blocks replacement on Windows but not on Linux, which is why the writer
 *     carries a bounded retry that is inert on POSIX (ADR-002).
 *
 * So Windows is a required leg, not a nice-to-have. A green Linux build tells you very little about
 * the file-handling half of this plugin.
 *
 *   linux / 17    the minimum supported baseline (Jenkins 2.541.3 targets Java 17)
 *   windows / 17  the same baseline on the platform whose file semantics differ
 *   linux / 21    forward compatibility, per SRS section 17.2
 *
 * Windows on JDK 21 is deliberately omitted: the platform differences found so far are in OS file
 * semantics rather than JDK version, so a fourth leg would roughly double CI time for little signal.
 * Add it if a JDK-specific difference ever turns up.
 *
 * forkCount is pinned to one fork per core because much of this suite starts real Jenkins instances
 * (the credential and sandbox tests) and a real inbound agent (gate 2). Oversubscribing makes those
 * flaky rather than fast.
 *
 * Gate evidence
 * -------------
 * Each gate test prints a platform evidence table to stdout and writes it to
 * target/gate-evidence/. Surefire captures the stdout copy, so the per-platform findings are
 * readable from the archived test results of each leg - which is the intended way to compare
 * Windows against Linux after a change.
 */
buildPlugin(
    useContainerAgent: true,
    forkCount: '1C',
    configurations: [
        [platform: 'linux',   jdk: 17],
        [platform: 'windows', jdk: 17],
        [platform: 'linux',   jdk: 21],
    ]
)
