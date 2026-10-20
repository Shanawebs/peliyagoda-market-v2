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
        it.requiredGrams > 0L && it.purchasedGrams > 0L &&
            it.purchasedGrams >= it.requiredGrams
    }.sortedBy { it.item.name.lowercase() }

fun completedPurchaseTotal(rows: List<ConsolidatedRow>): Long =
    completedPurchases(rows).sumOf { it.lineCents }

/**
 * Android's native PDF renderer retains Sinhala text through its installed
 * Android font fallback and StaticLayout shaping. No external PDF library,
 * web connection, or storage permission is required.
 */
object MarketPdf {
    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val LEFT = 36f
    private const val RIGHT = 559f
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

    fun write(date: String, rows: List<ConsolidatedRow>, output: OutputStream) {
        val completed = completedPurchases(rows)
        require(completed.isNotEmpty()) { "No completed purchases to export" }
        val total = completed.sumOf { it.lineCents }
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
            val info = PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNumber).create()
            val pg = document.startPage(info)
            page = pg
            val canvas = pg.canvas
            canvas.drawColor(Color.WHITE)
            canvas.drawRoundRect(LEFT, 35f, RIGHT, 130f, 14f, 14f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = light })
            canvas.drawText("PELIYAGODA MARKET PRO", LEFT + 16f, 63f,
                paint(12f, teal, true))
            canvas.drawText("Completed purchases", LEFT + 16f, 91f,
                paint(22f, navy, true))
            canvas.drawText("Date: " + date, LEFT + 16f, 114f,
                paint(11f, muted))
            y = 153f
        }

        fun canvas(): Canvas = requireNotNull(page).canvas

        try {
            nextPage()
            val heading = paint(11f, navy, true)
            // Stable A-Z item order and explicit final costs, including additional lots.
            for ((index, row) in completed.withIndex()) {
                val namePaint = paint(14f, navy, true)
                val availableNameWidth = 300
                val namelayout = StaticLayout.Builder.obtain(
                    row.item.name, 0, row.item.name.length, namePaint,
                    availableNameWidth
                ).setIncludePad(false).build()
                val rowHeight = maxOf(87f, namelayout.height.toFloat() + 50f)
                if (y + rowHeight > BOTTOM) nextPage()

                val c = canvas()
                c.drawRoundRect(LEFT, y, RIGHT, y + rowHeight - 8f,
                    9f, 9f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = light })

                val top = y + 11f
                val title = (index + 1).toString() + ". " + row.item.name
                val titleHeight = drawWrapped(
                    c, title, LEFT + 11f, top, availableNameWidth, namePaint
                )
                c.drawText(
                    "Required: " + MarketMath.kg(row.requiredGrams) + " kg" +
                        "  |  Bought: " + MarketMath.kg(row.purchasedGrams) + " kg",
                    LEFT + 11f, top + titleHeight + 17f, paint(10f, muted)
                )
                c.drawText(
                    "Final cost", RIGHT - 12f, y + 23f,
                    paint(10f, muted).apply { textAlign = Paint.Align.RIGHT }
                )
                c.drawText(
                    "Rs. " + MarketMath.lkr(row.lineCents),
                    RIGHT - 12f, y + 44f,
                    paint(15f, teal, true).apply { textAlign = Paint.Align.RIGHT }
                )
                y += rowHeight
            }
            if (y + 102f > BOTTOM) nextPage()
            val c = canvas()
            y += 10f
            c.drawRoundRect(LEFT, y, RIGHT, y + 80f, 10f, 10f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = teal })
            c.drawText(
                "Completed items: " + completed.size,
                LEFT + 16f, y + 27f, paint(12f, Color.WHITE, true)
            )
            c.drawText(
                "TOTAL FINAL COST: Rs. " + MarketMath.lkr(total),
                LEFT + 16f, y + 55f, paint(16f, Color.WHITE, true)
            )
            finishPage()
            document.writeTo(output)
        } finally {
            // Safe after writeTo success/failure. Never leave a page open.
            if (page != null) finishPage()
            document.close()
        }
    }
}
