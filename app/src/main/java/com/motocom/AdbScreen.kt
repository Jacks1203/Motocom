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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
fun AdbScreen(onBack: () -> Unit, openDeveloperOptions: () -> Unit, pairByNotice: () -> Unit) {
    val ready = ShellLink.status.value == ShellLink.Status.READY
    val busy by ShellLink.busy
    val paired by ShellLink.paired
    val message = ShellLink.message.value?.read(LocalContext.current).orEmpty()
    val failed by ShellLink.failed
    val justPaired by ShellLink.justPaired
    var manual by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TopBar("ADB", onBack = onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            when {
                ready -> StatusPanel(Tone.GOOD, stringResource(R.string.adb_chip_on), stringResource(R.string.adb_on_detail), Icons.Check)
                busy -> StatusPanel(Tone.NEUTRAL, stringResource(R.string.adb_preparing), message, Icons.Code, working = true)
                failed -> StatusPanel(Tone.BAD, stringResource(R.string.adb_failed), message, Icons.Warning)
                justPaired -> StatusPanel(Tone.GOOD, stringResource(R.string.adb_paired), stringResource(R.string.adb_paired_detail), Icons.Check)
                paired -> StatusPanel(Tone.NEUTRAL, stringResource(R.string.adb_chip_stopped), stringResource(R.string.adb_stopped_detail), Icons.Code)
                else -> StatusPanel(Tone.WARN, stringResource(R.string.adb_unset), stringResource(R.string.adb_unset_detail), Icons.Code)
            }

            if (ready) {
                Button(onClick = onBack, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
                    Text(stringResource(R.string.back_to_bike))
                }
                if (manual) {
                    PairSetup(busy, openDeveloperOptions, pairByNotice)
                    TextButton(onClick = { manual = false }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.hide)) }
                } else {
                    TextButton(onClick = { manual = true }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.pair_again)) }
                }
            } else if (paired && !manual) {
                Button(onClick = ShellLink::quickStart, Modifier.fillMaxWidth().height(52.dp), enabled = !busy, shape = RoundedCornerShape(16.dp)) {
                    Text(stringResource(if (busy) R.string.preparing else R.string.start_adb))
                }
                TextButton(onClick = { manual = true }, Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.pair_again_or_port))
                }
            } else {
                PairSetup(busy, openDeveloperOptions, pairByNotice)
                if (paired) {
                    TextButton(onClick = { manual = false }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.back_to_quick_start)) }
                }
            }

            Section(stringResource(R.string.help)) {
                HelpItem(R.string.help_enable, R.string.help_enable_text)
                HelpItem(R.string.help_split, R.string.help_split_text)
                HelpItem(R.string.help_after, R.string.help_after_text)
                HelpItem(R.string.help_ride, R.string.help_ride_text)
                HelpItem(R.string.help_no_wifi, R.string.help_no_wifi_text, last = true)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** The notification first, which needs no split screen; typing it all in by hand below. */
@Composable
private fun PairSetup(busy: Boolean, openDeveloperOptions: () -> Unit, pairByNotice: () -> Unit) {
    if (Build.VERSION.SDK_INT < 30) {
        ManualSetup(busy, openDeveloperOptions)
        return
    }
    val searching by PairingService.searching
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Step(null, stringResource(R.string.pair_by_notice), stringResource(R.string.notice_steps)) {
            Button(
                onClick = pairByNotice, enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)
            ) { Text(stringResource(if (searching) R.string.open_options_again else R.string.pair)) }
            if (searching) {
                Text(stringResource(R.string.waiting_code), style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
                TextButton(onClick = { PairingService.stop(context) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
            }
        }
        Text(
            stringResource(R.string.or_by_hand).uppercase(),
            Modifier.padding(start = 4.dp, top = 8.dp),
            style = MaterialTheme.typography.labelMedium, color = Palette.Muted
        )
        ManualSetup(busy, openDeveloperOptions)
    }
}

/** Pairing with the code, then starting on the connection port: the first time only. */
@Composable
private fun ManualSetup(busy: Boolean, openDeveloperOptions: () -> Unit) {
    var pairingPort by rememberSaveable { mutableStateOf("") }
    var connectionPort by rememberSaveable { mutableStateOf("") }
    // The one-time code is deliberately not persisted across recreation.
    var code by remember { mutableStateOf("") }
    // Used up once it has paired; kept after a failure, to see what was typed.
    val justPaired by ShellLink.justPaired
    LaunchedEffect(justPaired) { if (justPaired) code = "" }
    val numbers = KeyboardOptions(keyboardType = KeyboardType.Number)

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedButton(onClick = openDeveloperOptions, Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Icon(Icons.OpenIn, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.open_developer_options))
        }
        if (Build.VERSION.SDK_INT >= 30) {
            Step(1, stringResource(R.string.pair), stringResource(R.string.step_pair_text)) {
                OutlinedTextField(
                    pairingPort, { pairingPort = portOf(it) },
                    label = { Text(stringResource(R.string.pairing_port)) }, enabled = !busy, singleLine = true,
                    keyboardOptions = numbers, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    code, { code = it.filter(Char::isDigit).take(6) },
                    label = { Text(stringResource(R.string.six_digit_code)) }, enabled = !busy, singleLine = true,
                    keyboardOptions = numbers, modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(
                    onClick = { ShellLink.pair(pairingPort, code) },
                    enabled = !busy && pairingPort.isNotBlank() && code.length == 6,
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)
                ) { Text(stringResource(R.string.pair)) }
            }
            Step(2, stringResource(R.string.step_start), stringResource(R.string.step_start_text)) {
                ConnectionPort(connectionPort, { connectionPort = it }, busy, numbers)
            }
        } else {
            Step(1, stringResource(R.string.step_start), stringResource(R.string.step_start_android10)) {
                ConnectionPort(connectionPort, { connectionPort = it }, busy, numbers)
            }
        }
    }
}

