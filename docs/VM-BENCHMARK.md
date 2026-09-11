---
layout: default
title: In-world VM benchmark
description: Load real Compukter VMs in Minecraft and profile their server-tick cost.
---

# In-world VM benchmark

`vmbench` is an ordinary Guest Kotlin executable in `/rom`. It uses the same compiler output, verifier, process stack,
interpreter, quotas, and Minecraft computer lifecycle as player programs; it has no privileged benchmark runtime path.

The CPU workload isolates integer and branch execution. The redstone workload exercises acknowledged server-thread
world commits. Neither workload measures terminal, filesystem, compiler, network, or managed-allocation throughput.

Compukters also provides operator-only fleet commands. The headless mode isolates the native VM and actor scheduler;
the area mode drives ordinary placed computers and therefore includes block-entity, chunk, filesystem, and Minecraft
lifecycle overhead. Neither mode is a player-facing computer network or delivery protocol.

## Run one workload

At a computer shell, run:

```text
vmbench cpu <rounds>
vmbench redstone <rounds>
```

`rounds` must be an ASCII decimal value from `1` through `1000000`. Each round performs 1,024 iterations of a fixed
allocation-free integer kernel. For a quick correctness check:

```text
vmbench cpu 2
```

The result must end with:

```text
vmbench cpu: checksum=-365826314
```

The program prints once before and once after the hot loop. It makes no terminal or host-capability calls while the
workload is running, so a long run will leave the displayed start line unchanged.

`redstone` first normalizes the computer's local **top** weak output to zero. Every round then produces one `15 -> 0`
pulse. Each of those two transitions uses the ordinary Guest redstone API and suspends until the physical output is
committed on the Minecraft server thread and acknowledged back to the VM. Sequential suspension prevents the high and
low transitions from being combined into one commit. For example:

```text
vmbench redstone 2
```

finishes with a zero top output and:

```text
vmbench redstone: acknowledged transitions=4
```

The completion line is printed only after the final clear is acknowledged. Shutdown, reboot, and block removal retain
their normal production behavior and do not add benchmark-specific cleanup.

## Profile the actor scheduler

With cheats or server-operator permission, start up to 4096 ephemeral benchmark VMs:

```text
/compukters vmbench start <count 1..4096> <rounds 1..1000000>
/compukters vmbench capacity <count 1..4096> <rounds 1..1000000>
/compukters vmbench status
/compukters vmbench stop
```

Headless VMs execute an internal verified artifact through the same native session and actor scheduler used by placed
computers. The artifact receives `rounds` through an ordinary terminal Text event, not a benchmark-only VM operation.
It has no persistent filesystem or block entity and is closed through the actor lifecycle barrier after completion,
explicit stop, or server shutdown.

`capacity` runs the same artifact as a phased sequence. It first advances every admitted VM until the program is
waiting for terminal input, then leaves the settled fleet untouched for 100 server ticks. At the end of that idle
window it requests one resource snapshot from each waiting actor, sends the ordinary rounds Text event to wake the
whole fleet, and executes the same CPU workload as `start`. The idle window therefore measures resident capacity
without benchmark-generated VM execution; normal actor-service bookkeeping and unrelated server work continue.

`status` reports admitted, active, completed, failed, and closing actors; elapsed ticks and current smoothed Minecraft
MSPT; worker, mailbox, and result occupancy; average command-queue, execution, and completed-result latency; the last
server pump size and duration; and mailbox rejection deltas. Capacity reports additionally include the current phase
and waiting count; nearest-rank median, p95, and maximum settle, wake, and completion tick counts; and aggregate heap
used/capacity plus mutable execution-resident bytes. Missing resource replies are counted separately. While a fleet is
running or stopping, the command source that started it receives this report on every phase transition, automatically
every five seconds within a phase, and once more for the final `COMPLETED` or `STOPPED` state. The explicit `status`
command remains available for an immediate snapshot. Admission may be lower than requested when ordinary computers
already occupy the configured actor capacity. Run `stop` before changing the workload, and wait for `STOPPED` before
starting another fleet.

For an initial saturation profile, record an idle baseline and compare equal intervals at 1, 10, 100, 500, 1000, and
4096 actors. Use enough rounds that the fleet remains active for the complete observation interval. Scheduler
saturation should increase queue latency and completion time rather than Minecraft MSPT.

For the production capacity check requested by issue #606, run
`/compukters vmbench capacity 1000 1000000`. Preserve the automatic phase reports: the idle report describes resident
cost after settling, while wake and CPU reports show the scheduler burst and sustained execution separately.

## Dispatch to physical computers

After placing and booting computers, send the ordinary shell command to every loaded computer in a bounded cuboid:

```text
/compukters vmbench area <from> <to> <rounds 1..1000000>
/compukters vmbench area <from> <to> redstone <rounds 1..1000000>
```

