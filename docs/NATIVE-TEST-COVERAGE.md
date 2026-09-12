---
layout: default
title: Native runtime test coverage
---

# Native Runtime Test Coverage

This matrix records the direct test ownership of the managed Rust runtime in
`host/compukter-vm`, its `ffi` workspace member, and the cross-layer checks that exercise the native runtime from
Kotlin and Minecraft. It is a semantic coverage inventory, not a line-coverage percentage. Update it when a runtime
boundary is added, removed, or assigned to a different verification task.

## Evidence layers

The layers below answer different questions and must not be counted as substitutes for one another.

| Layer | Primary entry point | What it establishes |
| --- | --- | --- |
| Direct Rust invariants | `cargo test --manifest-path host/compukter-vm/Cargo.toml --workspace --locked --offline` | Artifact parsing and verification, VM execution and memory invariants, device models, filesystem semantics and persistence, C ABI wire encoding, handle lifetime, and tooling behavior |
| Kotlin-to-VM conformance | `./gradlew-sandbox-dev-parallel verifyKotlinVmConformance` | Compiler-produced `.cpkt` artifacts execute with the expected semantics in the pinned Rust VM |
| JDK FFM integration | `./gradlew-sandbox-dev-parallel :native-runtime:check` | Kotlin wire decoding, JDK 25 descriptors, native loading, typed errors, ownership, and real calls into the built Rust library |
| Runtime-host integration | `./gradlew-sandbox-dev-parallel :core:programRuntimeIntegrationTest` | The loader-independent host drives boot, processes, terminal input, compiler requests, deployment, and persistent files through FFM |
| Minecraft integration | `./gradlew-sandbox-dev-parallel :v26_1-neoforge:runGameTestServer` | NeoForge lifecycle, world store ownership, reboot/reload, compiler routing, IDE file access, resource snapshots, tombstones, and redstone behavior use the production runtime path |

`verifyLocalFull` composes these layers, but its success is one aggregate result rather than additional independent
coverage.

## Direct Rust matrix

| Runtime surface | Direct evidence | Principal guarantees | Remaining boundary |
| --- | --- | --- | --- |
| Artifact container, records, indexed tables, and canonical encoding | Unit tests under `src/decode`, `src/bytes.rs`, and `src/test_encode.rs`; `tests/container_rejection.rs`, `tests/minimal_artifact.rs`, `tests/golden_fixtures.rs`, and `tests/bounded_failures.rs` | Canonical forms, digests, section scope/order, record shapes, size/count limits, mutation robustness, and committed fixture reproducibility | Compiler agreement belongs to conformance rather than these decoder tests |
| Semantic verification and admission | `src/verify/tests.rs`, `src/execution/image.rs`, `tests/semantic_verification.rs`, and `tests/session_api.rs` | CFG typing, initialization, calls, exceptions, module linking, feature bits, capability compatibility, entry resolution, and profile admission atomicity | Kotlin source support is tracked by compiler tests and conformance |
| Scalar execution, control flow, calls, frames, quotas, tasks, channels, and traces | `src/execution/tests.rs`, `channel.rs`, `task.rs`, `frame.rs`, `numeric.rs`, `layout.rs`, and `error.rs` | JVM-compatible scalar behavior, deterministic block charging, calls/returns, frame bounds, stack overflow, bounded FIFO channel handoff, task wake order, trace digests, terminal outcomes, and failure payload bounds | Hardware timing is intentionally excluded from semantic gates |
| Managed heap, GC, strings, and external roots | `heap_tests.rs`, `gc_tests.rs`, `text_tests.rs`, and `external_roots.rs` | Portable layouts, allocation atomicity, arrays/objects/statics, exact safepoint roots, collection, OOM retry, UTF-16 semantics, stale handles, and steady-state allocation invariants | Hardware-specific baselines are opt-in ignored tests |
| Host requests, capabilities, suspension, and batching | `requests_tests.rs`, `session_tests.rs`, and the vertical tests in `computer.rs` | Bounded request storage, task/request identity, LWW reduction, out-of-order completion, capability admission, wait/resume atomicity, string transport, and host-failure classification | Multi-task scheduling is future work tracked separately from transport coverage |
| Terminal, stdio, and redstone devices | `tests/terminal_device.rs`, unit tests in `src/stdio.rs` and `src/redstone.rs`, plus `computer.rs` vertical tests | Grid mutation, replication/resync, bounded input, canonical/raw ownership, Unicode handling, redstone packets, waits, and output merging | Minecraft block-direction mapping belongs to GameTest |
| In-memory filesystem, paths, authority, ROM, and quota | `tests/filesystem_memory.rs`, `filesystem_namespace.rs`, `filesystem_path.rs`, and `filesystem_rom.rs` | Path canonicality, rights narrowing, isolated mounts, atomic mutations, generations, handles, logical quota, immutable ROM, and admission bounds | Host persistence is covered by the store/recovery rows below |
| Persistence codecs and logical recovery | `tests/filesystem_recovery.rs` | Exact journal/checkpoint codecs, bounded recovery, confirmed generations, torn unconfirmed tails, gaps, identity mismatch, and corruption rejection | Checkpoint emission is not implemented; a future writer must add physical crash-point coverage before shipping |
| World store, worker, object collection, and ownership | `tests/filesystem_store.rs`, `filesystem_store_lock.rs`, worker unit tests, and the `persistence-crash-fixture` subprocess matrix | Durable reopen, ordered mutation replay, backpressure, I/O fault degradation, tombstones, object validation/collection, close ordering, live exclusivity, stale-lock recovery, and all 24 current mutation/tombstone/collection process-exit points | Process exit does not simulate storage-device loss of unsynchronized writes |
| Process, compilation, and deployment integration | `tests/process_contract.rs`, `tests/computer_machine.rs`, and `computer.rs` vertical tests | Bounded arguments/diagnostics, child completion, compiler transaction atomicity, candidate identity and retry, executable revisions, and boot behavior | End-to-end compiler and host orchestration belongs to conformance and runtime-host integration |

