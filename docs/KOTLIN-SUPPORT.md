---
layout: default
title: Guest Kotlin support
---

# Guest Kotlin support

Compukters accepts Kotlin source through a pinned K2 frontend, then lowers it
to Compukter bytecode for the managed Rust VM. This is **Guest Kotlin**, not
Kotlin/JVM: K2 accepting source does not imply Java interoperability, JVM
library compatibility, or executable support in Compukters.

The compiler process uses Kotlin compiler libraries internally, but those host
dependencies are not visible to Guest source. Guest name resolution contains
only the native declarations published by the selected Compukters platform
modules. Their metadata, ordinary precompiled Kotlin bodies, and trusted
external bindings form one versioned platform contract.

This matrix describes the repository revision that contains it.

## Status legend

- [x] **Supported** — the narrowly stated behavior has execution-level
  conformance evidence, or a focused tooling test for an IDE-only claim.
- [ ] **Partial** — a useful subset works, but the stated boundary remains.
- [ ] **Unsupported** — the backend deliberately rejects the construct or has
  no implementation for it.
- **Not planned** — an intentional platform boundary, not queued work.

Every checked item names its evidence. Unchecked work links an exact tracking
issue when scheduled; otherwise it says `Tracking: not scheduled`. Compiler
acceptance alone is not execution evidence: a VM operation and a K2 construct
must meet through conformance coverage before the construct is marked
supported.

## Entry points and projects

- [x] **`main(args: Array<String>)` argument contract** — ordinary and
  `suspend` entry points receive one owned array whose strings preserve their
  exact UTF-16 code units. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  test `string array entry lowers deterministically for vm argv conformance`,
  and
  [`kotlin_writer.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/compiler-artifact/src/test/rust/executable-conformance/kotlin_writer.rs),
  test `k2_string_array_entry_executes_exact_utf16_arguments`.

- [ ] **Four legal `main` forms — Partial** — `fun main()`,
  `suspend fun main()`, and their single-`Array<String>` variants lower with
  explicit entry tags, but only the argument-bearing runtime contract has a
  dedicated K2-to-VM execution test. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  test `all four legal main forms lower deterministically with an explicit entry contract`.
  Tracking: not scheduled

- [x] **Invalid entry points are rejected** — duplicate entries, missing
  entries, unsupported parameters, nullable argument arrays, and non-`Unit`
  results produce no artifact. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  tests `entry policy rejects duplicate and invalid main functions` and
  `entry policy rejects a project without main`.

- [ ] **Multi-file projects — Partial** — cross-file top-level calls share one
  K2 session and lower deterministically, while the in-computer `kotlinc`
  command still accepts exactly one source file. Evidence:
  [`K2CompilerAdapterTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/K2CompilerAdapterTest.kt),
  test `cross-file reference participates in one K2 session before bounded lowering`,
  and
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  tests `multi-file terminal program lowers through trusted symbols` and
  `kotlinc command line rejects ambiguous or unsupported arguments`.
  Tracking: not scheduled

- [ ] **Project manifests and modules — Partial** — `compukter.toml` selects a
  portable native platform module graph; the IDE, analyzer, and compiler use
  the same declarations and versions. Compiler output is still one application
  artifact rather than an independently distributable Kotlin module ecosystem.
  Tracking: not scheduled

## Types and numeric semantics

- [x] **`Int`, `Boolean`, and `Char` scalar values** — these source types lower
  to distinct verified VM scalar types with Kotlin-compatible control and
  comparison behavior. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  tests `bounded when forms compile for admitted scalar types` and
  `primitive char array lowers deterministically for exact utf16 materialization`,
  paired with [`tests.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/host/compukter-vm/src/execution/tests.rs), test
  `scalar_vectors_match_kotlin_jvm_semantics`.

- [ ] **`Unit` and `Nothing` — Partial** — `Unit` function results and
  non-returning trusted intrinsics are admitted, but general `Nothing`
  expressions such as arbitrary throws are not lowered. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  tests `ordinary and suspend zero argument Unit main lower deterministically`
  and `typed process v2 facade lowers without public capability masks or suspend calls`.
  Tracking: not scheduled

- [ ] **`Byte`, `Short`, `Long`, `Float`, and `Double` — Unsupported** — the
  VM defines additional scalar operations, but the Guest source signature and
  value-type registry do not admit these Kotlin types; `Long` is covered by an
  explicit rejection test. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  test `unsupported source IR produces one stable target diagnostic and no artifact`.
  Tracking: not scheduled

- [ ] **Unsigned types — Unsupported** — `UByte`, `UShort`, `UInt`, and
  `ULong` have no Guest representation or standard operations; a `UInt`
  program is rejected as unsupported IR. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  test `unsupported source IR produces one stable target diagnostic and no artifact`.
  Tracking: not scheduled

- [ ] **Integer arithmetic — Partial** — `Int` supports `+`, `-`, `*`, `/`,
  `%`, unary minus, `and`, `or`, `xor`, `inv`, `shl`, and `ushr` with VM
  wrapping and masked-shift semantics. The remaining integer widths are not
  lowered from source.
  Evidence:
  [`KotlinProjectLowering`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/compiler-k2-engine/src/main/kotlin/ru/lazyhat/compukters/compiler/k2/engine/KotlinProjectLowering.kt)
  and [`numeric.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/host/compukter-vm/src/execution/numeric.rs), test
  `integers_wrap_mask_shifts_and_handle_min_division`.
  Tracking: not scheduled

