package com.smartmanager.whatsappshoot.licensetool

import java.awt.GraphicsEnvironment
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/** No arguments (or `gui`) opens the License Manager window; any other arguments run the command line. */
fun main(args: Array<String>) {
    if ((args.isEmpty() || args[0] == "gui") && !GraphicsEnvironment.isHeadless()) {
        SwingUtilities.invokeLater { LicenseGui().show() }
        return
    }
    val tool = LicenseTool(
        out = System.out,
        err = System.err,
        env = System.getenv(),
        readPassphrase = { prompt -> System.console()?.readPassword(prompt) },
    )
    exitProcess(tool.run(args.toList().let { if (it.firstOrNull() == "gui") emptyList() else it }))
}
