# Web Dashboard

The live UI ("ADB Performance Studio"). Served by `WebApp` (`presentation/web/WebServer.kt`); the
pages are Kotlin raw-string constants (`HomePage.kt`, `DashboardPage.kt`, `ComparePage.kt`).

## Pages & endpoints

| Route | Serves |
|-------|--------|
| `/` | Home: device picker + run configuration (`HOME_PAGE`) |
| `/dashboard` | Live charts (`DASHBOARD_PAGE`) |
| `/compare` | Two-run comparison (`COMPARE_PAGE`) |
| `/api/devices` | connected devices + busy state |
| `/api/start` (POST) | start a run from the configured form |
| `/api/stop` (POST) | request stop (aborts in-flight adb, returns fast) |
| `/api/session?since=<n>` | incremental run state + new samples as JSON |
| `/api/report` | the run's PDF |
| `/api/runs`, `/api/runcsv?folder=` | list past runs / fetch a run's CSV (for compare) |
| `/api/snapshot*`, `/api/procrank-snapshot*` | one-shot snapshots — see [device-and-procrank-snapshot.md](device-and-procrank-snapshot.md) |

## Polling protocol (how the live view works)

The dashboard calls `GET /api/session?since=idx` on a timer. The server returns run status, the device
line, feature flags (`hasPsi`, `hasTopMem`, `hasTopProcs`, `hasPlayback`…), the current `build`, and
**only samples with index ≥ `since`**; the client advances `idx = nextIndex`. Each sample is the JSON
serialization of a `Sample` (`WebApp.sessionJson()`).

On the first response the client captures the feature flags and shows/hides each chart accordingly.
Standard scalar metrics are defined declaratively in a `DEFS` array and rendered generically; the two
interactive per-process charts (Top CPU, Top Memory) are special sections.

### Resilience built into the client
- **Independent rendering:** each metric/section renders inside its own `try/catch`, so one failing
  chart can never freeze the others or the poll loop. (This was a real bug: an unguarded error in the
  shared render path used to blank every chart.)
- **No stale JS:** pages are served `Cache-Control: no-store`; the dashboard embeds its build tag and,
  if the server reports a newer build, reloads itself once. The build badge (bottom-right) shows the
  live JS build so you can confirm you're not on a cached page.
- **Canvas ids are unique:** the interactive Top-Memory chart uses `c-topmem` (not `c-mem`, which is
  the built-in "Memory (MB)" chart) — a shared id previously caused Chart.js "canvas already in use".

## Interactive top-process charts

The Top Process CPU/Memory sections list processes with their peak value and a checkbox; the top few
are auto-selected. Checking a process adds its time series to the chart. Data comes from the
`procs[]` / `memprocs[]` arrays on each sample (see [metrics.md](metrics.md)).

## Health badges

A rolling window (~last 30s) is summarized into colored badges — CPU, Memory (% free + PSI), Mem
zones (vs watermarks), etc. — giving an at-a-glance "is this healthy" verdict without reading the
charts.

## Stop / report flow

Pressing **Stop** shows a "Stopping…" state, POSTs `/api/stop`, and the server aborts any in-flight
adb command so the run finalizes in ~1–2s. When status becomes `done` the button is replaced by a
**Download PDF report** link. The client updates the button state before rendering data, so it always
flips out of "Stopping…" even if a later render call errors.

## Dev use

- Keep the dashboard open during a repro to watch PSI/memory in real time and catch the moment a
  metric turns — then open the report for the full picture.
- Use `--label` on two runs (e.g. `tunnel-off`, `tunnel-on`) and the `/compare` view to A/B them.
- If a chart looks wrong, open the browser console: the guarded renderers log precise messages
  (`memChart update failed`, `initMemChart failed`, …) instead of failing silently.
