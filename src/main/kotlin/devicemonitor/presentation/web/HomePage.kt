package devicemonitor.presentation.web

// Plain JS throughout (no template literals) so these Kotlin raw strings stay simple.

internal val HOME_PAGE = """<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<title>ADB Performance Studio</title>
<style>
  * { box-sizing: border-box; }
  body { font-family: -apple-system, 'Segoe UI', Roboto, sans-serif; margin: 0; background: #f4f5f7; color: #1f2430; }
  .hero { background: linear-gradient(135deg, #111827 0%, #1e3a5f 60%, #0e7490 100%); color: #fff; padding: 28px 28px 22px 28px; display: flex; align-items: flex-start; justify-content: space-between; flex-wrap: wrap; gap: 10px; }
  .hero h1 { margin: 0; font-size: 24px; letter-spacing: 0.3px; }
  .hero p { margin: 6px 0 0 0; color: #b8c4d4; font-size: 13px; }
  .hero-links { display: flex; gap: 10px; align-items: center; }
  .hero-links a { color: #7dd3fc; font-size: 13px; text-decoration: none; }
  .hero-links a:hover { text-decoration: underline; }
  .wrap { max-width: 1060px; margin: 0 auto; padding: 20px 24px 40px 24px; }
  h2 { font-size: 15px; color: #374151; margin: 22px 0 10px 0; }
  .devgrid { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 12px; }
  .dev { background: #fff; border: 2px solid #e2e4e8; border-radius: 10px; padding: 14px; cursor: pointer; transition: border-color .15s, box-shadow .15s; }
  .dev:hover { box-shadow: 0 2px 10px rgba(0,0,0,.07); }
  .dev.selected { border-color: #0e7490; box-shadow: 0 0 0 3px rgba(14,116,144,.15); }
  .dev.dead { opacity: .55; cursor: not-allowed; }
  .dev .serial { font-family: ui-monospace, Menlo, monospace; font-size: 12px; color: #6b7280; }
  .dev .model { font-weight: 600; margin: 4px 0; font-size: 14px; }
  .dev .meta { font-size: 12px; color: #4b5563; }
  .badge { display: inline-block; font-size: 11px; padding: 2px 8px; border-radius: 9px; color: #fff; float: right; }
  .badge.ok { background: #15803d; } .badge.bad { background: #b91c1c; }
  .rootchip { display: inline-block; font-size: 10px; padding: 1px 7px; border-radius: 8px; margin-top: 6px; }
  .rootchip.root { background: #dcfce7; color: #15803d; border: 1px solid #86efac; }
  .rootbtn { display: inline-block; font-size: 11px; margin-top: 6px; padding: 3px 10px; border-radius: 6px;
             border: 1px solid #b45309; background: #fff7ed; color: #b45309; cursor: pointer; }
  .rootbtn:disabled { opacity: .5; cursor: wait; }
  .empty { background: #fff; border: 2px dashed #cdd2da; border-radius: 10px; padding: 26px; text-align: center; color: #6b7280; font-size: 13px; grid-column: 1 / -1; }
  .panel { background: #fff; border: 1px solid #e2e4e8; border-radius: 10px; padding: 18px; }
  .row { display: flex; gap: 22px; flex-wrap: wrap; align-items: flex-start; margin-bottom: 14px; }
  .field { display: flex; flex-direction: column; gap: 4px; }
  .field label { font-size: 12px; color: #4b5563; font-weight: 600; }
  .field input[type=number], .field input[type=text] { padding: 7px 9px; border: 1px solid #cdd2da; border-radius: 6px; width: 150px; font-size: 13px; }
  .field .hint { font-size: 11px; color: #9aa1ab; }
  .toggle { display: flex; align-items: center; gap: 10px; padding: 9px 0; }
  .toggle .desc { font-size: 12px; color: #6b7280; }
  .toggle .name { font-size: 13px; font-weight: 600; }
  .switch { position: relative; width: 42px; height: 23px; flex: none; }
  .switch input { opacity: 0; width: 0; height: 0; }
  .slider { position: absolute; inset: 0; background: #cbd5e1; border-radius: 23px; cursor: pointer; transition: .15s; }
  .slider:before { content: ""; position: absolute; height: 17px; width: 17px; left: 3px; top: 3px; background: #fff; border-radius: 50%; transition: .15s; }
  .switch input:checked + .slider { background: #0e7490; }
  .switch input:checked + .slider:before { transform: translateX(19px); }
  .sub { margin-left: 52px; }
  button.start { background: linear-gradient(135deg, #0e7490, #15803d); border: 0; color: #fff; font-size: 15px; font-weight: 700;
                 padding: 12px 34px; border-radius: 8px; cursor: pointer; }
  button.start:disabled { background: #9aa1ab; cursor: not-allowed; }
  #msg { color: #b91c1c; font-size: 13px; margin-left: 14px; }
  .banner { background: #fef3c7; border: 1px solid #f59e0b; border-radius: 8px; padding: 10px 14px; font-size: 13px; margin-bottom: 14px; display: none; }
  .banner a { color: #0e7490; font-weight: 600; }
  .refresh { float: right; font-size: 12px; color: #0e7490; cursor: pointer; }
  .metrics-header { display: flex; align-items: center; justify-content: space-between; cursor: pointer; padding: 9px 0 5px 0; border-top: 1px solid #e2e4e8; margin-top: 8px; user-select: none; }
  .metrics-header .mh-title { font-size: 13px; font-weight: 600; color: #374151; }
  .metrics-header .mh-hint { font-size: 11px; color: #9aa1ab; margin-left: 8px; }
  .metrics-header .mh-arrow { font-size: 11px; color: #6b7280; transition: transform .2s; }
  .metrics-header.open .mh-arrow { transform: rotate(180deg); }
  .metrics-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(290px, 1fr)); gap: 2px 18px; padding: 8px 0 4px 0; }
  .mcheck { display: flex; align-items: center; gap: 8px; padding: 5px 0; }
  .mcheck input[type=checkbox] { width: 15px; height: 15px; accent-color: #0e7490; cursor: pointer; flex: none; }
  .mcheck-label { font-size: 13px; font-weight: 600; color: #374151; }
  .mcheck-cost { font-size: 11px; color: #9aa1ab; margin-left: 4px; }
  .mgroup-title { font-size: 12px; font-weight: 700; color: #0e7490; letter-spacing: .03em; text-transform: uppercase; margin: 12px 0 2px 0; }
  .mgroup-title:first-child { margin-top: 4px; }
  .mgroup-hint { font-size: 11px; font-weight: 400; color: #9aa1ab; text-transform: none; letter-spacing: 0; margin-left: 6px; }
</style>
</head>
<body>
<div class="hero">
  <div>
    <h1>ADB Performance Studio</h1>
    <p>Live system &amp; app performance for Android phones, TVs, STBs and Fire sticks — over adb.</p>
    <p style="font-size:11px;color:#9aa1ab;margin-top:2px">Build: <span id="buildtag">…</span></p>
  </div>
  <div class="hero-links">
    <a href="/compare">&#9654;&#9654; Run Comparison</a>
    <a href="/api/diagnostics" title="Download a zip with the logs and a thread dump — attach it when reporting a hang">Export diagnostics</a>
  </div>
</div>
<div class="wrap">
  <div class="banner" id="banner">A monitoring session is in progress. <a href="/dashboard" onclick="this.href='/dashboard?v='+Date.now()">Open the live dashboard &rarr;</a></div>

  <h2>1 &middot; Connected devices <span class="refresh" onclick="loadDevices()">&#8635; refresh</span></h2>
  <div class="devgrid" id="devices"><div class="empty">Scanning for adb devices&hellip;</div></div>
  <div id="snapshot-box" style="margin:10px 0 2px;display:flex;align-items:center;gap:12px;flex-wrap:wrap">
    <button type="button" id="snapBtn" onclick="takeSnapshot()"
      style="padding:9px 16px;border:1px solid #c7ccd6;border-radius:8px;background:#eef2ff;color:#3730a3;font-weight:600;cursor:pointer;font-size:13px">&#128247; Device Snapshot</button>
    <button type="button" id="procBtn" onclick="takeProcrank()"
      style="padding:9px 16px;border:1px solid #c7ccd6;border-radius:8px;background:#ecfeff;color:#0e7490;font-weight:600;cursor:pointer;font-size:13px">&#128202; Procrank Snapshot</button>
    <span class="hint">one-shot STB capabilities &amp; memory footprint &rarr; interactive report</span>
  </div>
  <div id="snapProgWrap" style="display:none;margin:6px 0 2px;max-width:520px">
    <div style="height:9px;background:#e5e7eb;border-radius:5px;overflow:hidden">
      <div id="snapProg" style="height:100%;width:0;background:#4f46e5;transition:width .3s"></div>
    </div>
    <div id="snapMsg" style="font-size:12px;color:#6b7280;margin-top:5px"></div>
  </div>
  <div id="procProgWrap" style="display:none;margin:6px 0 2px;max-width:520px">
    <div style="height:9px;background:#e5e7eb;border-radius:5px;overflow:hidden">
      <div id="procProg" style="height:100%;width:0;background:#0891b2;transition:width .3s"></div>
    </div>
    <div id="procMsg" style="font-size:12px;color:#6b7280;margin-top:5px"></div>
  </div>

  <h2>2 &middot; Configure the run</h2>
  <div class="panel">
    <div class="row">
      <div class="field"><label>Duration (seconds)</label><input type="number" id="duration" value="300" min="1"></div>
      <div class="field"><label>Sampling interval (seconds)</label><input type="number" id="interval" value="2" min="0.5" step="0.5"></div>
      <div class="field"><label>App package (optional)</label><input type="text" id="package" placeholder="com.example.tv">
        <span class="hint">enables PSS, app CPU &amp; media proc tracking</span></div>
      <div class="field"><label>Run label (optional)</label><input type="text" id="label" placeholder="tunnel-on">
        <span class="hint">tags folder &amp; PDF — use for tunnel comparisons</span></div>
      <div class="field" style="flex:1 1 100%"><label>Reports folder</label>
        <div style="display:flex;gap:8px;align-items:center">
          <input type="text" id="outputDir" placeholder="loading default…" style="flex:1">
          <button type="button" id="browseBtn" onclick="pickFolder()" style="white-space:nowrap;padding:8px 14px;border:1px solid #c7ccd6;border-radius:8px;background:#f3f4f6;color:#374151;cursor:pointer;font-size:13px">&#128193; Browse…</button>
        </div>
        <span class="hint">where run_* folders (PDF, CSV, logcat) are saved — must be writable</span></div>
    </div>
    <div class="toggle">
      <label class="switch"><input type="checkbox" id="zap"><span class="slider"></span></label>
      <div><div class="name">Channel zapping (CHANNEL_UP)</div><div class="desc">stress test: send KEYCODE_CHANNEL_UP periodically; each zap is marked on the charts</div></div>
    </div>
    <div class="sub row" id="zapcfg" style="display:none">
      <div class="field"><label>Zap every (ms)</label><input type="number" id="zapMs" value="5000" min="100"></div>
      <div class="field"><label>Stop after (zaps, optional)</label><input type="number" id="zapCount" placeholder="until duration"></div>
    </div>
    <div class="toggle">
      <label class="switch"><input type="checkbox" id="logcat" checked><span class="slider"></span></label>
      <div><div class="name">Logcat capture</div><div class="desc">saves logcat.log, flags anomalies (ANR, crashes, LMK kills, jank) with timestamps, extracts app build info from analytics events</div></div>
    </div>
    <div class="toggle">
      <label class="switch"><input type="checkbox" id="jank"><span class="slider"></span></label>
      <div><div class="name">UI jank &amp; frame phases</div><div class="desc">janky-frame % and input/animation/layout/draw ms per frame from dumpsys gfxinfo framestats &mdash; requires an app package above</div></div>
    </div>
    <div class="toggle">
      <label class="switch"><input type="checkbox" id="profile"><span class="slider"></span></label>
      <div><div class="name">Main-thread method profiler</div><div class="desc">ART sampling profiler (am profile) &mdash; names the actual methods running on the main thread; report gets a hot-methods table. Requires a debuggable app package</div></div>
    </div>
    <div class="toggle">
      <label class="switch"><input type="checkbox" id="perfetto"><span class="slider"></span></label>
      <div><div class="name">Perfetto system trace</div><div class="desc">records sched + gfx/view/input slices for the whole run; trace.perfetto-trace saved in the run folder for ui.perfetto.dev</div></div>
    </div>
    <div class="metrics-header" id="metricsHdr" onclick="toggleMetrics()">
      <span>
        <span class="mh-title">&#128270; Metrics options</span>
        <span class="mh-hint">disable to reduce device overhead</span>
      </span>
      <span class="mh-arrow">&#9660;</span>
    </div>
    <div id="metricsCfg" style="display:none">
      <div class="mgroup-title">System &middot; core <span class="mgroup-hint">essentially free — toggle to show/hide the chart</span></div>
      <div class="metrics-grid">
        <div class="mcheck"><input type="checkbox" id="m_cpu">
          <span><span class="mcheck-label">CPU utilization %</span><span class="mcheck-cost">free · /proc/stat</span></span></div>
        <div class="mcheck"><input type="checkbox" id="m_cpuidle">
          <span><span class="mcheck-label">CPU idle %</span><span class="mcheck-cost">free · /proc/stat</span></span></div>
        <div class="mcheck"><input type="checkbox" id="m_percore">
          <span><span class="mcheck-label">Per-core CPU %</span><span class="mcheck-cost">free · /proc/stat cpuN</span></span></div>
        <div class="mcheck"><input type="checkbox" id="m_mem">
          <span><span class="mcheck-label">Memory (MB)</span><span class="mcheck-cost">free · /proc/meminfo</span></span></div>
        <div class="mcheck"><input type="checkbox" id="m_mempct">
          <span><span class="mcheck-label">Memory used %</span><span class="mcheck-cost">free · /proc/meminfo</span></span></div>
        <div class="mcheck"><input type="checkbox" id="m_swap">
          <span><span class="mcheck-label">Swap used (MB)</span><span class="mcheck-cost">free · /proc/meminfo</span></span></div>
        <div class="mcheck"><input type="checkbox" id="m_zone">
          <span><span class="mcheck-label">Zone watermarks</span><span class="mcheck-cost">free · /proc/zoneinfo — free pages vs min/low/high</span></span></div>
        <div class="mcheck"><input type="checkbox" id="m_psi" checked>
          <span><span class="mcheck-label">PSI pressure stall</span><span class="mcheck-cost">low cost · /proc/pressure/*</span></span></div>
      </div>
      <div class="mgroup-title">System &middot; extra <span class="mgroup-hint">small per-tick device cost</span></div>
      <div class="metrics-grid">
        <div class="mcheck"><input type="checkbox" id="m_load">
          <span><span class="mcheck-label">Load average (1m)</span><span class="mcheck-cost">negligible · /proc/loadavg</span></span></div>
        <div class="mcheck"><input type="checkbox" id="m_pgfault">
          <span><span class="mcheck-label">Page faults (thrash)</span><span class="mcheck-cost">low cost · /proc/vmstat</span></span></div>
        <div class="mcheck"><input type="checkbox" id="m_reclaim" onchange="if(this.checked){var l=document.getElementById('logcat');if(l)l.checked=true;}">
          <span><span class="mcheck-label">Direct reclaim</span><span class="mcheck-cost">logcat · memory-pressure events/s</span></span></div>
        <div class="mcheck"><input type="checkbox" id="m_thermal">
          <span><span class="mcheck-label">CPU temperature</span><span class="mcheck-cost">low cost · /sys/class/thermal/*</span></span></div>
        <div class="mcheck"><input type="checkbox" id="m_freq">
          <span><span class="mcheck-label">CPU frequency</span><span class="mcheck-cost">low cost · scaling_cur_freq</span></span></div>
        <div class="mcheck"><input type="checkbox" id="m_network">
          <span><span class="mcheck-label">Network throughput</span><span class="mcheck-cost">low cost · /proc/net/dev</span></span></div>
        <div class="mcheck"><input type="checkbox" id="m_topprocs">
          <span><span class="mcheck-label">Top process CPU</span><span class="mcheck-cost">medium · reads /proc/*/stat per tick</span></span></div>
        <div class="mcheck"><input type="checkbox" id="m_topmem">
          <span><span class="mcheck-label">Top process memory</span><span class="mcheck-cost">RSS via a single ps call — lightweight, no root, no procrank</span></span></div>
        <div class="mcheck"><input type="checkbox" id="m_playback" onchange="if(this.checked){var l=document.getElementById('logcat');if(l)l.checked=true;}">
          <span><span class="mcheck-label">Playback &amp; tunnel (STB)</span><span class="mcheck-cost">logcat · video state + TUNNELED</span></span></div>
      </div>
      <div class="mgroup-title">App metrics <span class="mgroup-hint">require an app package above</span></div>
      <div class="metrics-grid">
        <div class="mcheck"><input type="checkbox" id="m_pss" checked>
          <span><span class="mcheck-label">App PSS breakdown</span><span class="mcheck-cost">medium · dumpsys meminfo</span></span></div>
        <div class="mcheck"><input type="checkbox" id="m_appcpu">
          <span><span class="mcheck-label">App process CPU</span><span class="mcheck-cost">low · /proc + dumpsys fallback</span></span></div>
        <div class="mcheck"><input type="checkbox" id="m_maincpu">
          <span><span class="mcheck-label">App main-thread CPU</span><span class="mcheck-cost">low · /proc task stat</span></span></div>
        <div class="mcheck"><input type="checkbox" id="m_mediacpu">
          <span><span class="mcheck-label">Media / HAL process CPU</span><span class="mcheck-cost">low · media pipeline PIDs</span></span></div>
      </div>
    </div>
    <div style="margin-top:16px">
      <button class="start" id="startBtn" onclick="startRun()">&#9654;&nbsp; Start performance run</button>
      <span id="msg"></span>
    </div>
  </div>
</div>
<script>
var selected = null;

var devicesLoading = false;
function loadDevices() {
  if (devicesLoading) return;   // never stack polls behind a slow adb — that exhausts the browser's connections
  devicesLoading = true;
  fetch('/api/devices').then(function (r) { devicesLoading = false; return r.json(); }, function (e) { devicesLoading = false; throw e; }).then(function (d) {
    document.getElementById('banner').style.display = d.busy ? 'block' : 'none';
    var grid = document.getElementById('devices');
    if (!d.devices.length) {
      grid.innerHTML = '<div class="empty">No device found. Connect a device via USB (or adb connect &lt;ip&gt;) and make sure USB debugging is authorized.</div>';
      selected = null;
      return;
    }
    var html = '';
    d.devices.forEach(function (dev) {
      var usable = dev.state === 'device';
      var cls = 'dev' + (usable ? '' : ' dead') + (dev.serial === selected ? ' selected' : '');
      var badge = usable ? '<span class="badge ok">ready</span>' : '<span class="badge bad">' + dev.state + '</span>';
      var model = dev.desc || dev.serial;
      var android = dev.android ? ('Android ' + dev.android) : '';
      var rootHtml = '';
      if (dev.root === 'root') {
        rootHtml = '<span class="rootchip root">&#10003; adbd running as root</span>';
      } else if (dev.root === 'available') {
        rootHtml = '<button class="rootbtn" data-rootserial="' + dev.serial + '">&#9889; Enable root (adb root)</button>';
      }
      html += '<div class="' + cls + '" data-serial="' + dev.serial + '" data-usable="' + usable + '">'
            + badge + '<div class="serial">' + dev.serial + '</div>'
            + '<div class="model">' + model + '</div>'
            + '<div class="meta">' + android + '</div>' + rootHtml + '</div>';
    });
    grid.innerHTML = html;
    var first = null;
    Array.prototype.forEach.call(grid.children, function (el) {
      if (el.dataset.usable === 'true') {
        if (!first) first = el;
        el.onclick = function () { selected = el.dataset.serial; loadDevices(); };
      }
    });
    if (!selected && first) { selected = first.dataset.serial; loadDevices(); }
    Array.prototype.forEach.call(document.querySelectorAll('.rootbtn'), function (btn) {
      btn.onclick = function (ev) {
        ev.stopPropagation();
        btn.disabled = true;
        btn.textContent = 'Restarting adbd...';
        var p = new URLSearchParams();
        p.set('serial', btn.dataset.rootserial);
        fetch('/api/root', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: p.toString() })
          .then(function (r) { return r.json(); })
          .then(function (d) {
            if (!d.ok) alert(d.error || 'adb root failed');
            setTimeout(loadDevices, 1500);
          })
          .catch(function () { setTimeout(loadDevices, 1500); });
      };
    });
  }).catch(function () {});
}

document.getElementById('zap').onchange = function () {
  document.getElementById('zapcfg').style.display = this.checked ? 'flex' : 'none';
};

function toggleMetrics() {
  var hdr = document.getElementById('metricsHdr');
  var cfg = document.getElementById('metricsCfg');
  var open = cfg.style.display !== 'none';
  cfg.style.display = open ? 'none' : 'block';
  if (open) hdr.classList.remove('open'); else hdr.classList.add('open');
}

function startRun() {
  var msg = document.getElementById('msg');
  msg.textContent = '';
  if (!selected) { msg.textContent = 'Select a device first.'; return; }
  var p = new URLSearchParams();
  p.set('serial', selected);
  p.set('duration', document.getElementById('duration').value);
  p.set('interval', document.getElementById('interval').value);
  p.set('zap', document.getElementById('zap').checked);
  p.set('zapMs', document.getElementById('zapMs').value);
  if (document.getElementById('zapCount').value) p.set('zapCount', document.getElementById('zapCount').value);
  p.set('package', document.getElementById('package').value);
  p.set('label', document.getElementById('label').value);
  p.set('outputDir', document.getElementById('outputDir').value);
  p.set('logcat', document.getElementById('logcat').checked);
  p.set('jank', document.getElementById('jank').checked);
  p.set('profile', document.getElementById('profile').checked);
  p.set('perfetto', document.getElementById('perfetto').checked);
  p.set('cpuUtil', document.getElementById('m_cpu').checked);
  p.set('cpuIdle', document.getElementById('m_cpuidle').checked);
  p.set('perCore', document.getElementById('m_percore').checked);
  p.set('memoryMb', document.getElementById('m_mem').checked);
  p.set('memoryPct', document.getElementById('m_mempct').checked);
  p.set('swap', document.getElementById('m_swap').checked);
  p.set('zoneinfo', document.getElementById('m_zone').checked);
  p.set('psi', document.getElementById('m_psi').checked);
  p.set('load', document.getElementById('m_load').checked);
  p.set('pageFaults', document.getElementById('m_pgfault').checked);
  p.set('reclaim', document.getElementById('m_reclaim').checked);
  p.set('thermal', document.getElementById('m_thermal').checked);
  p.set('cpuFreq', document.getElementById('m_freq').checked);
  p.set('network', document.getElementById('m_network').checked);
  p.set('topProcs', document.getElementById('m_topprocs').checked);
  p.set('topMem', document.getElementById('m_topmem').checked ? 'true' : 'false');
  p.set('playback', document.getElementById('m_playback').checked ? 'true' : 'false');
  p.set('appPss', document.getElementById('m_pss').checked);
  p.set('appCpu', document.getElementById('m_appcpu').checked);
  p.set('mainCpu', document.getElementById('m_maincpu').checked);
  p.set('mediaCpu', document.getElementById('m_mediacpu').checked);
  document.getElementById('startBtn').disabled = true;
  fetch('/api/start', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: p.toString() })
    .then(function (r) { return r.json(); })
    .then(function (d) {
      if (d.ok) { window.location = '/dashboard?v=' + Date.now(); }
      else { msg.textContent = d.error || 'Failed to start'; document.getElementById('startBtn').disabled = false; }
    })
    .catch(function () { msg.textContent = 'Request failed'; document.getElementById('startBtn').disabled = false; });
}

// Device snapshot — one-shot STB capabilities capture → PDF with progress bar.
function takeSnapshot() {
  if (!selected) { document.getElementById('msg').textContent = 'Select a device first.'; return; }
  var btn = document.getElementById('snapBtn'); btn.disabled = true;
  var wrap = document.getElementById('snapProgWrap'); wrap.style.display = 'block';
  var bar = document.getElementById('snapProg'); var msg = document.getElementById('snapMsg');
  bar.style.background = '#4f46e5'; bar.style.width = '5%'; msg.textContent = 'Starting capture…';
  var p = new URLSearchParams(); p.set('serial', selected);
  fetch('/api/snapshot', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: p.toString() })
    .then(function (r) { return r.json(); })
    .then(function (d) {
      if (!d.ok) { msg.textContent = d.error || 'Failed to start'; btn.disabled = false; return; }
      pollSnapshot(btn, bar, msg);
    })
    .catch(function () { msg.textContent = 'Request failed'; btn.disabled = false; });
}
function pollSnapshot(btn, bar, msg) {
  fetch('/api/snapshot-status').then(function (r) { return r.json(); }).then(function (d) {
    bar.style.width = Math.max(5, d.progress) + '%';
    if (d.error) { bar.style.background = '#dc2626'; msg.textContent = 'Error: ' + d.error; btn.disabled = false; return; }
    if (d.done) {
      bar.style.width = '100%'; bar.style.background = '#16a34a';
      msg.innerHTML = '✓ Snapshot ready — <a href="/api/snapshot-report" target="_blank">open the interactive report</a>';
      btn.disabled = false;
      window.open('/api/snapshot-report', '_blank');   // open interactive HTML in a new tab
      return;
    }
    msg.textContent = 'Capturing device capabilities… ' + d.progress + '%';
    setTimeout(function () { pollSnapshot(btn, bar, msg); }, 600);
  }).catch(function () { setTimeout(function () { pollSnapshot(btn, bar, msg); }, 1000); });
}

// Procrank snapshot — one-shot per-process VSS/RSS/PSS/USS capture → interactive HTML.
function takeProcrank() {
  if (!selected) { document.getElementById('msg').textContent = 'Select a device first.'; return; }
  var btn = document.getElementById('procBtn'); btn.disabled = true;
  var wrap = document.getElementById('procProgWrap'); wrap.style.display = 'block';
  var bar = document.getElementById('procProg'); var msg = document.getElementById('procMsg');
  bar.style.background = '#0891b2'; bar.style.width = '5%'; msg.textContent = 'Starting capture…';
  var p = new URLSearchParams(); p.set('serial', selected);
  fetch('/api/procrank-snapshot', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: p.toString() })
    .then(function (r) { return r.json(); })
    .then(function (d) {
      if (!d.ok) { msg.textContent = d.error || 'Failed to start'; btn.disabled = false; return; }
      pollProcrank(btn, bar, msg);
    })
    .catch(function () { msg.textContent = 'Request failed'; btn.disabled = false; });
}
function pollProcrank(btn, bar, msg) {
  fetch('/api/procrank-status').then(function (r) { return r.json(); }).then(function (d) {
    bar.style.width = Math.max(5, d.progress) + '%';
    if (d.error) { bar.style.background = '#dc2626'; msg.textContent = 'Error: ' + d.error; btn.disabled = false; return; }
    if (d.done) {
      bar.style.width = '100%'; bar.style.background = '#16a34a';
      msg.innerHTML = '✓ Procrank ready — <a href="/api/procrank-report" target="_blank">open the interactive report</a>';
      btn.disabled = false;
      window.open('/api/procrank-report', '_blank');
      return;
    }
    msg.textContent = 'Capturing per-process memory… ' + d.progress + '%';
    setTimeout(function () { pollProcrank(btn, bar, msg); }, 600);
  }).catch(function () { setTimeout(function () { pollProcrank(btn, bar, msg); }, 1000); });
}

// Native folder picker (opens an OS dialog on the machine running the app).
function pickFolder() {
  var btn = document.getElementById('browseBtn');
  if (btn.disabled) return;
  btn.disabled = true;
  var orig = btn.innerHTML;
  btn.innerHTML = 'Opening…';
  var cur = encodeURIComponent(document.getElementById('outputDir').value || '');
  fetch('/api/pick-folder?initial=' + cur).then(function (r) { return r.json(); }).then(function (d) {
    btn.disabled = false; btn.innerHTML = orig;
    if (d && d.path) { document.getElementById('outputDir').value = d.path; }
    else if (d && d.supported === false) { alert('The folder picker isn’t available here — type the path manually.'); }
  }).catch(function () { btn.disabled = false; btn.innerHTML = orig; });
}

// Prefill the Reports folder with the server's default (writable) location.
fetch('/api/defaults').then(function (r) { return r.json(); }).then(function (d) {
  var el = document.getElementById('outputDir');
  if (el && d && d.reportsDir) { el.value = d.reportsDir; el.placeholder = d.reportsDir; }
  var b = document.getElementById('buildtag');
  if (b && d && d.build) { b.textContent = d.build; }
}).catch(function () {});

loadDevices();
setInterval(loadDevices, 5000);
</script>
</body>
</html>
"""
