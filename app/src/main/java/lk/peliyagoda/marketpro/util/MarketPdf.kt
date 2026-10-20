package lk.peliyagoda.marketpro.util

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.OutputStream

/** Only fully purchased, requested items qualify as completed for the final-cost PDF. */
fun completedPurchases(rows: List<ConsolidatedRow>): List<ConsolidatedRow> =
    rows.filter {
        it.purchasedGrams > 0L && it.purchasedGrams >= it.requiredGrams
    }.sortedBy { it.item.name.lowercase() }

fun completedPurchaseTotal(rows: List<ConsolidatedRow>): Long =
    completedPurchases(rows).sumOf { it.lineCents }

/** Category totals contain COMPLETED items only. Uncompleted purchased goods still cost cash. */
data class CompletedCategoryGroup(
    val name: String,
    val rows: List<ConsolidatedRow>
) {
    val subtotalCents: Long get() = rows.sumOf { it.lineCents }
}

fun completedCategoryGroups(rows: List<ConsolidatedRow>): List<CompletedCategoryGroup> =
    completedPurchases(rows)
        .groupBy { it.item.category }
        .toSortedMap(String.CASE_INSENSITIVE_ORDER)
        .map { (category, completed) ->
            CompletedCategoryGroup(category, completed.sortedBy { it.item.name.lowercase() })
        }

/** Actual money spent includes purchased shortage/pending items, not only completed rows. */
fun totalMarketSpentCents(rows: List<ConsolidatedRow>): Long = rows.sumOf { it.lineCents }
fun cashBalanceCents(receivedCents: Long, rows: List<ConsolidatedRow>): Long =
    receivedCents - totalMarketSpentCents(rows)

/**
 * Android's native PDF renderer retains Sinhala text through its installed
 * Android font fallback and StaticLayout shaping. No external PDF library,
 * web connection, or storage permission is required.
 */
object MarketPdf {
    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val LEFT = 40f
    private const val RIGHT = 528f
    private const val BOTTOM = 788f
    private val navy = Color.rgb(16, 42, 55)
    private val muted = Color.rgb(75, 98, 110)
    private val teal = Color.rgb(8, 111, 125)
    private val light = Color.rgb(238, 248, 250)