@Composable
private fun ConnectionPort(port: String, onPort: (String) -> Unit, busy: Boolean, numbers: KeyboardOptions) {
    OutlinedTextField(
        port, { onPort(portOf(it)) },
        label = { Text(stringResource(R.string.connection_port)) }, enabled = !busy, singleLine = true,
        keyboardOptions = numbers, modifier = Modifier.fillMaxWidth()
    )
    Button(
        onClick = { ShellLink.start(port) }, enabled = !busy && port.isNotBlank(),
        modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)
    ) { Text(stringResource(if (busy) R.string.preparing else R.string.start_adb)) }
}

/** Android shows "192.168.1.20:37123"; pasted whole, only the port after the colon is kept. */
private fun portOf(typed: String): String {
    // An address without its port has none to give.
    if ('.' in typed && ':' !in typed) return ""
    return typed.substringAfterLast(':').filter(Char::isDigit).take(5)
}

@Composable
private fun Step(number: Int?, title: String, text: String, content: @Composable () -> Unit) {
    Section(null) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (number != null) Box(
                    Modifier.size(28.dp).clip(CircleShape).background(Palette.Primary.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("$number", color = Palette.Primary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
                if (number != null) Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(text, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
            content()
        }
    }
}

@Composable
private fun HelpItem(title: Int, text: Int, last: Boolean = false) {
    var open by rememberSaveable { mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 180f else 0f, label = "flecha")
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(onClickLabel = stringResource(if (open) R.string.hide else R.string.show)) { open = !open }
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(title), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Icon(Icons.ExpandMore, null, tint = Palette.Muted, modifier = Modifier.rotate(turn))
        }
        AnimatedVisibility(open) {
            Text(
                stringResource(text),
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Muted
            )
        }
        if (!last) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = Palette.Outline)
    }
}
