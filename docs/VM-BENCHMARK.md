---
layout: default
title: In-world VM benchmark
description: Load real Compukter VMs in Minecraft and profile their server-tick cost.
---

# In-world VM benchmark

`vmbench` is an ordinary Guest Kotlin executable in `/rom`. It uses the same compiler output, verifier, process stack,
interpreter, quotas, and Minecraft computer lifecycle as player programs; it has no privileged benchmark runtime path.

The first workload isolates integer and branch execution. It does not measure terminal, filesystem, compiler,
redstone, network, or managed-allocation throughput.

Compukters also provides operator-only fleet commands. The headless mode isolates the native VM and actor scheduler;
the area mode drives ordinary placed computers and therefore includes block-entity, chunk, filesystem, and Minecraft
lifecycle overhead. Neither mode is a player-facing computer network or delivery protocol.

## Run one workload

At a computer shell, run:

```text
vmbench cpu <rounds>
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

## Profile the actor scheduler

With cheats or server-operator permission, start up to 4096 ephemeral benchmark VMs:

```text
/compukters vmbench start <count 1..4096> <rounds 1..1000000>
/compukters vmbench status
/compukters vmbench stop
```

Headless VMs execute an internal verified artifact through the same native session and actor scheduler used by placed
computers. The artifact receives `rounds` through an ordinary terminal Text event, not a benchmark-only VM operation.
It has no persistent filesystem or block entity and is closed through the actor lifecycle barrier after completion,
explicit stop, or server shutdown.

`status` reports admitted, active, completed, failed, and closing actors; elapsed ticks and current smoothed Minecraft
MSPT; worker, mailbox, and result occupancy; average command-queue, execution, and completed-result latency; the last
server pump size and duration; and mailbox rejection deltas. Admission may be lower than requested when ordinary
computers already occupy the configured actor capacity. Run `stop` before changing the workload, and wait for `STOPPED`
before starting another fleet.

For an initial saturation profile, record an idle baseline and compare equal intervals at 1, 10, 100, 500, 1000, and
4096 actors. Use enough rounds that the fleet remains active for the complete observation interval. Scheduler
saturation should increase queue latency and completion time rather than Minecraft MSPT.

## Dispatch to physical computers

After placing and booting computers, send the ordinary shell command to every loaded computer in a bounded cuboid:

```text
/compukters vmbench area <from> <to> <rounds 1..1000000>
```

For example, `compukters vmbench area 0 64 0 9 73 9 1000000` scans a 10x10x10 region. The region may contain at most
32,768 block positions and at most 1000 computers receive a command. The dispatcher never loads chunks: unloaded
positions are counted and skipped. It first reports the scan and scheduled fan-out, then reports how many computers
accepted or rejected the asynchronous canonical command. Boot the fleet and let every shell reach its prompt before
dispatching if you want all computers to accept it.

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
