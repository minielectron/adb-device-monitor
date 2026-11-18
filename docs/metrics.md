# Metrics Reference

Every metric is a field on the immutable `Sample` (`domain/MetricModels.kt`), produced once per tick
by `MetricsCollector` (`data/metrics/`). Most core metrics come from **one batched `adb shell`**
(`Snapshot.buildCommand()`), which concatenates several `/proc` and `/sys` reads separated by
`===MARKER===` lines and is parsed by `Snapshot`. Optional/expensive metrics use extra reads, some on
a background thread.

For each metric below: **source** (how it's collected), **read** (how to interpret the number), and
**dev use** (what it tells you).

## Core (always sampled)

### System CPU utilization — `cpu` (%)
- **Source:** `/proc/stat` aggregate `cpu` line; utilization = busy-ticks Δ / total-ticks Δ between
  consecutive ticks. Per-core from `cpuN` lines.
- **Read:** 0–100%. Idle % is `100 - cpu`.
- **Dev use:** sustained high system CPU with low app CPU ⇒ work is in system/media/HAL processes
  (common on STBs during tunneled playback). Cross-check with Top Process CPU.

### Memory — `memUsed`, `memAvail` (MB), `memUsedPct` (%)
- **Source:** `/proc/meminfo` (`MemTotal`, `MemFree`, `MemAvailable`, `Cached`). Used = Total − Available.
- **Read:** `memAvail` is the honest "how much can I still allocate" number (accounts for reclaimable
  cache); prefer it over `MemFree`.
- **Dev use:** a steadily falling `memAvail` across a run is a leak signal; correlate with App PSS and
  Top Process Memory.

### Swap — `swap` (MB)
- **Source:** `/proc/meminfo` `SwapTotal − SwapFree`. Null when the device has no swap/zram.
- **Dev use:** rising swap under memory pressure means the kernel is compressing/evicting pages —
  precedes jank and LMK kills.

### CPU / Memory / IO Pressure (PSI) — `psiCpu`, `psiMem`, `psiIo` (% stalled, avg10)
- **Source:** `/proc/pressure/{cpu,memory,io}` `some avg10`. Falls back to per-file mode if the
  combined read is unavailable; skipped entirely if the kernel lacks `CONFIG_PSI`.
- **Read:** % of the last 10s that *some* task was stalled waiting on that resource. 0 = healthy;
  double digits = real contention.
- **Dev use:** the single best "is the device actually struggling" signal. Memory PSI > ~10 tends to
  precede LMK kills; IO PSI spikes explain stutters that CPU% alone doesn't.

### Load average — `load1`
- **Source:** `/proc/loadavg` (1-minute). **Dev use:** > core-count means the run queue is backed up.

### Major page faults / s — `faults`
- **Source:** `/proc/vmstat` `pgmajfault` delta rate. **Dev use:** high rate = thrashing (pages fetched
  from disk/zram), a memory-pressure symptom.

### Memory zones — `zoneActive`, `zoneFree`, `zoneMin`/`zoneLow`/`zoneHigh` (pages)
- **Source:** `/proc/zoneinfo` — active zones, free pages, and the three kernel watermarks.
- **Read:** free below `low` ⇒ `kswapd` reclaiming; free below `min` ⇒ **direct reclaim** (allocations
  block). **Dev use:** watching free pages cross watermarks explains reclaim-induced stalls.

### Thermal — `temp` (°C) & CPU frequency — `freq` (MHz)
- **Source:** hottest `/sys/class/thermal/thermal_zone*/temp`; mean online-core
  `scaling_cur_freq`. **Dev use:** frequency dropping while temp climbs = thermal throttling; explains
  performance that degrades only after the device warms up.

### Network — `rx`, `tx` (KB/s)
- **Source:** `/proc/net/dev` byte deltas across all non-loopback interfaces. **Dev use:** correlate
  buffering/playback stalls with throughput dips.

## Optional (opt-in per run)

### App PSS — `pss` + breakdown (`pssJava`, `pssNative`, `pssCode`, `pssStack`, `pssGfx`, `pssPriv`, `pssSys`) (MB)
- **Source:** `dumpsys meminfo <pkg>` (requires `--package`). PSS = proportional set size (shared pages
  counted proportionally) — the fairest single memory number for an app.
- **Dev use:** the breakdown localizes growth: Java heap ⇒ object leak; Graphics ⇒ surfaces/bitmaps;
  Native ⇒ NDK/codec. Compare tunnel-on vs tunnel-off runs in the comparison view.

### App CPU — `appCpu` (all threads) & `mainCpu` (main thread) (%)
- **Source:** per-process `/proc/<pid>/task/*/stat` deltas; non-root fallback to `dumpsys cpuinfo`.
- **Dev use:** `mainCpu` near 100% ⇒ main-thread jank risk even if total device CPU looks fine.

### UI Jank — `jank` (% janky frames) + Frame phases (`fIn`, `fAnim`, `fLay`, `fDraw` ms)
- **Source:** `dumpsys gfxinfo <pkg> framestats` deltas (requires `--package --jank`).
- **Read:** % of frames over the 16.6ms budget; phase ms shows *where* the time went (input/animation/
  layout/draw). **Dev use:** pinpoints the pipeline stage causing dropped frames.

### Top Process CPU — `procs[]` ({pid, n, pct})
- **Source:** `cat /proc/[0-9]*/stat` for all pids; per-process busy-tick deltas → top consumers.
- **Dev use:** attributes system CPU to specific processes live; the dashboard chart is interactive.

### Top Process Memory — `memprocs[]` ({pid, n, mb})  *(RSS)*
- **Source:** a single `ps -A -o PID,RSS,NAME` (header-aware parser handles device `ps` variants);
  RSS in KiB → MB. Runs on the background collector with adaptive back-off. **procrank is deliberately
  not used here** (it walks pagemap, is heavy, and needs root — see the Procrank *Snapshot* feature for
  a one-shot PSS view).
- **Read:** RSS over-counts shared pages, but it's a cheap, reliable ranking of the biggest processes.
- **Dev use:** spot which process is growing during a repro without root or per-tick overhead.

### Direct reclaim / s — `reclaim`
- **Source:** counted from logcat (`--playback`/logcat enabled). **Dev use:** confirms the kernel hit
  the `min` watermark and allocations are blocking — a hard memory-pressure signal.

## Logcat anomalies & events — `Anomaly` (`domain/Anomaly.kt`)
- **Source:** `AnomalyScanner` scans the growing `logcat.log` incrementally (byte-offset) for LMK
  kills, ANRs, "Skipped frames"/Davey jank, direct reclaim, etc.; `EventInfo` extracts app metadata
  (version, screen, account) from analytics blocks.
- **Read:** each anomaly has device time, elapsed seconds, type, and a detail string; shown on the
  dashboard timeline and in the report. **Dev use:** ties a metric spike to a concrete kernel/app event.

## STB playback — `video`, `tunnel`  (`--playback`)
- **Source:** `Playback.Tracker` replays the logcat tail into a player state machine (onSourceLoad →
  onReady → onPlaying …) and reads the build's tunnel-mode flag.
- **Dev use:** overlay player-state transitions on the metric charts to see how tunneled vs
  non-tunneled playback shifts load between the app and media/HAL processes.

## Player Debugger evidence

Player Debugger records normalized events in addition to numeric `Sample` values. Evidence is always
labelled by origin:

| Label | Meaning | Typical inputs |
|---|---|---|
| **Reported** (`APP_REPORTED`) | The app explicitly emitted a valid OPTD event | session/player lifecycle, buffer, first frame, A/V availability, quality, errors, handoffs |
| **Measured** (`DEVICE_MEASURED`) | The tool observed the Android device directly | CPU/memory/PSI/process samples and bounded media snapshots |
| **Log parsed** (`LOG_PARSED`) | A known platform/log pattern was recognized | ANR, Java/native crash, codec, DRM and audio failures, passive player state |
| **Inferred** (`ENGINE_INFERRED`) | The engine reconstructed a relationship from other evidence | synthetic session/player boundaries, missing endpoints, correlated incidents |
| **Unavailable** | The device/app did not expose the signal | displayed as unavailable, never silently converted to healthy |

Every event includes a monotonic timestamp, source and confidence. Structured OPTD telemetry has the
best identity and handoff fidelity; passive evidence remains useful but may lack exact player
instances. Incident rules link back to event sequence numbers and registered artifacts so a finding
can be audited rather than treated as an opaque score.

The Player Debugger capture form enables a balanced first-use set by default: CPU utilization,
per-core CPU, top-process CPU, memory used, PSI pressure stalls, and top-process memory. Logcat is
required. Changes are saved in local browser storage and restored after navigation or application
restart; returning Home does not reset the draft. Unreadable OEM signals remain `n/a`.

## CSV / programmatic reading

Every field above is a column in `samples.csv` (see [reports-and-comparison.md](reports-and-comparison.md)),
and the same object is served by the dashboard `/api/session`. To analyze a run in code, read the CSV —
no need to scrape the UI.
