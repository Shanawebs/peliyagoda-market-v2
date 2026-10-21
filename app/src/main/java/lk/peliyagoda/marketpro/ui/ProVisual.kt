package lk.peliyagoda.marketpro.ui

import androidx.compose.ui.graphics.Color

/**
 * V2.18 visual-only palette for non-interactive surfaces and content.
 *
 * Deliberately separate from ProColors: category chips, action buttons,
 * status badges, icons, navigation selections and their text retain all
 * V2.17 colors. Only general backgrounds, item typography, financial
 * figures and informational card surfaces use these tokens.
 */
object ProVisual {
    val page = Color(0xFFF1F6FA)
    val headerTop = Color(0xFFE6F5FA)
    val headerBottom = Color(0xFFDCEFF5)
    val card = Color(0xFFFFFFFF)
    val cardBorder = Color(0xFFDCE8EF)
    val itemInk = Color(0xFF123A48)
    val amountInk = Color(0xFF203E4B)
    val purpleAmount = Color(0xFF555079)
    // Four distinct light summary tiles, with clear borders on white/light screens.
    // Foreground numeric accents are intentionally unchanged from ProColors.
    val metricTeal = Color(0xFFD5F0EE)
    val metricPurple = Color(0xFFE7E1F7)
    val metricNeutral = Color(0xFFE5EDF6)
    val metricPurchased = Color(0xFFDCECF9)
    val metricTealBorder = Color(0xFFB4DFDB)
    val metricPurpleBorder = Color(0xFFCDC4EC)
    val metricNeutralBorder = Color(0xFFC6D7E4)
    val metricPurchasedBorder = Color(0xFFBBD7ED)

    // Two visually distinct kitchen information cards. No action buttons or
    // selectable category chips use these colors.
    val kitchenBanner = Color(0xFFD4EAF4)
    val kitchenBannerBorder = Color(0xFFB8D6E7)
    val kitchenCategoryBanner = Color(0xFFE0F2EF)
    val kitchenCategoryBorder = Color(0xFFC1E0DA)
    val marketBanner = Color(0xFFECE9FA)
    val purchaseCard = Color(0xFFF7FBFF)
    val purchaseCardBorder = Color(0xFFD8E7F1)
    val purchaseTitlePanel = Color(0xFFE3F3F9)
    val extraPurchasePanel = Color(0xFFEAF5FA)
}
