package devicemonitor.presentation.web

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
