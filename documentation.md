# ADB Device Monitor — Metrics Documentation

A plain-language reference for every metric the tool collects: what it means, how it is
measured (and with which command), how to use it to make an app faster or lighter, and how
expensive the measurement itself is on the device.

---

## How sampling works (read this first)

The tool takes a **sample** on a fixed **interval** (the "tick" — e.g. every 2 seconds). On
each tick it runs **one batched `adb shell` command** that `cat`s a handful of kernel files,
separated by `echo ===MARKER===` lines so the output can be split apart on the host. Doing it
in a single round-trip keeps device overhead tiny — important on weak set-top-box hardware.

A few ideas that come up throughout this document:

- **`/proc` and `/sys` are virtual files.** Reading them doesn't touch the disk; the kernel
  generates the numbers on read. That's why most metrics are essentially free to collect.
- **Counters vs. deltas.** Many kernel values are *cumulative counters* that only ever grow
  (total CPU ticks since boot, total bytes received, total page faults). A single reading is
  meaningless; the tool stores the previous reading and reports the **difference over the
  elapsed time** (`Δvalue / Δseconds`). This is why the first sample of a run has no rate.
- **Jiffies / ticks (`USER_HZ`).** The kernel counts CPU time in "ticks," typically 100 per
  second (`USER_HZ = 100`). So 50 ticks over 1 second of one core ≈ 50% of that core.
- **Normalization.** CPU percentages in this tool are scaled to **0–100%** for the whole
  device, not the Android/`top` style where 4 cores read as 400%.

### Cost legend

| Cost | Meaning |
|------|---------|
| **Negligible** | A tiny `/proc` or `/sys` read folded into the shared batch command. No measurable device impact. |
| **Low** | A few extra small file reads per tick. |
| **Medium** | A `dumpsys` service call or reading *every* process — noticeable but fine at normal intervals. Opt-in. |

---

## System metrics

### CPU Utilization & Idle %

**What it means.** How busy the CPU is, averaged across all cores, 0–100%. *Idle %* is simply
the complement (`100 − utilization`) and represents your **headroom**.
*Example:* `CPU 38% / idle 62%` = comfortable. `CPU 92% / idle 8%` = the device is saturated
and anything new will have to wait.

**How it's calculated.** From the aggregate `cpu` line of `/proc/stat`, which lists cumulative
ticks: `user nice system idle iowait irq softirq steal …`. The tool treats **idle = idle +
iowait** and **total = sum of all fields**. Between two ticks:
`utilization = 100 × (Δtotal − Δidle) / Δtotal`.
Command: `cat /proc/stat`.

**How to use it to optimize.** Sustained high utilization is the headline symptom — pair it
with *Top-10 process CPU* or *App CPU* to find the culprit. Look for busy-wait loops,
too-frequent polling/wakeups, or heavy work that should be batched or moved off the hot path.
Idle % tells you how much room you have before adding features.

**Cost.** Negligible (one small file, already in the batch).

---

### Per-core CPU %

**What it means.** The same utilization, but broken out per logical core (`cpu0…cpuN`). It
reveals **single-threaded bottlenecks** that the aggregate hides.
*Example:* `core0 98%, core1–3 ~10%` → one thread is pinned to a single core (classic
main-thread/render-thread bottleneck) even though the "overall CPU" looks moderate.

**How it's calculated.** The per-core `cpuN` lines of `/proc/stat`, same delta math as above.
Command: `cat /proc/stat` (same read — free).

**How to use it to optimize.** A single pinned core almost always means work that can't be
parallelized is on the critical path. Move it off the UI thread, split it across worker
threads, or reduce it. On big.LITTLE chips, a hot "little" core may mean the scheduler isn't
promoting your work to a big core.

**Cost.** Negligible (same file as CPU utilization).

---

### Memory — MB & Used %

**What it means.** How much RAM is in use vs. available. *Used* = `MemTotal − MemAvailable`
(MemAvailable is the kernel's honest estimate of what a new app could get without swapping).
*Example:* `1450 / 2048 MB used (71%)`. As *available* shrinks toward zero, the Low Memory
Killer starts killing background apps — and eventually yours.

**How it's calculated.** From `/proc/meminfo`: `MemTotal`, `MemAvailable` (falls back to
`MemFree`), `Cached`, `SwapTotal/SwapFree`. Command: `cat /proc/meminfo`.

