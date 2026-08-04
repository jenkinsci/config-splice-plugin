# Software Requirements Specification
## Jenkins Configuration Property Substitution Plugin

**Version:** 0.9  
**Status:** Released baseline, extended with generic XML paths and a Freestyle surface  
**Date:** August 3, 2026  
**Supersedes:** Version 0.8  

> **What changed in 0.9.** One **scope addition**: Freestyle support through a `SimpleBuildStep`
> adapter, listed as a Version 1.1 candidate in §21.2, is now implemented.
>
> | Change | Sections |
> |---|---|
> | Freestyle build step: surface, parameters, parity and failure reporting (new) | §4.7 |
> | Pipeline step no longer described as the only interface | §4.2 |
> | Agent-side failures cross the channel as `AbortException` (new) | §12.5.2 |
> | Acceptance criteria 51–56 added | §18 |
> | Moved from V1.1 candidates into delivered scope | §21.1, §21.2 |
>
> The Freestyle step is an **adapter, not a second implementation**: both surfaces bind the same
> `Describable` models and execute the same controller-side code path, so no behaviour is specified
> twice. The Pipeline step is unchanged, and every Pipeline job valid under 0.8 keeps its exact 0.8
> meaning.
>
> **What changed in 0.8.** Version 1.0 shipped as `11.v2c4a_4cb_cc6d5` against the 0.7 baseline. This
> version records one **scope addition**: generic XML element traversal, deferred in 0.4 and listed as
> a Version 1.1 candidate in §21.2, is now implemented.
>
> | Change | Sections |
> |---|---|
> | Generic XML path grammar and resolution rules (new) | §8.7 |
> | XML dispatch now falls through to the generic grammar instead of failing | §6.1 |
> | Shorthand-only document-shape rules scoped to the shorthands | §8.4 |
> | Generic XML removed from the exclusion list; XDT exclusion unchanged | §8.6 |
> | `CONFIG_SUBSTITUTION_XML_PATH_UNSUPPORTED` no longer reachable | §15.3 |
> | Acceptance criterion 9 replaced; criteria 44–50 added | §18 |
> | Moved from V1.1 candidates into delivered scope | §21.1, §21.2 |
>
> The two .NET shorthands are unchanged and keep precedence, so **every path valid under 0.7 keeps its
> exact 0.7 meaning**. The addition is strictly widening: paths that previously failed with
> `XML_PATH_UNSUPPORTED` now resolve or fail with a more specific code.
>
> **What changed in 0.7.** Version 0.6 was written before implementation. All six decision gates of
> Section 20 have since been executed on Windows 10 and AlmaLinux 9. **Four of their findings
> contradicted or under-specified this document** and are now corrected in place:
>
> | Finding | Gate | Amended |
> |---|---|---|
> | `Secret` does not encrypt over remoting; the plaintext crosses the channel | 2 | §12.4 |
> | `Secret.toString()` returns the plaintext, so any string conversion leaks it | 2 | §12.5.1 (new) |
> | `Files.isSymbolicLink()` returns false for a Windows directory junction | 3 | §13.1 |
> | Any open file handle blocks replacement on Windows, but not on Linux | 4 | §13.2.1 (new) |
>
> Implementation also settled several points this document had left implicit: cross-platform
> writability (§13.1), Ant glob semantics (§5.1), `mvn verify` as the verification command and
> cross-platform probe discipline (§17.1), and why the Windows CI leg is required (§17.2).
>
> Each amendment is traceable to the architecture decision record that measured it; Section 20
> records the gate outcomes and Appendix C indexes the ADRs. **Nothing in the V1.0 product scope
> changed** — the API, the path grammars, the result schema and the acceptance criteria of 0.6 all
> stand, with six criteria added rather than altered.

---

## 1. Executive summary
The plugin shall provide a concise, secure Jenkins Pipeline step for replacing **existing scalar values** in JSON configuration files and in XML configuration files. XML addressing is by shorthand for the two most common .NET collections, `appSettings` and `connectionStrings`, and by generic element traversal (Section 8.7) for everything else. It is intended to offer Jenkins users an experience comparable to Microsoft Variable Substitution while remaining explicit about file scope, path interpretation, type handling, source preservation, credentials, agent remoting, and failure behavior.

Version 1.0 is intentionally limited to the original user need and shall:

- support nested, quoted-key, and array-index paths in JSON;
- support top-level `.NET` `appSettings` and `connectionStrings` XML shorthand;
- require each matched file to belong to exactly one target group;
- update existing scalar values only;
- integrate with Jenkins Secret Text credentials;
- preserve every unaffected source byte;
- execute file discovery, parsing, validation, and writing on the Jenkins agent that owns the workspace;
- support validation-only execution through `dryRun`;
- return a value-free summary map directly usable by sandboxed Pipelines without Script Approval;
- detect and reject a source file changed between planning and commit; and
- fail safely without disclosing source values or resolved credentials.

The product is a **configuration substitution** plugin. It is not an XDT transform engine, template engine, arbitrary XPath/JSONPath evaluator, or general-purpose text replacement tool.

## 2. Goals and non-goals
### 2.1 Goals

1. Make environment-specific `web.config` and `appsettings.json` changes concise in Jenkinsfiles.
2. Prevent substitutions intended for one format or file set from being applied to unrelated files.
3. Provide deterministic, formally specified path semantics for the supported V1.0 path forms.
4. Preserve comments, whitespace, ordering, UTF-8 BOM state, newline style, and all unaffected bytes.
5. Support Jenkins Secret Text without storing resolved secrets in Pipeline arguments, build metadata, logs, reports, or return values.
6. Work on controller-local and remote-agent workspaces on Linux and Windows.
7. Provide clear validation, secure diagnostics, idempotent writes, source-change detection, and testable acceptance criteria.
8. Ship a focused first release whose behavior can be exercised by real Jenkinsfiles before the path engine is expanded.

### 2.2 Non-goals for Version 1.0

- Creating missing properties, elements, attributes, or array entries.
- Replacing JSON objects or arrays.
- Generic XML element traversal, XML indexes, `#text`, or arbitrary XML attribute selection.
- XDT transforms such as `xdt:Transform` and `xdt:Locator`.
- Following `appSettings file="..."` into an external configuration file.
- Arbitrary XPath, JSONPath, regular expressions, or text token replacement.
- Allowing one file to be resolved by more than one target group.
- Later-group override or last-writer-wins behavior.
- Per-glob required-match enforcement; `noMatchBehavior` applies to the target-group union in V1.0.
- Per-target-group overrides of top-level error policies.
- YAML, TOML, INI, `.env`, or binary formats.
- Credentials other than Jenkins Secret Text.
- Freestyle job UI support.
- Cross-workspace or absolute-path writes.
- A globally atomic transaction across multiple files.
- Merging concurrent external edits. The plugin detects a changed source snapshot and fails safely instead of overwriting it.
- Automatic environment-variable or token expansion. Pipeline authors shall use Groovy interpolation, `env`, `withEnv`, or credentials-backed substitutions explicitly.
- UTF-16, UTF-32, ISO-8859-1, Windows-1252, US-ASCII declarations, or any other non-UTF-8 declared encoding, even when current bytes happen to be ASCII-only.

The transformation engine shall be separated from the Pipeline step implementation so future Freestyle support and V1.1 path features can reuse it without redefining behavior.

## 3. Terminology
| Term | Definition |
|---|---|
| Target group | One set of file globs, one format, and the substitutions that apply only to those files. In V1.0, a file must resolve from exactly one target group. |
| Substitution | A property path plus either a literal value or a Jenkins credential ID. |
| Scalar | JSON string, number, boolean, or null; or the XML value attribute selected by a supported shorthand. |
| Source-preserving edit | Replacing only the exact source range of the target scalar while leaving all other bytes unchanged. |
| Missing file | The union of a target group's globs resolves to no regular files. |
| Missing path | A syntactically valid path resolves to no supported target scalar in a matched file. |
| Ambiguous path | A supported XML shorthand resolves to more than one `<add>` element. |
| Non-scalar target | A JSON object or array. |
| Value-free | Contains file names, property paths, counts, statuses, and error categories, but no current or replacement values, credential IDs, or resolved credentials. |
| Source snapshot | The exact original file bytes and their SHA-256 digest retained during planning for pre-commit change detection. |

## 4. Canonical Pipeline API
### 4.1 Canonical example

```groovy
def result = configSubstitution(
    targets: [
        [
            files: ['src/**/web.config'],
            format: 'xml',
            substitutions: [
                [
                    path: 'appSettings.ApiUrl',
                    value: 'https://production.com'
                ],
                [
                    path: 'appSettings.BankApi:Key',
                    credentialsId: 'bank-api-key'
                ]
            ]
        ],
        [
            files: ['src/**/appsettings.json'],
            format: 'json',
            substitutions: [
                [
                    path: 'Logging.LogLevel.Default',
                    value: 'Warning'
                ],
                [
                    path: 'FeatureFlags.Payments.Enabled',
                    value: 'true'
                ]
            ]
        ]
    ],
    dryRun: false,
    noMatchBehavior: 'fail',
    missingPathBehavior: 'fail',
    acknowledgeSecretLifecycle: false
)

echo "Changed ${result['filesChanged']} file(s)"
```

The two target groups are intentionally separate. A substitution is never applied outside its containing target group, and a file matching both groups is rejected in Version 1.0.

### 4.2 Pipeline step

- Step function name: `configSubstitution`
- Naming mechanism: `StepDescriptor.getFunctionName()` shall return `"configSubstitution"`.
- `@Symbol` is not required to name this custom `Step` and shall not be relied on for step resolution.
- Required context: `Run`, `FilePath`, and `TaskListener`
- Return type: `Map<String, Object>` containing only nested maps, lists, strings, booleans, integers, and null; the result is value-free, CPS-serializable, JEP-200-safe, and consumable in a sandboxed Pipeline without administrator approval
- This is the only surface that returns the result map. The Freestyle build step of §4.7 cannot, because `SimpleBuildStep.perform` is `void`.

The nested target-group and substitution models are concrete `Describable` types bound by their fields. They do not require symbols for the documented map syntax.

### 4.3 Top-level parameters

| Parameter | Type | Required | Default | Requirement |
|---|---|---:|---|---|
| `targets` | List of target groups | Yes | None | Must contain at least one target group. |
| `dryRun` | Boolean | No | `false` | Validate and report planned changes without writing or changing timestamps. |
| `noMatchBehavior` | Enum | No | `fail` | `fail`, `warn`, or `ignore`; evaluated for the union of globs in each target group. |
| `missingPathBehavior` | Enum | No | `fail` | `fail`, `warn`, or `ignore`; evaluated per substitution per matched file. |
| `acknowledgeSecretLifecycle` | Boolean | No | `false` | When true, suppress the credential-file lifecycle notice described in Section 12.6. It does not weaken any security control. |

### 4.4 Target group parameters

| Parameter | Type | Required | Default | Requirement |
|---|---|---:|---|---|
| `files` | List of String | Yes | None | One or more workspace-relative Ant-style globs. |
| `format` | Enum | No | `auto` | `auto`, `json`, or `xml`. |
| `substitutions` | List of substitutions | Yes | None | One or more substitutions scoped to this target group. |

A target group shall resolve to only one effective format. With `format: 'auto'`, each matched file is detected independently; the target group shall fail if it contains a mixture of XML and JSON files. Explicit `format` is recommended because path grammar and type constraints are format-dependent and can otherwise be validated only after agent-side glob expansion and format detection. If an `auto` group matches no files and `noMatchBehavior` is `warn` or `ignore`, only format-independent parameter checks can run; format-specific path and type validation is necessarily deferred because no effective format exists.

