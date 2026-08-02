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

## Not yet verified

**Linux/POSIX behaviour is unverified.** No Linux host was available. The POSIX permission-preservation
path is implemented and its probe self-reports "not applicable" on Windows rather than passing
vacuously. It must be confirmed on the Linux CI leg (SRS section 17.2) before gate 4 is closed for
both platforms. Expected differences to check there:

- `ATOMIC_MOVE` should succeed, and an open handle should **not** block the replacement — POSIX
  `rename()` over an open file succeeds, leaving readers on the old inode. If so, the retry is inert
  on Linux and costs nothing.
- Mode `rw-r-----` must survive the move.
- Owner and group are not preserved without privileges; confirm and document.

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
