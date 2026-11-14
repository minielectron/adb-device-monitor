package devicemonitor.presentation.web

internal val COMPARE_PAGE = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Run Comparison — ADB Performance Studio</title>
<script src="/assets/chart.umd.js"></script>
<script src="/assets/jspdf.umd.min.js"></script>
<style>
  .compare-shell { padding-top: 44px; }
  .compare-intro { display: flex; align-items: end; justify-content: space-between; gap: 24px; margin-bottom: 30px; }
  .compare-intro-copy { max-width: 680px; }
  .compare-workspace { margin-bottom: 18px; padding: 22px; }
  .run-pair { display: grid; grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr); gap: 14px; align-items: stretch; }
  .run-card { padding: 17px; border: 1px solid var(--line); border-radius: var(--radius-md); background: rgba(255,255,255,.025); }
  .run-card.a { border-color: rgba(232,182,109,.22); }
  .run-card.b { border-color: rgba(158,140,255,.22); }
  .run-marker { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 11px; color: var(--muted); font-size: 10px; font-weight: 800; letter-spacing: .12em; text-transform: uppercase; }
  .run-marker:after { content: ""; width: 34px; height: 3px; border-radius: 99px; background: var(--gold); }
  .run-card.b .run-marker:after { background: var(--violet); }
  .field { display: flex; flex-direction: column; gap: 7px; }
  .field label { color: var(--quiet); font-size: 10px; font-weight: 700; }
  select { width: 100%; min-width: 0; padding: 11px 38px 11px 12px; border: 1px solid var(--line); border-radius: 10px; color: var(--text); background: #171716; font-size: 12px; }
  .compare-action { display: grid; place-items: center; }
  button.cmp { min-width: 124px; }
  #err { min-height: 18px; margin-top: 10px; color: var(--red); font-size: 11px; }
  #loading { display: none; margin-top: 10px; color: var(--muted); font-size: 11px; }
  .result-tools { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 18px; padding: 13px 15px; }
  .legend { display: flex; align-items: center; gap: 18px; color: var(--muted); font-size: 11px; flex-wrap: wrap; }
  .dot { display: inline-block; width: 12px; height: 3px; margin-right: 5px; border-radius: 2px; vertical-align: middle; }
  #exportBar { margin-left: auto; }
  .tip { display: none; margin-bottom: 20px; padding: 14px 16px; border: 1px solid rgba(88,214,199,.2); border-radius: var(--radius-md); color: #bcebe6; background: linear-gradient(100deg, rgba(88,214,199,.1), rgba(88,214,199,.025)); font-size: 11px; line-height: 1.6; }
  .chartgrid { display: grid; gap: 30px; }
  .compare-group { min-width: 0; }
  .compare-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
  .ccard { min-width: 0; padding: 15px 16px; border: 1px solid var(--line); border-radius: var(--radius-md); background: linear-gradient(145deg, rgba(27,27,25,.9), rgba(15,15,14,.88)); box-shadow: inset 0 1px rgba(255,255,255,.025), 0 15px 44px rgba(0,0,0,.2); transition: border-color .2s, transform .2s; }
  .ccard:hover { border-color: var(--line-strong); transform: translateY(-2px); }
  .ccard h3 { margin: 0 0 10px; color: var(--text); font-size: 12px; letter-spacing: -.01em; }
  .ccard .chartbox { position: relative; height: 230px; }
  .empty-comparison { padding: 24px; border: 1px dashed var(--line-strong); border-radius: var(--radius-md); color: var(--muted); font-size: 12px; text-align: center; }
  @media (max-width: 880px) { .run-pair { grid-template-columns: 1fr; } .run-card.a { order: 1; } .run-card.b { order: 2; } .compare-action { order: 3; justify-items: stretch; } button.cmp { width: 100%; } .compare-grid { grid-template-columns: 1fr; } }
  @media (max-width: 680px) { .compare-intro { display: block; } .result-tools { align-items: flex-start; flex-direction: column; } #exportBar { margin-left: 0; } .compare-workspace { padding: 16px; } .ccard .chartbox { height: 205px; } }
</style>
</head>
<body>
<div class="studio-ambient" aria-hidden="true"><span class="studio-orb gold"></span><span class="studio-orb violet"></span><span class="studio-orb teal"></span></div>
<header class="studio-appbar"><div class="studio-brand"><div class="studio-brand-mark">APS</div><div class="studio-brand-copy"><div class="studio-brand-name">Run comparison</div><div class="studio-brand-meta">Performance intelligence workspace</div></div></div><div class="studio-appbar-context"></div><nav class="studio-actions" aria-label="Comparison navigation"><a class="studio-link" href="/">&#8592; Home</a><a class="studio-link" href="/api/diagnostics">Diagnostics</a></nav></header>
<main class="studio-shell compare-shell">
  <section class="compare-intro studio-enter"><div class="compare-intro-copy"><p class="studio-eyebrow">Evidence, side by side</p><h1 class="studio-page-title">Find the signal between two runs.</h1><p class="studio-page-copy">Compare application, pressure, rendering, media, and system behavior with a consistent baseline and candidate view.</p></div><span class="studio-status ok">Historical analysis</span></section>

  <section class="compare-workspace studio-panel studio-enter" aria-labelledby="select-runs-title">
    <div class="studio-section-head"><div><h2 class="studio-section-title" id="select-runs-title">Choose two captures</h2><p class="studio-section-copy">Use Run A as the baseline and Run B as the candidate.</p></div></div>
    <div class="run-pair">
      <div class="run-card a"><div class="run-marker">Run A · Baseline</div><div class="field"><label for="runA">Reference capture</label><select id="runA"><option value="">Loading runs...</option></select></div></div>
      <div class="compare-action"><button class="cmp studio-button primary" id="cmpBtn" onclick="doCompare()" disabled>Compare &rarr;</button></div>
      <div class="run-card b"><div class="run-marker">Run B · Candidate</div><div class="field"><label for="runB">Candidate capture</label><select id="runB"><option value="">Loading runs...</option></select></div></div>
    </div>
    <div id="err"></div>
    <div id="loading">Loading CSV data...</div>
  </section>

  <div class="tip" id="tunnelTip">
    <strong>Tunnel mode notes:</strong>
    App PSS decrease in tunnel mode is expected (video frames bypass the app render path).
    Watch for CPU PSI increases — this may indicate work shifting to media/HAL processes.
    Compare <em>media.codec</em> and <em>audioserver</em> CPU to confirm.
  </div>

  <div class="result-tools studio-panel" id="resultTools" style="display:none">
    <div class="legend" id="legend"></div>
    <div id="exportBar" style="display:none">
      <button class="pdf studio-button success" id="exportBtn" onclick="exportPdf()">&#8681; Export as PDF</button>
    </div>
  </div>

  <div class="chartgrid" id="charts"></div>
</main>
<script>
var COLOR_A = '#e8b66d';
var COLOR_B = '#9e8cff';
var COLOR_TARGET = '#61d89c';
Chart.defaults.color = '#aaa69e';
Chart.defaults.borderColor = 'rgba(255,255,255,.08)';
Chart.defaults.font.family = 'Inter, -apple-system, BlinkMacSystemFont, Segoe UI, sans-serif';
Chart.register({ id: 'studioBackground', beforeDraw: function (chart) {
  var ctx = chart.ctx; ctx.save(); ctx.globalCompositeOperation = 'destination-over';
  ctx.fillStyle = '#171716'; ctx.fillRect(0, 0, chart.width, chart.height); ctx.restore();
} });
var chartInsts = [];
var chartTitles = [];
var GROUP_META = {
  application: ['01 · Workload', 'Application', 'Process CPU, main-thread demand, and app memory.'],
  pressure: ['02 · Contention', 'Pressure', 'CPU and memory stall signals.'],
  rendering: ['03 · Experience', 'Rendering', 'Frame health and visible smoothness.'],
  media: ['04 · Playback', 'Media', 'Codec, audio, and media-service demand.'],
  system: ['05 · Device', 'System', 'Whole-device utilization.']
};

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
  document.getElementById('legend').innerHTML =
    '<span><span class="dot" style="width:24px;background:' + COLOR_A + '"></span>' + escHtml(labelA) + '</span>' +
    '<span><span class="dot" style="width:24px;background:' + COLOR_B + '"></span>' + escHtml(labelB) + '</span>' +
    '<span><span class="dot" style="width:24px;height:0;border-top:2px dashed ' + COLOR_TARGET + '"></span>Android target</span>';
}

function prepareComparisonGroups() {
  var root = document.getElementById('charts');
  root.innerHTML = '';
  Object.keys(GROUP_META).forEach(function (name) {
    var meta = GROUP_META[name];
    var section = document.createElement('section');
    section.className = 'compare-group';
    section.id = 'compare-group-' + name;
    section.innerHTML = '<div class="studio-section-head"><div><p class="studio-eyebrow">' + meta[0] + '</p><h2 class="studio-section-title">' + meta[1] + '</h2><p class="studio-section-copy">' + meta[2] + '</p></div></div><div class="compare-grid" id="compare-grid-' + name + '"></div>';
    root.appendChild(section);
  });
}

function makeChart(group, title, yLabel, seriesA, seriesB, labelA, labelB, baseline) {
  var div = document.createElement('div');
  div.className = 'ccard';
  var canvasId = 'cc' + chartInsts.length;
  div.innerHTML = '<h3>' + title + '</h3><div class="chartbox"><canvas id="' + canvasId + '"></canvas></div>';
  document.getElementById('compare-grid-' + group).appendChild(div);
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
  prepareComparisonGroups();
  document.getElementById('exportBar').style.display = 'none';
  document.getElementById('tunnelTip').style.display = 'block';
  document.getElementById('resultTools').style.display = 'flex';
  makeLegend(labelA, labelB);

  var metrics = [
    { group: 'application', col: 'app_cpu_pct',           title: 'App Process CPU (all threads) %',    y: '%' },
    { group: 'application', col: 'main_thread_cpu_pct',   title: 'App Main-Thread CPU %',              y: '%' },
    { group: 'application', col: 'pss_total_mb',          title: 'App PSS Total (MB)',                  y: 'MB' },
    { group: 'pressure', col: 'cpu_psi_some_avg10',    title: 'CPU Pressure PSI avg10 (%)',          y: '% stalled',
      baseline: { value: 0, label: '0 (no stall)' } },
    { group: 'pressure', col: 'mem_psi_some_avg10',    title: 'Memory Pressure PSI some avg10 (%)',  y: '% stalled',
      baseline: { value: 0, label: '0 (no stall)' } },
    { group: 'rendering', col: 'jank_pct',              title: 'UI Jank (% janky frames)',            y: '%',
      baseline: { value: 0, label: '0% (zero dropped frames)' } },
    { group: 'media', col: 'media_mediaserver_cpu', title: 'Media: mediaserver CPU %',            y: '%' },
    { group: 'media', col: 'media_codec_cpu',       title: 'Media: media.codec CPU %',            y: '%' },
    { group: 'media', col: 'media_audioserver_cpu', title: 'Media: audioserver CPU %',            y: '%' },
    { group: 'media', col: 'media_swcodec_cpu',     title: 'Media: vendor.mediaswcodec CPU %',    y: '%' },
    { group: 'media', col: 'media_omx_cpu',         title: 'Media: OMX HAL CPU %',                y: '%' },
    { group: 'system', col: 'cpu_util_pct',          title: 'System CPU Utilization %',            y: '%' }
  ];

  var shown = 0;
  metrics.forEach(function (m) {
    var dA = hasData(rowsA, m.col), dB = hasData(rowsB, m.col);
    if (!dA && !dB) return;
    shown++;
    makeChart(m.group, m.title, m.y,
      dA ? colSeries(rowsA, m.col) : [],
      dB ? colSeries(rowsB, m.col) : [],
      labelA, labelB, m.baseline || null);
  });
  Object.keys(GROUP_META).forEach(function (name) {
    var grid = document.getElementById('compare-grid-' + name);
    document.getElementById('compare-group-' + name).style.display = grid.children.length ? 'block' : 'none';
  });
  if (!shown) {
    document.getElementById('charts').innerHTML = '<div class="empty-comparison">No common metrics found between the two runs. Make sure both runs used --package (or set App package on the Home screen).</div>';
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
  document.getElementById('resultTools').style.display = 'none';

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
