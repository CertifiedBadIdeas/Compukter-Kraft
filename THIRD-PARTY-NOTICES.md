# Third-party notices

This inventory covers third-party material checked into the repository or
distributed in the Compukters production mod archive. It does not change any
third-party license. The complete Apache License 2.0 text is available at
`licenses/project/Apache-2.0.txt` and is packaged as
`META-INF/licenses/Compukters-Apache-2.0.txt`.

Apache-2.0 is the default for original Compukters software and source material,
not a blanket media license. Original media exceptions and the machine-readable
media inventory are documented in [MEDIA-LICENSES.md](MEDIA-LICENSES.md).

`licenses/distribution-components.tsv` is the machine-readable inventory used
by archive verification. Dependency version changes must update both files.

## XZ decompression

The outer mod archive contains XZ for Java 1.10 for deterministic compression
and pure-Java streaming decompression of the packaged K2 tooling bundle. XZ
for Java is copyright the XZ for Java authors and contributors and licensed
under 0BSD. The complete license is at
`licenses/jvm/xz-java-1.10-0BSD.txt`; upstream:
<https://github.com/tukaani-project/xz-java>.

## Kotlin compiler and libraries

The compiler worker contains JetBrains Kotlin compiler and runtime artifacts
from Kotlin 2.4.10, plus `kotlin-reflect` 1.6.10. The outer mod archive also
nests `kotlin-stdlib` 2.4.10. JetBrains-owned Kotlin code is licensed under
Apache-2.0. Kotlin contains third-party code under the additional licenses
identified by its upstream license inventory.

- Upstream: <https://github.com/JetBrains/kotlin>
- Pinned tag: `v2.4.10`
- Pinned commit: `5687445832cd835b4509b9fbc264cdf1a8201093`
- Complete license corpus: `licenses/kotlin/v2.4.10/`
- Packaged location: `META-INF/licenses/kotlin/v2.4.10/`

The worker also contains JetBrains annotations 13.0, licensed under
Apache-2.0: <https://github.com/JetBrains/java-annotations>.

## Kotlin Analysis API worker

The separately launched IDE analysis worker contains the pinned Kotlin 2.4.10
unshaded compiler and the `analysis-api-*-for-ide`,
`low-level-api-fir-for-ide`, `symbol-light-classes-for-ide`, and
`analysis-api-standalone-for-ide` aggregates published by JetBrains. These
artifacts are licensed under Apache-2.0 and use the Kotlin license corpus
listed above.

The worker also contains these pinned runtime dependencies:

- IntelliJ's patched `kotlinx-coroutines-core-jvm` 1.8.0-intellij-13,
  `kotlinx-serialization-core-jvm` 1.7.3, Caffeine 2.9.3, Error Prone
  annotations 2.10.0, and JetBrains annotations 23.0.0 — Apache-2.0.
- IntelliJ Platform `util-diff` 251.27812.49 — Apache-2.0; upstream:
  <https://github.com/JetBrains/intellij-community>.
- Checker Qual 3.19.0 — MIT; complete text at
  `licenses/jvm/checker-qual-3.19.0-MIT.txt`.

The Analysis API artifacts come from
<https://packages.jetbrains.team/maven/p/ij/intellij-dependencies>. Their
generated POMs name unpublished source modules which are already shaded into
the `for-ide` JARs, so the build resolves those aggregates non-transitively and
verifies the complete packaged JAR inventory.

## Kotlin formatter runtime

The analysis worker privately embeds ktlint 1.8.0 and its standard ruleset for
explicit on-demand Kotlin formatting. Ktlint is copyright Pinterest, Inc., Stanley
Shyiko, and contributors and is licensed under MIT. Its complete license is at
`licenses/jvm/ktlint-1.8.0-MIT.txt`. The formatter uses EditorConfig Java
(`ec4j-core`) 1.1.1, Poko annotations 0.20.1, and Kotlin Logging 7.0.13 under
Apache-2.0.

The compiler-free formatter runtime is adapted to the ordinary IntelliJ
namespace and reuses the analysis worker's packaged Kotlin compiler/runtime
files. Those files are loaded again in a separate child classloader rather than
duplicated in the distribution or shared with the live K2 Analysis API state.

