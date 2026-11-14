package devicemonitor.presentation.web

internal val DASHBOARD_PAGE = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>ADB Performance Studio — live</title>
<script src="/assets/chart.umd.js"></script>
<style>
  .run-brand { flex: none; }
  .run-context { min-width: 180px; flex: 1; }
  #status { margin-bottom: 5px; }
  #status:before { content: ""; display: inline-block; width: 7px; height: 7px; margin-right: 7px; border-radius: 50%; background: currentColor; box-shadow: 0 0 0 5px color-mix(in srgb, currentColor 10%, transparent); }
  #device { overflow: hidden; color: var(--quiet); font: 10px/1.35 ui-monospace, SFMono-Regular, Menlo, monospace; text-overflow: ellipsis; white-space: nowrap; }
  .running { color: var(--green); } .finished { color: var(--gold); } .failed { color: var(--red); } .stopping { color: var(--amber); }
  #stopBtn.busy { opacity: .58; cursor: default; }
  .spinner { display: inline-block; width: 11px; height: 11px; margin-right: 4px; border: 2px solid rgba(255,255,255,.45); border-top-color: #fff; border-radius: 50%; animation: spin .7s linear infinite; vertical-align: -1px; }
  @keyframes spin { to { transform: rotate(360deg); } }
  #reportBtn { display: none; }
  #buildStamp { color: var(--quiet); font: 9px/1.3 ui-monospace, SFMono-Regular, Menlo, monospace; }
  .dashboard-shell { padding-top: 30px; }
  .run-lead { display: flex; align-items: end; justify-content: space-between; gap: 22px; margin-bottom: 22px; }
  .run-lead h1 { margin: 0; font-size: clamp(30px, 4vw, 48px); line-height: 1; letter-spacing: -.045em; }
  .run-lead-copy { max-width: 620px; margin: 10px 0 0; color: var(--muted); font-size: 12px; line-height: 1.6; }
  #activity-banner { display: none; margin-bottom: 14px; padding: 13px 16px; border: 1px solid rgba(158,140,255,.24); border-radius: var(--radius-md); color: #d8d1ff; background: linear-gradient(100deg, rgba(158,140,255,.12), rgba(158,140,255,.035)); font-size: 12px; }
  #activity-cur { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
  .health-panel { margin-bottom: 28px; padding: 18px; }
  #badges { display: grid; grid-template-columns: repeat(auto-fit, minmax(145px, 1fr)); gap: 9px; }
  .hb { min-width: 0; padding: 12px 13px; border: 1px solid var(--line); border-radius: 12px; background: rgba(255,255,255,.025); }
  .hb .k { color: var(--quiet); font-size: 9px; font-weight: 800; letter-spacing: .1em; text-transform: uppercase; }
  .hb .v { margin-top: 5px; color: var(--text); font-size: 13px; font-weight: 750; }
  .hb.ok .v { color: var(--green); } .hb.warn .v { color: var(--amber); } .hb.bad .v { color: var(--red); }
  .metric-catalog { display: grid; gap: 30px; }
  .metric-group { min-width: 0; }
  .metric-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
  .card { min-width: 0; padding: 15px 16px; border: 1px solid var(--line); border-radius: var(--radius-md); background: linear-gradient(145deg, rgba(27,27,25,.9), rgba(15,15,14,.88)); box-shadow: inset 0 1px rgba(255,255,255,.025), 0 15px 44px rgba(0,0,0,.2); transition: border-color .2s, transform .2s; }
  .card:hover { border-color: var(--line-strong); transform: translateY(-2px); }
  .card h2 { margin: 0 0 8px; color: var(--text); font-size: 12px; letter-spacing: -.01em; }
  .card .chartbox { position: relative; height: 210px; }
  .chart-stats { display: flex; flex-wrap: wrap; gap: 2px 0; margin-top: 8px; padding-top: 7px; border-top: 1px solid var(--line); }
  .srow { display: flex; align-items: baseline; gap: 10px; width: 100%; color: var(--muted); font-size: 10px; line-height: 1.6; }
  .sname { min-width: 70px; max-width: 110px; overflow: hidden; color: var(--quiet); font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
  .sval { font-family: ui-monospace, Menlo, monospace; font-size: 11px; }
  .sval .sl { margin-right: 2px; color: var(--quiet); font-size: 8px; text-transform: uppercase; }
  .analysis-section { display: none; margin-top: 30px; padding: 22px; }
  .analysis-layout { display: grid; grid-template-columns: 240px minmax(0, 1fr); gap: 14px; }
  .rank-panel { max-height: 280px; overflow-y: auto; padding: 14px; border: 1px solid var(--line); border-radius: 13px; background: rgba(255,255,255,.022); }
  .rank-label { margin-bottom: 8px; color: var(--quiet); font-size: 9px; font-weight: 800; letter-spacing: .1em; text-transform: uppercase; }
  .rank-panel label { color: var(--muted) !important; }
  .analysis-chart { padding: 14px 15px; }
  .analysis-chart .chartbox { height: 240px; }
  #playback-section { border-color: rgba(158,140,255,.18); background: linear-gradient(145deg, rgba(31,27,45,.88), rgba(15,14,20,.92)); }
  .media-notice { display: none; margin-bottom: 9px; padding: 11px 13px; border-radius: 11px; font-size: 11px; font-weight: 700; }
  #pb-badges { display: flex; gap: 9px; margin-bottom: 12px; flex-wrap: wrap; }
  .playback-grid { display: grid; gap: 14px; }
  .events-panel { margin-top: 30px; padding: 22px; }
  .event-scroll { max-height: 380px; overflow: auto; border: 1px solid var(--line); border-radius: 13px; }
  #events table { width: 100%; border-collapse: collapse; color: var(--muted); font-size: 11px; background: rgba(255,255,255,.018); }
  #events th, #events td { padding: 10px 12px; border-bottom: 1px solid var(--line); text-align: left; }
  #events td:last-child { white-space: normal; word-break: break-word; overflow-wrap: anywhere; color: var(--muted); font: 10px/1.5 ui-monospace, Menlo, monospace; }
  #events th { position: sticky; top: 0; z-index: 1; color: var(--quiet); background: #151514; font-size: 9px; letter-spacing: .1em; text-transform: uppercase; }
  .tag { display: inline-flex; padding: 3px 7px; border-radius: 999px; font-size: 9px; font-weight: 800; letter-spacing: .04em; color: #fff; }
  .tag.anom { background: #a65d28; } .tag.zap { background: #923e43; } .tag.act { background: #6254a8; }
  @media (max-width: 900px) { .metric-grid { grid-template-columns: 1fr; } .analysis-layout { grid-template-columns: 1fr; } .rank-panel { max-height: 210px; } }
  @media (max-width: 720px) { .studio-appbar { flex-wrap: wrap; } .run-brand { min-width: 0; flex: 1 1 180px; } .run-context { min-width: 0; order: 3; flex: 1 0 100%; } .run-lead { display: block; } .run-lead .studio-status { margin-top: 16px; } .dashboard-shell { padding-top: 24px; } .health-panel, .analysis-section, .events-panel { padding: 16px; } .card .chartbox { height: 190px; } }
</style>
</head>
<body>
<div class="studio-ambient" aria-hidden="true"><span class="studio-orb gold"></span><span class="studio-orb violet"></span><span class="studio-orb teal"></span></div>
<header class="studio-appbar">
  <div class="studio-brand run-brand"><div class="studio-brand-mark">APS</div><div class="studio-brand-copy"><div class="studio-brand-name">Live telemetry</div><div class="studio-brand-meta" id="buildStamp" title="If this build time is older than your running jar, your browser is showing a cached page — hard-refresh with Cmd+Shift+R.">__BUILD__</div></div></div>
  <div class="run-context"><div id="status" class="running">live capture</div><div id="device">connecting&hellip;</div></div>
  <nav class="studio-actions" aria-label="Run actions">
    <a class="studio-link" href="/">&#8592; Home</a>
    <a class="studio-link" href="/api/diagnostics" title="Download logs and a thread dump">Diagnostics</a>
    <button class="studio-button danger" id="stopBtn" onclick="stopRun()">&#9632; Stop run</button>
    <a class="studio-button success" id="reportBtn" href="/api/report">&#8681; Download report</a>
  </nav>
</header>
<main class="studio-shell dashboard-shell">
  <section class="run-lead studio-enter"><div><p class="studio-eyebrow">Real-time performance</p><h1>Device under observation.</h1><p class="run-lead-copy">Live system, application, rendering, and media signals—organized for fast diagnosis while the run is active.</p></div><span class="studio-status ok">Streaming telemetry</span></section>
  <div id="activity-banner" role="status"><strong>Current activity</strong>&nbsp; <span id="activity-cur"></span></div>
  <section class="health-panel studio-panel studio-enter" aria-labelledby="health-title"><div class="studio-section-head"><div><h2 class="studio-section-title" id="health-title">Health at a glance</h2><p class="studio-section-copy">Recent signals from the active sampling window.</p></div></div><div id="badges"></div></section>

  <div id="grid" class="metric-catalog">
    <section class="metric-group" id="group-system"><div class="studio-section-head"><div><p class="studio-eyebrow">01 · Device</p><h2 class="studio-section-title">System</h2><p class="studio-section-copy">Compute, thermal, load, and network behavior.</p></div></div><div class="metric-grid" id="grid-system"></div></section>
    <section class="metric-group" id="group-memory"><div class="studio-section-head"><div><p class="studio-eyebrow">02 · Pressure</p><h2 class="studio-section-title">Memory</h2><p class="studio-section-copy">Capacity, reclaim, pressure, faults, and zone watermarks.</p></div></div><div class="metric-grid" id="grid-memory"></div></section>
    <section class="metric-group" id="group-application"><div class="studio-section-head"><div><p class="studio-eyebrow">03 · Workload</p><h2 class="studio-section-title">Application</h2><p class="studio-section-copy">Process CPU, memory footprint, and main-thread cost.</p></div></div><div class="metric-grid" id="grid-application"></div></section>
    <section class="metric-group" id="group-rendering"><div class="studio-section-head"><div><p class="studio-eyebrow">04 · Experience</p><h2 class="studio-section-title">Rendering</h2><p class="studio-section-copy">Frame health and UI smoothness.</p></div></div><div class="metric-grid" id="grid-rendering"></div></section>
    <section class="metric-group" id="group-media"><div class="studio-section-head"><div><p class="studio-eyebrow">05 · Playback</p><h2 class="studio-section-title">Media</h2><p class="studio-section-copy">Audio and video process demand.</p></div></div><div class="metric-grid" id="grid-media"></div></section>
  </div>

  <section id="proc-section" class="analysis-section studio-panel" aria-labelledby="proc-title">
    <div class="studio-section-head"><div><p class="studio-eyebrow">Application analysis</p><h2 class="studio-section-title" id="proc-title">Top Process CPU</h2><p class="studio-section-copy">Select processes to add or remove them from the chart.</p></div></div>
    <div class="analysis-layout">
      <div class="rank-panel"><div class="rank-label">Peak CPU %</div><div id="proc-list"><span class="studio-section-copy">Waiting for data&hellip;</span></div></div>
      <div class="card analysis-chart"><div class="chartbox"><canvas id="c-procs"></canvas></div>
      <div id="stats-procs" class="chart-stats"></div>
    </div>
  </div>
  </section>
  <section id="mem-section" class="analysis-section studio-panel" aria-labelledby="memory-analysis-title">
    <div class="studio-section-head"><div><p class="studio-eyebrow">Application analysis</p><h2 class="studio-section-title" id="memory-analysis-title">Top Process Memory</h2><p class="studio-section-copy" id="mem-src">Select processes to add or remove them from the chart.</p></div></div>
    <div class="analysis-layout">
      <div class="rank-panel"><div class="rank-label" id="mem-peak-label">Peak (MB)</div><div id="mem-list"><span class="studio-section-copy">Waiting for data&hellip;</span></div></div>
      <div class="card analysis-chart"><div class="chartbox"><canvas id="c-topmem"></canvas></div>
      <div id="stats-mem" class="chart-stats"></div>
    </div>
  </div>
  </section>
  <section id="playback-section" class="analysis-section studio-panel" aria-labelledby="playback-title">
    <div class="studio-section-head"><div><p class="studio-eyebrow">Media analysis</p><h2 class="studio-section-title" id="playback-title">Playback &amp; tunnel mode</h2><p class="studio-section-copy">Video lifecycle and TUNNELED / NON-TUNNELED state from logcat.</p></div></div>
    <div id="pb-buildbanner" class="media-notice"></div>
    <div id="pb-lifecycleflag" class="media-notice"></div>
    <div id="pb-badges"></div>
    <div class="playback-grid">
      <div class="card"><h2>Video player state <span class="studio-section-copy">&nbsp;&middot;&nbsp;raw mk-player-lifecycle events</span></h2><div class="chartbox" style="height:320px"><canvas id="c-video"></canvas></div>
    </div>
    <div class="card"><h2>Tunnel mode</h2>
      <div class="chartbox" style="height:170px"><canvas id="c-tunnel"></canvas></div>
    </div>
  </div>
  </section>
  <section id="events" class="events-panel studio-panel" aria-labelledby="events-title"><div class="studio-section-head"><div><p class="studio-eyebrow">Run narrative</p><h2 class="studio-section-title" id="events-title">Events</h2><p class="studio-section-copy">Channel changes, anomalies, and foreground activity in chronological order.</p></div></div><div class="event-scroll"><table><thead><tr><th style="width:70px">Time</th><th style="width:140px">Kind</th><th>Detail</th></tr></thead><tbody id="evbody"></tbody></table></div></section>
</main>
<script>
Chart.defaults.color = '#aaa69e';
Chart.defaults.borderColor = 'rgba(255,255,255,.08)';
Chart.defaults.font.family = 'Inter, -apple-system, BlinkMacSystemFont, Segoe UI, sans-serif';
// baseline: { value, label } — green dashed Android standard/idle reference line.
//   PSI  = 0   (any positive value = resource stall)
//   Jank = 0%  (zero janky frames = smooth playback)
//   Phases = 4ms each (4ms * 4 = 16ms = 60fps budget)
// Each chart maps 1:1 to a metric toggle via `flag` (a hasX key in the /api/session response).
// A card is shown only when its flag is not false.
var DEFS = [
  { id: 'cpu',     title: 'CPU Utilization (%)',               series: [['CPU %', 'cpu']], max: 100, flag: 'hasCpu' },
  { id: 'cpuidle', title: 'CPU Idle (%)',                      series: [['idle %', 'cpuIdle']], max: 100, flag: 'hasCpuIdle' },
  { id: 'mem',     title: 'Memory (MB)',                       series: [['Used', 'memUsed'], ['Available', 'memAvail']], flag: 'hasMem' },
  { id: 'mempct',  title: 'Memory Used (%)',                   series: [['used %', 'memUsedPct']], max: 100, flag: 'hasMemPct' },
  { id: 'swap',    title: 'Swap Used (MB)',                    series: [['Swap MB', 'swap']], flag: 'hasSwap' },
  { id: 'zonewm',  title: 'Memory Zone: free pages vs watermarks', series: [['free', 'zoneFree'], ['high', 'zoneHigh'], ['low', 'zoneLow'], ['min', 'zoneMin']],
                   colors: ['#1f77b4', '#2ca02c', '#f59e0b', '#d62728'], flag: 'hasZone' },
  { id: 'psi',     title: 'Pressure PSI avg10 (some)',         series: [['cpu', 'psiCpu'], ['memory', 'psiMem'], ['io', 'psiIo']],
                   baseline: { value: 0, label: 'target: 0 (no stall)' }, flag: 'hasPsi' },
  { id: 'reclaim', title: 'Direct Reclaim (events/s)',         series: [['reclaim/s', 'reclaim']],
                   baseline: { value: 0, label: 'target: 0 (no memory reclaim stalls)' }, flag: 'hasReclaim' },
  { id: 'load',    title: 'Load Average (1m)',                 series: [['load1', 'load1']], flag: 'hasLoad' },
  { id: 'faults',  title: 'Major Page Faults (/s)',            series: [['faults/s', 'faults']],
                   baseline: { value: 0, label: 'target: 0 (no thrashing)' }, flag: 'hasPageFaults' },
  { id: 'temp',    title: 'CPU Temperature (C)',               series: [['Temp C', 'temp']], flag: 'hasThermal' },
  { id: 'freq',    title: 'CPU Frequency (MHz)',               series: [['Freq MHz', 'freq']], flag: 'hasCpuFreq' },
  { id: 'net',     title: 'Network (KB/s)',                    series: [['RX', 'rx'], ['TX', 'tx']], flag: 'hasNetwork' },
  { id: 'pss',     title: 'App PSS — Total (MB)',              series: [['Total PSS', 'pss']], flag: 'hasPss' },
  { id: 'pssbreak',title: 'App PSS breakdown (MB)',            series: [['Java heap', 'pssJava'], ['Native heap', 'pssNative'], ['Code', 'pssCode'], ['Stack', 'pssStack'], ['Graphics', 'pssGfx'], ['Private other', 'pssPriv'], ['System', 'pssSys']], flag: 'hasPss' },
  { id: 'appcpu',  title: 'App Process CPU — all threads (%)', series: [['process %', 'appCpu']], flag: 'hasAppCpu' },
  { id: 'maincpu', title: 'App Main-Thread CPU (%)',           series: [['main thread %', 'mainCpu']], max: 100, flag: 'hasMainCpu' },
  { id: 'mediacpu',title: 'Media &amp; Audio Process CPU (%)', series: [], dynamic: true, flag: 'hasMedia' },
  { id: 'jank',    title: 'UI Jank (% janky frames)',          series: [['janky %', 'jank']], max: 100,
                   baseline: { value: 0, label: 'target: 0% (zero janky frames)' }, flag: 'hasJank' },
  { id: 'phases',  title: 'Frame Phases (avg ms)',             series: [['input', 'fIn'], ['animation', 'fAnim'], ['layout', 'fLay'], ['draw', 'fDraw']],
                   baseline: { value: 4, label: '4ms/phase (60fps budget)' }, flag: 'hasJank' }
];
var COLORS = ['#e8b66d', '#9e8cff', '#58d6c7', '#ff7676', '#71a8ff', '#61d89c', '#d68cff', '#b5afa5'];
var PROC_COLORS = ['#6366f1','#f59e0b','#10b981','#ef4444','#3b82f6','#8b5cf6','#ec4899','#14b8a6','#f97316','#84cc16'];
var METRIC_GROUPS = {
  cpu: 'system', cpuidle: 'system', load: 'system', temp: 'system', freq: 'system', net: 'system',
  mem: 'memory', mempct: 'memory', swap: 'memory', zonewm: 'memory', psi: 'memory', reclaim: 'memory', faults: 'memory',
  pss: 'application', pssbreak: 'application', appcpu: 'application', maincpu: 'application',
  jank: 'rendering', phases: 'rendering', mediacpu: 'media'
};
var charts = {};
var allSamples = [];
var mediaNamesInit = false;
var mediaNames = [];

// Top-process chart state
var procHistory = {};         // pid(str) -> {name, history:[{x,y}], peak}
var selectedPids = {};        // pid(str) -> true
var procAutoSelected = false;
var procChart = null;
var hasTopProcs = false;

// Top-memory chart state
var memHistory = {};          // pid(str) -> {name, history:[{x,y}], peak}
var selectedMemPids = {};
var memAutoSelected = false;
var memChart = null;
var hasTopMem = false;

// Playback (STB) state
var hasPlayback = false;
var videoChart = null;
var tunnelChart = null;
// Raw MediaKind player lifecycle states, ordered terminal/idle (low) -> actively playing (high).
// Must match Playback.LIFECYCLE_STATES on the Kotlin side.
var LIFECYCLE_STATES = ['onError','onSourceUnloaded','onPlaybackFinished','onStop','onPaused',
  'onSourceLoad','onSourceLoaded','onSeek','onStallStarted','onPlay','onStallEnded','onSeeked',
  'onRenderFirstFrame','onReady','onPlaying'];
var VIDEO_ENC = {}; LIFECYCLE_STATES.forEach(function (s, i) { VIDEO_ENC[s] = i; });
var VIDEO_TICKS = {}; LIFECYCLE_STATES.forEach(function (s, i) { VIDEO_TICKS[i] = s; });
var TUNNEL_ENC = { 'n/a': 0, unknown: 0.5, non_tunneled: 1, tunneled: 2 };

function buildCards() {
  DEFS.forEach(function (def) {
    var card = document.createElement('div');
    card.className = 'card';
    card.id = 'card-' + def.id;
    card.innerHTML = '<h2>' + def.title + '</h2><div class="chartbox"><canvas id="c-' + def.id + '"></canvas></div>'
      + '<div id="stats-' + def.id + '" class="chart-stats"></div>';
    document.getElementById('grid-' + METRIC_GROUPS[def.id]).appendChild(card);
    if (def.dynamic) return;  // media chart built later
    var datasets = def.series.map(function (s, i) {
      var col = (def.colors && def.colors[i]) || COLORS[i % COLORS.length];
      return { label: s[0], data: [], borderColor: col, borderWidth: 2,
               pointRadius: 0, tension: 0.15, yAxisID: (def.dual && i === 1) ? 'y2' : 'y' };
    });
    // Baseline reference line — green dashed. Populated lazily in pushSamples()
    // so its x-range always matches the real data (never distorts the axis).
    if (def.baseline) {
      def._blIdx = datasets.length;
      def._blVal = def.baseline.value;
      datasets.push({ label: def.baseline.label,
        data: [],
        borderColor: '#16a34a', borderWidth: 1.5, borderDash: [6, 4],
        pointRadius: 0, tension: 0, fill: false });
    }
    var scales = { x: { type: 'linear', title: { display: true, text: 'time (s)' } }, y: { beginAtZero: true } };
    if (def.max) scales.y.max = def.max;
    if (def.dual) scales.y2 = { position: 'right', beginAtZero: true, grid: { drawOnChartArea: false } };
    charts[def.id] = new Chart(document.getElementById('c-' + def.id), {
      type: 'line', data: { datasets: datasets },
      options: { animation: false, responsive: true, maintainAspectRatio: false,
                 plugins: { legend: { labels: { boxWidth: 12, font: { size: 10 } } } }, scales: scales }
    });
  });
}

function updateMetricGroups() {
  Object.keys({ system: 1, memory: 1, application: 1, rendering: 1, media: 1 }).forEach(function (name) {
    var grid = document.getElementById('grid-' + name);
    var group = document.getElementById('group-' + name);
    var visible = Array.prototype.some.call(grid.children, function (card) { return card.style.display !== 'none'; });
    group.style.display = visible ? 'block' : 'none';
  });
}

function initMediaChart(names) {
  if (mediaNamesInit || !names.length) return;
  mediaNamesInit = true;
  mediaNames = names;
  var datasets = names.map(function (n, i) {
    return { label: n, data: [], borderColor: COLORS[i % COLORS.length], borderWidth: 2,
             pointRadius: 0, tension: 0.15 };
  });
  charts['mediacpu'] = new Chart(document.getElementById('c-mediacpu'), {
    type: 'line', data: { datasets: datasets },
    options: { animation: false, responsive: true, maintainAspectRatio: false,
               plugins: { legend: { labels: { boxWidth: 12, font: { size: 10 } } } },
               scales: { x: { type: 'linear', title: { display: true, text: 'time (s)' } }, y: { beginAtZero: true } } }
  });
}

function initProcChart() {
  if (procChart) return;
  var ctx = document.getElementById('c-procs');
  if (!ctx) { console.error('initProcChart: canvas c-procs not found'); return; }
  try { if (window.Chart && Chart.getChart) { var ex = Chart.getChart(ctx); if (ex) ex.destroy(); } } catch (e) {}
  procChart = new Chart(ctx, {
    type: 'line',
    data: { datasets: [] },
    options: {
      animation: false, responsive: true, maintainAspectRatio: false,
      parsing: false,
      plugins: { legend: { labels: { boxWidth: 10, font: { size: 10 } } } },
      scales: {
        x: { type: 'linear', title: { display: true, text: 'time (s)' } },
        y: { beginAtZero: true, title: { display: false } }
      }
    }
  });
}

function renderProcList() {
  var div = document.getElementById('proc-list');
  if (!div) return;
  var pids = Object.keys(procHistory);
  if (!pids.length) return;
  pids.sort(function (a, b) { return procHistory[b].peak - procHistory[a].peak; });
  var html = '';
  pids.forEach(function (pid) {
    var proc = procHistory[pid];
    var chk = selectedPids[pid] ? 'checked' : '';
    html += '<label style="display:flex;align-items:center;gap:7px;padding:3px 0;cursor:pointer;font-size:12px">'
          + '<input type="checkbox" ' + chk + ' onchange="toggleProc(\'' + pid + '\')" '
          + 'style="width:14px;height:14px;accent-color:#6366f1;flex:none;cursor:pointer">'
          + '<span style="flex:1;font-family:ui-monospace,monospace;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">'
          + escHtml(proc.name) + '</span>'
          + '<span style="color:#6b7280;min-width:42px;text-align:right;font-size:11px">'
          + proc.peak.toFixed(1) + '%</span>'
          + '</label>';
  });
  div.innerHTML = html;
}

function toggleProc(pid) {
  if (selectedPids[pid]) { delete selectedPids[pid]; }
  else { selectedPids[pid] = true; }
  updateProcChart();
  renderProcList();
}

function updateProcChart() {
  if (!procChart) { initProcChart(); }
  if (!procChart) return;
  // Remove datasets for deselected pids
  procChart.data.datasets = procChart.data.datasets.filter(function (ds) { return !!selectedPids[ds._pid]; });
  // Add newly selected
  var existing = {};
  procChart.data.datasets.forEach(function (ds) { existing[ds._pid] = true; });
  Object.keys(selectedPids).forEach(function (pid) {
    if (existing[pid]) return;
    var proc = procHistory[pid];
    if (!proc) return;
    var colorIdx = procChart.data.datasets.length;
    var color = PROC_COLORS[colorIdx % PROC_COLORS.length];
    procChart.data.datasets.push({
      _pid: pid,
      label: proc.name,
      data: proc.history.slice(),
      borderColor: color,
      backgroundColor: color + '22',
      borderWidth: 2, pointRadius: 0, tension: 0.2, fill: false
    });
  });
  // Refresh data for existing datasets
  procChart.data.datasets.forEach(function (ds) {
    if (procHistory[ds._pid]) ds.data = procHistory[ds._pid].history.slice();
  });
  procChart.update('none');
}

function pushProcSamples(list) {
  list.forEach(function (s) {
    if (!s.procs || !s.procs.length) return;
    s.procs.forEach(function (p) {
      var key = '' + p.pid;
      if (!procHistory[key]) procHistory[key] = { name: p.n, history: [], peak: 0 };
      procHistory[key].history.push({ x: s.t, y: p.pct });
      if (p.pct > procHistory[key].peak) procHistory[key].peak = p.pct;
    });
  });
  // Auto-select top 3 by peak on first data
  if (!procAutoSelected && Object.keys(procHistory).length > 0) {
    procAutoSelected = true;
    var sorted = Object.keys(procHistory).sort(function (a, b) { return procHistory[b].peak - procHistory[a].peak; });
    sorted.slice(0, 3).forEach(function (pid) { selectedPids[pid] = true; });
  }
  renderProcList();
  updateProcChart();
  renderStats();
}

// ---- Top memory (PSS) interactive chart ----
function initMemChart() {
  if (memChart) return;
  var ctx = document.getElementById('c-topmem');
  if (!ctx) { console.error('initMemChart: canvas c-topmem not found'); return; }
  // Destroy any chart already bound to this canvas (a partially-constructed chart would otherwise
  // make a fresh `new Chart` throw "Canvas is already in use", leaving memChart null forever).
  try { if (window.Chart && Chart.getChart) { var ex = Chart.getChart(ctx); if (ex) ex.destroy(); } } catch (e) {}
  memChart = new Chart(ctx, {
    type: 'line', data: { datasets: [] },
    options: { animation: false, responsive: true, maintainAspectRatio: false, parsing: false,
      plugins: { legend: { labels: { boxWidth: 10, font: { size: 10 } } } },
      scales: { x: { type: 'linear', title: { display: true, text: 'time (s)' } },
                y: { beginAtZero: true, title: { display: true, text: 'Memory (MB)' } } } }
  });
}
function updateMemSource(src) {
  var el = document.getElementById('mem-src');
  var lbl = document.getElementById('mem-peak-label');
  if (!src) return;
  var txt = src === 'PSS' ? 'PSS via procrank (root)' : 'RSS via ps (lightweight, no root)';
  if (el) el.innerHTML = '&nbsp;&middot;&nbsp;' + txt + ' — check a process to chart it';
  if (lbl) lbl.textContent = 'PEAK ' + src + ' (MB)';
}
function renderMemList() {
  var div = document.getElementById('mem-list');
  if (!div) return;
  var pids = Object.keys(memHistory);
  if (!pids.length) return;
  pids.sort(function (a, b) { return memHistory[b].peak - memHistory[a].peak; });
  var html = '';
  pids.forEach(function (pid) {
    var proc = memHistory[pid];
    var chk = selectedMemPids[pid] ? 'checked' : '';
    html += '<label style="display:flex;align-items:center;gap:7px;padding:3px 0;cursor:pointer;font-size:12px">'
          + '<input type="checkbox" ' + chk + ' onchange="toggleMemProc(\'' + pid + '\')" '
          + 'style="width:14px;height:14px;accent-color:#10b981;flex:none;cursor:pointer">'
          + '<span style="flex:1;font-family:ui-monospace,monospace;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">'
          + escHtml(proc.name) + '</span>'
          + '<span style="color:#6b7280;min-width:52px;text-align:right;font-size:11px">'
          + Math.round(proc.peak) + ' MB</span>'
          + '</label>';
  });
  div.innerHTML = html;
}
function toggleMemProc(pid) {
  if (selectedMemPids[pid]) { delete selectedMemPids[pid]; } else { selectedMemPids[pid] = true; }
  updateMemChart();
  renderMemList();
}
function updateMemChart() {
  if (!memChart) { initMemChart(); }   // lazy retry — eager init in tick() may have run too early
  if (!memChart) return;
  memChart.data.datasets = memChart.data.datasets.filter(function (ds) { return !!selectedMemPids[ds._pid]; });
  var existing = {};
  memChart.data.datasets.forEach(function (ds) { existing[ds._pid] = true; });
  Object.keys(selectedMemPids).forEach(function (pid) {
    if (existing[pid]) return;
    var proc = memHistory[pid];
    if (!proc) return;
    var color = PROC_COLORS[memChart.data.datasets.length % PROC_COLORS.length];
    memChart.data.datasets.push({ _pid: pid, label: proc.name, data: proc.history.slice(),
      borderColor: color, backgroundColor: color + '22', borderWidth: 2, pointRadius: 0, tension: 0.2, fill: false });
  });
  memChart.data.datasets.forEach(function (ds) { if (memHistory[ds._pid]) ds.data = memHistory[ds._pid].history.slice(); });
  memChart.update('none');
}
function pushMemSamples(list) {
  list.forEach(function (s) {
    if (!s.memprocs || !s.memprocs.length) return;
    s.memprocs.forEach(function (p) {
      var key = '' + p.pid;
      if (!memHistory[key]) memHistory[key] = { name: p.n, history: [], peak: 0 };
      memHistory[key].history.push({ x: s.t, y: p.mb });
      if (p.mb > memHistory[key].peak) memHistory[key].peak = p.mb;
    });
  });
  if (!memAutoSelected && Object.keys(memHistory).length > 0) {
    memAutoSelected = true;
    var sorted = Object.keys(memHistory).sort(function (a, b) { return memHistory[b].peak - memHistory[a].peak; });
    sorted.slice(0, 3).forEach(function (pid) { selectedMemPids[pid] = true; });
  }
  renderMemList();
  updateMemChart();
  renderStats();
}

// ---- Playback (STB): video state + tunnel mode ----
function steppedChart(canvasId, color, tickMap, yMax) {
  return new Chart(document.getElementById(canvasId), {
    type: 'line',
    data: { datasets: [{ data: [], borderColor: color, backgroundColor: color + '22',
            borderWidth: 2, pointRadius: 0, stepped: true, fill: false }] },
    options: {
      animation: false, responsive: true, maintainAspectRatio: false, parsing: false,
      plugins: { legend: { display: false } },
      scales: {
        x: { type: 'linear', title: { display: true, text: 'time (s)' } },
        y: { min: 0, max: yMax,
             ticks: { stepSize: 1, callback: function (v) { return tickMap[v] !== undefined ? tickMap[v] : ''; } } }
      }
    }
  });
}

function initPlaybackCharts() {
  if (videoChart) return;
  videoChart = steppedChart('c-video', '#2563EB', VIDEO_TICKS, LIFECYCLE_STATES.length - 0.8);
  tunnelChart = steppedChart('c-tunnel', '#7C3AED',
    { 0: 'n/a', 0.5: 'unknown', 1: 'non-tun', 2: 'tunneled' }, 2.4);
}

function pushPlaybackSamples(list) {
  if (!videoChart) return;
  list.forEach(function (s) {
    if (s.video !== null && s.video !== undefined && VIDEO_ENC[s.video] !== undefined)
      videoChart.data.datasets[0].data.push({ x: s.t, y: VIDEO_ENC[s.video] });
    if (s.tunnel !== null && s.tunnel !== undefined && TUNNEL_ENC[s.tunnel] !== undefined)
      tunnelChart.data.datasets[0].data.push({ x: s.t, y: TUNNEL_ENC[s.tunnel] });
  });
  videoChart.update('none');
  tunnelChart.update('none');
  renderPlaybackBadges();
}

function renderBuildBanner(buildTunnel) {
  var el = document.getElementById('pb-buildbanner');
  if (!el) return;
  el.style.display = 'block';
  if (buildTunnel === true) {
    el.textContent = '✓ Tunnel mode ENABLED in build (isTunneledPlaybackEnabled=true)';
    el.style.background = 'rgba(97,216,156,.09)'; el.style.color = '#9af0c4'; el.style.border = '1px solid rgba(97,216,156,.28)';
  } else if (buildTunnel === false) {
    el.textContent = '✗ Tunnel mode DISABLED in build (isTunneledPlaybackEnabled=false)';
    el.style.background = 'rgba(255,118,118,.09)'; el.style.color = '#ffb0b0'; el.style.border = '1px solid rgba(255,118,118,.28)';
  } else {
    el.textContent = '— Build tunnel flag not yet detected (play a video; reads isTunneledPlaybackEnabled from player config)';
    el.style.background = 'rgba(255,255,255,.035)'; el.style.color = '#aaa69e'; el.style.border = '1px solid rgba(255,255,255,.09)';
  }
}

var PB_NOT_PLAYING = { onError: 1, onSourceUnloaded: 1, onPlaybackFinished: 1, onStop: 1, onPaused: 1 };
function isPlayingState(s) { return !!s && VIDEO_ENC[s] !== undefined && !PB_NOT_PLAYING[s]; }

// Separate lifecycle-state flag — prominent current player lifecycle state.
function renderLifecycleFlag() {
  var el = document.getElementById('pb-lifecycleflag');
  if (!el || !allSamples.length) return;
  var last = allSamples[allSamples.length - 1];
  var vs = last.video;
  el.style.display = 'block';
  if (vs) {
    var playing = isPlayingState(vs);
    el.textContent = '▶ Lifecycle state: ' + vs + (playing ? '  (playing)' : '  (not playing)');
    if (playing) { el.style.background = 'rgba(113,168,255,.09)'; el.style.color = '#a9caff'; el.style.border = '1px solid rgba(113,168,255,.28)'; }
    else { el.style.background = 'rgba(255,255,255,.035)'; el.style.color = '#aaa69e'; el.style.border = '1px solid rgba(255,255,255,.09)'; }
  } else {
    el.textContent = '▶ Lifecycle state: none yet (start a video)';
    el.style.background = 'rgba(255,255,255,.035)'; el.style.color = '#aaa69e'; el.style.border = '1px solid rgba(255,255,255,.09)';
  }
}

function renderPlaybackBadges() {
  if (!allSamples.length) return;
  var last = allSamples[allSamples.length - 1];
  var vs = last.video || 'none';
  var tn = last.tunnel || 'n/a';
  var vcls = isPlayingState(last.video) ? 'ok' : 'warn';
  var tcls = tn === 'tunneled' ? 'ok' : tn === 'non_tunneled' ? 'warn' : 'warn';
  var html = badge('Player state', vs, vcls)
           + badge('Tunnel mode', tn.replace('_', '-'), tcls);
  document.getElementById('pb-badges').innerHTML = html;
  renderLifecycleFlag();
}

// ---- foreground activity tracking ----
var lastActivity = null;
var activityEvents = [];   // { t, from, to }
function shortAct(comp) {
  if (!comp) return comp;
  var afterSlash = comp.indexOf('/') >= 0 ? comp.substring(comp.indexOf('/') + 1) : comp;
  var dot = afterSlash.lastIndexOf('.');
  return dot >= 0 ? afterSlash.substring(dot + 1) : afterSlash;
}
function handleActivity(s) {
  if (s.act === null || s.act === undefined || s.act === '') return;
  if (s.act !== lastActivity) {
    if (lastActivity !== null) activityEvents.push({ t: s.t, from: lastActivity, to: s.act });
    lastActivity = s.act;
    var b = document.getElementById('activity-banner');
    var c = document.getElementById('activity-cur');
    if (b && c) { b.style.display = 'block'; c.textContent = shortAct(s.act); c.title = s.act; }
  }
}

function pushSamples(list) {
  list.forEach(function (s) {
    allSamples.push(s);
    handleActivity(s);
    DEFS.forEach(function (def) {
      if (def.dynamic) return;
      def.series.forEach(function (ser, i) {
        var v = s[ser[1]];
        if (v !== null && v !== undefined) charts[def.id].data.datasets[i].data.push({ x: s.t, y: v });
      });
    });
    // Push media proc data
    if (mediaNamesInit && s.media) {
      mediaNames.forEach(function (n, i) {
        var v = s.media[n];
        if (v !== null && v !== undefined) charts['mediacpu'].data.datasets[i].data.push({ x: s.t, y: v });
      });
    }
  });
  // Push top-process data (guarded — never let one section's error freeze the others)
  try { if (hasTopProcs) pushProcSamples(list); } catch (e) { console.error('proc push failed', e); }
  // Push top-memory data
  try { if (hasTopMem) pushMemSamples(list); } catch (e) { console.error('mem push failed', e); }
  // Push playback (STB) data
  try { if (hasPlayback) pushPlaybackSamples(list); } catch (e) { console.error('playback push failed', e); }
  // Stretch each baseline to [firstSample.t … lastSample.t] — identical x-range
  // to the real data series, so Chart.js auto-scaling stays consistent.
  if (allSamples.length) {
    var firstT = allSamples[0].t;
    var lastT  = allSamples[allSamples.length - 1].t;
    DEFS.forEach(function (def) {
      if (def._blIdx === undefined || !charts[def.id]) return;
      charts[def.id].data.datasets[def._blIdx].data =
        [{ x: firstT, y: def._blVal }, { x: lastT, y: def._blVal }];
    });
  }
  // Update each chart independently — one chart throwing must not freeze the others.
  Object.keys(charts).forEach(function (k) { try { charts[k].update('none'); } catch (e) { console.error('chart update failed: ' + k, e); } });
  try { if (procChart) procChart.update('none'); } catch (e) { console.error('procChart update failed', e); }
  try { if (memChart) memChart.update('none'); } catch (e) { console.error('memChart update failed', e); }
  try { renderStats(); } catch (e) { console.error('renderStats failed', e); }
}

// ---- health badges (last ~30 s window) ----
function badge(k, v, cls) { return '<div class="hb ' + cls + '"><div class="k">' + k + '</div><div class="v">' + v + '</div></div>'; }

function avg(arr) { var f = arr.filter(function (x) { return x !== null && x !== undefined; });
  if (!f.length) return null; return f.reduce(function (a, b) { return a + b; }, 0) / f.length; }

function renderBadges(anomalies) {
  if (!allSamples.length) return;
  var last = allSamples[allSamples.length - 1];
  var win = allSamples.slice(-15);
  var html = '';

  var cpu = avg(win.map(function (s) { return s.cpu; }));
  if (cpu !== null) html += badge('CPU', Math.round(cpu) + '%', cpu > 85 ? 'bad' : cpu > 60 ? 'warn' : 'ok');

  var availPct = (last.memAvail !== null && last.memUsed !== null)
    ? 100 * last.memAvail / (last.memAvail + last.memUsed) : null;
  var psiMem = metricFlags.psi !== false ? avg(win.map(function (s) { return s.psiMem; })) : null;
  if (availPct !== null) {
    var memBad = availPct < 8 || (psiMem !== null && psiMem > 10);
    var memWarn = availPct < 15 || (psiMem !== null && psiMem > 3);
    html += badge('Memory', Math.round(availPct) + '% free', memBad ? 'bad' : memWarn ? 'warn' : 'ok');
  }

  // Memory zones — free pages vs kernel reclaim watermarks (below low = kswapd; below min = direct reclaim)
  if (last.zoneActive !== null && last.zoneActive !== undefined) {
    var zf = last.zoneFree, zlow = last.zoneLow, zmin = last.zoneMin;
    var zcls = 'ok', znote = '';
    if (zf !== null && zmin !== null && zf < zmin) { zcls = 'bad'; znote = ' · below min'; }
    else if (zf !== null && zlow !== null && zf < zlow) { zcls = 'warn'; znote = ' · below low'; }
    html += badge('Mem zones', last.zoneActive + ' active' + znote, zcls);
  }

  if (metricFlags.thermal !== false) {
    var temp = avg(win.map(function (s) { return s.temp; }));
    if (temp !== null) {
      var maxFreq = 0; allSamples.forEach(function (s) { if (s.freq > maxFreq) maxFreq = s.freq; });
      var freq = avg(win.map(function (s) { return s.freq; }));
      var throttling = temp > 80 && freq !== null && maxFreq > 0 && freq < maxFreq * 0.75;
      html += badge('Thermal', Math.round(temp) + ' C' + (throttling ? ' throttling' : ''),
                    throttling || temp > 90 ? 'bad' : temp > 75 ? 'warn' : 'ok');
    }
  }

  var jank = avg(win.map(function (s) { return s.jank; }));
  if (jank !== null) html += badge('UI Jank', Math.round(jank) + '%', jank > 30 ? 'bad' : jank > 10 ? 'warn' : 'ok');

  var appCpuAvg = avg(win.map(function (s) { return s.appCpu; }));
  if (appCpuAvg !== null) html += badge('App CPU', Math.round(appCpuAvg) + '%', appCpuAvg > 200 ? 'bad' : appCpuAvg > 100 ? 'warn' : 'ok');

  var crashes = 0, kills = 0;
  anomalies.forEach(function (a) {
    if (a.type === 'ANR' || a.type === 'Fatal exception' || a.type === 'Native crash') crashes++;
    if (a.type === 'LMK kill') kills++;
  });
  var stab = crashes > 0 ? crashes + ' crash/ANR' : kills > 0 ? kills + ' LMK kills' : 'stable';
  html += badge('Stability', stab, crashes > 0 ? 'bad' : kills > 0 ? 'warn' : 'ok');

  document.getElementById('badges').innerHTML = html;
}

function escHtml(s) { var d = document.createElement('div'); d.textContent = s; return d.innerHTML; }

function fmtStat(v) {
  if (v === null || v === undefined || isNaN(v)) return '-';
  if (Math.abs(v) >= 10000) return Math.round(v).toString();
  if (Math.abs(v) >= 100) return v.toFixed(1);
  if (Math.abs(v) >= 1) return v.toFixed(2);
  return v.toFixed(3);
}

function statHtml(dataset) {
  var vals = dataset.data.filter(function (p) { return p.y !== null && p.y !== undefined && !isNaN(p.y); }).map(function (p) { return p.y; });
  if (!vals.length) return '';
  var min = Math.min.apply(null, vals);
  var avg = vals.reduce(function (a, b) { return a + b; }, 0) / vals.length;
  var max = Math.max.apply(null, vals);
  return '<span class="sval"><span class="sl">min</span>' + fmtStat(min) + '</span>'
       + '<span class="sval"><span class="sl">avg</span>' + fmtStat(avg) + '</span>'
       + '<span class="sval"><span class="sl">max</span>' + fmtStat(max) + '</span>';
}

function renderStats() {
  // Standard metric charts (DEFS) — guarded so one chart's stats error can't stop the rest.
  DEFS.forEach(function (def) {
    try {
      var ch = charts[def.id];
      var div = document.getElementById('stats-' + def.id);
      if (!ch || !div) return;
      var html = '';
      ch.data.datasets.forEach(function (ds) {
        if (ds.borderDash && ds.borderDash.length) return; // skip baseline ref lines
        var row = statHtml(ds);
        if (!row) return;
        var namePart = (ch.data.datasets.filter(function (d) { return !d.borderDash || !d.borderDash.length; }).length > 1)
          ? '<span class="sname">' + escHtml(ds.label) + '</span>' : '';
        html += '<div class="srow">' + namePart + row + '</div>';
      });
      div.innerHTML = html;
    } catch (e) { console.error('stats failed: ' + def.id, e); }
  });
  // Proc chart
  try {
    if (procChart && hasTopProcs) {
      var div = document.getElementById('stats-procs');
      if (div) {
        var html = '';
        procChart.data.datasets.forEach(function (ds) {
          var row = statHtml(ds);
          if (!row) return;
          html += '<div class="srow"><span class="sname">' + escHtml(ds.label) + '</span>' + row + '</div>';
        });
        div.innerHTML = html;
      }
    }
  } catch (e) { console.error('proc stats failed', e); }
  // Mem chart
  try {
    if (memChart && hasTopMem) {
      var mdiv = document.getElementById('stats-mem');
      if (mdiv) {
        var mhtml = '';
        memChart.data.datasets.forEach(function (ds) {
          var row = statHtml(ds);
          if (!row) return;
          mhtml += '<div class="srow"><span class="sname">' + escHtml(ds.label) + '</span>' + row + '</div>';
        });
        mdiv.innerHTML = mhtml;
      }
    }
  } catch (e) { console.error('mem stats failed', e); }
}

function renderEvents(zaps, anomalies) {
  var rows = [];
  zaps.forEach(function (t) { rows.push({ t: t, html: '<span class="tag zap">zap</span>', detail: 'KEYCODE_CHANNEL_UP sent' }); });
  anomalies.forEach(function (a) {
    rows.push({ t: (a.t === null ? -1 : a.t), html: '<span class="tag anom">' + a.type + '</span>', detail: a.detail });
  });
  activityEvents.forEach(function (e) {
    rows.push({ t: e.t, html: '<span class="tag act">activity</span>', detail: shortAct(e.from) + '  →  ' + shortAct(e.to) });
  });
  rows.sort(function (a, b) { return a.t - b.t; });
  var body = '';
  rows.forEach(function (r) {
    var ts = r.t < 0 ? '?' : (Math.round(r.t * 10) / 10 + 's');
    body += '<tr><td>' + ts + '</td><td>' + r.html + '</td><td>' + escHtml(r.detail) + '</td></tr>';
  });
  document.getElementById('evbody').innerHTML = body || '<tr><td colspan="3">none yet</td></tr>';
}

var stopping = false;
function stopRun() {
  if (stopping) return;            // guard against double-clicks
  stopping = true;
  var btn = document.getElementById('stopBtn');
  btn.disabled = true;
  btn.classList.add('busy');
  btn.innerHTML = '<span class="spinner"></span>Stopping…';
  var st = document.getElementById('status');
  st.textContent = '● stopping — finishing current sample & writing the report…';
  st.className = 'stopping';
  fetch('/api/stop', { method: 'POST' }).catch(function () {});
}

// Metric availability flags — populated from first /api/session response
var metricFlags = {};

var idx = 0;
var PAGE_BUILD = "__BUILD__";
try { console.log('ADB Performance Studio — dashboard JS build:', PAGE_BUILD); } catch (e) {}
function tick() {
  fetch('/api/session?since=' + idx).then(function (r) { return r.json(); }).then(function (d) {
    if (d.status === 'idle') { window.location = '/'; return; }
    // Self-heal stale cached JS: if the server is a newer build than this page, reload fresh once.
    if (d.build && PAGE_BUILD && d.build !== PAGE_BUILD && !window.__reloadedForBuild) {
      window.__reloadedForBuild = true;
      console.warn('Dashboard JS is stale (page=' + PAGE_BUILD + ', server=' + d.build + ') — reloading fresh.');
      window.location.replace('/dashboard?v=' + Date.now());
      return;
    }
    var labelPart = d.label ? ' [' + d.label + ']' : '';
    document.getElementById('device').textContent = d.device + '   [' + d.target + ']' + labelPart + (d.runDir ? '   ' + d.runDir : '');
    if (d.mediaProcNames && d.mediaProcNames.length && d.hasMedia !== false && !mediaNamesInit) {
      initMediaChart(d.mediaProcNames);
    }
    // First response: capture flags, show/hide each chart by its 1:1 toggle, init sections.
    if (idx === 0) {
      metricFlags.psi        = d.hasPsi        !== false;
      metricFlags.thermal    = d.hasThermal    !== false;
      metricFlags.network    = d.hasNetwork    !== false;
      metricFlags.pageFaults = d.hasPageFaults !== false;
      metricFlags.cpuFreq    = d.hasCpuFreq   !== false;
      metricFlags.load       = d.hasLoad      !== false;
      // Generic gating: hide any chart whose flag is disabled in this run.
      DEFS.forEach(function (def) {
        if (def.flag && d[def.flag] === false) {
          var c = document.getElementById('card-' + def.id); if (c) c.style.display = 'none';
        }
      });
      updateMetricGroups();
      // Top-process feature (each init guarded so one failure can't halt the others or the loop)
      if (d.hasTopProcs === true) {
        hasTopProcs = true;
        var ps = document.getElementById('proc-section');
        if (ps) ps.style.display = 'block';
        try { initProcChart(); } catch (e) { console.error('initProcChart failed', e); }
      }
      // Top-memory feature
      if (d.hasTopMem === true) {
        hasTopMem = true;
        var ms = document.getElementById('mem-section');
        if (ms) ms.style.display = 'block';
        try { initMemChart(); } catch (e) { console.error('initMemChart failed', e); }
      }
      // Playback (STB) feature
      if (d.hasPlayback === true) {
        hasPlayback = true;
        var pbs = document.getElementById('playback-section');
        if (pbs) pbs.style.display = 'block';
        try { initPlaybackCharts(); } catch (e) { console.error('initPlaybackCharts failed', e); }
      }
    }
    idx = d.nextIndex;
    // Update controls/status FIRST so a finished/failed run always flips the Stop button out of
    // its "Stopping\u2026" state \u2014 even if a later render call throws.
    var st = document.getElementById('status');
    var finished = (d.status === 'done' || d.status === 'failed');
    if (finished) {
      st.textContent = d.status === 'done' ? '\u25a0 finished' : '\u26a0 ' + (d.error || 'failed');
      st.className = d.status === 'done' ? 'finished' : 'failed';
      var sb2 = document.getElementById('stopBtn'); if (sb2) sb2.style.display = 'none';
      if (d.status === 'done') { var rb = document.getElementById('reportBtn'); if (rb) rb.style.display = 'inline-block'; }
    }
    // Render data \u2014 each call independently guarded so no single metric can block the others,
    // the badges, or the poll loop. (This is what makes enabling Top Process Memory safe.)
    try { if (hasTopMem && d.memSource) updateMemSource(d.memSource); } catch (e) { console.error('updateMemSource failed', e); }
    try { if (d.samples.length) pushSamples(d.samples); } catch (e) { console.error('pushSamples failed', e); }
    try { if (hasPlayback) renderBuildBanner(d.buildTunnel); } catch (e) { console.error('renderBuildBanner failed', e); }
    try { renderEvents(d.zaps, d.anomalies); } catch (e) { console.error('renderEvents failed', e); }
    try { renderBadges(d.anomalies); } catch (e) { console.error('renderBadges failed', e); }
    if (!finished) setTimeout(tick, Math.max(1000, d.intervalSec * 1000 / 2));
  }).catch(function () { setTimeout(tick, 2000); });
}

buildCards();
tick();
</script>
</body>
</html>
"""