- [ ] **Conversions — Partial** — `Int.toChar()` is lowered; general numeric
  conversions are outside the source subset even where the VM has conversion
  semantics. Tracking: not scheduled

## Expressions and control flow

- [x] **Scalar `when` with individual branches** — `Int`, `Char`, `Boolean`,
  and `String` subjects, plus subjectless boolean conditions, lower to bounded
  deterministic branches; matched and fallback paths execute in the VM.
  Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  tests `bounded when lowers deterministically for vm execution` and
  `bounded when forms compile for admitted scalar types`, and
  [`kotlin_writer.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/compiler-artifact/src/test/rust/executable-conformance/kotlin_writer.rs),
  test `k2_bounded_when_selects_matched_and_fallback_branches`.

- [ ] **Pattern-rich `when` — Unsupported** — range membership, comma-joined
  branch conditions, and arbitrary `Any` type patterns do not publish an
  artifact. Type branches over the admitted sealed class subset are handled
  separately under the object model. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  test `unsupported when patterns produce no artifact`.
  Tracking: not scheduled

- [ ] **`if`, blocks, mutable locals, and `while` — Partial** — these forms
  compile and are used by the checked-in shell, including nested loops and
  reassignment. `break` and `continue` targeting the current innermost `while`
  lower directly; jumps to an outer loop are rejected. There is no
  source-level conformance suite covering every expression/result shape or
  ordinary `do-while`. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  tests `shell language subset lowers control flow scalars strings and raw terminal calls`,
  `checked in shell compiles deterministically`, and
  `while loop jumps lower locally and reject outer targets`.
  Tracking: not scheduled

- [x] **Allocation-free unit-step `Int` `for` loops** — `start..endInclusive`,
  `start until endExclusive`, and `start..<endExclusive` evaluate and snapshot
  both bounds once, then execute as scalar frame slots with no `IntRange` or
  iterator allocation. Empty, reversed, singleton, negative, and
  `Int.MAX_VALUE` boundaries preserve Kotlin behavior. `break` and `continue`
  targeting the current innermost `for` are supported, including nested loops.
  Every repeated path crosses an existing loop-header quota safepoint.
  Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  tests `inclusive Int for loops lower without range or iterator allocation`,
  `exclusive Int for loops lower without range or iterator allocation`,
  `Int for loop supplies its generated increment constant`, and
  `allocation free Int loops lower deterministically for vm execution`, plus
  [`kotlin_writer.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/compiler-artifact/src/test/rust/executable-conformance/kotlin_writer.rs),
  test `k2_int_loops_execute_across_quota_slices_without_host_io`.

- [ ] **Other ranges, progressions, and iterable `for` loops — Unsupported** —
  `downTo`, `step`, arrays, strings, collections, custom iterators, ordinary
  source `do-while`, and labeled jumps to an outer loop publish no artifact.
  Non-loop `IntRange`, `until`, and `rangeUntil` calls are declaration-only and
  are not a general executable range API. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  test `unsupported loop forms publish no artifact`.
  Tracking: not scheduled

- [ ] **Destructuring and delegated expressions — Unsupported** — component
  calls, delegated storage, and their generated source shapes are not admitted
  as a supported contract. Tracking: not scheduled

## Functions and calls

- [ ] **Top-level and member calls — Partial** — direct top-level calls,
  immutable property getters, and supported member operations lower by exact
  symbol. Arbitrary library or virtual dispatch remains outside the subset.
  Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  tests `multi-file terminal program lowers through trusted symbols` and
  `same-named guest function remains an ordinary project call`.
  Tracking: not scheduled

