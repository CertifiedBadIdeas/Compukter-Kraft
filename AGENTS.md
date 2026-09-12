# Repository Guidelines

## Workspace & Working Checkout

Treat this repository root as the Compukters workspace. Do not create Git worktrees; perform all work in the user's
current checkout and current branch unless the user explicitly requests a worktree for a specific task.

Do not spawn sub-agents or delegate work to other agents unless the user explicitly requests sub-agent or
parallel-agent involvement for a specific task.

When work touches `host/compukter-vm`, check for `host/compukter-vm/AGENTS.md` and read it completely if it exists.

## Project Structure & Module Organization

Compukters is a Gradle multi-module Kotlin project with native Rust VM components. Kotlin modules live under
`modules/`: `core` contains shared device/runtime logic, `native-runtime/api` contains the Java 21 runtime API and models,
`native-runtime/ffm` contains the JDK 25 FFM transport, and `native-runtime/jni` is the Java 21 JNI transport leaf, while
`v26_1/v26_1-common` plus `v26_1/v26_1-neoforge` contain the Minecraft 26.1.2 integration. Host-side Rust
VM code lives in `host/compukter-vm`. Documentation is in
`docs/`, mod metadata is in `config/`, and visual/model assets are in
`models/` and top-level logo files.

## Build, Test, and Development Commands

- AI agents running Gradle from the sandbox should use `./gradlew-sandbox` instead of `./gradlew`. It keeps
  `GRADLE_USER_HOME` in `.gradle-sandbox`, disables the Gradle daemon, and avoids sharing host Gradle lock files.
- For non-interactive Gradle tasks, including formatting, compilation, tests, checks, packaging, and verification,
  AI agents should prefer
  `./gradlew-sandbox-dev-parallel-summary <tasks>`. It runs the same parallel wrapper, captures the complete combined
  output under `build/agent-logs/`, prints only the successful build summary, and emits a bounded diagnostic tail on
  failure without changing the Gradle exit code.
- Use `./gradlew-sandbox-dev-parallel <tasks>` only for interactive development runs such as `runClient` or
  `runGameTestServer`, or when the user explicitly requests live per-task output. It delegates to
  `./gradlew-sandbox-dev --parallel <tasks> -PcompukterVmBuildJobs=$(nproc)`.
- `./gradlew-sandbox-dev-parallel verifyLocalFast` is the default fast feedback entrypoint. It runs build-script and
  policy checks plus a curated JVM test slice; it does not claim complete module coverage.
- `./gradlew-sandbox-dev-parallel-summary verifyLocalFull` verifies the complete current checkout while keeping its
  complete output in a log: every Gradle subproject
  `check`, all registered Kotlin-to-VM conformance scenarios, host Rust and FFM checks, runtime integrations, the real
  NeoForge GameTest server, and the production artifact packaged for the locally configured native platform.
- `./gradlew build` builds all Gradle modules and runs standard checks.
- `./gradlew test` runs JVM unit tests across Kotlin modules.
- `./gradlew :core:test`, `./gradlew :native-runtime-api:test`, or `./gradlew :native-runtime-ffm:verifyNativeRuntime` runs focused module tests.
- `./gradlew-sandbox-dev-parallel :v26_1-neoforge:runClient` launches the NeoForge dev client.
- `./gradlew-sandbox-dev-parallel :v26_1-neoforge:runGameTestServer` runs the real NeoForge GameTest server.
- `./gradlew-sandbox-dev-parallel :v26_1-neoforge:buildProductionUniversalJar` builds the official-name production mod jar without a remap stage.
- `./gradlew-sandbox-dev-parallel :v26_1-neoforge:buildReleaseUniversalJar` is the separate tagged release gate. It
  requires a clean exact-tag checkout and configured Linux and Windows native runtime bundles; local full verification
  does not claim this release state.
- `cargo test --manifest-path host/compukter-vm/Cargo.toml --locked --offline` runs the managed Compukter VM tests.

## Coding Style & Naming Conventions

Kotlin targets JVM 25 and uses the `org.jmailen.kotlinter` plugin. Run Gradle with JDK 25 selected through `JAVA_HOME`
or a Gradle-discoverable installation. Keep Kotlin package paths under
`ru.lazyhat.compukters`, use four-space indentation, `PascalCase` for types, `camelCase` for members, and `*Test.kt`
for tests. Existing original source files carry an Apache-2.0 header; preserve it when editing or adding comparable
Kotlin, Gradle, and Rust files. Do not apply the project header to vendored or generated third-party material.
Rust crates use edition 2021 conventions: `snake_case` modules/functions, `PascalCase` types, and integration tests in
`tests/*.rs`.

