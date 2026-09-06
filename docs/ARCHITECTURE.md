# Compukters Architecture

## Product boundary

Compukters is an in-game Kotlin programming platform. The first source contract is an ordinary bounded `.kt` project with a top-level `fun main()`. The standalone playground and Minecraft computer use the same compiler artifact and VM session boundary.

## Compilation and execution

```text
Kotlin .kt project
  -> isolated pinned K2 worker
  -> Kotlin IR lowering
  -> canonical Compukter artifact
  -> server-global persistent content-addressed cache
  -> ProgramRuntimeHost / VM session
  -> JDK 25 FFM / versioned Rust C ABI
  -> Rust Compukter VM
```

The trusted JVM side owns compiler isolation, diagnostics, Kotlin IR lowering, artifact production, and the server-global content-addressed cache. Kotlin compiler internals do not cross the bounded FFM boundary. The Rust machine validates guest paths, captures an immutable source snapshot plus filesystem preconditions, and suspends only the requesting foreground process. The server tick submits and polls bounded compiler work without waiting on the worker or cache. On completion Rust re-verifies the complete artifact and atomically installs it only if the source and output preconditions still match. Native buffers remain caller-owned and no Rust pointer enters Kotlin.

The compiler worker is packaged as pinned data rather than added to the mod runtime classpath. At server startup its bounded ZIP is validated and published beneath `<world>/compukters/compiler-worker`; temporary worker state is kept separately beneath `<world>/compukters/compiler-temp`. Successful artifacts are stored beneath `<world>/compukters/compiler-cache/v1`, shared by every computer and dimension in that server world, and reused after restart. Cache keys cover the source snapshot, compiler/worker identity, target, trusted API metadata, and limits. Both cache publication and cache hits pass the stateless Rust artifact verifier over FFM; runtime admission quotas are deliberately not part of cache validity.

The raw terminal is a synchronous Rust device: cell writes, positional writes, rectangular fills, colors, cursor changes, and input polling never cross into Minecraft. The authoritative fixed 51x19 cell grid and its replication journal live in the machine; Minecraft only renders full states or ordered deltas. Waiting for an absent input event and foreground process execution are explicit suspension points. Kotlin guests consume ordered raw Text and Key events; there is no compatibility line buffer or second input protocol. A program must finish its active event before starting an interactive child, so event ownership never leaks across foreground process frames. The server host never blocks the Minecraft thread.

## Runtime ownership

`ProgramRuntimeHost` owns one current Rust `ComputerMachine`, advances it with bounded guest and maintenance budgets, commits terminal changes once per active server tick, and exposes typed full/delta states and failures through JDK 25 FFM. It does not own a second grid or output transcript. It is loader-independent and server-thread confined.

The Minecraft carrier owns exactly one host and advances it once per server tick. Rust starts `/rom/boot`, compiled from `system/programs/boot.kt`; boot delegates to `/rom/shell`, compiled from `system/programs/shell.kt`. A foreground child suspends its parent and returns a stable integer result when it exits or fails. There is one active foreground lane today, while the runtime contract leaves room for later parallel execution. Reboot replaces the complete machine stack and clears the terminal. Minecraft sends full state to a new viewer and ordered deltas thereafter. All viewers may submit bounded key and text events, which merge in server-arrival order without a client-side echo or an input lease.

Minecraft owns the persistent 30-bit redstone output register and samples dirty local faces before VM advancement. Rust owns the complete input snapshot, predicate waiters, and confirmed output mirror. Input crosses FFI as one scalar changed-mask-plus-levels packet; output requests are reduced in publication order and committed through one loader-independent host port at most once per computer per tick. A successful physical commit is confirmed to Rust before every original blocking request resumes. VM halt, fault, shutdown, reboot, and replacement never synthesize a zero output.

The client renders the fixed 51x19 grid in a centered compact panel using one 6x9 `minecraft:uniform` cell scale; the world remains visible through a translucent dim layer. Presentation never changes terminal coordinates or owns a second grid.

The Rust VM owns verification, the Tier 0 interpreter, managed memory and collection, quotas, traps/faults, capability suspension, and host-neutral sessions. Future JIT/AOT tiers must remain behind the same verified artifact and session contract.

