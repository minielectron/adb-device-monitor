package devicemonitor.presentation.report

import devicemonitor.data.playerdebug.PlayerDebugManifest
import devicemonitor.data.playerdebug.PlayerDebugRedactor
import devicemonitor.domain.playerdebug.*
import java.io.File
import java.util.Locale

data class PlayerHandoffMapNode(
    val key: String,
    val label: String,
    val delivery: String?,
    val role: PlayerRole,
    val missing: Boolean = false
)

data class PlayerHandoffMapEdge(
    val id: String,
    val sourceKey: String,
    val targetKey: String,
    val reason: String?,
    val durationNs: Long?,
    val result: HandoffResult,
    val overlapNs: Long?,
    val incidentCount: Int
)

data class PlayerHandoffMap(val nodes: List<PlayerHandoffMapNode>, val edges: List<PlayerHandoffMapEdge>)

/** Static, sanitized Player Debugger report. It contains no scripts, remote assets, or raw evidence. */
object PlayerDebugReport {
    fun handoffMap(graph: PlayerSessionGraph, incidents: List<PlayerIncident>): PlayerHandoffMap {
        val nodes = linkedMapOf<String, PlayerHandoffMapNode>()
        val edges = mutableListOf<PlayerHandoffMapEdge>()
        graph.viewingSessions.forEach { session ->
            fun key(id: String) = "${session.id}:$id"
            session.playerInstances.forEachIndexed { index, player ->
                nodes[key(player.id)] = PlayerHandoffMapNode(
                    key(player.id),
                    summaryText(player.delivery ?: player.family ?: player.implementation ?: "Player ${index + 1}"),
                    player.delivery,
                    player.role
                )
            }
            session.handoffs.forEach { handoff ->
                val sourceKey = handoff.sourceInstanceId?.let(::key) ?: "${session.id}:missing-source:${handoff.id}"
                val targetKey = handoff.targetInstanceId?.let(::key) ?: "${session.id}:missing-target:${handoff.id}"
                nodes.putIfAbsent(sourceKey, PlayerHandoffMapNode(sourceKey, "Unknown source", null, PlayerRole.UNKNOWN, true))
                nodes.putIfAbsent(targetKey, PlayerHandoffMapNode(targetKey, "Unknown target", null, PlayerRole.UNKNOWN, true))
                val affected = setOfNotNull(handoff.sourceInstanceId, handoff.targetInstanceId)
                val edgeEnd = listOfNotNull(handoff.sourceReleasedAtNs, handoff.activatedAtNs, handoff.requestedAtNs).max()
                val linkedIncidents = incidents.count { incident ->
                    incident.handoffId == handoff.id || (
                        incident.handoffId == null && incident.instanceIds.any(affected::contains) &&
                            incident.startedAtNs in handoff.requestedAtNs..edgeEnd
                    )
                }
                edges += PlayerHandoffMapEdge(
                    handoff.id,
                    sourceKey,
                    targetKey,
                    handoff.reason?.let(::summaryText),
                    handoff.activatedAtNs?.minus(handoff.requestedAtNs),
                    handoff.result,
                    handoff.overlapNs,
                    linkedIncidents
                )
            }
        }
        return PlayerHandoffMap(nodes.values.toList(), edges)
    }

    fun writeHtml(
        manifest: PlayerDebugManifest,
        graph: PlayerSessionGraph,
        incidents: List<PlayerIncident>,
        file: File
    ) {
        file.parentFile?.mkdirs()
        file.writeText(html(manifest, graph, incidents))
    }