## Testing Guidelines

Kotlin tests use `kotlin.test` on JUnit Platform; build-script tests use JUnit Jupiter. Place tests beside the owning
module in `src/test/kotlin`. NeoForge game tests live under `src/gameTest/kotlin` and are compiled by `check`; run
`:v26_1-neoforge:runGameTestServer` when validating in-game behavior.

## Commit & Pull Request Guidelines

Recent history uses concise Conventional Commit-style messages such as `chore(vm): ...`, `ci(roadmap): ...`, and
`docs(roadmap): ...`. Keep commits scoped and imperative. Pull requests should explain the behavior change, list
verification commands, link related issues or roadmap items, and include screenshots or short recordings for visible
Minecraft/UI changes.

Keep user-visible changes in the continuous `docs/CHANGELOG.md`, under the heading for the version currently declared
in `gradle.properties`. Use release versions as section headings rather than creating one changelog file per release.
Internal refactors, tests, build chores, and documentation-only edits need no entry unless they materially affect users,
contributors, packaging, or the release process. When releasing a version, replace its `In development` marker with the
release date and add the next development-version heading at the top.

After each verified implementation stage, create a focused commit unless the user explicitly asks not to commit.
Commit frequently enough that a clean, build, or tooling failure cannot destroy a large body of uncommitted work.
Stage only files that belong to the current task, and never include unrelated user changes in an agent commit.

### Semantic history per issue

For work started after this policy was adopted, keep a clean semantic history for each issue rather than blindly
squashing the whole issue into one commit. Record the starting commit before the first task commit. Checkpoint commits
remain encouraged during implementation, but before declaring the issue complete, clean only the unpublished commits
created for that issue:

- fold small corrections, formatting-only follow-ups, and missing test adjustments into the commit they belong to,
  preferably with `fixup` and autosquash;
- squash superseded or abandoned intermediate implementations;
- reword the remaining commit messages to be concise, imperative Conventional Commit messages;
- retain one or more genuinely atomic, independently reviewable semantic commits when the issue contains distinct
  stages or concerns.

Do not rewrite existing `dev` history, commits that predate the current issue, commits belonging to another task, or
history that has already been pushed or shared unless the user explicitly authorizes that rewrite. If unrelated or
shared commits make the issue range unsafe to rewrite, preserve the history and report the constraint instead.

## Agent-Specific Instructions

Respect user changes in the worktree. Prefer focused edits, run the narrowest useful verification, and update docs or
active machine ABI references when behavior changes.
Before changing behavior or packaging, select every applicable repository-local workflow: `compukters-language-feature`
for Guest Kotlin semantics, `compukters-abi-change` for versioned boundaries, `compukters-runtime-change` for VM and
runtime ownership, `compukters-neoforge-integration` for Minecraft/loader behavior, and `compukters-release` for
distributable artifacts or release-readiness claims. Use `compukters-cross-layer-triage` only while ownership of a
failure is unclear. Shared skills govern the development process; local skills govern Compukters invariants. Combine
applicable local skills for cross-boundary work, but do not load unrelated workflows merely to enumerate them.
Use the `using-github-roadmap` skill before architecturally significant work: new subsystems, cross-module capabilities,
material component-boundary or public API/ABI changes, or work requiring a design specification or coordinated stages.
Localized fixes, docs, tests, mechanical refactors, routine chores, and read-only investigation do not require an issue.
Keep temporary design specs and implementation plans under `.agents/tmp/specs/` and `.agents/tmp/plans/` respectively;
do not place them under `docs/` or commit them unless the user explicitly requests publication.
For GitHub operations in this repository, do not use the GitHub MCP server or GitHub app connector tools. Use the
`gh` CLI instead, including `gh api` for REST or GraphQL operations that are not covered by first-class `gh` commands.
All `gh` commands and Git commands which access a remote repository must run outside the sandbox. If an SSH remote
cannot use an SSH agent, use the authenticated GitHub CLI HTTPS credential helper for that command instead of retrying
SSH; do not change the stored remote merely to work around the sandbox.
