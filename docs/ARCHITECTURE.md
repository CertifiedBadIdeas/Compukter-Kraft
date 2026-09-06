# Compukters Architecture

## Product boundary

Compukters is an in-game Kotlin programming platform with two production authoring paths. Programs created inside a
computer use the Rust-owned `/home` filesystem and the packaged `/rom/edit` and `/rom/kotlinc` tools. The client IDE
owns bounded multi-file projects with `compukter.toml`, an optional `compukter.lock`, and Kotlin sources beneath `src`;
it can analyze and compile a project locally, attach to a computer, and deploy the resulting executable.

Both paths produce the same canonical Compukter artifact and execute through the same VM session boundary. A project
must declare exactly one supported top-level entry point: `fun main()`, `suspend fun main()`, or either form with one
`Array<String>` parameter, returning `Unit`. The standalone playground uses the same compiler artifact and VM session
contracts without the Minecraft carrier.

## Platform and K2 tooling

Guest Kotlin declarations are authored in `guest-platform` as separately versioned modules. Its build produces the
canonical platform bundle consumed by both compilation and IDE analysis. `platform-bundle` owns the bundle model,
codec, module graph, and default imports; `platform-k2` exposes that metadata to K2 without making the K2
implementation part of the platform format.

Compiler and analysis workers are pinned, isolated JVM processes. Their payloads are assembled into one bounded
`k2-tooling-workers.zip`; Kotlin compiler and Analysis API internals stay inside those workers and do not enter the mod
runtime classpath. `worker-client` owns the generic payload publication, process, framing, deadline, and immutable-byte
machinery shared by both worker clients.

The two execution-producing paths are:

```text
In-computer source
  -> Rust captures source and output preconditions
  -> server-global ServerCompilerService
  -> isolated compiler K2 worker
  -> server-global persistent artifact cache
  -> Rust re-verifies and atomically installs the artifact

Client IDE project
  -> bounded project snapshot + manifest + lock + resolved platform profile
  -> client compilation service and client-local artifact cache
  -> isolated compiler K2 worker
  -> attach and verify against a server target
  -> revision-checked deployment into the Rust filesystem
  -> optional canonical terminal submission to run the executable
```

Both compilation services send ordered project sources, target settings, worker identity, platform-module identities,
and limits through the same bounded compiler protocol. `compiler-k2-engine` owns the shared FIR-to-IR and Compukter
lowering implementation; `compiler-k2` supplies the isolated compiler-worker entry point and payload.

The client IDE has a separate analysis path. `ide-analysis-client` owns the bounded protocol, scheduling,
cancellation, and worker lifetime without depending on K2. `ide-analysis-k2` owns the isolated incremental K2
workspace and answers diagnostics, completion, symbol, reference, expression, and semantic-token queries. Compilation
and analysis use the same resolved platform bundle and source-snapshot identities, but have separate worker sessions
and result contracts.

Analysis protocol v7 also carries explicit Kotlin format requests. The request includes the exact editor text and
UTF-16 caret captured by Ctrl+S, so formatting does not depend on whether the semantic snapshot has caught up. The
worker runs ktlint standard rules in a separately loaded nested classpath because ktlint's compiler-embeddable runtime
cannot share IntelliJ classes with the standalone K2 analysis environment. Formatted text is bounded by the negotiated
source-file limit; the client applies a current result as one undoable edit before saving, discards stale results, and
saves the unchanged source with a warning when formatting fails. Autosave and implicit saves do not format.

## Server compilation

The trusted server JVM owns compiler scheduling, diagnostics, artifact production, and the server-global
content-addressed cache. The Rust machine validates guest paths, captures an immutable source snapshot plus filesystem
preconditions, and suspends only the requesting foreground process. The server tick submits and polls bounded compiler
work without waiting on the worker or cache. On completion Rust re-verifies the complete artifact and atomically
installs it only if the source and output preconditions still match. Native buffers remain caller-owned and no Rust
pointer enters Kotlin.

The packaged tooling payload is validated and published beneath `<world>/compukters/compiler-worker`; temporary
worker state is kept separately beneath `<world>/compukters/compiler-temp`. Successful server artifacts are stored
beneath `<world>/compukters/compiler-cache/v1`, shared by every computer and dimension in that server world, and reused
after restart. Cache keys cover the ordered source snapshot, compiler and payload identity, target, selected trusted
platform modules, and compilation limits. Cache publication and cache hits both pass the stateless Rust artifact
verifier over FFM; runtime admission quotas are deliberately not part of cache validity.