    fun html(manifest: PlayerDebugManifest, graph: PlayerSessionGraph, incidents: List<PlayerIncident>): String {
        val map = handoffMap(graph, incidents)
        val players = graph.viewingSessions.sumOf { it.playerInstances.size }
        val capabilities = graph.processEpochs.flatMap { it.capabilities }.distinct().size
        val sourceCounts = EvidenceSource.entries.associateWith { source -> graph.events.count { it.source == source } }
        val timelineCounts = graph.events.groupingBy(::timelineCategory).eachCount().toList().sortedByDescending { it.second }
        val orderedIncidents = incidents.sortedWith(compareByDescending<PlayerIncident> { it.severity.ordinal }.thenBy { it.startedAtNs })
        return """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src data:; base-uri 'none'; form-action 'none'">
<title>Player Debugger report</title><style>${reportCss()}</style></head><body>
<main><header><p class="eyebrow">Offline playback evidence</p><h1>Player Debugger report</h1><p class="lede">A sanitized, deterministic summary generated locally by ADB Performance Studio.</p></header>
<section class="kpis" aria-label="Capture summary">
${kpi(manifest.state.name.lowercase().replaceFirstChar(Char::uppercase), "Capture state")}${kpi(graph.events.size.toString(), "Events")}${kpi(graph.viewingSessions.size.toString(), "Sessions")}${kpi(players.toString(), "Players")}${kpi(map.edges.size.toString(), "Handoffs")}${kpi(incidents.size.toString(), "Incidents")}
</section>
<section class="panel"><div class="section-head"><div><p class="eyebrow">Capture</p><h2>Summary</h2></div><span class="status">${h(manifest.packageName)}</span></div><div class="facts"><div><span>Capture</span><b>${h(manifest.captureId)}</b></div><div><span>Started</span><b>${h(manifest.startedAt)}</b></div><div><span>Updated</span><b>${h(manifest.updatedAt)}</b></div><div><span>Replay health</span><b>${graph.duplicateEvents} duplicate · ${graph.lateEvents} late</b></div></div></section>
<section class="panel"><div class="section-head"><div><p class="eyebrow">Coverage</p><h2>Evidence availability</h2></div><span class="status">$capabilities adapter signals</span></div><div class="coverage">${sourceCounts.entries.joinToString("") { coverage(it.key, it.value) }}</div></section>
<section class="panel"><div class="section-head"><div><p class="eyebrow">Directed transitions</p><h2>Handoff Map</h2></div><span class="status">${map.nodes.size} nodes · ${map.edges.size} edges</span></div>${handoffSvg(map)}${edgeList(map)}</section>
<section class="panel"><div class="section-head"><div><p class="eyebrow">Normalized events</p><h2>Timeline summary</h2></div><span class="status">Raw values omitted</span></div><div class="timeline">${timelineCounts.joinToString("") { "<div><span>${h(it.first)}</span><b>${it.second}</b></div>" }.ifEmpty { "<p class=empty>No timeline evidence was available.</p>" }}</div></section>
<section class="panel"><div class="section-head"><div><p class="eyebrow">Explainable findings</p><h2>Incident Lens</h2></div><span class="status">${orderedIncidents.size} findings</span></div><div class="incidents">${orderedIncidents.mapIndexed { index, incident -> incidentCard(index + 1, incident) }.joinToString("").ifEmpty { "<p class=empty>No deterministic incident matched this capture.</p>" }}</div></section>
<footer>Generated offline · No remote assets · Raw logs, device identifiers, tokens and URLs are excluded.</footer></main></body></html>"""
    }

