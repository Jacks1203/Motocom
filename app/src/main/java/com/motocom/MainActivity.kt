package com.motocom

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.motocom.bike.BikeQr
import com.motocom.bike.BikeState
import com.motocom.shell.ShellLink
import com.motocom.ui.BikeScreenPreview
import com.motocom.ui.Callout
import com.motocom.ui.IconBadge
import com.motocom.ui.Icons
import com.motocom.ui.ListRow
import com.motocom.ui.MotocomTheme
import com.motocom.ui.Palette
import com.motocom.ui.RowDivider
import com.motocom.ui.Section
import com.motocom.ui.Segmented
import com.motocom.ui.StatusPanel
import com.motocom.ui.Tone
import com.motocom.ui.TopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var moto: Moto

    /** Whether Android may put the app to sleep with the phone locked; checked on every return. */
    private val batteryFree = mutableStateOf(true)

    private enum class Page { HOME, ADB, LOG }

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
        val adbUsable = ShellLink.status.value == ShellLink.Status.READY || ShellLink.paired.value
        if (adbUsable && moto.chosenApp.value != null && !moto.running.value && !moto.waitingForAdb.value) connect()
    }

    private fun readQr() = scanQr.launch(ScanOptions().apply {
        setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        setPrompt("Apunta al código QR de la pantalla de la moto")
        setBeepEnabled(false)
        setOrientationLocked(false)
    })

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        moto = Moto.get(this)
        if (moto.chosenApp.value == null) {
            NAV_APPS.firstOrNull { installed(it) }?.let(moto::choose)
        }
        setContent {
            MotocomTheme {
                Surface(Modifier.fillMaxSize(), color = Palette.Background) {
                    var page by rememberSaveable { mutableStateOf(Page.HOME) }
                    BackHandler(enabled = page != Page.HOME) { page = Page.HOME }
                    AnimatedContent(
                        page,
                        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "pantalla"
                    ) { shown ->
                        when (shown) {
                            Page.HOME -> Home(openAdb = { page = Page.ADB }, openLog = { page = Page.LOG })
                            Page.ADB -> AdbScreen(onBack = { page = Page.HOME }, openDeveloperOptions = ::openDeveloperOptions)
                            Page.LOG -> LogScreen(onBack = { page = Page.HOME })
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ShellLink.refresh()
        batteryFree.value = ignoringBatteryOptimizations()
    }

    private fun connect() {
        val missing = moto.missingPermissions()
        if (missing.isEmpty()) moto.connect() else askPermissions.launch(missing.toTypedArray())
    }

    /** The one screen for riding: which bike, which app, how big, and the button to go. */
    @Composable
    private fun Home(openAdb: () -> Unit, openLog: () -> Unit) {
        val running by moto.running
        val state by moto.state
        val waiting by moto.waitingForAdb
        val bike by moto.bike
        val chosen by moto.chosenApp
        val dpi by moto.dpi
        val adbReady = ShellLink.status.value == ShellLink.Status.READY
        val paired by ShellLink.paired
        val adbBusy by ShellLink.busy
        val adbFailed = !adbBusy && ShellLink.message.value.startsWith(ADB_ERROR)
        val adbMissing = !adbReady && !paired
        val sizeName = SIZES.firstOrNull { it.first == dpi }?.second ?: "$dpi ppp"
        val appName = chosen?.let(::label)
        var picking by rememberSaveable { mutableStateOf(false) }
        var showBike by rememberSaveable { mutableStateOf(false) }

        Column(Modifier.fillMaxSize()) {
            TopBar("Motocom", logo = {
                // The launcher icon, shaped as the phone's launcher shapes it.
                appIcon(packageName)?.let { Image(it, null, Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))) }
            }) {
                AdbChip(adbReady, paired, adbBusy, waiting && adbFailed, openAdb)
                IconButton(onClick = openLog) { Icon(Icons.Log, "Registro") }
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                when {
                    // A failed start leaves Connect waiting: say so here, not only on the ADB screen.
                    waiting && adbFailed -> StatusPanel(
                        Tone.BAD, "No se ha podido iniciar ADB",
                        ShellLink.message.value.removePrefix(ADB_ERROR).trim(), Icons.Warning
                    )
                    waiting -> StatusPanel(
                        Tone.NEUTRAL, "Iniciando ADB…",
                        if (adbBusy) "${ShellLink.message.value} La moto se conectará en cuanto esté listo."
                        else "La moto se conectará en cuanto ADB esté listo.",
                        Icons.Code, working = true
                    )
                    running && state == BikeState.SHOWING ->
                        StatusPanel(Tone.GOOD, "En la moto", "${appName ?: "La app"} se ve en la moto. Ya puedes bloquear el móvil.", Icons.Check)
                    running && state == BikeState.GREETED ->
                        StatusPanel(Tone.NEUTRAL, "Preparando la imagen…", "La moto ha respondido.", Icons.Navigation, working = true)
                    running -> StatusPanel(
                        Tone.NEUTRAL, "Buscando la moto…",
                        "Abre la conexión con el móvil en la pantalla de la moto. Wifi y ubicación, encendidos.",
                        Icons.Navigation, working = true
                    )
                    adbMissing -> StatusPanel(Tone.WARN, "Falta configurar ADB", "Solo hace falta la primera vez.", Icons.Code)
                    bike == null -> StatusPanel(Tone.NEUTRAL, "Vincula tu moto", "Lee el código QR que aparece en su pantalla.", Icons.Qr)
                    chosen == null -> StatusPanel(Tone.NEUTRAL, "Elige una app", "La que quieres ver en la moto.", Icons.Navigation)
                    else -> StatusPanel(Tone.GOOD, "Listo para conectar", "Enciende la moto y abre en su pantalla la conexión con el móvil.", Icons.Check)
                }

                Section(if (running) "En la moto" else "Conexión") {
                    ListRow(
                        leading = { IconBadge(Icons.Qr) },
                        title = "Moto",
                        value = bike?.name ?: "Sin vincular · leer QR",
                        onClick = { if (bike == null) readQr() else showBike = true },
                        clickLabel = if (bike == null) "Leer QR" else "Ver datos de la moto"
                    )
                    RowDivider()
                    ListRow(
                        leading = { AppIcon(chosen) },
                        title = "App",
                        value = appName ?: "Elegir app",
                        onClick = if (running) null else ({ picking = true }),
                        clickLabel = "Cambiar app"
                    )
                    if (running) {
                        RowDivider()
                        ListRow(
                            leading = { IconBadge(Icons.Lock, Palette.Muted) },
                            title = "Tamaño",
                            value = "$sizeName · desconecta para cambiarlo",
                            onClick = null
                        )
                    }
                }

                if (running) MoveApp() else SizeChooser(dpi)

                if (!batteryFree.value) {
                    Callout(
                        "El ahorro de batería puede cortar la imagen con el móvil bloqueado.",
                        Tone.WARN
                    ) {
                        TextButton(onClick = ::askBatteryExemption) { Text("Permitir") }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            BottomAction(running, waiting, adbFailed, adbMissing, bike != null, chosen != null, openAdb) { picking = true }
        }

        if (picking) {
            AppPicker(chosen, onDismiss = { picking = false }) {
                moto.choose(it)
                picking = false
            }
        }
        if (showBike) bike?.let { BikeDialog(it, running) { showBike = false } }
    }

    /** ADB, out of the way: green when on, and a way into its own screen. */
    @Composable
    private fun AdbChip(ready: Boolean, paired: Boolean, busy: Boolean, failed: Boolean, onClick: () -> Unit) {
        val (color, text) = when {
            ready -> Palette.Good to "ADB activo"
            busy -> Palette.Primary to "Iniciando ADB…"
            failed -> Palette.Bad to "Error de ADB"
            paired -> Palette.Muted to "ADB parado"
            else -> Palette.Warn to "Configurar ADB"
        }
        Row(
            Modifier.heightIn(min = 48.dp).clip(CircleShape).background(Palette.SurfaceHigh)
                .clickable(onClickLabel = "Abrir ajustes de ADB", role = Role.Button, onClick = onClick)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }

    /** How big the app looks on the bike, which can only be set before connecting. */
    @Composable
    private fun SizeChooser(dpi: Int) {
        Section("Tamaño en la moto") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BikeScreenPreview(dpi, Modifier.width(96.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val (_, name, note) = SIZES.firstOrNull { it.first == dpi } ?: Triple(dpi, "$dpi ppp", "")
                        Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(note, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
                        Text("No cambia la pantalla del móvil.", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                    }
                }
                Segmented(
                    options = SIZES.map { it.second },
                    selected = SIZES.indexOfFirst { it.first == dpi },
                    enabled = true
                ) { moto.setDpi(SIZES[it].first) }
            }
        }
    }

    /** While on the bike: bring the app over to type a destination, then send it back. */
    @Composable
    private fun MoveApp() {
        Section("Escribir un destino") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Trae la app al móvil para escribir y devuélvela a la moto al terminar.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.Muted
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = { moto.toPhone() }, Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(16.dp)) {
                        Icon(Icons.Phone, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Al móvil")
                    }
                    FilledTonalButton(onClick = { moto.toBike() }, Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(16.dp)) {
                        Icon(Icons.Navigation, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("A la moto")
                    }
                }
            }
        }
    }

    /** Always the next thing to do, so the screen reads from top to this button. */
    @Composable
    private fun BottomAction(
        running: Boolean, waiting: Boolean, adbFailed: Boolean, adbMissing: Boolean, hasBike: Boolean, hasApp: Boolean,
        openAdb: () -> Unit, pickApp: () -> Unit,
    ) {
        Column(
            Modifier.fillMaxWidth().background(Palette.Background).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val big = Modifier.fillMaxWidth().height(56.dp)
            val shape = RoundedCornerShape(18.dp)
            when {
                waiting && adbFailed -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = moto::cancelWaiting, Modifier.weight(1f).height(56.dp), shape = shape) { Text("Cancelar", fontSize = 16.sp) }
                        Button(onClick = ShellLink::quickStart, Modifier.weight(1f).height(56.dp), shape = shape) { Text("Reintentar", fontSize = 16.sp) }
                    }
                    TextButton(onClick = openAdb, Modifier.fillMaxWidth()) { Text("Ajustes de ADB") }
                }
                waiting -> {
                    Callout(SIZE_WARNING)
                    OutlinedButton(onClick = moto::cancelWaiting, big, shape = shape) { Text("Cancelar", fontSize = 16.sp) }
                }
                running -> OutlinedButton(
                    onClick = moto::stop, big, shape = shape,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.Bad)
                ) { Text("Desconectar", fontSize = 16.sp) }
                adbMissing -> Button(onClick = openAdb, big, shape = shape) { Text("Configurar ADB", fontSize = 16.sp) }
                !hasBike -> Button(onClick = ::readQr, big, shape = shape) {
                    Icon(Icons.Qr, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Leer QR de la moto", fontSize = 16.sp)
                }
                !hasApp -> Button(onClick = pickApp, big, shape = shape) { Text("Elegir app", fontSize = 16.sp) }
                else -> {
                    Callout(SIZE_WARNING)
                    Button(onClick = ::connect, big, shape = shape) { Text("Conectar", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }

    @Composable
    private fun AppIcon(pkg: String?) {
        val icon = pkg?.let { appIcon(it) }
        if (icon != null) {
            Image(icon, null, Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)))
        } else {
            IconBadge(Icons.Navigation)
        }
    }

    /** Loaded off the main thread: some launchers' icons are slow to draw. */
    @Composable
    private fun appIcon(pkg: String): ImageBitmap? = produceState<ImageBitmap?>(null, pkg) {
        value = withContext(Dispatchers.IO) {
            runCatching { packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
        }
    }.value

    @Composable
    private fun BikeDialog(bike: BikeQr, running: Boolean, onDismiss: () -> Unit) {
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = Palette.SurfaceHigh,
            title = { Text("Tu moto") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    BikeField("Nombre", bike.name)
                    BikeField("Wifi", bike.ssid)
                    BikeField("Contraseña", bike.password.ifEmpty { "—" })
                    if (bike.model.isNotEmpty()) BikeField("Modelo", bike.model)
                    if (bike.serial.isNotEmpty()) BikeField("Nº de serie", bike.serial)
                }
            },
            confirmButton = {
                TextButton(onClick = { onDismiss(); readQr() }, enabled = !running) { Text("Leer otro QR") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } }
        )
    }

    @Composable
    private fun BikeField(name: String, value: String) {
        Column {
            Text(name, style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
            Text(value, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace)
        }
    }

    /** Every attempt, as it went: for finding out later, away from the bike, what failed. */
    @Composable
    private fun LogScreen(onBack: () -> Unit) {
        val lines = moto.lines
        val list = rememberLazyListState()
        LaunchedEffect(lines.size) { if (lines.isNotEmpty()) list.scrollToItem(lines.size - 1) }
        Column(Modifier.fillMaxSize()) {
            TopBar("Registro", onBack = onBack) {
                IconButton(onClick = { moto.share(this@MainActivity) }) { Icon(Icons.Share, "Compartir registro") }
            }
            Surface(
                Modifier.weight(1f).fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                shape = RoundedCornerShape(20.dp),
                color = Palette.Surface
            ) {
                if (lines.isEmpty()) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("Todavía no hay nada.", color = Palette.Muted)
                    }
                } else {
                    LazyColumn(Modifier.padding(12.dp), state = list) {
                        items(lines) {
                            Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp, color = Palette.Text.copy(alpha = 0.85f))
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AppPicker(chosen: String?, onDismiss: () -> Unit, onPick: (String) -> Unit) {
        val apps = remember { launchableApps() }
        var query by remember { mutableStateOf("") }
        val shown = apps.filter { query.isBlank() || it.second.contains(query.trim(), ignoreCase = true) }
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Palette.Surface
        ) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text("¿Qué app va a la moto?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    query, { query = it },
                    Modifier.fillMaxWidth(),
                    placeholder = { Text("Buscar") },
                    leadingIcon = { Icon(Icons.Search, null) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp)
                )
                Spacer(Modifier.height(8.dp))
            }
            LazyColumn(Modifier.fillMaxWidth().imePadding()) {
                items(shown, key = { it.first }) { (pkg, name) ->
                    val isNav = pkg in NAV_APPS
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable(onClickLabel = "Elegir", role = Role.Button) { onPick(pkg) }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .semantics { if (pkg == chosen) stateDescription = "elegida" },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppIcon(pkg)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.bodyLarge)
                            if (isNav) Text("Navegación", style = MaterialTheme.typography.bodySmall, color = Palette.Primary)
                        }
                        if (pkg == chosen) Icon(Icons.Check, null, tint = Palette.Primary)
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
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

    private fun openDeveloperOptions() {
        runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
    }

    private companion object {
        const val ADB_ERROR = "Error ADB:"
        const val SIZE_WARNING = "Elige el tamaño antes de conectar: después no se puede cambiar."

        /** Density on the bike's 592 x 816 screen: higher, bigger letters and less map. */
        val SIZES = listOf(
            Triple(200, "Pequeño", "Cabe más mapa; letras pequeñas."),
            Triple(240, "Normal", "Equilibrado. Recomendado."),
            Triple(280, "Grande", "Se lee mejor en marcha."),
            Triple(320, "Enorme", "Lo más legible, con poco mapa."),
        )

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
