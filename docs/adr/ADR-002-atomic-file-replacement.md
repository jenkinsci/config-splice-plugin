# ADR-002: Atomic file replacement

**Status:** Accepted, with one proposed SRS amendment
**Date:** 2026-08-01
**Decision gate:** SRS v0.6 section 20, gate 4
**Related:** [ADR-001](ADR-001-source-preserving-range-location.md)

## Context

SRS section 13.2 requires each changed file to be written through a uniquely named sibling temporary
file, with restrictive permissions, the original's permissions preserved, an atomic same-filesystem
replacement, and guaranteed temp cleanup on every failure path. Gate 4 asked us to verify that this
actually works on Windows and Linux, and to define the fallback when atomic move is unavailable.

The gate found the expected answer to the question it asked, and a more consequential answer to a
question it did not.

## Decision

`AtomicFileWriter.replace(Path, byte[])` implements:

1. **Refuse up front** if the target is not a regular file, is DOS read-only, or is not writable.
2. **Create a sibling temp** `.<name>.<random>.configsplice-tmp` in the target's own directory —
   never the system temp directory — owner-only (`rw-------`) where the platform supports it.
3. **Write and `force(true)`** before the rename, so a crash cannot leave the target pointing at
   content that was never persisted.
4. **Copy the original's permissions** onto the replacement on POSIX.
5. **Move** with `ATOMIC_MOVE + REPLACE_EXISTING`, falling back to `REPLACE_EXISTING` alone if the
   filesystem cannot promise atomicity, retried a bounded number of times.
6. **Delete the temp in `finally`** on every failure path.

## Evidence

Measured by `Gate4EvidenceTest`, written to `target/gate-evidence/gate-4-atomic-replacement.txt`.

```
=== Gate 4 evidence: atomic replacement ===
  platform: Windows 10 10.0 / JDK 17.0.12
  temporary file location                    sibling of the target, as required
  target open via FileInputStream            replacement BLOCKED, failed cleanly with WRITE_FAILED
  target open via FileChannel (READ)         replacement BLOCKED, failed cleanly with WRITE_FAILED
  same-directory move                        ATOMIC_MOVE supported and used
  permissions explicitly transferred         no (replacement inherits the directory ACL at creation)
  read-only target                           refused with WRITE_FAILED; original intact
  POSIX permission preservation              not applicable on this platform (no posix view)
  briefly-held handle (released after 60ms)  recovered once the handle closed
  permanently-held handle                    failed after 539ms (bounded, no hang)
```

### The significant finding

`ATOMIC_MOVE` is supported same-directory on NTFS, so the question the gate asked has a dull answer.
The important discovery is different: **on Windows, any open handle to the target blocks the
replacement**, for an ordinary `FileInputStream` as well as a NIO `FileChannel`. A replacement needs
delete access to the target, and neither opener grants sharing that permits it.

This is not a hypothetical. Antivirus scanners open files immediately after they are written, and IIS
holds `web.config` open. The single most common target of this plugin, on the platform it will most
often run on, is routinely held open by software the user does not control. A single-attempt
implementation would convert a background virus scan into a failed build.

Hence step 5's bounded retry: five attempts with linear backoff, capped under one second in total.
Measured, it recovers from a handle released after 60 ms and still fails in 539 ms when the handle is
held throughout. It narrows a race; it does not pretend to eliminate it.

## Consequences

- **Failure is always safe, never partial.** Every probe asserts the same two invariants regardless of
  platform behaviour: the original bytes survive, and no temporary file is leaked. That holds for
  blocked handles, read-only targets and unwritable targets alike.
- **Windows ACLs are inherited, not copied.** The replacement is a new file that inherits the
  workspace directory's ACL at creation — which is what any file in that directory would get, and
  almost certainly what the original had. Reading the target's *effective* ACL and writing it back
  would convert inherited ACEs into explicit ones, permanently detaching the file from directory
  inheritance: a silent, hard-to-reverse permission change made on the user's behalf. **Known
  limitation:** explicit, non-inherited ACEs set directly on a config file are not carried over.
- **Read-only is refused, not worked around.** Clearing the flag would silently defeat a deliberate
  protection. `Files.isWritable` is checked on every platform, not just Windows, because a POSIX
  `rename()` needs write permission only on the *directory* — without the check, an unwritable `0444`
  config file would be replaced on Linux while the same build failed on Windows.
- **Interrupts are not swallowed.** The retry loop rethrows on `InterruptedException` so a Pipeline
  abort is not absorbed into a backoff.

## Linux verification

Measured on AlmaLinux 9.8 (kernel 5.14.0-687.15.1.el9_8) with JDK 17.0.20. Every prediction held.

```
=== Gate 4 evidence: atomic replacement ===
  platform: Linux 5.14.0-687.15.1.el9_8.x86_64 / JDK 17.0.20
  temporary file location                    sibling of the target, as required
  target open via FileInputStream            replacement SUCCEEDED despite the open handle
  target open via FileChannel (READ)         replacement SUCCEEDED despite the open handle
  same-directory move                        ATOMIC_MOVE supported and used
  permissions explicitly transferred         yes (POSIX permissions copied to the replacement)
  read-only target                           refused with WRITE_FAILED; original intact
  POSIX permission preservation              verified: mode rw-r----- survived the move
  briefly-held handle (released after 60ms)  recovered once the handle closed
```

**The retry is inert on Linux, as predicted.** POSIX `rename()` over an open file succeeds, leaving
existing readers on the old inode, so the Windows-driven retry never fires and costs nothing.

**The read-only row validates the cross-platform writability check.** SRS section 13.2 did not ask
for `Files.isWritable` on POSIX; it was added because a POSIX `rename()` needs write permission only
on the *directory*, so a `0444` config file would otherwise have been silently replaced on Linux while
the identical build failed on Windows. The Linux run confirms both platforms now refuse it.

### A test bug this run exposed

The first Linux run failed one assertion. `Gate4EvidenceTest` asserted that a permanently-held handle
**must** cause a failure — a Windows-specific outcome hard-coded into a probe that was supposed to be
measuring platform behaviour. The engine was correct; the test was wrong, and its `permanently-held
handle` row is consequently absent from the evidence above.

Fixed: the probe now records whichever outcome occurs and asserts only what must hold everywhere —
the call is bounded, nothing is left damaged, and a reported success really replaced the content.
Windows still reports `replacement BLOCKED and failed cleanly, bounded at 530ms`. Re-running on Linux
will add the row, expected to read `replacement SUCCEEDED`.

The lesson generalises: in a cross-platform gate, assert invariants and *record* behaviour. Every
other probe in this suite already did, which is why only this one broke.

## Proposed SRS amendment

The bounded retry introduces externally visible timing behaviour, which SRS section 20 says requires
review. Suggested addition to section 13.2:

> When the atomic replacement fails because the target is momentarily held open by another process,
> the implementation shall retry a bounded number of times within a total budget of approximately one
> second before failing with `CONFIG_SUBSTITUTION_WRITE_FAILED`. The retry shall not extend to
> failures that cannot succeed on repetition, such as a read-only or unwritable target, which are
> refused before any temporary file is created.

Section 16.7 ("respond correctly to Pipeline interruption") is already satisfied: the backoff rethrows
on interrupt.

## Verification

`Gate4EvidenceTest` — 7 probes, all passing on Windows 10 / JDK 17. Full suite: 71 tests, 0 failures.