`ServerCompilerService` performs bounded asynchronous preparation, persistent-cache lookup, and single-flight
deduplication by compilation identity. Minecraft-facing code submits requests and drains completions; worker and cache
I/O run outside the server tick thread.

## Runtime ownership

`ProgramRuntimeHost` owns one current Rust `ComputerMachine`, advances it with bounded guest and maintenance budgets,
commits terminal changes once per active server tick, and exposes typed full/delta states and failures through JDK 25
FFM. It does not own a second grid or output transcript. It is loader-independent and server-thread confined.

The Minecraft carrier owns exactly one host and advances it once per server tick. Rust starts `/rom/boot`, compiled
from `system/programs/boot.kt`; boot delegates to `/rom/shell`, compiled from `system/programs/shell.kt`. A foreground
child suspends its parent until it exits or fails. There is one active foreground lane today, while the runtime
contract leaves room for later parallel execution. Reboot replaces the complete machine stack and clears the
terminal. Minecraft sends full state to a new viewer and ordered deltas thereafter. Terminal viewers may submit
bounded key and text events, which merge in server-arrival order without client-side echo or a terminal input lease.

The raw terminal is a synchronous Rust device: cell writes, positional writes, rectangular fills, colors, cursor
changes, and input polling never cross into Minecraft. The authoritative fixed 51x19 cell grid and its replication
journal live in the machine; Minecraft only renders full states or ordered deltas. Waiting for an absent input event
and foreground process execution are explicit suspension points. Kotlin guests consume ordered raw Text and Key
events; there is no compatibility line buffer or second input protocol. A program must finish its active event before
starting an interactive child, so event ownership never leaks across foreground process frames. The server host never
blocks the Minecraft thread.

Minecraft owns the persistent 30-bit redstone output register and samples dirty local faces before VM advancement.
Rust owns the complete input snapshot, predicate waiters, and confirmed output mirror. Input crosses FFI as one scalar
changed-mask-plus-levels packet; output requests are reduced in publication order and committed through one
loader-independent host port at most once per computer per tick. A successful physical commit is confirmed to Rust
before every original blocking request resumes. VM halt, fault, shutdown, reboot, and replacement never synthesize a
zero output.

The client renders the fixed 51x19 grid in a centered compact panel while the world remains visible through a
translucent dim layer. Users can select the packaged Cozette 6x13, Dina 6x10, or ProggyTiny 6x10 terminal font without
changing terminal coordinates or creating a second grid. The terminal screen can suspend its observation and open the
IDE, whose target terminal view consumes the same replicated terminal state.

The Rust VM owns verification, the Tier 0 interpreter, managed memory and collection, quotas, traps and faults,
capability suspension, and host-neutral sessions. Future JIT or AOT tiers must remain behind the same verified artifact
and session contract.

## Filesystem and machine lifetime

The Rust runtime owns the guest filesystem and its persistence. Minecraft stores only a stable 128-bit `ComputerId`;
guest paths and bytes never enter block-entity NBT or a JVM-side mirror. Every computer sees an immutable packaged
`/rom` and a private persistent `/home`. The world store lives under `<world>/compukters/filesystems`, performs bounded
I/O on its own worker, flushes active generations on world saves, and drains, flushes, and closes before server shutdown
completes. Removing a computer through the player destruction lifecycle closes its machine before creating a
recoverable tombstone; ordinary block-entity removal during chunk unload only closes the current machine and preserves
its filesystem.

The versioned FFM ABI exposes opaque world-store lifecycle operations, machine creation inside a store, stateless
artifact verification, and dedicated bounded compilation request and completion calls. Kotlin can select a world
store, identify a computer, request flush, tombstone, or recovery, and route compiler results, but it cannot perform
arbitrary guest file operations. Guest code reaches Rust-owned state only through declared capabilities. The guest
filesystem facade exposes bounded `stat`, `list`, `readText`, and `writeText`; Rust validates paths, UTF-8, permissions,
quotas, and atomic replacement while `/rom` remains immutable. The shell and editor map stable failures to user-facing
diagnostics. Executable installation remains a Rust-owned filesystem transaction and never accepts a host path.

The IDE target protocol is a separate bounded host interface rather than direct filesystem ownership. After attaching
to a target, the client may inspect supported filesystem metadata and content, upload an artifact for verification,
observe the destination revision, and deploy using a verification ticket plus the expected revision. Heartbeats and
detach bound the target attachment lifetime; revision conflicts require an explicit retry or user confirmation.

## Guest programs and APIs

Boot, shell, `kotlinc`, and `edit` are ordinary no-std Kotlin programs packaged as extensionless executables in `/rom`.
Shell owns line editing, authoritative echo, prompts, and direct built-ins. A non-absolute external command resolves
first to `/home/<name>` and, only when that path is absent, falls back to `/rom/<name>`; an absolute command is used as
given.

