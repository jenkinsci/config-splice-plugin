# Config Splice

A Jenkins Pipeline step that replaces **existing scalar values** in JSON and .NET XML configuration
files — `appsettings.json` and `web.config` — preserving every byte it was not asked to change.

> **Status: alpha.** The engine, the Pipeline step and all six decision gates are complete. The
> step runs end to end in a sandboxed Pipeline. Not yet done: form UI (`config.jelly`), help text,
> Freestyle support, and the Linux CI leg. See [Current state](#current-state).

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
| `ConfigSubstitutionStep` — the Pipeline step | Implemented, end-to-end tested |
| Credentials, agent remoting, result map | Implemented |
| Decision gate 1 — source-range location | **Passed**, ADR-001 |
| Decision gate 2 — secret handling over remoting | **Passed**, ADR-003 |
| Decision gate 3 — workspace confinement | **Passed on Windows**, ADR-004; Linux leg outstanding |
| Decision gate 4 — atomic replacement | **Passed on Windows**, ADR-002; Linux leg outstanding |
| Decision gate 5 — sandbox-safe result map | **Passed**, ADR-005 |
| Decision gate 6 — pre-commit test seam | **Passed**, ADR-005 |
| Freestyle support, `config.jelly` views, help text | Not started |

89 tests pass on JDK 17. **All six decision gates are closed** on Windows; gates 3 and 4 still need
their Linux leg. Two tests self-skip: the Jenkins parent POM injects Jelly and properties
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
sudo dnf install -y java-17-openjdk-devel   # AlmaLinux/RHEL 9; needs sudo
bash scripts/run-gates-linux.sh             # must NOT be run under sudo
```

`bash scripts/...` works whether or not the file carries its executable bit, which a copy from
Windows will have dropped. If you prefer `./scripts/run-gates-linux.sh`, run
`chmod +x scripts/run-gates-linux.sh` first — note that `sudo ./file` on a non-executable file
reports a misleading `command not found`.

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
- [`docs/adr/ADR-005`](docs/adr/ADR-005-step-api-surface-and-testability.md) — why the result map is
  plain collections, why `@Symbol` is absent, and the pre-commit test seam

## License

MIT