SLF4J API 2.0.18 is copyright QOS.ch Sarl and licensed under MIT. Its complete
license is at `licenses/jvm/slf4j-2.0.18-MIT.txt`.

- Ktlint upstream: <https://github.com/pinterest/ktlint>
- EditorConfig Java upstream: <https://github.com/ec4j/editorconfig-core-java>
- Poko upstream: <https://github.com/drewhamilton/Poko>
- SLF4J upstream: <https://github.com/qos-ch/slf4j>

## Kotlin coroutines and logging

The worker contains `kotlinx-coroutines-core-jvm` 1.8.0 and the outer archive
nests version 1.11.0. Kotlin coroutines are copyright JetBrains and the Kotlin
contributors and are licensed under Apache-2.0:
<https://github.com/Kotlin/kotlinx.coroutines>.

The outer archive nests `kotlin-logging-jvm` 8.0.4. Kotlin Logging is copyright
Ohad Shai and contributors and is licensed under Apache-2.0:
<https://github.com/oshai/kotlin-logging>.

## IDE project metadata

The outer archive nests the lightweight libraries used to read local IDE
project manifests and lock files:

- Tomlj 1.1.1 — Apache-2.0. The complete Apache License 2.0 text is packaged
  at `META-INF/licenses/Compukters-Apache-2.0.txt`.
- ANTLR 4 Runtime 4.11.1 — BSD-3-Clause; complete text at
  `licenses/jvm/antlr4-runtime-4.11.1-BSD-3-Clause.txt`.

## Statically linked Rust crates

The packaged native `compukter_ffi` library statically links the crates below.
For crates offered under `MIT OR Apache-2.0`, this distribution selects
Apache-2.0. Versions are pinned by the single VM workspace lock at
`host/compukter-vm/Cargo.lock`.

- `block-buffer` 0.10.4 — Apache-2.0
- `cfg-if` 1.0.4 — Apache-2.0
- `cpufeatures` 0.2.17 — Apache-2.0
- `crypto-common` 0.1.7 — Apache-2.0
- `digest` 0.10.7 — Apache-2.0
- `sha2` 0.10.9 — Apache-2.0
- `typenum` 1.20.1 — Apache-2.0
- `version_check` 0.9.5 — Apache-2.0
- `generic-array` 0.14.7 — MIT; complete text at
  `licenses/rust/generic-array-0.14.7-LICENSE.txt`

Crate sources and authorship metadata are available through
<https://crates.io/> and the source URLs recorded in Cargo package metadata.

## Material Symbols

The IDE toolbar includes a generated PNG atlas derived from Material Symbols
Sharp icons at commit `0cbb08816df07faaae3dca060d4ebb10b66c214f`, copyright
Google LLC, licensed under Apache-2.0. The complete license and provenance are
packaged as `META-INF/licenses/Material-Symbols-Apache-2.0.txt` and
`META-INF/licenses/Material-Symbols-PROVENANCE.txt`.

## Terminal fonts

The mod distributes generated bitmap atlases derived from these pinned fonts:

- Cozette v1.30.0, copyright Ines, MIT. Complete license and provenance:
  `modules/minecraft/shared/neoforge/src/main/resources/META-INF/licenses/Cozette-MIT.txt`
  and `Cozette-PROVENANCE.txt`.
- Dina v2.92 Regular 6pt, copyright Joergen Ibsen, MIT. Complete license and
  provenance: `Dina-LICENSE.txt` and `Dina-PROVENANCE.txt` in the same
  `META-INF/licenses` directory.
- ProggyTiny at commit `139ec08a38096161291792313ef5803fc4f0e37b`,
  copyright Tristan Grimmer, MIT. Complete license and provenance:
  `Proggy-MIT.txt` and `Proggy-PROVENANCE.txt` in that directory.

The source inputs and duplicate provenance records are under `tools/fonts/`.

## Gradle Wrapper

The repository includes the Gradle Wrapper from Gradle 9.7.1. Gradle is
licensed under Apache-2.0: <https://github.com/gradle/gradle>. The wrapper is a
repository/build tool and is not embedded in the Compukters production mod
archive.
