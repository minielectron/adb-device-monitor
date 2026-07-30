# Device Snapshot & Procrank Snapshot

Two **one-shot** captures (buttons on the Home page) that produce a self-contained interactive HTML
report about the device's current memory state. Implemented in
`presentation/report/Capabilities.kt`; served by `WebApp` via async start + progress-poll + inline
HTML endpoints.

Both render **server-side** (SVG charts + static HTML tables). There is intentionally **no Chart.js,
no CDN, and no embedded JS data array**, because those failed silently on locale/escaping edge cases.
If the capture has rows, the graph is there.

## Device Snapshot  (`📸 Device Snapshot`)

- **What:** a snapshot of the STB's capabilities and memory footprint — hardware/OS summary, a
  memory-footprint doughnut (PSS by category: Android / 3rd-party / Launcher vs MemFree / MemAvailable),
  and a full process table.
- **How:** `Capabilities.capture(adb, target)` runs a few `adb shell` reads (getprop summary,
  `/proc/meminfo`, launcher detection, per-process PSS) and `writeHtml()` renders the HTML.
- **Endpoints:** `POST /api/snapshot` (async start) → `GET /api/snapshot-status` (progress) →
  `GET /api/snapshot-report` (serves the HTML inline; also saved as `device-snapshot_<ts>.html`).
- **Read:** the doughnut shows where RAM is going; "Total RAM" is drawn at the doughnut's true center.
- **Dev use:** a fast baseline of a device you don't know — what launcher/apps dominate memory before
  you even start a run.

## Procrank Snapshot  (`📊 Procrank Snapshot`)

- **What:** per-process **VSS/RSS/PSS/USS** from `procrank` — the accurate proportional-memory view —
  as a top-15 bar chart plus a sortable/searchable full table.
- **How:** `captureProcrank(adb, target)` tries `procrank`, then `su 0 procrank` / `su -c procrank`,
  and accepts the result only if it yields real data rows (a non-rooted device often prints just the
  header). If none work it falls back to `ps` (RSS/VSS; PSS/USS blank). A debug sidecar
  `procrank-snapshot_<ts>.debug.txt` records exactly what each command returned.
- **Endpoints:** `POST /api/procrank-snapshot` → `GET /api/procrank-status` → `GET /api/procrank-report`.
- **Read:** PSS is the fair per-process number (shared pages counted proportionally); USS is
  private-only (what you'd reclaim by killing it); RSS over-counts shared; VSS is address space (mostly
  noise). The report header states the source (`procrank` vs `ps` fallback) and process count.
- **Dev use:** the definitive "who is really using memory" view for a repro. Because it's one-shot and
  bounded by the hardened adb timeout, it's safe even though procrank is heavy — unlike the live Top
  Process Memory chart, which uses cheap RSS via `ps` every tick.

## Why one-shot vs live differ

`procrank` walks every process's pagemap to compute PSS: accurate but slow and root-gated. That's fine
for a deliberate one-shot snapshot, but wrong for a per-tick live chart (it would stall sampling). So:

| | Live chart | Procrank Snapshot |
|-|------------|-------------------|
| Command | `ps -A -o PID,RSS,NAME` | `procrank` (su fallbacks) → `ps` fallback |
| Metric | RSS | PSS/USS/RSS/VSS |
| Cost | cheap, no root | heavy, usually needs root |
| Cadence | every tick | on demand |
