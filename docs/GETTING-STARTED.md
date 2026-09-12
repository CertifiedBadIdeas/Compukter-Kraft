---
layout: default
title: Getting started
description: Install Compukters and run your first Kotlin program from the terminal or the in-game IDE.
---

# Getting started

This guide takes you from a clean installation to a Kotlin program running inside a Minecraft computer. The primary
authoring path is the project-based client IDE; a small editor and compiler inside each computer provide an autonomous
terminal workflow. Both produce the same verified Compukter executable format.

## Requirements

| Component | Supported baseline |
|---|---|
| Minecraft: Java Edition | Exactly **26.1.2** or **1.21.1** |
| NeoForge | **26.1.2.97+** for Minecraft 26.1.2; **21.1.250+** for Minecraft 1.21.1 |
| Java | **JDK 25** for 26.1.2; **Java 21** for 1.21.1 |
| Packaged native runtime | **Linux x86_64** or **Windows x86_64** |

The mod is required on both the client and server. macOS and ARM builds are not part of the current published
artifacts.

## Install Compukters

1. Install the Java, Minecraft, and NeoForge versions from one matching baseline above.
2. Download the matching `compukters-<minecraft>-neoforge-<version>.jar` asset from the
   [latest Compukters release](https://github.com/CertifiedBadIdeas/Compukters/releases/latest).
3. Put the JAR in the `mods` directory of the client and, for multiplayer, the server.
4. Start the game with the matching NeoForge profile. Compukters has no Architectury runtime dependency.

Use a disposable world while learning the current development release. Back up worlds before moving them between mod
versions.

## Place and open a computer

Compukters does not currently provide a survival recipe. In a creative world, open the **Compukters** creative tab and
take a **Compukter**, or run:

```text
/give @s compukters:compukter
```

Place the block and use it with an empty hand. Its terminal opens and the built-in shell displays a `>` prompt after the
computer boots. Run `help` to see the currently available shell commands.

## Path 1: build and run a project in the IDE

The IDE stores projects on the client under `<game directory>/compukters/ide/projects`. A project can contain multiple
Kotlin files and can be deployed to the computer you opened it from.

1. Click **IDE** in the computer terminal or press **Ctrl+I**. Opening the IDE from the terminal automatically attaches
   that computer as the target. You can also look directly at a computer and press Ctrl+I.
2. Choose **Create project**, enter `hello`, and confirm. The IDE creates `compukter.toml` and opens `src/main.kt`.
3. Enter this program:

```kotlin
fun main() {
    println("Hello from Compukters!")
}
```

4. Press **Ctrl+S**, then use **Build** or press **Ctrl+F9**. Wait for the status line to report a successful build.
5. Use **Deploy** to install `/home/hello` on the attached computer. Confirm the overwrite dialog if that path already
   exists.
6. Open the **Terminal** tool on the right and run `hello` at the shell prompt.

The triangular **Run** action is the shortcut for the final sequence: it saves, builds, deploys the manifest program,
and submits its installed path to the attached computer.

## Path 2: write directly inside the computer

The computer also carries a compact terminal editor and compiler. At the shell prompt, open a source file with
`edit hello.kt`, enter the program above, press **Ctrl+S**, and then **Ctrl+X**. Compile it with:

```text
kotlinc hello.kt
```

Without `-o`, `kotlinc` writes an extensionless executable with the source basename. Run it from the shell:

```text
hello
```

The terminal should print:

```text
Hello from Compukters!
```

The source and executable live in this computer's writable `/home` filesystem. The built-in `edit`, `kotlinc`, and
`shell` programs live in the read-only `/rom` filesystem. `/home` survives ordinary saves and world reloads; breaking a
computer removes its active filesystem and moves the data to a recoverable tombstone rather than treating the dropped
block as a portable disk.

### Useful IDE shortcuts

| Shortcut | Action |
|---|---|
| Ctrl+I | Open the IDE |
| Ctrl+S | Save the active source |
| Ctrl+F9 | Build the project |
| Ctrl+Space | Request completion |
| Ctrl+P | Show parameters for the call at the caret |
| Ctrl+B | Go to declaration |
| Ctrl+Alt+L | Reformat the active Kotlin source |
| Alt+Left / Alt+Right | Navigate backward / forward |

## Current boundaries

Compukters intentionally supports a focused Kotlin subset. Do not assume that arbitrary Kotlin/JVM libraries, Java
interop, reflection, threads, ordinary coroutines, collections, floating-point types, or exceptions are available. The
[Guest Kotlin support matrix](https://certifiedbadideas.github.io/Compukters/KOTLIN-SUPPORT/) is the compatibility contract for language features, standard-library
operations, Guest APIs, and IDE behavior.

Programs execute in a deterministic managed VM rather than a general JVM. CPU work, memory, filesystem access, terminal
I/O, processes, and redstone are admitted through bounded runtime contracts.

## Next steps

- Connect a program to the world with [Redstone GPIO](https://certifiedbadideas.github.io/Compukters/REDSTONE/).
- Check exact language support in the [Guest Kotlin support matrix](https://certifiedbadideas.github.io/Compukters/KOTLIN-SUPPORT/).
- Contributors can continue with [Architecture](https://certifiedbadideas.github.io/Compukters/ARCHITECTURE/) and [Verification](https://certifiedbadideas.github.io/Compukters/VERIFICATION/).
- Report a reproducible problem through [GitHub Issues](https://github.com/CertifiedBadIdeas/Compukters/issues).
