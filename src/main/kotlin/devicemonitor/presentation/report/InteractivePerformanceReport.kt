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
  .control-grid { display:grid; grid-template-columns:minmax(220px,1fr) minmax(280px,2fr) auto; align-items:end; gap:14px; }
  .field label { display:block; margin-bottom:7px; color:var(--quiet); font-size:9px; font-weight:800; letter-spacing:.1em; text-transform:uppercase; }
  .field input[type=search] { width:100%; min-height:39px; padding:0 12px; border:1px solid var(--line); border-radius:10px; outline:0; color:var(--text); background:var(--surface-soft); }
  .field input[type=search]:focus { border-color:rgba(232,182,109,.55); box-shadow:0 0 0 3px rgba(232,182,109,.08); }
  .range-head { display:flex; justify-content:space-between; gap:12px; margin-bottom:7px; color:var(--quiet); font:9px/1.3 ui-monospace,Menlo,monospace; }
  .range-inputs { display:grid; grid-template-columns:1fr 1fr; gap:10px; }
  .range-inputs input { width:100%; accent-color:var(--gold); }
  .metric-catalog { display:grid; gap:30px; }
  .metric-group { min-width:0; }
  .metric-grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:14px; }
  .card { min-width:0; padding:15px 16px; border:1px solid var(--line); border-radius:var(--radius-md); background:linear-gradient(145deg,rgba(27,27,25,.9),rgba(15,15,14,.88)); box-shadow:inset 0 1px rgba(255,255,255,.025),0 15px 44px rgba(0,0,0,.2); transition:border-color .2s,transform .2s; }
  .card:hover { border-color:var(--line-strong); transform:translateY(-2px); }
  .card h3 { margin:0 0 8px; color:var(--text); font-size:12px; letter-spacing:-.01em; }
  .chartbox { position:relative; height:210px; }
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
  @media(max-width:900px){.metric-grid{grid-template-columns:1fr}.control-grid{grid-template-columns:1fr}.control-grid .studio-button{width:100%}}
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
  <section class="controls studio-panel studio-enter"><div class="control-grid"><div class="field"><label for="search">Find a metric</label><input id="search" type="search" placeholder="CPU, PSS, rendering…"></div><div class="field"><div class="range-head"><span>Visible time range</span><span id="rangeLabel">—</span></div><div class="range-inputs"><input id="rangeStart" type="range" aria-label="Range start"><input id="rangeEnd" type="range" aria-label="Range end"></div></div><button class="studio-button" id="reset" type="button">Reset view</button></div></section>
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

  var markerPlugin = {
    id:'scenarioEvidence',
    afterDatasetsDraw:function(chart) {
      var x = chart.scales.x, area = chart.chartArea, ctx = chart.ctx;
      var marks = (report.markers || []).concat((report.anomalies || []).map(function(a){return {t:a.t,label:a.type,outcome:'fail',anomaly:true};}));
      ctx.save();
      marks.forEach(function(m) {
        if (!Number.isFinite(m.t) || m.t < x.min || m.t > x.max) return;
        var px = x.getPixelForValue(m.t);
        ctx.strokeStyle = m.anomaly || m.outcome === 'fail' ? 'rgba(255,118,118,.68)' : 'rgba(158,140,255,.32)';
        ctx.setLineDash(m.anomaly ? [4,3] : [2,4]);
        ctx.beginPath(); ctx.moveTo(px,area.top); ctx.lineTo(px,area.bottom); ctx.stroke();
      });
      ctx.restore();
    }
  };
  Chart.register(markerPlugin);

  function safe(value) { return value == null ? '' : String(value); }
  function esc(value) { return safe(value).replace(/[&<>\"']/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;',"'":'&#39;'}[c];}); }
  function fmt(value) { if (!Number.isFinite(value)) return '—'; var a=Math.abs(value); return a>=1000?value.toFixed(0):a>=100?value.toFixed(1):value.toFixed(2); }
  function point(key) { return samples.filter(function(p){return Number.isFinite(p[key]);}).map(function(p){return {x:p.t,y:p[key]};}); }
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
  function valuesFor(getter) { return samples.map(function(p){var y=getter(p);return Number.isFinite(y)?{x:p.t,y:y}:null;}).filter(Boolean); }
  function stats(dataset, start, end) {
    var values=dataset.data.filter(function(p){return p.x>=start&&p.x<=end&&Number.isFinite(p.y);}).map(function(p){return p.y;});
    if(!values.length)return null;
    return {min:Math.min.apply(null,values),max:Math.max.apply(null,values),avg:values.reduce(function(a,b){return a+b;},0)/values.length};
  }
  function renderStats(item) {
    var start=Number(document.getElementById('rangeStart').value),end=Number(document.getElementById('rangeEnd').value);
    item.stats.innerHTML=item.chart.data.datasets.map(function(ds){var s=stats(ds,start,end);if(!s)return '';return '<div class="srow"><span class="sname">'+esc(ds.label)+'</span><span class="sval"><span class="sl">min</span>'+fmt(s.min)+'</span><span class="sval"><span class="sl">avg</span>'+fmt(s.avg)+'</span><span class="sval"><span class="sl">max</span>'+fmt(s.max)+'</span></div>';}).join('');
  }
  function buildChart(def,index) {
    var series=seriesFor(def).map(function(s){return {label:s[0],data:valuesFor(s[1])};}).filter(function(s){return s.data.length;});
    if(!series.length)return;
    var group=document.querySelector('[data-group="'+def.g+'"] .metric-grid');
    var card=document.createElement('article');card.className='card';card.dataset.title=def.t.toLowerCase();
    card.innerHTML='<h3>'+def.t+'</h3><div class="chartbox"><canvas></canvas></div><div class="chart-stats"></div>';
    group.appendChild(card);
    var datasets=series.map(function(s,i){return {label:s.label,data:s.data,borderColor:colors[i%colors.length],backgroundColor:colors[i%colors.length],borderWidth:2,pointRadius:0,pointHitRadius:8,tension:.15};});
    var scales={x:{type:'linear',title:{display:true,text:'time (s)'}},y:{beginAtZero:true}};if(def.max)scales.y.max=def.max;
    var chart=new Chart(card.querySelector('canvas'),{type:'line',data:{datasets:datasets},options:{animation:false,responsive:true,maintainAspectRatio:false,parsing:false,normalized:true,interaction:{mode:'nearest',intersect:false},plugins:{legend:{labels:{boxWidth:12,font:{size:10}}},decimation:{enabled:true,algorithm:'lttb',samples:900},tooltip:{callbacks:{title:function(items){return items.length?'t = '+fmt(items[0].parsed.x)+' s':'';}}}},scales:scales}});
    var item={chart:chart,card:card,stats:card.querySelector('.chart-stats')};charts.push(item);renderStats(item);
  }
  function updateGroups() {
    document.querySelectorAll('.metric-group').forEach(function(group){group.style.display=Array.prototype.some.call(group.querySelector('.metric-grid').children,function(card){return card.style.display!=='none';})?'block':'none';});
  }
  function applyRange() {
    var startInput=document.getElementById('rangeStart'),endInput=document.getElementById('rangeEnd');
    var start=Number(startInput.value),end=Number(endInput.value);if(start>end){var swap=start;start=end;end=swap;}
    startInput.value=start;endInput.value=end;document.getElementById('rangeLabel').textContent=fmt(start)+'s — '+fmt(end)+'s';
    charts.forEach(function(item){item.chart.options.scales.x.min=start;item.chart.options.scales.x.max=end;item.chart.update('none');renderStats(item);});
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

  renderFacts();renderEvents();
  if(!samples.length){document.getElementById('empty').style.display='block';document.getElementById('catalog').style.display='none';return;}
  var times=samples.map(function(p){return p.t;}).filter(Number.isFinite),min=Math.min.apply(null,times),max=Math.max.apply(null,times);if(min===max)max=min+1;
  ['rangeStart','rangeEnd'].forEach(function(id){var el=document.getElementById(id);el.min=min;el.max=max;el.step=Math.max((max-min)/1000,.01);});
  document.getElementById('rangeStart').value=min;document.getElementById('rangeEnd').value=max;
  defs.forEach(buildChart);applyRange();updateGroups();
  document.getElementById('rangeStart').addEventListener('input',applyRange);document.getElementById('rangeEnd').addEventListener('input',applyRange);
  document.getElementById('reset').addEventListener('click',function(){document.getElementById('rangeStart').value=min;document.getElementById('rangeEnd').value=max;document.getElementById('search').value='';charts.forEach(function(item){item.card.style.display='';});applyRange();updateGroups();});
  document.getElementById('search').addEventListener('input',function(e){var q=e.target.value.trim().toLowerCase();charts.forEach(function(item){item.card.style.display=!q||item.card.dataset.title.indexOf(q)>=0?'':'none';});updateGroups();});
}());
"""
