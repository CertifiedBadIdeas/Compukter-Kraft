---
name: compukters-runtime-change
description: Use when changing Compukter VM execution, verification internals, managed memory, scheduling, quotas, terminal or filesystem behavior, persistence, snapshots, or Kotlin runtime-host integration behind an unchanged versioned ABI.
---

# Compukters Runtime Change

Preserve deterministic bounded execution and single ownership across the Rust VM, Kotlin runtime adapter, and host.

## Locate Ownership

Read the runtime ownership section of `docs/ARCHITECTURE.md` and the relevant VM documentation. Identify the owning
layer before editing:

- `host/compukter-vm` owns decoding and verification internals, execution, managed memory, quotas, capabilities,
  terminal state, guest filesystem and persistence, and host-neutral sessions;
- `native-runtime` owns JDK 25 FFM layouts, safe Kotlin adapters, native lifetime, and error mapping;
- `core` owns loader-independent `ProgramRuntimeHost` behavior, bounded advancement, and host capabilities;
- Minecraft modules adapt the host to game lifecycle but do not own a second VM, filesystem, or terminal model.

Use `compukters-abi-change` if an artifact encoding, capability schema, exported C ABI, FFM layout, or persisted
versioned contract changes. Use `compukters-language-feature` when Guest Kotlin source semantics or support claims
change. New subsystems or material ownership changes require the repository roadmap workflow.

## Preserve Runtime Invariants

Keep untrusted work verified before execution and bounded by explicit limits. Preserve deterministic ordering,
well-defined traps and failures, capability suspension and resume semantics, and ownership of native memory. Native
buffers remain caller-owned and Rust pointers do not escape into Kotlin.

Do not block the Minecraft server thread. Keep persistent filesystem publication and replacement atomic, preserve
world-save and shutdown ordering, and distinguish chunk unload from player destruction. Avoid mirrored authoritative
state across Rust, Kotlin, and Minecraft layers.

## Verify the Change

Use focused Rust tests for VM semantics, rejection, limits, persistence, and failure paths; focused Kotlin tests for
adapter lifetime, error mapping, and `ProgramRuntimeHost`; and cross-language conformance when both sides participate.
For performance work, reproduce with a stable workload and compare relevant budgets or benchmarks rather than relying
on wall-clock anecdotes.

When `host/compukter-vm` changes, verify and commit that submodule repository first, then update the parent gitlink in
a separate parent commit. Run full local verification after a completed change spans the VM and parent runtime layers.
