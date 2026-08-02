# ADR-004: Workspace confinement against links

**Status:** Accepted, with one proposed SRS amendment
**Date:** 2026-08-01
**Decision gate:** SRS v0.6 section 20, gate 3
**Related:** [ADR-002](ADR-002-atomic-file-replacement.md)

## Context

SRS section 13.1 requires every file the plugin touches to be proven a descendant of the workspace
after link resolution, with symbolic links that escape rejected, and with Windows directory junctions
and NTFS reparse points "treated as link-like traversal risks". Gate 3 asked whether that actually
holds on Windows.

## Decision

`WorkspaceGuard.requireConfinedRegularFile(workspaceRoot, relativePath)` decides confinement on the
**resolved real path**, not on path strings and not on file attributes:

1. Reject blank, absolute, drive-qualified and UNC paths.
2. Resolve against the workspace and normalise; reject lexically escaping paths cheaply.
3. Reject a path that does not exist, with `FILE_NOT_FOUND` rather than `WORKSPACE_ESCAPE`.
4. Call `toRealPath()` on both workspace and target, and require the target to start with the
   workspace. **This is the check that does the real work.**
5. Require `Files.isRegularFile(..., NOFOLLOW_LINKS)`, which rejects links, directories, devices and
   pipes in one test.

The class uses only `java.nio`, so the rule holds without a Jenkins runtime and can be tested
exhaustively in milliseconds. The step layer will additionally call `FilePath.isDescendant` at the
Jenkins boundary as defence in depth, per SRS section 13.1 rule 3.

## Evidence

Measured by `Gate3EvidenceTest`, written to
`target/gate-evidence/gate-3-workspace-confinement.txt`.

```
=== Gate 3 evidence: workspace confinement against links ===
  platform: Windows 10 10.0 / JDK 17.0.12
  ordinary relative file                       accepted
  absolute path                                refused with WORKSPACE_ESCAPE
  lexical .. traversal                         refused with WORKSPACE_ESCAPE
  escaping junction, lexically innocent path   refused with WORKSPACE_ESCAPE
  JDK view of a junction                       isSymbolicLink=false isDirectory=true isOther=true
  would an isSymbolicLink() screen catch it?   NO - junctions are not symbolic links
  internal junction                            accepted; resolved to its real path inside the workspace
  symbolic link escaping the workspace         refused with WORKSPACE_ESCAPE
  symlinked target file (points inside)        refused - rename would replace the link itself
  directory as target                          refused with WORKSPACE_ESCAPE
  missing file                                 refused with FILE_NOT_FOUND (distinct from escape)
```

### The finding that matters

**`Files.isSymbolicLink()` returns false for a Windows directory junction.**

An implementation that screened for symbolic links — the obvious reading of SRS section 13.1 rule 4,
and the first thing most people would write — would let junctions straight through. The attack needs
no `..` and no absolute path: with `config` as a junction pointing anywhere on the machine,
`config/web.config` is lexically innocent and resolves outside the workspace.

`toRealPath()` resolves junctions and symlinks alike, which is why confinement is decided there.
(`isOther()` does report true for a junction and would also catch it, but relying on that means
enumerating link flavours correctly forever; resolving the path does not.)

## Consequences

- **Attribute-based screening is banned in this codebase.** Any future check must go through
  `toRealPath()`. A comment in `WorkspaceGuard` records why.
- **A missing file is `FILE_NOT_FOUND`, not `WORKSPACE_ESCAPE`.** Conflating them would report a typo
  as an attempted security violation, which trains users to ignore the message that matters.
- **Internal junctions are accepted and resolved.** The guard returns the real path, so the rest of
  the pipeline operates on the actual file rather than the alias.
- **The guard is Jenkins-free**, so it runs in the fast engine test suite rather than needing a
  harness.

## Proposed SRS amendment

The implementation is deliberately stricter than SRS section 13.1 rule 4, which says only that
symbolic links *escaping* the workspace must be rejected. `WorkspaceGuard` refuses a symlinked target
file even when it points inside the workspace.

Reason: the atomic replacement (ADR-002) renames a temporary file over the target. Applied to a
symlink, that replaces **the link itself** with a regular file, silently destroying the user's
symlink rather than updating the file it points at. Following the link and writing through it is the
other option, but that reintroduces a resolution step between validation and commit and buys very
little — a .NET configuration file is essentially never a symlink.

Suggested addition to section 13.1:

> The final target shall be a regular file as reported with `NOFOLLOW_LINKS`. A symbolic link,
> junction or other reparse point is refused as a target even when it resolves inside the workspace,
> because the atomic replacement would replace the link itself rather than the file it designates.
> Links appearing as intermediate path components are permitted provided the fully resolved path
> remains inside the workspace.

## Linux verification

Measured on AlmaLinux 9.8 with JDK 17.0.20. All seven probes passed; the confinement logic behaves
identically on both platforms.

```
=== Gate 3 evidence: workspace confinement against links ===
  platform: Linux 5.14.0-687.15.1.el9_8.x86_64 / JDK 17.0.20
  ordinary relative file                     accepted
  absolute path                              refused with WORKSPACE_ESCAPE
  lexical .. traversal                       refused with WORKSPACE_ESCAPE
  directory junction                         not applicable on this platform
  internal junction                          not applicable
  symbolic link escaping the workspace       refused with WORKSPACE_ESCAPE
  symlinked target file (points inside)      refused - rename would replace the link itself
  directory as target                        refused with WORKSPACE_ESCAPE
  missing file                               refused with FILE_NOT_FOUND (distinct from escape)
```

The junction probes correctly report "not applicable" rather than passing vacuously, and the symlink
probes — which needed elevation on Windows and could have come back inconclusive there — are
conclusive on both platforms. Deciding confinement on `toRealPath()` rather than on file attributes
means there is no platform-specific branch in `WorkspaceGuard` at all, which is why nothing needed
changing for Linux.

Gate 3 is now closed on both supported platforms.

## Verification

`Gate3EvidenceTest` — 7 probes, all conclusive on Windows 10 / JDK 17, no inconclusive results.
Full suite: 82 tests, 0 failures.