### 4.5 Substitution parameters

| Parameter | Type | Required | Default | Requirement |
|---|---|---:|---|---|
| `path` | String | Yes | None | Must conform to Section 6. |
| `value` | String | Conditional | None | Required for a non-null literal substitution. Mutually exclusive with `credentialsId`; forbidden for `type: 'null'`. |
| `credentialsId` | String | Conditional | None | Required for a credential-backed substitution. Mutually exclusive with `value`; forbidden for `type: 'null'`. |
| `type` | Enum | No | `auto` | `auto`, `string`, `number`, `boolean`, or `null`. For an effective XML target group, only `auto` and `string` are valid. |

For `type: 'null'`, both `value` and `credentialsId` shall be omitted. For every other type, exactly one source shall be supplied.

`value` is always modeled as a String in the Jenkins configuration model. This avoids polymorphic `Object` binding and preserves Snippet Generator and Pipeline model fidelity.

### 4.6 Parameter validation

The step shall reject before file modification:

- an empty `targets`, `files`, or `substitutions` list;
- blank paths, globs, values where required, or credential IDs;
- both `value` and `credentialsId` on one substitution;
- neither source on a non-null substitution;
- `credentialsId` with `type` other than `auto` or `string`;
- a value supplied with `type: 'null'`;
- duplicate canonical paths within one target group;
- for an effective XML target group, any `type` other than `auto` or `string`; this check occurs after format selection when `format: 'auto'`;
- unsupported format or behavior values;
- absolute paths or paths outside the workspace.

Duplicate-path comparison is case-sensitive and performed after parsing the path to its canonical representation. Invalid XML types shall fail with `CONFIG_SUBSTITUTION_TYPE_INVALID` before credential resolution or file modification. Same-file multi-group ownership is an agent-side discovery validation governed by Section 5.3, not a bind-time parameter check. Override-by-later-group is explicitly unsupported in Version 1.0.

### 4.7 Freestyle build step

The plugin shall additionally provide a Freestyle build step implementing `SimpleBuildStep`.

#### 4.7.1 Surface

1. The build step shall be applicable to all `AbstractProject` job types and shall appear in the **Add build step** menu.
2. It shall expose exactly the parameters of Sections 4.3 to 4.5, bound from a form rather than from a Groovy map, and shall apply the same validation as Section 4.6.
3. It shall carry **no `@Symbol`**. Pipeline users are served by the step of Section 4.2, which returns the result map; publishing a symbol would offer Pipeline a second entry point that silently lacks that return value. Reachability through the legacy `step([$class: …])` form is an unavoidable property of `SimpleBuildStep` and is not a documented interface.
4. It shall require a workspace and shall obtain `Run`, `FilePath` and `TaskListener` from the build.

#### 4.7.2 Parity

Both surfaces shall execute the same controller-side code path — validation, credential resolution, the lifecycle notice of Section 12.6, dispatch to the agent, log replay and the summary line. This is a **requirement, not an implementation note**: credential resolution carries the rule of Section 12.1 that lookup fails generically and never reveals whether an unauthorized credential ID exists, and a second copy of that logic is how one copy would lose the rule.

Given identical configuration and identical input files, the two surfaces shall produce **byte-identical output**.

#### 4.7.3 Result reporting

The Freestyle step discards the result map of Section 11.2, having nowhere to return it. The counts it carries shall still reach the user through the build log of Section 15.1, which is the only channel a Freestyle job has. `missingPathBehavior: warn` therefore has no programmatic consumer on this surface, and the inline help shall say so.

#### 4.7.4 Failure reporting

A failure shall abort the build with the value-free message of Section 15 and **no stack trace**. Agent-side failures already cross the channel as `AbortException` (Section 12.5); controller-side failures shall be converted to one. A stack trace is both noise and a place where a cause chain could surface a source excerpt the message deliberately withholds.

## 5. File discovery and format selection
### 5.1 Glob behavior

1. Globs are evaluated relative to the current Jenkins workspace.
2. Absolute paths are prohibited.
3. Matched directories are ignored; only regular files are eligible.
4. Multiple globs in one target group form a union; duplicate matches within that group are de-duplicated.
5. `noMatchBehavior` applies when the union for a target group is empty.
6. Every individual glob shall produce a value-free pattern entry in `details`. When a glob matches no files but the target-group union is non-empty, the plugin shall emit one non-fatal `NOTE` line naming the group and original pattern. The condition shall not increment `warnings`, alter the build result, or independently invoke `noMatchBehavior` in Version 1.0.
7. The relative path used in logs and results shall use `/` as the display separator, independent of agent operating system.
8. File processing order shall be deterministic: target-group order, normalized relative file path order, then substitution order.
9. Patterns are **Ant-style**, with Ant's matching semantics specifically. This is a normative choice, not a stylistic one: `**/appsettings.json` matches a file at the workspace root under Ant, but does **not** under the superficially equivalent `glob:` syntax of `java.nio.file.PathMatcher`, which requires at least one intervening directory. Substituting one for the other would silently stop matching root-level configuration files. A test shall pin this difference.
10. Per-pattern match counts are reported before union de-duplication, so they may overlap within a target group and shall not be summed to derive `filesMatched` (see Section 11.2).

### 5.2 Format selection

- `format: 'json'` requires every matched file to parse as supported JSON.
- `format: 'xml'` requires every matched file to parse as supported XML and every substitution to use a supported `.NET` shorthand.
- `format: 'auto'` may use extension and a non-destructive content probe.
- Supported conventional extensions are `.json`, `.config`, and `.xml`.
- An unrecognized or conflicting format shall fail before modification.

Path semantics depend on the effective format. A target group shall therefore never mix formats.

### 5.3 Unique file ownership in Version 1.0

A workspace file shall be owned by exactly one target group in a step invocation.

1. After all groups are expanded, the implementation shall build a normalized file-to-group index.
2. If any regular file appears in more than one target group, the entire step shall fail during validation before credentials are resolved and before any file is modified.
3. The error shall identify the safe relative file name and both conflicting target-group indexes.
4. Group ordering shall not imply override behavior.
5. Users shall consolidate substitutions for one file set into a single target group or use non-overlapping globs.

Support for aggregating **disjoint** path sets from overlapping groups may be considered for Version 1.1. Last-writer-wins override semantics are not planned unless introduced through an explicit opt-in policy.

## 6. Property path language
### 6.1 Format-dependent dispatch

The effective file format determines which path language applies:

1. For JSON, the JSON grammar in Section 6.2 applies to the complete path.
2. For XML, a raw path beginning with the exact prefix `appSettings.` or `connectionStrings.` is parsed only by the corresponding shorthand in Sections 8.2 or 8.3.
3. XML shorthand has complete precedence over the JSON/general member grammar. The shorthand remainder is not split into dot-separated members.
4. Any other XML path is parsed by the generic XML path grammar in Section 8.7.

The prefix test in rule 2 is purely lexical and is applied before any parsing, so the routing of a given path never depends on document content. This is what makes the guarantee in rule 3 checkable: no document can cause a shorthand path to be reinterpreted as a generic one, or the reverse.

### 6.2 JSON path grammar

```text
path          := member ( ('.' member) | index )*
member        := unquoted | quoted
unquoted      := unquoted-char+
quoted        := "'" quoted-char* "'"
quoted-char   := any character except "'" | "''"
index         := '[' digits ']'
digits        := '0' | nonzero-digit digit*
```

Lexical rules:

1. An unquoted member must not contain `.`, `[`, `]`, `'`, or whitespace.
2. `:`, `-`, and `@` are ordinary characters in an unquoted JSON member; they are not separators.
3. Inside a quoted member, `''` represents one literal single quote.
4. Backslash has no escaping meaning in the property-path language.
5. Array indexes are zero-based and written as `[0]`, `[1]`, and so on.
6. An index attaches to the immediately preceding JSON array selection: `Services[0].Url`.
7. Root JSON arrays are out of scope for Version 1.0; the root must be an object.
8. Path matching is exact and case-sensitive.

### 6.3 Examples

| Path | Meaning |
|---|---|
| `Logging.LogLevel.Default` | JSON nested properties `Logging` → `LogLevel` → `Default`. |
| `Serilog.'MinimumLevel.Default'` | JSON property `Serilog`, then literal key `MinimumLevel.Default`. |
| `Services[0].Url` | `Url` in the first object in JSON array `Services`. |
| `Features.'key with spaces'` | JSON key containing spaces. |
| `appSettings.Logging.LogLevel.Default` | XML shorthand whose literal key is `Logging.LogLevel.Default`. |
| `connectionStrings.Default.@providerName` | XML shorthand selecting the `providerName` attribute. |

### 6.4 Parse errors

Malformed or unsupported paths shall fail before any file is modified. Diagnostics may identify the path and character position but shall never contain source or replacement values.

## 7. Scalar resolution and type behavior
### 7.1 JSON path resolution

1. Each unquoted or quoted member selects an exact object property.
2. Each index selects a zero-based array element.
3. Every intermediate component must exist and have the required container type.
4. The final target must be a scalar: string, number, boolean, or null.
5. A final object or array is a non-scalar error and always fails.
6. Missing object properties and out-of-range indexes are missing-path conditions.
7. The plugin shall support JavaScript-style `//` and `/* ... */` comments as accepted input because .NET configuration files commonly contain them.
8. Duplicate property names within the same JSON object are an ambiguous-document error and always fail, whether or not the duplicate lies on a requested path. Comparison is exact and case-sensitive.
9. Trailing commas may be supported only if explicitly documented and covered by tests; they are not required by this SRS.

### 7.2 Literal typing

The Jenkins-bound `value` is a String. The emitted JSON type is controlled as follows:

#### `type: 'auto'` (default)

The replacement is coerced to the type of the existing JSON scalar:

| Existing target type | Required literal behavior |
|---|---|
| String | Emit the provided literal as a JSON string. |
| Number | Parse the literal as a complete JSON number and emit a JSON number. |
| Boolean | Accept only `true` or `false`, case-insensitive; emit lowercase JSON boolean. |
| Null | Fail and require an explicit `type`, because null has no inferable non-null type. |

#### Explicit type

- `string`: emit a JSON string.
- `number`: require a complete valid JSON number; reject NaN, Infinity, and partial parsing.
- `boolean`: require `true` or `false`, case-insensitive.
- `null`: emit JSON null and require no `value` or `credentialsId`.

Explicit type may deliberately change one JSON scalar type to another.

### 7.3 Credentials in JSON

A credential-backed substitution always emits a JSON string. `credentialsId` may use only `type: 'auto'` or `type: 'string'`; all other types shall be rejected.

- With `type: 'auto'`, the existing JSON target must already be a string. A number, boolean, or null target shall fail with `CONFIG_SUBSTITUTION_CREDENTIAL_TYPE_ACK_REQUIRED`.
- With explicit `type: 'string'`, any existing JSON scalar may be deliberately changed to a string.

This rule prevents a hidden credential value from silently changing the semantic type of a configuration property. The explicit `string` type is the user's acknowledgement of that type change.

### 7.4 XML type constraint

For an effective XML target group, `type: 'auto'` and `type: 'string'` are equivalent and replace the selected XML attribute with a string value. `type: 'number'`, `type: 'boolean'`, and `type: 'null'` are inapplicable to XML and shall fail with `CONFIG_SUBSTITUTION_TYPE_INVALID` during validation, before credential resolution and before any file modification. The plugin shall not interpret XML `null` as an empty attribute, attribute removal, or element removal.

### 7.5 JSON source preservation

The output shall be byte-for-byte identical to the input outside the exact source ranges replaced by substitutions. In particular, the plugin shall preserve:

