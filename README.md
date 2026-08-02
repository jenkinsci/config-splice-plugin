# Config Splice

A Jenkins Pipeline step that replaces **existing scalar values** in JSON and .NET XML configuration
files — `appsettings.json` and `web.config` — preserving every byte it was not asked to change.

> **Status: pre-alpha.** The substitution engine and decision gate 1 are complete and tested. The
> Pipeline step itself is not yet implemented. See [Current state](#current-state).

## What it does

```groovy
def result = configSubstitution(
    targets: [
        [
            files: ['**/web.config'],
            format: 'xml',
            substitutions: [
                [path: 'appSettings.ApiUrl', value: 'https://production.com'],
                [path: 'appSettings.BankApi:Key', credentialsId: 'bank-api-key']
            ]
        ],
        [
            files: ['**/appsettings.json'],
            format: 'json',
            substitutions: [
                [path: 'Logging.LogLevel.Default', value: 'Warning'],
                [path: "Serilog.'MinimumLevel.Default'", value: 'Information']
            ]
        ]
    ]
)

echo "Changed ${result['filesChanged']} file(s)"
```

The product name is *Config Splice*; the step is called `configSubstitution` because a public API
should be descriptive and predictable even when the product name is more distinctive.

## Design commitments

- **Existing values only.** Nothing is ever created. A path that does not resolve is a missing path,
  never a new field.
- **Byte-exact preservation.** Comments, indentation, key order, attribute order, quote style,
  self-closing tags, BOM state and CRLF/LF all survive. This is enforced by a test oracle, not by
  good intentions — see [ADR-001](docs/adr/ADR-001-source-preserving-range-location.md).
- **Secrets stay out of everything observable.** Resolved credentials never reach logs, exception
  messages, Pipeline arguments, the result map or persisted build XML. Third-party parser exceptions
  are never propagated verbatim, because their messages embed source excerpts.
- **Fail loudly, never silently.** Ambiguous XML matches, duplicate JSON keys, non-UTF-8 encodings and
  malformed input all fail before any file is modified.

## Current state

| Component | State |
|---|---|
| `engine` — source model, encoding admission, splice planning | Implemented, tested |
| `engine.json` — path grammar, scalar locator, string codec | Implemented, tested |
| `engine.xml` — shorthand grammar, tag scanner, attribute locator | Implemented, tested |
| `AtomicFileWriter` — sibling temp, fsync, atomic replace | Implemented, tested on Windows |
| `WorkspaceGuard` — link-aware workspace confinement | Implemented, tested on Windows |
| Decision gate 1 — source-range location | **Passed**, recorded in ADR-001 |
| Decision gate 2 — secret handling over remoting | **Passed**, recorded in ADR-003 |
| Decision gate 3 — workspace confinement | **Passed on Windows**, recorded in ADR-004; Linux leg outstanding |
| Decision gate 4 — atomic replacement | **Passed on Windows**, recorded in ADR-002; Linux leg outstanding |
| `ConfigSubstitutionStep` — the Pipeline step | Not started |
| Credentials, agent remoting | Not started |
| Gates 5, 6 | Not started |

82 tests pass on JDK 17. Two tests self-skip: the Jenkins parent POM injects Jelly and properties
checks that stand down until view files exist.

Gate evidence is written to `target/gate-evidence/` on every build, so the Windows and Linux CI legs
can be compared directly.

### Architecture

The `engine` package tree has **no Jenkins imports** and must keep it that way. SRS section 2.2
requires the transformation engine to be reusable by a future `SimpleBuildStep` adapter without
redefining behaviour, and keeping the boundary clean also means the engine can be tested in
milliseconds without a Jenkins harness.

```
io.jenkins.plugins.configsplice
├── engine/                 format-agnostic: ranges, plans, encoding, errors
│   ├── json/               JSON path grammar and scalar location
│   └── xml/                .NET shorthand grammar and attribute location
└── (step layer)            Jenkins-facing; not yet written
```

## Building

Requires **JDK 17** and Maven 3.9.6+.

```bash
mvn test          # engine tests, seconds
mvn verify        # adds SpotBugs and the plugin parent's checks
mvn hpi:run       # run a Jenkins instance with the plugin loaded
```

The baseline is Jenkins **2.541.3** on **Java 17** — the newest LTS line that still supports Java 17,
since 2.555.1 and later require Java 21. See SRS section 14.1.

### On Linux

```bash
./scripts/run-gates-linux.sh
```

Checks for JDK 17, fetches Maven 3.9.16 into `.tools/` if the system Maven is older than 3.9.6
(AlmaLinux 9's AppStream ships 3.8.x, which fails with `Unknown packaging: hpi`), runs the suite, and
prints the gate evidence with notes on what should differ from the Windows results.

## Documentation

- `../jenkins-config-substitution-plugin-requirements-v0.6.md` — the requirements specification
- [`docs/adr/ADR-001`](docs/adr/ADR-001-source-preserving-range-location.md) — why locate-then-splice,
  and what the parsers actually reported
- [`docs/adr/ADR-002`](docs/adr/ADR-002-atomic-file-replacement.md) — atomic replacement, the Windows
  open-handle finding, and one proposed SRS amendment
- [`docs/adr/ADR-003`](docs/adr/ADR-003-secret-handling-over-remoting.md) — what actually crosses the
  remoting channel, and why `Secret.toString()` forces a masking wrapper
- [`docs/adr/ADR-004`](docs/adr/ADR-004-workspace-confinement.md) — why confinement is decided on the
  resolved real path, and why a symlink screen would miss Windows junctions

## License

MIT
