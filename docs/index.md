---
layout: default
title: Programmable computers, bounded by design
description: Write Kotlin in Minecraft and run it in a deterministic managed VM.
---

# Programmable computers, bounded by design.

{: .hero-copy }
Compukters is an in-game programming platform for Minecraft. Write Kotlin, compile it with a pinned K2 toolchain, and run
verified programs inside a deterministic, resource-bounded managed VM.

<div class="actions">
  <a class="button primary" href="{{ '/GETTING-STARTED/' | relative_url }}">Get started</a>
  <a class="button" href="https://github.com/CertifiedBadIdeas/Compukters">View source</a>
</div>

<span class="version-chip">Minecraft 26.1.2</span>
<span class="version-chip">NeoForge 26.1.2.97+</span>
<span class="version-chip">Java 25</span>

<div class="feature-grid">
  <section class="feature-card">
    <h3>Kotlin in game</h3>
    <p>Edit and compile directly in a computer terminal, or work with multi-file projects in the client IDE.</p>
  </section>
  <section class="feature-card">
    <h3>Deterministic runtime</h3>
    <p>Verified Compukter bytecode runs with explicit execution quotas, managed memory, and bounded host capabilities.</p>
  </section>
  <section class="feature-card">
    <h3>Real automation</h3>
    <p>Read redstone inputs, drive persistent outputs, handle terminal events, and keep programs on a persistent filesystem.</p>
  </section>
</div>

## Choose a path

- **New player:** follow [Getting started](https://certifiedbadideas.github.io/Compukters/GETTING-STARTED/) from installation to your first running program.
- **Guest Kotlin author:** check the exact [Kotlin support matrix](https://certifiedbadideas.github.io/Compukters/KOTLIN-SUPPORT/) before relying on a language or library feature.
- **Automation builder:** learn the local-side model in [Redstone GPIO](https://certifiedbadideas.github.io/Compukters/REDSTONE/).
- **Following development:** see the continuous [Changelog](https://certifiedbadideas.github.io/Compukters/CHANGELOG/).
- **Contributor:** start with [Architecture](https://certifiedbadideas.github.io/Compukters/ARCHITECTURE/) and [Verification](https://certifiedbadideas.github.io/Compukters/VERIFICATION/).

Compukters is under active development. The support matrix describes shipped behavior; roadmap ideas are not part of the
current compatibility contract.
