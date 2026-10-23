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
    val page = Color(0xFFF2F7FA)
    val headerTop = Color(0xFFDDF5F2)
    val headerBottom = Color(0xFFE9F4F9)
    val card = Color(0xFFFFFFFF)
    val cardBorder = Color(0xFFD6E5EC)
    val itemInk = Color(0xFF132D3A)
    val amountInk = Color(0xFF1B4250)
    val purpleAmount = Color(0xFF5F5792)
    // Four distinct light summary tiles, with clear borders on white/light screens.
    // Foreground numeric accents are intentionally unchanged from ProColors.
    val metricTeal = Color(0xFFDDF5F2)
    val metricPurple = Color(0xFFF0EBFC)
    val metricNeutral = Color(0xFFECF1F5)
    val metricPurchased = Color(0xFFE5F2FC)
    val metricTealBorder = Color(0xFFC5E7E2)
    val metricPurpleBorder = Color(0xFFDAD2EE)
    val metricNeutralBorder = Color(0xFFD6E0E8)
    val metricPurchasedBorder = Color(0xFFCEE3F0)

    // Two visually distinct kitchen information cards. No action buttons or
    // selectable category chips use these colors.
    val kitchenBanner = Color(0xFFE4F1FA)
    val kitchenBannerBorder = Color(0xFFC6DFEB)
    val kitchenCategoryBanner = Color(0xFFDFF5F1)
    val kitchenCategoryBorder = Color(0xFFC4E5DB)
    val marketBanner = Color(0xFFEAEAF8)
    val purchaseCard = Color(0xFFFFFFFF)
    val purchaseCardBorder = Color(0xFFD6E5EC)
    val purchaseTitlePanel = Color(0xFFE5F2FC)
    val extraPurchasePanel = Color(0xFFEFF8FA)
}