- comments;
- property order;
- indentation and spaces;
- CRLF or LF line endings;
- trailing newline presence or absence;
- UTF BOM;
- untouched string escape spelling;
- all untouched number spelling.

The replacement token itself may be re-encoded into valid JSON according to the selected type.

---

## 8. XML resolution and .NET configuration mappings
### 8.1 Secure XML requirements

XML processing shall:

- disable external general entities;
- disable external parameter entities;
- disable external DTD loading;
- prohibit network and local external-resource resolution;
- reject unsupported DTD-dependent input safely;
- avoid exposing parser source excerpts in errors.

### 8.2 .NET `appSettings` shorthand

The following forms are required:

```text
appSettings.<key>
appSettings.<key>.@value
```

Resolution is exactly:

```text
/configuration/appSettings/add[@key='<key>']/@value
```

Shorthand grammar:

```text
app-settings-path := 'appSettings.' shorthand-key [ '.@value' ]
shorthand-key      := quoted | raw-literal
raw-literal        := one or more characters other than single quote, CR, or LF
```

`quoted` uses the escaping rules from Section 6.2. A raw literal must not have leading or trailing whitespace; internal whitespace is significant.

1. The shorthand remainder is either exactly one quoted member or raw literal text, optionally followed by exactly one recognized terminal selector.
2. A quoted key may contain dots, selector-looking suffixes, and escaped single quotes using `''`. Two adjacent quoted members, such as `appSettings.'A'.'B'`, are invalid.
3. For a raw remainder, the parser greedily recognizes at most one final `.@value` as the terminal selector. Any earlier `.@value` remains part of the key. Thus `appSettings.Foo.@value.@value` targets key `Foo.@value` and its `value` attribute.
4. Without an explicit selector, the target is still `@value`.
5. Dots and colons in the key are literal without quoting.
6. `appSettings.@value` has no terminal-selector separator and therefore targets the literal key `@value`.
7. To target a key whose complete raw name ends in `.@value` without supplying an explicit selector, quote the complete key: `appSettings.'Literal.Key.Ending.@value'`.
8. Matching is case-sensitive and exact.
9. Only direct `<add>` children of the top-level `/configuration/appSettings` element are considered.
10. `<clear/>` and `<remove/>` elements are ignored.
11. A key found only inside a nested `<location>` is not matched by this shorthand.
12. If `<appSettings file="...">` is present, the external file is not followed. Only inline `<add>` entries are eligible.
13. More than one matching `<add>` element is an ambiguous-path error and always fails.

Examples:

```text
appSettings.ApiUrl
appSettings.BankApi:Key
appSettings.Logging.LogLevel.Default
appSettings.@value
appSettings.'Literal.Key.Ending.@value'
appSettings.'Foo'.@value
appSettings.Foo.@value.@value
```

### 8.3 .NET `connectionStrings` shorthand

The following forms are required:

```text
connectionStrings.<name>
connectionStrings.<name>.@connectionString
connectionStrings.<name>.@providerName
```

Resolution is:

```text
/configuration/connectionStrings/add[@name='<name>']/@connectionString
/configuration/connectionStrings/add[@name='<name>']/@providerName
```

Shorthand grammar:

```text
connection-strings-path := 'connectionStrings.' shorthand-name [ terminal-selector ]
terminal-selector       := '.@connectionString' | '.@providerName'
shorthand-name          := quoted | raw-literal
raw-literal             := one or more characters other than single quote, CR, or LF
```

`quoted` uses the escaping rules from Section 6.2. A raw literal must not have leading or trailing whitespace; internal whitespace is significant.

1. The shorthand remainder is either exactly one quoted member or raw literal text, optionally followed by exactly one recognized terminal selector.
2. A quoted name may contain dots, selector-looking suffixes, and escaped single quotes using `''`. Multiple quoted members are invalid.
3. For a raw remainder, the parser greedily recognizes at most one final terminal selector. Earlier selector-looking text remains part of the name.
4. Without a terminal selector, the target is `@connectionString`.
5. Quoting disambiguates a complete name ending in `.@connectionString` or `.@providerName` when no explicit selector is intended.
6. Matching is case-sensitive and exact.
7. Only direct `<add>` children of top-level `/configuration/connectionStrings` are considered.
8. `<clear/>` and `<remove/>` elements are ignored.
9. More than one matching `<add>` element is ambiguous and always fails.
10. A missing requested attribute is a missing-path condition; Version 1.0 does not create it.

Examples:

```text
connectionStrings.Default
connectionStrings.Default.@providerName
connectionStrings.Reporting.Primary.@connectionString
connectionStrings.'Literal.Name.Ending.@providerName'
```

### 8.4 Supported XML document shape and namespaces

Rules 1, 2 and 4 constrain **the shorthands of Sections 8.2 and 8.3 only**. Generic paths (Section 8.7) impose no document-shape requirement and are governed by their own matching rules.

1. For the shorthands, the document element must be the unprefixed lexical name `configuration`.
2. The shorthand matches unprefixed lexical names `appSettings`, `connectionStrings`, `add`, `key`, `name`, `value`, `connectionString`, and `providerName`.
3. Namespace declarations may be present elsewhere in the document and do not by themselves make it unsupported.
4. A namespace prefix on any element or attribute required by the shorthand prevents that node from matching; namespace-URI resolution and prefix remapping are out of scope, for shorthand and generic paths alike.
5. XDT declarations such as `xmlns:xdt="..."` are permitted as unrelated source text, but XDT transform directives are not executed.

### 8.5 XML source preservation

The output shall be byte-for-byte identical to the input outside exact replacement ranges. The plugin shall preserve:

- comments and processing instructions;
- whitespace and indentation;
- attribute order;
- quote style of untouched attributes;
- self-closing versus expanded elements;
- XML declaration spelling;
- DTD/comment placement when the document is otherwise accepted;
- CRLF or LF line endings;
- UTF-8 BOM state;
- untouched entity spelling.

Replacement XML attribute content shall be escaped correctly for its source quote context; replacement element text shall be escaped per Section 8.7.3.

An element-text replacement (Section 8.7) replaces the **whole** character range between the element's start and end tags, including any leading or trailing whitespace and line breaks in that range. Replacing the text of a pretty-printed element therefore collapses it onto one line. This follows from the range being the target, and is the only case in which the plugin alters whitespace; it shall be documented in user-facing help.

### 8.6 Explicit XDT exclusion

The plugin shall document prominently that “configuration substitution” does not mean Microsoft XDT transformation. XDT files, transform directives, locator rules, and any form of structural change — element insertion, removal, reordering, or attribute creation — remain out of scope.

The distinction is that the plugin only ever **replaces the contents of a source range that already exists**. Generic XML paths (Section 8.7) widen what can be addressed; they do not widen what can be done to it.

### 8.7 Generic XML paths

Any XML path not claimed by the shorthands (Section 6.1 rule 2) is a generic path.

#### 8.7.1 Grammar

```text
path     := element ( '.' element )* '.' selector
element  := member index?
member   := unquoted | quoted
unquoted := one or more characters other than '.', '[', ']', '\'' and whitespace
quoted   := '...'   with '' representing one literal single quote
index    := '[' digits ']'
selector := '@' member | '#text'
```

A path that does not end in a selector is a `CONFIG_SUBSTITUTION_PATH_SYNTAX` error. The terminal selector is mandatory because XML has no single obvious “value of an element”: without it, `configuration.appSettings` would be ambiguous between the element, its text, and an attribute on it.

#### 8.7.2 Resolution rules

1. The first element step matches the **document element**. Each subsequent step matches **direct children only**; there is no descendant search.
2. Element and attribute members match the **exact case-sensitive lexical qualified name**, prefix included. `xdt:add` matches `xdt:add` and nothing else. Namespace-URI resolution and prefix remapping are out of scope (Section 8.4 rule 4).
3. `[n]` selects the zero-based *n*-th occurrence among same-name direct children, in document order.
4. A step **without** an index that matches more than one candidate fails with `CONFIG_SUBSTITUTION_PATH_AMBIGUOUS`. It is never resolved by taking the first match. The error message shall name an indexed form that would disambiguate it.
5. `[n]` beyond the number of matching siblings fails with `CONFIG_SUBSTITUTION_PATH_MISSING`.
6. A step matching no candidate fails with `CONFIG_SUBSTITUTION_PATH_MISSING`.
7. `@name` targets that attribute's value range on the resolved element. An absent attribute is `CONFIG_SUBSTITUTION_PATH_MISSING`; attributes are **never created**.
8. `#text` targets the character range between the resolved element's start and end tags. It is valid only where that range contains no markup. An element with any child element, comment, CDATA section or processing instruction fails with `CONFIG_SUBSTITUTION_PATH_AMBIGUOUS`, because replacing the range would delete markup the user did not address.
9. `#text` on a self-closing element fails with `CONFIG_SUBSTITUTION_PATH_MISSING`. An empty expanded element such as `<x></x>` has a valid, zero-length text range.
10. Generic paths may address content the shorthands deliberately skip, including entries nested inside `<location>`.

#### 8.7.3 Replacement encoding

Attribute targets are escaped per Section 8.2's rules, driven by the quote character observed at the target site. Text targets escape `&`, `<` and `>` only: quotes carry no structural meaning in element content, and escaping them would change bytes the user did not ask to change.

#### 8.7.4 Rationale for failing on ambiguity

Rules 4 and 8 are the load-bearing safety rules of this section. Both describe situations where a plausible answer exists — the first matching sibling; the text between the tags — and both refuse it. Silently choosing would make the result depend on document ordering or on markup the path does not mention, neither of which is visible in the path the user wrote. Preferring a failed build over a wrong substitution is the same trade-off Section 8.5 makes for source preservation.

## 9. Error and warning behavior
### 9.1 Configurable conditions

#### `noMatchBehavior`

- `fail`: fail the step.
- `warn`: emit one value-free warning and continue; build result remains unchanged.
- `ignore`: continue silently except for counts in the returned result.

This is evaluated when a target group's union of globs resolves to no file. Per-glob required-match enforcement is deferred.

#### `missingPathBehavior`

Evaluated per substitution per matched file:

- `fail`: fail validation before the write phase.
- `warn`: emit a value-free warning and continue; build result remains unchanged.
- `ignore`: continue silently except for counts in the result.

A `warn` setting never marks the build `UNSTABLE`. Teams requiring CI gating shall use `fail` or inspect the returned result explicitly.

### 9.2 Conditions that always fail

The following are structural, security, or programming errors and shall not be downgraded to warnings in Version 1.0:

- malformed or unsupported path syntax;
- an ambiguous generic XML path (Section 8.7.2 rules 4 and 8);
- malformed or unsupported XML/JSON;
- unsupported or non-UTF-8 declared encoding;
- JSON path resolving to a non-scalar;
- ambiguous XML shorthand match;
- duplicate canonical substitution path within one target group;
- one file being resolved by more than one target group;
- duplicate JSON property name within one object;
- credential-backed `type: auto` targeting a non-string JSON scalar;
- an XML substitution using `type: number`, `boolean`, or `null`;
- type coercion failure;
- missing, inaccessible, or wrong-type credential;
- path traversal, symlink, junction, or reparse-point violation;
- overlapping replacement source ranges;
- source file changing between planning and commit;
- I/O or atomic replacement failure.

### 9.3 Validation and commit phases

The implementation shall perform a complete validation/planning phase before modifying any file. Validation includes file discovery, unique group ownership, format checks, path parsing, path resolution, scalar/type validation, credential availability, duplicate-key checks, source-range conflict checks, and source snapshot creation.

