# Development

Contributor-facing notes. For what the plugin does and how to use it, see the
[README](../README.md); for the conventions a pull request is expected to follow, see
[CONTRIBUTING](../CONTRIBUTING.md).

## Building

Requires **JDK 17** and **Maven 3.9.6 or newer**.

```bash
mvn clean verify    # tests, SpotBugs, the parent's static checks, HPI packaging
mvn hpi:run         # local Jenkins with the plugin loaded, http://localhost:8080/jenkins
```

`mvn verify` is what CI runs. `mvn test` alone will not catch a SpotBugs finding or an HPI packaging
problem, and has previously reported green while `verify` failed.

The baseline is Jenkins **2.541.3** on **Java 17** — the newest LTS line that still supports Java 17,
since 2.555.1 and later require Java 21.

**Build on JDK 17 locally, even though CI cannot.** ci.jenkins.io accepts only JDK 21 and 25, so the
suite is never *executed* on a Java 17 runtime; the parent POM pins `--release 17`, which makes CI's
JDK 21 emit Java 17 bytecode, so compilation is covered and execution is not. Your local JDK 17 run is
the only place the tests actually run on the runtime the baseline supports. It matters most for
anything touching reflection, class loading or the module system.

### On Linux

```bash
sudo dnf install -y java-17-openjdk-devel   # AlmaLinux/RHEL 9; needs sudo
bash scripts/run-gates-linux.sh             # must NOT be run under sudo
```

The script checks for JDK 17, fetches Maven 3.9.16 into `.tools/` when the system Maven is too old
(AlmaLinux 9 ships 3.8.x, which fails with `Unknown packaging: hpi`), runs `clean verify`, and prints
the decision-gate evidence with notes on what should differ from the Windows results.

`bash scripts/...` works whether or not the file carries its executable bit, which a copy from Windows
drops. Note that `sudo ./file` on a non-executable file reports a misleading `command not found`.

## Architecture

The `engine` package tree has **no Jenkins imports**, and must keep it that way. That is what lets the
bulk of the suite run in milliseconds without a Jenkins harness, and what keeps a future Freestyle
adapter cheap.

```
io.jenkins.plugins.configsplice
├── engine/         format-agnostic: source model, ranges, splice planning, encoding, confinement
│   ├── json/       JSON path grammar and scalar location
│   └── xml/        .NET shorthand grammar and attribute location
└── (step layer)    Jenkins-facing: the step, credentials, remoting, result map
```

The central design choice is **locate-then-splice**: parse only to understand the document, then
replace the exact source range of the target scalar. Reading into a tree and writing back would
destroy comments, indentation, key order and newline style — the things this plugin exists to
preserve.

## Continuous integration

`Jenkinsfile` builds three configurations on ci.jenkins.io:

| Platform | JDK | Why |
|---|---|---|
| linux | 21 | the ordinary case |
| windows | 21 | the same JDK, where file semantics differ |
| linux | 25 | forward compatibility |

The JDK versions are what ci.jenkins.io offers, not a choice — see the note under Building for why
none of these legs proves anything about running on Java 17.

**Windows is a required leg.** Two behaviours differ between platforms and would each have shipped as
a defect had only one been tested: `Files.isSymbolicLink()` misses Windows directory junctions, and an
open file handle blocks replacement on Windows but not on Linux. A green Linux build says little about
the file-handling half of this plugin.

Each decision-gate test writes a platform evidence table to `target/gate-evidence/` and to standard
output, so the Windows and Linux legs can be compared directly after a change.

## Decision records

`docs/adr/` records why the design is what it is, and what was measured to establish it.

| ADR | Subject |
|---|---|
| [ADR-001](adr/ADR-001-source-preserving-range-location.md) | Source-preserving range location: why locate-then-splice, and what Jackson and StAX actually report |
| [ADR-002](adr/ADR-002-atomic-file-replacement.md) | Atomic replacement: the fallback, the bounded retry, and permission preservation |
| [ADR-003](adr/ADR-003-secret-handling-over-remoting.md) | Secret handling: what crosses the remoting channel, and why a masking type is required |
| [ADR-004](adr/ADR-004-workspace-confinement.md) | Workspace confinement: why the resolved real path decides |
| [ADR-005](adr/ADR-005-step-api-surface-and-testability.md) | Step API surface: the plain-collection result map, function-name resolution, the pre-commit seam |

## Requirements specification

[`requirements-v0.8.md`](requirements-v0.8.md) is the specification the implementation was built
against. Version 0.7 amended four sections from measurements taken during implementation, and 0.8
adds generic XML paths (Section 8.7); Section 20 records the decision-gate outcomes and Appendix C
indexes the ADRs against the sections they support.
