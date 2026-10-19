package com.motocom

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.motocom.shell.ShellLink
import com.motocom.ui.Icons
import com.motocom.ui.Palette
import com.motocom.ui.Section
import com.motocom.ui.StatusPanel
import com.motocom.ui.Tone
import com.motocom.ui.TopBar

/**
 * Everything about getting ADB going, away from the bike: once it is paired the user hardly
 * ever comes here, since Connect starts ADB by itself.
 */
@Composable
fun AdbScreen(onBack: () -> Unit, openDeveloperOptions: () -> Unit) {
    val ready = ShellLink.status.value == ShellLink.Status.READY
    val busy by ShellLink.busy
    val paired by ShellLink.paired
    val message by ShellLink.message
    var manual by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TopBar("ADB", onBack = onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            when {
                ready -> StatusPanel(Tone.GOOD, "ADB activo", "Sigue en marcha hasta que reinicies el móvil.", Icons.Check)
                busy -> StatusPanel(Tone.NEUTRAL, "Preparando ADB…", message, Icons.Code, working = true)
                message.startsWith("Error") -> StatusPanel(Tone.BAD, "No se ha podido iniciar", message.removePrefix("Error ADB: "), Icons.Warning)
                paired -> StatusPanel(Tone.NEUTRAL, "ADB parado", "Ya está emparejado: basta con pulsar Iniciar ADB, o Conectar en la pantalla principal.", Icons.Code)
                else -> StatusPanel(Tone.WARN, "Sin configurar", "Motocom necesita ADB para crear la pantalla de la moto. Solo hay que emparejar la primera vez.", Icons.Code)
            }

            if (ready) {
                Button(onClick = onBack, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
                    Text("Volver a la moto")
                }
                if (manual) {
                    ManualSetup(busy, openDeveloperOptions)
                    TextButton(onClick = { manual = false }, Modifier.fillMaxWidth()) { Text("Ocultar") }
                } else {
                    TextButton(onClick = { manual = true }, Modifier.fillMaxWidth()) { Text("Emparejar de nuevo") }
                }
            } else if (paired && !manual) {
                Button(onClick = ShellLink::quickStart, Modifier.fillMaxWidth().height(52.dp), enabled = !busy, shape = RoundedCornerShape(16.dp)) {
                    Text(if (busy) "Preparando…" else "Iniciar ADB")
                }
                TextButton(onClick = { manual = true }, Modifier.fillMaxWidth()) {
                    Text("Emparejar de nuevo o poner el puerto a mano")
                }
            } else {
                ManualSetup(busy, openDeveloperOptions)
                if (paired) {
                    TextButton(onClick = { manual = false }, Modifier.fillMaxWidth()) { Text("Volver al inicio rápido") }
                }
            }

            Section("Ayuda") {
                HelpItem("Activar la depuración inalámbrica", ENABLE_HELP)
                HelpItem("Pantalla dividida para ver el código", SPLIT_HELP)
                HelpItem("Las siguientes veces", AFTER_HELP)
                HelpItem("Antes de salir con la moto", RIDE_HELP)
                HelpItem("Fuera de casa, sin wifi", NO_WIFI_HELP, last = true)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Pairing with the code, then starting on the connection port: the first time only. */
@Composable
private fun ManualSetup(busy: Boolean, openDeveloperOptions: () -> Unit) {
    var pairingPort by rememberSaveable { mutableStateOf("") }
    var connectionPort by rememberSaveable { mutableStateOf("") }
    // The one-time code is deliberately not persisted across recreation.
    var code by remember { mutableStateOf("") }
    val numbers = KeyboardOptions(keyboardType = KeyboardType.Number)

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedButton(onClick = openDeveloperOptions, Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Icon(Icons.OpenIn, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Abrir opciones de desarrollador")
        }
        if (Build.VERSION.SDK_INT >= 30) {
            Step(1, "Emparejar", "En Depuración inalámbrica, toca «Vincular dispositivo con código» y copia aquí su puerto y su código. Usa pantalla dividida para no cerrar ese diálogo.") {
                OutlinedTextField(
                    pairingPort, { pairingPort = it.filter(Char::isDigit).take(5) },
                    label = { Text("Puerto de emparejamiento") }, enabled = !busy, singleLine = true,
                    keyboardOptions = numbers, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    code, { code = it.filter(Char::isDigit).take(6) },
                    label = { Text("Código de 6 cifras") }, enabled = !busy, singleLine = true,
                    keyboardOptions = numbers, modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(
                    onClick = { ShellLink.pair(pairingPort, code); code = "" },
                    enabled = !busy && pairingPort.isNotBlank() && code.length == 6,
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)
                ) { Text("Emparejar") }
            }
            Step(2, "Iniciar", "Vuelve a la pantalla principal de Depuración inalámbrica y copia el puerto de «Dirección IP y puerto». Es distinto al de emparejar; la IP no hace falta.") {
                ConnectionPort(connectionPort, { connectionPort = it }, busy, numbers)
            }
        } else {
            Step(1, "Iniciar", "En Android 10 hace falta un ordenador: conecta el móvil por USB, ejecuta «adb tcpip 5555», escribe 5555 aquí y acepta la autorización en el móvil.") {
                ConnectionPort(connectionPort, { connectionPort = it }, busy, numbers)
            }
        }
    }
}

@Composable
private fun ConnectionPort(port: String, onPort: (String) -> Unit, busy: Boolean, numbers: KeyboardOptions) {
    OutlinedTextField(
        port, { onPort(it.filter(Char::isDigit).take(5)) },
        label = { Text("Puerto de conexión") }, enabled = !busy, singleLine = true,
        keyboardOptions = numbers, modifier = Modifier.fillMaxWidth()
    )
    Button(
        onClick = { ShellLink.start(port) }, enabled = !busy && port.isNotBlank(),
        modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)
    ) { Text(if (busy) "Preparando…" else "Iniciar ADB") }
}

@Composable
private fun Step(number: Int, title: String, text: String, content: @Composable () -> Unit) {
    Section(null) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(28.dp).clip(CircleShape).background(Palette.Primary.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("$number", color = Palette.Primary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(text, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
            content()
        }
    }
}

@Composable
private fun HelpItem(title: String, text: String, last: Boolean = false) {
    var open by rememberSaveable { mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 180f else 0f, label = "flecha")
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(onClickLabel = if (open) "Ocultar" else "Mostrar") { open = !open }
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Icon(Icons.ExpandMore, null, tint = Palette.Muted, modifier = Modifier.rotate(turn))
        }
        AnimatedVisibility(open) {
            Text(
                text,
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Muted
            )
        }
        if (!last) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = Palette.Outline)
    }
}