- [x] **Direct `suspend` project calls** — a suspending Guest function may call
  another suspending project function and resume across an asynchronous host
  capability. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  test `suspend project call lowers deterministically for vm execution`, and
  [`kotlin_writer.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/compiler-artifact/src/test/rust/executable-conformance/kotlin_writer.rs),
  test `k2_suspend_project_call_resumes_across_async_capability`.

- [ ] **Default arguments — Partial** — omitted `Array<String>` parameters are
  supported only for direct `emptyArray()` or direct `arrayOf` call defaults;
  general default expressions and constructor defaults are rejected.
  Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  tests `string arrays support copyOfRange and supported default arguments`
  and `guest object subset rejects mutable generic initialized secondary and explicitly cast shapes`.
  Tracking: not scheduled

- [ ] **Extension functions and overloads — Partial** — K2 resolves project
  extensions and overloads by symbol, and same-named project functions do not
  impersonate trusted intrinsics. Execution coverage is not comprehensive.
  Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  tests `same-named char array helper remains an ordinary project call` and
  `same-named guest function remains an ordinary project call`.
  Tracking: not scheduled

- [ ] **Named and vararg arguments — Partial** — ordinary K2 argument binding
  works only when the resulting direct call stays in the admitted signature
  subset; direct `arrayOf` varargs are specially lowered, while spread arrays
  are rejected. Tracking: not scheduled

- [ ] **Generic functions and classes — Unsupported** — user type parameters
  are outside the Guest object and signature subset. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  test `guest object subset rejects mutable generic initialized secondary and explicitly cast shapes`.
  Tracking: not scheduled

- [ ] **Lambdas, local functions, and function references — Unsupported** —
  function objects and nested declaration ownership are not represented by the
  current backend. Tracking: not scheduled

- [ ] **Recursion — Partial** — direct calls and bounded VM call depth can
  represent recursion, but no Kotlin-to-VM recursive source conformance test
  defines it as a supported language contract. Tracking: not scheduled

## Classes and object model

- [ ] **Immutable constructor classes — Partial** — classes with a primary
  constructor whose every parameter is an immutable backed property lower to
  managed objects. VM allocation, field access, inheritance layout, and type
  checks are verified independently, but the source class fixture is not yet
  executed end to end. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  test `guest object subset lowers sealed results data values enum identity and type branches`,
  paired with [`heap_tests.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/host/compukter-vm/src/execution/heap_tests.rs),
  tests `heap_instructions_round_trip_reference_fields` and
  `heap_instructions_use_inherited_fields_and_interface_closure`.
  Tracking: not scheduled

- [ ] **Sealed interfaces, data classes, and stateless enums — Partial** — the
  admitted fixture lowers sealed result types, immutable data values, enum
  identity, exhaustive type branches, and smart-cast property reads. It lacks
  an end-to-end source execution test and does not imply all generated data or
  enum methods. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  test `guest object subset lowers sealed results data values enum identity and type branches`.
  Tracking: not scheduled

- [ ] **Mutable properties, custom initializers, computed properties,
  constructor defaults, secondary constructors, and stateful enums — Unsupported** —
  each of these shapes is rejected before artifact publication. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  test `guest object subset rejects mutable generic initialized secondary and explicitly cast shapes`.
  Tracking: not scheduled

- [ ] **User `object` declarations — Unsupported** — the source class layout
  admits classes, interfaces, and enums, but not singleton object declarations.
  Trusted Guest API objects are compiler-provided facades, not evidence for
  user-defined objects. Tracking: not scheduled

- [ ] **Type tests and casts — Partial** — `is` checks and compiler-generated
  smart casts over admitted references lower to VM type checks and checked
  casts; explicit `as` source casts are rejected. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  tests `guest object subset lowers sealed results data values enum identity and type branches`
  and `guest object subset rejects mutable generic initialized secondary and explicitly cast shapes`,
  paired with [`heap_tests.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/host/compukter-vm/src/execution/heap_tests.rs),
  test `heap_instructions_checked_cast_handles_nullability_and_incompatibility`.
  Tracking: not scheduled

- [ ] **Primitive `value class` declarations — Partial** — a value class with
  exactly one `Int`, `Boolean`, or `Char` property erases to that scalar for
  constructors, properties, methods, operators, constants, and trusted ABI
  calls. `@JvmInline` is deliberately rejected because it belongs to the JVM
  platform, not Guest Kotlin. Nullable, generic, reference-backed, boxed, and
  multi-property forms are rejected. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  test `typed redstone side API lowers deterministically to scalar capability operations`,
  and
  [`CanonicalPlatformSourceTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/guest-platform/src/test/kotlin/ru/lazyhat/compukters/platform/source/CanonicalPlatformSourceTest.kt),
  which rejects JVM-only value-class syntax from native platform sources.
  Tracking: not scheduled

## Nullability and exceptions

- [ ] **Nullable user references — Unsupported** — artifact and VM types can
  encode nullable references, but nullable Kotlin source values and operations
  have no admitted lowering and execution contract. Tracking: not scheduled

- [ ] **Safe calls, Elvis, and non-null assertions — Unsupported** — the IR
  shapes and exception behavior produced by these operators are not part of
  the admitted source subset. Tracking: not scheduled

- [ ] **`throw`, `try`, `catch`, and `finally` — Unsupported** — the artifact
  and VM have verified exception tables, but the K2 backend does not lower
  `IrThrow` or `IrTry` from Guest source. Tracking: not scheduled

- [ ] **Standard exception classes — Unsupported** — Kotlin/JVM exception
  classes are not a Guest standard-library surface. VM traps and bounded host
  failures remain typed runtime outcomes rather than catchable Kotlin
  exceptions. Tracking: not scheduled

