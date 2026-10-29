package com.motocom

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
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

    /** USB debugging is off, so ADB stops when wireless debugging does; checked on every return. */
    private val usbOff = mutableStateOf(false)

    private enum class Page { HOME, ADB, LOG }

    /** A permission the bike needs was refused: said on the home screen, with a way to Settings. */
    private val refused = mutableStateOf(false)

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (moto.missingPermissions().isEmpty()) {
            refused.value = false
            moto.connect()
        } else {
            refused.value = true
        }
    }

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            scan()
        } else {
            Toast.makeText(this, R.string.no_camera, Toast.LENGTH_LONG).show()
            if (!shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) openAppSettings()
        }
    }

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pairByNotice() else noNotifications()
    }

    private val scanQr = registerForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@registerForActivityResult
        if (!moto.readQr(text)) {
            Toast.makeText(this, R.string.not_bike_qr, Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        // Not connecting by itself: the app and its size are to be chosen first.
        Toast.makeText(this, R.string.bike_linked, Toast.LENGTH_SHORT).show()
    }

    private fun readQr() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scan()
        else askCamera.launch(Manifest.permission.CAMERA)
    }

    private fun scan() = scanQr.launch(ScanOptions().apply {
        setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        setPrompt(getString(R.string.scan_prompt))
        setBeepEnabled(false)
        setOrientationLocked(false)
    })

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(Language.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        // The theme's own grey would show behind the system bars when the language change recreates the screen.
        window.setBackgroundDrawable(ColorDrawable(Palette.Background.toArgb()))
        moto = Moto.get(this)
        BikeService.createChannel(this)
        PairingService.createChannel(this)
        pickDefaultApp()
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
                            Page.ADB -> AdbScreen(onBack = { page = Page.HOME }, openDeveloperOptions = ::openDeveloperOptions, pairByNotice = ::pairByNotice)
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
        usbOff.value = !ShellLink.usbDebuggingOn()
        batteryFree.value = ignoringBatteryOptimizations()
        if (moto.missingPermissions().isEmpty()) refused.value = false
        if (!moto.running.value && moto.forgetMissingApp()) {
            Toast.makeText(this, R.string.app_gone, Toast.LENGTH_LONG).show()
            pickDefaultApp()
        }
    }

    /** The first navigation app installed, if none is chosen yet. */
    private fun pickDefaultApp() {
        if (moto.chosenApp.value == null) NAV_APPS.firstOrNull { launchable(it) }?.let(moto::choose)
    }

    private fun connect() {
        val ui = getSharedPreferences("motocom_ui", MODE_PRIVATE)
        val missing = moto.permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        // Notifications are asked once; nearby devices (or location) every time they are missing.
        if (moto.missingPermissions().isNotEmpty() || (missing.isNotEmpty() && !ui.getBoolean(KEY_ASKED, false))) {
            ui.edit().putBoolean(KEY_ASKED, true).apply()
            // Android 12 ignores asking for the fine location alone, coarse already granted.
            askPermissions.launch(if (moto.missingPermissions().isNotEmpty()) moto.permissions else missing.toTypedArray())
        } else {
            moto.connect()
        }
    }

    private fun openAppSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    /** The one screen for riding: which bike, which app, how big, and the button to go. */
    @Composable
    private fun Home(openAdb: () -> Unit, openLog: () -> Unit) {
        val running by moto.running
        val state by moto.state
        val waiting by moto.waitingForAdb
        val place by moto.appPlace
        val bike by moto.bike
        val chosen by moto.chosenApp
        val dpi by moto.dpi
        val adbReady = ShellLink.status.value == ShellLink.Status.READY
        val paired by ShellLink.paired
        val adbBusy by ShellLink.busy
        val adbFailed = !adbBusy && ShellLink.failed.value
        val adbMessage = ShellLink.message.value?.read(this).orEmpty()
        val adbMissing = !adbReady && !paired
        val sizeName = SIZES.firstOrNull { it.first == dpi }?.let { stringResource(it.second) } ?: stringResource(R.string.size_dpi, dpi)
        val appName = chosen?.let(::label)
        var picking by rememberSaveable { mutableStateOf(false) }
        var showBike by rememberSaveable { mutableStateOf(false) }

        Column(Modifier.fillMaxSize()) {
            TopBar("Motocom", logo = {
                // The launcher icon, shaped as the phone's launcher shapes it.
                appIcon(packageName)?.let { Image(it, null, Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))) }
            }) {
                AdbChip(adbReady, paired, adbBusy, waiting && adbFailed, openAdb)
                IconButton(onClick = openLog) { Icon(Icons.Log, stringResource(R.string.log)) }
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                when {
                    // A failed start leaves Connect waiting: say so here, not only on the ADB screen.
                    waiting && adbFailed -> StatusPanel(
                        Tone.BAD, stringResource(R.string.status_adb_failed), adbMessage, Icons.Warning
                    )
                    waiting -> StatusPanel(
                        Tone.NEUTRAL, stringResource(R.string.status_adb_starting),
                        if (adbBusy) stringResource(R.string.status_adb_starting_busy, adbMessage)
                        else stringResource(R.string.status_adb_starting_idle),
                        Icons.Code, working = true
                    )
                    running && state == BikeState.SHOWING && place == AppPlace.PHONE -> StatusPanel(
                        Tone.WARN, stringResource(R.string.status_on_phone),
                        stringResource(R.string.status_on_phone_detail), Icons.Phone
                    )
                    running && state == BikeState.SHOWING && place == AppPlace.NOWHERE -> StatusPanel(
                        Tone.WARN, stringResource(R.string.status_nowhere),
                        stringResource(R.string.status_nowhere_detail, appName ?: stringResource(R.string.the_app_lower)),
                        Icons.Warning
                    )
                    running && state == BikeState.SHOWING -> StatusPanel(
                        Tone.GOOD, stringResource(R.string.status_on_bike),
                        stringResource(
                            if (Build.VERSION.SDK_INT >= 33) R.string.status_on_bike_lock else R.string.status_on_bike_no_lock,
                            appName ?: stringResource(R.string.the_app)
                        ),
                        Icons.Check
                    )
                    running && state == BikeState.GREETED ->
                        StatusPanel(
                            Tone.NEUTRAL, stringResource(R.string.status_preparing),
                            stringResource(R.string.status_preparing_detail), Icons.Navigation, working = true
                        )
                    running -> StatusPanel(
                        Tone.NEUTRAL, stringResource(R.string.status_searching),
                        stringResource(R.string.status_searching_detail),
                        Icons.Navigation, working = true
                    )
                    refused.value -> StatusPanel(
                        Tone.BAD, stringResource(R.string.status_permission),
                        stringResource(if (Build.VERSION.SDK_INT >= 33) R.string.status_permission_nearby else R.string.status_permission_location),
                        Icons.Warning
                    )
                    adbMissing -> StatusPanel(
                        Tone.WARN, stringResource(R.string.status_adb_missing), stringResource(R.string.status_adb_missing_detail), Icons.Code
                    )
                    bike == null -> StatusPanel(
                        Tone.NEUTRAL, stringResource(R.string.status_no_bike), stringResource(R.string.status_no_bike_detail), Icons.Qr
                    )
                    chosen == null -> StatusPanel(
                        Tone.NEUTRAL, stringResource(R.string.status_no_app), stringResource(R.string.status_no_app_detail), Icons.Navigation
                    )
                    usbOff.value -> StatusPanel(
                        Tone.WARN, stringResource(R.string.usb_debugging_off), stringResource(R.string.usb_debugging_off_detail), Icons.Code
                    )
                    else -> StatusPanel(
                        Tone.GOOD, stringResource(R.string.status_ready), stringResource(R.string.status_ready_detail), Icons.Check
                    )
                }

                Section(stringResource(if (running) R.string.status_on_bike else R.string.section_connection)) {
                    ListRow(
                        leading = { IconBadge(Icons.Qr) },
                        title = stringResource(R.string.row_bike),
                        value = bike?.name ?: stringResource(R.string.row_bike_none),
                        onClick = { if (bike == null) readQr() else showBike = true },
                        clickLabel = stringResource(if (bike == null) R.string.read_qr else R.string.show_bike)
                    )
                    RowDivider()
                    ListRow(
                        leading = { AppIcon(chosen) },
                        title = stringResource(R.string.row_app),
                        value = appName ?: stringResource(R.string.choose_app),
                        onClick = if (running) null else ({ picking = true }),
                        clickLabel = stringResource(R.string.change_app)
                    )
                    if (running) {
                        RowDivider()
                        ListRow(
                            leading = { IconBadge(Icons.Lock, Palette.Muted) },
                            title = stringResource(R.string.row_size),
                            value = stringResource(R.string.row_size_locked, sizeName),
                            onClick = null
                        )
                    }
                }

                if (refused.value && !running) {
                    Callout(stringResource(R.string.permission_callout), Tone.BAD) {
                        TextButton(onClick = ::openAppSettings) { Text(stringResource(R.string.settings)) }
                    }
                }

                if (running) {
                    if (state == BikeState.SHOWING) MoveApp(place)
                } else {
                    SizeChooser(dpi)
                }

                if (!batteryFree.value) {
                    Callout(stringResource(R.string.battery_callout), Tone.WARN) {
                        TextButton(onClick = ::askBatteryExemption) { Text(stringResource(R.string.allow)) }
                    }
                }

                // Hidden while anything is under way, whose notices and messages are in the language it began with.
                if (!running && !waiting && !adbBusy && !PairingService.searching.value) LanguageChooser()
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
            ready -> Palette.Good to R.string.adb_chip_on
            busy -> Palette.Primary to R.string.status_adb_starting
            failed -> Palette.Bad to R.string.adb_chip_error
            paired -> Palette.Muted to R.string.adb_chip_stopped
            else -> Palette.Warn to R.string.adb_setup
        }
        Row(
            Modifier.heightIn(min = 48.dp).clip(CircleShape).background(Palette.SurfaceHigh)
                .clickable(onClickLabel = stringResource(R.string.open_adb), role = Role.Button, onClick = onClick)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(text), style = MaterialTheme.typography.labelLarge)
        }
    }

    /** How big the app looks on the bike, which can only be set before connecting. */
    @Composable
    private fun SizeChooser(dpi: Int) {
        Section(stringResource(R.string.section_size)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BikeScreenPreview(dpi, Modifier.width(96.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val size = SIZES.firstOrNull { it.first == dpi }
                        Text(
                            size?.let { stringResource(it.second) } ?: stringResource(R.string.size_dpi, dpi),
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold
                        )
                        if (size != null) Text(stringResource(size.third), style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
                        Text(stringResource(R.string.size_phone_unchanged), style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                    }
                }
                Segmented(
                    options = SIZES.map { stringResource(it.second) },
                    selected = SIZES.indexOfFirst { it.first == dpi },
                    enabled = true
                ) { moto.setDpi(SIZES[it].first) }
            }
        }
    }

    /** Each language named in itself, so it can be found whichever one is on. */
    @Composable
    private fun LanguageChooser() {
        val current = Language.current(this)
        Section(stringResource(R.string.section_language)) {
            Column(Modifier.padding(16.dp)) {
                Segmented(
                    options = listOf("Español", "English"),
                    selected = Language.ALL.indexOf(current),
                    enabled = true
                ) {
                    val chosen = Language.ALL[it]
                    if (chosen != current) {
                        Language.set(this@MainActivity, chosen)
                        recreate()
                    }
                }
            }
        }
    }

    /** While on the bike: bring the app over to type a destination, then send it back. */
    @Composable
    private fun MoveApp(place: AppPlace) {
        Section(stringResource(R.string.section_destination)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.destination_text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.Muted
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = { moto.toPhone() }, Modifier.weight(1f).height(52.dp), enabled = place == AppPlace.BIKE, shape = RoundedCornerShape(16.dp)) {
                        Icon(Icons.Phone, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.to_phone))
                    }
                    FilledTonalButton(onClick = { moto.toBike() }, Modifier.weight(1f).height(52.dp), enabled = place != AppPlace.BIKE, shape = RoundedCornerShape(16.dp)) {
                        Icon(Icons.Navigation, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.to_bike))
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
                        OutlinedButton(onClick = moto::cancelWaiting, Modifier.weight(1f).height(56.dp), shape = shape) { Text(stringResource(R.string.cancel), fontSize = 16.sp) }
                        Button(onClick = ShellLink::quickStart, Modifier.weight(1f).height(56.dp), shape = shape) { Text(stringResource(R.string.retry), fontSize = 16.sp) }
                    }
                    TextButton(onClick = openAdb, Modifier.fillMaxWidth()) { Text(stringResource(R.string.adb_settings)) }
                }
                waiting -> {
                    Callout(stringResource(R.string.size_warning))
                    OutlinedButton(onClick = moto::cancelWaiting, big, shape = shape) { Text(stringResource(R.string.cancel), fontSize = 16.sp) }
                }
                running -> OutlinedButton(
                    onClick = moto::stop, big, shape = shape,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.Bad)
                ) { Text(stringResource(R.string.disconnect), fontSize = 16.sp) }
                adbMissing -> Button(onClick = openAdb, big, shape = shape) { Text(stringResource(R.string.adb_setup), fontSize = 16.sp) }
                !hasBike -> Button(onClick = ::readQr, big, shape = shape) {
                    Icon(Icons.Qr, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.read_bike_qr), fontSize = 16.sp)
                }
                !hasApp -> Button(onClick = pickApp, big, shape = shape) { Text(stringResource(R.string.choose_app), fontSize = 16.sp) }
                else -> {
                    Callout(stringResource(R.string.size_warning))
                    Button(onClick = ::connect, big, shape = shape) { Text(stringResource(R.string.connect), fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
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
            title = { Text(stringResource(R.string.your_bike)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    BikeField(stringResource(R.string.bike_name), bike.name)
                    BikeField(stringResource(R.string.bike_wifi), bike.ssid)
                    BikeField(stringResource(R.string.bike_password), bike.password.ifEmpty { "—" })
                    if (bike.model.isNotEmpty()) BikeField(stringResource(R.string.bike_model), bike.model)
                    if (bike.serial.isNotEmpty()) BikeField(stringResource(R.string.bike_serial), bike.serial)
                }
            },
            confirmButton = {
                TextButton(onClick = { onDismiss(); readQr() }, enabled = !running) { Text(stringResource(R.string.read_other_qr)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } }
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
            TopBar(stringResource(R.string.log), onBack = onBack) {
                IconButton(onClick = { moto.share(this@MainActivity) }) { Icon(Icons.Share, stringResource(R.string.share_log)) }
            }
            Surface(
                Modifier.weight(1f).fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                shape = RoundedCornerShape(20.dp),
                color = Palette.Surface
            ) {
                if (lines.isEmpty()) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.log_empty), color = Palette.Muted)
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
        // Asking every app its name takes a while with a few hundred of them.
        val apps = produceState(emptyList<Pair<String, String>>()) {
            value = withContext(Dispatchers.IO) { launchableApps() }
        }.value
        var query by remember { mutableStateOf("") }
        val pickLabel = stringResource(R.string.pick)
        val pickedLabel = stringResource(R.string.picked)
        val shown = apps.filter { query.isBlank() || it.second.contains(query.trim(), ignoreCase = true) }
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Palette.Surface
        ) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(stringResource(R.string.pick_app_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    query, { query = it },
                    Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.search)) },
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
                            .clickable(onClickLabel = pickLabel, role = Role.Button) { onPick(pkg) }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .semantics { if (pkg == chosen) stateDescription = pickedLabel },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppIcon(pkg)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.bodyLarge)
                            if (isNav) Text(stringResource(R.string.navigation), style = MaterialTheme.typography.bodySmall, color = Palette.Primary)
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

    private fun launchable(pkg: String) = runCatching { packageManager.getLaunchIntentForPackage(pkg) != null }.getOrDefault(false)

    private fun ignoringBatteryOptimizations(): Boolean =
        (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    @SuppressLint("BatteryLife")
    private fun askBatteryExemption() {
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
    }

    /** The code is typed in a notification, so Android's pairing dialog never has to close. */
    private fun pairByNotice() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // Refused for good, Android answers at once and the notice settings open.
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled() || !PairingService.canAsk(this)) return noNotifications()
        PairingService.start(this)
        openDeveloperOptions()
    }

    private fun noNotifications() {
        Toast.makeText(this, R.string.no_notifications, Toast.LENGTH_LONG).show()
        runCatching {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
    }

    private fun openDeveloperOptions() {
        runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
    }

    private companion object {
        const val KEY_ASKED = "permisos_pedidos"

        /** Density on the bike's 592 x 816 screen: higher, bigger letters and less map. */
        val SIZES = listOf(
            Triple(200, R.string.size_small, R.string.size_small_note),
            Triple(240, R.string.size_normal, R.string.size_normal_note),
            Triple(280, R.string.size_large, R.string.size_large_note),
            Triple(320, R.string.size_huge, R.string.size_huge_note),
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