The Rust runtime also owns the guest filesystem and its persistence. Minecraft stores only a stable 128-bit `ComputerId`; guest paths and bytes never enter block-entity NBT or a JVM-side mirror. Every computer sees an immutable packaged `/rom` and a private persistent `/home`. The world store lives under `<world>/compukters/filesystems`, performs bounded I/O on its own worker, flushes active generations on world saves, and drains, flushes, and closes before server shutdown completes. Removing a computer through the player destruction lifecycle closes its machine before creating a recoverable tombstone; ordinary block-entity removal during chunk unload only closes the current machine and preserves its filesystem.

The versioned FFM ABI exposes opaque world-store lifecycle operations, machine creation inside a store, stateless artifact verification, and dedicated bounded compilation request/completion calls. Kotlin can select a world store, identify a computer, request flush/tombstone/recovery, and route compiler results, but it cannot perform arbitrary guest file operations. Guest code reaches Rust-owned state only through declared capabilities. The filesystem facade exposes bounded `stat`, `list`, `readText`, and `writeText`; Rust validates paths, UTF-8, permissions, quotas, and atomic replacement while `/rom` remains immutable. The shell and editor map stable integer results to user-facing diagnostics. Executable installation remains a Rust-owned filesystem transaction and never accepts a host path.

Boot, shell, `kotlinc`, and `edit` are ordinary no-std Kotlin programs packaged as extensionless executables in `/rom`. Shell owns line editing, authoritative echo, prompts, and built-ins; non-built-in commands resolve to `/home/<name>` unless the user supplies an absolute path. `Process.run(path, capabilities)` accepts an explicit integer capability mask, executes one verified extensionless artifact as a foreground child, and returns a stable integer result code without exposing VM internals. `/rom/kotlinc source.kt [-o output]` accepts one source today; its default output is the source basename without `.kt`.

`/rom/edit <path>` is a nano-like 51x19 editor backed by one managed 4096-unit `CharArray` gap buffer. Cursor motion and deletion preserve UTF-16 surrogate pairs, CRLF input is normalized to LF, Tab inserts four spaces, Enter inherits leading indentation, and the viewport scrolls in both axes. Ctrl+S writes through Rust-owned `FileSystem.writeText`; Ctrl+X exits directly when clean or opens a Y/N/Escape save prompt when dirty. The buffer, source, and compiled artifact belong to the computer filesystem, while the terminal state belongs only to the current VM lifetime. The verified playable loop is `edit demo.kt` -> `kotlinc demo.kt` -> `demo`; source and artifact survive machine reload and remain isolated by `ComputerId`.

Terminal, redstone, process, filesystem, and compiler declarations live in the `guest-platform` metadata bundle so the compiler, future addon bundles, and IDE autocomplete consume the same Kotlin API surface. General stream handles, pipes, process redirection, multi-file projects, and addon API bundles remain later layers.

## Module ownership

| Module | Purpose |
|---|---|
| `compiler-artifact` | Canonical artifact model, validation, and encoding |
| `compiler-client` | Bounded controller and protocol for the isolated compiler worker |
| `compiler-k2` | Pinned K2/IR integration and Compukter lowering |
| `compiler-runtime` | Server-global single-flight scheduling, persistent artifact cache, and packaged-worker lifecycle |
| `guest-platform` | Trusted Guest Kotlin facade declarations and compiler source metadata |
| `native-runtime` | Kotlin-facing JDK 25 FFM VM session, opaque world-store lifecycle, and trusted host capabilities |
| `core` | Loader-independent server behavior and `ProgramRuntimeHost` |
| `playground` | Standalone compile-and-run entry point with stdin/stdout |
| `v26_1-common` | Loader-independent Minecraft 26.1 adapters and computer carrier |
| `v26_1-neoforge` | NeoForge 26.1 registration, resources, GameTest, and production archive |
| `host/compukter-vm` | Artifact verification, managed Rust execution runtime, and VM-owned versioned C ABI in its `ffi` workspace member |

Ownership rules:

- `core` must not import `net.minecraft.*`.
- Kotlin modules must not implement another interpreter or mutable guest machine model.
- Minecraft protocol, UI, and assets require deliberate feature designs and live next to their owning feature.
- Compiler-internal FIR/IR types must not leak into the artifact, FFM, or native runtime contract.
- `LegacyImplementationRemovalTest` prevents removed product contours and old package identities from returning.
