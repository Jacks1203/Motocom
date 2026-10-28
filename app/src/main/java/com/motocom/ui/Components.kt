package com.motocom.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motocom.R

enum class Tone(val color: Color) {
    NEUTRAL(Palette.Primary), GOOD(Palette.Good), WARN(Palette.Warn), BAD(Palette.Bad)
}

/** Title row of every screen, with a back arrow on all but the first. */
@Composable
fun TopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    logo: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(Icons.Back, stringResource(R.string.back)) }
        } else {
            Spacer(Modifier.width(12.dp))
        }
        if (logo != null) {
            logo()
            Spacer(Modifier.width(12.dp))
        }
        Text(
            title,
            Modifier.weight(1f).semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        actions()
        Spacer(Modifier.width(4.dp))
    }
}

/** What is going on, in big: the first thing on each screen. */
@Composable
fun StatusPanel(tone: Tone, title: String, detail: String?, icon: ImageVector, working: Boolean = false) {
    val color by animateColorAsState(tone.color, label = "tono")
    Surface(shape = RoundedCornerShape(24.dp), color = Palette.Surface, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(52.dp).clip(CircleShape).background(color.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                if (working) {
                    CircularProgressIndicator(Modifier.size(26.dp), color = color, strokeWidth = 3.dp)
                } else {
                    Icon(icon, null, tint = color, modifier = Modifier.size(26.dp))
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                if (detail != null) {
                    Text(detail, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
                }
            }
        }
    }
}

/** A titled group of rows on one rounded surface. */
@Composable
fun Section(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                title.uppercase(),
                Modifier.padding(start = 4.dp, bottom = 8.dp).semantics { heading() },
                style = MaterialTheme.typography.labelMedium,
                color = Palette.Muted,
                letterSpacing = 1.2.sp
            )
        }
        Surface(shape = RoundedCornerShape(20.dp), color = Palette.Surface, modifier = Modifier.fillMaxWidth()) {
            Column(content = content)
        }
    }
}

@Composable
fun RowDivider() = HorizontalDivider(Modifier.padding(start = 72.dp), color = Palette.Outline, thickness = 1.dp)

/** One line of a [Section]: what it is, what it is set to and, if it can be changed, a chevron. */
@Composable
fun ListRow(
    leading: @Composable () -> Unit,
    title: String,
    value: String,
    onClick: (() -> Unit)?,
    trailing: @Composable (() -> Unit)? = if (onClick != null) {
        { Icon(Icons.Chevron, null, tint = Palette.Muted) }
    } else null,
    clickLabel: String? = null,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp)
            .then(if (onClick != null) Modifier.clickable(onClickLabel = clickLabel, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { leading() }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
            Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/** A round tinted badge with an icon, the leading part of a [ListRow]. */
@Composable
fun IconBadge(icon: ImageVector, tint: Color = Palette.Primary) {
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/** A short notice: an icon, some text and, optionally, something to do about it. */
@Composable
fun Callout(
    text: String,
    tone: Tone = Tone.NEUTRAL,
    icon: ImageVector = if (tone == Tone.NEUTRAL) Icons.Info else Icons.Warning,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(tone.color.copy(alpha = 0.10f))
            .border(1.dp, tone.color.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = tone.color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = Palette.Text)
        if (action != null) {
            Spacer(Modifier.width(8.dp))
            action()
        }
    }
}

/** Pick one of a few, side by side. */
@Composable
fun Segmented(options: List<String>, selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp))
            .background(Palette.SurfaceHigh).padding(4.dp).selectableGroup()
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            val fill by animateColorAsState(if (on) Palette.Primary else Color.Transparent, label = "segmento")
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(10.dp)).background(fill)
                    .selectable(selected = on, enabled = enabled, role = Role.RadioButton) { onSelect(i) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = when {
                        on -> Palette.OnPrimary
                        enabled -> Palette.Text
                        else -> Palette.Muted.copy(alpha = 0.6f)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * The bike's 592 x 816 screen at [dpi], with a sketch of a navigation app on it: the same
 * banner, bar and roads take more of the screen as the density goes up.
 */
@Composable
fun BikeScreenPreview(dpi: Int, modifier: Modifier = Modifier) {
    val density by animateFloatAsState(dpi.toFloat(), label = "densidad")
    val description = stringResource(R.string.size_preview)
    Canvas(
        modifier.aspectRatio(BIKE_WIDTH / BIKE_HEIGHT).clip(RoundedCornerShape(12.dp))
            .border(2.dp, Palette.Outline, RoundedCornerShape(12.dp))
            .semantics { contentDescription = description }
    ) {
        // Pixels of this preview per dp of the bike's virtual screen.
        val u = size.width * density / (BIKE_WIDTH * 160f)
        val w = size.width
        val h = size.height
        drawRect(Color(0xFF1A2230))
        val road = Color(0xFF2C3748)
        drawLine(road, Offset(0f, h * 0.38f), Offset(w, h * 0.30f), strokeWidth = 14 * u)
        drawLine(road, Offset(0f, h * 0.70f), Offset(w, h * 0.78f), strokeWidth = 10 * u)
        drawLine(road, Offset(w * 0.28f, 0f), Offset(w * 0.22f, h), strokeWidth = 10 * u)
        drawLine(road, Offset(w * 0.76f, 0f), Offset(w * 0.84f, h), strokeWidth = 8 * u)
        val puck = Offset(w * 0.5f, h - 64 * u - 70 * u)
        val route = Path().apply {
            moveTo(puck.x, puck.y)
            cubicTo(puck.x, puck.y - 60 * u, w * 0.78f, h * 0.45f, w * 0.80f, h * 0.31f)
            lineTo(w * 0.78f, 0f)
        }
        drawPath(route, Palette.Primary, style = Stroke(width = 7 * u, cap = StrokeCap.Round))
        drawCircle(Color.White, radius = 11 * u, center = puck)
        drawCircle(Palette.Primary, radius = 8 * u, center = puck)

        // Turn banner on top.
        val bannerH = 76 * u
        drawRect(Color(0xFF1565C0), size = Size(w, bannerH))
        val pad = 14 * u
        drawRoundRect(Color.White.copy(alpha = 0.9f), Offset(pad, pad), Size(bannerH - 2 * pad, bannerH - 2 * pad), CornerRadius(6 * u))
        val textX = bannerH
        drawRoundRect(Color.White, Offset(textX, pad + 4 * u), Size(150 * u, 14 * u), CornerRadius(4 * u))
        drawRoundRect(Color.White.copy(alpha = 0.6f), Offset(textX, pad + 28 * u), Size(100 * u, 10 * u), CornerRadius(4 * u))

        // Arrival bar at the bottom.
        val barH = 64 * u
        drawRect(Color(0xFF0F141C), Offset(0f, h - barH), Size(w, barH))
        drawRoundRect(Color.White, Offset(pad, h - barH + 16 * u), Size(70 * u, 14 * u), CornerRadius(4 * u))
        drawRoundRect(Color.White.copy(alpha = 0.5f), Offset(pad, h - barH + 38 * u), Size(110 * u, 10 * u), CornerRadius(4 * u))
        drawRoundRect(Palette.Bad.copy(alpha = 0.85f), Offset(w - pad - 70 * u, h - barH + 14 * u), Size(70 * u, 36 * u), CornerRadius(18 * u))
    }
}

private const val BIKE_WIDTH = 592f
private const val BIKE_HEIGHT = 816f