- [x] **Compiler diagnostic source coordinates** — syntax and type diagnostics
  preserve virtual paths and UTF-16 offsets while bounding count and text.
  Evidence:
  [`K2CompilerAdapterTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/K2CompilerAdapterTest.kt),
  tests `syntax and type diagnostics use virtual paths and UTF-16 offsets` and
  `diagnostic count text and physical paths are bounded`.

## Strings, arrays, and collections

- [x] **UTF-16 `CharArray` materialization** — `CharArray(size)`, indexed
  access, mutation, `size`, `concatToString(start, end)`, and
  `String(array, start, length)` preserve exact UTF-16 code units through
  Guest execution. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  test `primitive char array lowers deterministically for exact utf16 materialization`,
  and
  [`kotlin_writer.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/compiler-artifact/src/test/rust/executable-conformance/kotlin_writer.rs),
  test `k2_char_array_program_executes_exact_utf16_materialization`.

- [ ] **`String` operations — Partial** — literals, concatenation,
  interpolation lowered as concatenation, `length`, indexed `get`,
  `substring`, equality, and construction from `CharArray` map to verified VM
  operations. Other Kotlin text functions are not available. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  test `shell language subset lowers control flow scalars strings and raw terminal calls`,
  paired with [`text_tests.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/host/compukter-vm/src/execution/text_tests.rs),
  tests `string_content_operations_use_kotlin_utf16_semantics`,
  `string_concat_selects_utf16_for_bmp_and_surrogate_code_units`, and
  `string_substring_preserves_full_identity_and_freshens_proper_ranges`.
  Tracking: not scheduled

- [ ] **`Array<String>` operations — Partial** — entry arrays,
  `emptyArray<String>()`, direct `arrayOf` calls, `size`, indexed get/set, and
  `copyOfRange` are lowered. General `Array<T>`, spread arguments, iterators,
  and higher-order operations are unavailable. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  tests `string arrays can be constructed read and written` and
  `string arrays support copyOfRange and supported default arguments`, paired
  with [`heap_tests.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/host/compukter-vm/src/execution/heap_tests.rs), test
  `heap_instructions_round_trip_reference_arrays`.
  Tracking: not scheduled

- [x] **Specialized `IntArray` storage** — `IntArray(size)`, `intArrayOf(...)`,
  empty arrays, `size`, indexed get/set, and mutation lower to dense unboxed
  i32 storage. Factory arguments evaluate left-to-right exactly once; negative
  sizes, oversized allocations, and invalid indexes preserve VM trap or
  allocation-exhaustion behavior across quota slices. Initializer lambdas,
  `Array<Int>`, direct iteration, `indices`, spread arguments, covariance,
  reflection, and collection helpers remain outside the admitted subset.
  Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  tests `specialized IntArray lowers to unboxed primitive array instructions`,
  `unsupported IntArray forms publish no artifact`, and
  `specialized IntArray lowers deterministically for vm conformance`, plus
  [`kotlin_writer.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/compiler-artifact/src/test/rust/executable-conformance/kotlin_writer.rs),
  test `k2_int_array_executes_specialized_storage_and_traps`.

- [ ] **Other primitive arrays — Unsupported** — primitive arrays other than
  `CharArray` and `IntArray` have no source-level Guest representation even
  though the VM can store every primitive array width. Tracking: not scheduled

- [ ] **Collections, sequences, and iterators — Unsupported** — `List`,
  `Set`, `Map`, collection builders, iteration protocols, and sequence APIs
  are absent; `listOf(1)` is explicitly rejected as unsupported IR. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  test `unsupported source IR produces one stable target diagnostic and no artifact`.
  Tracking: not scheduled

## Coroutines and concurrency

- [x] **Direct suspension across a host request** — a `suspend` Guest call
  resumes at its verified continuation block after an asynchronous capability
  response. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  test `suspend project call lowers deterministically for vm execution`, and
  [`kotlin_writer.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/compiler-artifact/src/test/rust/executable-conformance/kotlin_writer.rs),
  test `k2_suspend_project_call_resumes_across_async_capability`.

