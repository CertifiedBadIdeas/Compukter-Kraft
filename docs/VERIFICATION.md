---
layout: default
title: Verification
---

# Verification

Compukters uses different verification gates for fast feedback, a complete local checkout, and a distributable
multi-platform release. Choose evidence from the changed boundary first, then run the final gate required by the stage.

For long runs where console volume is undesirable, use `./gradlew-sandbox-dev-parallel-summary <tasks>`. It preserves
the complete combined log under `build/agent-logs/`, prints only the final Gradle summary on success, and emits a
bounded diagnostic summary and log tail on failure without changing the command's exit code.

## Evidence contract

A successful command proves only the checks that actually belong to its task graph and executed with their required
inputs. A dry-run proves task membership, not behavior. An expected mandatory scenario that was filtered out, skipped
because an input was absent, or otherwise did not execute is not passing evidence. A normal Gradle `UP-TO-DATE` result
is valid only to the extent that the owning task declares all behavior-relevant inputs.

Run evidence after the last relevant change. Record the exact command and result, and limit readiness claims to that
scope. For a multi-stage implementation, focused checks protect each stage; `verifyLocalFull` is required after all
stages are integrated.

Use the [in-world VM benchmark](https://certifiedbadideas.github.io/Compukters/VM-BENCHMARK/) for repeatable manual
profiles of aggregate runnable-computer cost. Its results are environment-specific performance evidence, not a
replacement for deterministic runtime and conformance checks.

## Verification levels

| Command | Claim it supports | It does not establish |
| --- | --- | --- |
| `./gradlew-sandbox-dev-parallel verifyLocalFast` | Policy and build-script checks plus the curated fast JVM slice | Complete module, VM, GameTest, or release coverage |
| `./gradlew-sandbox-dev-parallel verifyAllModuleChecks` | Every current Gradle subproject `check` lifecycle | Host Rust crate checks, Kotlin-to-VM conformance, or the real GameTest server |
| `./gradlew-sandbox-dev-parallel verifyKotlinVmConformance` | Every execution-conformance scenario registered through the root build's conformance registration path | Unrelated JVM, IDE, Minecraft, or release behavior |
| `./gradlew-sandbox-dev-parallel-summary verifyLocalFull` | Complete current checkout: all subproject checks, registered conformance, Rust/FFM/JNI and Runtime-version consistency, integrations, real GameTests, and both production artifacts for the locally configured native platform | Clean-tag state or Linux-and-Windows universal release readiness |
| `./gradlew-sandbox-dev-parallel :v26_1-neoforge:buildProductionUniversalJar` | Official-name production JAR and archive checks for the configured local native resources | A clean tagged universal release or unconfigured target platforms |
| `./gradlew-sandbox-dev-parallel :v1_21_1-neoforge:buildProductionUniversalJar` | Remapped Minecraft 1.21.1 production JAR, Java 21 JNI runtime, metadata, and archive resources | Feature parity for the 26.1-only IDE UI or multi-platform release readiness |
| `./gradlew-sandbox-dev-parallel-summary :v1_21_1-neoforge:buildReleaseUniversalJar` | Clean exact-tag release state, Linux and Windows JNI resources, 1.21.1 archive contents, and packaged JNI execution | The 26.1.2 artifact or external publication |
| `./gradlew-sandbox-dev-parallel-summary :v26_1-neoforge:buildReleaseUniversalJar` | Clean exact-tag release state, Linux and Windows FFI resources, 26.1.2 archive contents, and packaged FFM execution | The 1.21.1 artifact or external publication |
| `./gradlew-sandbox-dev-parallel-summary buildReleaseUniversalJar` from the repository root | Gradle selects both version-specific tasks and builds both distributable JARs from the same validated dual-transport Runtime bundles on a clean exact tag | Publication, upload, push, or external release creation |

The production task retains its historical `UniversalJar` name, but it selects universal native bundles only when the
release runtime bundle configuration is present. Use the release task—not the production task name—as the universal
release gate.

## Boundary matrix

The commands below are minimum focused evidence. Add narrower tests for the behavior changed, including malformed,
limit, lifetime, or failure cases when those contracts are affected.

See [Native runtime test coverage](https://certifiedbadideas.github.io/Compukters/NATIVE-TEST-COVERAGE/) for the semantic ownership matrix across direct Rust,
FFI, Kotlin-to-VM conformance, runtime-host integration, and NeoForge GameTests.

| Changed boundary | Focused evidence | Final evidence |
| --- | --- | --- |
| Guest Kotlin declarations, platform metadata, K2 lowering, or IDE semantics | Owning `guest-platform`, `platform-bundle`, `platform-k2`, `compiler-k2-engine`, `compiler-k2`, and affected `ide-*` `check` tasks; `verifyKotlinVmConformance` for an execution claim | `verifyLocalFull` after a completed cross-layer feature |
| `.cpkt` model, encoding, instruction, verifier, or admission contract | `:compiler-artifact:check`, affected compiler checks, focused VM tests, and the applicable conformance scenario; include malformed and version behavior | `verifyLocalFull` |
| Worker protocol, payload, isolation, or tooling bundle | Owning client/server module `check`, forked-worker checks, payload/license verification, and wrong-version or malformed framing cases | `verifyLocalFull` when multiple worker boundaries change |
| Rust VM execution, managed memory, quotas, terminal, filesystem, or persistence | Focused `cargo test --manifest-path host/compukter-vm/Cargo.toml --locked --offline` target or test, plus the owning JVM adapter check when observable there | `verifyLocalFull` when the parent and VM or multiple runtime layers change |
| Native runtime API, Rust C ABI, JDK FFM adapter, or Java 21 JNI adapter | `:native-runtime-api:check`, focused FFI/JNI Rust checks, the owning `:native-runtime-ffm:verifyNativeRuntime` or `:native-runtime-jni:verifyNativeRuntime`, layout/error/lifetime cases, and a real JVM-to-native integration | `verifyLocalFull` |
| Loader-independent computer/runtime behavior | `:core:check`; include `programRuntimeIntegrationTest` when VM behavior participates | `verifyLocalFull` for a completed runtime vertical |
| Minecraft lifecycle, registration, persistence adapter, redstone, or server-visible networking | Owning common/NeoForge checks and `:v26_1-neoforge:runGameTestServer` when world or lifecycle behavior changes | `verifyLocalFull` |
| Client UI, input, rendering, or other inherently visual behavior | Owning module checks plus a development-client scenario with the observation recorded | `verifyLocalFull` plus the required manual observation |
| Metadata, resources, native packaging, access transformers, or archive composition | `:v26_1-neoforge:buildProductionUniversalJar` and inspection produced by its verification tasks | `verifyLocalFull`; use the release gate as well only for a release candidate |
| Tagged distributable release | `verifyLocalFull` on the exact candidate revision before tagging | `buildReleaseUniversalJar` from the repository root on the clean exact tag with the published Runtime bundles |

## Manual client scenarios

Require a development-client observation only when the changed contract is inherently visual or interactive and
cannot be established by a lower automated layer. Examples include rendering, screen layout, focus, keyboard or mouse
interaction, and the visible result of reconnecting or opening a viewer. Do not require a client run for VM, compiler,
server lifecycle, networking-state, or persistence behavior that focused tests or GameTests can prove directly.

Before launching the client, write down a bounded scenario containing:

- the clean test world and initial state;
- the exact player actions;
- the observable expected result;
- a timeout or clear completion condition;
- the log, screenshot, or short recording needed to preserve the observation when it matters to review.

Run manual scenarios on a disposable test world, not a user save. If the agent cannot control or observe the client,
report the scenario as pending manual evidence instead of treating a successful client launch as verification. Promote
a recurring scenario to GameTest or another automated test when the behavior becomes observable without subjective
visual judgment.

## Release evidence

For an artifact or release-readiness claim, record:

- the parent repository revision and pinned `host/compukter-vm` revision;
- the exact produced JAR path and its digest when it will leave the local checkout;
- the packaged native resource paths and whether they came from local development output or configured release bundles;
- the verification commands run from that revision.

Building or verifying an artifact does not authorize creating a tag, pushing commits, uploading files, or publishing a
release. Those external mutations require an explicit user request.