`Process.run(path)` and `Process.run(path, args)` execute one verified extensionless artifact as a foreground child and
return `ProcessResult.Exited(code)` or `ProcessResult.Failed(reason, diagnostic)`. `Process.exit(code)` terminates the
current process. Guest code does not select an explicit capability mask when starting a child.

`/rom/kotlinc source.kt [-o output]` accepts exactly one source file today; its default output is the source basename
without `.kt`. This single-file in-computer command is distinct from the IDE and compiler protocol, which support
bounded multi-file project snapshots.

`/rom/edit <path>` is a nano-like 51x19 editor backed by one managed 4096-unit `CharArray` gap buffer. Cursor motion and
deletion preserve UTF-16 surrogate pairs, CRLF input is normalized to LF, Tab inserts four spaces, Enter inherits
leading indentation, and the viewport scrolls in both axes. Ctrl+S writes through Rust-owned `FileSystem.writeText`;
Ctrl+X exits directly when clean or opens a Y/N/Escape save prompt when dirty. The buffer, source, and compiled artifact
belong to the computer filesystem, while terminal state belongs only to the current VM lifetime. The playable
in-computer loop is `edit demo.kt` -> `kotlinc demo.kt` -> `demo`; source and artifact survive machine reload and remain
isolated by `ComputerId`.

Terminal, standard output and error, redstone, process, filesystem, and compiler declarations live in the
`guest-platform` bundle as separately identifiable modules. Compilation and IDE analysis resolve the same module graph
and consume the same Kotlin API surface. General stream handles, pipes, process redirection, and third-party addon
bundles remain later layers.

## Module ownership

| Module | Purpose |
|---|---|
| `native-runtime` | Kotlin-facing JDK 25 FFM VM session, opaque world-store lifecycle, and trusted host capabilities |
| `platform-bundle` | Canonical platform bundle model, codec, module graph, identities, and default imports |
| `platform-k2` | Shared K2 metadata and FIR integration for the Compukters platform |
| `compiler-artifact` | Canonical executable artifact model, validation, and encoding |
| `worker-client` | Generic bounded JVM worker processes, payload publication, framing, deadlines, and immutable values |
| `tooling-runtime` | Packaged shared runtime containing the pinned compiler and analysis workers |
| `compiler-client` | Compiler protocol, controller, project snapshots, compilation identities, and persistent cache |
| `compiler-runtime` | Server-global scheduling, single-flight compilation, persistent cache, and compiler backend lifecycle |
| `compiler-k2-engine` | Shared K2 FIR-to-IR pipeline, platform linking, trusted intrinsics, and Compukter lowering |
| `compiler-k2` | Isolated compiler worker entry point and packaged compiler payload |
| `guest-platform` | Trusted Guest Kotlin declarations and canonical platform-bundle inputs |
| `ide-core` | Minecraft-independent project, editor, profile resolution, client compilation, and analysis models |
| `ide-analysis-client` | K2-free analysis protocol, controller, scheduling, cancellation, and worker lifetime |
| `ide-analysis-k2` | Isolated K2 Analysis API worker and incremental project workspace |
| `ide-client` | Minecraft-independent IDE workspace, controller, analysis coordination, target, and file-transfer logic |
| `playground` | Standalone compile-and-run entry point with stdin and stdout |
| `core` | Loader-independent server behavior and `ProgramRuntimeHost` |
| `v26_1-common` | Loader-independent Minecraft 26.1 adapters and computer carrier |
| `v26_1-neoforge` | NeoForge 26.1 registration, networking, client UI, GameTests, resources, and production archive |
| `host/compukter-vm` | Artifact verification, managed Rust execution runtime, and VM-owned versioned C ABI in its `ffi` workspace member |

Ownership rules:

- `core` must not import `net.minecraft.*`.
- Kotlin modules must not implement another interpreter or mutable guest machine model.
- `worker-client`, `ide-core`, `ide-analysis-client`, and `ide-client` must not acquire K2 implementation dependencies.
- K2 compiler internals belong to `compiler-k2-engine` and `compiler-k2`; K2 Analysis API internals belong to
  `ide-analysis-k2`.
- Minecraft protocol, UI, and assets require deliberate feature designs and live next to their owning feature.
- Compiler-internal FIR and IR types must not leak into the platform bundle, artifact, worker protocol, FFM, or native
  runtime contracts.
- `LegacyImplementationRemovalTest` prevents removed product contours and old package identities from returning.
