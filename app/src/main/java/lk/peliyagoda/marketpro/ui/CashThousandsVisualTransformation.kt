package lk.peliyagoda.marketpro.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import lk.peliyagoda.marketpro.util.CashAmountFormatter

/**
 * Adds thousands separators while typing without changing the editable string.
 * Tracks inserted comma positions to keep the cursor and backspace consistent.
 */
object CashThousandsVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val source = text.text
        val formatted = CashAmountFormatter.grouped(source)
        val sourceOffsets = IntArray(source.length + 1)
        val decimalIndex = source.indexOf('.')
        val wholeEnd = if (decimalIndex >= 0) decimalIndex else source.length
        var visual = 0
        for (i in source.indices) {
            if (i > 0 && i < wholeEnd && (wholeEnd - i) % 3 == 0) visual++
            sourceOffsets[i] = visual
            visual++
        }
        sourceOffsets[source.length] = visual

        return TransformedText(
            AnnotatedString(formatted),
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int): Int =
                    sourceOffsets[offset.coerceIn(0, source.length)]

                override fun transformedToOriginal(offset: Int): Int {
                    val end = offset.coerceIn(0, formatted.length)
                    var count = 0
                    for (i in 0 until end) {
                        if (formatted[i] != ',') count++
                    }
                    return count.coerceIn(0, source.length)
                }
            }
        )
    }
}