After successful validation, files are committed independently using atomic sibling replacement. Global all-or-nothing atomicity across multiple files is not promised. If a file commit fails after one or more earlier files were committed, the step shall throw `CONFIG_SUBSTITUTION_WRITE_FAILED`; a failing step does not return a result map. Both the thrown message and the build log shall identify, using value-free workspace-relative paths, the file whose commit failed and every file already committed. The failed file shall retain its pre-step bytes, and every temporary file created for the failed commit shall be removed in `finally`.

No file shall be modified during `dryRun`.

## 10. Source-preserving architecture
### 10.1 Required strategy

The implementation shall use a **locate-then-splice** design rather than parse-then-serialize.

1. Parse or tokenize the document to validate structure and logically resolve each target.
2. Determine the exact source range of the existing JSON scalar token or supported XML attribute value.
3. Serialize only the replacement scalar for that context.
4. Apply non-overlapping edits to the original content in descending source-offset order.
5. Preserve all content outside edited ranges exactly.

The parser may be used as a locator, but the implementation shall not re-emit the full JSON or XML document through an object model, DOM transformer, or pretty printer.

### 10.2 Implementation validation spike

Before final estimation, developers shall create a small architecture spike that proves reliable source-range location for:

- JSON strings, numbers, booleans, and null;
- duplicate-key detection during a streaming pass;
- JSON comments before and after targets;
- XML `appSettings` and `connectionStrings` attributes using single and double quotes;
- XML entity-containing attribute values;
- UTF-8 with and without BOM;
- clean rejection of UTF-16 LE/BE and legacy declared encodings;
- CRLF and LF inputs.

Jackson streaming and StAX may be used, but no SRS requirement assumes their reported offsets alone are sufficient. A small lexical range locator may be combined with structural parsing. The ADR shall record the chosen approach and verified offset semantics.

### 10.3 Encoding support

Version 1.0 shall write only UTF-8 documents, with or without a UTF-8 BOM.

- JSON UTF-8 with or without BOM is supported.
- XML UTF-8 with or without BOM is supported.
- An XML declaration may be absent or may declare `encoding="utf-8"` using any ASCII letter case and either quote style.
- XML declarations naming `us-ascii`, `iso-8859-1`, `windows-1252`, UTF-16, UTF-32, or any other encoding shall fail with `CONFIG_SUBSTITUTION_UNSUPPORTED_ENCODING`, even when the current byte sequence is ASCII-only.
- A BOM/declaration conflict shall fail before parsing or modification.
- The original UTF-8 BOM presence, newline style, and all unaffected bytes shall be preserved.

Supporting legacy declared encodings by conditionally inspecting replacement characters or rewriting declarations is deliberately out of scope for Version 1.0.

### 10.4 Exact-preservation test oracle

For each successful test fixture, the test suite shall verify that output bytes differ from input bytes only in the planned replacement ranges. This is the primary source-preservation acceptance oracle.

## 11. Dry run and result map
### 11.1 `dryRun`

With `dryRun: true`, the plugin shall perform all validation that does not require writing and shall:

- resolve file globs;
- parse files;
- resolve paths;
- validate literal types;
- resolve and authorize credential IDs without exposing values;
- compute planned source ranges;
- detect unchanged substitutions;
- return the same summary categories as a real run;
- not create temporary files;
- not modify content, permissions, timestamps, or extended attributes.

### 11.2 Result map

The step shall return a `Map<String, Object>` composed only of sandbox-safe, CPS-serializable data: standard Java `LinkedHashMap`, `ArrayList`, `String`, `Boolean`, integral numeric counts, and null. No custom plugin-defined object may appear anywhere in the returned graph. It shall contain no source values, replacement values, or credential IDs. Minimum keys:

| Key | Meaning |
|---|---|
| `dryRun` | Whether execution was validation-only. |
| `targetGroups` | Number of target groups evaluated. |
| `patternsEvaluated` | Number of globs evaluated. |
| `patternsUnmatched` | Number of globs with no match. |
| `filesMatched` | Number of unique files matched. |
| `filesPlanned` | Files that would change after successful validation. |
| `filesChanged` | Files actually committed. Zero in dry run. |
| `filesUnchanged` | Files requiring no byte change. |
| `substitutionsMatched` | Successful path resolutions. |
| `substitutionsMissing` | Missing-path occurrences. |
| `warnings` | Number of warning-level conditions. |
| `details` | Stable value-free pattern and substitution records as defined below. |

`details` shall be an `ArrayList` of `LinkedHashMap` entries. Every entry shall contain the same seven keys, in this order, and no value-bearing data:

| Key | Type | Meaning |
|---|---|---|
| `kind` | String | `pattern` or `substitution`. |
| `group` | Integer | One-based target-group index used consistently in user-facing logs and results. |
| `pattern` | String or null | Original glob exactly as supplied for a `pattern` entry; null for a `substitution` entry. |
| `file` | String or null | Workspace-relative, `/`-separated file name for a `substitution` entry; null for a `pattern` entry. |
| `path` | String or null | Original property-path expression exactly as supplied for a `substitution` entry; null for a `pattern` entry. |
| `status` | String | For patterns: `matched` or `unmatched`. For substitutions: `planned`, `changed`, `unchanged`, or `missing`. |
| `matches` | Integer or null | Number of unique regular files matched by a pattern; null for a substitution entry. |

Status semantics are stable public API:

- `planned`: the substitution would change bytes in a successful dry run;
- `changed`: the substitution changed bytes in a successful real run;
- `unchanged`: the target resolved but its serialized replacement was byte-identical;
- `missing`: the target did not resolve and execution continued under `warn` or `ignore`;
- `matched` / `unmatched`: the individual glob matched one or more / zero eligible regular files.

Pattern entries shall appear in target-group and declared-glob order. Substitution entries shall follow deterministic processing order from Section 5.1. Each pattern record reports matches for that declared glob before union de-duplication; therefore, per-pattern counts may overlap within a target group and shall not be summed to derive `filesMatched`. No status record shall contain current values, replacement values, credential IDs, source snippets, hashes, or temporary-file names.

The result map shall not be automatically archived and shall not attach a persistent Run action in Version 1.0. Normal documented access, including `result['filesChanged']`, ordinary Groovy property access such as `result.filesChanged`, and iteration over nested maps/lists, shall work in a sandboxed Pipeline without Script Approval or administrator intervention. Bracket access remains the canonical documentation style because it makes the Map contract explicit.

---

## 12. Credentials and secret-handling requirements
### 12.1 Supported credential type

Version 1.0 supports `org.jenkinsci.plugins.plaincredentials.StringCredentials` only.

Credential lookup shall:

- use the current `Run`/`Item` context so folder-scoped credentials resolve correctly;
- use `CredentialsProvider.findCredentialById(...)` or the currently recommended equivalent;
- participate in credentials usage tracking;
- fail generically when a credential is absent, inaccessible, or the wrong type;
- avoid revealing whether an unauthorized credential ID exists.

### 12.2 Jenkins UI and enumeration safety

Any credential selector used by Snippet Generator or future UI shall:

- accept `@AncestorInPath Item` context;
- perform the required item permission check before enumeration;
- list only accessible Secret Text credentials;
- avoid exposing credential IDs to unauthorized users.

### 12.3 Pipeline argument warning

Documentation shall display a prominent warning:

> Do not hard-code a secret in `value:`. A literal written into the step configuration is persisted with the build and displayed by Pipeline metadata and visualization features.

**Correction (0.7, from review).** Earlier versions stated this absolutely — "never place a secret in `value:`". That is too broad. Referring to a credential through an environment variable bound by `withCredentials` is a safe and recommended pattern, and is the natural approach when one credential supplies several values, such as a username and password pair:

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

`withCredentials` masks the value in the build log, and `ArgumentsActionImpl.replaceSensitiveVariables` substitutes the literal with `${MY_PASS}` before the step arguments are persisted, so the plaintext does not reach stored build metadata. The requirement is therefore against **hard-coding** a secret, not against use of the `value` parameter.

Documentation shall also state that the transformed target file and its temporary sibling necessarily contain the resolved secret.

### 12.4 Remoting threat model

Files reside on the agent, so the resolved secret must reach that trusted agent to perform the substitution. The plugin shall:

- perform all glob expansion, parsing, range location, and writing on the workspace-owning agent;
- use `FilePath.act(...)` with `jenkins.agents.ControllerToAgentFileCallable` or an equivalent supported controller-to-agent callable;
- never persist the resolved secret in the step configuration, `ArgumentsAction`, result map, Run action, or build log;
- avoid retaining plaintext in controller-side `String` fields longer than necessary;
- treat Jenkins agents as trusted execution environments, consistent with Jenkins credential use on agents.

**Measured behavior (ADR-003, gate 2).** Version 0.6 required this to be established rather than assumed. It has been, against a real Jenkins 2.541.3 and a real online agent:

- `hudson.util.Secret` does **not** encrypt under Java serialization. Its plaintext is present in the serialized bytes, and a callable carrying a `Secret` field is byte-for-byte as revealing as one carrying a `String`.
- The agent recovers the plaintext without any access to the controller's `$JENKINS_HOME/secrets`, which is only possible if the plaintext itself crossed the channel.
- `Secret` does protect the **at-rest** form: `getEncryptedValue()` is ciphertext, and XStream persists it encrypted, which is what keeps secrets out of `config.xml` and `build.xml`.

Confidentiality over remoting therefore rests entirely on two things, both the operator's responsibility: the transport security of the agent channel, and the trustworthiness of the agent host. This is consistent with how Jenkins credentials binding already behaves, so the plugin introduces no new exposure.

Documentation shall state this plainly and shall not imply that using `credentialsId` keeps the secret on the controller. The plugin shall not attempt to detect or refuse an insecure agent channel: Jenkins exposes no reliable supported way to determine whether a given channel is encrypted, and a check that silently passes on a misconfigured channel would be worse than none.

### 12.5 Exception and logging sanitization

No parser, tokenizer, serializer, I/O library, or credentials-library exception message shall be printed verbatim to the build log.

All exceptions shall be caught at the plugin boundary and rewritten to value-free messages containing only safe fields such as:

- relative file path;
- property path expression;
- error category;
- line/column or character offset when it does not include source excerpts;
- a generated correlation identifier for controller logs.

The plugin shall not log:

- current source values;
- replacement literals;
- credential values;
- serialized replacement text;
- parser source snippets;
- temporary-file contents.

A mandatory security test shall inject a unique known secret, force failures at multiple stages, and assert that the token appears nowhere in build logs, exception text, returned results, Pipeline node arguments, or persisted build XML.

#### 12.5.1 Masking carrier type

Resolved replacement values shall be carried in a type whose `toString()` returns a fixed placeholder, so that accidental string conversion cannot expose a secret. The plaintext shall be reachable only through a single, conspicuously named accessor.

`hudson.util.Secret` shall not be relied on for this. Measurement (ADR-003) confirms that `Secret.toString()` returns the plaintext, so `"value=" + secret`, `String.valueOf(secret)` and `String.format("%s", secret)` all leak it. A single careless log statement or a concatenated exception message would defeat this section entirely, which means the "never log a value" rule **cannot be enforced by the type system if the carrying type is `Secret`**.

Requirements:

1. Literal values shall be wrapped in the same carrier as credential-backed values, so there is one code path and no per-call-site judgement about which values are sensitive.
2. No type that holds a replacement value, including intermediate planning structures, may expose that value through a generated or default `toString()`. Java records are a specific hazard here: the compiler-generated `toString()` prints every component.
3. A test shall assert the masking behavior of the carrier type directly, independently of build-log assertions.

#### 12.5.2 Crossing the agent channel

A failure raised on the agent shall reach the controller as an `AbortException` carrying the already value-free message and **no cause**. Two properties follow, and both are required:

