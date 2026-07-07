package devicemonitor

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
  </div>
</div>
<div class="wrap">
  <div class="banner" id="banner">A monitoring session is in progress. <a href="/dashboard">Open the live dashboard &rarr;</a></div>

  <h2>1 &middot; Connected devices <span class="refresh" onclick="loadDevices()">&#8635; refresh</span></h2>
  <div class="devgrid" id="devices"><div class="empty">Scanning for adb devices&hellip;</div></div>

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
        <div class="mcheck"><input type="checkbox" id="m_cpu" checked>
          <span><span class="mcheck-label">CPU utilization %</span><span class="mcheck-cost">free · /proc/stat</span></span></div>
        <div class="mcheck"><input type="checkbox" id="m_cpuidle" checked>
          <span><span class="mcheck-label">CPU idle %</span><span class="mcheck-cost">free · /proc/stat</span></span></div>
        <div class="mcheck"><input type="checkbox" id="m_percore" checked>
          <span><span class="mcheck-label">Per-core CPU %</span><span class="mcheck-cost">free · /proc/stat cpuN</span></span></div>
        <div class="mcheck"><input type="checkbox" id="m_mem" checked>
          <span><span class="mcheck-label">Memory (MB)</span><span class="mcheck-cost">free · /proc/meminfo</span></span></div>
        <div class="mcheck"><input type="checkbox" id="m_mempct" checked>
          <span><span class="mcheck-label">Memory used %</span><span class="mcheck-cost">free · /proc/meminfo</span></span></div>
        <div class="mcheck"><input type="checkbox" id="m_swap" checked>
          <span><span class="mcheck-label">Swap used (MB)</span><span class="mcheck-cost">free · /proc/meminfo</span></span></div>
        <div class="mcheck"><input type="checkbox" id="m_zone" checked>
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
        <div class="mcheck"><input type="checkbox" id="m_reclaim" checked onchange="if(this.checked){var l=document.getElementById('logcat');if(l)l.checked=true;}">
          <span><span class="mcheck-label">Direct reclaim</span><span class="mcheck-cost">logcat · memory-pressure events/s</span></span></div>
        <div class="mcheck"><input type="checkbox" id="m_thermal">
          <span><span class="mcheck-label">CPU temperature</span><span class="mcheck-cost">low cost · /sys/class/thermal/*</span></span></div>
        <div class="mcheck"><input type="checkbox" id="m_freq">
          <span><span class="mcheck-label">CPU frequency</span><span class="mcheck-cost">low cost · scaling_cur_freq</span></span></div>
        <div class="mcheck"><input type="checkbox" id="m_network">
          <span><span class="mcheck-label">Network throughput</span><span class="mcheck-cost">low cost · /proc/net/dev</span></span></div>
        <div class="mcheck"><input type="checkbox" id="m_topprocs" checked>
          <span><span class="mcheck-label">Top process CPU</span><span class="mcheck-cost">medium · reads /proc/*/stat per tick</span></span></div>
        <div class="mcheck"><input type="checkbox" id="m_playback" onchange="if(this.checked){var l=document.getElementById('logcat');if(l)l.checked=true;}">
          <span><span class="mcheck-label">Playback &amp; tunnel (STB)</span><span class="mcheck-cost">logcat · video state + TUNNELED</span></span></div>
      </div>
      <div class="mgroup-title">App metrics <span class="mgroup-hint">require an app package above</span></div>
      <div class="metrics-grid">
        <div class="mcheck"><input type="checkbox" id="m_pss" checked>
          <span><span class="mcheck-label">App PSS breakdown</span><span class="mcheck-cost">medium · dumpsys meminfo</span></span></div>
        <div class="mcheck"><input type="checkbox" id="m_appcpu" checked>
          <span><span class="mcheck-label">App process CPU</span><span class="mcheck-cost">low · /proc + dumpsys fallback</span></span></div>
        <div class="mcheck"><input type="checkbox" id="m_maincpu" checked>
          <span><span class="mcheck-label">App main-thread CPU</span><span class="mcheck-cost">low · /proc task stat</span></span></div>
        <div class="mcheck"><input type="checkbox" id="m_mediacpu" checked>
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

