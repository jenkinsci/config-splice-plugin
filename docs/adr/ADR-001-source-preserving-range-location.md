# ADR-001: Source-preserving range location

**Status:** Accepted
**Date:** 2026-08-01
**Decision gate:** SRS v0.6 section 20, gate 1
**Supersedes:** none

## Context

SRS section 10.1 mandates a **locate-then-splice** design: parse only to understand the document,
then replace the exact source range of the target scalar and leave every other byte untouched. SRS
section 10.4 makes that testable — output bytes must differ from input bytes only inside planned
ranges.

Gate 1 asked whether a structural parser reports offsets precise enough to do this, for JSON scalars
and for .NET XML attribute values, across comments, CRLF, escapes, entities and BOM. SRS section
10.2 explicitly declined to assume they were sufficient.

## Decision

Locate-then-splice is confirmed viable, implemented differently for the two formats.

**JSON — Jackson streaming as the locator, with verification.**
`JsonScalarLocator` walks the token stream, takes the token start from
`JsonParser.currentTokenLocation()`, computes the token end with a small lexical scan, and then
**verifies** the candidate range by decoding it and comparing against the value Jackson itself
parsed. Only a verified range becomes a splice target.

**XML — StAX as a safety gate, a lexical scanner as the locator.**
`DotNetAttributeLocator` runs StAX first with `SUPPORT_DTD` and external entities disabled purely to
prove the document is well formed and safe, then hands the proven-good text to `XmlTagScanner`, which
computes exact attribute-value ranges.

## Evidence

Measured by `Gate1EvidenceTest`, which prints the table below on every run.

### JSON

Fourteen cases spanning all four scalar kinds, line and block comments either side of the target,
CRLF and LF, escapes including `\uXXXX`, three levels of nesting, and array indices:

```
  --> 0/14 cases needed a start adjustment; 0/14 had an inexact parser end offset.
```

Jackson's reported start **and** end offsets were exact in every case — a better result than
anticipated. A separate case confirms offsets stay absolute rather than chunk-relative in a ~144 KB
document, more than ten times Jackson's 8 KB default read buffer.

### XML

StAX reports one `Location` per event and offers **no API at all** for the source position of an
individual attribute value. This is a hard limitation, not a precision problem: no amount of care
with StAX yields the ranges exact-byte splicing requires. The lexical scanner is therefore
load-bearing for XML, which vindicates the SRS's refusal to assume parser offsets would suffice.

## Consequences

- **The JSON verification layer is a guard, not a workaround.** The evidence says Jackson alone would
  work today. It is retained because it is nearly free and it converts a dependency on undocumented
  offset conventions into a per-call proof. A future Jackson that changed conventions would make this
  class fail loudly rather than silently splice the wrong bytes.
- **The outward search in `resolveVerifiedRange` is only safe because of verification.** Without it, a
  reported offset landing one character into `8080` would still "look like" the start of a number.
  The two must not be separated.
- **`XmlTagScanner` is security-relevant and must stay narrow.** It runs only on text StAX has already
  accepted. It resolves no entities, loads no external resources and interprets no DTD. It skips
  comments, CDATA, processing instructions and declarations so that markup inside a comment is never
  mistaken for an element, and it compares raw qualified names so `xdt:add` never matches `add`.
- **Everything works in `char` offsets.** `SourceDocument` owns the single byte/character boundary.
  Decoding is strict (`CodingErrorAction.REPORT`): malformed UTF-8 is rejected rather than silently
  becoming U+FFFD, which would change untouched bytes on write-back.
- **Gate 4 (atomic replacement on Windows) is now the largest remaining unknown.** Nothing here
  depends on its outcome.

## Alternatives rejected

| Alternative | Why rejected |
|---|---|
| Jackson tree model / DOM + `Transformer` | Destroys comments, indentation, key order, attribute order, self-closing form and newline style — the exact things SRS sections 7.4 and 8.5 require preserving. |
| Regular-expression replacement | Cannot distinguish a real `<add>` from one inside a comment, cannot honour JSON nesting, and has no notion of ambiguity. |
| StAX offsets plus arithmetic to find attributes | Would depend on undocumented, implementation-specific behaviour of whichever StAX implementation the agent's JRE supplies. |
| A hand-written XML parser used for validation too | Reimplements well-formedness and XXE defence that the JDK already provides and that is far better tested than anything written here. |

## Verification

`Gate1EvidenceTest`, `JsonScalarLocatorTest`, `DotNetAttributeLocatorTest`,
`DotNetPathParserTest`, `EncodingSupportTest` — 64 tests, all passing on JDK 17.
`ExactPreservationOracle` is the SRS section 10.4 oracle; it deliberately does not call
`SplicePlan.applyTo` to build its expectation, so it checks the splicer rather than agreeing with it.