    private fun handoffSvg(map: PlayerHandoffMap): String {
        if (map.nodes.isEmpty()) return "<p class=empty>No player instances or handoffs were reconstructed.</p>"
        val columns = minOf(4, maxOf(1, map.nodes.size))
        val rows = (map.nodes.size + columns - 1) / columns
        val width = 960
        val height = maxOf(220, 80 + rows * 130)
        val positions = map.nodes.mapIndexed { index, node ->
            node.key to Pair(120 + (index % columns) * 230, 72 + (index / columns) * 130)
        }.toMap()
        fun boundary(from: Pair<Int, Int>, to: Pair<Int, Int>): Pair<Int, Int> {
            val dx = (to.first - from.first).toDouble()
            val dy = (to.second - from.second).toDouble()
            if (dx == 0.0 && dy == 0.0) return from
            val scale = minOf(if (dx == 0.0) Double.MAX_VALUE else 82.0 / kotlin.math.abs(dx), if (dy == 0.0) Double.MAX_VALUE else 31.0 / kotlin.math.abs(dy))
            return Pair(kotlin.math.round(from.first + dx * scale).toInt(), kotlin.math.round(from.second + dy * scale).toInt())
        }
        val paths = map.edges.mapIndexed { index, edge ->
            val source = positions.getValue(edge.sourceKey)
            val target = positions.getValue(edge.targetKey)
            val cls = edge.result.name.lowercase()
            if (edge.sourceKey == edge.targetKey) {
                "<path class=\"edge $cls\" d=\"M ${source.first + 55} ${source.second - 31} C ${source.first + 115} ${source.second - 105}, ${source.first - 115} ${source.second - 105}, ${source.first - 55} ${source.second - 31}\" marker-end=\"url(#arrow)\"/><text class=\"edge-number\" x=\"${source.first}\" y=\"${source.second - 82}\">${index + 1}</text>"
            } else {
                val start = boundary(source, target)
                val end = boundary(target, source)
                val mx = (source.first + target.first) / 2
                val my = (source.second + target.second) / 2 - 14 - (index % 2) * 12
                "<path class=\"edge $cls\" d=\"M ${start.first} ${start.second} Q $mx $my ${end.first} ${end.second}\" marker-end=\"url(#arrow)\"/><text class=\"edge-number\" x=\"$mx\" y=\"${my - 5}\">${index + 1}</text>"
            }
        }.joinToString("")
        val nodes = map.nodes.joinToString("") { node ->
            val (x, y) = positions.getValue(node.key)
            "<g class=\"node${if (node.missing) " missing" else ""}\" transform=\"translate(${x - 82} ${y - 31})\"><title>${h(node.label)} · ${h(node.role.name.lowercase())}</title><rect width=\"164\" height=\"62\" rx=\"14\"/><text class=\"node-label\" x=\"82\" y=\"27\">${h(short(node.label, 22))}</text><text class=\"node-meta\" x=\"82\" y=\"45\">${h(node.role.name.lowercase())}</text></g>"
        }
        return "<div class=\"map-scroll\"><svg class=\"map\" viewBox=\"0 0 $width $height\" role=\"img\" aria-label=\"Directed player handoff map\"><defs><marker id=\"arrow\" markerWidth=\"8\" markerHeight=\"8\" refX=\"7\" refY=\"4\" orient=\"auto\"><path d=\"M0,0 L8,4 L0,8 z\"/></marker></defs>$paths$nodes</svg></div>"
    }

    private fun edgeList(map: PlayerHandoffMap): String {
        if (map.edges.isEmpty()) return "<p class=empty>No handoff transitions were observed.</p>"
        val nodes = map.nodes.associateBy { it.key }
        return "<div class=\"edges\">" + map.edges.mapIndexed { index, edge ->
            val from = nodes.getValue(edge.sourceKey).label
            val to = nodes.getValue(edge.targetKey).label
            "<article><div class=\"edge-title\"><i>${index + 1}</i><b>${h(from)} → ${h(to)}</b><span class=\"${edge.result.name.lowercase()}\">${h(edge.result.name.lowercase())}</span></div><div class=chips>${chip("Reason", edge.reason ?: "Unavailable")}${chip("Activation", duration(edge.durationNs))}${chip("Overlap", duration(edge.overlapNs))}${chip("Incidents", edge.incidentCount.toString())}</div></article>"
        }.joinToString("") + "</div>"
    }

    private fun incidentCard(number: Int, incident: PlayerIncident): String {
        val artifacts = incident.evidence.artifacts.map { summaryText(File(it.artifact).name) }.distinct()
        val evidence = buildList {
            if (incident.evidence.eventSequences.isNotEmpty()) add("${incident.evidence.eventSequences.size} linked event(s)")
            artifacts.forEach { add("Evidence: $it") }
        }
        return "<article class=\"incident ${incident.severity.name.lowercase()}\"><div class=\"incident-head\"><i>$number</i><div><h3>${h(summaryText(incident.title))}</h3><p>${h(summaryText(incident.evidence.explanation))}</p></div><span>${h(incident.severity.name.lowercase())} · ${Math.round(incident.confidence * 100)}%</span></div>${if (evidence.isEmpty()) "" else "<div class=evidence>${evidence.joinToString("") { "<code>${h(it)}</code>" }}</div>"}${if (incident.suggestedChecks.isEmpty()) "" else "<ul>${incident.suggestedChecks.joinToString("") { "<li>${h(summaryText(it))}</li>" }}</ul>"}</article>"
    }

