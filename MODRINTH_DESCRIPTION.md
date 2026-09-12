# Compukters

**Program computers in Minecraft with Kotlin through an integrated in-game IDE.**

Compukters is an in-game programming platform built around Kotlin projects, programmable computers, persistent
storage, redstone automation, and a deterministic resource-bounded virtual machine.

> **Compukters is experimental and under active development.**
>
> The complete programming loop works, but Guest Kotlin, its APIs, and the player-facing experience continue to
> evolve. Breaking changes may occur between releases.

## Write Kotlin in the integrated IDE

Create persistent multi-file projects without leaving Minecraft. The client-side IDE provides:

- syntax highlighting, diagnostics, completion, hover information, and parameter information;
- declaration navigation, Kotlin-aware formatting, smart typing, and editor history;
- a project explorer, remembered workspaces, and versioned Compukters platform configuration;
- attachment to a computer with verified build, deploy, and run actions;
- read-only previews and imports from the attached computer's filesystem;
- an attached terminal for interacting with the target computer directly.

Code analysis and compilation run in isolated Kotlin K2 workers using the same Guest API definitions as the target
computer.

## Run programs on real computers

Every computer boots into an interactive shell and owns an isolated persistent `/home` filesystem. Programs and files
survive chunk unloads and world restarts. For quick edits, the computer also includes compact terminal tools:

```text
edit hello.kt
kotlinc hello.kt
hello
```

Guest programs can launch bounded cooperative tasks, communicate through `IntChannel`, start other programs, wait for
terminal input, and produce computer sounds.

## Automate with redstone

Programs can read and control all six sides of a computer relative to its facing. The Guest Kotlin API supports:

- immediate redstone input reads;
- efficient waits for the next change, an exact level, or a minimum level;
- output levels from `0` to `15`;
- persistent weak and direct power modes.

## Kotlin, without unrestricted JVM access

Compukters uses the Kotlin K2 frontend, but Guest programs do not run as Kotlin/JVM and cannot access arbitrary Java
classes, Minecraft internals, the server filesystem, or the host operating system:

```text
Kotlin source
    ↓
Kotlin K2 / IR
    ↓
verified Compukter bytecode
    ↓
managed runtime
```

The runtime controls memory, execution quotas, scheduling, and every capability exposed to programs. Guest Kotlin is
a deliberately bounded subset of the language and standard library; consult the support matrix for exact details.

## Compatibility

- **Minecraft 1.21.1** — NeoForge 21.1.250 or newer, Java 21
- **Minecraft 26.1.2** — NeoForge 26.1.2.97 or newer, Java 25
- **Operating systems:** Linux x86_64 and Windows x86_64

Each Minecraft version has its own download. No Architectury runtime dependency is required.

## Links

- [Documentation and getting started](https://certifiedbadideas.github.io/Compukters/)
- [Guest Kotlin support matrix](https://certifiedbadideas.github.io/Compukters/KOTLIN-SUPPORT/)
- [Redstone API and behavior](https://certifiedbadideas.github.io/Compukters/REDSTONE/)
- [Changelog](https://certifiedbadideas.github.io/Compukters/CHANGELOG/)
- [Source code](https://github.com/CertifiedBadIdeas/Compukters)
- [Development blog](https://t.me/lazyhatdev) — in Russian
- [Software license](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/LICENSE.md) — Apache-2.0
- [Media licenses and credits](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/MEDIA-LICENSES.md)
