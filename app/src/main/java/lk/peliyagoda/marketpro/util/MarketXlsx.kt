package lk.peliyagoda.marketpro.util

import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Minimal dependency-free XLSX exporter. XLSX holds Unicode strings as XML,
 * so Sinhala item names remain readable without CSV encoding detection.
 * All untrusted item names are written as inline strings, never formulas.
 */
object MarketXlsx {
    private data class Cell(val value: String, val numeric: Boolean = false)
    private fun txt(value: String) = Cell(value)
    private fun num(value: Long, scale: Int): Cell =
        Cell(BigDecimal.valueOf(value, scale).toPlainString(), true)
    private fun count(value: Int): Cell = Cell(value.toString(), true)

    private fun xml(value: String): String = buildString {
        for (c in value) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> if (c == '\t' || c == '\n' || c == '\r' || c >= ' ') append(c)
            }
        }
    }

    private fun letters(column: Int): String {
        var i = column + 1
        val result = StringBuilder()
        while (i > 0) {
            i--
            result.insert(0, ('A'.code + (i % 26)).toChar())
            i /= 26
        }
        return result.toString()
    }

    private fun sheet(data: List<List<Cell>>, widths: List<Int>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        append("<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>")
        append("<sheetFormatPr defaultRowHeight=\"17\"/>")
        append("<cols>")
        widths.forEachIndexed { index, width ->
            append("<col min=\"").append(index + 1).append("\" max=\"")
                .append(index + 1).append("\" width=\"")
                .append(width).append("\" customWidth=\"1\"/>")
        }
        append("</cols><sheetData>")
        data.forEachIndexed { rowIndex, cells ->
            val row = rowIndex + 1
            append("<row r=\"").append(row).append("\">")
            cells.forEachIndexed { col, cell ->
                val ref = letters(col) + row
                if (rowIndex == 0) {
                    append("<c r=\"").append(ref).append("\" s=\"1\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                        .append(xml(cell.value)).append("</t></is></c>")
                } else if (cell.numeric) {
                    append("<c r=\"").append(ref).append("\" s=\"2\"><v>")
                        .append(cell.value).append("</v></c>")
                } else {
                    append("<c r=\"").append(ref).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                        .append(xml(cell.value)).append("</t></is></c>")
                }
            }
            append("</row>")
        }
        append("</sheetData></worksheet>")
    }

    fun create(date: String, rows: List<ConsolidatedRow>): ByteArray {
        val summary = mutableListOf(
            listOf(
                txt("Date"), txt("Item"), txt("Category"),
                txt("Main Kitchen KG"), txt("Indian Kitchen KG"), txt("Pastry Kitchen KG"),
                txt("Total Required KG"), txt("Purchased KG"), txt("Variance KG"),
                txt("Initial Price LKR/KG"), txt("Extra Purchase Lots"),
                txt("Additional Cost LKR"), txt("Line Total LKR")
            )
        )
        val detail = mutableListOf(
            listOf(
                txt("Date"), txt("Item"), txt("Category"), txt("Purchase Type"),
                txt("Quantity KG"), txt("Unit Price LKR/KG"), txt("Line Amount LKR")
            )
        )
        rows.forEach { row ->
            summary.add(
                listOf(
                    txt(date), txt(row.item.name), txt(row.item.category),
                    num(row.mainGrams, 3), num(row.indianGrams, 3),
                    num(row.pastryGrams, 3), num(row.requiredGrams, 3),
                    num(row.purchasedGrams, 3), num(row.varianceGrams, 3),
                    row.purchase?.let { num(it.priceCentsPerKg, 2) } ?: txt(""),
                    count(row.additions.size), num(row.additionalCents, 2),
                    num(row.lineCents, 2)
                )
            )
            row.purchase?.let {
                detail.add(
                    listOf(txt(date), txt(row.item.name), txt(row.item.category), txt("Initial purchase"),
                        num(it.quantityGrams, 3), num(it.priceCentsPerKg, 2),
                        num(MarketMath.billCents(it.quantityGrams, it.priceCentsPerKg), 2))
                )
            }
            row.additions.forEachIndexed { index, addition ->
                detail.add(
                    listOf(txt(date), txt(row.item.name), txt(row.item.category),
                        txt("Additional purchase " + (index + 1)),
                        num(addition.quantityGrams, 3), num(addition.priceCentsPerKg, 2),
                        num(MarketMath.billCents(addition.quantityGrams, addition.priceCentsPerKg), 2))
                )
            }
        }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun add(path: String, data: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(data.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            add("[Content_Types].xml", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                  <Default Extension="xml" ContentType="application/xml"/>
                  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                  <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
                  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                  <Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                </Types>
            """.trimIndent())
            add("_rels/.rels", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                </Relationships>
            """.trimIndent())
            add("xl/workbook.xml", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                 xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <sheets>
                    <sheet name="Daily Summary" sheetId="1" r:id="rId1"/>
                    <sheet name="Purchase Details" sheetId="2" r:id="rId2"/>
                  </sheets>
                </workbook>
            """.trimIndent())
            add("xl/_rels/workbook.xml.rels", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/>
                  <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
                </Relationships>
            """.trimIndent())
            add("xl/styles.xml", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <fonts count="2">
                    <font><sz val="11"/><name val="Calibri"/></font>
                    <font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font>
                  </fonts>
                  <fills count="3">
                    <fill><patternFill patternType="none"/></fill>
                    <fill><patternFill patternType="gray125"/></fill>
                    <fill><patternFill patternType="solid"><fgColor rgb="FF086F7D"/><bgColor indexed="64"/></patternFill></fill>
                  </fills>
                  <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
                  <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
                  <cellXfs count="3">
                    <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
                    <xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFill="1" applyFont="1"/>
                    <xf numFmtId="2" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
                  </cellXfs>
                </styleSheet>
            """.trimIndent())
            add("xl/worksheets/sheet1.xml", sheet(summary, listOf(16, 31, 22, 19, 20, 19, 21, 18, 19, 23, 22, 22, 20)))
            add("xl/worksheets/sheet2.xml", sheet(detail, listOf(16, 31, 22, 25, 19, 22, 22)))
        }
        return out.toByteArray()
    }
}