The original form submits `/rom/vmbench cpu <rounds>` and remains compatible. The explicit `redstone` form submits
`/rom/vmbench redstone <rounds>`. For example, `compukters vmbench area 0 64 0 9 73 9 redstone 600` scans a 10x10x10
region and starts 600 acknowledged pulses on each accepted computer. The region may contain at most 32,768 block
positions. At most 4096 computers receive a command, matching the default actor-service capacity. The dispatcher never
loads chunks: unloaded positions are counted and skipped. It first reports the scan and scheduled fan-out, then reports
how many computers accepted or rejected the asynchronous canonical command. While the run is active, it reports
progress every five seconds and emits one final `COMPLETED` report. `/compukters vmbench status` shows the latest
physical-area run instead of the headless
fleet when `area` was the latest benchmark command. A second `area` command is rejected until the retained run reaches
`COMPLETED`, so an accidental repeat cannot replace its status. Boot the fleet and let every shell reach its prompt
before dispatching if you want all computers to accept it.

The physical report separates command delivery from execution: `pending`, `accepted`, and `rejected` describe delivery;
`active`, `completed`, and `unavailable` describe accepted computers using their already-published runtime state. This
status scan does not poll terminals, load chunks, or submit actor requests. Redstone runs also show `world`, the
world-request delta since dispatch, and `pulseProgress`, that delta divided by the expected two acknowledged transitions
per accepted computer and round. Treat the percentage as an aggregate throughput aid: unrelated world requests on the
same actor service can contribute to the delta, while the computer-state counts determine completion.

## Profile scaling in a world

Use a disposable world with cheats enabled, otherwise idle loaded chunks, and a fixed render and simulation distance.
Keep the Minecraft, NeoForge, Compukters, Java, and hardware versions unchanged across the comparison.

1. Record a zero-benchmark baseline with the server's vanilla profiler: run `/debug start`, wait for a fixed interval
   such as 60 seconds, then run `/debug stop`.
2. Place and boot one computer. Confirm `vmbench cpu 2` produces the checksum above.
3. Start `vmbench cpu 1000000`, or use the bounded `area` command, and capture another profile for the same interval.
4. Repeat with 2, 4, 8, and then more simultaneously running computers. Begin profiling only after the whole fleet is
   running.
5. For every run, record the computer count, profile duration, observed milliseconds per tick or TPS, and the generated
   vanilla profile archive. Stop increasing the fleet once the server cannot sustain its target tick rate.

Minecraft writes `/debug` profiling results beneath the current game or server directory. Compare equal-duration runs;
the benchmark intentionally defines deterministic VM work, not a universal wall-clock threshold. Faster hosts may
complete more aggregate work before their tick time degrades.

## Profile world-request capacity

Use a disposable layout and keep the block above every computer empty. The benchmark intentionally sends weak power
through the top face; attached redstone components add their own neighbor-update cost and should only be present when
that is part of the experiment. Keep all benchmark chunks loaded without relying on the dispatcher to load them.

1. Boot the complete physical fleet and wait until every computer shows its shell prompt.
2. Record an idle `/debug` profile and the nearest `VM actors` debug-log record. The actor service logs one record every
   five seconds.
3. Dispatch the redstone workload to 1 computer, then repeat on otherwise identical 10, 100, 500, 1000, and optionally
   4096-computer layouts. Choose enough rounds to cover the intended observation interval; the measured 1000-computer
   development layout took about two minutes at 600 rounds, so 300 rounds is a practical one-minute starting point on
   that host. Use the same rounds and observation interval at every size.
4. For each size, preserve the automatic area reports, an equal-duration `/debug` profile, the first and last
   `VM actors` records, and the wall-clock time until the area status reaches `COMPLETED` and `worldDeferred` returns to
   zero.
5. Spot-check that terminals end with the expected `acknowledged transitions=<rounds * 2>` line and zero top output
   before the next run.

Compare MSPT/TPS together with deltas, not absolute lifetime counters. `worldDeferred` is the current number of actors
waiting on a world acknowledgement, while the `worldTotal` delta counts world requests surfaced during the interval.
Also record `results`, `queueAvgUs`/`queueMaxUs`, `resultAvgUs`/`resultMaxUs`, `drainedLast`, `pumpLastUs`,
`inputRejected`, and `mailboxRejected`. A healthy saturation curve increases queue and completion latency under the
fixed server-thread bound; it must not turn one tick into unbounded world work. A `worldTotal` delta of twice the round
count per accepted computer, followed by `worldDeferred=0`, is the expected complete-run shape when every top output
starts at zero. A computer whose top output was nonzero contributes one additional normalization request.

## Stop a long run

`vmbench` runs as a foreground process and Compukters does not currently provide Ctrl+C process signalling. For a
headless fleet, use `/compukters vmbench stop`. For physical computers, use the existing **Shutdown** or **Reboot**
action. Breaking the block also closes the active machine, but use a disposable world and prefer an orderly power
action when gathering profiles.

Unused work does not accumulate while the computer is unloaded or powered off. Start a fresh workload after rebooting
instead of treating interrupted and resumed observations as one benchmark run.

## Report useful results

Include the following when attaching measurements to
[#473](https://github.com/CertifiedBadIdeas/Compukters/issues/473):

- Compukters commit or release and the Minecraft, NeoForge, and Java versions;
- CPU model, operating system, allocated heap, render distance, and simulation distance;
- computer count, command, warmup, and measured duration;
- baseline and loaded milliseconds per tick or TPS;
- the vanilla profiler archive and any visible overload messages.