- [ ] **VM-blocking calls from ordinary functions — Partial** — designated
  Guest API calls such as terminal event waiting lower from an ordinary caller
  and the VM verifies the blocking-capability contract, but the generated
  ordinary-main fixture is not executed end to end. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  test `ordinary main lowers trusted terminal wait as vm blocking`, paired
  with [`verify/tests.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/host/compukter-vm/src/verify/tests.rs), tests
  `vm_blocking_capability_is_valid_in_a_non_suspending_function` and
  `vm_blocking_capability_does_not_claim_coroutine_semantics`.
  Tracking: not scheduled

- [ ] **Coroutine builders, scopes, cancellation, and structured concurrency — Unsupported** —
  `kotlinx.coroutines` is not part of the Guest standard library, and Guest
  programs cannot create concurrent tasks. Tracking: not scheduled

- [ ] **Parallel Guest execution — Unsupported** — one process waits at a
  suspension point; concurrent scheduling within one program is future work.
  Tracking: not scheduled

## Native platform modules

The platform catalog currently publishes these module identities. A project
sees the modules selected by `compukter.toml`, their transitive dependencies,
and the mandatory built-ins module; there is no ambient Kotlin/JVM classpath.

| Module | Guest surface |
| --- | --- |
| `kotlin:builtins` | Core language types, arrays, function types, and structural declarations required by K2 |
| `stdlib:core` | Small native core helpers such as `require` and supported array construction |
| `stdlib:ranges` | Declaration surface for `IntRange`, `until`, and `rangeUntil`; canonical unit-step `Int` loops lower without runtime range objects |
| `std:terminal` | `print`, `println`, `readln`, stderr, and raw terminal operations |
| `std:filesystem` | The bounded filesystem facade |
| `compukter:compiler` | Guest compilation operations |
| `compukter:process` | Child process execution and explicit exit |
| `compukter:redstone` | Side-oriented redstone reads, waits, and weak/direct output writes |
| `compukter:sound` | Bounded one-shot computer beeps with admission feedback |

Ordinary functions in these modules are compiled ahead of Guest projects into
relocatable platform fragments. Only declarations explicitly marked as native
external bindings lower to host capability operations; a Guest declaration
cannot become one merely by copying its package, name, and signature.

## Kotlin standard library

- [ ] **Console functions — Partial** — `print` accepts `String`, `Int`,
  `Boolean`, and `Char`; `println` supports those types plus the no-argument
  form; `readln()` reads one canonical line. Other overloads and formatting
  are unavailable. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  test `ordinary Kotlin standard streams lower to stdio capability operations`,
  paired with [`computer.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/host/compukter-vm/src/computer.rs), tests
  `stdio_read_line_echoes_then_writes_stdout_and_stderr_in_order` and
  `stdio_read_conflict_becomes_bounded_host_failure_without_consuming_input`.
  Tracking: not scheduled

- [ ] **Core scalar operations — Partial** — the admitted `Int`, `Boolean`,
  and `Char` operations listed above are provided by `kotlin:builtins` and
  canonical compiler primitives. The wider primitive API, parsing, formatting,
  bit operations, and math packages are absent. Tracking: not scheduled

- [ ] **Text and array helpers — Partial** — only the `String`, `CharArray`,
  `IntArray`, and `Array<String>` operations listed above are published by the
  native built-ins and core modules. Regex, Unicode categories, encodings,
  generic array helpers, and collection conversions are absent. Tracking: not scheduled

- [ ] **Standard collections and functional helpers — Unsupported** — the
  collection hierarchy and higher-order functions such as `map`, `filter`,
  and `fold` are not Guest runtime types. Tracking: not scheduled

- [ ] **Standard exceptions, reflection, and coroutine libraries — Unsupported** —
  these packages have no Guest implementation. Tracking: not scheduled

## Compukters Guest APIs

- [x] **One-shot sound** — `Sound.beep(note)` and
  `Sound.beep(note, volume)` emit the vanilla note-block pling from the
  computer and return whether the server admitted it. Notes are bounded to
  `0..24`, with `12` as neutral pitch; volume is bounded to `1..100` and
  defaults to `100`. A computer may emit once every four ticks, the server
  admits at most 64 computer sounds per tick, and rejected sounds are not
  queued. The VM validates the scalar request, while the actor carrier performs
  the Minecraft call on the server thread before resuming the Guest Boolean.
  Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  test `sound beep lowers deterministically to a blocking Boolean capability operation`,
  [`computer.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/host/compukter-vm/src/computer.rs), sound request tests, and
  [`ComputerSoundGameTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/v26_1/v26_1-neoforge/src/gameTest/kotlin/ru/lazyhat/compukters/impl/computer/ComputerSoundGameTest.kt).

- [x] **Redstone GPIO** — `Redstone.<side>` exposes immediate `get()`,
  edge-triggered `await()`, exact `await(level)`, threshold
  `awaitAtLeast(level)`, and blocking
  `set(level, power = Redstone.Power.WEAK)`, with `Redstone.Power.DIRECT` for
  direct power. These operations
  lower through the trusted scalar capability while packed output batching
  remains private to the runtime. Rust waiter tests, core batch-commit tests, and the
  real NeoForge `compukters:computer_redstone` GameTest cover the complete path.
  Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  test `redstone program lowers deterministically for vm conformance`,
  [`computer.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/host/compukter-vm/src/computer.rs), redstone tests, and
  [`ComputerRedstoneGameTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/v26_1/v26_1-neoforge/src/gameTest/kotlin/ru/lazyhat/compukters/impl/computer/ComputerRedstoneGameTest.kt).

