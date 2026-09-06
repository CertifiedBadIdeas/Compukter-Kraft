---
name: compukters-neoforge-integration
description: Use when changing Minecraft or NeoForge registration, lifecycle, networking, client UI or input, persistence adapters, redstone behavior, GameTests, or resources tied to loader registration or the production archive. Do not use for loader-independent core code or simple standalone asset edits.
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

If investigation localizes the owning behavior below the Minecraft adapter, continue with
`compukters-runtime-change`, `compukters-abi-change`, or `compukters-language-feature` rather than duplicating the fix
in the loader layer.

## Select Evidence

- Use ordinary module tests for pure adapters, codecs, geometry, input transforms, and lifecycle state machines.
- Use `src/gameTest/kotlin` and the real GameTest server for registration, world lifecycle, ticks, block entities,
  persistence, networking-visible server behavior, or redstone interaction.
- Use the development client only for behavior whose correctness is inherently visual or interactive and cannot be
  established at a lower automated layer. Define the initial test-world state, exact player actions, expected visible
  result, and completion condition before launching it. A successful launch is not evidence that the scenario passed.
  If the agent cannot control or observe the client, hand back the bounded scenario as pending manual evidence. Record
  the observation and provide screenshots or a short recording when it matters to review.
- Build the production JAR for the configured native resources when metadata, resources, native packaging, access
  transformers, or archive composition changes. Use `compukters-release` for a distributable artifact or universal
  release-readiness claim; the production task's historical name alone does not prove multi-platform coverage.

When an API detail depends on the pinned Minecraft or NeoForge version, verify it against sources or official
documentation for the versions in `gradle.properties`; do not assume behavior from an older mapping or loader release.
Use the Minecraft, client, or packaging row in `docs/VERIFICATION.md` to select focused evidence and the final gate.