There is no persisted VM execution snapshot format in the current Rust runtime. Filesystem recovery state and terminal
presentation snapshots are covered in their owning rows; a future VM snapshot feature must add its own direct format,
compatibility, corruption, and resume matrix.

## FFI matrix

| FFI surface | Rust evidence | JVM evidence | Assessment |
| --- | --- | --- | --- |
| Wire versions, tags, scalar encodings, and bounded buffers | `ffi/src/wire.rs` unit tests and `ffi/tests/ffi_api.rs` | Decoder and malformed-wire tests in `VmSessionTest`, `WorldFileSystemStoreTest`, and terminal transport tests | Strong for currently exercised values and failure tags |
| Opaque sessions, stores, and deployment candidates | `ffi/src/handle_table.rs`, bridge unit tests, and `ffi/tests/ffi_api.rs` | `VmSessionTest`, store tests, and real FFM integration tests | Covers invalid/stale/busy handles, conditional consumption, close order, and retry behavior |
| Exported artifact, execution, terminal, filesystem, deployment, compilation, redstone, and resource calls | `ffi/tests/ffi_api.rs` directly exercises 37 of 40 current exports; the Unit, string, and failure `resume_*` exports are covered below the export boundary by bridge/session tests | `FfmAbiParityIntegrationTest` resolves and safely invokes the authoritative descriptors for all 40 symbols; native integration, runtime-host integration, and GameTest additionally exercise shipped workflows | Complete built-library symbol/descriptor parity, with deeper behavior assigned to the owning Rust and vertical tests |
| Panic containment and public error classification | `ffi_api::tests::panic_is_contained_as_an_internal_status`, wire-code tests, and API invalid-input cases | Typed Kotlin mapping and malformed-result tests | Stable mappings are covered; artificial invalid addresses are outside the safe test contract, while null/length validation is covered |
| Built dynamic library and packaged loading | Runtime-bundler smoke helper plus archive tests | The shared `FfmAbiFunction` inventory, `FfmAbiParityIntegrationTest`, `nativeIntegrationTest`, `packagedNativeIntegrationTest`, loader tests, and production archive verification | Actual loading, ABI version, all 40 descriptors, packaged extraction, and archive composition are covered |

## Cross-layer map

The nine registered Kotlin-to-VM conformance scenarios cover the executable artifact writer, the supported Kotlin
subset, suspend-call lowering, VM-owned bounded channel handoff, `when`, `Array<String>` entry arguments, platform
scalar calls, bounded `Int` loops, and specialized `IntArray` operations. These prove compiler/runtime agreement; they do not add independent decoder,
verifier, allocator, or failure-path coverage.

The two `ProgramRuntimeHostIntegrationTest` scenarios cover ROM boot, foreground child execution, reboot, terminal
editing, compiler requests, verified installation, Unicode input, and filesystem persistence through the real FFM
library. The NeoForge GameTests add production registration and ticking, removal/close, observed resource snapshots,
two-computer compilation, persistent programming and world restart, tombstone recovery, IDE filesystem import, and
world-facing redstone.
These are vertical ownership checks, not replacements for the direct Rust tests of the same components.

## Inventory notes

After issue #614, `cargo test --workspace -- --list` registers 538 Rust tests across the VM, FFI, persistence crash
fixture, runtime-bundler, xtask, integrations, and doctests. Nine are intentionally ignored by the normal workspace
run: seven hardware-specific performance or artifact-regeneration tests in the VM, one fixture regeneration test, and
one dynamic-library smoke test that requires a separately built library. The normal semantic gates cover their
contracts through committed fixtures, JVM packaged-native integration, and non-timing assertions; performance
baselines remain explicit opt-in evidence.

`cargo llvm-cov` is not installed in the repository toolchain, so this audit makes no line- or branch-percentage claim.
The matrix is derived from registered test targets, test names, owning source modules, Gradle task graphs, and the
observable boundary each test exercises.

## Focused gaps

The issue #39 audit has no remaining focused gaps. New runtime surfaces must extend this matrix and add evidence at
their owning direct, FFM, and vertical boundaries as applicable.
