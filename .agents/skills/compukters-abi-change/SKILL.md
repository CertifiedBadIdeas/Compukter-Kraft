---
name: compukters-abi-change
description: Use when changing the Compukter artifact format or instructions, verifier contracts, capability schemas, JDK FFM bindings, Rust C ABI, or another versioned Kotlin-to-VM boundary.
---

# Compukters ABI Change

Make every producer, consumer, verifier, version decision, and ownership rule agree across the JVM and Rust boundary.

## Identify the Boundary

Read `docs/ARCHITECTURE.md`, then locate the current source of truth and every reader before editing. Classify the
change as one or more of:

- canonical `.cpkt` encoding, manifest, type, instruction, or debug metadata;
- artifact validation and Rust admission verification;
- Guest capability identity, operation schema, or value representation;
- exported Rust C ABI and Kotlin JDK 25 FFM layout or call contract;
- persisted VM state or another externally durable binary contract.

Decide explicitly whether the change is compatible, requires a version bump, or intentionally makes a clean break.
Treat a public ABI, artifact compatibility, or component-boundary change as architecture-significant work governed by
the repository roadmap rules.

## Change Both Sides Coherently

Update the smallest complete set of producers and consumers. Depending on the boundary, inspect:

- `compiler-artifact` models, validators, encoders, and generated conformance artifacts;
- K2 lowering and platform metadata that emit the affected structure;
- `host/compukter-vm` decoding, verification, execution, FFI exports, and test encoders;
- `native-runtime` FFM descriptors, layouts, adapters, ownership, and error mapping;
- loader-independent `core` contracts and Minecraft adapters only when behavior crosses into them.

Preserve the architectural ownership rules: Kotlin compiler internals stay on the trusted JVM side, Rust re-verifies
complete artifacts, native buffers remain caller-owned, and Rust pointers do not escape into Kotlin.

The VM is a Git submodule. If it changes, verify and commit its repository coherently, then update the parent gitlink
in a separate parent-repository commit without absorbing unrelated submodule work.

## Verify the Contract

Cover valid round trips and malformed rejection at the changed boundary. Check byte order, widths, signedness,
alignment, tags, counts, limits, ownership, and error mapping where applicable. Run focused JVM and Rust tests plus
the relevant cross-language conformance task; use full local verification when the completed change spans both
repositories or multiple runtime layers. Update active ABI or architecture documentation in the same stage.