**How to use it to optimize.** Track *Used %* and *available MB* over a session. Reduce your
app's footprint (see *App PSS*), release caches/bitmaps on `onTrimMemory`, and watch for a
steady climb that never comes back down (a leak). On a 2 GB STB, staying well clear of the
low-memory threshold avoids being killed mid-playback.

**Cost.** Negligible.

---

### PSI — Pressure Stall Information (cpu / memory / io)

**What it means.** The modern, most sensitive signal for resource contention. It answers
"how much time did tasks spend **stalled waiting** for this resource?"
- **`some`** = at least one task was stalled.
- **`full`** = *every* non-idle task was stalled (reported for memory and io) — the system was
  effectively frozen on that resource.

**What `avg10` means.** PSI reports three rolling windows: `avg10`, `avg60`, `avg300` — the
percentage of the **last 10, 60, and 300 seconds** that tasks were stalled. This tool charts
**avg10** because it reacts fastest. *Example:* `mem some avg10 = 0` is healthy; `= 15` means
15% of the last 10 seconds *something* was blocked waiting on memory reclaim — you're
thrashing even if "free memory" still looks okay.

**How it's calculated.** Read directly from the kernel; the tool parses the `avg10=` value:
```
cat /proc/pressure/cpu       # e.g. "some avg10=1.23 avg60=0.50 avg300=0.10 total=…"
cat /proc/pressure/memory
cat /proc/pressure/io
```

**How to use it to optimize.** PSI is your early-warning system, often rising before FPS or
"free memory" visibly move. **CPU PSI** up → too many runnable threads competing; cut
concurrency. **Memory PSI** up → working set too big; trim allocations. **IO PSI** up → too
much disk read/write; batch, cache, or prefetch off the hot path.

**Cost.** Negligible. Requires Android 10+ / kernel 4.20+ (older devices simply report `n/a`).

---

### Load Average (1 min)

**What it means.** The average number of processes that were **runnable or in uninterruptible
(I/O) wait** over the last minute. It's only meaningful **relative to the core count**.
*Example on a 4-core box:* `1.2` = light; `4.0` = fully busy; `8.0` = twice as much work
queued as the hardware can run, so everything is waiting.

**How it's calculated.** The first number in `/proc/loadavg`. Command: `cat /proc/loadavg`.

**How to use it to optimize.** If load sits above your core count for sustained periods, you're
oversubscribed — reduce concurrent background jobs, thread-pool sizes, or scheduled work.
Unlike CPU%, load also captures tasks blocked on I/O, so a high load with *low* CPU% points at
disk/network waits.

**Cost.** Negligible.

---

### Swap Used

**What it means.** How much data the kernel has pushed out of RAM into swap (often `zram`, a
compressed in-RAM swap, on Android/STB). *Example:* `0 MB` = no pressure; `120 MB` = the system
ran short on RAM and is paying a CPU/latency tax to compress/evict pages.

**How it's calculated.** `SwapTotal − SwapFree` from `/proc/meminfo` (reported as `null` when
the device has no swap configured).

**How to use it to optimize.** Any sustained swap usage is a smell that your working set is too
big for the device. Because zram trades CPU for RAM, heavy swapping shows up as both memory
*and* CPU pressure and hurts frame timing.

**Cost.** Negligible (same read as Memory).

---

### Memory Zone Watermarks (`/proc/zoneinfo`)

**What it means.** The kernel splits RAM into *zones* (DMA, DMA32, Normal, …). For each zone it
tracks the **free page count** and three **watermarks** — `min`, `low`, `high` (in pages) — that
drive memory reclaim:
- free pages drop below **high** → the kernel starts trimming caches,
- below **low** → it wakes **kswapd** to reclaim in the background,
- below **min** → an allocating thread must do **direct reclaim** itself (a stall), and the Low
  Memory Killer is close behind.

