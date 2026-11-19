package io.github.iw.durableatlas.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.parse
import kotlin.system.exitProcess

/**
 * Runs the tool and ends the JVM with the command's exit status.
 *
 * The exit is explicit on every path. Analysing sources starts threads that are not daemons, and
 * they would keep the process alive after the command has finished, or has failed.
 */
fun main(args: Array<String>) {
    val command = durableAtlas()
    val status = try {
        command.parse(args)
        ExitCode.SUCCESS
    } catch (e: CliktError) {
        command.echoFormattedHelp(e)
        e.statusCode
    } catch (e: Throwable) {
        System.err.println("durable-atlas: unexpected failure")
        e.printStackTrace()
        ExitCode.FAILURE
    }
    exitProcess(status)
}
