package com.smartmanager.whatsappshoot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// ---- colours: WhatsApp-like teal, calm neutral surfaces ----
object WssColors {
    val Ok = Color(0xFF1B9E5A)
    val Warn = Color(0xFFD98200)
    val Bad = Color(0xFFD33A3A)
    val Idle = Color(0xFF8A9A95)
    val HeroTop = Color(0xFF0B7A6C)
    val HeroBottom = Color(0xFF053F38)
}

private val LightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF075E54), onPrimary = Color.White,
    primaryContainer = Color(0xFFD5F0E9), onPrimaryContainer = Color(0xFF00201B),
    secondary = Color(0xFF3F7F74), secondaryContainer = Color(0xFFE2F1ED), onSecondaryContainer = Color(0xFF0B2B26),
    tertiary = Color(0xFF9A5B00), tertiaryContainer = Color(0xFFFFE9C7), onTertiaryContainer = Color(0xFF301A00),
    background = Color(0xFFF4F7F6), onBackground = Color(0xFF14201D),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF14201D),
    surfaceVariant = Color(0xFFE3EBE8), onSurfaceVariant = Color(0xFF3F4A47),
    outline = Color(0xFF8A9A95), outlineVariant = Color(0xFFD0DAD6),
    error = Color(0xFFBA1A1A), errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
)

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF4FD1B5), onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF0F4A41), onPrimaryContainer = Color(0xFFBDF2E6),
    secondary = Color(0xFF9FD1C6), secondaryContainer = Color(0xFF1E3B36), onSecondaryContainer = Color(0xFFD2EEE7),
    tertiary = Color(0xFFFFB950), tertiaryContainer = Color(0xFF4A3000), onTertiaryContainer = Color(0xFFFFE0B0),
    background = Color(0xFF0D1412), onBackground = Color(0xFFE1ECE8),
    surface = Color(0xFF151D1B), onSurface = Color(0xFFE1ECE8),
    surfaceVariant = Color(0xFF253431), onSurfaceVariant = Color(0xFFBBCAC5),
    outline = Color(0xFF6F807B), outlineVariant = Color(0xFF34443F),
    error = Color(0xFFFFB4AB), errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
)

private val WssShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp), extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun WssTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme
    MaterialTheme(colorScheme = scheme, shapes = WssShapes) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
    }
}

// ---- building blocks ----

enum class Tone { OK, WARN, BAD, IDLE }

@Composable
fun toneColor(t: Tone): Color = when (t) { Tone.OK -> WssColors.Ok; Tone.WARN -> WssColors.Warn; Tone.BAD -> WssColors.Bad; Tone.IDLE -> WssColors.Idle }

/** Big screen title with an optional one-line explanation. */
@Composable
fun ScreenTitle(title: String, subtitle: String? = null) {
    Column(Modifier.padding(top = 8.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A titled card that groups related content. */
@Composable
fun SectionCard(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

/** A highlighted message card (needs attention / information). */
@Composable
fun NoticeCard(title: String, body: String, tone: Tone = Tone.WARN, action: @Composable () -> Unit = {}) {
    val container = when (tone) {
        Tone.BAD -> MaterialTheme.colorScheme.errorContainer
        Tone.WARN -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val content = when (tone) {
        Tone.BAD -> MaterialTheme.colorScheme.onErrorContainer
        Tone.WARN -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = container, contentColor = content)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            action()
        }
    }
}

/** A checklist line: green check when [ok], amber warning otherwise, optional action button. */
@Composable
fun CheckRow(ok: Boolean, title: String, detail: String, actionLabel: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(
            if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning, contentDescription = if (ok) "Ready" else "Needs attention",
            tint = if (ok) WssColors.Ok else WssColors.Warn, modifier = Modifier.size(24.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (actionLabel != null) OutlinedButton(onClick = onAction) { Text(actionLabel) }
    }
}

@Composable
fun RowDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

/** A settings line: title and explanation on the left, a control on the right. */
@Composable
fun SettingRow(title: String, description: String? = null, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

@Composable
fun SwitchSetting(title: String, description: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) =
    SettingRow(title, description) { Switch(checked = checked, onCheckedChange = onChange) }

/** Choice chips that wrap onto several lines instead of running off the screen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceChips(options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { (label, value) -> FilterChip(selected = selected == value, onClick = { onSelect(value) }, label = { Text(label) }) }
    }
}

/** A small coloured status pill. */
@Composable
fun StatusPill(text: String, tone: Tone) {
    val c = toneColor(tone)
    Row(
        Modifier.clip(CircleShape).background(c.copy(alpha = 0.16f)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(c))
        Text(text, style = MaterialTheme.typography.labelLarge, color = c, fontWeight = FontWeight.SemiBold)
    }
}

/** Label on the left, value on the right (kept for simple read-outs). */
@Composable
fun StatusRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
