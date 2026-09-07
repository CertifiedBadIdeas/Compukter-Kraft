# Compukters

**Compukters brings programmable computers to Minecraft, with Kotlin tooling in the game and a custom sandboxed virtual machine.**

Write Kotlin programs, compile them, and run them on computers in your world. Use the computer's compact terminal and built-in tools for quick edits, or work in the client-side IDE on a multi-file project and deploy it to a computer when it is ready.

> **Compukters is experimental and under active development.**
>
> The playable programming loop works end to end, but Guest Kotlin, its APIs, and the player-facing experience are still evolving. Expect incomplete features and breaking changes between releases.

## Program inside Minecraft

Each computer boots into an interactive shell and has its own persistent `/home` filesystem. The packaged tools let you edit, compile, and run a program without leaving the game:

```text
edit hello.kt
kotlinc hello.kt
hello
```

The source and compiled program survive chunk unloads and world restarts. Programs run in the foreground and can launch other programs through the Guest process API.

## Use the in-game IDE

The client-side IDE provides a richer workflow for local multi-file Kotlin projects:

- syntax highlighting, diagnostics, completion, hover information, and declaration navigation;
- editor history, smart typing, Kotlin-aware formatting, and familiar keyboard editing;
- a project explorer and versioned Compukters platform configuration;
- attachment to a computer, verified deployment, and optional launch after deployment;
- read-only previews and imports from the attached computer's filesystem;
- an attached terminal, so you can use the target computer without leaving the IDE.

Compilation and code analysis use isolated Kotlin K2 workers and the same Guest API definitions, so the IDE understands the platform that will run the program.

## Automate with redstone

Programs can read and control all six sides of a computer relative to its facing. The Guest Kotlin API supports:

- immediate redstone input reads;
- waiting for the next change, an exact level, or a minimum level;
- output levels from `0` to `15`;
- weak and direct power modes.

Redstone output persists across program completion, reboot, chunk reload, and world restart. Input changes are sampled at server-tick boundaries, allowing programs to wait efficiently instead of polling continuously.

## Kotlin, without the JVM

Guest programs use Kotlin syntax and the K2 frontend, but they do not run as Kotlin/JVM and cannot access arbitrary Java classes or Minecraft internals:

```text
Kotlin source
    ↓
Kotlin K2 / IR
    ↓
verified Compukter bytecode
    ↓
managed Rust VM
```

The VM controls memory, execution quotas, and access to the outside world. Programs can interact only through explicit capabilities such as the terminal, filesystem, processes, compiler, and redstone APIs. This keeps execution deterministic and prevents Guest code from reaching the server filesystem or host operating system.

Guest Kotlin is currently a deliberately limited subset of the language and standard library. See the [support matrix](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/docs/KOTLIN-SUPPORT.md) for the exact language, library, and API boundaries.

## Compatibility

- **Minecraft 26.1.2**
- **NeoForge 26.1.2.97 or newer for Minecraft 26.1.2**
- **Java 25**

No Architectury runtime dependency is required.

## Links

- [Source code](https://github.com/CertifiedBadIdeas/Compukters)
- [Guest Kotlin support matrix](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/docs/KOTLIN-SUPPORT.md)
- [Redstone API and behavior](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/docs/REDSTONE.md)
- [Development blog](https://t.me/lazyhatdev) — in Russian
- Software license: [Apache-2.0](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/LICENSE.md)
- [Media licenses and credits](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/MEDIA-LICENSES.md)
