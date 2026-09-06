---
name: compukters-language-feature
description: Use when adding, removing, or changing Guest Kotlin language or library support, K2-to-Compukter lowering, or corresponding IDE semantic support. Do not use for host-only Kotlin refactors that cannot change guest programs.
---

# Compukters Language Feature

Keep the accepted Guest Kotlin surface, emitted artifact, VM behavior, IDE model, and published support claim aligned.

## Establish the Contract

Read the relevant entry in `docs/KOTLIN-SUPPORT.md` and the compilation boundary in `docs/ARCHITECTURE.md`. Distinguish
among these claims before changing code:

- K2 accepts the source;
- Compukters lowers and verifies it;
- the Rust VM executes it with defined semantics;
- the IDE analyzes, highlights, navigates, or completes it correctly.

Do not mark a language construct supported merely because K2 accepts it. Preserve the documented intentional
non-goals unless the work has an explicit architecture decision.

Use `compukters-abi-change` when the feature changes an artifact instruction, encoded representation, capability
schema, FFM contract, or other versioned boundary. Use `compukters-runtime-change` for execution support behind an
unchanged boundary. This skill continues to own the end-to-end Guest Kotlin semantic claim.

## Trace the Feature

Follow the feature through only the layers it actually affects:

1. pinned K2 integration and diagnostics in `compiler-k2`;
2. IR interpretation and lowering in `compiler-k2-engine`;
3. canonical types, instructions, validation, and encoding in `compiler-artifact`;
4. verification and execution in `host/compukter-vm` when runtime behavior changes;
5. platform declarations and bundles when the source-visible API changes;
6. `ide-*` analysis and client behavior when tooling makes a matching claim.

Define evaluation order, value representation, overflow or trap behavior, allocation cost, control flow, and
suspension behavior wherever the construct can expose them. Reuse existing artifact instructions and runtime
semantics when they express the contract without weakening verification.

## Preserve Evidence

Choose evidence for each claim that changes. Prefer focused K2 lowering tests for emitted structure, deterministic
artifact/conformance coverage for the Kotlin-to-Rust boundary, VM tests for execution semantics, and focused IDE tests
for tooling-only behavior. Exercise rejection paths when unsupported shapes must produce diagnostics rather than
invalid artifacts or runtime faults.

When Guest Kotlin support changes, update the affected `docs/KOTLIN-SUPPORT.md` entry and its exact evidence in the
same commit. Keep checked entries tied to stable repository paths and named test behavior.
