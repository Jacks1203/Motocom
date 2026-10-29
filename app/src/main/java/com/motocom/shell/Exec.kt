package com.motocom.shell

import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * What [command] printed, errors included, or null if it could not be run or did not end in
 * [timeoutS] seconds: a stuck system command must not hold whoever asked for ever.
 */
internal fun exec(timeoutS: Long, vararg command: String): String? = try {
    val process = ProcessBuilder(*command).redirectErrorStream(true).start()
    var out = ""
    // Read alongside, or a big dump fills the pipe and the command never ends.
    val reader = thread(isDaemon = true) { out = runCatching { process.inputStream.bufferedReader().readText() }.getOrDefault("") }
    if (process.waitFor(timeoutS, TimeUnit.SECONDS)) {
        reader.join(2000)
        out
    } else {
        process.destroy()
        null
    }
} catch (e: Exception) {
    null
}
