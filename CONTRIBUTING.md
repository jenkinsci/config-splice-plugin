# Contributing to Config Splice

Thanks for your interest. This plugin edits configuration files in place and writes credentials into
them, so a few of the conventions below are stricter than usual. They exist because a defect here
corrupts someone's configuration or discloses a secret, not because of taste.

## Getting set up

Requires **JDK 17** and **Maven 3.9.6 or newer**. Older Maven fails with `Unknown packaging: hpi`.

```bash
mvn clean verify    # the full build: tests, SpotBugs, HPI packaging
mvn hpi:run         # a local Jenkins with the plugin loaded, on http://localhost:8080/jenkins
```

On Linux, `bash scripts/run-gates-linux.sh` runs the same build and prints the per-platform decision
gate evidence. It fetches a suitable Maven into `.tools/` if the system one is too old.

## Before you open a pull request

**Run `mvn clean verify`, not `mvn test`.** SpotBugs, the plugin parent's static checks and HPI
packaging all bind to `verify`. Every one of them has caught a defect that `mvn test` reported as
green — two null-dereference paths and a packaging failure. A green `mvn test` is not evidence.

**Run it on Windows and Linux if you touched file handling.** The two platforms genuinely differ:
`Files.isSymbolicLink()` returns false for a Windows directory junction, and an open file handle
blocks replacement on Windows but not on Linux. Both differences were found by testing on both, and
either would have shipped as a defect otherwise. CI covers this, but knowing before you push is
cheaper.

## Conventions that are not negotiable

**The `engine` package must not import Jenkins classes.** The transformation engine is deliberately
usable without a Jenkins runtime, which is what lets most of the suite run in milliseconds and keeps a
future Freestyle adapter cheap. If you need Jenkins in the engine, the design is wrong somewhere else.

**Never let a replacement value reach a log, message or `toString()`.** Values are carried in
`ResolvedValue`, whose `toString()` returns a fixed placeholder. `hudson.util.Secret` is *not* safe for
this — its `toString()` returns the plaintext.

Records are a specific hazard: the generated `toString()` prints every component, and it comes back
silently if someone regenerates the record or deletes an override that looks unused. Every type
holding a value therefore overrides it — `SplicePlan.Edit`, `SubstitutionCallable.Replacement`, and
both `Located` records — and `ValueMaskingTest` asserts each one, so removing a mask fails the build
rather than arming a future log statement. If you add a type that carries a value, add it there too.

This covers the value already in the file as well as the one replacing it: the reason to substitute a
connection string is that the file holds one.

**Third-party exceptions never reach the build log verbatim.** Jackson and StAX embed source excerpts
in their messages, and a source excerpt from a file mid-substitution can contain a resolved credential.
Catch at the boundary and rewrite to a value-free message.

**Cross-platform tests assert invariants and record behaviour.** Where Windows and Linux legitimately
differ, assert only what must hold everywhere — bounded execution, original bytes intact, no residue —
and record the observed outcome as evidence. Asserting one platform's result produces a test that
passes where it was written and fails elsewhere for no defect.

**Nothing is ever created.** A property path that does not resolve is a missing path, never a new
field. If a change would add a key, attribute or array entry, it is out of scope.

## Architecture decisions

`docs/adr/` records why the design is what it is, with the measurements behind each choice. Three are
worth reading before changing the corresponding code:

- **ADR-001** — why locate-then-splice, and what Jackson and StAX actually report. The JSON
  verification layer is a guard, not a workaround; removing it restores a silent dependency on
  undocumented offset conventions.
- **ADR-003** — what crosses the remoting channel, and why a masking carrier type is required.
- **ADR-005** — why the result map is plain JDK collections. A plugin-defined result object cannot be
  read from a sandboxed Pipeline without administrator approval.

## Reporting a security issue

Do not open a public issue. Follow the
[Jenkins security reporting process](https://www.jenkins.io/security/reporting/).

## Code of Conduct

The [Jenkins Code of Conduct](https://www.jenkins.io/project/conduct/) applies to this repository.
