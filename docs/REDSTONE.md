# Redstone GPIO

Each computer exposes six redstone sides relative to its case: `front`, `back`,
`left`, `right`, `top`, and `bottom`. Horizontal sides rotate with the
computer's facing; top and bottom remain vertical. Guest programs never need
world directions.

## Guest API

Select a side through `Redstone.<side>` and operate on that side directly:

```kotlin
import compukter.redstone.Redstone

fun main() {
    val current = Redstone.left.get()

    Redstone.right.set(15)                         // weak power by default
    Redstone.top.set(15, Redstone.Power.DIRECT)   // direct power

    val changed = Redstone.left.await()
    Redstone.front.await(7)
    Redstone.back.awaitAtLeast(7)
}
```

Levels are integers in `0..15`; passing another value fails before host I/O.
`get()` returns the latest sampled input immediately. It does not suspend the
program.

The wait operations block the current VM task:

- `await()` is edge-triggered and waits for the next sampled change. The new
  level can be any value, including `0`.
- `await(level)` is level-triggered and completes when the input equals that
  level. It may complete immediately from the current snapshot.
- `awaitAtLeast(level)` is level-triggered and completes when the input is at
  least that level. It may also complete immediately.

`set(level, power = Redstone.Power.WEAK)` is blocking host I/O. `WEAK` emits
ordinary weak power and is the default. `DIRECT` additionally exposes the same
level as vanilla direct power, allowing propagation through an adjacent solid
conductor. Direct power is a mode, not a second independently programmable
level.

The public API intentionally has no packed-register or multi-side builder. The
runtime may still fold concurrent writes into one physical commit per computer
per server tick; that batching is an implementation detail rather than a Guest
Kotlin data model.

## Minecraft behavior

Input changes are coalesced at the server-tick boundary. Every transmitted
packet contains the complete six-side level snapshot plus a changed-side mask.
A pulse that starts and ends between two samples may therefore be missed by
design. Waiter interest does not change world sampling.

Minecraft owns and persists the packed output register. Program completion,
shutdown, faults, reboot, VM replacement, and chunk reload do not reset it.
Rust owns the current input snapshot, waiters, and a confirmed output mirror;
each new VM session is seeded from Minecraft before it runs.

A concise multi-side DSL is deferred until Guest Kotlin supports receiver
lambdas.