    private fun paint(size: Float, color: Int, bold: Boolean = false) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = Typeface.create(
                Typeface.DEFAULT,
                if (bold) Typeface.BOLD else Typeface.NORMAL
            )
        }

    private fun drawWrapped(
        canvas: Canvas, content: String, x: Float, y: Float,
        width: Int, textPaint: TextPaint
    ): Int {
        val layout = StaticLayout.Builder
            .obtain(content, 0, content.length, textPaint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()
        canvas.save()
        canvas.translate(x, y)
        layout.draw(canvas)
        canvas.restore()
        return layout.height
    }

    fun write(date: String, rows: List<ConsolidatedRow>, output: OutputStream) =
        write(date, rows, 0L, output)

    fun write(
        date: String,
        rows: List<ConsolidatedRow>,
        cashReceivedCents: Long,
        output: OutputStream
    ) {
        require(cashReceivedCents >= 0) { "Cash received cannot be negative" }
        val groups = completedCategoryGroups(rows)
        val count = groups.sumOf { it.rows.size }
        require(count > 0) { "No completed purchases to export" }
        val completedTotal = groups.sumOf { it.subtotalCents }
        val spent = totalMarketSpentCents(rows)
        val balance = cashReceivedCents - spent

        val document = PdfDocument()
        var pageNumber = 0
        var page: PdfDocument.Page? = null
        var y = 0f

        fun finishPage() {
            page?.let { pg ->
                pg.canvas.drawText(
                    "Market Pro  |  " + date + "  |  Page " + pageNumber,
                    LEFT, 813f, paint(9f, muted)
                )
                document.finishPage(pg)
            }
            page = null
        }

        fun nextPage() {
            finishPage()
            pageNumber++
            val pg = document.startPage(
                PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNumber).create()
            )
            page = pg
            val canvas = pg.canvas
            canvas.drawColor(Color.WHITE)
            canvas.drawRoundRect(
                LEFT, 35f, RIGHT, 130f, 14f, 14f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = light }
            )
            canvas.drawText("PELIYAGODA MARKET PRO", LEFT + 16f, 63f,
                paint(12f, teal, true))
            canvas.drawText("Completed purchases by category", LEFT + 16f, 90f,
                paint(18f, navy, true))
            canvas.drawText("Date: " + date, LEFT + 16f, 114f, paint(11f, muted))
            y = 153f
        }

        fun c(): Canvas = requireNotNull(page).canvas
        fun ensure(height: Float) {
            if (y + height > BOTTOM) nextPage()
        }
        fun drawTotalBar(label: String, value: Long, background: Int) {
            ensure(51f)
            c().drawRoundRect(
                LEFT, y, RIGHT, y + 44f, 8f, 8f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background }
            )
            c().drawText(
                label, LEFT + 12f, y + 27f, paint(12f, Color.WHITE, true)
            )
            val valueText = "Rs. " + MarketMath.lkr(value)
            val p = paint(15f, Color.WHITE, true).apply {
                textAlign = Paint.Align.RIGHT
            }
            val available = 216f
            if (p.measureText(valueText) > available) {
                p.textSize = (p.textSize * available /
                    p.measureText(valueText)).coerceAtLeast(9f)
            }
            c().drawText(valueText, RIGHT - 12f, y + 28f, p)
            y += 51f
        }

        try {
            nextPage()
            var number = 0
            for (group in groups) {
                ensure(56f)
                val headerY = y
                c().drawRoundRect(
                    LEFT, headerY, RIGHT, headerY + 32f, 8f, 8f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(220, 243, 246) }
                )
                drawWrapped(c(), group.name, LEFT + 11f, headerY + 6f,
                    400, paint(17f, teal, true))
                y += 39f
                for (row in group.rows) {
                    number++
                    val namePaint = paint(17f, navy, true)
                    val nameWidth = 253
                    val nameLayout = StaticLayout.Builder.obtain(
                        number.toString() + ". " + row.item.name, 0,
                        (number.toString() + ". " + row.item.name).length,
                        namePaint, nameWidth
                    ).setIncludePad(false).build()
                    val height = maxOf(67f, nameLayout.height.toFloat() + 39f)
                    ensure(height)
                    c().drawRoundRect(
                        LEFT, y, RIGHT, y + height - 4f, 8f, 8f,
                        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = light }
                    )
                    val title = "$" + "{number}. " + "$" + "{row.item.name}"
                    val titleHeight = drawWrapped(c(), title, LEFT + 11f, y + 7f,
                        nameWidth, namePaint)
                    val qty = "Required: " + MarketMath.kg(row.requiredGrams) +
                        " kg  |  Bought: " + MarketMath.kg(row.purchasedGrams) + " kg"
                    val qtyPaint = paint(12f, muted, true)
                    if (qtyPaint.measureText(qty) > nameWidth.toFloat()) {
                        qtyPaint.textSize = (12f * nameWidth / qtyPaint.measureText(qty))
                            .coerceAtLeast(9f)
                    }
                    c().drawText(qty, LEFT + 11f, y + 9f + titleHeight + 17f, qtyPaint)
                    c().drawText("Final cost", RIGHT - 10f, y + 21f,
                        paint(10f, muted).apply { textAlign = Paint.Align.RIGHT })
                    val money = "Rs. " + MarketMath.lkr(row.lineCents)
                    val moneyPaint = paint(16f, teal, true).apply {
                        textAlign = Paint.Align.RIGHT
                    }
                    if (moneyPaint.measureText(money) > 185f) {
                        moneyPaint.textSize = (16f * 185f / moneyPaint.measureText(money))
                            .coerceAtLeast(10f)
                    }
                    c().drawText(money, RIGHT - 10f, y + 44f, moneyPaint)
                    y += height
                }
                drawTotalBar(group.name + " subtotal", group.subtotalCents, teal)
                y += 6f
            }

            y += 6f
            drawTotalBar("COMPLETED FINAL TOTAL (" + count + ")", completedTotal, navy)
            drawTotalBar("CASH RECEIVED", cashReceivedCents, Color.rgb(93, 87, 134))
            drawTotalBar("TOTAL SPENT (ALL PURCHASES)", spent, Color.rgb(93, 87, 134))
            drawTotalBar(
                "CASH BALANCE",
                balance,
                if (balance >= 0L) Color.rgb(23, 107, 61)
                else Color.rgb(163, 34, 43)
            )
            // Explicit explanation: the completed-only cost and cash-spend
            // total may differ while shortage/pending items have purchased lots.
            ensure(35f)
            drawWrapped(
                c(),
                "Cash balance uses every purchased lot (including incomplete items). " +
                    "Completed final total contains completed items only.",
                LEFT + 2f, y + 3f, 470, paint(9f, muted)
            )
            finishPage()
            document.writeTo(output)
        } finally {
            if (page != null) finishPage()
            document.close()
        }
    }
}
