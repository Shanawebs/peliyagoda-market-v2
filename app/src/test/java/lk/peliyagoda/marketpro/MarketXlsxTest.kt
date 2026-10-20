package lk.peliyagoda.marketpro

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import lk.peliyagoda.marketpro.data.ItemEntity
import lk.peliyagoda.marketpro.data.PurchaseAdditionEntity
import lk.peliyagoda.marketpro.data.PurchaseEntity
import lk.peliyagoda.marketpro.data.RequisitionEntity
import lk.peliyagoda.marketpro.util.MarketXlsx
import lk.peliyagoda.marketpro.util.consolidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketXlsxTest {
    @Test fun sinhalaAndNumericValuesSurviveRealXlsxExport() {
        val date = "2026-10-20"
        val entries = consolidate(
            items = listOf(ItemEntity(1, "කැරට් & Beetroot", "එළවළු")),
            requisitions = listOf(RequisitionEntity(date, "Main Kitchen", 1, 3500)),
            purchases = listOf(PurchaseEntity(date, 1, 2500, 15000)),
            additions = listOf(PurchaseAdditionEntity(1, date, 1, 1000, 17000))
        )
        val bytes = MarketXlsx.create(date, entries)
        assertTrue(bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte())
        val sheets = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
            while (true) {
                val e = input.nextEntry ?: break
                sheets[e.name] = input.readBytes()
                input.closeEntry()
            }
        }
        assertTrue(sheets.containsKey("[Content_Types].xml"))
        assertTrue(sheets.containsKey("xl/worksheets/sheet1.xml"))
        assertTrue(sheets.containsKey("xl/worksheets/sheet2.xml"))
        // All generated XML must be parseable by an actual XML parser.
        for ((name, content) in sheets) {
            if (name.endsWith(".xml") || name.endsWith(".rels")) {
                DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(content))
            }
        }
        val summary = sheets.getValue("xl/worksheets/sheet1.xml").toString(Charsets.UTF_8)
        val details = sheets.getValue("xl/worksheets/sheet2.xml").toString(Charsets.UTF_8)
        assertTrue(summary.contains("කැරට් &amp; Beetroot"))
        assertTrue(summary.contains("එළවළු"))
        assertTrue(summary.contains("<v>3.500</v>"))
        assertTrue(summary.contains("<v>545.00</v>")) // 2.5*150 + 1*170
        assertTrue(details.contains("Additional purchase 1"))
        assertTrue(details.contains("<v>170.00</v>"))
        assertFalse(summary.contains("ï»¿"))
        assertEquals(1, entries.size)
    }

    @Test fun textStartingWithEqualsRemainsStringNotFormula() {
        val records = consolidate(
            items = listOf(ItemEntity(1, "=SUM(A1:A5)", "Vegetables")),
            requisitions = listOf(RequisitionEntity("2026-10-20", "Main Kitchen", 1, 1000)),
            purchases = emptyList()
        )
        val bytes = MarketXlsx.create("2026-10-20", records)
        ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                if (entry.name == "xl/worksheets/sheet1.xml") {
                    val xml = input.readBytes().toString(Charsets.UTF_8)
                    assertTrue(xml.contains("<t xml:space=\"preserve\">=SUM(A1:A5)</t>"))
                    assertFalse(xml.contains("<f>"))
                }
                input.closeEntry()
            }
        }
    }
}
