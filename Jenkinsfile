/*
 * CI for ci.jenkins.io, via the Jenkins infrastructure pipeline library.
 * Reference: https://github.com/jenkins-infra/pipeline-library
 *
 * Why this particular matrix
 * --------------------------
 * Most plugins can get away with a single platform. This one cannot: two behaviours differ between
 * Windows and Linux, and each would have shipped as a defect had only one platform been tested.
 *
 *   - Files.isSymbolicLink() returns false for a Windows directory junction, so a symlink-based
 *     confinement check would let a junction escape the workspace (ADR-004).
 *   - An open file handle blocks replacement on Windows but not on Linux, which is why the writer
 *     carries a bounded retry that is inert on POSIX (ADR-002).
 *
 * So Windows is a required leg, not a nice-to-have. A green Linux build tells you very little about
 * the file-handling half of this plugin. Do not drop it to save build time.
 *
 * On the JDK versions
 * -------------------
 * ci.jenkins.io accepts only JDK 21 and 25. The plugin still *targets* Java 17 bytecode — the parent
 * POM derives that from jenkins.baseline 2.541, and javac --release 17 running on JDK 21 produces
 * Java 17 class files — so controllers on Java 17 remain supported.
 *
 * The consequence worth knowing: CI never executes the suite on a Java 17 runtime. Compilation is
 * pinned, execution is not. Run `mvn clean verify` on JDK 17 locally before a release if anything
 * touched reflection, class loading or the module system.
 *
 * forkCount is pinned to one fork per core because much of this suite starts real Jenkins instances
 * (the credential and sandbox tests) and a real inbound agent (gate 2). Oversubscribing makes those
 * flaky rather than fast.
 *
 * Gate evidence
 * -------------
 * Each gate test prints a platform evidence table to stdout and writes it to target/gate-evidence/.
 * Surefire captures the stdout copy, so the per-platform findings are readable from the archived test
 * results of each leg - the intended way to compare Windows against Linux after a change.
 */
buildPlugin(
    useContainerAgent: true,
    forkCount: '1C',
    configurations: [
        [platform: 'linux',   jdk: 21],
        [platform: 'windows', jdk: 21],
        [platform: 'linux',   jdk: 25],
    ]
)
