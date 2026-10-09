package com.motocom

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.motocom.bike.BikeQr
import com.motocom.bike.BikeState
import com.motocom.shell.ShellLink

class MainActivity : ComponentActivity() {
    private lateinit var moto: Moto

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (moto.missingPermissions().isEmpty()) moto.connect()
    }

    private val scanQr = registerForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@registerForActivityResult
        if (!moto.readQr(text)) {
            Toast.makeText(this, "Ese código no es el de la pantalla de una moto.", Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        // Whoever reads the code is standing at the bike: no need for another tap.
        if (moto.chosenApp.value != null && !moto.running.value && !moto.waitingForAdb.value) connect()
    }

    private fun readQr() = scanQr.launch(ScanOptions().apply {
        setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        setPrompt("Apunta al código QR de la pantalla de la moto")
        setBeepEnabled(false)
        setOrientationLocked(false)
    })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        moto = Moto.get(this)
        if (moto.chosenApp.value == null) {
            NAV_APPS.firstOrNull { installed(it) }?.let(moto::choose)
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Screen() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ShellLink.refresh()
    }

    private fun connect() {
        val missing = moto.missingPermissions()
        if (missing.isEmpty()) moto.connect() else askPermissions.launch(missing.toTypedArray())
    }

    /** The usual circled "i" that opens an explanation. */
    @Composable
    private fun InfoButton(onClick: () -> Unit) {
        Box(
            Modifier.size(22.dp).clip(CircleShape)
                .border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape)
                .clickable(onClickLabel = "Más información", onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text("i", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif)
        }
    }

    @Composable
    private fun Screen() {
        val running by moto.running
        val state by moto.state
        val waiting by moto.waitingForAdb
        var picking by remember { mutableStateOf(false) }
        var sizeInfo by remember { mutableStateOf(false) }

        Column(
            Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Pantalla de la moto", style = MaterialTheme.typography.headlineSmall)

            AdbCard()

            BikeCard(moto.bike.value, running)

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("3. App para la moto", style = MaterialTheme.typography.titleMedium)
                    val chosen = moto.chosenApp.value
                    Text(chosen?.let { label(it) } ?: "Ninguna elegida")
                    OutlinedButton(onClick = { picking = true }) { Text("Elegir app") }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Tamaño de letras y botones en la moto", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f, fill = false))
                        InfoButton { sizeInfo = true }
                    }
                    Text(
                        "Más grande se lee mejor desde la moto, pero cabe menos mapa. " +
                            if (running) "Para cambiarlo, desconecta primero." else "Se aplica al conectar.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SIZES.forEach { (dpi, name) ->
                            FilterChip(
                                selected = moto.dpi.intValue == dpi,
                                onClick = { moto.setDpi(dpi) },
                                enabled = !running,
                                label = { Text(name, fontSize = 12.sp, maxLines = 1) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("4. Conectar", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            waiting -> "Esperando a ADB. Completa el paso 1: la moto se conecta en cuanto esté listo."
                            !running && moto.bike.value == null -> "Primero lee el QR de la moto (paso 2)."
                            !running -> "Parado."
                            state == BikeState.SHOWING -> "En la moto. Ya puedes bloquear el móvil."
                            state == BikeState.GREETED -> "La moto ha contestado; preparando la imagen…"
                            else -> "Buscando la moto (wifi y ubicación encendidos)…"
                        }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (waiting) {
                            OutlinedButton(onClick = moto::cancelWaiting) { Text("Cancelar") }
                        } else if (!running) {
                            Button(onClick = ::connect, enabled = moto.chosenApp.value != null && moto.bike.value != null) { Text("Conectar") }
                        } else {
                            Button(onClick = moto::stop) { Text("Desconectar") }
                        }
                    }
                    if (running) {
                        Text("Para escribir un destino, trae la app al móvil y luego devuélvela a la moto.")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { moto.toPhone() }) { Text("Traer al móvil") }
                            OutlinedButton(onClick = { moto.toBike() }) { Text("Mandar a la moto") }
                        }
                    }
                    if (!ignoringBatteryOptimizations()) {
                        TextButton(onClick = ::askBatteryExemption) { Text("Quitar el ahorro de batería a esta app (para que siga con el móvil bloqueado)") }
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Registro", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { moto.share(this@MainActivity) }) { Text("Compartir") }
            }
            Log()
        }

        if (sizeInfo) {
            AlertDialog(
                onDismissRequest = { sizeInfo = false },
                confirmButton = { TextButton(onClick = { sizeInfo = false }) { Text("Entendido") } },
                title = { Text("Tamaño en la moto") },
                text = {
                    Text(
                        "La app elegida se dibuja en una pantalla virtual que se manda a la moto. " +
                            "Este ajuste cambia la densidad de esa pantalla (como el «tamaño de pantalla» de los ajustes de Android): " +
                            "con más tamaño, letras, iconos y botones salen más grandes y se leen mejor en marcha, " +
                            "pero cabe menos contenido (menos mapa). Con menos tamaño cabe más, pero se ve todo más pequeño.\n\n" +
                            "No afecta a la pantalla del móvil. Se aplica al conectar; para cambiarlo, desconecta primero."
                    )
                }
            )
        }

        if (picking) {
            AppPicker(onDismiss = { picking = false }) {
                moto.choose(it)
                picking = false
            }
        }
    }

    @Composable
    private fun AdbCard() {
        val ready = ShellLink.status.value == ShellLink.Status.READY
        val busy by ShellLink.busy
        var pairingPort by rememberSaveable { mutableStateOf("") }
        var connectionPort by rememberSaveable { mutableStateOf("") }
        // The one-time code is deliberately not persisted across recreation.
        var code by remember { mutableStateOf("") }
        var guide by rememberSaveable { mutableStateOf(false) }
        val paired by ShellLink.paired
        var manual by rememberSaveable { mutableStateOf(false) }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("1. ADB directo", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Box(Modifier.size(12.dp).background(if (ready) Color(0xFF2E7D32) else Color(0xFFC62828), CircleShape))
                    Spacer(Modifier.size(6.dp))
                    Text(if (ready) "Conectado" else "Desconectado", style = MaterialTheme.typography.labelLarge)
                }
                Text(ShellLink.message.value)
                if (!ready && paired) {
                    Text("Ya está emparejado: no hace falta poner puertos. Pulsa Conectar en el paso 4 o Iniciar ADB aquí.")
                    Button(onClick = ShellLink::quickStart, enabled = !busy) { Text(if (busy) "Preparando…" else "Iniciar ADB") }
                    TextButton(onClick = { manual = !manual }) { Text(if (manual) "Ocultar emparejamiento" else "Emparejar de nuevo o poner el puerto a mano") }
                }
                if (!ready && (!paired || manual)) {
                    if (android.os.Build.VERSION.SDK_INT >= 30) {
                        Text("Primera vez: empareja con el código de Depuración inalámbrica. Usa pantalla dividida para mantener visible el código. Si no sabes abrirla, pulsa «Cómo conectarla» más abajo.")
                        OutlinedTextField(pairingPort, { pairingPort = it.filter(Char::isDigit).take(5) },
                            label = { Text("Puerto de emparejamiento") }, enabled = !busy, singleLine = true,
                            modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) },
                            label = { Text("Código de 6 cifras") }, enabled = !busy, singleLine = true,
                            modifier = Modifier.fillMaxWidth())
                        OutlinedButton(onClick = { ShellLink.pair(pairingPort, code); code = "" },
                            enabled = !busy && pairingPort.isNotBlank() && code.length == 6) { Text("Emparejar") }
                    } else {
                        Text("Android 10: activa ADB por USB desde un ordenador con adb tcpip 5555; introduce 5555 aquí y acepta la autorización en el móvil.")
                    }
                    if (android.os.Build.VERSION.SDK_INT >= 30) Text("Después: usa el puerto de «Dirección IP y puerto» de la pantalla principal de Depuración inalámbrica. Es distinto al de emparejar.")
                    OutlinedTextField(connectionPort, { connectionPort = it.filter(Char::isDigit).take(5) },
                        label = { Text("Puerto de conexión ADB") }, enabled = !busy, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    Button(onClick = { ShellLink.start(connectionPort) }, enabled = !busy && connectionPort.isNotBlank()) {
                        Text(if (busy) "Preparando…" else "Iniciar ADB")
                    }
                    OutlinedButton(onClick = {
                        runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                    }) { Text("Opciones de desarrollador") }
                }
                TextButton(onClick = { guide = !guide }) { Text(if (guide) "Ocultar guía" else "Cómo conectarla") }
                if (guide) Text(
                    "1. Conecta el móvil a un wifi. Activa Opciones de desarrollador tocando 7 veces Número de compilación en Ajustes → Acerca del teléfono. Activa Depuración inalámbrica y acepta la red.\n\n" +
                    "Cómo abrir pantalla dividida (dos apps a la vez):\n" +
                    "• Abre Motocom y después Ajustes → Opciones de desarrollador → Depuración inalámbrica. Aún no abras el código.\n" +
                    "• Abre Recientes: con gestos, desliza desde el borde inferior hacia el centro y mantén un instante antes de soltar; con tres botones, pulsa el cuadrado o las tres rayas. Verás tarjetas de las apps abiertas.\n" +
                    "• En Xiaomi, Redmi o POCO, mantén pulsada la tarjeta de Motocom y toca Pantalla dividida o el icono de dos rectángulos, uno sobre otro. En otros móviles, toca el icono de Motocom encima de su tarjeta y elige Pantalla dividida.\n" +
                    "• Selecciona Ajustes como segunda app. Verás una app arriba y otra abajo; el orden no importa. Ahora abre Vincular dispositivo con código en la mitad de Ajustes.\n" +
                    "• Arrastra la barra central si necesitas más espacio y desplázate dentro de Motocom para ver los campos. Mantén abierto el diálogo del código mientras emparejas.\n" +
                    "• Para salir después de emparejar, arrastra la barra central hasta un borde.\n" +
                    "Si no aparece la opción, asegúrate de estar en Recientes, no en el escritorio. Los menús dependen del teléfono; algunos no permiten dividir la pantalla. Si ofrece ventana flotante, puedes poner Motocom así sobre Ajustes.\n\n" +
                    "2. En pantalla dividida con Motocom, abre Vincular dispositivo con código. Copia aquí su puerto y código; pulsa Emparejar sin cerrar ese diálogo.\n\n" +
                    "3. Vuelve a la pantalla principal de Depuración inalámbrica. Copia el puerto de Dirección IP y puerto y pulsa Iniciar ADB. No necesitas copiar la IP: se conecta a este mismo móvil.\n\n" +
                    "4. Cuando diga ADB listo, lee el QR de la pantalla de la moto, elige Waze, Maps u otra app, enciende wifi y ubicación, abre la conexión de la pantalla de la moto y pulsa Conectar. Cierra Carbit Ride y OffNavigator antes.\n\n" +
                    "5. Usa Traer al móvil para escribir un destino y Mandar a la moto para devolverla. Comprueba parado que la imagen sigue al bloquear el móvil.\n\n" +
                    "Después de la primera vez basta con pulsar Conectar: la app busca sola el puerto y, si Android se lo permite, enciende y apaga ella misma la depuración inalámbrica (en Xiaomi activa también «Depuración USB (ajustes de seguridad)»). Si no puede encenderla, actívala tú antes de pulsar. Solo vuelve a emparejar si Android ha olvidado la autorización; para que no la olvide, activa «Desactivar el tiempo de espera de autorización de ADB» en Opciones de desarrollador. Android 10 necesita un ordenador; Android 13 o superior permite solicitar una pantalla independiente del bloqueo.\n\n" +
                    "Fuera de casa, sin wifi:\n" +
                    "Si ya ponía ADB listo, no hace falta nada: aunque Android cierre Motocom, ADB sigue en marcha hasta que reinicies el móvil. Si se ha reiniciado o apagado, usa el wifi de la moto:\n" +
                    "• Enciende la moto y abre la conexión con el móvil en su pantalla.\n" +
                    "• En Ajustes → Wifi, conéctate a la red DIRECT-HJ-… de tu moto con la contraseña que indica el QR de su pantalla, como a cualquier wifi. Si avisa de que no hay internet, quédate conectado igualmente.\n" +
                    "• En Motocom pulsa Iniciar ADB y espera a ADB listo. Si Android pregunta si permites la depuración en esa red, acepta.\n" +
                    "• Importante: vuelve a Ajustes → Wifi, toca esa red y pulsa Olvidar. Si no la olvidas, la pantalla de la moto no se conecta.\n" +
                    "• Vuelve a Motocom y pulsa Conectar, como siempre."
                )
            }
        }
    }

    /** Which bike the phone will look for, as its QR code said. */
    @Composable
    private fun BikeCard(bike: BikeQr?, running: Boolean) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("2. Tu moto", style = MaterialTheme.typography.titleMedium)
                if (bike == null) {
                    Text("Enciende la moto y abre en su pantalla la conexión con el móvil, la que enseña un código QR. Pulsa «Leer QR» y apunta la cámara al código. Solo hace falta una vez.")
                } else {
                    BikeField("Moto", bike.name)
                    BikeField("Wifi", bike.ssid)
                    BikeField("Contraseña", bike.password.ifEmpty { "—" })
                    if (bike.model.isNotEmpty()) BikeField("Modelo", bike.model)
                    if (bike.serial.isNotEmpty()) BikeField("Nº de serie", bike.serial)
                }
                OutlinedButton(onClick = ::readQr, enabled = !running) {
                    Text(if (bike == null) "Leer QR" else "Leer otro QR")
                }
            }
        }
    }

    @Composable
    private fun BikeField(name: String, value: String) {
        Row(Modifier.fillMaxWidth()) {
            Text(name, Modifier.weight(0.4f), style = MaterialTheme.typography.bodyMedium)
            Text(value, Modifier.weight(0.6f), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
        }
    }

    @Composable
    private fun Log() {
        val lines = moto.lines
        val list = rememberLazyListState()
        LaunchedEffect(lines.size) { if (lines.isNotEmpty()) list.scrollToItem(lines.size - 1) }
        Card(Modifier.fillMaxWidth().height(320.dp)) {
            LazyColumn(Modifier.padding(8.dp), state = list) {
                items(lines) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    @Composable
    private fun AppPicker(onDismiss: () -> Unit, onPick: (String) -> Unit) {
        val apps = remember { launchableApps() }
        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
            title = { Text("¿Qué app va a la moto?") },
            text = {
                LazyColumn {
                    items(apps) { (pkg, name) ->
                        Text(
                            name,
                            Modifier.fillMaxWidth().clickable { onPick(pkg) }.padding(vertical = 12.dp),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        )
    }

    /** Navigation apps first, then everything else that has an icon, by name. */
    private fun launchableApps(): List<Pair<String, String>> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != packageName }
            .map { it to label(it) }
            .sortedWith(compareBy({ it.first !in NAV_APPS }, { it.second.lowercase() }))
    }

    private fun label(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun installed(pkg: String) = runCatching { packageManager.getPackageInfo(pkg, 0) }.isSuccess

    private fun ignoringBatteryOptimizations(): Boolean =
        (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    @SuppressLint("BatteryLife")
    private fun askBatteryExemption() {
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
    }

    private companion object {
        /** Density on the bike's 592 x 816 screen: higher, bigger letters and less map. */
        val SIZES = listOf(200 to "Pequeño", 240 to "Normal", 280 to "Grande", 320 to "Enorme")

        /** Offered first, in this order; the first one installed is chosen the first time. */
        val NAV_APPS = listOf(
            "com.waze",
            "com.google.android.apps.maps",
            "com.offnavigator",
            "net.osmand",
            "net.osmand.plus",
            "app.organicmaps"
        )
    }
}
