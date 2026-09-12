---
layout: default
title: Changelog
description: User-visible changes in every Compukters release.
permalink: /CHANGELOG/
---

# Changelog

This page records user-visible Compukters changes. The newest version is first; releases remain as permanent section
headings so this page has one stable URL that can be shared outside the repository.

## 0.4.0 — In development

### Guest Kotlin

- Added bounded cooperative tasks through `Tasks.launch`, task handles, and `join`.
- Added VM-owned bounded `IntChannel` communication between Guest tasks with suspending `send(Int)` and `receive()`.
- Added immutable top-level Guest state, including scalar constants and channel declarations.
- Added default `Int` arguments to platform calls.
- Added bounded computer beeps through the Guest sound API.

### Runtime and computers

- Added a NeoForge 1.21.1 / Java 21 port backed by the JNI runtime transport; the existing 26.1.2 build continues to
  use JDK 25 FFM.
- Fixed 1.21.1 computer startup on Java 21 and restored its block and item models and crisp terminal rendering.
- Unified the terminal panel, border, background, title, and resource-gauge palette across both supported Minecraft versions.
- Moved production computers onto the asynchronous actor runtime with bounded admission, worker execution, result
  delivery, and clean close barriers.
- Moved Guest task scheduling and channel handoff into the Rust VM; channel traffic does not make a server request.
- Preserved redstone transitions while VM work is in flight and aligned redstone and sound effects with fixed server
  tick phases.
- Allowed terminal, compiler, and redstone waits to coexist with host operations from other Guest tasks without
  starvation or missed redstone edges while another task publishes output.
- Restored live terminal observation after leaving the IDE.
- Reduced the default Guest heap and removed unused runtime tracing overhead.
- Improved VM register access, host continuation delivery, result pumping, and scheduler capacity.
- Added terminal gauges for observed VM CPU utilization, heap use, and resident execution state.

### Profiling

- Added the packaged `vmbench` program and in-world controls for deterministic headless VM fleets.
- Added CPU and capacity benchmark modes, automatic status output, scheduler/result metrics, phased sampling, physical
  computer area fanout, and a redstone pulse workload.
- Raised the supported actor capacity to 4096 and allowed benchmark areas larger than 1000 computers.

### In-game IDE and tooling

- Ported the integrated IDE and its icon toolbar to Minecraft 1.21.1 while retaining version-specific screen and
  rendering adapters.
- Added remembered project workspaces and a project switcher.
- Added parameter information and replaced IDE toolbar labels with icons.
- Fixed analysis failures involving synthetic Kotlin declarations.
- Reused verified Kotlin worker caches and reduced packaged tooling size with solid XZ compression and shared compiler
  files.

### Persistence and verification

- Moved bounded filesystem persistence work off the server thread and strengthened store shutdown, failure isolation,
  stale-lock recovery, and crash-point coverage.
- Added complete native FFI symbol/descriptor parity checks and expanded Kotlin-to-VM conformance coverage.
- Non-interactive agent Gradle runs now keep their complete logs in `build/agent-logs/` and print a concise summary.
- Added this continuous repository-owned changelog as a permanent page on the documentation site.

### Compatibility notes

- Minecraft 1.21.1 with NeoForge 21.1.250 or newer is supported on Java 21. Its initial compatibility build includes
  computers, persistence, redstone, sound, compilation, the terminal, the integrated IDE, and VM benchmark commands.
- Channel-enabled executables use artifact/runtime contract 1.2 and require the matching 0.4 runtime. Older artifacts
  whose channel limits are zero remain valid under the extended contract.
- Minecraft 26.1.2 with NeoForge 26.1.2.97 or newer remains the primary Java 25 baseline.

[Compare v0.3.0 with the current branch](https://github.com/CertifiedBadIdeas/Compukters/compare/v0.3.0...dev).

## 0.3.0 — 2026-09-06

This release focused on programmable redstone I/O, a native Compukters K2 platform, a richer in-game IDE, and a
substantially reworked managed runtime.

### Redstone GPIO

- Added first-class analog redstone input and output on all six computer-local sides.
- Added suspending waits for the next change, an exact level, or a minimum level.
- Added weak and direct output power modes.
- Made outputs persistent across program completion, reboot, VM faults, VM replacement, and chunk reload.
- Sampled and coalesced input changes at Minecraft tick boundaries without Guest-side polling.

### Guest Kotlin

- Replaced the JVM-bootstrap Guest environment with a native Compukters K2 platform.
- Made compiler and IDE analysis consume the same explicit platform metadata and source declarations.
- Added modular Guest platform libraries selected through `compukter.toml`.
- Added allocation-free primitive-backed value classes, scalar string interpolation, bounded `Int` ranges, `break`,
  `continue`, and specialized `IntArray` operations.
- Added lazy type initialization for enum and platform-library static state.

### In-game IDE

- Added semantic hover, Go to Declaration, source navigation history, and read-only platform source views.
- Added context-aware keyword completion, explicit Kotlin reformatting, automatic delimiter pairing, structural editing,
  copy/cut, word navigation, page navigation, multi-line indentation, and double-click selection.
- Improved syntax highlighting, Unicode handling, caret behavior, incremental analysis, and analysis diagnostics.

### Runtime, compiler, and verification

- Updated to Compukter Runtime 0.9.0 with FFI ABI 9.
- Reworked VM frames, compact Guest references, exact GC safepoint liveness, memory accounting, and managed reference
  storage.
- Changed host-request limits into tick-scoped backpressure for valid long-running I/O programs.
- Improved linking, reachability, dead-code elimination, artifact validation, and compiler/VM conformance coverage.
- Expanded `verifyLocalFull` to every Gradle subproject, Rust/FFI checks, runtime integration, GameTests, and the
  production JAR while keeping tagged multi-platform release verification separate.

### Breaking changes

- Executables from 0.2.x must be rebuilt from Kotlin source for the 0.3 artifact/runtime contract.
- Projects using the former JVM-bootstrap Guest platform may need their dependency lock resolved again.
- Guest Kotlin exposes only declarations provided by selected Compukters platform modules, not the arbitrary JVM
  Kotlin standard library.

[GitHub release](https://github.com/CertifiedBadIdeas/Compukters/releases/tag/v0.3.0) ·
[Compare v0.2.0...v0.3.0](https://github.com/CertifiedBadIdeas/Compukters/compare/v0.2.0...v0.3.0)

## 0.2.0 — 2026-08-29

### In-game IDE

- Added build, verify, deploy, and run support for attached computers.
- Added an interactive target terminal and a read-only filesystem explorer with file preview and import.
- Added overload/signature completion and automatic completion-list scrolling.
- Moved Kotlin tooling initialization off the render thread and improved initial focus and semantic highlighting.

### Terminal and UI

- Added an IDE button and `Ctrl+I` shortcut to the computer terminal.
- Made Compukters UI scaling independent of Minecraft GUI scale.
- Improved bitmap-font rendering and the IDE terminal layout on smaller screens.
- Fixed delayed terminal submission, terminal ownership handoff, dialog layering, and font-change layout issues.

### Computers, runtime, and distribution

- Restored the blue workbench-style computer appearance.
- Added secure revision-aware executable deployment and improved computer, IDE, and server-VM communication.
- Updated to Compukter Runtime 0.5.1.
- Added pinned and verified Linux and Windows runtime bundles, shared deterministic Kotlin tooling, and stronger
  production JAR assembly and release checks.

[GitHub release](https://github.com/CertifiedBadIdeas/Compukters/releases/tag/v0.2.0).