1. Jenkins prints an `AbortException` as its message alone, with no stack trace, on every surface. This is what makes Section 4.7.4 achievable without per-surface handling.
2. Dropping the cause is deliberate. A cause chain is precisely where a parser message — which may embed a source excerpt, and therefore a resolved credential — would resurface after this section took care to exclude it.

### 12.6 Secret lifecycle after substitution

Credential substitution changes the workspace file into secret-bearing material. The plugin cannot prevent later Pipeline steps from copying that file into longer-lived storage.

User documentation shall instruct teams to:

- perform credential-backed substitution as late as possible, after any archive, stash, test-report, cache, or packaging step that must not contain the secret;
- place it immediately before the deploy, launch, or consumption action;
- never `archiveArtifacts`, `stash`, fingerprint, publish, cache, upload, or bake the post-substitution file into an image layer unless that disclosure is explicitly intended and the destination is access-controlled;
- restore or delete the secret-bearing workspace file after use where practical; and
- understand that `deleteDir()` or workspace cleanup does not retroactively remove an artifact, stash, cache entry, image layer, or external upload created earlier.

When `dryRun` is false, one or more credential-backed substitutions are included in the validated commit plan, and `acknowledgeSecretLifecycle` is false, the step shall emit exactly one value-free notice per invocation before the commit phase:

```text
[configSubstitution] SECURITY NOTICE: This step writes credential-backed values to workspace files. Do not archive or stash those files after substitution. Set acknowledgeSecretLifecycle: true only after reviewing this risk.
```

Setting `acknowledgeSecretLifecycle: true` suppresses only this repeated notice. It shall not suppress errors, policy warnings, documentation, secret-leak protections, or result fields, and it shall not be inferred from prior builds. The notice shall not identify credential IDs or values, mark the build unstable, or increment the warning count returned for `noMatchBehavior` or `missingPathBehavior`.

## 13. Agent file safety, symlinks, and atomic writes
### 13.1 Workspace confinement

1. All matched paths must be workspace-relative. Absolute, drive-qualified and UNC paths are rejected.
2. The agent-side implementation shall verify each resolved file remains a descendant of the workspace after link resolution.
3. **Confinement shall be decided on the fully resolved real path**, not on file attributes and not on path strings alone. A lexical screen for `..` may run first as a cheap rejection, but it is not sufficient on its own.
4. Symbolic links that escape the workspace shall be rejected.
5. Windows directory junctions and NTFS reparse points shall be treated as link-like traversal risks and covered by Windows tests.
6. The final target shall be a regular file as reported with `NOFOLLOW_LINKS`. A symbolic link, junction or other reparse point is refused as a target **even when it resolves inside the workspace**, because the atomic replacement of Section 13.2 renames over the target and would therefore replace the link itself rather than the file it designates. Links appearing as intermediate path components are permitted provided the fully resolved path remains inside the workspace.
7. A file that does not exist shall be reported as `CONFIG_SUBSTITUTION_FILE_NOT_FOUND`, distinct from `CONFIG_SUBSTITUTION_WORKSPACE_ESCAPE`. Reporting a typo as an attempted security violation trains users to ignore the message that matters.
8. The target shall be writable by the build user on every platform, checked before any temporary file is created. This is deliberately stricter than the platform minimum: a POSIX `rename()` requires write permission only on the *directory*, so without this check a read-only `0444` configuration file would be silently replaced on Linux while the identical build failed on Windows.

**Measured behavior (ADR-004, gate 3).** Rule 3 exists because `Files.isSymbolicLink()` returns **false** for a Windows directory junction. An implementation screening for symbolic links — the obvious reading of rule 4, and the first thing most developers would write — would let a junction through. The attack needs no `..` and no absolute path: with an intermediate directory replaced by a junction, a lexically innocent relative path resolves outside the workspace. Resolving the real path catches junctions and symbolic links alike, and needs no platform-specific branch.

### 13.2 Temporary files

For each changed file:

- create a uniquely named sibling temporary file on the same filesystem;
- use restrictive permissions where supported, preferably POSIX `0600` during creation;
- on Windows, inherit no broader permissions than required and preserve the target ACL where feasible;
- write and flush the complete replacement content;
- preserve the original file's required permissions/attributes;
- flush the content to the storage device before the rename, so that a crash cannot leave the target naming content that was never persisted;
- atomically replace the original where the platform supports same-filesystem atomic move;
- delete the temporary file in `finally` on every failure path;
- never place the temporary file in a global system temporary directory.

The temporary file is a sibling of the target for two reasons, and the second is the security one: a rename is only atomic within a filesystem, and the temporary file transiently holds the same content as the target — including any resolved credential — so it must inherit the workspace's access controls rather than those of a shared temporary directory.

#### 13.2.1 Replacement fallback and bounded retry

Two distinct failure modes, both measured (ADR-002, gate 4):

**Atomic move unavailable.** The implementation shall fall back to a single replace-existing move, which is still one filesystem operation. It shall **never** delete the target and then rename: that trades a very small window for one in which the file does not exist at all, and a failure inside it destroys the user's configuration rather than leaving it intact.

**Target momentarily held open.** On Windows, any open handle to the target blocks the replacement — measured for an ordinary `FileInputStream` as well as a NIO `FileChannel`. Antivirus scanners and IIS open `web.config` routinely and briefly, so a single-attempt implementation would convert a background virus scan into a failed build.

The implementation shall therefore retry the replacement a bounded number of times with a short backoff, within a total budget of approximately one second, before failing with `CONFIG_SUBSTITUTION_WRITE_FAILED`. Constraints:

1. The retry shall not extend to failures that cannot succeed on repetition, such as a read-only or unwritable target. Those are refused under Section 13.1 before any temporary file is created.
2. The budget shall be bounded so that a target held open for the duration still fails promptly rather than hanging.
3. An interruption shall propagate rather than be absorbed into a backoff, so a Pipeline abort is honored (Section 16 item 7).
4. Whatever the outcome, the original bytes shall survive and no temporary file shall be left behind.

Measured effect: a handle released after 60 ms is recovered from; a permanently held target fails in roughly 530 ms on Windows. On Linux the retry never engages, because POSIX `rename()` over an open file succeeds and leaves existing readers on the old inode — the same probe completes in 14 to 19 ms, which is the measured absence of the backoff cycles rather than an inference about how `rename()` should behave.

#### 13.2.2 Permission preservation

A rename replaces the target, so the replacement carries the temporary file's access controls unless they are transferred deliberately.

- On POSIX, the original file's mode shall be copied onto the replacement before the move. Verified: mode `rw-r-----` survives.
- On Windows, the replacement inherits the workspace directory's ACL at creation, which is what any file in that directory would receive and almost certainly what the original had. Copying the target's *effective* ACL is deliberately not attempted, because it would convert inherited entries into explicit ones and permanently detach the file from directory inheritance — a silent and hard-to-reverse permission change made on the user's behalf.
- **Known limitation:** explicit, non-inherited ACL entries set directly on a Windows configuration file are not carried across a replacement. This shall be documented.

### 13.3 Idempotency

If all resolved replacement values are already represented by identical target bytes, the plugin shall:

- skip temporary-file creation and replacement;
- leave file modification time unchanged;
- count the file as `filesUnchanged`;
- succeed unless another validation error exists.

Idempotency is a separate acceptance criterion.

### 13.4 External concurrency and snapshot validation

The plugin does not promise to merge changes made by another process during execution. It shall **detect and reject** a changed source snapshot before commit:

1. During planning, compute and retain a SHA-256 digest of the exact original bytes for each file.
2. Immediately before creating the sibling temporary file, read the current file again and compare its digest to the planned digest.
3. If the digest differs, fail that file with `CONFIG_SUBSTITUTION_SOURCE_CHANGED` before writing or renaming anything.
4. The design does not claim to eliminate the inherent race window between the final digest check and the atomic rename.
5. Jenkins' normal separate concurrent-build workspaces remain supported.

The implementation shall provide a package-private or dependency-injected **pre-commit test seam** that lets tests mutate the target after planning and before the digest re-check. The seam shall not be part of the public Pipeline API and shall have no behavior in production unless supplied by tests.

## 14. Jenkins engineering requirements
### 14.1 Baseline and Java

- Minimum Jenkins core: **2.541.3**.
- Java source/target release: **17**.
- Development and CI shall also test on Java 21 where supported.
- Rationale: 2.541.3 remains a currently recommended LTS baseline and supports Java 17, while the 2.555 LTS line requires Java 21 or newer as of this specification date.

The baseline shall be rechecked before Version 1.0 is released, because Jenkins baseline recommendations and update-center support move over time. As of Version 0.7 it is confirmed in use: the plugin builds against 2.541.3 with `maven.compiler.release` 17, and the full suite passes on Windows 10 and AlmaLinux 9.

### 14.2 Maven and dependencies

- Use the current Jenkins plugin parent POM compatible with the chosen baseline.
- Import `io.jenkins.tools.bom:bom-2.541.x` at a current compatible release.
- Declare managed plugin dependencies without explicit versions where covered by the BOM.
- Expected dependencies include:
  - `workflow-step-api`;
  - `credentials`;
  - `plain-credentials`;
  - the Jackson API plugin `io.jenkins.plugins:jackson3-api` (note the groupId differs from the former `org.jenkins-ci.plugins:jackson2-api`);
  - supporting test dependencies from the plugin BOM.
- **Depend on the Jackson API plugin, never on the Jackson library directly.** Concretely: declare `io.jenkins.plugins:jackson3-api` and do not declare any artifact under the `tools.jackson.core` or `tools.jackson.dataformat` groups (for example `tools.jackson.core:jackson-core`) in this POM. Those coordinates are supplied transitively by the API plugin, which is what keeps a single Jackson on the classpath at runtime.
  - The Jackson 3 Maven groupId and its Java package prefix happen to be the same string, `tools.jackson.*` — as was also true of Jackson 2's `com.fasterxml.jackson.*`. The rule above is about Maven coordinates; the corresponding *import* prefix in source is likewise `tools.jackson.*`, relocated from `com.fasterxml.jackson.*`.
- Jackson 3 raises **unchecked** exceptions (`tools.jackson.core.exc.StreamReadException` extends `RuntimeException`), so the compiler will not enforce Section 12.5's sanitization boundary. Every call into Jackson shall be enclosed by a catch of `JacksonException`, and a test shall assert the exception *type* that crosses the engine boundary rather than only that parsing failed.
- Enable dependency and bundled-artifact checks supplied by the modern plugin parent where practical.
- Configure Dependabot or Renovate for dependency updates.

### 14.3 Pipeline model

- Use a dedicated `Step`, `StepDescriptor`, and `StepExecution` appropriate for non-blocking Pipeline execution.
- Override `StepDescriptor.getFunctionName()` to return `configSubstitution`; do not rely on `@Symbol` to name the custom step.
- Use concrete nested `Describable` classes for target groups and substitutions so Snippet Generator renders stable field-based map syntax; symbols are not required for these non-polymorphic nested types.
- Avoid bare `Object` fields.
- Return only sandbox-safe map/list/primitive data from the step; do not expose a plugin-defined result POJO to Pipeline scripts.
- Ensure all remoting-transferred classes are serializable and compatible with JEP-200 constraints.
- The step and documented result-map access shall not require Script Approval for sandboxed Pipeline usage.

### 14.4 Freestyle decision

Freestyle support is deliberately excluded from Version 1.0 to keep the first release focused on the requested Pipeline use case and to avoid duplicating security-sensitive UI configuration. The core substitution engine shall not depend on Pipeline classes, allowing a future `SimpleBuildStep` adapter.

---

## 15. Logging and user experience
### 15.1 Normal log output

