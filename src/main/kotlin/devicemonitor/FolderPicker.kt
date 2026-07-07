package devicemonitor

import java.awt.FileDialog
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.io.File
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.SwingUtilities

/**
 * Native "choose a folder" dialog. Because the app runs locally on the user's Mac, the Java
 * process can pop a real OS folder picker and hand the chosen absolute path back to the browser
 * (a browser file input can't expose an absolute server-side path, so this is the way to do it).
 *
 * On macOS we use AWT FileDialog with the Apple "directories" flag (native Cocoa panel); elsewhere
 * we fall back to Swing's JFileChooser in directories-only mode. Headless JVMs return null.
 */
object FolderPicker {

    fun isSupported(): Boolean = !GraphicsEnvironment.isHeadless()

    /** Show the picker (blocking). Returns the chosen absolute path, or null if cancelled/unsupported. */
    fun choose(initial: String?): String? {
        if (GraphicsEnvironment.isHeadless()) return null
        val isMac = System.getProperty("os.name", "").lowercase().contains("mac")
        val result = arrayOfNulls<String>(1)
        val task = Runnable {
            result[0] = if (isMac) chooseMac(initial) else chooseSwing(initial)
        }
        if (SwingUtilities.isEventDispatchThread()) task.run()
        else SwingUtilities.invokeAndWait(task)
        return result[0]
    }

    private fun chooseMac(initial: String?): String? {
        val prev = System.getProperty("apple.awt.fileDialogForDirectories")
        System.setProperty("apple.awt.fileDialogForDirectories", "true")
        val parent = Frame().apply { isAlwaysOnTop = true }
        return try {
            val dlg = FileDialog(parent, "Choose Reports Folder", FileDialog.LOAD)
            if (!initial.isNullOrBlank() && File(initial).isDirectory) dlg.directory = initial
            dlg.toFront()
            dlg.isVisible = true   // blocks until the user picks or cancels
            val dir = dlg.directory
            val name = dlg.file
            when {
                dir != null && name != null -> File(dir, name).absolutePath
                dir != null -> dir
                else -> null
            }
        } finally {
            parent.dispose()
            if (prev != null) System.setProperty("apple.awt.fileDialogForDirectories", prev)
            else System.clearProperty("apple.awt.fileDialogForDirectories")
        }
    }

    private fun chooseSwing(initial: String?): String? {
        val frame = JFrame().apply { isAlwaysOnTop = true }
        return try {
            val chooser = JFileChooser().apply {
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                dialogTitle = "Choose Reports Folder"
                if (!initial.isNullOrBlank() && File(initial).isDirectory) currentDirectory = File(initial)
            }
            if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION)
                chooser.selectedFile?.absolutePath
            else null
        } finally {
            frame.dispose()
        }
    }
}
