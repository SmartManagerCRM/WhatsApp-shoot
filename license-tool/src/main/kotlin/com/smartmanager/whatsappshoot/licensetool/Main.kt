package com.smartmanager.whatsappshoot.licensetool

import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val tool = LicenseTool(
        out = System.out,
        err = System.err,
        env = System.getenv(),
        readPassphrase = { prompt -> System.console()?.readPassword(prompt) },
    )
    exitProcess(tool.run(args.toList()))
}
