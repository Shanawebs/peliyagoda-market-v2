package lk.peliyagoda.marketpro.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Scale
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Independent UI-only design system for the Market Pro Expressive PREVIEW.
 *
 * No data objects, persistence, cloud code, purchase calculations, item IDs,
 * exports or navigation callbacks live in this file. The 2.36 branch is immutable.
 * All components are intentionally light enough for 4 GB Android devices.
 */
object ExpressivePalette {
    val ink = Color(0xFF132D3A)
    val muted = Color(0xFF526B79)
    val teal = Color(0xFF086F7D)
    val background = Color(0xFFF2F7FA)
    val outline = Color(0xFFD6E5EC)
    val paleTeal = Color(0xFFDDF5F2)
    val paleBlue = Color(0xFFE5F2FC)
    val paleViolet = Color(0xFFF0EBFC)
    val paleNeutral = Color(0xFFECF1F5)
    val violet = Color(0xFF5F5792)
    val success = Color(0xFF176A4A)
    val danger = Color(0xFFAD3245)
}

private val expressiveScheme = lightColorScheme(
    primary = ExpressivePalette.teal,
    onPrimary = Color.White,
    primaryContainer = ExpressivePalette.paleTeal,
    onPrimaryContainer = ExpressivePalette.ink,
    secondary = ExpressivePalette.violet,
    onSecondary = Color.White,
    secondaryContainer = ExpressivePalette.paleViolet,
    background = ExpressivePalette.background,
    onBackground = ExpressivePalette.ink,
    surface = Color.White,
    onSurface = ExpressivePalette.ink,
    surfaceVariant = ExpressivePalette.paleBlue,
    onSurfaceVariant = ExpressivePalette.muted,
    outline = ExpressivePalette.outline,
    error = ExpressivePalette.danger
)

private val expressiveTypography = Typography(
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 29.sp, fontWeight = FontWeight.ExtraBold),
    titleLarge = TextStyle(fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun MarketExpressiveTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = expressiveScheme,
        typography = expressiveTypography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp),
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(22.dp),
            extraLarge = RoundedCornerShape(28.dp)
        ),
        content = content
    )
}

@Composable
fun ExpressiveSectionCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, ExpressivePalette.outline),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 15.dp)) {
            content()
        }
    }
}

private fun iconForMetric(label: String): ImageVector = when (label) {
    "Total Required" -> Icons.Filled.Scale
    "Purchased" -> Icons.Filled.CheckCircle
    "Estimated Bill" -> Icons.Filled.AccountBalanceWallet
    else -> Icons.Filled.Inventory2
}

@Composable
fun ExpressiveMetricCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color = ExpressivePalette.teal
) {
    val background = when (label) {
        "Total Required" -> ExpressivePalette.paleTeal
        "Purchased" -> ExpressivePalette.paleBlue
        "Estimated Bill" -> ExpressivePalette.paleViolet
        else -> ExpressivePalette.paleNeutral
    }
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = background),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.17f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 13.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(32.dp)
                        .background(Color.White.copy(alpha = 0.80f), RoundedCornerShape(11.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(iconForMetric(label), contentDescription = null,
                        tint = accent, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(7.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = ExpressivePalette.muted,
                    maxLines = 2,
                    lineHeight = 15.sp,
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
