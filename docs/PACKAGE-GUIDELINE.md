---
layout: default
title: Package guideline
---

# Package Guideline

Compukters product code uses the root package `ru.lazyhat.compukters`. New code must follow the current Kotlin-artifact architecture instead of recreating any removed VM, terminal, retained-display, or generic device layer.

## Module ownership

- `compiler-artifact` owns the canonical artifact model, validation, and encoding.
- `compiler-client` owns bounded communication with the isolated compiler worker.
- `compiler-k2` owns Kotlin frontend/IR integration and artifact lowering.
- `native-runtime` owns the Kotlin-facing JDK 25 FFM VM session and trusted host capabilities.
- `core` owns loader-independent server product behavior, including `ProgramRuntimeHost` and future computer carriers.
- `playground` owns the standalone compiler-and-VM executable used outside Minecraft.
- `v26_1-common` owns loader-independent Minecraft 26.1 adapters.
- `v26_1-neoforge` owns NeoForge 26.1 bootstrap, registration, resources, and loader-specific adapters.
- `host/compukter-vm` owns verification, interpretation, memory management, quotas, VM execution, and the versioned C ABI in its `ffi` workspace member.

## Placement rules

1. Put VM semantics in `host/compukter-vm`; Kotlin modules must not implement a second interpreter or machine state model.
2. Put server-thread lifecycle and device policy in `core`; do not expose Minecraft types there.
3. Put Minecraft-neutral adapters in `v26_1-common` and NeoForge registration/wiring in `v26_1-neoforge`.
4. Define network protocols next to the feature which owns them. Do not restore the removed global CC-style network package.
5. Introduce UI, terminal rendering, and assets from a new accepted design. Do not restore the removed UI DSL, retained display, palette, font, or workbench implementation.
6. Prefer focused feature packages such as `device/runtime/program` and the future `device/computer` over generic `utils`, `platform`, or `events` dumping grounds.

## Product boundary

The current executable path is:

```text
Kotlin source
  -> isolated K2 worker
  -> canonical Compukter artifact
  -> Kotlin VM session / ProgramRuntimeHost
  -> JDK 25 FFM / versioned Rust C ABI
  -> Rust VM
```

New packages should make this path clearer or add a deliberate host capability. Any competing execution path requires a new architecture decision first.
