package devicemonitor.common

/** Build identifier read from the jar manifest — lets you confirm which build is running. */
fun buildTag(): String = try {
    val mf = java.util.jar.Manifest(object {}.javaClass.getResourceAsStream("/META-INF/MANIFEST.MF"))
    val ver = mf.mainAttributes.getValue("Implementation-Version") ?: "1.0.0"
    val bt = mf.mainAttributes.getValue("Build-Time") ?: "?"
    "v$ver (build $bt)"
} catch (e: Exception) {
    "v1.0.0"
}
