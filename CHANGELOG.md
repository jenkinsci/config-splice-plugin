# Changelog

All notable changes to **Config Splice** are recorded here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased] — 1.0.0

First release. Everything below is new.

### Added

**The `configSubstitution` Pipeline step.** Replaces existing scalar values in JSON and .NET XML
configuration files, scoped so that XML paths are never applied to JSON files:

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
        ]
    ]
)
echo "Changed ${result['filesChanged']} file(s)"
```

**JSON property paths** — nested navigation, quoted keys for names containing dots, and zero-based
array indexes: `Logging.LogLevel.Default`, `Serilog.'MinimumLevel.Default'`, `Services[0].Url`.

**.NET XML shorthand** for the two collections that matter in practice:

| Path | Resolves to |
|---|---|
| `appSettings.ApiUrl` | `/configuration/appSettings/add[@key='ApiUrl']/@value` |
| `appSettings.BankApi:Key` | dots and colons are literal, never navigation |
| `connectionStrings.Default` | the connection string |
| `connectionStrings.Default.@providerName` | the provider name |

**Type handling.** By default a replacement takes the type already present, so a JSON number stays an
unquoted number and a boolean stays a boolean. An explicit `type` of `string`, `number`, `boolean` or
`null` overrides that when a change is intended.

**Secret Text credentials** via `credentialsId`, resolved in the job's context so folder-scoped
credentials work.

**Byte-exact preservation.** Comments, indentation, key order, attribute order, quote style,
self-closing tags, the XML declaration, BOM state and CRLF/LF line endings all survive. Only the
target value's own bytes change.

**`dryRun`** to validate and report without writing anything.

**Error policies** `fail`, `warn` and `ignore` for unmatched file patterns and for property paths that
do not resolve. Both default to `fail`.

**A value-free result map**, readable from a sandboxed Pipeline without Script Approval, containing
counts plus a `details` list of per-pattern and per-substitution records.

**Idempotency.** A run that would produce identical bytes performs no write and leaves the file's
modification time untouched.

**Atomic writes.** Each changed file is replaced through a same-directory temporary file, so a target
is either fully old or fully new and never truncated.

**Form UI** with field help, a credential picker and live validation, usable from the Pipeline Snippet
Generator.

### Security

- **The transformed file contains the secret.** That is the purpose of the step. Substitute as late as
  possible — after any `archiveArtifacts`, `stash`, fingerprint, cache, upload or image build that
  must not carry it. Workspace cleanup cannot retract a copy already taken.
- **A credential crosses the agent channel in the clear.** `hudson.util.Secret` protects the *stored*
  form, not transport. Confidentiality depends on the agent channel being encrypted and the agent host
  being trusted, exactly as for any other Jenkins credential used on an agent.
- **Never place a secret in `value:`.** Literal step arguments are persisted with the build and shown
  by Pipeline visualisation. Use `credentialsId:`.
- A one-line security notice is printed on any real run that uses credentials. Set
  `acknowledgeSecretLifecycle: true` to suppress **only that notice** once the risk has been reviewed.
- Resolved credentials never appear in build logs, exception messages, the returned result or
  persisted build metadata. Third-party parser exceptions are never propagated verbatim, because their
  messages can quote file content.
- XML is parsed with DTDs and external entities disabled; a document that depends on them is rejected.
- Files are confined to the workspace by resolving the real path, which catches Windows directory
  junctions as well as symbolic links.
- The credential picker performs permission checks before listing, so it cannot be used to enumerate
  credential IDs.

### Behavior worth knowing

- **Nothing is ever created.** A path that does not resolve is a missing path, not a new field.
- **Ambiguity fails.** Two matching `<add>` elements, or duplicate property names anywhere in a JSON
  document, are errors rather than a silent choice between them.
- **A read-only or unwritable target is refused** rather than silently unprotected — on Linux as well
  as Windows, even though a POSIX rename would technically succeed.
- **Symbolic links and junctions are refused as targets**, even when they resolve inside the workspace,
  because replacing one would destroy the link rather than update the file it points at.
- **On Windows, a replacement blocked by another process is retried** briefly (under a second) before
  failing. Antivirus and IIS hold `web.config` open routinely, and a single attempt would turn a
  background scan into a failed build. The retry never engages on Linux.
- **A file changed on disk between planning and writing is detected and not overwritten.**
- If several files are being written and one fails after others succeeded, the step fails and names
  both the failed file and every file already committed. The failed file keeps its original bytes.

### Known limitations

- **UTF-8 only.** Files with a UTF-16/UTF-32 byte order mark, or an XML declaration naming any other
  encoding — including `us-ascii`, `iso-8859-1` and `windows-1252` — are rejected before modification
  rather than transcoded.
- **XML support is the two .NET shorthands only.** Generic element traversal, arbitrary attributes,
  element text and XML indexes are not supported. XDT transforms are explicitly out of scope: this
  plugin substitutes values, it does not run `Web.Release.config`.
- **`appSettings file="..."` is not followed**, and entries inside `<location>` are not matched by the
  shorthand.
- **One target group per file.** A file matched by two groups is rejected; consolidate the group or use
  non-overlapping patterns.
- **Substituting a deliberately empty string is not expressible.** A blank value is treated as "no
  value supplied".
- **Windows ACLs are inherited, not copied.** A replaced file inherits its directory's ACL, which is
  what a file in that directory would normally have. Explicit, non-inherited entries set directly on a
  configuration file are not carried across.
- **Pipeline only.** No Freestyle build step in this release.
- JSON objects and arrays cannot be replaced wholesale; only scalars.
- Files are expected to be at most a few MiB.

### Compatibility

- Requires **Jenkins 2.541.3** or newer on **Java 17** or newer.
- Verified on Windows 10 and AlmaLinux 9, on Java 17, with the full test suite and static analysis
  passing on both.

---

[Unreleased]: https://github.com/jenkinsci/config-splice-plugin/commits/main