Default log output shall be concise and value-free. Example:

```text
[configSubstitution] Matched 2 files in 2 target groups.
[configSubstitution] web.config: 2 substitutions planned, 0 missing.
[configSubstitution] appsettings.json: 2 substitutions planned, 0 missing.
[configSubstitution] Changed 2 files; 0 unchanged; 0 warnings.
```

Individual unmatched-glob example when the same target group still matches another pattern:

```text
[configSubstitution] NOTE: target group 1: pattern 'srv/**/web.config' matched no files.
```

This note is informational: it does not increment the warning count, change the build result, or independently invoke `noMatchBehavior`.

Dry run example:

```text
[configSubstitution] Dry run: 2 files would change; no files were written.
```

Credential-backed invocation example:

```text
[configSubstitution] SECURITY NOTICE: This step writes credential-backed values to workspace files. Do not archive or stash those files after substitution. Set acknowledgeSecretLifecycle: true only after reviewing this risk.
```

### 15.2 Warnings

Warnings shall identify the relative file and path, but not values. Example:

```text
[configSubstitution] WARNING: src/App/appsettings.json: path 'FeatureFlags.Payments.Enabled' was not found.
```

### 15.3 Error messages

Errors shall be actionable and categorized, for example:

- `CONFIG_SUBSTITUTION_PATH_SYNTAX`
- `CONFIG_SUBSTITUTION_XML_PATH_UNSUPPORTED` — retained in the enumeration for compatibility, but no longer reachable from a step invocation since Section 8.7 made every XML path either shorthand or generic
- `CONFIG_SUBSTITUTION_TARGET_GROUP_OVERLAP`
- `CONFIG_SUBSTITUTION_FILE_NOT_FOUND`
- `CONFIG_SUBSTITUTION_PATH_MISSING`
- `CONFIG_SUBSTITUTION_PATH_AMBIGUOUS`
- `CONFIG_SUBSTITUTION_NON_SCALAR`
- `CONFIG_SUBSTITUTION_TYPE_INVALID`
- `CONFIG_SUBSTITUTION_CREDENTIAL_TYPE_ACK_REQUIRED`
- `CONFIG_SUBSTITUTION_DUPLICATE_JSON_KEY`
- `CONFIG_SUBSTITUTION_CREDENTIAL_UNAVAILABLE`
- `CONFIG_SUBSTITUTION_UNSUPPORTED_ENCODING`
- `CONFIG_SUBSTITUTION_WORKSPACE_ESCAPE`
- `CONFIG_SUBSTITUTION_PARSE_FAILED`
- `CONFIG_SUBSTITUTION_SOURCE_CHANGED`
- `CONFIG_SUBSTITUTION_WRITE_FAILED`

No error shall reveal a current or replacement value.

---

## 16. Performance and operational requirements
1. A single step invocation shall scan each target group once.
2. Files shall be processed on the agent; full file contents shall not be transferred to the controller.
3. The implementation shall support files up to at least 10 MiB by default.
4. A configurable hard safety limit may be added; exceeding it shall fail before modification.
5. Memory use should be bounded to a small multiple of the largest processed file, not the sum of all workspace files.
6. Multiple substitutions in one file shall be applied in one atomic file replacement.
7. The step shall respond correctly to Pipeline interruption and delete temporary files where execution reaches cleanup code.
8. No external executable, PowerShell, Node.js, .NET runtime, or Python runtime may be required.

---

## 17. Testing requirements
### 17.1 Test pyramid

Use:

- extensive unit tests for JSON grammar, XML shorthand parsing, path resolution, type coercion, duplicate-key detection, range location, escaping, and byte preservation;
- Jenkins integration tests for Pipeline binding, `getFunctionName()`, Snippet Generator, credentials scope, permissions, remoting, logs, result serialization, and restart behavior where relevant;
- higher-realism `JenkinsSessionRule` or `RealJenkinsRule` tests for selected security and agent scenarios;
- minimal end-to-end UI tests only where they add unique coverage.

Use the modern Jenkins test harness and JUnit configuration generated by the current plugin archetype.

**The verification command is `mvn verify`, not `mvn test`.** Static analysis, the plugin parent's checks and HPI packaging all bind to `verify`, and every one of them has caught a defect that `mvn test` reported as green: two null-dereference paths, and an HPI packaging failure caused by a missing `index.jelly`. A local `mvn test` run is not evidence that CI will pass, and shall not be reported as such.

**Cross-platform probes shall assert invariants and record behavior, never assert one platform's outcome.** Where behavior legitimately differs between Windows and Linux, a probe shall assert only what must hold everywhere — bounded execution, original bytes intact, no residue — and record the observed outcome as evidence. Asserting a platform-specific result produces a test that passes on the machine it was written on and fails elsewhere for no defect. This rule was written after exactly that happened to the Section 13.2.1 retry probe.

### 17.2 Required operating-system matrix

CI shall include:

- Linux agent/controller configuration;
- Windows agent/controller or Windows agent configuration;
- the JDK versions the Jenkins CI infrastructure accepts (currently 21 and 25).

**Note on the build JDK.** ci.jenkins.io accepts only JDK 21 and 25, so the suite cannot be executed on a Java 17 runtime in CI. The plugin still *targets* Java 17 bytecode — the parent POM derives the release level from `jenkins.baseline`, and `javac --release 17` on JDK 21 produces Java 17 class files — so controllers on Java 17 remain supported. Compilation is pinned; execution is not. A `mvn clean verify` on JDK 17 shall be run locally before a release whenever a change touches reflection, class loading or the module system.

The repository shall be prepared for Jenkins `buildPlugin` with Linux and Windows configurations from the beginning.

**Windows is a required leg, not a courtesy one.** Gates 3 and 4 found two behaviors that differ between platforms and that would each have shipped as a defect had only one platform been exercised: `Files.isSymbolicLink()` misses Windows junctions (Section 13.1), and an open file handle blocks replacement on Windows but not on Linux (Section 13.2.1). A green Linux build carries little information about the file-handling half of this plugin. A future change to the CI matrix shall not drop the Windows configuration to save build time.

The implemented matrix is Linux/JDK 21, Windows/JDK 21 and Linux/JDK 25. Windows on JDK 25 is deliberately omitted because the differences found so far are in operating-system file semantics rather than JDK version; it shall be added if a JDK-specific difference is ever observed.

Each decision-gate test shall write its platform evidence table to a build artifact as well as standard output, so the Windows and Linux legs can be compared directly after any change.

### 17.3 Required fixture categories

#### JSON

- nested objects;
- arrays and indexes;
- literal dotted keys using quotes;
- keys containing colons, spaces, single quotes, and `@`;
- strings, integers, decimals, exponent numbers, booleans, and null;
- type changes with explicit `type`;
- XML-inapplicable type values are covered under XML fixtures, while JSON accepts all documented types;
- comments around targets;
- CRLF/LF, final newline/no final newline;
- UTF-8 BOM/no BOM;
- UTF-16 LE/BE and legacy-encoding fixtures that fail cleanly;
- duplicate property names within one object, including a duplicate unrelated to a requested path;
- malformed JSON;
- object/array final targets;
- unchanged values.

#### XML

- `appSettings` keys with dots and colons;
- literal key `@value` via `appSettings.@value`;
- quoted keys ending in `.@value`;
- greedy terminal-selector case `appSettings.Foo.@value.@value`;
- `connectionStrings` `@connectionString` and `@providerName`;
- XML `type: auto` and `type: string` succeed, while `number`, `boolean`, and `null` fail with `CONFIG_SUBSTITUTION_TYPE_INVALID` before credential resolution or modification;
- duplicate `<add>` matches;
- `<clear/>` and `<remove/>`;
- nested `<location>` elements;
- `appSettings file="..."`;
- single/double attribute quotes;
- entities, comments, processing instructions, declaration variants;
- CRLF/LF, UTF-8 BOM/no BOM;
- `utf-8` declaration case and quote variants;
- `us-ascii`, ISO-8859-1, Windows-1252, UTF-16 LE/BE, BOM/declaration conflict, and other unsupported encodings;
- unrelated namespace declarations;
- prefixed required elements that do not match shorthand;
- XXE and external DTD attempts;
- generic XML path rejected as unsupported;
- malformed XML;
- unchanged values.

#### Jenkins/security

- `StepDescriptor.getFunctionName()` resolves `configSubstitution` without relying on `@Symbol`;
- global and folder-scoped Secret Text credentials;
- unauthorized credential enumeration;
- missing/wrong credential type;
- credential-backed `auto` against string and non-string JSON targets, including explicit `string` acknowledgement;
- remote-agent execution;
- Pipeline restart/result-map serialization where supported;
- sandboxed bracket access, Map property access, and iteration over the exact documented `details` schema without administrator approval;
- individual matched and unmatched glob detail entries, plus a non-fatal value-free `NOTE` for each unmatched glob when the group union is non-empty;
- `format: auto` with a zero-file group under `warn` and `ignore`, confirming only format-independent validation can run;
- secret token absent from logs, exceptions, arguments, results, and build XML;
- literal `value` documentation warning;
- credential post-substitution security notice, suppression only through `acknowledgeSecretLifecycle`, and documentation covering archive/stash/image-layer persistence;
- workspace traversal, symlinks, Linux links, Windows junctions/reparse points;
- temporary-file cleanup and permissions;
- same file matched by two target groups fails and identifies both groups;
- source-file digest change between planning and commit using the mandatory internal test seam;
- dry-run timestamps unchanged;
- atomic write failure;
- controlled commit failure on the second of two planned files, verifying value-free identification of the failed and already-committed files plus temporary-file cleanup;
- idempotent second execution.

## 18. Acceptance criteria
The Version 1.0 release is acceptable only when all criteria below are automated and passing.