- [x] **Terminal write, event wait, and key result** — `Terminal.write`,
  `Terminal.awaitEvent`, and `Terminal.eventKey` lower to exact terminal
  capability calls and execute across a host request. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  test `suspend project call lowers deterministically for vm execution`, and
  [`kotlin_writer.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/compiler-artifact/src/test/rust/executable-conformance/kotlin_writer.rs),
  test `k2_suspend_project_call_resumes_across_async_capability`.

- [ ] **Remaining raw terminal operations — Partial** — clear, erase, text and
  action/modifier event fields, and event completion lower through trusted
  signatures and have device-level VM tests, but lack generated
  Kotlin-to-VM execution coverage. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  test `shell language subset lowers control flow scalars strings and raw terminal calls`,
  paired with
  [`terminal_device.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/host/compukter-vm/tests/terminal_device.rs), tests
  `stable_key_and_atomic_text_events_merge_in_fifo_order` and
  `input_limits_reject_whole_events_without_partial_queue_mutation`.
  Tracking: not scheduled

- [ ] **Positional terminal drawing — Partial** — cursor position and
  visibility, palette colors, `writeAt`, and rectangular `fill` lower through
  exact trusted signatures and have VM device conformance, but no generated
  Kotlin program executes the complete facade end to end. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  test `positional terminal facade lowers through exact trusted signatures`,
  paired with
  [`terminal_device.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/host/compukter-vm/tests/terminal_device.rs), tests
  `positional_patch_and_fill_do_not_move_the_stream_cursor` and
  `positional_terminal_write_clips_one_row_and_decodes_scalars`.
  Tracking: not scheduled

- [ ] **Filesystem facade — Partial** — `stat`, `list`, `readText`, and
  `writeText` have exact trusted signatures and bounded VM operations.
  Lowering coverage currently executes only at the compiler/VM sides
  separately. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  test `filesystem text facade lowers through exact trusted signatures`,
  and [`computer.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/host/compukter-vm/src/computer.rs), tests
  `filesystem_text_response_is_bounded_before_guest_materialization` and
  `filesystem_text_write_replaces_existing_bytes_through_the_machine`.
  Tracking: not scheduled

- [ ] **Process facade — Partial** — `Process.run(path, args)` returns typed
  exited/failed results, and `Process.exit(code)` terminates explicitly. The
  source facade and VM process contract are covered separately rather than by
  one end-to-end generated program. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  test `typed process v2 facade lowers without public capability masks or suspend calls`,
  paired with [`computer.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/host/compukter-vm/src/computer.rs), tests
  `process_v2_run_materializes_structured_arguments_for_the_child` and
  `process_v2_explicit_exit_preserves_all_codes_and_rejects_invalid_values`.
  Tracking: not scheduled

- [ ] **Compiler facade — Partial** — `Compiler.compile(source, output)` and
  `Compiler.diagnostics()` are published by `compukter:compiler`, and the
  checked-in `/rom/kotlinc` program compiles deterministically. Full
  Guest-to-host compilation behavior is tested at the VM transaction layer
  rather than as one generated Kotlin execution test. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  test `checked in kotlinc compiles deterministically`, paired with
  [`computer.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/host/compukter-vm/src/computer.rs), test
  `compiler_transaction_snapshots_and_atomically_installs_an_executable`.
  Tracking: not scheduled

- [x] **Trusted API identity** — a user declaration cannot impersonate a Guest
  intrinsic merely by copying its name and signature. The canonical registry
  keys every external binding by selected platform module, Kotlin callable ID,
  and exact canonical signature; lowering also verifies that the declaration
  came from native platform metadata or the exact platform source module.
  Evidence:
  [`TrustedIntrinsicContractTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/compiler-k2-engine/src/test/kotlin/ru/lazyhat/compukters/compiler/k2/engine/intrinsic/TrustedIntrinsicContractTest.kt)
  and
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  test `platform callable lookalike remains an ordinary project call`.

## IDE and tooling

- [x] **Incremental lexical highlighting** — edits propagate lexical state and
  remain identical to a full scan. Evidence:
  [`IncrementalKotlinHighlighterTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/ide-core/src/test/kotlin/ru/lazyhat/compukters/ide/highlight/IncrementalKotlinHighlighterTest.kt),
  tests `edits propagate lexical state and remain identical to a full scan`
  and `seeded random edits always equal the full-scan oracle`.

- [x] **Smart Kotlin delimiter and block entry** — writable Kotlin sources
  insert and track balanced delimiters, wrap selections, remove untouched
  pairs with Backspace, and preserve structural indentation and line endings
  on Enter without applying the behavior to plain-text files. Evidence:
  [`KotlinSmartTypingTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/ide-core/src/test/kotlin/ru/lazyhat/compukters/ide/editor/KotlinSmartTypingTest.kt),
  tests `pairs wrap and tracked closers remain distinct from ordinary source`,
  `paired backspace and undo are atomic`, `pairing is suppressed inside strings and comments`,
  and `structural enter preserves CRLF and splits an automatic brace pair`,
  plus [`IdeClientControllerTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/ide-client/src/test/kotlin/ru/lazyhat/compukters/ide/client/controller/IdeClientControllerTest.kt),
  test `Kotlin smart typing flows through writable editor while plain text stays literal`.

