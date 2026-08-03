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

## Property paths

### JSON

| Path | Meaning |
|---|---|
| `Logging.LogLevel.Default` | three nested objects |
| `Serilog.'MinimumLevel.Default'` | a literal key containing dots — quote it |
| `Services[0].Url` | zero-based array index |

### XML

Two shorthands cover the .NET configuration collections that matter in practice.

| Path | Resolves to |
|---|---|
| `appSettings.ApiUrl` | `/configuration/appSettings/add[@key='ApiUrl']/@value` |
| `appSettings.BankApi:Key` | dots and colons are **literal** here, never navigation |
| `connectionStrings.Default` | the connection string |
| `connectionStrings.Default.@providerName` | the provider name |

Matching is exact and case-sensitive. Only entries directly inside the top-level `appSettings` or
`connectionStrings` element are considered.

Anything else in the document is reached by walking elements from the document root and ending in
`.@attribute` or `.#text`:

| Path | Resolves to |
|---|---|
| `configuration.'system.webServer'.security.requestFiltering.requestLimits.@maxAllowedContentLength` | that attribute |
| `configuration.branding.title.#text` | the element's text |
| `configuration.'system.webServer'.handlers.add[1].@name` | the **second** `add` child |
| `configuration.location.'system.web'.authorization.deny.@users` | content inside `<location>` |

A path starting `appSettings.` or `connectionStrings.` is always the shorthand; anything else is a
generic path. Three rules are worth knowing:

- **Dots separate steps.** An element whose name contains one — `system.webServer` — must be quoted,
  or it reads as two steps.
- **Names are matched exactly as written**, prefix included. `xdt:add` matches `xdt:add` and nothing
  else; namespace URIs are never resolved.
- **Ambiguity fails rather than guesses.** If a step matches more than one sibling and carries no
  `[n]`, the build fails instead of silently picking the first — document order is not something you
  should have to reason about. Add `[0]`, `[1]`, … to choose.

Absent elements and attributes are treated as missing paths and are **never created**. `#text` works
only where the element contains text and nothing else; an element holding child elements, comments or
CDATA is reported as ambiguous rather than having its markup overwritten.

`#text` replaces everything between the tags, indentation included, so a pretty-printed element
collapses onto one line:

```xml
<title>                        <title>Production Portal</title>
    Staging Portal      →
</title>
```

This is the one case where the plugin changes whitespace. Attribute substitutions never do.

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
> - **Do not hard-code a secret in `value:`.** A literal is persisted with the build and shown by
>   Pipeline visualisation.
>
> Resolved credentials never appear in build logs, exception messages, the returned result or
> persisted build metadata.

Where one credential supplies several values, `withCredentials` works too — the value is masked in
the log and Pipeline stores the step argument as `${MY_PASS}` rather than the secret itself:

```groovy
withCredentials([usernamePassword(credentialsId: 'my-secret',
                                  usernameVariable: 'MY_USER',
                                  passwordVariable: 'MY_PASS')]) {
    configSubstitution(targets: [[files: ['*.json'], format: 'json',
        substitutions: [
            [path: 'user', value: env.MY_USER, type: 'string'],
            [path: 'pass', value: env.MY_PASS, type: 'string']
        ]]])
}
```

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
- **Files are confined to the workspace**, including against Windows directory junctions. Symbolic
  links and junctions are refused as targets even when they resolve inside, because replacing one
  would destroy the link rather than update the file it points at.
- **A read-only or unwritable target is refused** — on Linux as well as Windows, even though a POSIX
  rename would technically succeed.
- **On Windows, a replacement blocked by another process is retried** briefly before failing.
  Antivirus and IIS hold `web.config` open routinely. The retry never engages on Linux.
- **A file changed on disk between planning and writing is detected and not overwritten.**
- If several files are written and one fails after others succeeded, the step fails and names both
  the failed file and every file already committed. The failed file keeps its original bytes.

## Limitations

- **UTF-8 only.** UTF-16 files, and XML declarations naming `us-ascii`, `iso-8859-1` or
  `windows-1252`, are rejected before modification rather than transcoded.
- **XML paths address existing attributes and element text only.** Elements cannot be created, moved
  or removed. **XDT transforms are out of scope** — this plugin substitutes values, it does not run
  `Web.Release.config`.
- **`appSettings file="..."` is not followed.** Entries inside `<location>` are not matched by the
  shorthands, though a generic path reaches them.
- **Namespace prefixes are lexical.** Two prefixes bound to the same namespace URI are not
  interchangeable in a path.
- **One target group per file.** A file matched by two groups is rejected; consolidate the group or
  use non-overlapping patterns.
- **Only scalars.** JSON objects and arrays cannot be replaced wholesale.
- **A deliberately empty string is not expressible.** A blank value means "not supplied".
- **Windows ACLs are inherited, not copied.** A replaced file inherits its directory's ACL. Explicit,
  non-inherited entries set directly on a configuration file are not carried across.
- **Pipeline only.** No Freestyle build step in this release.
- Files are expected to be at most a few MiB.

## Documentation

- **Release notes** — see the Releases tab on the plugin site, generated from GitHub releases
- [Development](docs/development.md) — building, CI, architecture and decision records
- [Contributing](CONTRIBUTING.md)

## License

[MIT](LICENSE)
