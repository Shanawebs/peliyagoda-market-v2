package lk.peliyagoda.marketpro.data

/**
 * V2.25 curated master items supplied by the user.
 *
 * Do NOT delete older items to install these names: historical requisitions,
 * purchases and invoice photos are tied to the original item IDs. Instead,
 * archived items remain in the database and are hidden from new-item pickers.
 *
 * Sinhala spelling and category assignments are kept as provided.
 */
object MarketCatalog {
    const val VERSION = 1
    const val CHINESE_VEG = "Chinese Veg"
    val categories = listOf(CHINESE_VEG, MarketConstants.VEGETABLES, MarketConstants.FRUITS)

    private fun lines(source: String, category: String): List<Pair<String, String>> =
        source.trimIndent().lineSequence()
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotEmpty() }
            .map { it to category }
            .toList()

    val entries: List<Pair<String, String>> by lazy {
        lines(
            """
            අයිස් බේජ්
            ඔරිගානෝ කොල
            කේල් කොල
            කොත්තමල්ලි කොල
            කෝලිෆ්ලවර්
            ගලන්ගල්
            චයිනීස් කැකිරි
            චයිනීස් ගො‍ටුකොල
            චයිනීස් ගෝවා
            චෙරි තක්කාලි
            ජැලපිනො මිරිස්
            ටැමරිලෝ - ගස් තක්කාලි
            ඩිල් කොල
            තහිනි
            පාස්ලි
            පැචෝයි
            පෙපර්ස් - කහ
            පෙපර්ස් - කොල
            පෙපර්ස් - රතු
            බටර් ලෙ‍ටුස්
            බැසිල්
            බ්‍රොකොලි
            මින්චි කොළ
            රොස්මරි
            ලෙ‍ටුස්
            ලෙ‍ටුස් - රතු
            සෙලරි කොල
            සේජ් ලෙ‍ටුස්
            """, CHINESE_VEG
        ) + lines(
            """
            අඟුණ කොල
            අතු ගෝවා
            අමු ඉඟුරු
            අමු මිරිස්
            අඹ (අමු )
            අරත්ත
            අර්තාපල් අල - රට
            අර්තාපල් අල - ලංකා
            අලු කෙසෙල්
            අලු පුහුල්
            අවර
            අසමෝදගම් කොල
            ඇම්බ‍රැල්ලා
            ඉන්නල
            ඉරමුසු
            එල බ‍ටු
            කංකුං
            කතුරු මුරුංගා
            කරපිංචා
            කරවිල
            කවුපි
            කැකිරි
            කැරට්
            කැ‍රැන්කොකු
            කිරි අල
            කෙසෙල් කොල
            කෙහෙල් මුව
            කොච්චි
            කොස් ඇට
            කොස් මදුලු
            කොහිල අල
            කොහිල දලු
            ගස්ලබු (අමු)
            ගො‍ටුකොල
            ගෝවා
            ග්‍රීන් හවුස් තක්කාලි
            චවු චවු
            තක්කාලි
            තම්පලා
            තිබ්බ‍ටු
            තුඹ කරවිල
            තෙබු
            දන්දිල
            දඹල
            දිය ලබු
            දෙල්
            දෙහි
            දෙහි කොල
            නිවිති
            නෙලුම් අල
            නෙලුම් කොල
            නෝකෝල්
            ප‍තෝල
            පිපිඤ්ඤා
            පොලොස්
            පොල්
            බටන් මෂ්රුම් - Fresh
            බටර් බෝංචි
            බටානා
            බඩ ඉරිඟු
            බණ්ඩක්කා
            බතල
            බීෆ් තක්කාලි
            බේබි අර්තාපල් අල
            බේබි කැරට්
            බොක්ස් ලෙ‍ටුස්
            බෝංචි
            මංඤොක්කා
            මංඤොක්කා කොල
            මයික්‍රෝ ලෙ‍ටුස්
            මෂ් රූම්
            මහදුරු
            මහදුරු අල
            මාලු මිරිස්
            මැල්ල කොල
            මෑකරල්
            මිදි කොල
            මුං ඇට
            මුගුනුවැන්න
            මුරුංගා
            මුරුංගා කොල
            යකිනාරං
            රතු අල
            රතු ගෝවා
            රතු තම්පලා
            රතු මිරිස්
            රතු රාඹු
            රතු ළූනු
            රනවරා
            රම්පේ
            රාඹු
            රොකට් ලෙ‍ටුස්
            ලීක්ස්
            ලෙමන්
            ලොකු ලූනු
            වකාමා
            වට්ටක්කා
            වට්ටක්කා කොල
            වම්බ‍ටු
            වම්බ‍ටු - රවුම්
            වැටකොලු
            වැල්පෙනෙල
            වියලි ඉඟුරු
            සලාද කොල
            සාරණ
            සුකිනි
            සුකිනි - කහ
            සුදු ලූනු
            සුදු ලූනු ගස්
            සේර
            හාතවාරිය
            හීන් බෝවිටියා
            ළූනු ගස්
            ළූනු මල්
            """, MarketConstants.VEGETABLES
        ) + lines(
            """
            අන්නාසි
            අඹ (අල්පෝන්සු)
            අඹ (කර්තකොලොම්බන්)
            අලිගැට පේර
            ආනමාළු කෙසෙල්
            ඇඹරැල්ලා
            ඇඹුල් කෙසෙල්
            ඉඟුරු
            කටු අනෝදා
            කාමරංකා
            කැන්ටලූප්
            කිවි
            කොමඩු
            කොළ ඇපල්
            කොළ මිදි
            ගස්ලබු
            ඩ්‍රැගන් ෆෘට්
            තැඹිලි
            දිවුල්
            පැණි දොඩම්
            පැෂන් ෆෘට්
            පේර
            මැංගුස්
            රට දොඩම්
            රතු ඇපල්
            රතු මිදි
            රඹුටන්
            වෙරලු
            ස්ට්‍රෝබෙරි
            """, MarketConstants.FRUITS
        )
    }
}