The chart plots **free pages** against all three watermarks (summed across the active zones), and
a badge reports **how many zones are active** (zones that actually have managed memory).
*Example:* free `150,000` pages sitting well above high `11,724` = healthy headroom. Free sliding
toward low `9,770` = kswapd is working; toward min `7,816` = direct reclaim and imminent kills.

**How it's calculated.** `cat /proc/zoneinfo`; for each `Node N, zone …` block the tool reads the
`pages free` line and the `min` / `low` / `high` watermark lines, sums them across zones whose
`managed` pages > 0, and counts those active zones. Values are **page counts** (multiply by the
page size — usually 4 KiB — for bytes).

**How to use it to optimize.** This is the *mechanism* behind LMK kills and memory jank: if free
pages routinely dip under `low`/`min`, the device is memory-starved and will reclaim (or kill)
aggressively. Reduce your app's footprint (see *App PSS*) and correlate the dips with the
*Direct Reclaim* metric and LMK-kill events. Watching free vs. the watermarks tells you exactly
how much slack remains before the kernel starts fighting for memory.

**Cost.** Negligible (one extra small `/proc` read in the batch).

---

### Direct Reclaim

**What it means.** A **direct reclaim** occurs when a thread requests memory, none is free, and
*that thread itself* must synchronously reclaim pages before the allocation can proceed — a stall
that directly hurts latency (unlike background reclaim done by kswapd). This metric is the **rate
of direct-reclaim events per second**. *Example:* `0/s` is healthy; a burst of `5–10/s` during
playback means the app is allocating faster than the system can free memory, and threads are
stalling to reclaim.

**How it's calculated.** The tool watches the captured logcat and counts new lines matching
direct-reclaim / `allocstall` messages appended since the previous tick (a byte-offset counter
over the growing log file), divided by the tick duration.

> **Note:** direct reclaim is a *kernel* signal. It shows up here only if the device surfaces
> those messages in logcat (many vendor STB builds do). If your device logs them only to the
> kernel ring buffer (`dmesg`), this chart stays at 0 — in that case the *Memory Zone Watermarks*
> chart above is the always-available companion (free pages below `min` ⇒ direct reclaim is
> happening).

**How to use it to optimize.** Any sustained direct reclaim is a red flag: allocations are
stalling on memory. Pair it with the zone watermarks (free below `min`) and memory PSI. Reduce
allocations on hot paths, reuse/pool buffers, and release caches under `onTrimMemory`.

**Cost.** Negligible (reads only the newly-appended bytes of the local logcat file; no extra adb).

---

### CPU Temperature

**What it means.** The temperature of the hottest thermal zone (SoC/CPU), in °C.
*Example:* `55 °C` normal; `85 °C+` risks **thermal throttling**, where the kernel drops clock
speed to cool down — which silently tanks performance. Especially relevant for fanless STBs in
enclosed AV cabinets.

