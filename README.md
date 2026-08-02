# Config Splice

Replaces **existing** values in JSON and .NET XML configuration files from a Jenkins Pipeline —
`appsettings.json` and `web.config` — preserving every byte it was not asked to change.

Comments, indentation, key order, attribute order, quote style, BOM state and line endings all
survive. Only the target value's own bytes change.

## Example

```groovy
configSubstitution(
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
                [path: 'FeatureFlags.Payments.Enabled', value: 'true']
            ]
        ]
    ]
)
```

Groups pair file patterns with the substitutions that apply **only** to those files, so XML paths are
never applied to JSON files.

## Requirements

Jenkins **2.541.3** or newer, on **Java 17** or newer.

## Property paths

### JSON

| Path | Meaning |
|---|---|
| `Logging.LogLevel.Default` | three nested objects |
| `Serilog.'MinimumLevel.Default'` | a literal key containing dots — quote it |
| `Services[0].Url` | zero-based array index |

### XML

Version 1.0 supports the two .NET configuration collections that matter in practice.

| Path | Resolves to |
|---|---|
| `appSettings.ApiUrl` | `/configuration/appSettings/add[@key='ApiUrl']/@value` |
| `appSettings.BankApi:Key` | dots and colons are **literal** here, never navigation |
| `connectionStrings.Default` | the connection string |
| `connectionStrings.Default.@providerName` | the provider name |

Matching is exact and case-sensitive. Only entries directly inside the top-level `appSettings` or
`connectionStrings` element are considered.

## Value types

By default a replacement takes the type already present: a JSON number stays an unquoted number, a
boolean stays a boolean, a string stays a string. Set `type` to `string`, `number`, `boolean` or
`null` to change one deliberately.

```groovy
[path: 'Services[0].Port', value: '8443', type: 'number']
```

## Credentials

Use `credentialsId` with a **Secret Text** credential rather than putting a secret in `value`.
Folder-scoped credentials resolve correctly.

```groovy
[path: 'connectionStrings.Default', credentialsId: 'production-connection-string']
```

> **Read this before using credentials.**
>
> - **The transformed file contains the secret.** That is the purpose of the step. Substitute as late
>   as possible — after any `archiveArtifacts`, `stash`, fingerprint, cache, upload or image build that
>   must not carry it. Workspace cleanup cannot retract a copy already taken.
> - **The value crosses the agent channel in the clear.** `hudson.util.Secret` protects the stored
>   form, not transport. Confidentiality depends on the agent channel being encrypted and the agent
>   host being trusted — as it does for any Jenkins credential used on an agent.
> - **Never put a secret in `value:`.** Literal step arguments are persisted with the build and shown
>   by Pipeline visualisation.
>
> Resolved credentials never appear in build logs, exception messages, the returned result or
> persisted build metadata.

## Options

| Option | Default | Meaning |
|---|---|---|
| `dryRun` | `false` | Validate and report without writing anything |
| `noMatchBehavior` | `fail` | When a target group matches no files: `fail`, `warn` or `ignore` |
| `missingPathBehavior` | `fail` | When a property path does not resolve: `fail`, `warn` or `ignore` |
| `acknowledgeSecretLifecycle` | `false` | Suppress the credential lifecycle notice — and nothing else |

## Return value

The step returns a map you can read from a sandboxed Pipeline without Script Approval:

```groovy
def result = configSubstitution(targets: [ /* ... */ ])
echo "Changed ${result['filesChanged']} of ${result['filesMatched']} file(s)"

result['details'].findAll { it['status'] == 'missing' }.each {
    echo "not found: ${it['file']} -> ${it['path']}"
}
```

Counts include `filesMatched`, `filesPlanned`, `filesChanged`, `filesUnchanged`,
`substitutionsMatched`, `substitutionsMissing` and `warnings`. `details` is an ordered list of
per-pattern and per-substitution records. The result contains no values and no credential IDs.

## Behaviour worth knowing

- **Nothing is ever created.** A path that does not resolve is a missing path, never a new field.
- **Ambiguity fails.** Two matching `<add>` elements, or duplicate property names anywhere in a JSON
  document, are errors rather than a silent choice.
- **Writes are atomic.** Each changed file is replaced through a same-directory temporary file, so a
  target is either fully old or fully new, never truncated.
- **Re-running changes nothing.** A run that would produce identical bytes performs no write and
  leaves the modification time untouched.
- **Files are confined to the workspace**, including against Windows directory junctions.

## Limitations

- **UTF-8 only.** UTF-16 files, and XML declarations naming `us-ascii`, `iso-8859-1` or
  `windows-1252`, are rejected rather than transcoded.
- **XML support is the two shorthands above.** Generic element traversal, arbitrary attributes and
  element text are not supported. **XDT transforms are out of scope** — this plugin substitutes
  values, it does not run `Web.Release.config`.
- **One target group per file.** A file matched by two groups is rejected.
- **Pipeline only.** No Freestyle build step in this release.

The [changelog](CHANGELOG.md) has the complete list.

## Documentation

- [Changelog](CHANGELOG.md) — release contents, security notes and known limitations
- [Development](docs/development.md) — building, CI, architecture and decision records
- [Contributing](CONTRIBUTING.md)

## License

[MIT](LICENSE)
