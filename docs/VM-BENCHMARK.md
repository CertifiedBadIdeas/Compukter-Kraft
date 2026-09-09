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

## Profile scaling in a world

Use a disposable world with cheats enabled, otherwise idle loaded chunks, and a fixed render and simulation distance.
Keep the Minecraft, NeoForge, Compukters, Java, and hardware versions unchanged across the comparison.

1. Record a zero-benchmark baseline with the server's vanilla profiler: run `/debug start`, wait for a fixed interval
   such as 60 seconds, then run `/debug stop`.
2. Place and boot one computer. Confirm `vmbench cpu 2` produces the checksum above.
3. Start `vmbench cpu 1000000` and capture another profile for the same interval.
4. Repeat with 2, 4, 8, and then more simultaneously running computers. Start the same command on every computer and
   begin profiling only after the whole fleet is running.
5. For every run, record the computer count, profile duration, observed milliseconds per tick or TPS, and the generated
   vanilla profile archive. Stop increasing the fleet once the server cannot sustain its target tick rate.

Minecraft writes `/debug` profiling results beneath the current game or server directory. Compare equal-duration runs;
the benchmark intentionally defines deterministic VM work, not a universal wall-clock threshold. Faster hosts may
complete more aggregate work before their tick time degrades.

## Stop a long run

`vmbench` runs as a foreground process and Compukters does not currently provide Ctrl+C process signalling. Use the
computer's existing **Shutdown** or **Reboot** action to stop it. Breaking the block also closes the active machine, but
use a disposable world and prefer an orderly power action when gathering profiles.

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
