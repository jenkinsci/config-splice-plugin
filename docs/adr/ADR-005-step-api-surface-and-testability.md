# ADR-005: Step API surface and testability

**Status:** Accepted
**Date:** 2026-08-01
**Decision gates:** SRS v0.6 section 20, gates 5 and 6
**Related:** [ADR-003](ADR-003-secret-handling-over-remoting.md)

## Context

Two gates covering the Jenkins-facing layer:

- **Gate 5** — does the step resolve by function name, and can a *sandboxed* Pipeline read every
  documented result key without an administrator approving anything?
- **Gate 6** — can a test deterministically mutate a file inside the window between planning and the
  pre-commit digest check, through a seam that is absent from the public API and inert in production?

## Decision

**Naming.** `StepDescriptor.getFunctionName()` returns `configSubstitution`. No `@Symbol` anywhere on
the step. Jenkins resolves a `StepDescriptor` through `getFunctionName()` and falls back to a symbol
only for descriptors that are not steps, so the annotation would have been decorative — and the SRS
carried that error from v0.1 to v0.3.

**Result map.** The step returns a `LinkedHashMap` containing only JDK collections, strings, booleans,
integers and null. No plugin-defined type appears anywhere in the graph. This is what makes it
JEP-200-safe over remoting and readable from a sandboxed script.

**Agent boundary.** `SubstitutionCallable` does discovery, confinement, parsing, locating, planning and
writing on the agent, and returns the result map plus a buffered list of log lines. Buffering the log
rather than plumbing a `TaskListener` agent-side keeps ordering deterministic and the agent code free
of Jenkins UI concerns. The `log` key is stripped before the map is returned, so the documented
schema is exactly what SRS section 11.2 specifies.

**Test seam.** `SubstitutionCallable.PreCommitHook` is a package-private interface on a
package-private class, installed through a package-private static setter, `null` in production.

## Evidence

```
=== Gate 5 evidence: step resolution and sandbox-safe result map ===
  bracket access result['filesChanged']       works in sandbox
  property access result.filesChanged         works in sandbox
  iteration over result['details'] entries    works in sandbox
  type inference end to end                   string/number/boolean each kept their JSON type
  comment preservation end to end             // comment survived a real Pipeline run
  pending script-security approvals           0 (must be 0)
  StepDescriptor.getFunctionName()            configSubstitution, no @Symbol involved
  dryRun                                      reported 1 planned, changed 0, file untouched

=== Gate 6 evidence: pre-commit test seam ===
  seam absent (production path)               no effect; substitution committed normally
  file mutated between planning and commit    detected; SOURCE_CHANGED, no overwrite
  concurrent writer's content                 preserved intact
  temporary files after a detected change     none
  seam visibility                             package-private on a package-private class
```

Gate 5 runs a genuinely sandboxed `CpsFlowDefinition` and then asserts
`ScriptApproval.get().getPendingSignatures()` is empty. An unsandboxed script would have proved
nothing, and checking the approval queue is what distinguishes "it worked" from "it worked because
this test happened to run as an administrator".

The end-to-end type and comment probes are worth noting: the engine's guarantees now hold through a
real Pipeline run, not just in unit tests. A number stayed an unquoted number, a boolean stayed an
unquoted boolean, and a `//` comment survived — through glob expansion, remoting, splicing and an
atomic write.

## Consequences

- **The v0.2 design would have failed this gate.** A plugin-defined result object requires a
  script-security approval for every field read in a sandboxed script, contradicting the SRS's own
  "no Script Approval" requirement. Returning plain collections is not a stylistic preference; it is
  the only shape that satisfies both requirements at once.
- **`details` is now a public contract.** Gate 5 iterates it and asserts the `kind` discriminator, so
  changing its shape is a breaking change from here on.
- **Untestable acceptance criteria get skipped.** SRS acceptance criterion 31 covers a window
  microseconds wide and otherwise unreachable. Making the seam a requirement in its own right is what
  keeps that criterion from being quietly dropped at test-writing time.
- **`SplicePlan.Edit.toString()` is overridden to mask its replacement.** A record's generated
  `toString()` prints every component, which would have put a resolved credential into any log line,
  assertion message or debugger label that touched an edit. Found while wiring `ResolvedValue`; the
  same reasoning as ADR-003.

## Verification

`Gate5EvidenceTest` (3 tests, real sandboxed Pipeline), `Gate6EvidenceTest` (4 tests).
Full suite: 89 tests, 0 failures, on Windows / JDK 17.