- [x] **On-demand Kotlin formatting** — Ctrl+Alt+L and the toolbar Format
  action format writable `.kt` sources with ktlint standard rules, applying
  the result as one undoable edit with a mapped UTF-16 caret and leaving it
  dirty for a separate save. Stale results never replace newer typing;
  formatter failures warn without saving, while Ctrl+S, autosave, implicit
  saves, previews, and non-Kotlin files remain unaffected.
  Evidence:
  [`KotlinFormatterTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/ide-kotlin-formatter/src/test/kotlin/ru/lazyhat/compukters/ide/formatter/KotlinFormatterTest.kt),
  [`RelocatedKotlinFormatterTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/ide-kotlin-formatter/src/test/kotlin/ru/lazyhat/compukters/ide/formatter/RelocatedKotlinFormatterTest.kt),
  [`IsolatedKotlinFormatterTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/ide-analysis-k2/src/test/kotlin/ru/lazyhat/compukters/ide/analysis/k2/formatter/IsolatedKotlinFormatterTest.kt),
  [`FormatQueryTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/ide-analysis-k2/src/test/kotlin/ru/lazyhat/compukters/ide/analysis/k2/query/FormatQueryTest.kt),
  and
  [`IdeAnalysisFlowTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/ide-client/src/test/kotlin/ru/lazyhat/compukters/ide/client/controller/IdeAnalysisFlowTest.kt),
  tests `explicit format changes Kotlin atomically and leaves saving separate`,
  `stale format result never overwrites or saves newer typing`,
  `format failure warns without saving the unformatted Kotlin source`, and
  `explicit save bypasses the asynchronous formatter`.

- [x] **Semantic highlighting and inferred-type presentation** — declarations,
  extension functions, inferred expressions, and smart casts receive K2-backed
  semantic tokens; mutable properties, locals, and their resolved references
  carry the Islands Dark underline effect. Evidence:
  [`SemanticTokenQueryTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/ide-analysis-k2/src/test/kotlin/ru/lazyhat/compukters/ide/analysis/k2/query/SemanticTokenQueryTest.kt),
  tests `presentation classifies declarations and extension functions`,
  `presentation marks inferred and smart cast expressions`, and
  `presentation marks mutable declarations and references`, plus
  [`IdeRendererStateTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/v26_1/v26_1-neoforge/src/test/kotlin/ru/lazyhat/compukters/impl/ide/IdeRendererStateTest.kt),
  test `expression metadata does not override lexical code colors`.

- [x] **K2 diagnostics** — incomplete syntax remains analyzable, multi-file
  diagnostics retain virtual paths, and UTF-16 ranges remain exact. Evidence:
  [`DiagnosticQueryTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/ide-analysis-k2/src/test/kotlin/ru/lazyhat/compukters/ide/analysis/k2/query/DiagnosticQueryTest.kt),
  tests `type error after supplementary character keeps UTF-16 range`,
  `diagnostics from multiple files retain their virtual paths`, and
  `incomplete syntax produces a bounded diagnostic instead of failing analysis`.

- [x] **Semantic completion with overloads** — completion uses inferred
  receivers, applicable extensions, visibility, distinct overload entries,
  argument labels, deterministic ranking, and bounded result counts. Evidence:
  [`CompletionQueryTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/ide-analysis-k2/src/test/kotlin/ru/lazyhat/compukters/ide/analysis/k2/query/CompletionQueryTest.kt),
  tests `qualified completion uses inferred receiver members and applicable extensions`,
  `completion preserves overloads and orders them deterministically`, and
  `completion gives standard library overloads distinct argument labels`,
  plus
  [`CompletionIntegrationTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/ide-analysis-k2/src/test/kotlin/ru/lazyhat/compukters/ide/analysis/k2/integration/CompletionIntegrationTest.kt),
  test `forked worker returns semantic completion`.

- [x] **Context-aware keyword completion** — declaration, modifier, statement,
  and expression keywords are ranked with semantic symbols for valid file,
  class-body, and executable-block contexts, while imports, package directives,
  qualified access, comments, and literal string content suppress them. Evidence:
  [`CompletionQueryTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/ide-analysis-k2/src/test/kotlin/ru/lazyhat/compukters/ide/analysis/k2/query/CompletionQueryTest.kt),
  tests `completion proposes keywords for declarations and executable blocks`
  and `completion suppresses keywords outside unqualified Kotlin code`.

- [x] **Expression information and callable signatures** — hover-style
  queries render inferred local types, resolved signatures, and smart-cast
  types. Evidence:
  [`ExpressionInfoQueryTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/ide-analysis-k2/src/test/kotlin/ru/lazyhat/compukters/ide/analysis/k2/query/ExpressionInfoQueryTest.kt),
  tests `expression query renders an inferred local type`,
  `expression query renders a resolved callable signature`, and
  `expression query reports a smart cast type`.