**How it's calculated.** The tool loops over every thermal zone and takes the hottest; the
sysfs value is in **milli-degrees** (so it's divided by 1000):
```
for z in /sys/class/thermal/thermal_zone*/; do cat "$z/temp"; cat "$z/type"; done
```

**How to use it to optimize.** Watch temperature **together with CPU frequency**: if temp is
high and frequency has dropped while your CPU% is still maxed, you're being throttled — the fix
is to reduce *sustained* load (background transcodes, tight loops, wakeful services), not just
peak load.

**Cost.** Negligible (tiny sysfs reads).

---

### CPU Frequency

**What it means.** The mean current clock speed of the online cores, in MHz.
*Example:* a core rated to `1800 MHz` sitting at `1000 MHz` under heavy load and heat = being
throttled; sitting low while the device is idle = healthy power-saving.

**How it's calculated.** Mean of each core's `scaling_cur_freq` (reported in kHz, converted to
MHz):
```
cat /sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq
```

**How to use it to optimize.** Two signals: (1) frequency *stuck low* while utilization and
temperature are high = thermal/power throttling → reduce heat. (2) frequency *ramping high
while the device should be idle* = your app is causing wakeups/background work → wasted power
and battery/heat.

**Cost.** Negligible.

---

### Major Page Faults

**What it means.** The rate of **major** page faults per second — faults that had to fetch a
page from disk or swap (each costs on the order of milliseconds). *Minor* faults (satisfied
from RAM) are cheap and not shown here. *Example:* `0–2/s` is normal; `50/s` means code/data
pages are being evicted and reloaded constantly — memory thrashing.

**How it's calculated.** The cumulative `pgmajfault` counter from `/proc/vmstat`, turned into a
rate (`Δ/Δseconds`):
```
grep pgmajfault /proc/vmstat
```

**How to use it to optimize.** A spike in major faults means the working set doesn't fit in
available RAM — the same root cause as memory PSI, and it frequently precedes jank, stutter, or
ANRs. Reduce memory footprint and avoid churning large `mmap`ed files.

**Cost.** Negligible.

---

### Network Throughput

**What it means.** Receive/transmit rate in KB/s across all non-loopback interfaces.
*Example:* `RX 2500 KB/s` during video streaming, `RX 5 KB/s` at idle; a burst usually maps to
buffering or prefetch.

**How it's calculated.** The RX/TX byte columns from `/proc/net/dev`, summed across interfaces
and turned into a rate. Command: `cat /proc/net/dev`.

**How to use it to optimize.** Unexpected TX at idle = chatty analytics/telemetry. Bursty RX
that correlates with playback stalls = buffer-starvation. Overlay this with the STB playback
state to see whether network dips line up with rebuffering.

**Cost.** Negligible.

---

## Application & process metrics

*(These require pointing the tool at a package with `--package`; the heavier ones are opt-in.)*

### App PSS (memory breakdown)

**What it means.** **Proportional Set Size** — the RAM genuinely attributable to your app, with
shared pages (e.g. framework code) divided fairly among everyone using them. It's the fairest
single number for "how much memory does my app cost." *Example:* `Total PSS 180 MB`, of which
`graphics 60 MB` points at large bitmaps/textures.

The tool charts this two ways: a **Total PSS** chart, and an **App PSS breakdown** chart with
every App Summary category — *Java heap, native heap, code, stack, graphics, private other,
system* — mirroring exactly the `dumpsys meminfo` App Summary block (the same view the reference
`MemMonitor.py` plots). The breakdown lets you see *which* component is growing, not just that
total PSS rose.

**How it's calculated.** Parsed from `dumpsys meminfo <package>` (App Summary section, values in
KB → MB).

**How to use it to optimize.** Watch the **trend** — a monotonic climb that never drops is a
leak. In the breakdown: a large/growing *graphics* component → downsize/recycle bitmaps and
textures; large *native* → check JNI allocations and native buffers; growing *Java heap* →
object retention on the managed heap.

**Cost.** **Medium** — `dumpsys meminfo` is a binder service call, heavier than a `/proc` read.
Runs once per tick only when a package is set.

---

### App CPU — Process & Main Thread

**What it means.** Two numbers: **process CPU%** (all of the app's threads summed — can exceed
100% on multi-core) and **main-thread CPU%** (just the UI thread, 0–100%).
*Example:* `process 140%, main 85%` → the app is doing real parallel work *and* the UI thread
is nearly maxed, which will cause jank.

**How it's calculated.** `utime + stime` tick deltas from `/proc/<pid>/task/*/stat` (all threads,
whole process) and `/proc/<pid>/task/<pid>/stat` (main thread).
**Non-root fallback:** on unrooted devices where those per-thread stat files aren't readable, the
whole-process number falls back to parsing `dumpsys cpuinfo` for the package (summing the main and
any `:sub` processes) — coarser than the tick delta, but it works without root.

**How to use it to optimize.** High **main-thread** CPU is the actionable one: move parsing,
I/O, bitmap decoding, and layout-heavy work to background threads/coroutines so the UI thread
stays free to render frames on time.

**Cost.** Low (a couple of small `/proc` reads, or one `dumpsys cpuinfo` on the fallback path);
requires `--package`.

---

### UI Jank % & Frame Phases

**What it means.** **Jank %** = the share of frames that missed their deadline (>16.67 ms at
60 fps → a dropped/late frame the user can see). **Frame phases** break each frame's time into
*input, animation, measure/layout, draw* (ms), so you can see *where* the time went.
*Example:* `jank 3%` is smooth; `jank 30%` with `layout 9 ms` says expensive layout is blowing
the frame budget.

