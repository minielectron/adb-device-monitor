package devicemonitor.presentation.report

import com.google.gson.GsonBuilder
import devicemonitor.data.storage.ProjectRecord
import devicemonitor.data.storage.RunManifest
import devicemonitor.domain.Sample
import devicemonitor.domain.releaseguard.ReleaseScenario
import devicemonitor.presentation.web.STUDIO_UI_CSS
import java.io.File

/** Writes a portable, server-free copy of the Live Dashboard for one complete scenario run. */
internal object InteractivePerformanceReport {
    private val gson = GsonBuilder().create()

    fun write(
        file: File,
        project: ProjectRecord,
        scenario: ReleaseScenario,
        manifest: RunManifest,
        evidence: ScenarioRunEvidence
    ) {
        val payload = linkedMapOf<String, Any?>(
            "project" to project.name,
            "packageName" to scenario.packageName,
            "scenario" to scenario.name,
            "revision" to scenario.revision,
            "runId" to manifest.runId,
            "startedAt" to manifest.startedAt,
            "updatedAt" to manifest.updatedAt,
            "verdict" to manifest.evaluation?.verdict?.name,
            "device" to linkedMapOf(
                "model" to manifest.device?.model,
                "androidVersion" to manifest.device?.androidVersion,
                "firmware" to manifest.device?.firmware
            ),
            "attempts" to manifest.attempts.size,
            "samples" to evidence.samples.map(::sample),
            "markers" to evidence.markers.map { marker ->
                linkedMapOf(
                    "t" to finite(marker.elapsedSec),
                    "attempt" to marker.attempt,
                    "step" to marker.stepIndex,
                    "label" to marker.label,
                    "section" to marker.section,
                    "type" to marker.type.name.lowercase(),
                    "outcome" to marker.outcome.name.lowercase(),
                    "detail" to marker.detail
                )
            },
            "anomalies" to evidence.anomalies.map { anomaly ->
                linkedMapOf(
                    "time" to anomaly.deviceTime,
                    "t" to finite(anomaly.elapsedSec),
                    "type" to anomaly.type,
                    "detail" to anomaly.detail
                )
            }
        )
        val data = gson.toJson(payload)
        val chartRuntime = requireNotNull(javaClass.classLoader.getResourceAsStream("web/chart.umd.js")) {
            "Bundled Chart.js runtime is missing"
        }.bufferedReader().use { it.readText() }
            .replace(Regex("(?m)^//# sourceMappingURL=.*$"), "")

        file.writeText(
            """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Interactive performance · ${escapeHtml(scenario.name)}</title>
$STUDIO_UI_CSS
<style>
  .report-context { min-width: 0; flex: 1; }
  .report-shell { padding-top: 30px; }
  .report-lead { display:flex; align-items:flex-end; justify-content:space-between; gap:22px; margin-bottom:22px; }
  .report-lead h1 { margin:0; font-size:clamp(30px,4vw,48px); line-height:1; letter-spacing:-.045em; }
  .report-lead-copy { max-width:680px; margin:10px 0 0; color:var(--muted); font-size:12px; line-height:1.6; }
  .health-panel,.controls,.events-panel { margin-bottom:28px; padding:18px; }
  .facts { display:grid; grid-template-columns:repeat(auto-fit,minmax(145px,1fr)); gap:9px; }
  .fact { min-width:0; padding:12px 13px; border:1px solid var(--line); border-radius:12px; background:rgba(255,255,255,.025); }
  .fact .k { color:var(--quiet); font-size:9px; font-weight:800; letter-spacing:.1em; text-transform:uppercase; }
  .fact .v { margin-top:5px; overflow:hidden; color:var(--text); font-size:13px; font-weight:750; text-overflow:ellipsis; white-space:nowrap; }
  .fact.ok .v { color:var(--green); } .fact.warn .v { color:var(--amber); } .fact.bad .v { color:var(--red); }
  .control-grid { display:grid; grid-template-columns:minmax(220px,1fr) auto; align-items:end; gap:14px; }
  .field label { display:block; margin-bottom:7px; color:var(--quiet); font-size:9px; font-weight:800; letter-spacing:.1em; text-transform:uppercase; }
  .field input[type=search] { width:100%; min-height:39px; padding:0 12px; border:1px solid var(--line); border-radius:10px; outline:0; color:var(--text); background:var(--surface-soft); }
  .field input[type=search]:focus { border-color:rgba(232,182,109,.55); box-shadow:0 0 0 3px rgba(232,182,109,.08); }
  .timeline-actions { display:flex; align-items:center; justify-content:flex-end; gap:7px; }
  .timeline-actions .studio-button { min-width:38px; padding-inline:10px; }
  .timeline-wrap { margin-top:14px; padding:12px; border:1px solid var(--line); border-radius:13px; background:rgba(0,0,0,.2); }
  .timeline-head { display:flex; align-items:center; justify-content:space-between; gap:12px; margin-bottom:9px; }
  .timeline-label { color:var(--quiet); font-size:9px; font-weight:800; letter-spacing:.1em; text-transform:uppercase; }
  .timeline-readout { color:var(--muted); font:10px/1.3 ui-monospace,Menlo,monospace; }
  .navigator { position:relative; height:76px; overflow:hidden; border:1px solid var(--line); border-radius:10px; background:#0b0c0c; touch-action:none; user-select:none; }
  .navigator canvas { position:absolute; inset:0 0 18px; width:100%; height:58px; }
  .nav-shade { position:absolute; top:0; bottom:18px; background:rgba(3,3,3,.62); pointer-events:none; }
  .nav-shade.left { left:0; } .nav-shade.right { right:0; }
  .nav-window { position:absolute; top:0; bottom:18px; min-width:8px; border:1px solid rgba(232,182,109,.75); border-radius:5px; background:rgba(232,182,109,.08); box-shadow:0 0 0 1px rgba(0,0,0,.55),inset 0 0 24px rgba(232,182,109,.05); cursor:grab; }
  .nav-window.dragging { cursor:grabbing; }
  .nav-handle { position:absolute; top:0; bottom:0; width:12px; border:0; padding:0; background:transparent; cursor:ew-resize; }
  .nav-handle:after { content:""; position:absolute; top:50%; left:50%; width:3px; height:22px; border-radius:3px; background:var(--gold-bright); box-shadow:0 0 0 1px rgba(0,0,0,.65); transform:translate(-50%,-50%); }
  .nav-handle.start { left:-6px; } .nav-handle.end { right:-6px; }
  .event-rail { position:absolute; left:0; right:0; bottom:0; height:18px; border-top:1px solid var(--line); background:rgba(255,255,255,.018); }
  .event-cluster { position:absolute; top:5px; width:5px; height:8px; padding:0; border:0; border-radius:5px; background:var(--violet); transform:translateX(-50%); cursor:help; }
  .event-cluster.anomaly { background:var(--red); } .event-cluster.failed { background:var(--amber); }
  .gesture-help { margin:8px 2px 0; color:var(--quiet); font-size:9px; line-height:1.5; }
  .metric-catalog { display:grid; gap:30px; }
  .metric-group { min-width:0; }
  .metric-grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:14px; }
  .card { min-width:0; padding:15px 16px; border:1px solid var(--line); border-radius:var(--radius-md); background:linear-gradient(145deg,rgba(27,27,25,.9),rgba(15,15,14,.88)); box-shadow:inset 0 1px rgba(255,255,255,.025),0 15px 44px rgba(0,0,0,.2); transition:border-color .2s,transform .2s; }
  .card:hover { border-color:var(--line-strong); transform:translateY(-2px); }
  .card h3 { margin:0 0 8px; color:var(--text); font-size:12px; letter-spacing:-.01em; }
  .chartbox { position:relative; height:210px; }
  .chartbox canvas { cursor:grab; touch-action:none; }
  .chartbox canvas.dragging { cursor:grabbing; }
  .chart-stats { display:flex; flex-wrap:wrap; gap:2px 0; margin-top:8px; padding-top:7px; border-top:1px solid var(--line); }
  .srow { display:flex; align-items:baseline; gap:10px; width:100%; color:var(--muted); font-size:10px; line-height:1.6; }
  .sname { min-width:70px; max-width:110px; overflow:hidden; color:var(--quiet); font-size:9px; text-overflow:ellipsis; white-space:nowrap; }
  .sval { font-family:ui-monospace,Menlo,monospace; font-size:11px; }
  .sval .sl { margin-right:2px; color:var(--quiet); font-size:8px; text-transform:uppercase; }
  .empty { display:none; padding:42px; color:var(--quiet); text-align:center; }
  .event-scroll { max-height:380px; overflow:auto; border:1px solid var(--line); border-radius:13px; }
  table { width:100%; border-collapse:collapse; color:var(--muted); font-size:11px; background:rgba(255,255,255,.018); }
  th,td { padding:10px 12px; border-bottom:1px solid var(--line); text-align:left; vertical-align:top; }
  th { position:sticky; top:0; z-index:1; color:var(--quiet); background:#151514; font-size:9px; letter-spacing:.1em; text-transform:uppercase; }
  td:last-child { word-break:break-word; overflow-wrap:anywhere; font:10px/1.5 ui-monospace,Menlo,monospace; }
  .tag { display:inline-flex; padding:3px 7px; border-radius:999px; color:#fff; font-size:9px; font-weight:800; letter-spacing:.04em; }
  .tag.anomaly { background:#a65d28; } .tag.scenario { background:#6254a8; } .tag.fail { background:#923e43; }
  @media(max-width:900px){.metric-grid{grid-template-columns:1fr}.control-grid{grid-template-columns:1fr}.timeline-actions{justify-content:flex-start}}
  @media(max-width:720px){.studio-appbar{flex-wrap:wrap}.report-context{order:3;flex:1 0 100%}.report-lead{display:block}.report-shell{padding-top:24px}.health-panel,.controls,.events-panel{padding:16px}.card .chartbox{height:190px}}
</style>
</head>
<body>
<div class="studio-ambient" aria-hidden="true"><span class="studio-orb gold"></span><span class="studio-orb violet"></span><span class="studio-orb teal"></span></div>
<header class="studio-appbar">
  <div class="studio-brand"><div class="studio-brand-mark">APS</div><div class="studio-brand-copy"><div class="studio-brand-name">Interactive performance</div><div class="studio-brand-meta">portable · offline · complete scenario</div></div></div>
  <div class="report-context"><div class="studio-status ok">Capture complete</div><div class="studio-brand-meta">${escapeHtml(project.name)} · ${escapeHtml(scenario.name)}</div></div>
</header>
<main class="studio-shell report-shell">
  <section class="report-lead studio-enter"><div><p class="studio-eyebrow">Release Guard evidence</p><h1>Scenario under observation.</h1><p class="report-lead-copy">Explore the complete measured run with the same visual language as Live Dashboard. Hover for exact values, toggle series from legends, filter metrics, and focus any time range.</p></div><span class="studio-status ok">Self-contained report</span></section>
  <section class="health-panel studio-panel studio-enter"><div class="studio-section-head"><div><h2 class="studio-section-title">Run at a glance</h2><p class="studio-section-copy">Device identity and complete-scenario evidence coverage.</p></div></div><div class="facts" id="facts"></div></section>
  <section class="controls studio-panel studio-enter">
    <div class="control-grid"><div class="field"><label for="search">Find a metric</label><input id="search" type="search" placeholder="CPU, PSS, rendering…"></div><div class="timeline-actions" aria-label="Timeline controls"><button class="studio-button" id="zoomOut" type="button" title="Zoom out">−</button><button class="studio-button" id="zoomIn" type="button" title="Zoom in">+</button><button class="studio-button" id="reset" type="button">Reset view</button></div></div>
    <div class="timeline-wrap"><div class="timeline-head"><span class="timeline-label">Scenario timeline</span><span class="timeline-readout" id="rangeLabel">—</span></div><div class="navigator" id="navigator"><canvas id="navigatorCanvas" aria-hidden="true"></canvas><div class="nav-shade left" id="navShadeLeft"></div><div class="nav-shade right" id="navShadeRight"></div><div class="nav-window" id="navWindow"><button class="nav-handle start" id="navStart" type="button" aria-label="Visible range start"></button><button class="nav-handle end" id="navEnd" type="button" aria-label="Visible range end"></button></div><div class="event-rail" id="eventRail" aria-label="Clustered scenario events"></div></div><p class="gesture-help">Wheel or pinch to zoom · drag a chart to pan · Shift-drag to zoom a precise interval · drag the navigator window or handles to move through the run.</p></div>
  </section>
  <div class="empty studio-panel" id="empty">No performance samples were captured for this scenario.</div>
  <div class="metric-catalog" id="catalog">
    <section class="metric-group" data-group="system"><div class="studio-section-head"><div><p class="studio-eyebrow">01 · Device</p><h2 class="studio-section-title">System</h2><p class="studio-section-copy">Compute, thermal, load, and network behavior.</p></div></div><div class="metric-grid"></div></section>
    <section class="metric-group" data-group="memory"><div class="studio-section-head"><div><p class="studio-eyebrow">02 · Pressure</p><h2 class="studio-section-title">Memory</h2><p class="studio-section-copy">Capacity, reclaim, pressure, faults, and zone watermarks.</p></div></div><div class="metric-grid"></div></section>
    <section class="metric-group" data-group="application"><div class="studio-section-head"><div><p class="studio-eyebrow">03 · Workload</p><h2 class="studio-section-title">Application</h2><p class="studio-section-copy">Process CPU, memory footprint, and main-thread cost.</p></div></div><div class="metric-grid"></div></section>
    <section class="metric-group" data-group="rendering"><div class="studio-section-head"><div><p class="studio-eyebrow">04 · Experience</p><h2 class="studio-section-title">Rendering</h2><p class="studio-section-copy">Frame health and UI smoothness.</p></div></div><div class="metric-grid"></div></section>
    <section class="metric-group" data-group="media"><div class="studio-section-head"><div><p class="studio-eyebrow">05 · Playback</p><h2 class="studio-section-title">Media</h2><p class="studio-section-copy">Audio and video process demand.</p></div></div><div class="metric-grid"></div></section>
  </div>
  <section class="events-panel studio-panel" id="eventsPanel"><div class="studio-section-head"><div><p class="studio-eyebrow">Run narrative</p><h2 class="studio-section-title">Scenario events &amp; anomalies</h2><p class="studio-section-copy">Markers span every measured repetition; failures and device anomalies are consolidated here.</p></div></div><div class="event-scroll"><table><thead><tr><th>Time</th><th>Kind</th><th>Attempt</th><th>Detail</th></tr></thead><tbody id="events"></tbody></table></div></section>
</main>
<script id="report-data" type="application/json">$data</script>
<script>$chartRuntime</script>
<script>$REPORT_SCRIPT</script>
</body></html>"""
        )
    }