- [x] **Parameter information** — Ctrl+P opens a caret-anchored popup for the
  innermost call, lists bounded and deterministic K2-resolved overload
  signatures, and highlights the active positional, named, or vararg
  parameter. The popup follows edits and caret movement, rejects stale
  snapshot results, and closes on Escape, focus loss, file changes, or when
  the caret leaves a call. Evidence:
  [`ParameterInfoQueryTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/ide-analysis-k2/src/test/kotlin/ru/lazyhat/compukters/ide/analysis/k2/query/ParameterInfoQueryTest.kt),
  [`AnalysisRequestCoordinatorTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/ide-analysis-client/src/test/kotlin/ru/lazyhat/compukters/ide/analysis/controller/AnalysisRequestCoordinatorTest.kt),
  [`IdeAnalysisFlowTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/ide-client/src/test/kotlin/ru/lazyhat/compukters/ide/client/controller/IdeAnalysisFlowTest.kt),
  [`IdeInputAdapterTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/v26_1/v26_1-neoforge/src/test/kotlin/ru/lazyhat/compukters/impl/ide/IdeInputAdapterTest.kt),
  and
  [`IdeRendererStateTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/v26_1/v26_1-neoforge/src/test/kotlin/ru/lazyhat/compukters/impl/ide/IdeRendererStateTest.kt).

- [x] **Navigation and project references** — declarations, selected platform
  APIs, builtins such as `intArrayOf`, and exact project references resolve to
  their attached sources without matching unrelated same-spelling symbols.
  Evidence:
  [`NavigationAndReferencesTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/ide-analysis-k2/src/test/kotlin/ru/lazyhat/compukters/ide/analysis/k2/integration/NavigationAndReferencesTest.kt),
  tests `forked worker navigates and finds exact project references` and
  `forked worker navigates to attached builtin source`, paired
  with [`DeclarationQueryTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/ide-analysis-k2/src/test/kotlin/ru/lazyhat/compukters/ide/analysis/k2/query/DeclarationQueryTest.kt),
  test `navigation maps int array factory to its platform source`, and
  [`ReferenceQueryTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/ide-analysis-k2/src/test/kotlin/ru/lazyhat/compukters/ide/analysis/k2/query/ReferenceQueryTest.kt),
  test `references cross project files and exclude unrelated same spelling symbols`.

- [x] **Local project build and cache** — the client builds real project
  snapshots, reuses the global compiler cache, deduplicates active work, and
  keeps compiler I/O off the caller thread. Evidence:
  [`LocalIdeWorkflowTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/ide-client/src/test/kotlin/ru/lazyhat/compukters/ide/client/integration/LocalIdeWorkflowTest.kt),
  test `real project resolves builds and reuses global compiler cache`, and
  [`ClientCompilationServiceTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/ide-core/src/test/kotlin/ru/lazyhat/compukters/ide/compiler/ClientCompilationServiceTest.kt),
  tests `deduplicates active build and admits one distinct queued build` and
  `cache hit avoids another worker request and all IO stays on service thread`.

- [x] **Target verification, deployment, and run** — verification is
  non-mutating, successful tickets can be reused by deployment, and Run saves,
  builds, deploys the manifest program, then submits its installed path.
  Evidence:
  [`IdeTargetCoordinatorTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/ide-client/src/test/kotlin/ru/lazyhat/compukters/ide/client/target/IdeTargetCoordinatorTest.kt),
  tests `verify is non mutating and its matching ticket is reused by deploy`
  and `run deploys then submits exactly the installed path`, plus
  [`IdeTargetFlowTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/ide-client/src/test/kotlin/ru/lazyhat/compukters/ide/client/controller/IdeTargetFlowTest.kt),
  test `run saves builds deploys manifest program and submits canonical line`.

- [ ] **Debugger and runtime inspection — Unsupported** — there are no
  breakpoints, stepping, watches, stack inspection, or live variable views.
  Tracking: not scheduled

## Intentional non-goals

- **Not planned: Java interoperability and JVM bytecode/libraries.** Guest
  programs target Compukter bytecode, not a JVM.
- **Not planned: reflection and dynamic class loading.** Runtime types and code
  are admitted from verified artifacts before execution.
- **Not planned: arbitrary compiler plugins and annotation processors.** The
  trusted compiler pipeline and selected native platform modules define the
  source surface.
- **Not planned: ambient access to host JVM or operating-system resources.**
  Guest programs cross only explicit, bounded capability interfaces.

## Maintenance policy

- A commit that changes Guest Kotlin support updates the affected matrix entry
  and its evidence in the same commit.
- Every checked item keeps a stable repository link and names the exact test
  behavior that supports it.
- A scheduled gap links its exact implementation issue; broad umbrella issues
  do not replace feature-specific tracking.
- Removing support unchecks the item and states the new boundary in the same
  change.
- Intentional non-goals change only through an explicit architecture decision,
  not by converting them into unchecked tasks.
- This document carries no manually maintained release number or commit hash;
  it always describes the revision that contains it.