**How it's calculated.** From `dumpsys gfxinfo <package> framestats`: the janky/total frame
counters (delta per tick) and the per-phase durations.

**How to use it to optimize.** Aim to keep each phase under ~4 ms (so the total clears 16.7 ms).
High **layout** → flatten the view hierarchy / avoid nested weights. High **draw** → reduce
overdraw and complex clipping. High **input** → you're doing work in touch handlers on the UI
thread.

**Cost.** **Medium** — `dumpsys gfxinfo` call; only with `--jank` + `--package`.

---

### Media & HAL Process CPU

**What it means.** CPU% of the media/audio pipeline processes — `mediaserver`, `media.codec`,
`audioserver`, and vendor HAL processes. *Example:* `codec 45%` during playback. On an STB,
*high* codec-process CPU during video usually means the **software** decode path, not the
hardware/tunneled path.

**How it's calculated.** `pidof` each known media process → `utime + stime` deltas from
`/proc/<pid>/stat`.

**How to use it to optimize.** If codec CPU is high while a video plays, verify tunnel mode (see
below) — you likely fell off the hardware path. Also consider resolution/bitrate and whether
DRM/secure-surface constraints forced software decode.

**Cost.** Low–medium (a few `/proc` reads); with `--package`.

---

### Top-10 Process CPU (interactive)

**What it means.** The ten hottest processes on the *whole device* this tick, by CPU%. The
dashboard lets you tick any PID to add its trace to a chart. *Example:* discover a background
service or "noisy neighbor" eating 30% CPU and starving your app.

**How it's calculated.** Reads **every** process's `/proc/[pid]/stat` in one shell call and
computes `utime + stime` deltas, then sorts.

**How to use it to optimize.** Great for finding CPU thieves outside your own app — vendor
services, other apps, daemons — that steal cycles and cause your frames to land late.

**Cost.** **Medium** — it reads a stat file for *every* process each tick (still one shell
round-trip, but many files). Off unless enabled.

---

### Top Process Memory — RSS (interactive)

**What it means.** The top processes on the device by **RSS** (Resident Set Size, MB) this tick —
the "who's eating RAM" companion to Top-10 CPU. The dashboard lets you tick any process to chart
its memory over time. *Example:* spot a background app climbing to 400 MB, or confirm the
launcher's footprint. (RSS counts each shared page in full, so it slightly over-attributes shared
memory versus PSS — but it's the standard, cheap "top consumer" signal, the same value `top` shows.)

**How it's calculated.** RSS is read from **`/proc/[pid]/stat` field 24** (resident pages) via a
single `cat /proc/[0-9]*/stat` — the exact same world-readable, always-terminating shell read
used by Top-CPU. No root, no `dumpsys meminfo`, no `procrank`: those are either heavy or can hang
(a wedged `su`/`procrank` keeps stdout open and blocks), so they're deliberately not used here.

> For the true **PSS** *breakdown* of a single app (Java/native/graphics/…), use the *App PSS*
> metric, which reads `dumpsys meminfo <package>` for your one package (not the whole system).

**How to use it to optimize.** Find memory hogs beyond your own app (vendor services, other apps)
that shrink available RAM and push the device toward LMK kills. Correlate a rising process with
the *Memory Zone Watermarks* and *Memory* charts.

**Cost.** **Low–Medium** — one `cat /proc/[0-9]*/stat` per tick (many files, one round-trip), run
on a background thread so it never delays the sampling loop. Off unless enabled.

---

## Set-top-box (STB) playback metrics

*(Derived by parsing the captured logcat — no extra adb calls, so effectively free.)*

### Video Player State

**What it means.** The raw player lifecycle, event by event — e.g. `onSourceLoad → onSourceLoaded
→ onPlay → onStallStarted → onRenderFirstFrame → onReady → onPlaying`. It's a live timeline of
exactly what the player is doing.

**How it's calculated.** The tool scans `mk-player-lifecycle` lines in logcat (MediaKind
player) and surfaces the raw event as the current state.

