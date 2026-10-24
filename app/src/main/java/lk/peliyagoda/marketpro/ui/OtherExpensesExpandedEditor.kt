package lk.peliyagoda.marketpro.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import lk.peliyagoda.marketpro.data.OtherExpenseAmounts
import lk.peliyagoda.marketpro.data.OtherExpenseField
import lk.peliyagoda.marketpro.util.CashAmountFormatter
import lk.peliyagoda.marketpro.util.MarketMath

/** Fields belong inside the existing Cash Received popup. No Dashboard card. */
@Composable
internal fun OtherExpensesExpandedEditor(
    businessDate: String,
    expenses: OtherExpenseAmounts,
    onSaveAmount: (OtherExpenseField, Long) -> Unit,
    onSaveRemarks: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Daily Operating Expenses",
            style = MaterialTheme.typography.titleSmall,
            color = ProColors.navy
        )
        OtherExpenseAmountField(
            businessDate, OtherExpenseField.TRANSPORT, "Transport",
            expenses.transportCents, onSaveAmount
        )
        OtherExpenseAmountField(
            businessDate, OtherExpenseField.LABOR_CHARGE, "Labor Charge",
            expenses.laborChargeCents, onSaveAmount
        )
        OtherExpenseAmountField(
            businessDate, OtherExpenseField.MEALS_AND_DRINKS, "Meals and Drinks",
            expenses.mealsDrinksCents, onSaveAmount
        )
        OtherExpenseAmountField(
            businessDate, OtherExpenseField.OTHERS, "Others",
            expenses.othersCents, onSaveAmount
        )
        var remarksDraft by remember(businessDate) { mutableStateOf(expenses.remarks) }
        OutlinedTextField(
            value = remarksDraft,
            onValueChange = { value ->
                if (value.length <= OtherExpenseAmounts.MAX_REMARK_CHARS) {
                    remarksDraft = value
                    onSaveRemarks(value)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Special Remarks") },
            placeholder = { Text("Transport details, helpers, or other notes…") },
            supportingText = { Text("Saved automatically · ${remarksDraft.length}/500") },
            minLines = 2,
            maxLines = 4,
            shape = RoundedCornerShape(14.dp)
        )
        Text(
            "Amounts and Remarks save automatically on this phone. " +
                "Cloud upload for Other Expenses requires a separate verified backend update.",
            color = ProColors.muted,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun OtherExpenseAmountField(
    businessDate: String,
    field: OtherExpenseField,
    label: String,
    savedCents: Long,
    onSave: (OtherExpenseField, Long) -> Unit
) {
    var draft by remember(businessDate, field) {
        mutableStateOf(MarketMath.editPriceOrBlank(savedCents))
    }
    val parsed = MarketMath.parseBlankPriceCents(draft)
    val valid = parsed != null && parsed in 0L..OtherExpenseAmounts.MAX_CATEGORY_CENTS
    OutlinedTextField(
        value = draft,
        onValueChange = { input ->
            val clean = CashAmountFormatter.cleanInput(input)
            if (clean.length <= 14) {
                draft = clean
                MarketMath.parseBlankPriceCents(clean)
                    ?.takeIf { it in 0L..OtherExpenseAmounts.MAX_CATEGORY_CENTS }
                    ?.let { onSave(field, it) }
            }
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        prefix = { Text("Rs. ") },
        visualTransformation = CashThousandsVisualTransformation,
        isError = !valid,
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    )
}
