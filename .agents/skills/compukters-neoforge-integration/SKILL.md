---
name: compukters-neoforge-integration
description: Use when changing Minecraft or NeoForge registration, lifecycle, networking, client UI or input, resources, persistence adapters, redstone behavior, or GameTests in the v26_1 modules. Do not use for changes contained entirely in loader-independent core code.
---

# Compukters NeoForge Integration

Keep loader-specific code at the edge and validate behavior at the lowest layer that can prove it.

## Place Ownership Correctly

Read the relevant ownership and runtime sections in `docs/ARCHITECTURE.md`. Put loader-independent Minecraft behavior
in `v26_1-common`, NeoForge registration and adapters in `v26_1-neoforge`, and behavior that does not need
`net.minecraft.*` in `core` or its owning lower module. `core` must not import Minecraft classes.

Determine the side and lifecycle before editing: physical client, logical server, server tick, level save, chunk
load/unload, block destruction, viewer open/close, or server shutdown. Preserve server-thread confinement and keep
compiler, filesystem, and other blocking work off the Minecraft tick.

For payloads and persisted data, validate bounds and identifiers at the receiving boundary. Keep the Rust-owned
filesystem and VM state out of block-entity NBT, and do not create a second authoritative terminal or machine model
on the Minecraft side.

## Select Evidence

- Use ordinary module tests for pure adapters, codecs, geometry, input transforms, and lifecycle state machines.
- Use `src/gameTest/kotlin` and the real GameTest server for registration, world lifecycle, ticks, block entities,
  persistence, networking-visible server behavior, or redstone interaction.
- Use the development client for behavior whose correctness is inherently visual or interactive; record the manual
  observation and provide screenshots or a short recording when preparing a pull request.
- Build the production universal JAR when metadata, resources, native packaging, access transformers, or archive
  composition changes.

When an API detail depends on the pinned Minecraft or NeoForge version, verify it against sources or official
documentation for the versions in `gradle.properties`; do not assume behavior from an older mapping or loader release.