    private fun timelineCategory(event: PlaybackEvent): String {
        val type = event.type.lowercase(Locale.US)
        return when {
            type.startsWith("session.") -> "Sessions"
            "handoff" in type || "fallback" in type -> "Handoffs"
            "error" in type || "crash" in type || "anr" in type || "failure" in type -> "Errors"
            type.startsWith("player.") || type.startsWith("process.") || type.startsWith("app.") -> "Players"
            "state" in type -> "States"
            "buffer" in type -> "Buffering"
            "first_frame" in type || "first.frame" in type || "video_available" in type -> "First frame"
            "snapshot" in type || "dumpsys" in type -> "Snapshots"
            "audio" in type || "video" in type || "render" in type || "surface" in type -> "Audio / video"
            "quality" in type || "bitrate" in type || "dropped" in type || "decoder.stall" in type -> "Quality"
            "metric" in type || event.measurements.keys.any { it.contains(Regex("cpu|memory|psi|network|thermal|load|process", RegexOption.IGNORE_CASE)) } -> "Performance"
            else -> "Other evidence"
        }
    }

    private fun coverage(source: EvidenceSource, count: Int): String {
        val label = when (source) {
            EvidenceSource.APP_REPORTED -> "Reported"
            EvidenceSource.DEVICE_MEASURED -> "Measured"
            EvidenceSource.LOG_PARSED -> "Log parsed"
            EvidenceSource.ENGINE_INFERRED -> "Inferred"
        }
        return "<div><i>${label.first()}</i><span>$label</span><b>$count</b></div>"
    }