1. **Scoped targets:** The canonical example succeeds because XML substitutions apply only to XML files and JSON substitutions only to JSON files.
2. **Step resolution:** `StepDescriptor.getFunctionName()` returns `configSubstitution`, and the documented Pipeline step resolves without depending on `@Symbol`.
3. **Unique ownership:** A file matched by two target groups fails before credential resolution or writes and identifies the safe relative file plus both groups.
4. **XML app setting:** `appSettings.ApiUrl` changes only the `value` attribute of the unique top-level matching `<add>` element.
5. **Dotted XML key:** `appSettings.Logging.LogLevel.Default` treats the dotted remainder as one literal XML key.
6. **Selector disambiguation:** `appSettings.Foo.@value.@value` targets key `Foo.@value`, while `appSettings.@value` targets the literal key `@value`.
7. **Credential substitution:** `appSettings.BankApi:Key` accepts a folder-scoped Secret Text credential and does not disclose its resolved value outside the intended target/temp file and process memory.
8. **Connection-string attributes:** Both `@connectionString` and `@providerName` can be updated.
9. **Shorthand precedence:** A path beginning `appSettings.` or `connectionStrings.` resolves through the shorthand regardless of document content, so every path valid under Version 0.7 keeps its Version 0.7 meaning.
10. **JSON nesting:** `Logging.LogLevel.Default` resolves as three JSON object levels.
11. **Quoted JSON key:** `Serilog.'MinimumLevel.Default'` resolves a literal dotted property name.
12. **JSON arrays:** `Services[0].Url` updates the first array element using zero-based syntax.
13. **Type inference:** With `type: 'auto'`, an existing JSON boolean receives `'true'` as boolean, an existing number receives `'8080'` as number, and an existing string receives `'8080'` as string.
14. **Explicit JSON type:** `type: 'null'` writes JSON null and an explicit supported type may change an existing JSON scalar type.
15. **XML type constraint:** XML accepts only `type: 'auto'` and `type: 'string'`; `number`, `boolean`, and `null` fail with `CONFIG_SUBSTITUTION_TYPE_INVALID` before credential resolution and modification.
16. **Credential type acknowledgement:** Credential-backed `type: auto` succeeds only for an existing JSON string; non-string JSON scalars require explicit `type: string`.
17. **Non-scalar rejection:** Paths resolving to JSON objects or arrays fail without modification.
18. **Duplicate JSON keys:** A JSON object containing duplicate exact property names fails as ambiguous before modification, even when the duplicate is unrelated to a requested path.
19. **Missing path:** Missing paths never create content. `fail`, `warn`, and `ignore` behave exactly as Section 9 specifies.
20. **Missing files:** Target-group union no-match behavior follows Sections 5 and 9.
21. **Individual glob visibility:** When one glob is unmatched but its target-group union is non-empty, execution remains non-fatal, `warnings` is unchanged, one value-free `NOTE` is logged, and `details` contains the corresponding `unmatched` pattern record.
22. **Dry run:** `dryRun: true` performs validation, returns planned counts and `planned` detail records, and leaves bytes, timestamps, permissions, and attributes unchanged.
23. **Exact preservation:** For all supported fixtures, bytes outside intended replacement ranges are identical before and after execution, including comments, newline style, BOM, and formatting.
24. **Encoding scope:** UTF-8 with or without BOM is preserved; XML declarations are accepted only when absent or case-insensitively `utf-8`; legacy or conflicting declarations fail cleanly before modification.
25. **Safe malformed input:** Malformed or unsupported files fail before modification and do not leak source snippets or values.
26. **XXE safety:** External entity and DTD access attempts do not access filesystem or network resources.
27. **Workspace safety:** Absolute paths, traversal, escaping symlinks, and Windows reparse-point escapes are rejected.
28. **Atomic per-file write:** A successful changed file is replaced through a same-directory temporary file; failures do not leave a truncated original.
29. **Partial-commit reporting:** A controlled commit failure on the second of two planned files fails the step with `CONFIG_SUBSTITUTION_WRITE_FAILED`, leaves the first file committed, leaves the second file at its pre-step bytes, names both the failed file and every already-committed file in the thrown message and build log, and leaves no temporary file behind.
30. **Temp-file hygiene:** Temporary files use restrictive permissions where supported and are removed on success and tested failure paths.
31. **Idempotency:** A second identical run performs no write and does not change the target mtime.
32. **Source-change detection:** A file whose SHA-256 digest changes between planning and commit is detected through the pre-commit test seam, is not overwritten, and fails with a value-free source-changed error.
33. **Secret non-disclosure:** A seeded secret never appears in build logs, thrown messages, returned results, Pipeline arguments, persisted build XML, or diagnostic reports.
34. **Secret lifecycle notice:** A credential-backed real run emits the value-free notice unless `acknowledgeSecretLifecycle: true`; the acknowledgement suppresses only the notice, and documentation explains that cleanup cannot retract persisted copies.
35. **Agent execution:** File content is processed on the workspace-owning agent through a supported controller-to-agent callable.
36. **Cross-platform:** Required tests pass on Linux and Windows configurations.
37. **Stable result schema:** `details` is an ordered list of seven-key maps with the exact key types, one-based group indexes, null conventions, and status enums defined in Section 11.2; it contains no values or credential IDs.
38. **Pipeline usability:** The step and nested models render correctly in Snippet Generator, avoid a polymorphic `Object value` field, and return a Map whose bracket access, property access, and iteration over documented details work in a sandboxed Pipeline without Script Approval.
39. **Masking carrier type:** The type carrying resolved replacement values returns a fixed placeholder from `toString()`, and no type holding a replacement value exposes it through a generated `toString()`. Asserted directly on the type, independently of build-log assertions.
40. **Bounded replacement retry:** A target held open and then released within the retry budget is replaced successfully; a target held open throughout fails with `CONFIG_SUBSTITUTION_WRITE_FAILED` within the bounded budget, leaves the original bytes intact and leaves no temporary file. The probe asserts these invariants and records the platform outcome rather than asserting one.
41. **Link targets refused:** A symbolic link, junction or other reparse point is refused as a target even when it resolves inside the workspace; a link appearing as an intermediate path component is accepted provided the resolved path stays inside; a lexically innocent path that escapes through a junction is refused.
42. **Cross-platform writability:** A read-only or otherwise unwritable target is refused with `CONFIG_SUBSTITUTION_WRITE_FAILED` on both Windows and POSIX, before any temporary file is created.
43. **Ant glob semantics:** `**/name` matches a file at the workspace root as well as nested files; per-pattern match counts overlap within a group and do not sum to `filesMatched`.
44. **Generic attribute path:** A generic path resolves an attribute no shorthand can address, including one nested arbitrarily deep and one inside `<location>`, and changes nothing else in the file.
45. **Generic text path:** A generic `#text` path replaces an element's text, escaping `&`, `<` and `>` and leaving quote characters unescaped; an empty expanded element yields a valid zero-length range.
46. **Occurrence indexes:** `[n]` selects the *n*-th same-name sibling in document order and leaves the others untouched; an index beyond the last sibling fails with `CONFIG_SUBSTITUTION_PATH_MISSING`.
47. **Ambiguity is never resolved silently:** An unindexed step matching more than one sibling fails with `CONFIG_SUBSTITUTION_PATH_AMBIGUOUS` and is **not** downgraded by `missingPathBehavior: ignore`; the message names an indexed form that would disambiguate it, and the file is unchanged.
48. **Mixed content refused:** `#text` on an element containing a child element, comment, CDATA section or processing instruction fails with `CONFIG_SUBSTITUTION_PATH_AMBIGUOUS` rather than overwriting the markup; `#text` on a self-closing element fails with `CONFIG_SUBSTITUTION_PATH_MISSING`.
49. **Lexical name matching:** A prefixed element is matched only by its exact qualified name; the unprefixed form does not match it, and a step matches direct children only, never descendants.
50. **Round-trippable diagnostics:** The canonical rendering of a parsed generic path re-parses to the same path, and a path-resolution failure names no current or replacement value.
51. **Freestyle availability:** The build step is offered for Freestyle projects and appears in the **Add build step** menu.
52. **Freestyle form binding:** Every parameter of Sections 4.3 to 4.5, including the `List<String>` glob field and nested substitutions, survives a real form round trip.
53. **Surface parity:** Given identical configuration and identical input, the Freestyle step and the Pipeline step produce byte-identical output.
54. **Freestyle failure reporting:** A failing substitution aborts the build with the value-free message and prints no stack trace.
55. **Freestyle secret handling:** A credential-backed substitution reaches the target file and appears in neither the build log nor the persisted build XML, and the lifecycle notice of Section 12.6 is printed on this surface too.
56. **No second Pipeline surface:** The Freestyle build step publishes no `@Symbol`.

## 19. Required implementation deliverables
- Maven-based Jenkins plugin repository.
- Installable HPI/JPI artifact.
- Source license and contribution guidance.
- README with canonical XML/JSON examples and security warnings.
- Reference Jenkinsfile.
- Sample `web.config` and `appsettings.json` fixtures.
- Release notes generated by release-drafter from labelled pull requests, surfaced on the plugin site. A hand-maintained CHANGELOG is **not** a deliverable: it duplicates that mechanism and drifts. Durable product documentation — security guidance and known limitations — belongs in the README.
- Architecture decision records for every closed decision gate, indexed in Appendix C.
- Unit and Jenkins integration test suites.
- Linux and Windows CI definitions.
- Dependency-management configuration using the Jenkins plugin BOM.
- Security evidence showing secret-leak tests and XXE/traversal tests.
- Per-platform decision-gate evidence written as a build artifact.
- User-facing error-code reference.
- A repeatable script for running the suite and collecting gate evidence on Linux.

---

## 20. Engineering decision gates
All six gates have been executed and closed. Each was a short spike, not an unresolved product requirement. The findings are recorded in the architecture decision records indexed in Appendix C, and the four that contradicted or under-specified this document have been applied to the sections named below.

| # | Gate | Outcome | Recorded in | Amended |
|---|---|---|---|---|
| 1 | **Source offsets** — can a structural parser locate exact ranges for JSON scalars and supported .NET XML attribute values? | **Closed.** Jackson's reported start *and* end offsets were exact in 14/14 cases, including comments, CRLF, escapes, nesting and arrays, and remained absolute past its internal read buffer. StAX, by contrast, has **no API at all** for an attribute value's source position, so a lexical scanner is load-bearing for XML. | ADR-001 | — |
| 2 | **Secret remoting** — what actually crosses the channel? | **Closed.** `Secret` does not encrypt under Java serialization; the plaintext crosses, and an agent recovers it without controller keys. Separately, `Secret.toString()` returns the plaintext. | ADR-003 | §12.4, §12.5.1 |
| 3 | **Windows links** — does confinement hold against junctions and reparse points? | **Closed on Windows and Linux.** `Files.isSymbolicLink()` returns false for a Windows directory junction, so confinement must be decided on the resolved real path. | ADR-004 | §13.1 |
| 4 | **Atomic replacement** — does same-directory atomic move work, and what is the fallback? | **Closed on Windows and Linux.** `ATOMIC_MOVE` works on both. The more consequential finding was unasked-for: any open handle blocks replacement on Windows but not on Linux. | ADR-002 | §13.2.1, §13.2.2 |
| 5 | **Pipeline result and naming** — does the step resolve by function name, and is the result map readable from a sandbox? | **Closed.** Verified against a genuinely sandboxed script with an empty script-security approval queue. Confirms the §4.2 naming correction made in 0.4. | ADR-005 | — |
| 6 | **Pre-commit testability** — can a test reach the window between planning and the digest check? | **Closed.** Seam is package-private on a package-private class and inert in production. | ADR-005 | — |

Gates 3 and 4 were executed on Windows 10 and on AlmaLinux 9; gates 1, 2, 5 and 6 are platform-neutral and were executed on both. Full `mvn clean verify` — including static analysis and HPI packaging — passes on both platforms.

A spike may change an implementation detail, but any proposed change to externally visible behavior or acceptance criteria requires SRS review. The amendments listed above went through that review before being applied here.

## 21. Release roadmap and deferred scope
### 21.1 Version 1.0 implementation scope

Version 1.0 consists of:

- one owning target group per file;
- JSON nested, quoted-key, and array-index paths;
- JSON automatic and explicit scalar typing;
- XML `auto`/`string` typing only;
- XML `appSettings` and `connectionStrings` shorthand;
- generic XML element traversal with indexes, arbitrary attributes and `#text` (Section 8.7, added in 0.8);
- Secret Text credentials;
- `dryRun`;
- `fail`, `warn`, and `ignore` policies at the step level;
- UTF-8 input/output with exact unaffected-byte preservation;
- locate-then-splice edits;
- atomic sibling writes and idempotency;
- workspace confinement and link defenses;
- SHA-256 source-change detection;
- a sandbox-safe result Map;
- sanitized errors and mandatory secret-leak tests;
- Freestyle support through a `SimpleBuildStep` adapter (Section 4.7, added in 0.9).

### 21.2 Candidate Version 1.1 scope

The following are deferred candidates and are not commitments until separately approved:

- aggregation of disjoint substitution paths from overlapping target groups;
- an explicit duplicate-path policy if override semantics are ever considered;
- `requireEachPatternMatch`;
- per-target-group `noMatchBehavior` and `missingPathBehavior` overrides;
- carefully bounded support for additional encodings based on real repository evidence.

### 21.3 Product-learning objective

The V1.0 boundary is deliberate. The first production release should validate the common property-path and credential workflow in real Jenkinsfiles before the plugin commits to a generic XML language or multi-group composition semantics.

