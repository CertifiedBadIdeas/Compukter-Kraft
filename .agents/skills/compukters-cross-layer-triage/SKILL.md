---
name: compukters-cross-layer-triage
description: Use when diagnosing a Compukters failure whose owning layer is unclear or which may cross Gradle, isolated K2 workers, artifact verification, JDK FFM/native loading, the Rust VM, or NeoForge GameTests. Do not use for an already-localized routine test failure.
---

# Compukters Cross-Layer Triage

Localize the first broken contract before changing code.

## Reproduce and Classify

Capture the narrowest reliable reproduction, complete first failure, relevant exit category, and whether the failure
is deterministic. Do not start with the final wrapper exception when an earlier compiler diagnostic, verifier reason,
native error, VM trap, or GameTest assertion is available.

Classify the earliest failing boundary:

- Gradle configuration, dependency wiring, generated inputs, or Kotlin compilation;
- isolated compiler or analysis worker startup, payload, protocol, timeout, or cache;
- K2 analysis/lowering and canonical artifact production;
- artifact decoding, structural verification, admission, or instruction execution;
- native library packaging/loading, exported symbol, FFM layout, ownership, or error mapping;
- VM quota, managed allocation, capability suspension/resume, filesystem, or terminal behavior;
- loader-independent Minecraft carrier behavior versus NeoForge registration, lifecycle, payload, or GameTest behavior.

Use `docs/ARCHITECTURE.md` to identify the owner. For Guest Kotlin claims, also inspect the evidence recorded in
`docs/KOTLIN-SUPPORT.md`.

## Narrow the Boundary

Compare the nearest producer and consumer using an existing focused test or artifact. Prefer inspecting stable facts:
worker command and payload identity, cache key and inputs, encoded bytes and decoded fields, verifier result, native
symbol and layout, host request/reply ordering, tick at which state changes, or the first divergent terminal/VM state.

If the symptom appears in NeoForge, reproduce the underlying behavior in `core`, `native-runtime`, or the VM when
possible before attributing it to the loader. If JVM and Rust disagree, construct the smallest cross-language
conformance case rather than patching both sides speculatively.

Stop cross-layer triage once evidence identifies the broken contract and its owner. Continue through the narrowest
applicable workflow:

- `compukters-language-feature` for Guest Kotlin semantics or corresponding IDE semantic behavior;
- `compukters-abi-change` for a versioned platform bundle, worker protocol, artifact, capability, FFM, or C ABI boundary;
- `compukters-runtime-change` for VM, persistence, terminal, scheduling, quota, or runtime-host internals;
- `compukters-neoforge-integration` for loader and Minecraft lifecycle behavior;
- `debugging-strategy` for the causal investigation and fix, alongside the owning Compukters skill when its domain
  invariants still apply, or alone when no specialist skill matches.

The owning workflow implements and verifies the fix. Re-run the original reproduction and broaden verification
according to the number of affected layers.
After localization, use the owning boundary row in `docs/VERIFICATION.md`; do not substitute a broad green aggregate
for evidence that the original failure path executed.
