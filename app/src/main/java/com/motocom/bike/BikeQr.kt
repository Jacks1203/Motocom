package com.motocom.bike

import java.net.URLDecoder

/**
 * What the QR code on the bike's screen says: which wifi direct is the bike's and its password.
 *
 * ```
 *   http://www.carbit.com.cn/down6/…?modelid=37512&sn=XXXX&action=9
 *       &ssid=DIRECT-HJ-5G-XXXXXX&pwd=xxxxxxxx&auth=WPA-PSK&name=HJ-5G-XXXXXX
 * ```
 *
 * The address in front is only where a phone without the app would be sent to download it.
 */
data class BikeQr(
    /** The name the bike goes by on wifi direct. */
    val name: String,
    /** The same wifi seen as an ordinary network, "DIRECT-" and the name. */
    val ssid: String,
    val password: String,
    val model: String,
    val serial: String
) {
    companion object {
        /** Null when the code is not a bike's. */
        fun parse(text: String): BikeQr? {
            val fields = text.substringAfter('?', "").split('&').mapNotNull { pair ->
                val key = pair.substringBefore('=', "")
                if (key.isEmpty()) return@mapNotNull null
                // URLDecoder reads '+' as a space, as in forms; in a password it is just a '+'.
                val raw = pair.substringAfter('=').replace("+", "%2B")
                val value = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrNull()
                    ?: return@mapNotNull null
                key.lowercase() to value
            }.toMap()
            val ssid = fields["ssid"].orEmpty()
            val name = fields["name"].orEmpty().ifEmpty { ssid.removePrefix("DIRECT-") }
            // Any address with a name in it is not enough: the bike's always carries these.
            if (name.isEmpty() || ("modelid" !in fields && "action" !in fields)) return null
            return BikeQr(
                name = name,
                ssid = ssid.ifEmpty { name }.let { if (it.startsWith("DIRECT-")) it else "DIRECT-$it" },
                password = fields["pwd"].orEmpty(),
                model = fields["modelid"].orEmpty(),
                serial = fields["sn"].orEmpty()
            )
        }
    }
}