    private fun reportCss() = """
:root{color-scheme:dark;--bg:#090a0b;--panel:#121313;--line:#2b2d2d;--text:#f2efe8;--muted:#aaa59c;--quiet:#77736d;--gold:#e8b66d;--violet:#9e8cff;--teal:#58d6c7;--red:#ff7676;--green:#70d6a1}*{box-sizing:border-box}body{margin:0;background:radial-gradient(circle at 8% 0%,#211b12 0,transparent 30%),radial-gradient(circle at 92% 8%,#161426 0,transparent 28%),var(--bg);color:var(--text);font:14px/1.5 Inter,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif}main{width:min(1180px,calc(100% - 32px));margin:auto;padding:52px 0 36px}header{padding:0 4px 24px}h1{margin:7px 0 8px;font-size:clamp(34px,5vw,64px);line-height:.98;letter-spacing:-.055em}h2,h3,p{margin-top:0}.lede{max-width:680px;color:var(--muted)}.eyebrow{margin:0;color:var(--gold);font:800 10px/1.2 ui-monospace,monospace;letter-spacing:.14em;text-transform:uppercase}.panel{margin-top:16px;padding:22px;border:1px solid var(--line);border-radius:18px;background:rgba(18,19,19,.94);box-shadow:0 16px 42px rgba(0,0,0,.2)}.section-head{display:flex;align-items:flex-start;justify-content:space-between;gap:18px;margin-bottom:18px}.section-head h2{margin:7px 0 0;font-size:21px}.status{padding:7px 10px;border:1px solid var(--line);border-radius:9px;color:var(--muted);font-size:10px}.kpis{display:grid;grid-template-columns:repeat(6,minmax(0,1fr));gap:9px}.kpis div{padding:15px;border:1px solid var(--line);border-radius:14px;background:rgba(18,19,19,.86)}.kpis b{display:block;font-size:24px}.kpis span,.facts span{display:block;color:var(--quiet);font-size:9px;letter-spacing:.09em;text-transform:uppercase}.facts{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:10px}.facts div{padding:12px;border:1px solid var(--line);border-radius:12px}.facts b{display:block;margin-top:5px;overflow-wrap:anywhere;font-size:11px}.coverage{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:10px}.coverage div{display:grid;grid-template-columns:auto 1fr auto;align-items:center;gap:9px;padding:11px;border:1px solid var(--line);border-radius:12px;color:var(--muted)}.coverage i{display:grid;place-items:center;width:22px;height:22px;border-radius:50%;color:#111;background:var(--teal);font-style:normal;font-weight:900}.coverage b{color:var(--text)}.map-scroll{overflow:auto;border:1px solid var(--line);border-radius:14px;background:#0c0d0d}.map{display:block;width:100%;min-width:700px;max-height:520px}.edge{fill:none;stroke:var(--violet);stroke-width:2}.edge.failed,.edge.looped{stroke:var(--red)}.edge.succeeded{stroke:var(--teal)}marker path{fill:var(--gold)}.edge-number{fill:var(--gold);font:800 12px ui-monospace,monospace;text-anchor:middle}.node rect{fill:#18191a;stroke:#504a63}.node.missing rect{stroke-dasharray:6 5;stroke:#676767}.node-label{fill:var(--text);font:750 12px sans-serif;text-anchor:middle}.node-meta{fill:var(--quiet);font:9px ui-monospace,monospace;text-anchor:middle;text-transform:uppercase}.edges,.incidents{display:grid;gap:9px;margin-top:12px}.edges article,.incident{padding:13px;border:1px solid var(--line);border-radius:13px;background:#0d0e0e}.edge-title{display:flex;align-items:center;gap:9px}.edge-title i,.incident-head>i{display:grid;place-items:center;width:23px;height:23px;flex:none;border-radius:50%;color:#111;background:var(--gold);font-style:normal;font-weight:900}.edge-title b{flex:1}.edge-title span{font-size:9px;text-transform:uppercase}.edge-title .failed,.edge-title .looped{color:var(--red)}.edge-title .succeeded{color:var(--green)}.chips,.evidence{display:flex;gap:6px;flex-wrap:wrap;margin-top:9px}.chips span,.evidence code{padding:5px 7px;border:1px solid var(--line);border-radius:7px;color:var(--quiet);font:9px ui-monospace,monospace}.timeline{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:8px}.timeline div{display:flex;justify-content:space-between;padding:10px;border:1px solid var(--line);border-radius:10px;color:var(--muted)}.timeline b{color:var(--text)}.incident-head{display:flex;align-items:flex-start;gap:10px}.incident-head div{flex:1}.incident h3{margin:1px 0 5px;font-size:13px}.incident p{margin:0;color:var(--muted);font-size:10px}.incident-head>span{color:var(--quiet);font-size:9px;text-transform:uppercase}.incident.warning{border-left:3px solid var(--gold)}.incident.critical{border-left:3px solid var(--red)}.incident.info{border-left:3px solid var(--teal)}.incident ul{margin:9px 0 0;padding-left:18px;color:var(--muted);font-size:10px}.empty{padding:22px;color:var(--quiet);text-align:center}footer{padding:25px 5px 0;color:var(--quiet);font-size:9px;text-align:center}@media(max-width:760px){main{width:min(100% - 20px,1180px);padding-top:28px}.kpis{grid-template-columns:repeat(2,1fr)}.facts,.coverage,.timeline{grid-template-columns:1fr}.section-head{flex-direction:column}.panel{padding:16px}.incident-head{flex-wrap:wrap}}
""".trimIndent()

    private fun kpi(value: String, label: String) = "<div><b>${h(value)}</b><span>${h(label)}</span></div>"
    private fun chip(label: String, value: String) = "<span>${h(label)} · ${h(value)}</span>"
    private fun duration(ns: Long?): String = ns?.let { "%.2f s".format(Locale.US, it / 1_000_000_000.0) } ?: "Unavailable"
    private fun short(value: String, limit: Int) = if (value.length <= limit) value else value.take(limit - 1) + "…"
    private fun summaryText(value: String): String = PlayerDebugRedactor.redactText(value).value
        .replace(Regex("https?://\\S+", RegexOption.IGNORE_CASE), "[REDACTED URL]")
    private fun h(value: Any?): String = value?.toString().orEmpty().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
}
