# ADR-003: Secret handling over remoting

**Status:** Accepted, with two proposed SRS amendments
**Date:** 2026-08-01
**Decision gate:** SRS v0.6 section 20, gate 2
**Related:** [ADR-002](ADR-002-atomic-file-replacement.md)

## Context

Configuration files live on the agent, so a resolved Secret Text credential must reach the agent for
the substitution to happen. SRS section 12.4 requires the plugin to *"document that
`hudson.util.Secret` protects persisted form but must not be assumed to guarantee ciphertext in Java
remoting serialization"* and to settle the question by measurement.

The answer determines what the plugin may honestly promise users about where their secrets travel,
and it turned out to matter for a second reason nobody had raised.

## Evidence

Measured by `Gate2EvidenceTest` against a real Jenkins 2.541.3 with a real agent, written to
`target/gate-evidence/gate-2-secret-remoting.txt`.

```
=== Gate 2 evidence: secret handling over remoting ===
  jenkins: 2.541.3 / JDK 17.0.12
  callable with a Secret field                 PLAINTEXT PRESENT in the callable payload
  callable with a String field                 plaintext present (expected; used as the control)
  does Secret protect the wire vs a String?    NO - no wire-level difference
  Secret via Java serialization                PLAINTEXT PRESENT in the serialized bytes
  Secret at-rest form (getEncryptedValue)      encrypted, plaintext absent
  Secret.toString()                            EXPOSES plaintext
  string concatenation / String.valueOf        EXPOSES plaintext
  agent recovers plaintext without controller keys yes - confirms the plaintext crosses the channel
```

### Finding 1: `Secret` gives no wire-level protection

Java-serializing a `Secret` writes its plaintext. A callable carrying a `Secret` field and one
carrying a `String` field are, at the byte level, equally revealing. The real-agent probe confirms the
consequence directly: the agent recovered the plaintext despite having no access to the controller's
`$JENKINS_HOME/secrets`, which is only possible if the plaintext itself crossed the channel.

`Secret` protects the **at-rest** form — `getEncryptedValue()` is ciphertext, and XStream persists it
encrypted, which is what keeps secrets out of `config.xml` and `build.xml`. It does nothing for
transport.

The SRS was right to forbid the assumption. This is now a measured fact rather than a caution.

### Finding 2: `Secret.toString()` exposes the plaintext

Not part of the gate's brief, but discovered while probing, and more dangerous day to day than
finding 1. `Secret.toString()` returns the plaintext, so `"value=" + secret`, `String.valueOf(secret)`
and any `String.format("%s", secret)` all leak it. A single careless log statement or an exception
message built by concatenation defeats SRS section 12.5 entirely.

This means **"never log a value" cannot be enforced by the type system if the type is `Secret`.**

## Decision

1. **Keep using `Secret`, but never claim it protects the wire.** It remains correct for holding
   resolved credentials in memory and for anything that might be persisted, because it keeps
   plaintext out of `config.xml`, `build.xml` and `ArgumentsAction`. Documentation must not imply
   transport confidentiality.

2. **Confidentiality over remoting rests on two things, and both are the operator's responsibility:**
   the transport security of the agent channel (SSH, or inbound agents over TLS), and the
   trustworthiness of the agent host. This is consistent with how Jenkins credentials binding already
   works — an agent that runs a build with a credential can always see that credential — so the plugin
   does not introduce a new exposure, but the documentation must state it plainly rather than let
   users infer that `credentialsId` means "the secret never leaves the controller".

3. **Introduce a masking wrapper for in-flight use.** The engine and step layer will carry resolved
   values in a small `ResolvedValue` type whose `toString()` returns a fixed placeholder and which
   exposes the plaintext only through an explicitly named accessor. `Secret` remains the type at the
   Jenkins boundary; the wrapper is what travels through substitution code, so that the accidental
   log statement in finding 2 is impossible rather than merely forbidden.

4. **No attempt to detect or refuse insecure channels.** Jenkins does not expose a reliable,
   supported way to ask whether a given agent channel is encrypted, and a check that silently passes
   on misconfiguration would be worse than none. This is documented as an operator responsibility
   instead.

## Consequences

- The existing leak test (SRS section 12.5) must be extended to assert the wrapper's `toString()`, not
  only build-log contents. A type-level test is far cheaper than hoping review catches every
  concatenation.
- `ResolvedValue` must not implement `Serializable` accidentally in a way that widens exposure; it
  carries a `Secret` internally so that any persistence path still gets the encrypted form.
- Nothing here changes the transformation engine. Gate 2 constrains the step layer only.

## Proposed SRS amendments

**To section 12.4**, replacing the "document that..." bullet with a measured statement:

> Measurement (ADR-003) confirms that `hudson.util.Secret` does not encrypt under Java serialization:
> a resolved credential crosses the remoting channel in plaintext, and an agent recovers it without
> access to controller keys. Confidentiality therefore depends entirely on the agent channel's
> transport security and on the agent host being trusted. Documentation shall state this plainly and
> shall not imply that `credentialsId` keeps the secret on the controller.

**To section 12.5**, adding:

> Resolved credential values shall be carried in a type whose `toString()` returns a fixed
> placeholder, so that accidental string conversion cannot expose a secret. `hudson.util.Secret`
> shall not be relied on for this: measurement confirms its `toString()` returns the plaintext. A test
> shall assert the masking behaviour of the type directly, independently of build-log assertions.

## Verification

`Gate2EvidenceTest` — 4 probes against a real Jenkins and a real online agent, all passing.
Full suite: 75 tests, 0 failures.