**How to use it to optimize.** Measure **startup latency** (`onSourceLoad` → `onRenderFirstFrame`)
and spot **rebuffering** (`onStallStarted` without a quick `onStallEnded`). Correlate stalls
with the network and CPU charts to find the cause.

**Cost.** Negligible (reads the tail of the already-captured logcat file).

---

### Tunnel Mode (build flag vs. actual)

**What it means.** Whether video used the **tunneled hardware path**, where decoded frames go
straight to the display without round-tripping through the app — lower CPU, lower power,
smoother playback. The tool separates two things people often conflate:
- **Build flag** — `isTunneledPlaybackEnabled` from the player config: did the app *ask* for
  tunneling?
- **Actual** — the `MediaCodec/CCodec: Configuring TUNNELED / NON-TUNNELED video playback`
  line: did the hardware actually *grant* it?

*Example:* build `true` + actual `tunneled` → working as intended. Build `true` + actual
`non_tunneled` → it silently fell back to the software path — investigate.

**How it's calculated.** Regex over logcat for the MediaCodec configuration lines and the
player-config flag.

**How to use it to optimize.** If you're not actually tunneling, that explains high codec CPU,
extra power draw, and micro-stutter. Check codec selection, DRM/secure-surface setup, and the
output surface. Verifying this used to require grepping logcat by hand; here it's a live badge,
a timeline, and a line in the PDF report.

**Cost.** Negligible (logcat parse).

---

## Quick reference

| Metric | Source | Command (per tick) | Cost |
|--------|--------|--------------------|------|
| CPU utilization & idle % | `/proc/stat` (aggregate) | `cat /proc/stat` | Negligible |
| Per-core CPU % | `/proc/stat` (cpuN) | `cat /proc/stat` | Negligible |
| Memory MB & used % | `/proc/meminfo` | `cat /proc/meminfo` | Negligible |
| PSI cpu/mem/io (avg10) | `/proc/pressure/*` | `cat /proc/pressure/{cpu,memory,io}` | Negligible |
| Load average (1m) | `/proc/loadavg` | `cat /proc/loadavg` | Negligible |
| Swap used | `/proc/meminfo` | `cat /proc/meminfo` | Negligible |
| Memory zone watermarks (free vs min/low/high) | `/proc/zoneinfo` | `cat /proc/zoneinfo` | Negligible |
| Direct reclaim (events/s) | logcat | (parses captured logcat) | Negligible |
| CPU temperature | `/sys/class/thermal` | `cat thermal_zone*/temp` | Negligible |
| CPU frequency | `/sys/.../cpufreq` | `cat cpu*/cpufreq/scaling_cur_freq` | Negligible |
| Major page faults | `/proc/vmstat` | `grep pgmajfault /proc/vmstat` | Negligible |
| Network throughput | `/proc/net/dev` | `cat /proc/net/dev` | Negligible |
| App PSS (total + category breakdown) | `dumpsys` | `dumpsys meminfo <pkg>` | Medium |
| App / main-thread CPU (dumpsys fallback) | `/proc/<pid>/task` | `cat /proc/<pid>/task/*/stat` or `dumpsys cpuinfo` | Low |
| UI jank & frame phases | `dumpsys` | `dumpsys gfxinfo <pkg> framestats` | Medium |
| Media/HAL process CPU | `/proc/<pid>/stat` | `pidof` + `cat /proc/<pid>/stat` | Low–Med |
| Top-10 process CPU | `/proc/[pid]/stat` (all) | `cat /proc/[0-9]*/stat` | Medium |
| Top process memory (RSS) | `/proc/[pid]/stat` field 24 | `cat /proc/[0-9]*/stat` | Low–Medium |
| Player state | logcat | (parses captured logcat) | Negligible |
| Tunnel mode | logcat | (parses captured logcat) | Negligible |

**Overall:** the entire system-metrics block is a single `adb shell` round-trip reading small
virtual files, so it's safe to run even on constrained STB hardware at 1–2 second intervals.
The only heavier collectors — App PSS, UI jank, and Top-10 process CPU — are opt-in, so you pay
for them only when you ask for them.
