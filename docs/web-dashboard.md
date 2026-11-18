# Web Dashboard

The local UI ("ADB Performance Studio"). `WebApp` in `presentation/web/WebServer.kt` serves Kotlin
page templates to a normal browser or the native app's WebView. Runtime assets are bundled; the HTTP
origin is local and no internet service is required.

## Pages & endpoints

| Route | Serves |
|-------|--------|
| `/` | Home: device picker, run configuration and Quick Diagnostics (`HOME_PAGE`) |
| `/dashboard` | Live charts (`DASHBOARD_PAGE`) |
| `/compare` | Two-run comparison (`COMPARE_PAGE`) |
| `/player-debugger` | Offline Player Debugger cockpit, graph, recorder and incidents |
| `/release-guard` | Offline Release Guard project/scenario workspace and results |
| `/api/devices` | connected devices + busy state |
| `/api/defaults`, `/api/pick-folder` | resolved reports folder / native folder picker |
| `/api/start` (POST) | start a run from the configured form |
| `/api/stop` (POST) | request stop (aborts in-flight adb, returns fast) |
| `/api/session?since=<n>` | incremental run state + new samples as JSON |
| `/api/report` | the run's PDF |
| `/api/runs`, `/api/runcsv?folder=` | list past runs / fetch a run's CSV (for compare) |
| `/api/snapshot*`, `/api/procrank-snapshot*` | one-shot snapshots — see [device-and-procrank-snapshot.md](device-and-procrank-snapshot.md) |
| `/api/layout-inspector*` | hierarchy/screenshot capture, status and HTML — see [layout-inspector.md](layout-inspector.md) |
| `/api/player/*` | capability probe, capture, graph/timeline/incidents, report, bundles, import and re-analysis |
| `/api/release/*` | projects/scenarios, capability probe, execution, result, baseline and artifact access |
| `/api/diagnostics` | application/JVM support ZIP — see [diagnostics.md](diagnostics.md) |

## Home defaults and draft persistence

The initial Home metric set is CPU utilization, per-core CPU, top-process CPU, memory used, PSI and
top-process memory. Logcat capture is also initially enabled. The page saves its form fields,
checkboxes and open/closed panel state in local browser storage, so moving to Player Debugger,
Release Guard, Compare or Home does not erase unsaved run configuration. A saved draft takes
precedence over first-use defaults.

Player Debugger keeps a separate saved draft for target, package, duration, interval and its six
visible signal checkboxes.

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
