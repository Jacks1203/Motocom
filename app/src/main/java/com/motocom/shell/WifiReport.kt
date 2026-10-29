package com.motocom.shell

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything the system says about getting into the bike's wifi, gathered as "adb shell" can and
 * the app cannot: for a phone far away whose owner sends the record and nothing else. Only these
 * fixed commands, nothing the app asks for by name.
 *
 * Each part has a size of its own, so a chatty one does not push the others out, and the whole
 * fits in what Binder carries back to the app.
 */
internal object WifiReport {
    fun make(since: Long): String = buildString {
        part("direcciones del móvil (ip addr)", run("ip", "addr"), 8_000)
        part("wifi directo según Android (dumpsys wifip2p)", wifiP2p(run("dumpsys", "wifip2p"), since), 20_000)
        part("cómo se pidió la dirección en wifi directo (dumpsys network_stack)", ipClients(run("dumpsys", "network_stack"), since), 15_000)
        part("registro del sistema sobre wifi", systemLog(since), 20_000)
    }

    private fun StringBuilder.part(title: String, text: String, max: Int) {
        append("--- ").append(title).append(" ---\n")
        val body = text.trim().ifEmpty { "(nada)" }
        // The end is what happened last, which is what is being looked for.
        if (body.length > max) append("(recortado: solo lo último)\n").append(body.takeLast(max)) else append(body)
        append('\n')
    }

    /** Android's state and its last steps (records) since [since]. */
    private fun wifiP2p(dump: String, since: Long): String {
        val from = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(since))
        val records = mutableListOf<String>()
        val rest = mutableListOf<String>()
        for (line in dump.lineSequence()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("rec[") -> {
                    // "time=10-29 10:40:37.616": same year, so the text sorts as the time does.
                    val time = trimmed.substringAfter("time=", "").take(from.length)
                    if (time >= from) records += trimmed
                }
                // Every connection it remembers, any app's: not what went wrong now.
                trimmed.startsWith("startTime=") -> {}
                else -> rest += line
            }
        }
        return buildString {
            append(rest.joinToString("\n").trim()).append('\n')
            append("pasos desde $from:\n")
            records.takeLast(400).forEach { append(it).append('\n') }
        }
    }

    /**
     * What the address machinery did on each wifi direct interface since [since]: its steps and
     * the packets it sent and got (DHCP ones among them), as text and without their bytes.
     */
    private fun ipClients(dump: String, since: Long): String {
        val from = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date(since))
        val out = StringBuilder()
        var inside = false
        for (line in dump.lineSequence()) {
            val trimmed = line.trim()
            val name = Regex("^--- BEGIN (\\S+) ---$").find(trimmed)?.groupValues?.get(1)
                ?: Regex("^IpClient\\.(\\S+)$").find(line)?.groupValues?.get(1)
            when {
                name != null -> {
                    inside = "p2p" in name
                    if (inside) out.append("== ").append(name).append('\n')
                }
                trimmed.startsWith("--- END") -> inside = false
                // Unindented, it is the next part of the dump.
                line.isNotEmpty() && !line[0].isWhitespace() && trimmed.endsWith(":") && !line.startsWith("20") &&
                    trimmed !in INTERFACE_PARTS -> inside = false
                !inside -> {}
                trimmed.startsWith("[") -> {} // the packet's bytes
                Regex("^\\d{4}-\\d\\d-\\d\\dT").containsMatchIn(trimmed) -> if (trimmed.take(from.length) >= from) out.append(trimmed).append('\n')
                trimmed in INTERFACE_PARTS -> out.append(trimmed).append('\n')
            }
        }
        return out.toString()
    }

    /** The lines of the system log since [since] about wifi direct, wifi and addresses. */
    private fun systemLog(since: Long): String {
        val start = "%d.%03d".format(Locale.US, since / 1000, since % 1000)
        val wanted = Regex("p2p|dhcp|ipclient|supplicant|wificond|wifihal|wlan|wifi.?direct|WifiService|ClientModeImpl", RegexOption.IGNORE_CASE)
        val noise = Regex(" (AlarmManager|ActivityManager|ActivityThread|BroadcastQueue|JavaheapMonitor|dex2oat\\w*|ArtService|BaselineProfile): ")
        return run("logcat", "-d", "-v", "threadtime", "-t", start).lineSequence()
            .filter { wanted.containsMatchIn(it) && !noise.containsMatchIn(it) }
            .joinToString("\n")
    }

    private fun run(vararg command: String): String =
        exec(TIMEOUT_S, *command) ?: "(${command.joinToString(" ")}: sin respuesta en $TIMEOUT_S s)"

    private const val TIMEOUT_S = 20L
    /** The headings inside each interface's part of network_stack's dump. */
    private val INTERFACE_PARTS = setOf("APF log:", "State machine log:", "Connectivity packet log:")
}
