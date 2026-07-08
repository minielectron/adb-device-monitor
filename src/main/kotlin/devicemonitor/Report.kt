package devicemonitor

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.jfree.chart.ChartFactory
import org.jfree.chart.JFreeChart
import org.jfree.chart.plot.PlotOrientation
import org.jfree.chart.plot.ValueMarker
import org.jfree.chart.plot.XYPlot
import org.jfree.data.xy.XYSeries
import org.jfree.data.xy.XYSeriesCollection
import java.awt.BasicStroke
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val PALETTE = listOf(
    Color(0x1f, 0x77, 0xb4), Color(0xd6, 0x27, 0x28),
    Color(0x2c, 0xa0, 0x2c), Color(0xff, 0x7f, 0x0e),
    Color(0x94, 0x67, 0xbd), Color(0x8c, 0x56, 0x4b),
    Color(0x17, 0xbe, 0xcf), Color(0x7f, 0x7f, 0x7f)
)

class PdfReport(
    private val samples: List<Sample>,
    private val info: DeviceInfo,
    private val target: String,
    private val intervalSec: Double,
    private val zapMs: Long? = null,
    private val zapTimes: List<Double> = emptyList(),
    private val packageName: String? = null,
    private val anomalies: List<Anomaly> = emptyList(),
    private val appInfo: Map<String, String> = emptyMap(),
    private val label: String? = null,
    private val loadEnabled: Boolean = true,
    private val pageFaultsEnabled: Boolean = true,
    private val buildTunnelEnabled: Boolean? = null,
    // Always-collected charts don't auto-skip on null data, so gate them explicitly.
    private val cpuUtilEnabled: Boolean = true,
    private val cpuIdleEnabled: Boolean = true,
    private val memoryMbEnabled: Boolean = true,
    private val memoryPctEnabled: Boolean = true,
    private val swapEnabled: Boolean = true,
    private val psiEnabled: Boolean = true,
    private val zoneInfoEnabled: Boolean = true
) {
    private data class ChartSpec(
        val title: String,
        val yLabel: String,
        val series: List<Pair<String, (Sample) -> Double?>>,
        val fixedMaxY: Double? = null,
        val baseline: Double? = null   // green dashed horizontal target line
    )

    private val specs: List<ChartSpec> by lazy { buildList {
        if (packageName != null) {
            add(ChartSpec(
                "App PSS Total - $packageName", "MB",
                listOf("Total PSS" to { s: Sample -> s.pss?.totalMb })
            ))
            // App Summary PSS breakdown (matches dumpsys meminfo categories).
            add(ChartSpec(
                "App PSS breakdown - $packageName", "MB",
                listOf(
                    "Java Heap" to { s: Sample -> s.pss?.javaHeapMb },
                    "Native Heap" to { s: Sample -> s.pss?.nativeHeapMb },
                    "Code" to { s: Sample -> s.pss?.codeMb },
                    "Stack" to { s: Sample -> s.pss?.stackMb },
                    "Graphics" to { s: Sample -> s.pss?.graphicsMb },
                    "Private Other" to { s: Sample -> s.pss?.privateOtherMb },
                    "System" to { s: Sample -> s.pss?.systemMb }
                )
            ))
        }
        addAll(base)

        // Per-core CPU chart: series count depends on the device, so build it from the data.
        val cores = samples.maxOfOrNull { it.perCoreUtilPct?.size ?: 0 } ?: 0
        if (cores > 0) {
            add(ChartSpec(
                "Per-Core CPU Utilization", "%",
                (0 until cores).map { c ->
                    "core$c" to { s: Sample -> s.perCoreUtilPct?.getOrNull(c) }
                },
                fixedMaxY = 100.0
            ))
        }
        add(ChartSpec(
            "Network Throughput", "KB/s",
            listOf(
                "RX" to { s: Sample -> s.netRxKbps },
                "TX" to { s: Sample -> s.netTxKbps }
            )
        ))
        add(ChartSpec("CPU Temperature", "deg C", listOf("hottest CPU/SoC zone" to { s: Sample -> s.cpuTempC })))
        add(ChartSpec("CPU Frequency (mean of cores)", "MHz", listOf("MHz" to { s: Sample -> s.cpuFreqMhz })))
        add(ChartSpec(
            "Major Page Faults", "faults/s",
            listOf("pgmajfault rate" to { s: Sample -> s.majorFaultsPerSec }),
            baseline = 0.0   // 0 = no memory thrashing
        ))
        if (samples.any { it.jankPct != null }) {
            add(ChartSpec(
                "UI Jank (gfxinfo)", "% janky frames",
                listOf("janky %" to { s: Sample -> s.jankPct }),
                fixedMaxY = 100.0,
                baseline = 0.0   // 0% = zero janky frames
            ))
        }
        // App CPU: process (all threads) + main thread on the same chart
        val hasAppCpu = samples.any { it.appCpuPct != null }
        val hasMainCpu = samples.any { it.mainThreadCpuPct != null }
        if (hasAppCpu || hasMainCpu) {
            val cpuSeries = buildList {
                if (hasAppCpu) add("process (all threads)" to { s: Sample -> s.appCpuPct })
                if (hasMainCpu) add("main thread" to { s: Sample -> s.mainThreadCpuPct })
            }
            add(ChartSpec("App CPU — Process vs Main Thread", "%", cpuSeries))
        }
        // Media / HAL process CPU (one series per process)
        val mediaNames = samples.flatMap { it.mediaProcs.keys }.distinct().sorted()
        if (mediaNames.isNotEmpty() && samples.any { s -> s.mediaProcs.values.any { it != null } }) {
            add(ChartSpec(
                "Media & Audio Process CPU", "%",
                mediaNames.map { name -> name to { s: Sample -> s.mediaProcs[name] } }
            ))
        }
        if (samples.any { it.frameLayoutMs != null }) {
            add(ChartSpec(
                "Frame Phases (avg ms per frame)", "ms",
                listOf(
                    "input" to { s: Sample -> s.frameInputMs },
                    "animation" to { s: Sample -> s.frameAnimMs },
                    "measure/layout" to { s: Sample -> s.frameLayoutMs },
                    "draw" to { s: Sample -> s.frameDrawMs }
                ),
                baseline = 4.0   // 4ms/phase keeps total <16.67ms (60fps budget)
            ))
        }
    } }

    private val base: List<ChartSpec> by lazy {
        buildList {
            if (cpuUtilEnabled)
                add(ChartSpec("CPU Utilization", "%", listOf("CPU %" to { s: Sample -> s.cpuUtilPct }), fixedMaxY = 100.0))
            if (cpuIdleEnabled)
                add(ChartSpec("CPU Idle", "%", listOf("idle %" to { s: Sample -> s.cpuIdlePct }), fixedMaxY = 100.0))
            // PSI target: 0 means no tasks stalled — any non-zero value indicates resource pressure.
            if (psiEnabled)
                add(ChartSpec("CPU Pressure (PSI avg10)", "% of time stalled",
                    listOf("some" to { s: Sample -> s.cpuPsiSomeAvg10 }), baseline = 0.0))
            if (memoryMbEnabled)
                add(ChartSpec(
                    "Memory Usage", "MB",
                    listOf(
                        "Used" to { s: Sample -> s.memUsedMb },
                        "Available" to { s: Sample -> s.memAvailableMb },
                        "Cached" to { s: Sample -> s.cachedMb },
                        "Total" to { s: Sample -> s.memTotalMb }
                    )
                ))
            if (memoryPctEnabled)
                add(ChartSpec("Memory Used", "%", listOf("used %" to { s: Sample -> s.memUsedPct }), fixedMaxY = 100.0))
            if (psiEnabled) {
                add(ChartSpec(
                    "Memory Pressure (PSI avg10)", "% of time stalled",
                    listOf(
                        "some" to { s: Sample -> s.memPsiSomeAvg10 },
                        "full" to { s: Sample -> s.memPsiFullAvg10 }
                    ),
                    baseline = 0.0
                ))
                add(ChartSpec("IO Pressure (PSI avg10)", "% of time stalled",
                    listOf("some" to { s: Sample -> s.ioPsiSomeAvg10 }), baseline = 0.0))
            }
            // Load average (its own chart when enabled).
            if (loadEnabled) {
                add(ChartSpec("Load Average (1 min)", "load", listOf("load1" to { s: Sample -> s.load1 })))
            }
            // Swap Used — dedicated chart. Auto-skips on devices with no swap (all-null series).
            if (swapEnabled)
                add(ChartSpec("Swap Used", "MB", listOf("swap used" to { s: Sample -> s.swapUsedMb })))
            // Memory zone free pages vs reclaim watermarks (min/low/high), in pages.
            if (zoneInfoEnabled)
                add(ChartSpec(
                    "Memory Zone: free pages vs watermarks", "pages",
                    listOf(
                        "free" to { s: Sample -> s.zoneFreePages },
                        "high" to { s: Sample -> s.zoneWmHighPages },
                        "low" to { s: Sample -> s.zoneWmLowPages },
                        "min" to { s: Sample -> s.zoneWmMinPages }
                    )
                ))
            // Direct reclaim events/s from logcat. Auto-skips when logcat is off (all-null series).
            add(ChartSpec("Direct Reclaim (memory pressure)", "events/s",
                listOf("reclaim/s" to { s: Sample -> s.directReclaimPerSec }), baseline = 0.0))
        }
    }

    fun write(outFile: File) {
        PDDocument().use { doc ->
            writeSummaryPage(doc)
            writeChartPages(doc)
            doc.save(outFile)
        }
    }

    // ---------- summary page ----------

    private fun writeSummaryPage(doc: PDDocument) {
        val page = PDPage(PDRectangle.A4)
        doc.addPage(page)
        PDPageContentStream(doc, page).use { cs ->
            var y = 790f
            fun line(text: String, font: PDType1Font, size: Float, indent: Float = 40f) {
                cs.beginText()
                cs.setFont(font, size)
                cs.newLineAtOffset(indent, y)
                cs.showText(text)
                cs.endText()
                y -= size + 7f
            }

            val reportTitle = if (label != null) "Android Performance Report — $label" else "Android Device Metrics Report"
            line(reportTitle, PDType1Font.HELVETICA_BOLD, 20f)
            y -= 6f
            val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            line("Generated: $ts", PDType1Font.HELVETICA, 10f)
            line("Target: $target", PDType1Font.HELVETICA, 10f)
            line(
                "Device: ${info.manufacturer} ${info.model}  |  Android ${info.androidVersion} (SDK ${info.sdk})",
                PDType1Font.HELVETICA, 10f
            )
            line(
                "CPU cores: ${info.cpuCores}  |  Total RAM: ${fmt(info.totalMemMb)} MB",
                PDType1Font.HELVETICA, 10f
            )
            if (appInfo.isNotEmpty()) {
                y -= 4f
                val fields = buildList {
                    appInfo["app_version"]?.let { add("App version" to it) }
                    appInfo["device_model"]?.let { add("Device model" to it) }
                    appInfo["os_name"]?.let { add("OS" to it) }
                    appInfo["firmware_version"]?.let { add("Firmware version" to it) }
                    appInfo["account_id"]?.let { add("Account ID" to it) }
                    appInfo["screen_resolution"]?.let { add("Screen resolution" to it) }
                    appInfo["screen_size"]?.let { add("Screen size" to it) }
                }
                y = drawKeyValueTable(cs, y, fields)
                y -= 10f
            }
            val durationSec = samples.lastOrNull()?.elapsedSec ?: 0.0
            line(
                "Samples: ${samples.size}  |  Interval: ${fmt(intervalSec)} s  |  Duration: ${fmt(durationSec)} s",
                PDType1Font.HELVETICA, 10f
            )
            if (packageName != null) {
                line("App tracked: $packageName (PSS via dumpsys meminfo)", PDType1Font.HELVETICA, 10f)
            }
            if (zapMs != null) {
                line(
                    "Zapping: CHANNEL_UP every $zapMs ms  |  ${zapTimes.size} key events sent" +
                        "  (dashed red lines on the charts mark each zap)",
                    PDType1Font.HELVETICA, 10f
                )
            }
            line(
                "Anomalies: ${AnomalyScanner.summary(anomalies)}" +
                    if (anomalies.isNotEmpty()) "  (orange dashed lines on the charts; details in anomalies.log)" else "",
                PDType1Font.HELVETICA, 10f
            )
            // STB playback / tunnel mode summary (only when playback tracking captured data)
            if (samples.any { it.videoState != null || it.tunnelMode != null }) {
                val playing = samples.count { Playback.isPlayingState(it.videoState) }
                val tunneled = samples.count { it.tunnelMode == "tunneled" }
                val nonTunneled = samples.count { it.tunnelMode == "non_tunneled" }
                val playSecs = playing * intervalSec
                val tunnelLabel = when {
                    tunneled > 0 && nonTunneled > 0 -> "mixed (tunneled $tunneled, non-tunneled $nonTunneled samples)"
                    tunneled > 0 -> "tunneled"
                    nonTunneled > 0 -> "non-tunneled"
                    else -> "not observed"
                }
                y -= 4f
                line("Playback (STB):", PDType1Font.HELVETICA_BOLD, 10f)
                val buildFlag = when (buildTunnelEnabled) {
                    true -> "Tunnel enabled in build: YES (isTunneledPlaybackEnabled=true)"
                    false -> "Tunnel enabled in build: NO (isTunneledPlaybackEnabled=false)"
                    null -> "Tunnel enabled in build: not detected in logcat"
                }
                line(buildFlag, PDType1Font.HELVETICA, 10f)
                line(
                    "Video playing: $playing/${samples.size} samples (~${fmt(playSecs)} s)  |  Actual tunnel mode: $tunnelLabel",
                    PDType1Font.HELVETICA, 10f
                )
            }
            y -= 14f
            line("Summary", PDType1Font.HELVETICA_BOLD, 13f)
            y -= 4f

            // Per-core rows are omitted here (the chart shows them).
            val rows = specs.filterNot { it.title.startsWith("Per-Core") }
                .flatMap { spec ->
                    spec.series.map { (name, extractor) ->
                        "${spec.title} - $name" to samples.mapNotNull(extractor)
                    }
                }
            y = drawSummaryTable(cs, y, rows)

            y -= 12f
            line("\"-\" = no data captured (source unreadable on this device, or app not running).", PDType1Font.HELVETICA_OBLIQUE, 8f)
            line("PSI note: pressure metrics require Android 10+ / kernel 4.20+.", PDType1Font.HELVETICA_OBLIQUE, 8f)
            line("avg10 = % of the last 10 s in which tasks stalled waiting for the resource.", PDType1Font.HELVETICA_OBLIQUE, 8f)
        }
    }

    /**
     * Compact bordered key/value table for app/device info extracted from logcat events.
     * Returns the y position below the table.
     */
    private fun drawKeyValueTable(
        cs: PDPageContentStream, topY: Float,
        rows: List<Pair<String, String>>
    ): Float {
        val x = 40f
        val labelW = 140f
        val valueW = 375f
        val w = labelW + valueW
        val rowH = 14f
        val pad = 6f
        var y = topY

        fun cell(t: String, cx: Float, cw: Float, font: PDType1Font, baseY: Float) {
            var s = t
            while (s.isNotEmpty() && font.getStringWidth(s) / 1000f * 8.5f > cw - 2 * pad) s = s.dropLast(1)
            if (s != t) s = s.dropLast(2) + "..."
            cs.beginText(); cs.setFont(font, 8.5f); cs.newLineAtOffset(cx + pad, baseY); cs.showText(s); cs.endText()
        }

        for ((idx, row) in rows.withIndex()) {
            if (idx % 2 == 1) {
                cs.setNonStrokingColor(Color(245, 245, 245))
                cs.addRect(x, y - rowH, w, rowH)
                cs.fill()
                cs.setNonStrokingColor(Color(0, 0, 0))
            }
            val baseY = y - rowH + 4f
            cell(row.first, x, labelW, PDType1Font.HELVETICA_BOLD, baseY)
            cell(row.second, x + labelW, valueW, PDType1Font.HELVETICA, baseY)
            y -= rowH
        }

        cs.setStrokingColor(Color(160, 160, 160))
        cs.setLineWidth(0.6f)
        cs.addRect(x, y, w, topY - y)
        cs.stroke()
        cs.moveTo(x + labelW, y); cs.lineTo(x + labelW, topY); cs.stroke()
        cs.setStrokingColor(Color(0, 0, 0))
        return y
    }

    /**
     * Draws a bordered table: Metric | Min | Avg | Max, with header fill and zebra rows.
     * Returns the y position below the table.
     */
    private fun drawSummaryTable(
        cs: PDPageContentStream, topY: Float,
        rows: List<Pair<String, List<Double>>>
    ): Float {
        val x = 40f
        val cols = floatArrayOf(275f, 80f, 80f, 80f)
        val w = cols.sum()
        val rowH = 15f
        val pad = 6f
        var y = topY

        fun cell(t: String, cx: Float, cw: Float, font: PDType1Font, size: Float, right: Boolean, baseY: Float) {
            val tw = font.getStringWidth(t) / 1000f * size
            val tx = if (right) cx + cw - pad - tw else cx + pad
            cs.beginText(); cs.setFont(font, size); cs.newLineAtOffset(tx, baseY); cs.showText(t); cs.endText()
        }

        fun fit(t: String, font: PDType1Font, size: Float, maxW: Float): String {
            if (font.getStringWidth(t) / 1000f * size <= maxW) return t
            var s = t
            while (s.isNotEmpty() && font.getStringWidth("$s...") / 1000f * size > maxW) s = s.dropLast(1)
            return "$s..."
        }

        // header row
        cs.setNonStrokingColor(Color(225, 225, 225))
        cs.addRect(x, y - rowH, w, rowH)
        cs.fill()
        cs.setNonStrokingColor(Color(0, 0, 0))
        var hx = x
        listOf("Metric" to false, "Min" to true, "Avg" to true, "Max" to true).forEachIndexed { i, (h, right) ->
            cell(h, hx, cols[i], PDType1Font.HELVETICA_BOLD, 9f, right, y - rowH + 4.5f)
            hx += cols[i]
        }
        y -= rowH

        // data rows (zebra striping)
        for ((idx, row) in rows.withIndex()) {
            if (y - rowH < 80f) break
            val (label, values) = row
            if (idx % 2 == 1) {
                cs.setNonStrokingColor(Color(245, 245, 245))
                cs.addRect(x, y - rowH, w, rowH)
                cs.fill()
                cs.setNonStrokingColor(Color(0, 0, 0))
            }
            val baseY = y - rowH + 4.5f
            cell(fit(label, PDType1Font.HELVETICA, 8.5f, cols[0] - 2 * pad), x, cols[0], PDType1Font.HELVETICA, 8.5f, false, baseY)
            val cellsX = floatArrayOf(x + cols[0], x + cols[0] + cols[1], x + cols[0] + cols[1] + cols[2])
            if (values.isEmpty()) {
                for (i in 0..2) cell("-", cellsX[i], cols[i + 1], PDType1Font.HELVETICA, 8.5f, true, baseY)
            } else {
                cell(fmt(values.min()), cellsX[0], cols[1], PDType1Font.HELVETICA, 8.5f, true, baseY)
                cell(fmt(values.average()), cellsX[1], cols[2], PDType1Font.HELVETICA, 8.5f, true, baseY)
                cell(fmt(values.max()), cellsX[2], cols[3], PDType1Font.HELVETICA, 8.5f, true, baseY)
            }
            y -= rowH
        }

        // grid: outer box, header separator, column verticals
        cs.setStrokingColor(Color(160, 160, 160))
        cs.setLineWidth(0.6f)
        cs.addRect(x, y, w, topY - y)
        cs.stroke()
        cs.moveTo(x, topY - rowH); cs.lineTo(x + w, topY - rowH); cs.stroke()
        var vx = x
        for (i in 0 until cols.size - 1) {
            vx += cols[i]
            cs.moveTo(vx, y); cs.lineTo(vx, topY); cs.stroke()
        }
        cs.setStrokingColor(Color(0, 0, 0))
        return y
    }

    // ---------- chart pages ----------

    private fun writeChartPages(doc: PDDocument) {
        val margin = 40f
        val chartW = PDRectangle.A4.width - 2 * margin   // ~515 pt
        val chartH = 318f   // slightly reduced vs 330 to leave room for stats text below
        val w = (chartW * 2).toInt()
        val h = (chartH * 2).toInt()

        // Rasterize the charts in parallel (CPU-bound and independent), preserving order.
        // PDFBox insertion below stays single-threaded (a PDDocument isn't thread-safe).
        val specsWithCharts = specs.mapNotNull { spec -> buildChart(spec)?.let { spec to it } }
        val parallelism = minOf(specsWithCharts.size, Runtime.getRuntime().availableProcessors(), 4).coerceAtLeast(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(parallelism)
        val rendered: List<Pair<ChartSpec, BufferedImage>> = try {
            specsWithCharts.map { (spec, chart) ->
                pool.submit(java.util.concurrent.Callable { spec to chart.createBufferedImage(w, h) })
            }.map { it.get() }
        } finally {
            pool.shutdown()
        }

        rendered.chunked(2).forEach { pair ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                pair.forEachIndexed { i, (spec, img) ->
                    val pdImg = LosslessFactory.createFromImage(doc, img)
                    val yPos = if (i == 0) PDRectangle.A4.height - margin - chartH
                               else PDRectangle.A4.height - margin - 2 * chartH - 30f
                    cs.drawImage(pdImg, margin, yPos, chartW, chartH)
                    // Min / Avg / Max stats strip below the chart image
                    drawChartStats(cs, margin, yPos, chartW, spec)
                }
            }
        }
    }

    /**
     * Draws a compact Min / Avg / Max stats text strip just below the chart image.
     * [chartBottomY] is the y-coordinate of the bottom edge of the chart image in PDF space.
     * Supports up to 4 series laid out in up to 2 columns.
     */
    private fun drawChartStats(cs: PDPageContentStream, x: Float, chartBottomY: Float, width: Float, spec: ChartSpec) {
        // entries: list of (seriesName, Triple(min, avg, max))
        val entries = spec.series.mapNotNull { (name, extractor) ->
            val vals = samples.mapNotNull(extractor)
            if (vals.isEmpty()) null else name to Triple(vals.min(), vals.average(), vals.max())
        }
        if (entries.isEmpty()) return

        cs.setFont(PDType1Font.HELVETICA, 7f)
        cs.setNonStrokingColor(Color(80, 80, 80))

        val cols = if (entries.size > 2) 2 else entries.size
        val colW = width / cols
        val textY = chartBottomY - 9f   // first line sits 9 pt below chart bottom

        entries.take(4).forEachIndexed { idx, (name, stats) ->
            val (min, avg, max) = stats
            val col = idx % cols
            val row = idx / cols
            val prefix = if (spec.series.size > 1) "$name: " else ""
            val text = "${prefix}min ${fmt(min)}   avg ${fmt(avg)}   max ${fmt(max)}"
            cs.beginText()
            cs.newLineAtOffset(x + col * colW, textY - row * 9f)
            cs.showText(text)
            cs.endText()
        }

        cs.setNonStrokingColor(Color(0, 0, 0))
    }

    private fun buildChart(spec: ChartSpec): JFreeChart? {
        val dataset = XYSeriesCollection()
        var hasData = false
        for ((name, extractor) in spec.series) {
            val series = XYSeries(name)
            for (s in samples) {
                extractor(s)?.let {
                    series.add(s.elapsedSec, it)
                    hasData = true
                }
            }
            if (series.itemCount > 0) dataset.addSeries(series)
        }
        if (!hasData) return null

        val chart = ChartFactory.createXYLineChart(
            spec.title, "Time (s)", spec.yLabel, dataset,
            PlotOrientation.VERTICAL, spec.series.size > 1, false, false
        )
        styleChart(chart, spec)
        return chart
    }

    private fun styleChart(chart: JFreeChart, spec: ChartSpec) {
        chart.backgroundPaint = Color.WHITE
        val plot = chart.plot as XYPlot
        plot.backgroundPaint = Color.WHITE
        plot.domainGridlinePaint = Color(220, 220, 220)
        plot.rangeGridlinePaint = Color(220, 220, 220)
        val renderer = plot.renderer
        for (i in 0 until (plot.dataset?.seriesCount ?: 0)) {
            renderer.setSeriesPaint(i, PALETTE[i % PALETTE.size])
            renderer.setSeriesStroke(i, BasicStroke(2.2f))
        }
        spec.fixedMaxY?.let { plot.rangeAxis.setRange(0.0, it) }

        // Vertical dashed markers at every CHANNEL_UP zap event.
        val zapStroke = BasicStroke(
            1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
            1.0f, floatArrayOf(4f, 4f), 0f
        )
        for (t in zapTimes) {
            val marker = ValueMarker(t)
            marker.paint = Color(200, 60, 60, 140)
            marker.stroke = zapStroke
            plot.addDomainMarker(marker)
        }


        // Green dashed horizontal baseline — Android idle/target reference.
        spec.baseline?.let { bl ->
            val blMarker = ValueMarker(bl)
            blMarker.paint = Color(0, 150, 0, 210)
            blMarker.stroke = BasicStroke(
                1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, floatArrayOf(7f, 4f), 0f
            )
            if (bl > 0) {
                blMarker.label = "target: ${fmt(bl)}"
                blMarker.labelFont = java.awt.Font("SansSerif", java.awt.Font.PLAIN, 9)
                blMarker.labelPaint = Color(0, 120, 0)
            }
            plot.addRangeMarker(blMarker)
        }

        // Orange dashed markers at every flagged logcat anomaly.
        for (a in anomalies) {
            val t = a.elapsedSec ?: continue
            val marker = ValueMarker(t)
            marker.paint = Color(255, 140, 0, 160)
            marker.stroke = zapStroke
            marker.label = a.type
            marker.labelFont = java.awt.Font("SansSerif", java.awt.Font.PLAIN, 9)
            marker.labelPaint = Color(190, 100, 0)
            plot.addDomainMarker(marker)
        }
    }

    private fun fmt(v: Double): String = String.format("%.1f", v)
}