## Appendix A. Developer-review disposition
| Review item | Disposition |
|---|---|
| Flat file/substitution cross product breaks canonical example | Adopted in 0.2. Replaced with substitutions scoped inside target groups. |
| Path grammar undefined | Adopted in 0.2 and narrowed in 0.4. V1.0 now defines JSON paths plus the two XML shorthands only. |
| Dotted XML keys ambiguous | Adopted. XML shorthand consumes the key/name remainder literally; JSON uses nesting and quoted literal-dot keys. |
| `connectionStrings` provider name inaccessible | Adopted. Added terminal `@connectionString` and `@providerName`. |
| Parse-and-serialize destroys comments/formatting | Adopted. Locate-then-splice and an exact-byte test oracle are mandatory. |
| Polymorphic `value` unsuitable for Jenkins model | Adopted. `value` is String plus `type`; default `auto` follows the existing JSON scalar type. |
| `dryRun` and report undefined | Adopted. Added `dryRun` and a value-free sandbox-safe result Map. |
| Baseline, BOM, remoting, credential scope, exception sanitization, traversal, temp files, idempotency, and cross-platform CI | Adopted in 0.2/0.3 and retained. |
| Custom result POJO conflicts with no-Script-Approval requirement | Adopted in 0.3. The returned graph contains only standard map/list/primitive data. |
| XML shorthand conflicts with general grammar | Adopted in 0.3 and simplified in 0.4. XML dispatches directly to shorthand; JSON uses the member grammar. |
| Credential `auto` conflicts on non-string JSON target | Adopted. `auto` requires an existing string; explicit `string` acknowledges a type change. |
| Namespace declarations rejected too broadly | Adopted. Unrelated declarations are permitted, while V1.0 shorthand matches required lexical names only. |
| Duplicate JSON property names | Adopted. Exact duplicates within one object always fail, including duplicates off the requested path. |
| Source SHA-256 check optional or overstated | Retained as mandatory, wording corrected from “prevent” to “detect and reject,” and an internal pre-commit test seam is required. |
| Cross-group overlap described as layering but duplicate paths fail | Resolved by scope in 0.4. V1.0 rejects every same-file overlap and no longer claims layering. Disjoint aggregation is a V1.1 candidate. |
| `@Symbol` described as naming the custom Step | Corrected in 0.4. `StepDescriptor.getFunctionName()` is the required naming mechanism; symbols are not relied on. |
| Legacy XML declarations under-specified | Resolved in 0.4. Only absent or case-insensitive UTF-8 declarations are accepted; US-ASCII and legacy declarations fail cleanly. |
| Unsuppressible secret lifecycle notice creates noise | Resolved in 0.4. Added explicit `acknowledgeSecretLifecycle`; it suppresses only the repeated notice. |
| `result.filesChanged` not mentioned | Clarified in 0.4. Both normal Map property access and bracket access must work; bracket form remains canonical. |
| `appSettings.@value` edge case | Added explicit semantics, fixture, and acceptance coverage in 0.4. |
| Per-target error policies likely future request | Deferred explicitly to candidate Version 1.1 scope. |
| Specification scope risks delaying useful release | Adopted in 0.4. Generic XML and overlapping-group aggregation are deferred; V1.0 is centered on `web.config` and `appsettings.json`. |
| XML `type` behavior undefined | Closed in 0.5. XML permits only `auto` and `string`; all other types fail during validation with `CONFIG_SUBSTITUTION_TYPE_INVALID`. |
| `details` result shape undefined | Closed in 0.5. The ordered list entry schema, key types, null conventions, group indexing, and status enums are now a stable public contract. |
| Individual unmatched glob silent | Closed in 0.5. Every glob produces a detail record, and an unmatched glob emits a non-fatal value-free `NOTE` when the group union is non-empty. |
| `format: auto` validation timing unclear | Clarified in 0.5. Format-specific path/type validation occurs after detection and cannot run for a zero-file auto group continuing under `warn` or `ignore`. |
| Same-file overlap listed as bind-time parameter validation | Corrected in 0.5. The check is owned by agent-side file discovery in Section 5.3 and runs before credentials are resolved. |
| `partialCommit` result field cannot be observed when the step fails | Closed in 0.6. Removed the dead result key; a partial commit throws `CONFIG_SUBSTITUTION_WRITE_FAILED`, reports the failed and already-committed workspace-relative files, and is covered by an automated second-file commit-failure criterion. |
| Per-pattern `matches` counts can overlap after union de-duplication | Clarified in 0.6. Each pattern reports its own matches, and the specification now warns that these counts do not sum to `filesMatched`. |
| `Secret` assumed to possibly protect the remoting channel | **Measured in 0.7 (gate 2).** It does not: the plaintext crosses, and the agent recovers it without controller keys. Section 12.4 now states this as fact and forbids documentation implying otherwise. |
| `Secret.toString()` exposes the plaintext | **Found in 0.7 (gate 2), outside the gate's brief.** Added Section 12.5.1 requiring a masking carrier type, because "never log a value" cannot be enforced by the type system while the carrying type is `Secret`. |
| An open file handle blocks replacement on Windows | **Measured in 0.7 (gate 4).** Antivirus and IIS hold `web.config` open routinely, so a single-attempt implementation would fail builds for no defect. Added the bounded retry of Section 13.2.1, measured inert on Linux. |
| `Files.isSymbolicLink()` misses Windows junctions | **Measured in 0.7 (gate 3).** The obvious reading of the old Section 13.1 rule 4 would have left a hole. Confinement is now decided on the resolved real path. |
| A symlinked target would be replaced by the rename, not updated | **Adopted in 0.7.** Section 13.1 rule 6 now refuses link targets even when they resolve inside the workspace. Stricter than 0.6. |
| POSIX `rename()` needs write permission only on the directory | **Adopted in 0.7.** Without an explicit writability check, a `0444` file would be silently replaced on Linux while the identical build failed on Windows. Section 13.1 rule 8. |
| Ant and `PathMatcher` glob semantics differ for `**` at the root | **Clarified in 0.7.** Section 5.1 rule 9 makes Ant semantics normative and requires a test pinning the difference. |
| `mvn test` reported green while `mvn verify` failed | **Adopted in 0.7.** Section 17.1 makes `verify` the verification command; `test` alone missed two null-dereferences and an HPI packaging failure. |
| A cross-platform probe asserted one platform's outcome | **Adopted in 0.7.** Section 17.1 requires probes to assert invariants and record behavior. Written after the retry probe passed on Windows and failed on Linux for no defect. |
| Windows CI leg might be dropped to save time | **Adopted in 0.7.** Section 17.2 records why Windows is required, citing the two platform differences that would otherwise have shipped. |

## Appendix B. Reference examples
### B.1 JSON literal dotted key

```groovy
[
    files: ['**/appsettings.json'],
    format: 'json',
    substitutions: [
        [
            path: "Serilog.'MinimumLevel.Default'",
            value: 'Information'
        ]
    ]
]
```

### B.2 JSON array and explicit type

```groovy
[
    files: ['**/services.json'],
    format: 'json',
    substitutions: [
        [path: 'Services[0].Port', value: '8443', type: 'number'],
        [path: 'Services[0].Enabled', value: 'true']
    ]
]
```

### B.3 XML connection string provider

```groovy
[
    files: ['**/web.config'],
    format: 'xml',
    substitutions: [
        [
            path: 'connectionStrings.Default.@providerName',
            value: 'Microsoft.Data.SqlClient'
        ],
        [
            path: 'connectionStrings.Default',
            credentialsId: 'production-connection-string'
        ]
    ]
]
```

### B.4 Validation-only deployment gate

```groovy
def validation = configSubstitution(
    targets: deploymentTargets,
    dryRun: true,
    noMatchBehavior: 'fail',
    missingPathBehavior: 'fail'
)

echo "Validated ${validation['filesMatched']} configuration file(s)"
```

---

## Appendix C. Architecture decision records

Each closed decision gate produced an ADR recording what was decided, what was measured, and which alternatives were rejected. They live in `docs/adr/` in the plugin repository and are the authority for *why* the requirements below read as they do.

| ADR | Subject | Gate | Sections it supports |
|---|---|---|---|
| ADR-001 | Source-preserving range location — locate-then-splice, and what Jackson and StAX actually report | 1 | §10.1, §10.2, §7.4, §8.5 |
| ADR-002 | Atomic file replacement — the fallback, the bounded retry, and permission preservation | 4 | §13.2, §13.2.1, §13.2.2 |
| ADR-003 | Secret handling over remoting — what crosses the channel, and why a masking type is required | 2 | §12.4, §12.5.1 |
| ADR-004 | Workspace confinement — why the real path decides, and why a symlink screen misses junctions | 3 | §13.1 |
| ADR-005 | Step API surface and testability — plain-collection result map, function-name resolution, pre-commit seam | 5, 6 | §4.2, §11.2, §13.4 |

Three of these changed the design rather than merely documenting it, and are worth reading before modifying the corresponding code:

- **ADR-001** records that Jackson's offsets proved exact while StAX cannot report attribute positions at all. The JSON verification layer is retained as a guard rather than a workaround; removing it would restore a silent dependency on undocumented offset conventions.
- **ADR-003** records that `Secret` protects persistence but not transport, and that its `toString()` leaks. Both facts are load-bearing for §12.
- **ADR-005** records that the original plugin-defined result object could not satisfy the no-Script-Approval requirement. Returning plain collections is not a stylistic preference; it is the only shape that satisfies both requirements at once.

---

## References
1. Microsoft Variable Substitution: https://github.com/microsoft/variable-substitution
2. Jenkins plugin tutorial: https://www.jenkins.io/doc/developer/tutorial/
3. Choosing a Jenkins baseline: https://www.jenkins.io/doc/developer/plugin-development/choosing-jenkins-baseline/
4. Jenkins Java support policy: https://www.jenkins.io/doc/book/platform-information/support-policy-java/
5. Jenkins dependency management: https://www.jenkins.io/doc/developer/plugin-development/dependency-management/
6. Writing Pipeline-compatible plugins: https://www.jenkins.io/doc/developer/plugin-development/pipeline-integration/
7. Pipeline Step API `StepDescriptor` Javadoc: https://javadoc.jenkins.io/plugin/workflow-step-api/org/jenkinsci/plugins/workflow/steps/StepDescriptor.html
8. Jenkins Credentials consumer guide: https://github.com/jenkinsci/credentials-plugin/blob/master/docs/consumer.adoc
9. Jenkins credentials security: https://www.jenkins.io/doc/book/security/credentials/
10. Jenkins developer security guidance: https://www.jenkins.io/doc/developer/security/
11. Jenkins testing guidance: https://www.jenkins.io/doc/developer/testing/
12. Jenkins core `FilePath` API: https://javadoc.jenkins.io/hudson/FilePath.html
13. Jenkins core `Secret` API: https://javadoc.jenkins.io/hudson/util/Secret.html
14. Jenkins in-process Script Approval: https://www.jenkins.io/doc/book/managing/script-approval/
15. Script Security plugin `@Whitelisted` API: https://javadoc.jenkins.io/plugin/script-security/org/jenkinsci/plugins/scriptsecurity/sandbox/whitelists/Whitelisted.html
16. RFC 8259, The JavaScript Object Notation Data Interchange Format: https://www.rfc-editor.org/rfc/rfc8259

References 3, 6, and 7 were last rechecked during the Version 0.5 review. The baseline they inform — Jenkins 2.541.3 on Java 17 — has since been confirmed in use: the plugin builds and its full suite passes against it on Windows and Linux. All Jenkins platform references shall be rechecked again before Version 1.0 is released.