private const val ENABLE_HELP =
    "1. Conecta el móvil a un wifi.\n" +
        "2. En Ajustes → Acerca del teléfono, toca 7 veces «Número de compilación» para activar las Opciones de desarrollador.\n" +
        "3. En Opciones de desarrollador, activa «Depuración inalámbrica» y acepta la red.\n" +
        "4. Para que Android no olvide la autorización, activa también «Desactivar el tiempo de espera de autorización de ADB»."

private const val SPLIT_HELP =
    "• Abre Motocom y después Ajustes → Opciones de desarrollador → Depuración inalámbrica. Aún no abras el código.\n" +
        "• Abre Recientes: con gestos, desliza desde abajo hacia el centro y mantén un instante; con botones, pulsa el cuadrado.\n" +
        "• En Xiaomi, Redmi o POCO, mantén pulsada la tarjeta de Motocom y toca «Pantalla dividida». En otros móviles, toca el icono de Motocom encima de su tarjeta.\n" +
        "• Elige Ajustes como segunda app y abre «Vincular dispositivo con código» en esa mitad.\n" +
        "• Arrastra la barra central si necesitas más espacio. Para salir, llévala hasta un borde.\n" +
        "Si tu móvil no permite dividir la pantalla, prueba con ventana flotante."

private const val AFTER_HELP =
    "Basta con pulsar Conectar: Motocom busca sola el puerto y, si Android se lo permite, enciende y apaga ella misma la depuración inalámbrica (en Xiaomi activa también «Depuración USB (ajustes de seguridad)»). " +
        "Si no puede encenderla, actívala tú antes de pulsar. Solo hay que volver a emparejar si Android ha olvidado la autorización.\n\n" +
        "Android 10 necesita un ordenador; con Android 13 o superior la imagen sigue con el móvil bloqueado."

private const val RIDE_HELP =
    "• Cierra del todo Carbit Ride y OffNavigator antes de pulsar Conectar: usan los mismos puertos y solo una app puede tenerlos.\n" +
        "• Ten el wifi y la ubicación encendidos.\n" +
        "• Con la moto parada, bloquea el móvil y comprueba que la imagen sigue en la pantalla de la moto.\n" +
        "• Para escribir un destino, pulsa «Al móvil» y después «A la moto»."

private const val NO_WIFI_HELP =
    "Si ADB ya estaba activo, no hace falta nada: sigue en marcha hasta que reinicies el móvil, aunque Android cierre Motocom. Si se ha reiniciado, usa el wifi de la moto:\n" +
        "• Enciende la moto y abre en su pantalla la conexión con el móvil.\n" +
        "• Lee el QR de la pantalla de la moto con Motocom (no necesita ADB). Después toca la fila Moto en la pantalla principal: en «Wifi» verás cómo se llama la red de la moto y en «Contraseña», su contraseña.\n" +
        "• En Ajustes → Wifi, conéctate a esa red con esa contraseña. Si avisa de que no hay internet, quédate conectado.\n" +
        "• En Motocom pulsa Iniciar ADB y espera a que diga ADB activo. Si Android pregunta si permites la depuración en esa red, acepta.\n" +
        "• Importante: vuelve a Ajustes → Wifi y olvida esa red. Si no, la pantalla de la moto no se conecta.\n" +
        "• Vuelve a Motocom y pulsa Conectar."
