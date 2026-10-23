package lk.peliyagoda.marketpro.ui

/**
 * Deterministic Dashboard category matching.
 * UI selection is owned by MarketProApp; neither the stickyHeader nor a tab
 * animation may own an independent mutable copy of the selected category.
 */
internal object DashboardCategoryRules {
    fun matches(selected: String, itemCategory: String): Boolean =
        selected == "All" || selected == itemCategory
}