function loadDevices() {
  fetch('/api/devices').then(function (r) { return r.json(); }).then(function (d) {
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
  p.set('playback', document.getElementById('m_playback').checked ? 'true' : 'false');
  p.set('appPss', document.getElementById('m_pss').checked);
  p.set('appCpu', document.getElementById('m_appcpu').checked);
  p.set('mainCpu', document.getElementById('m_maincpu').checked);
  p.set('mediaCpu', document.getElementById('m_mediacpu').checked);
  document.getElementById('startBtn').disabled = true;
  fetch('/api/start', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: p.toString() })
    .then(function (r) { return r.json(); })
    .then(function (d) {
      if (d.ok) { window.location = '/dashboard'; }
      else { msg.textContent = d.error || 'Failed to start'; document.getElementById('startBtn').disabled = false; }
    })
    .catch(function () { msg.textContent = 'Request failed'; document.getElementById('startBtn').disabled = false; });
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

internal val DASHBOARD_PAGE = """<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<title>ADB Performance Studio — live</title>
<script src="https://cdn.jsdelivr.net/npm/chart.js@4.4.3/dist/chart.umd.min.js"></script>
<style>
  body { font-family: -apple-system, 'Segoe UI', Roboto, sans-serif; margin: 0; background: #f4f5f7; color: #1f2430; }
  header { background: linear-gradient(135deg, #111827, #1e3a5f); color: #fff; padding: 12px 20px; display: flex; align-items: center; gap: 16px; flex-wrap: wrap; }
  header h1 { font-size: 16px; margin: 0; }
  header .sub { font-size: 12px; color: #cbd5e1; flex: 1; }
  #status { font-weight: 700; font-size: 13px; }
  .running { color: #4ade80; } .finished { color: #fbbf24; } .failed { color: #f87171; } .stopping { color: #fbbf24; }
  header a, header button { font-size: 12px; border-radius: 6px; padding: 6px 12px; border: 0; cursor: pointer; text-decoration: none; }
  #stopBtn { background: #b91c1c; color: #fff; }
  #stopBtn.busy { background: #9ca3af; color: #fff; cursor: default; opacity: .9; }
  .spinner { display: inline-block; width: 11px; height: 11px; margin-right: 4px; border: 2px solid rgba(255,255,255,.45); border-top-color: #fff; border-radius: 50%; animation: spin .7s linear infinite; vertical-align: -1px; }
  @keyframes spin { to { transform: rotate(360deg); } }
  #reportBtn { background: #15803d; color: #fff; display: none; }
  .home { background: #374151; color: #e5e7eb; }
  #badges { display: flex; gap: 10px; padding: 12px 20px 0 20px; flex-wrap: wrap; }
  .hb { background: #fff; border: 1px solid #e2e4e8; border-radius: 8px; padding: 8px 14px; min-width: 130px; }
  .hb .k { font-size: 11px; color: #6b7280; }
  .hb .v { font-size: 14px; font-weight: 700; margin-top: 2px; }
  .hb.ok .v { color: #15803d; } .hb.warn .v { color: #b45309; } .hb.bad .v { color: #b91c1c; }
  .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; padding: 14px 20px; }
  .card { background: #fff; border: 1px solid #e2e4e8; border-radius: 8px; padding: 10px 12px; }
  .card h2 { font-size: 13px; margin: 0 0 6px 0; color: #374151; }
  .card .chartbox { position: relative; height: 200px; }
  .chart-stats { border-top: 1px solid #f0f0f3; margin-top: 6px; padding-top: 5px; display: flex; flex-wrap: wrap; gap: 2px 0; }
  .srow { display: flex; gap: 10px; align-items: baseline; width: 100%; font-size: 11px; color: #374151; line-height: 1.6; }
  .sname { color: #6b7280; min-width: 70px; max-width: 110px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 10px; }
  .sval { font-family: ui-monospace, Menlo, monospace; font-size: 11px; }
  .sval .sl { color: #9aa1ab; font-size: 9px; margin-right: 1px; text-transform: uppercase; }
  #events { margin: 0 20px 24px 20px; }
  #events h2 { font-size: 13px; color: #374151; }
  #events table { width: 100%; border-collapse: collapse; font-size: 12px; background: #fff; border: 1px solid #e2e4e8; }
  #events th, #events td { text-align: left; padding: 5px 10px; border-bottom: 1px solid #eef0f3; }
  #events td:last-child { white-space: normal; word-break: break-word; overflow-wrap: anywhere; font-family: ui-monospace, Menlo, monospace; font-size: 11px; color: #374151; }
  #events th { background: #e9ebf0; }
  .tag { display: inline-block; padding: 1px 7px; border-radius: 9px; font-size: 11px; color: #fff; }
  .tag.anom { background: #ea7317; } .tag.zap { background: #c0392b; }
</style>
</head>
<body>
<header>
  <h1>Live run</h1>
  <span id="status" class="running">&#9679; live</span>
  <div class="sub" id="device">connecting&hellip;</div>
  <a class="home" href="/">&#8962; Home</a>
  <button id="stopBtn" onclick="stopRun()">&#9632; Stop run</button>
  <a id="reportBtn" href="/api/report">&#8681; Download PDF report</a>
</header>
<div id="badges"></div>
<div class="grid" id="grid"></div>
<div id="proc-section" style="margin:0 20px 20px;display:none">
  <h2 style="font-size:14px;font-weight:600;color:#374151;margin:0 0 10px">&#129424; Top Process CPU %
    <span style="font-size:11px;font-weight:400;color:#6b7280">&nbsp;&middot;&nbsp;check a process to add it to the chart</span>
  </h2>
  <div style="display:grid;grid-template-columns:230px 1fr;gap:14px">
    <div style="background:#fff;border:1px solid #e2e4e8;border-radius:10px;padding:12px 14px;overflow-y:auto;max-height:270px">
      <div style="font-size:11px;color:#6b7280;margin-bottom:6px;font-weight:600;letter-spacing:.04em">PEAK CPU %</div>
      <div id="proc-list"><span style="font-size:12px;color:#9aa1ab">Waiting for data&hellip;</span></div>
    </div>
    <div class="card" style="padding:12px 14px">
      <div class="chartbox" style="height:230px"><canvas id="c-procs"></canvas></div>
      <div id="stats-procs" class="chart-stats"></div>
    </div>
  </div>
</div>
<div id="playback-section" style="margin:0 20px 20px;display:none">
  <h2 style="font-size:14px;font-weight:600;color:#374151;margin:0 0 10px">&#128250; Playback &amp; Tunnel Mode (STB)
    <span style="font-size:11px;font-weight:400;color:#6b7280">&nbsp;&middot;&nbsp;video player state &amp; TUNNELED/NON-TUNNELED from logcat</span>
  </h2>
  <div id="pb-buildbanner" style="display:none;margin-bottom:8px;padding:10px 14px;border-radius:8px;font-size:13px;font-weight:600"></div>
  <div id="pb-lifecycleflag" style="display:none;margin-bottom:12px;padding:10px 14px;border-radius:8px;font-size:13px;font-weight:600"></div>
  <div id="pb-badges" style="display:flex;gap:10px;margin-bottom:12px;flex-wrap:wrap"></div>
  <div style="display:grid;grid-template-columns:1fr;gap:14px">
    <div class="card" style="padding:12px 14px">
      <h2 style="font-size:13px">Video player state <span style="font-size:11px;font-weight:400;color:#6b7280">&nbsp;&middot;&nbsp;raw mk-player-lifecycle events</span></h2>
      <div class="chartbox" style="height:320px"><canvas id="c-video"></canvas></div>
    </div>
    <div class="card" style="padding:12px 14px">
      <h2 style="font-size:13px">Tunnel mode</h2>
      <div class="chartbox" style="height:170px"><canvas id="c-tunnel"></canvas></div>
    </div>
  </div>
</div>
<div id="events">
  <h2>Events (zaps &amp; anomalies)</h2>
  <table><thead><tr><th style="width:70px">t</th><th style="width:140px">kind</th><th>detail</th></tr></thead><tbody id="evbody"></tbody></table>
</div>
<script>
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
var COLORS = ['#1f77b4', '#d62728', '#2ca02c', '#ff7f0e', '#9467bd', '#8c564b', '#17becf', '#7f7f7f'];
var PROC_COLORS = ['#6366f1','#f59e0b','#10b981','#ef4444','#3b82f6','#8b5cf6','#ec4899','#14b8a6','#f97316','#84cc16'];
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
  var grid = document.getElementById('grid');
  DEFS.forEach(function (def) {
    var card = document.createElement('div');
    card.className = 'card';
    card.id = 'card-' + def.id;
    card.innerHTML = '<h2>' + def.title + '</h2><div class="chartbox"><canvas id="c-' + def.id + '"></canvas></div>'
      + '<div id="stats-' + def.id + '" class="chart-stats"></div>';
    grid.appendChild(card);
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
  var ctx = document.getElementById('c-procs');
  if (!ctx || procChart) return;
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
    el.style.background = '#dcfce7'; el.style.color = '#166534'; el.style.border = '1px solid #86efac';
  } else if (buildTunnel === false) {
    el.textContent = '✗ Tunnel mode DISABLED in build (isTunneledPlaybackEnabled=false)';
    el.style.background = '#fee2e2'; el.style.color = '#991b1b'; el.style.border = '1px solid #fca5a5';
  } else {
    el.textContent = '— Build tunnel flag not yet detected (play a video; reads isTunneledPlaybackEnabled from player config)';
    el.style.background = '#f3f4f6'; el.style.color = '#6b7280'; el.style.border = '1px solid #e5e7eb';
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
    if (playing) { el.style.background = '#dbeafe'; el.style.color = '#1e40af'; el.style.border = '1px solid #93c5fd'; }
    else { el.style.background = '#f3f4f6'; el.style.color = '#6b7280'; el.style.border = '1px solid #e5e7eb'; }
  } else {
    el.textContent = '▶ Lifecycle state: none yet (start a video)';
    el.style.background = '#f3f4f6'; el.style.color = '#6b7280'; el.style.border = '1px solid #e5e7eb';
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

function pushSamples(list) {
  list.forEach(function (s) {
    allSamples.push(s);
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
  // Push top-process data
  if (hasTopProcs) pushProcSamples(list);
  // Push playback (STB) data
  if (hasPlayback) pushPlaybackSamples(list);
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
  Object.keys(charts).forEach(function (k) { charts[k].update('none'); });
  renderStats();
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
  // Standard metric charts (DEFS)
  DEFS.forEach(function (def) {
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
  });
  // Proc chart
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
}

function renderEvents(zaps, anomalies) {
  var rows = [];
  zaps.forEach(function (t) { rows.push({ t: t, html: '<span class="tag zap">zap</span>', detail: 'KEYCODE_CHANNEL_UP sent' }); });
  anomalies.forEach(function (a) {
    rows.push({ t: (a.t === null ? -1 : a.t), html: '<span class="tag anom">' + a.type + '</span>', detail: a.detail });
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
function tick() {
  fetch('/api/session?since=' + idx).then(function (r) { return r.json(); }).then(function (d) {
    if (d.status === 'idle') { window.location = '/'; return; }
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
      // Top-process feature
      if (d.hasTopProcs === true) {
        hasTopProcs = true;
        var ps = document.getElementById('proc-section');
        if (ps) ps.style.display = 'block';
        initProcChart();
      }
      // Playback (STB) feature
      if (d.hasPlayback === true) {
        hasPlayback = true;
        var pbs = document.getElementById('playback-section');
        if (pbs) pbs.style.display = 'block';
        initPlaybackCharts();
      }
    }
    idx = d.nextIndex;
    if (d.samples.length) pushSamples(d.samples);
    if (hasPlayback) renderBuildBanner(d.buildTunnel);
    renderEvents(d.zaps, d.anomalies);
    renderBadges(d.anomalies);
    var st = document.getElementById('status');
    if (d.status === 'done' || d.status === 'failed') {
      st.textContent = d.status === 'done' ? '\u25a0 finished' : '\u26a0 ' + (d.error || 'failed');
      st.className = d.status === 'done' ? 'finished' : 'failed';
      document.getElementById('stopBtn').style.display = 'none';
      if (d.status === 'done') document.getElementById('reportBtn').style.display = 'inline-block';
    } else {
      setTimeout(tick, Math.max(1000, d.intervalSec * 1000 / 2));
    }
  }).catch(function () { setTimeout(tick, 2000); });
}

buildCards();
tick();
</script>
</body>
</html>
"""

internal val COMPARE_PAGE = """<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<title>Run Comparison — ADB Performance Studio</title>
<script src="https://cdn.jsdelivr.net/npm/chart.js@4.4.3/dist/chart.umd.min.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/jspdf/2.5.1/jspdf.umd.min.js"></script>
<style>
  * { box-sizing: border-box; }
  body { font-family: -apple-system, 'Segoe UI', Roboto, sans-serif; margin: 0; background: #f4f5f7; color: #1f2430; }
  .hero { background: linear-gradient(135deg, #111827 0%, #1e3a5f 60%, #0e7490 100%); color: #fff; padding: 20px 28px; }
  .hero h1 { margin: 0; font-size: 20px; }
  .hero p { margin: 5px 0 0 0; color: #b8c4d4; font-size: 13px; }
  .wrap { max-width: 1060px; margin: 0 auto; padding: 20px 24px 40px 24px; }
  .panel { background: #fff; border: 1px solid #e2e4e8; border-radius: 10px; padding: 18px; margin-bottom: 20px; }
  .row { display: flex; gap: 20px; flex-wrap: wrap; align-items: flex-end; }
  .field { display: flex; flex-direction: column; gap: 5px; }
  .field label { font-size: 12px; color: #4b5563; font-weight: 600; }
  select { padding: 7px 10px; border: 1px solid #cdd2da; border-radius: 6px; font-size: 13px; min-width: 280px; }
  button.cmp { background: linear-gradient(135deg, #0e7490, #1e40af); border: 0; color: #fff; font-size: 14px; font-weight: 700;
               padding: 10px 28px; border-radius: 8px; cursor: pointer; }
  button.cmp:disabled { background: #9aa1ab; cursor: not-allowed; }
  button.pdf { background: linear-gradient(135deg, #15803d, #166534); border: 0; color: #fff; font-size: 13px; font-weight: 600;
               padding: 9px 22px; border-radius: 8px; cursor: pointer; }
  button.pdf:disabled { background: #9aa1ab; cursor: wait; }
  #err { color: #b91c1c; font-size: 13px; margin-top: 8px; }
  .chartgrid { display: grid; grid-template-columns: 1fr; gap: 20px; }
  .ccard { background: #fff; border: 1px solid #e2e4e8; border-radius: 8px; padding: 14px 16px; }
  .ccard h3 { font-size: 13px; margin: 0 0 10px 0; color: #374151; }
  .ccard .chartbox { position: relative; height: 220px; }
  .legend { display: flex; gap: 20px; margin-bottom: 10px; font-size: 12px; align-items: center; flex-wrap: wrap; }
  .dot { display: inline-block; width: 12px; height: 3px; border-radius: 2px; margin-right: 5px; vertical-align: middle; }
  .tip { background: #eff6ff; border: 1px solid #bfdbfe; border-radius: 8px; padding: 12px 16px; font-size: 13px; color: #1e40af; margin-bottom: 16px; }
  a.back { color: #0e7490; font-size: 13px; text-decoration: none; }
  a.back:hover { text-decoration: underline; }
  #loading { display: none; color: #6b7280; font-size: 13px; margin-top: 8px; }
</style>
</head>
<body>
<div class="hero">
  <h1>&#9654;&#9654; Run Comparison</h1>
  <p>Compare two runs side-by-side — App CPU, App PSS, Media Process CPU, CPU Pressure, Jank.</p>
</div>
<div class="wrap">
  <a class="back" href="/">&#8592; Back to home</a>

  <div class="panel" style="margin-top:16px">
    <div class="row">
      <div class="field">
        <label>Run A (e.g. Tunnel OFF / Baseline)</label>
        <select id="runA"><option value="">Loading runs...</option></select>
      </div>
      <div class="field">
        <label>Run B (e.g. Tunnel ON)</label>
        <select id="runB"><option value="">Loading runs...</option></select>
      </div>
      <button class="cmp" id="cmpBtn" onclick="doCompare()" disabled>Compare &rarr;</button>
    </div>
    <div id="err"></div>
    <div id="loading">Loading CSV data...</div>
  </div>

  <div class="tip" id="tunnelTip" style="display:none">
    <strong>Tunnel mode notes:</strong>
    App PSS decrease in tunnel mode is expected (video frames bypass the app render path).
    Watch for CPU PSI increases — this may indicate work shifting to media/HAL processes.
    Compare <em>media.codec</em> and <em>audioserver</em> CPU to confirm.
  </div>

  <div class="legend" id="legend" style="display:none">
    <span><span class="dot" style="background:#1f77b4;height:3px;display:inline-block;width:24px"></span> Run A</span>
    <span><span class="dot" style="background:#d62728;height:3px;display:inline-block;width:24px"></span> Run B</span>
    <span><span style="display:inline-block;width:24px;height:0;border-top:2px dashed #16a34a;margin-right:5px;vertical-align:middle"></span> Android target</span>
  </div>
  <div id="exportBar" style="display:none;margin-bottom:10px">
    <button class="pdf" id="exportBtn" onclick="exportPdf()">&#8681; Export as PDF</button>
  </div>

  <div class="chartgrid" id="charts"></div>
</div>
<script>
var COLOR_A = '#1f77b4';
var COLOR_B = '#d62728';
var COLOR_TARGET = '#16a34a';
var chartInsts = [];
var chartTitles = [];

function parseCsv(text) {
  var lines = text.trim().split('\n');
  if (!lines.length) return [];
  var headers = lines[0].split(',').map(function (h) { return h.trim(); });
  return lines.slice(1).map(function (line) {
    var vals = line.split(',');
    var obj = {};
    headers.forEach(function (h, i) {
      var v = parseFloat(vals[i]);
      obj[h] = isNaN(v) ? null : v;
    });
    return obj;
  });
}

function colSeries(rows, col) {
  return rows.filter(function (r) { return r[col] !== null; })
             .map(function (r) { return { x: r['elapsed_sec'], y: r[col] }; });
}

function hasData(rows, col) {
  return rows.some(function (r) { return r[col] !== null && r[col] !== undefined; });
}

function makeLegend(labelA, labelB) {
  document.getElementById('legend').style.display = 'flex';
  document.getElementById('legend').innerHTML =
    '<span style="font-size:12px"><span style="display:inline-block;width:24px;height:3px;background:' + COLOR_A + ';margin-right:5px;vertical-align:middle"></span>' + escHtml(labelA) + '</span>' +
    '<span style="font-size:12px"><span style="display:inline-block;width:24px;height:3px;background:' + COLOR_B + ';margin-right:5px;vertical-align:middle;opacity:.7"></span>' + escHtml(labelB) + '</span>';
}

function makeChart(title, yLabel, seriesA, seriesB, labelA, labelB, baseline) {
  var div = document.createElement('div');
  div.className = 'ccard';
  var canvasId = 'cc' + chartInsts.length;
  div.innerHTML = '<h3>' + title + '</h3><div class="chartbox"><canvas id="' + canvasId + '"></canvas></div>';
  document.getElementById('charts').appendChild(div);
  var datasets = [
    { label: labelA, data: seriesA, borderColor: COLOR_A, borderWidth: 2, pointRadius: 0, tension: 0.15 },
    { label: labelB, data: seriesB, borderColor: COLOR_B, borderWidth: 2, pointRadius: 0, tension: 0.15, borderDash: [5, 3] }
  ];
  // Android target reference line — anchored to actual data x-range so the
  // axis auto-scales identically to the real series (no distortion).
  if (baseline) {
    var allPts = seriesA.concat(seriesB);
    var minX = allPts.length ? Math.min.apply(null, allPts.map(function(p) { return p.x; })) : 0;
    var maxX = allPts.length ? Math.max.apply(null, allPts.map(function(p) { return p.x; })) : 300;
    datasets.push({ label: 'Android target: ' + baseline.label,
      data: [{ x: minX, y: baseline.value }, { x: maxX, y: baseline.value }],
      borderColor: COLOR_TARGET, borderWidth: 1.5, borderDash: [7, 4],
      pointRadius: 0, tension: 0, fill: false });
  }
  var chart = new Chart(document.getElementById(canvasId), {
    type: 'line',
    data: { datasets: datasets },
    options: {
      animation: false, responsive: true, maintainAspectRatio: false,
      plugins: { legend: { labels: { boxWidth: 14, font: { size: 11 } } } },
      scales: {
        x: { type: 'linear', title: { display: true, text: 'time (s)' } },
        y: { beginAtZero: true, title: { display: true, text: yLabel } }
      }
    }
  });
  chartInsts.push(chart);
  chartTitles.push(title);
}

function escHtml(s) { var d = document.createElement('div'); d.textContent = String(s); return d.innerHTML; }

function renderComparison(rowsA, rowsB, labelA, labelB) {
  chartInsts.forEach(function (c) { c.destroy(); });
  chartInsts = [];
  chartTitles = [];
  document.getElementById('charts').innerHTML = '';
  document.getElementById('exportBar').style.display = 'none';
  document.getElementById('tunnelTip').style.display = 'block';
  makeLegend(labelA, labelB);

  var metrics = [
    { col: 'app_cpu_pct',           title: 'App Process CPU (all threads) %',    y: '%' },
    { col: 'main_thread_cpu_pct',   title: 'App Main-Thread CPU %',              y: '%' },
    { col: 'pss_total_mb',          title: 'App PSS Total (MB)',                  y: 'MB' },
    { col: 'cpu_psi_some_avg10',    title: 'CPU Pressure PSI avg10 (%)',          y: '% stalled',
      baseline: { value: 0, label: '0 (no stall)' } },
    { col: 'mem_psi_some_avg10',    title: 'Memory Pressure PSI some avg10 (%)',  y: '% stalled',
      baseline: { value: 0, label: '0 (no stall)' } },
    { col: 'jank_pct',              title: 'UI Jank (% janky frames)',            y: '%',
      baseline: { value: 0, label: '0% (zero dropped frames)' } },
    { col: 'media_mediaserver_cpu', title: 'Media: mediaserver CPU %',            y: '%' },
    { col: 'media_codec_cpu',       title: 'Media: media.codec CPU %',            y: '%' },
    { col: 'media_audioserver_cpu', title: 'Media: audioserver CPU %',            y: '%' },
    { col: 'media_swcodec_cpu',     title: 'Media: vendor.mediaswcodec CPU %',    y: '%' },
    { col: 'media_omx_cpu',         title: 'Media: OMX HAL CPU %',                y: '%' },
    { col: 'cpu_util_pct',          title: 'System CPU Utilization %',            y: '%' }
  ];

  var shown = 0;
  metrics.forEach(function (m) {
    var dA = hasData(rowsA, m.col), dB = hasData(rowsB, m.col);
    if (!dA && !dB) return;
    shown++;
    makeChart(m.title, m.y,
      dA ? colSeries(rowsA, m.col) : [],
      dB ? colSeries(rowsB, m.col) : [],
      labelA, labelB, m.baseline || null);
  });
  if (!shown) {
    document.getElementById('charts').innerHTML = '<div style="padding:20px;color:#6b7280;font-size:13px">No common metrics found between the two runs. Make sure both runs used --package (or set App package in the web UI).</div>';
  } else {
    document.getElementById('exportBar').style.display = 'block';
  }
}

function doCompare() {
  var fA = document.getElementById('runA').value;
  var fB = document.getElementById('runB').value;
  var err = document.getElementById('err');
  err.textContent = '';
  if (!fA || !fB) { err.textContent = 'Please select both runs.'; return; }
  if (fA === fB) { err.textContent = 'Select two different runs.'; return; }
  document.getElementById('cmpBtn').disabled = true;
  document.getElementById('loading').style.display = 'block';
  document.getElementById('tunnelTip').style.display = 'none';
  document.getElementById('legend').style.display = 'none';

  var labelA = document.getElementById('runA').options[document.getElementById('runA').selectedIndex].text;
  var labelB = document.getElementById('runB').options[document.getElementById('runB').selectedIndex].text;

  var pA = fetch('/api/runcsv?folder=' + encodeURIComponent(fA)).then(function (r) { return r.text(); });
  var pB = fetch('/api/runcsv?folder=' + encodeURIComponent(fB)).then(function (r) { return r.text(); });
  Promise.all([pA, pB]).then(function (texts) {
    document.getElementById('loading').style.display = 'none';
    document.getElementById('cmpBtn').disabled = false;
    var rowsA = parseCsv(texts[0]);
    var rowsB = parseCsv(texts[1]);
    renderComparison(rowsA, rowsB, labelA, labelB);
  }).catch(function (e) {
    document.getElementById('loading').style.display = 'none';
    document.getElementById('cmpBtn').disabled = false;
    err.textContent = 'Failed to load CSV data: ' + e;
  });
}

function exportPdf() {
  if (!chartInsts.length) return;
  var btn = document.getElementById('exportBtn');
  btn.disabled = true;
  btn.textContent = 'Generating PDF...';

  // Use a short timeout so the button state renders before the (synchronous) PDF work starts.
  setTimeout(function () {
    try {
      var jsPDF = window.jspdf && window.jspdf.jsPDF;
      if (!jsPDF) { alert('jsPDF not loaded yet — try again in a moment.'); return; }
      var doc = new jsPDF({ orientation: 'p', unit: 'mm', format: 'a4' });
      var pgW = 210, margin = 14;
      var imgW = pgW - 2 * margin;
      var imgH = Math.round(imgW * 0.48); // ~87mm — fits 2 charts per A4 page

      // Cover page
      var labelA = document.getElementById('runA').options[document.getElementById('runA').selectedIndex].text;
      var labelB = document.getElementById('runB').options[document.getElementById('runB').selectedIndex].text;
      doc.setFontSize(18); doc.setFont(undefined, 'bold');
      doc.text('Run Comparison', margin, 32);
      doc.setFontSize(11); doc.setFont(undefined, 'normal');
      doc.text('Run A: ' + labelA, margin, 50);
      doc.text('Run B: ' + labelB, margin, 59);
      doc.setFontSize(9); doc.setTextColor(100);
      doc.text('Green dashed lines show Android performance targets (Google Vitals / PSI spec).', margin, 74);
      doc.text('Generated by ADB Performance Studio  —  ' + new Date().toLocaleString(), margin, 82);
      doc.setTextColor(0);

      // Charts — 2 per A4 page with title above each
      var gapBetween = 12;
      var titleH = 6;
      var slotH = titleH + imgH + gapBetween;

      chartInsts.forEach(function (chart, idx) {
        var posInPage = idx % 2;
        if (posInPage === 0) doc.addPage();
        var yTitle = margin + posInPage * slotH;
        var yImg = yTitle + titleH;
        doc.setFontSize(9); doc.setFont(undefined, 'bold');
        doc.text(chartTitles[idx] || ('Chart ' + (idx + 1)), margin, yTitle);
        doc.setFont(undefined, 'normal');
        var dataUrl = chart.canvas.toDataURL('image/png', 1.0);
        doc.addImage(dataUrl, 'PNG', margin, yImg, imgW, imgH);
      });

      doc.save('tunnel-comparison.pdf');
    } catch (e) {
      alert('PDF export failed: ' + e);
    } finally {
      btn.disabled = false;
      btn.textContent = '⬇ Export as PDF';
    }
  }, 50);
}

function loadRuns() {
  fetch('/api/runs').then(function (r) { return r.json(); }).then(function (d) {
    var selA = document.getElementById('runA');
    var selB = document.getElementById('runB');
    selA.innerHTML = '<option value="">-- select run A --</option>';
    selB.innerHTML = '<option value="">-- select run B --</option>';
    if (!d.runs.length) {
      selA.innerHTML = '<option value="">No runs found yet</option>';
      selB.innerHTML = '<option value="">No runs found yet</option>';
      return;
    }
    d.runs.forEach(function (r) {
      var label = r.label ? r.folder + '  [' + r.label + ']' : r.folder;
      var optA = new Option(label, r.folder);
      var optB = new Option(label, r.folder);
      selA.appendChild(optA);
      selB.appendChild(optB);
    });
    // Auto-select first two if available
    if (d.runs.length >= 1) selA.value = d.runs[0].folder;
    if (d.runs.length >= 2) selB.value = d.runs[1].folder;
    document.getElementById('cmpBtn').disabled = false;
  }).catch(function () {});
}

loadRuns();
</script>
</body>
</html>
"""