    private fun sample(value: Sample): Map<String, Any?> = linkedMapOf(
        "t" to finite(value.elapsedSec),
        "cpu" to finite(value.cpuUtilPct),
        "cpuIdle" to finite(value.cpuIdlePct),
        "cores" to value.perCoreUtilPct?.map(::finite),
        "memUsed" to finite(value.memUsedMb),
        "memUsedPct" to finite(value.memUsedPct),
        "memAvail" to finite(value.memAvailableMb),
        "psiCpu" to finite(value.cpuPsiSomeAvg10),
        "psiMem" to finite(value.memPsiSomeAvg10),
        "psiIo" to finite(value.ioPsiSomeAvg10),
        "rx" to finite(value.netRxKbps),
        "tx" to finite(value.netTxKbps),
        "temp" to finite(value.cpuTempC),
        "freq" to finite(value.cpuFreqMhz),
        "faults" to finite(value.majorFaultsPerSec),
        "reclaim" to finite(value.directReclaimPerSec),
        "swap" to finite(value.swapUsedMb),
        "zoneFree" to finite(value.zoneFreePages),
        "zoneMin" to finite(value.zoneWmMinPages),
        "zoneLow" to finite(value.zoneWmLowPages),
        "zoneHigh" to finite(value.zoneWmHighPages),
        "load1" to finite(value.load1),
        "pss" to finite(value.pss?.totalMb),
        "pssJava" to finite(value.pss?.javaHeapMb),
        "pssNative" to finite(value.pss?.nativeHeapMb),
        "pssCode" to finite(value.pss?.codeMb),
        "pssStack" to finite(value.pss?.stackMb),
        "pssGfx" to finite(value.pss?.graphicsMb),
        "pssPriv" to finite(value.pss?.privateOtherMb),
        "pssSys" to finite(value.pss?.systemMb),
        "jank" to finite(value.jankPct),
        "mainCpu" to finite(value.mainThreadCpuPct),
        "fIn" to finite(value.frameInputMs),
        "fAnim" to finite(value.frameAnimMs),
        "fLay" to finite(value.frameLayoutMs),
        "fDraw" to finite(value.frameDrawMs),
        "appCpu" to finite(value.appCpuPct),
        "screenFps" to finite(value.screenFps),
        "videoFps" to finite(value.videoFps),
        "media" to value.mediaProcs.mapValues { finite(it.value) },
        "procs" to value.topProcs.map { mapOf("pid" to it.pid, "n" to it.name, "v" to finite(it.cpuPct)) },
        "memprocs" to value.topMemProcs.map { mapOf("pid" to it.pid, "n" to it.name, "v" to finite(it.pssMb)) }
    )

