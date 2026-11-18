package devicemonitor.data.device

import devicemonitor.data.adb.Adb
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.time.Instant
import javax.imageio.ImageIO
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

data class LayoutBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2
}

data class LayoutElement(
    val index: Int,
    val depth: Int,
    val className: String,
    val resourceId: String?,
    val text: String?,
    val contentDescription: String?,
    val packageName: String?,
    val bounds: LayoutBounds,
    val enabled: Boolean,
    val clickable: Boolean,
    val focusable: Boolean,
    val focused: Boolean,
    val scrollable: Boolean,
    val selected: Boolean,
    val checked: Boolean,
    val password: Boolean
) {
    val type: String get() = className.substringAfterLast('.').ifBlank { "Unknown" }
}

data class LayoutHierarchy(
    val elements: List<LayoutElement>,
    val rotation: Int?,
    val truncated: Boolean
)

data class LayoutInspectorSnapshot(
    val serial: String,
    val capturedAt: Instant,
    val screenWidth: Int,
    val screenHeight: Int,
    val hierarchy: LayoutHierarchy,
    val screenshotPng: ByteArray
)

object LayoutInspectorCapture {
    private const val MAX_XML_BYTES = 8 * 1024 * 1024
    private const val MAX_PNG_BYTES = 16 * 1024 * 1024
    internal const val MAX_NODES = 5_000
    private val boundsPattern = Regex("\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]")

    fun capture(adb: Adb, serial: String, progress: (Int) -> Unit = {}): LayoutInspectorSnapshot {
        require(adb.devices().any { it.serial == serial && it.state == "device" }) {
            "Selected device is not authorized or connected"
        }
        progress(8)
        val token = System.nanoTime().toString().replace("-", "")
        val remoteXml = "/data/local/tmp/adb_layout_$token.xml"
        val xml = adb.shell(
            serial,
            "uiautomator dump $remoteXml >/dev/null 2>&1; cat $remoteXml; rm -f $remoteXml",
            15
        )
        require(xml.isNotBlank()) { "Android returned an empty UI hierarchy" }
        require(xml.toByteArray().size <= MAX_XML_BYTES) { "UI hierarchy exceeds 8 MiB" }
        val hierarchy = parse(xml)
        require(hierarchy.elements.isNotEmpty()) { "UI hierarchy contains no inspectable elements" }
        progress(48)

        val remotePng = "/data/local/tmp/adb_layout_$token.png"
        val localPng = Files.createTempFile("adb-layout-", ".png")
        val bytes = try {
            adb.shell(serial, "screencap -p $remotePng", 10)
            adb.pull(serial, remotePng, localPng.toString())
            require(Files.isRegularFile(localPng)) { "Screenshot pull produced no file" }
            require(Files.size(localPng) in 1..MAX_PNG_BYTES.toLong()) { "Screenshot is empty or exceeds 16 MiB" }
            Files.readAllBytes(localPng)
        } finally {
            runCatching { adb.shell(serial, "rm -f $remotePng", 5) }
            Files.deleteIfExists(localPng)
        }
        require(bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)) {
            "Screenshot is not a valid PNG"
        }
        val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: error("Screenshot PNG could not be decoded")
        require(image.width > 0 && image.height > 0) { "Screenshot has invalid dimensions" }
        progress(82)
        return LayoutInspectorSnapshot(serial, Instant.now(), image.width, image.height, hierarchy, bytes)
    }

    internal fun parse(xml: String, maxNodes: Int = MAX_NODES): LayoutHierarchy {
        require(maxNodes > 0) { "Node limit must be positive" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        val document = factory.newDocumentBuilder().parse(xml.byteInputStream())
        val root = document.documentElement ?: error("UI hierarchy has no root element")
        val elements = ArrayList<LayoutElement>(minOf(maxNodes, 512))
        var totalNodes = 0

        fun visit(node: Node, depth: Int) {
            if (node.nodeType != Node.ELEMENT_NODE) return
            val element = node as Element
            if (element.tagName == "node") {
                totalNodes++
                if (elements.size < maxNodes) parseElement(element, elements.size, depth)?.let(elements::add)
            }
            val children = element.childNodes
            for (i in 0 until children.length) visit(children.item(i), if (element.tagName == "node") depth + 1 else depth)
        }
        visit(root, 0)
        return LayoutHierarchy(elements, root.getAttribute("rotation").toIntOrNull(), totalNodes > maxNodes)
    }

    private fun parseElement(element: Element, index: Int, depth: Int): LayoutElement? {
        val match = boundsPattern.matchEntire(element.getAttribute("bounds")) ?: return null
        val values = match.groupValues.drop(1).mapNotNull(String::toIntOrNull)
        if (values.size != 4 || values[2] < values[0] || values[3] < values[1]) return null
        val password = element.bool("password")
        fun visible(name: String): String? = if (password) "Protected" else element.attr(name)
        return LayoutElement(
            index = index,
            depth = depth,
            className = element.attr("class") ?: "Unknown",
            resourceId = element.attr("resource-id"),
            text = visible("text"),
            contentDescription = visible("content-desc"),
            packageName = element.attr("package"),
            bounds = LayoutBounds(values[0], values[1], values[2], values[3]),
            enabled = element.bool("enabled"),
            clickable = element.bool("clickable"),
            focusable = element.bool("focusable"),
            focused = element.bool("focused"),
            scrollable = element.bool("scrollable"),
            selected = element.bool("selected"),
            checked = element.bool("checked"),
            password = password
        )
    }

    private fun Element.attr(name: String): String? = getAttribute(name).trim().ifBlank { null }
    private fun Element.bool(name: String): Boolean = getAttribute(name).equals("true", ignoreCase = true)

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
}