    private fun finite(value: Double?): Double? = value?.takeIf(Double::isFinite)

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}

private const val REPORT_SCRIPT = """
(function () {
  'use strict';
  var report = JSON.parse(document.getElementById('report-data').textContent);
  var samples = report.samples || [];
  var charts = [];
  var colors = ['#e8b66d','#9e8cff','#58d6c7','#ff7676','#71a8ff','#61d89c','#d68cff','#b5afa5'];
  var defs = [
    {g:'system',t:'CPU Utilization (%)',s:[['CPU %','cpu']],max:100},
    {g:'system',t:'CPU Idle (%)',s:[['Idle %','cpuIdle']],max:100},
    {g:'system',t:'Per-core CPU (%)',dynamic:'cores',max:100},
    {g:'system',t:'Load Average (1m)',s:[['Load 1m','load1']]},
    {g:'system',t:'CPU Temperature (C)',s:[['Temperature','temp']]},
    {g:'system',t:'CPU Frequency (MHz)',s:[['Frequency','freq']]},
    {g:'system',t:'Network (KB/s)',s:[['RX','rx'],['TX','tx']]},
    {g:'memory',t:'Memory (MB)',s:[['Used','memUsed'],['Available','memAvail']]},
    {g:'memory',t:'Memory Used (%)',s:[['Used %','memUsedPct']],max:100},
    {g:'memory',t:'Swap Used (MB)',s:[['Swap','swap']]},
    {g:'memory',t:'Pressure PSI avg10 (some)',s:[['CPU','psiCpu'],['Memory','psiMem'],['I/O','psiIo']]},
    {g:'memory',t:'Direct Reclaim (events/s)',s:[['Reclaim','reclaim']]},
    {g:'memory',t:'Major Page Faults (/s)',s:[['Faults','faults']]},
    {g:'memory',t:'Memory Zone Watermarks',s:[['Free','zoneFree'],['High','zoneHigh'],['Low','zoneLow'],['Min','zoneMin']]},
    {g:'application',t:'App PSS — Total (MB)',s:[['Total PSS','pss']]},
    {g:'application',t:'App PSS breakdown (MB)',s:[['Java heap','pssJava'],['Native heap','pssNative'],['Code','pssCode'],['Stack','pssStack'],['Graphics','pssGfx'],['Private other','pssPriv'],['System','pssSys']]},
    {g:'application',t:'App Process CPU — all threads (%)',s:[['Process %','appCpu']]},
    {g:'application',t:'App Main-Thread CPU (%)',s:[['Main thread %','mainCpu']],max:100},
    {g:'application',t:'Top Process CPU (%)',dynamic:'procs'},
    {g:'application',t:'Top Process Memory (MB)',dynamic:'memprocs'},
    {g:'rendering',t:'UI Jank (% janky frames)',s:[['Janky %','jank']],max:100},
    {g:'rendering',t:'Frame Phases (avg ms)',s:[['Input','fIn'],['Animation','fAnim'],['Layout','fLay'],['Draw','fDraw']]},
    {g:'rendering',t:'Presented FPS',s:[['Screen FPS','screenFps'],['Video FPS','videoFps']]},
    {g:'media',t:'Media & Audio Process CPU (%)',dynamic:'media'}
  ];

  Chart.defaults.color = '#aaa69e';
  Chart.defaults.borderColor = 'rgba(255,255,255,.08)';
  Chart.defaults.font.family = 'Inter, -apple-system, BlinkMacSystemFont, Segoe UI, sans-serif';

  var fullStart=0,fullEnd=1,viewStart=0,viewEnd=1,minWindow=1,viewportFrame=0;
  var evidence=(report.markers||[]).filter(function(m){return Number.isFinite(m.t);}).map(function(m){return {t:m.t,kind:'marker',type:m.type,outcome:m.outcome,label:m.label};})
    .concat((report.anomalies||[]).filter(function(a){return Number.isFinite(a.t);}).map(function(a){return {t:a.t,kind:'anomaly',type:a.type,outcome:'fail',label:a.detail};}));

  function evidenceClusters(events,start,end,limit) {
    if(!events.length||end<=start||limit<=0)return [];
    var slots={};
    events.forEach(function(event){
      if(event.t<start||event.t>end)return;
      var slot=Math.min(limit-1,Math.max(0,Math.floor((event.t-start)/(end-start)*limit)));
      var cluster=slots[slot]||(slots[slot]={sum:0,count:0,anomaly:false,failed:false,labels:[]});
      cluster.sum+=event.t;cluster.count++;cluster.anomaly=cluster.anomaly||event.kind==='anomaly';cluster.failed=cluster.failed||event.outcome==='fail';
      if(cluster.labels.length<3)cluster.labels.push(event.type||event.label||'event');
    });
    return Object.keys(slots).map(function(key){var c=slots[key];c.t=c.sum/c.count;return c;}).sort(function(a,b){return a.t-b.t;});
  }

  var interactionPlugin = {
    id:'scenarioEvidence',
    afterDatasetsDraw:function(chart) {
      var x=chart.scales.x,area=chart.chartArea,ctx=chart.ctx,span=viewEnd-viewStart,close=span<=(fullEnd-fullStart)*.05;
      var candidates=evidence.filter(function(event){
        if(event.kind==='anomaly')return true;
        if(event.type==='attempt_start'||event.type==='section_start'||event.outcome==='fail')return true;
        return close&&(event.type==='step_start'||event.type==='step_end');
      });
      var marks=evidenceClusters(candidates,viewStart,viewEnd,24);
      ctx.save();
      marks.forEach(function(m){
        var px=x.getPixelForValue(m.t);
        ctx.strokeStyle=m.anomaly?'rgba(255,118,118,.66)':m.failed?'rgba(244,180,95,.55)':'rgba(158,140,255,.32)';
        ctx.lineWidth=m.count>1?2:1;ctx.setLineDash(close?[4,4]:[]);
        ctx.beginPath();ctx.moveTo(px,area.top);ctx.lineTo(px,close?area.bottom:area.top+11);ctx.stroke();
      });
      ctx.restore();
    },
    afterDraw:function(chart){
      var selection=chart._rangeSelection;if(!selection)return;
      var area=chart.chartArea,left=Math.max(area.left,Math.min(selection.start,selection.end)),right=Math.min(area.right,Math.max(selection.start,selection.end)),ctx=chart.ctx;
      ctx.save();ctx.fillStyle='rgba(232,182,109,.12)';ctx.strokeStyle='rgba(255,214,154,.85)';ctx.setLineDash([4,3]);ctx.fillRect(left,area.top,right-left,area.bottom-area.top);ctx.strokeRect(left,area.top,right-left,area.bottom-area.top);ctx.restore();
    }
  };
  Chart.register(interactionPlugin);

  function safe(value) { return value == null ? '' : String(value); }
  function esc(value) { return safe(value).replace(/[&<>\"']/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;',"'":'&#39;'}[c];}); }
  function fmt(value) { if (!Number.isFinite(value)) return '—'; var a=Math.abs(value); return a>=1000?value.toFixed(0):a>=100?value.toFixed(1):value.toFixed(2); }
  function clock(value){if(!Number.isFinite(value))return '—';var h=Math.floor(value/3600),m=Math.floor(value%3600/60),s=value%60;return(h?h+':':'')+(h?String(m).padStart(2,'0'):m)+':'+s.toFixed(2).padStart(5,'0');}
  function seriesFor(def) {
    if (def.dynamic === 'cores') {
      var count=samples.reduce(function(n,p){return Math.max(n,(p.cores||[]).length);},0);
      return Array.from({length:count},function(_,i){return ['Core '+i,function(p){return p.cores && p.cores[i];}];});
    }
    if (def.dynamic === 'media') {
      var names={}; samples.forEach(function(p){Object.keys(p.media||{}).forEach(function(n){names[n]=true;});});
      return Object.keys(names).sort().map(function(n){return [n,function(p){return p.media && p.media[n];}];});
    }
    if (def.dynamic === 'procs' || def.dynamic === 'memprocs') {
      var store={};
      samples.forEach(function(p){(p[def.dynamic]||[]).forEach(function(proc){var id=String(proc.pid);if(!store[id])store[id]={name:proc.n||id,peak:0};store[id].peak=Math.max(store[id].peak,proc.v||0);});});
      return Object.keys(store).sort(function(a,b){return store[b].peak-store[a].peak;}).slice(0,8).map(function(id){return [store[id].name+' · '+id,function(p){var row=(p[def.dynamic]||[]).find(function(v){return String(v.pid)===id;});return row&&row.v;}];});
    }
    return (def.s||[]).map(function(s){return [s[0],function(p){return p[s[1]];}];});
  }
  function valuesFor(getter) {
    var values=samples.map(function(p){var y=getter(p);return Number.isFinite(y)&&Number.isFinite(p.t)?{x:p.t,y:y}:null;}).filter(Boolean).sort(function(a,b){return a.x-b.x;});
    return values.reduce(function(out,p){if(out.length&&out[out.length-1].x===p.x)out[out.length-1]=p;else out.push(p);return out;},[]);
  }
  function stats(points, start, end) {
    var values=points.filter(function(p){return p.x>=start&&p.x<=end&&Number.isFinite(p.y);}).map(function(p){return p.y;});
    if(!values.length)return null;
    return {min:Math.min.apply(null,values),max:Math.max.apply(null,values),avg:values.reduce(function(a,b){return a+b;},0)/values.length};
  }
  function renderStats(item) {
    item.stats.innerHTML=item.chart.data.datasets.map(function(ds){var s=stats(ds._fullData||ds.data,viewStart,viewEnd);if(!s)return '';return '<div class="srow"><span class="sname">'+esc(ds.label)+'</span><span class="sval"><span class="sl">min</span>'+fmt(s.min)+'</span><span class="sval"><span class="sl">avg</span>'+fmt(s.avg)+'</span><span class="sval"><span class="sl">max</span>'+fmt(s.max)+'</span></div>';}).join('');
  }
  function buildChart(def,index) {
    var series=seriesFor(def).map(function(s){return {label:s[0],data:valuesFor(s[1])};}).filter(function(s){return s.data.length;});
    if(!series.length)return;
    var group=document.querySelector('[data-group="'+def.g+'"] .metric-grid');
    var card=document.createElement('article');card.className='card';card.dataset.title=def.t.toLowerCase();
    card.innerHTML='<h3>'+def.t+'</h3><div class="chartbox"><canvas></canvas></div><div class="chart-stats"></div>';
    group.appendChild(card);
    var datasets=series.map(function(s,i){return {label:s.label,data:s.data,_fullData:s.data,borderColor:colors[i%colors.length],backgroundColor:colors[i%colors.length],borderWidth:2,pointRadius:0,pointHitRadius:8,tension:.15};});
    var scales={x:{type:'linear',min:viewStart,max:viewEnd,title:{display:true,text:'time (s)'}},y:{beginAtZero:true}};if(def.max)scales.y.max=def.max;
    var chart=new Chart(card.querySelector('canvas'),{type:'line',data:{datasets:datasets},options:{animation:false,responsive:true,maintainAspectRatio:false,parsing:false,normalized:true,interaction:{mode:'nearest',intersect:false},plugins:{legend:{labels:{boxWidth:12,font:{size:10}}},decimation:{enabled:true,algorithm:'lttb',samples:900},tooltip:{callbacks:{title:function(items){return items.length?'t = '+fmt(items[0].parsed.x)+' s':'';}}}},scales:scales}});
    var item={chart:chart,card:card,stats:card.querySelector('.chart-stats')};charts.push(item);attachChartGestures(item);renderStats(item);
  }
  function updateGroups() {
    document.querySelectorAll('.metric-group').forEach(function(group){group.style.display=Array.prototype.some.call(group.querySelector('.metric-grid').children,function(card){return card.style.display!=='none';})?'block':'none';});
  }
  function clampRange(start,end){
    var center=(start+end)/2,span=Math.max(minWindow,end-start);start=center-span/2;end=center+span/2;
    if(start<fullStart){end+=fullStart-start;start=fullStart;}if(end>fullEnd){start-=end-fullEnd;end=fullEnd;}
    return [Math.max(fullStart,start),Math.min(fullEnd,end)];
  }
  function setViewport(start,end){var range=clampRange(start,end);viewStart=range[0];viewEnd=range[1];if(!viewportFrame)viewportFrame=requestAnimationFrame(flushViewport);}
  function flushViewport(){viewportFrame=0;charts.forEach(function(item){item.chart.options.scales.x.min=viewStart;item.chart.options.scales.x.max=viewEnd;item.chart.update('none');renderStats(item);});syncNavigator();}
  function zoomAt(anchor,factor){var span=Math.max(minWindow,Math.min(fullEnd-fullStart,(viewEnd-viewStart)*factor)),ratio=(anchor-viewStart)/(viewEnd-viewStart);setViewport(anchor-span*ratio,anchor+span*(1-ratio));}
  function panBy(delta){setViewport(viewStart+delta,viewEnd+delta);}
  function resetView(){document.getElementById('search').value='';charts.forEach(function(item){item.card.style.display='';});setViewport(fullStart,fullEnd);updateGroups();}
  function syncNavigator(){
    var total=fullEnd-fullStart,left=(viewStart-fullStart)/total*100,right=(fullEnd-viewEnd)/total*100,windowEl=document.getElementById('navWindow');
    document.getElementById('navShadeLeft').style.width=left+'%';document.getElementById('navShadeRight').style.width=right+'%';windowEl.style.left=left+'%';windowEl.style.right=right+'%';
    document.getElementById('rangeLabel').textContent=clock(viewStart)+' — '+clock(viewEnd)+' · '+clock(viewEnd-viewStart);
    document.getElementById('navStart').setAttribute('aria-valuetext',clock(viewStart));document.getElementById('navEnd').setAttribute('aria-valuetext',clock(viewEnd));
    document.getElementById('zoomOut').disabled=viewEnd-viewStart>=total-.001;
  }
  function attachChartGestures(item){
    var canvas=item.chart.canvas,state=null;
    canvas.addEventListener('wheel',function(event){
      event.preventDefault();var span=viewEnd-viewStart;
      if(Math.abs(event.deltaX)>Math.abs(event.deltaY)&&!event.ctrlKey){panBy(event.deltaX/canvas.clientWidth*span);return;}
      var area=item.chart.chartArea,px=Math.max(area.left,Math.min(area.right,event.offsetX)),anchor=item.chart.scales.x.getValueForPixel(px);zoomAt(anchor,event.deltaY>0?1.12:1/1.12);
    },{passive:false});
    canvas.addEventListener('pointerdown',function(event){
      if(event.button!==0)return;var area=item.chart.chartArea;if(event.offsetX<area.left||event.offsetX>area.right||event.offsetY<area.top||event.offsetY>area.bottom)return;
      state={mode:event.shiftKey?'select':'pan',x:event.offsetX,start:viewStart,end:viewEnd};canvas.classList.add('dragging');canvas.setPointerCapture(event.pointerId);
      if(state.mode==='select'){item.chart._rangeSelection={start:event.offsetX,end:event.offsetX};item.chart.draw();}
    });
    canvas.addEventListener('pointermove',function(event){if(!state)return;if(state.mode==='pan'){var width=item.chart.chartArea.right-item.chart.chartArea.left,delta=-(event.offsetX-state.x)/width*(state.end-state.start);setViewport(state.start+delta,state.end+delta);}else{item.chart._rangeSelection.end=event.offsetX;item.chart.draw();}});
    function finish(event){if(!state)return;if(state.mode==='select'&&Math.abs(event.offsetX-state.x)>6){var a=item.chart.scales.x.getValueForPixel(Math.min(state.x,event.offsetX)),b=item.chart.scales.x.getValueForPixel(Math.max(state.x,event.offsetX));setViewport(a,b);}state=null;item.chart._rangeSelection=null;canvas.classList.remove('dragging');item.chart.draw();}
    canvas.addEventListener('pointerup',finish);canvas.addEventListener('pointercancel',function(){state=null;item.chart._rangeSelection=null;canvas.classList.remove('dragging');item.chart.draw();});canvas.addEventListener('dblclick',resetView);
  }
  function primaryOverview(){var keys=['cpu','pss','memUsed','memUsedPct'];for(var i=0;i<keys.length;i++){var key=keys[i],points=valuesFor(function(p){return p[key];});if(points.length)return points;}return [];}
  function drawNavigator(){
    var canvas=document.getElementById('navigatorCanvas'),rect=canvas.getBoundingClientRect(),ratio=window.devicePixelRatio||1,width=Math.max(1,Math.round(rect.width*ratio)),height=Math.max(1,Math.round(rect.height*ratio));canvas.width=width;canvas.height=height;var ctx=canvas.getContext('2d'),points=primaryOverview();ctx.clearRect(0,0,width,height);if(!points.length)return;
    var low=Math.min.apply(null,points.map(function(p){return p.y;})),high=Math.max.apply(null,points.map(function(p){return p.y;}));if(high===low)high=low+1;
    ctx.beginPath();points.forEach(function(p,index){var x=(p.x-fullStart)/(fullEnd-fullStart)*width,y=height-4-(p.y-low)/(high-low)*(height-8);if(index)ctx.lineTo(x,y);else ctx.moveTo(x,y);});ctx.lineTo(width,height);ctx.lineTo(0,height);ctx.closePath();var gradient=ctx.createLinearGradient(0,0,0,height);gradient.addColorStop(0,'rgba(232,182,109,.30)');gradient.addColorStop(1,'rgba(232,182,109,.02)');ctx.fillStyle=gradient;ctx.fill();ctx.beginPath();points.forEach(function(p,index){var x=(p.x-fullStart)/(fullEnd-fullStart)*width,y=height-4-(p.y-low)/(high-low)*(height-8);if(index)ctx.lineTo(x,y);else ctx.moveTo(x,y);});ctx.strokeStyle='#e8b66d';ctx.lineWidth=Math.max(1,ratio);ctx.stroke();
  }
  function renderEventRail(){
    var rail=document.getElementById('eventRail'),width=Math.max(1,rail.clientWidth),clusters=evidenceClusters(evidence,fullStart,fullEnd,Math.max(1,Math.floor(width/8)));
    rail.innerHTML=clusters.map(function(c){var cls=c.anomaly?' anomaly':c.failed?' failed':'',left=(c.t-fullStart)/(fullEnd-fullStart)*100,title=c.count+' event'+(c.count===1?'':'s')+' · '+clock(c.t)+' · '+c.labels.join(', ');return '<button class="event-cluster'+cls+'" type="button" style="left:'+left+'%" title="'+esc(title)+'" aria-label="'+esc(title)+'"></button>';}).join('');
  }
  function initNavigator(){
    var nav=document.getElementById('navigator'),windowEl=document.getElementById('navWindow'),drag=null;
    function timeAt(event){var rect=nav.getBoundingClientRect();return fullStart+Math.max(0,Math.min(1,(event.clientX-rect.left)/rect.width))*(fullEnd-fullStart);}
    nav.addEventListener('pointerdown',function(event){
      if(event.button!==0||event.target.classList.contains('event-cluster'))return;var mode=event.target.id==='navStart'?'start':event.target.id==='navEnd'?'end':windowEl.contains(event.target)?'pan':'recenter',at=timeAt(event);
      if(mode==='recenter'){var span=viewEnd-viewStart;setViewport(at-span/2,at+span/2);mode='pan';}
      drag={mode:mode,x:event.clientX,start:viewStart,end:viewEnd};windowEl.classList.add('dragging');nav.setPointerCapture(event.pointerId);
    });
    nav.addEventListener('pointermove',function(event){if(!drag)return;var at=timeAt(event),rect=nav.getBoundingClientRect(),delta=(event.clientX-drag.x)/rect.width*(fullEnd-fullStart);if(drag.mode==='start')setViewport(at,drag.end);else if(drag.mode==='end')setViewport(drag.start,at);else setViewport(drag.start+delta,drag.end+delta);});
    function end(){drag=null;windowEl.classList.remove('dragging');}nav.addEventListener('pointerup',end);nav.addEventListener('pointercancel',end);
    ['navStart','navEnd'].forEach(function(id){document.getElementById(id).addEventListener('keydown',function(event){if(event.key!=='ArrowLeft'&&event.key!=='ArrowRight')return;event.preventDefault();var delta=(viewEnd-viewStart)*.05*(event.key==='ArrowLeft'?-1:1);if(id==='navStart')setViewport(viewStart+delta,viewEnd);else setViewport(viewStart,viewEnd+delta);});});
    drawNavigator();renderEventRail();syncNavigator();
  }
  function renderFacts() {
    var device=report.device||{},verdict=safe(report.verdict||'evidence only').replace(/_/g,' '),status=verdict.toLowerCase()==='fail'?'bad':verdict.toLowerCase()==='warning'?'warn':'ok';
    var facts=[['Verdict',verdict,status],['Device',device.model||'Unknown',''],['Android',device.androidVersion||'Unknown',''],['Firmware',device.firmware||'Unknown',''],['Samples',samples.length,''],['Measured repetitions',report.attempts||0,''],['Anomalies',(report.anomalies||[]).length,(report.anomalies||[]).length?'warn':'ok'],['Run',report.runId||'Unknown','']];
    document.getElementById('facts').innerHTML=facts.map(function(f){return '<div class="fact '+f[2]+'"><div class="k">'+esc(f[0])+'</div><div class="v" title="'+esc(f[1])+'">'+esc(f[1])+'</div></div>';}).join('');
  }
  function renderEvents() {
    var events=(report.markers||[]).map(function(m){return {t:m.t,kind:m.outcome==='fail'?'Failed step':'Scenario',tag:m.outcome==='fail'?'fail':'scenario',attempt:m.attempt,detail:m.label+(m.section?' · '+m.section:'')+(m.detail?' · '+m.detail:'')};})
      .concat((report.anomalies||[]).map(function(a){return {t:a.t,kind:a.type,tag:'anomaly',attempt:'—',detail:a.detail};})).sort(function(a,b){return a.t-b.t;});
    document.getElementById('events').innerHTML=events.length?events.map(function(e){return '<tr><td>'+fmt(e.t)+'s</td><td><span class="tag '+e.tag+'">'+esc(e.kind)+'</span></td><td>'+esc(e.attempt)+'</td><td>'+esc(e.detail)+'</td></tr>';}).join(''):'<tr><td colspan="4">No scenario events or anomalies were recorded.</td></tr>';
  }

  samples=samples.filter(function(p){return Number.isFinite(p.t);}).sort(function(a,b){return a.t-b.t;});
  renderFacts();renderEvents();
  if(!samples.length){document.getElementById('empty').style.display='block';document.getElementById('catalog').style.display='none';return;}
  var times=samples.map(function(p){return p.t;});fullStart=Math.min.apply(null,times);fullEnd=Math.max.apply(null,times);if(fullStart===fullEnd)fullEnd=fullStart+1;viewStart=fullStart;viewEnd=fullEnd;minWindow=Math.max(1,(fullEnd-fullStart)/10000);
  defs.forEach(buildChart);initNavigator();updateGroups();
  document.getElementById('zoomOut').addEventListener('click',function(){zoomAt((viewStart+viewEnd)/2,2);});
  document.getElementById('zoomIn').addEventListener('click',function(){zoomAt((viewStart+viewEnd)/2,.5);});
  document.getElementById('reset').addEventListener('click',resetView);
  document.getElementById('search').addEventListener('input',function(e){var q=e.target.value.trim().toLowerCase();charts.forEach(function(item){item.card.style.display=!q||item.card.dataset.title.indexOf(q)>=0?'':'none';});updateGroups();});
  window.addEventListener('resize',function(){drawNavigator();renderEventRail();syncNavigator();});
}());
"""
