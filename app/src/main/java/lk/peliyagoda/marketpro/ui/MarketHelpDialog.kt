package lk.peliyagoda.marketpro.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocalMall
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import lk.peliyagoda.marketpro.R

// Sinhala: bundled Noto Sans Sinhala (SIL OFL). English: system sans-serif.
// Google Sans is not redistributed because no redistribution license is provided.
private val sinhalaGuideFont = FontFamily(Font(R.font.noto_sans_sinhala))
private val ink = Color(0xFF102A37)
private val dividerColor = Color(0xFFE1E5E7)

private data class MarketHelpSection(val heading: String, val description: String)

private val helpSections = listOf(
    MarketHelpSection(
        "🧭 1. දෛනික වැඩ කරන පිළිවෙළ",
        "• මුලින් ඉහළ Calendar එකෙන් අදාල දිනය තෝරන්න.\n• Kitchens පිටුවේ Main, Indian සහ Bar/Pastry කුස්සිවල භාණ්ඩ අවශ්‍යතා ඇතුළත් කරන්න.\n• ඉන්පසු Market පිටුවේ මිලදී ගත් ප්‍රමාණය, එක් kg එකක මිල සහ ලැබුණු මුදල් සටහන් කරන්න.\n• අවසානයේ Dashboard එකෙන් අඩුවෙන් මිලදී ගත් භාණ්ඩ සහ වියදම් පරීක්ෂා කර වාර්තා Export කරන්න."
    ),
    MarketHelpSection(
        "📅 2. දිනය තෝරාගැනීම",
        "• App එකේ ඉහළ ඇති Calendar සලකුණ ඔබා අවශ්‍ය දිනය තෝරාගන්න. වම් හා දකුණු ඊතලවලින් පෙර හෝ ඊළඟ දවසට යා හැකිය.\n• Calendar එකේ සලකුණු කර ඇති දිනවල දත්ත කලින් සටහන් කර තිබේ.\n• දවසක් මාරු කළ විට එම දිනයේම Kitchens, Purchases සහ Cash Received දත්ත පෙන්වයි. වෙනත් දවසකට ඇතුළත් කරන දත්ත සමඟ මිශ්‍ර නොවේ.\n• භාණ්ඩයක් හෝ ප්‍රමාණයක් නොපෙනේ නම් මුලින් දිනය නිවැරදිදැයි බලන්න."
    ),
    MarketHelpSection(
        "🗂️ 3. Master Item Catalog – භාණ්ඩ ලේඛනය",
        "• ඉහළ ඇති භාණ්ඩ පෙට්ටි සලකුණ (Item Catalog) ඔබන්න. Categories අනුව භාණ්ඩ ලැයිස්තුව තෝරන්න; Search කොටසෙන් භාණ්ඩ නම සොයන්න.\n• භාණ්ඩයක් නැත්නම් New Item Name යටතේ නම ටයිප් කර නිවැරදි Category එක තෝරා Add Item ඔබන්න.\n• Category එකක් අලුතින් අවශ්‍ය නම් Kitchens > Add Items තුළ Category එකක් එක් කිරීමේ විකල්පය භාවිත කරන්න.\n• දැනට ඇති භාණ්ඩයක් නම වෙනස් කළ යුතු නම් Edit සලකුණ භාවිත කරන්න. පරණ මිලදී ගැනීම් හා අවශ්‍යතා එම භාණ්ඩයට සම්බන්ධව පවතී.\n• එකම භාණ්ඩයට වෙනස් නම් කිහිපයක් නොයොදා නිවැරදි නමක් පවත්වාගන්න. එවිට Reports වඩාත් නිවැරදි වේ."
    ),
    MarketHelpSection(
        "🍽️ 4. Kitchens – අවශ්‍යතා ඇතුළත් කිරීම",
        "• පහළ Kitchens පිටුවට යන්න. ඉහළින් Main, Indian හෝ Bar/Pastry තෝරා අවශ්‍ය කුස්සියට යන්න.\n• Add Items ඔබා භාණ්ඩ සොයන්න, අවශ්‍ය භාණ්ඩ තෝරා Add කරන්න. තෝරාගත් භාණ්ඩ එම කුස්සියේ ලැයිස්තුවට එකතු වේ.\n• සෑම භාණ්ඩයකම Require කොටසට අවශ්‍ය ප්‍රමාණය kg වලින් ඇතුළත් කරන්න. උදාහරණ: 2.5 යනු කිලෝග්‍රෑම් 2යි ග්‍රෑම් 500යි.\n• වලංගු ප්‍රමාණයක් ඇතුළත් කළ පසු එය ස්වයංක්‍රීයව Save වේ. වෙනම Save Button එකක් අවශ්‍ය නැහැ.\n• වැරදීමකින් එක් කළ භාණ්ඩයක් ඉවත් කිරීමට එහි Delete සලකුණ ඔබා Confirmation ලබාදෙන්න. එම දිනයේ එම කුස්සියේ අවශ්‍යතාව පමණක් ඉවත් වේ.\n• කුස්සි තුනම අවසන් කළ පසු Dashboard එකෙන් සියලු කුස්සිවල එකතුව පරීක්ෂා කරන්න."
    ),
    MarketHelpSection(
        "📊 5. Dashboard – සියලු අවශ්‍යතා එකතුව",
        "• Dashboard එකෙන් සියලු කුස්සිවල ඒ දවසට අවශ්‍ය භාණ්ඩ Consolidated ලැයිස්තුවක් ලෙස පෙන්වයි.\n• Total Required යනු අවශ්‍ය මුළු ප්‍රමාණයයි. Purchased යනු මිලදී ගත් ප්‍රමාණයයි. Estimated Bill යනු ඇතුළත් කළ මිලදී ගැනීම් අනුව ගණනය වන මුදලයි.\n• සෑම භාණ්ඩයකටම Main, Indian සහ Bar/Pastry වලින් අවශ්‍ය ප්‍රමාණ වෙන වෙනම බලාගත හැකිය.\n• PENDING = තවම මිලදී ගෙන නැත. SHORTAGE = අවශ්‍ය ප්‍රමාණයට වඩා අඩුවෙන් මිලදී ගෙන ඇත. COMPLETED = අවශ්‍ය ප්‍රමාණය සම්පූර්ණ වී ඇත.\n• Category Buttons වලින් Vegetables, Fruits වැනි කාණ්ඩ වෙන්කර පරීක්ෂා කළ හැකිය. දවස අවසානයේ SHORTAGE භාණ්ඩ නැවත සොයා බලන්න."
    ),
    MarketHelpSection(
        "🛒 6. Market – මිලදී ගැනීම් සටහන් කිරීම",
        "• Market පිටුවට යන්න. Kitchens වලින් ඉල්ලා ඇති භාණ්ඩ මෙහි Category අනුව පෙන්වයි.\n• Required = අවශ්‍ය මුළු kg ප්‍රමාණය. Purchased = මිලදී ගත් kg ප්‍රමාණය. Market Price = එක් kg එකක මිල (රුපියල්).\n• උදාහරණයක්: අර්තාපල් 5 kg අවශ්‍යයි. මුලින් 3 kg, kg එකක් රු. 400 බැගින් ගත්තා නම් Purchased = 3 සහ Market Price = 400 යොදන්න. මුල් වියදම රු. 1,200යි; ඉතිරි හිඟය 2 kgයි.\n• වලංගු Quantity හා Price ඇතුළත් කරන විට වාර්තාව ස්වයංක්‍රීයව Save වේ. ප්‍රමාණ සහ මිල දෙකම නිවැරදිදැයි නැවත පරීක්ෂා කරන්න.\n• එක් භාණ්ඩයක Subtotal එකෙන් එම භාණ්ඩයේ ඇතුළත් කළ මුළු මිලදී ගැනීම් වියදම පෙන්වයි."
    ),
    MarketHelpSection(
        "➕ 7. අමතර මිලදී ගැනීම් සහ Invoice",
        "• මුලින් Purchased ප්‍රමාණය සහ Market Price දෙකම ඇතුළත් කර තිබිය යුතුයි. එම භාණ්ඩයේ තව හිඟයක් තිබේ නම් (+) සලකුණෙන් Additional Purchase එකක් එක් කරන්න.\n• උදාහරණයක්: ඉහත අර්තාපල් හිඟය 2 kg, පසුව kg එකක් රු. 450 බැගින් ගත්තා නම් Additional Quantity = 2 සහ Price = 450 යොදන්න.\n• එවිට මුල් රු. 1,200 + අමතර රු. 900 = මුළු රු. 2,100යි. වෙන වෙනම ගත් මිල ගණන් වෙන වෙනම සටහන් වේ.\n• අමතර මිලදී ගැනීමක් වැරදියි නම් එම Additional Purchase entry එකේ Quantity/Price නිවැරදි කරන්න.\n• භාණ්ඩයක Camera/Invoice සලකුණෙන් බිල් ඡායාරූපයක් එක් කළ හැකිය. ඡායාරූප දුරකථනයේම පවතින නිසා App මාරු කිරීමට පෙර වෙනම සුරක්ෂිත කරගන්න."
    ),
    MarketHelpSection(
        "💰 8. Cash Received – මුදල් ගණනය",
        "• Market පිටුවේ Cash Received විවෘත කර එම දිනයේ මිලදී ගැනීම් සඳහා ලබාදුන් මුදල ඇතුළත් කරන්න.\n• උදාහරණයක්: රු. 10,000ක් ලැබී සියලු මිලදී ගැනීම් සඳහා රු. 7,850ක් වැය කළා නම් ඉතිරි මුදල රු. 2,150යි.\n• අදාළ මුදල් ඇතුළත් කළ විට වලංගු අගය ස්වයංක්‍රීයව Save වේ. අවශ්‍ය නම් නැවත විවෘත කර සංශෝධනය කළ හැකිය.\n• මුදල් ලබාගත් සහ වියදම් වූ ප්‍රමාණය බිල්පත් සමඟ දවස අවසානයේ ගළපා බලන්න."
    ),
    MarketHelpSection(
        "📄 9. PDF, Excel සහ Items Price Chart",
        "• Dashboard එකේ PDF Button එකෙන් ඒ දිනයේ Completed Purchases සඳහා වාර්තාව ලබාගන්න. මිලදී ගැනීම් සම්පූර්ණ නොවූ භාණ්ඩ වෙනම පරීක්ෂා කරන්න.\n• ඉහළ ⋮ Menu එකේ Export Excel (.xlsx) මඟින් ගණනය කිරීම්/විශ්ලේෂණ සඳහා Excel Report එක ලබාගත හැකිය.\n• Items Price Chart විවෘත කර මුලින් Category එකත්, ඉන්පසු Item එකත් තෝරන්න. දින අනුව පෙර මිල ගණන් සහ Purchased Quantities පෙන්වයි.\n• මිල ගණන් වෙනස් වී ඇති ආකාරය බලා, අවශ්‍ය නම් පෙර බිල්පත් සමඟ සසඳන්න."
    ),
    MarketHelpSection(
        "💾 10. Backup සහ Restore – වැදගත්",
        "• ⋮ Menu > Backup All Records (JSON) තෝරා අලුත් Backup File එකක් දුරකථනයේ ආරක්ෂිත තැනක Save කරන්න. සෑම දිනකම හෝ වැදගත් වෙනස්කම්වලට පසු Backup එකක් ගැනීම හොඳ පුරුද්දකි.\n• Restore Backup (JSON) තේරූ විට පළමු වරට Owner විසින් 6-Digit PIN එක සකස් කළ යුතුයි. ඉන්පසු නිවැරදි PIN එකෙන් පමණක් File තෝරාගත හැකිය.\n• වැරදි PIN උත්සාහ 3කට පසු විනාඩි 5ක Lock එකක් යෙදේ. Restore කිරීමට පෙර නැවත අවසාන Confirmation එක ලබාදිය යුතුය.\n• ⚠️ Restore කිරීමෙන් දැනට ඇති Catalog, Kitchens, Purchases සහ Cash Records, File එකේ දත්ත මඟින් Replace විය හැකිය. Restore කිරීමට පෙර දැනට ඇති දත්තවල Backup එකක් Save කරගන්න.\n• JSON Backup එකට Invoice ඡායාරූප ඇතුළත් නොවේ. ඒවා වෙනම සුරක්ෂිත කරගන්න.\n• Cloud Sync සක්‍රියව ඇති App එකක Restore අවහිර කර ඇත. Cloud Restore/Disconnect වැනි වෙනස්කම් තහවුරු නොකර කරන්න එපා."
    ),
    MarketHelpSection(
        "☁️ 11. Cloud Owner සහ Cloud Sync (DEV)",
        "• Cloud Owner Login / Sign Up මඟින් Owner Account එකට පිවිසිය හැකිය. Login වීමෙන් පමණක් Records Cloud එකට Upload නොවේ.\n• Cloud Sync & Backup (DEV) තවම පරීක්ෂණ මට්ටමේ පහසුකමකි. වගකිවයුතු පුද්ගලයාගේ උපදෙස් නොමැතිව Enable නොකරන්න.\n• Cloud Sync සක්‍රිය කිරීමට පෙර පැරණි App එකේ JSON Backup එක අලුත් App එකට Restore කර අවශ්‍ය දත්ත සම්පූර්ණව තිබෙන බව පරීක්ෂා කරන්න.\n• Invoice Photos Cloud Sync හරහා Upload නොවන නිසා ඒවා Device එකේම පවතී. ජාල සම්බන්ධතාව නැති විට Pending Changes තිබිය හැකිය."
    ),
    MarketHelpSection(
        "🛠️ 12. ගැටලුවක් ඇති වුණොත්",
        "• භාණ්ඩයක් නොපෙනේ නම් නිවැරදි Date, Kitchen සහ Category/Filter තෝරාගෙනදැයි බලන්න.\n• ප්‍රමාණයක් Save නොවන්නේ නම් Quantity සහ Price වල අකුරු වෙනුවට වලංගු අංක ඇතුළත් කර තිබේදැයි පරීක්ෂා කරන්න.\n• Additional Purchase (+) වැඩ නොකරන්නේ නම් මුල් Purchased Qty සහ Market Price ඇතුළත් කර තිබේද, තවත් හිඟයක් තිබේද බලන්න.\n• Restore File වැරදි බව පෙන්වයි නම් නිවැරදි Market Pro JSON Backup එකක්දැයි පරීක්ෂා කරන්න. වැරදි File එකක් බලෙන් Restore කරන්න එපා.\n• දත්ත නැතිවීමක් වැළැක්වීමට කලින් App එක Uninstall කිරීම හෝ App Storage Clear කිරීම, Backup සහ Data Verification සාර්ථක වන තුරු නොකරන්න.\n• තවත් උදව් අවශ්‍ය නම් පහළ Help Line අංකය ඔබා Phone Dialer එක විවෘත කරගන්න."
    )
)


@Composable
fun MarketHelpDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.92f),
            shape = RoundedCornerShape(28.dp),
            color = Color(0xFFF8F8FC),
            shadowElevation = 12.dp
        ) {
            Column(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Market Pro for Purchasing\nDepartment - Bellagio Ltd",
                    modifier = Modifier.fillMaxWidth(),
                    color = ink,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 19.sp,
                    lineHeight = 27.sp,
                    textAlign = TextAlign.Center
                )
                Text(
                    "භාවිත මාර්ගෝපදේශය",
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    color = ProColors.teal,
                    fontFamily = sinhalaGuideFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    lineHeight = 30.sp,
                    textAlign = TextAlign.Center
                )
                HorizontalDivider(
                    modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
                    color = dividerColor
                )

                // Lazy rendering keeps the longer Sinhala guide responsive on 4GB devices.
                // The title and close button remain fixed while the guide scrolls.
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(15.dp)
                ) {
                    item(key = "introduction") {
                        Text(
                            "Market Pro මඟින් කුස්සි අවශ්‍යතා, මිලදී ගැනීම්, මිල ගණන් සහ වියදම් පහසුවෙන් කළමනාකරණය කළ හැකිය. පහත පියවර අනුව වැඩ කරන්න.",
                            fontFamily = sinhalaGuideFont,
                            fontSize = 17.sp,
                            lineHeight = 28.sp,
                            color = ink
                        )
                    }
                    items(helpSections, key = { it.heading }) { section ->
                        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(
                                section.heading,
                                fontFamily = sinhalaGuideFont,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                lineHeight = 29.sp,
                                color = ink
                            )
                            Text(
                                section.description,
                                fontFamily = sinhalaGuideFont,
                                fontSize = 17.sp,
                                lineHeight = 28.sp,
                                color = Color(0xFF3D4650)
                            )
                        }
                    }
                    item(key = "contact-card") {
                        MarketHelpContactCard {
                            val dial = Intent(Intent.ACTION_DIAL, Uri.parse("tel:0705505250"))
                            runCatching { context.startActivity(dial) }
                        }
                    }
                }
                HorizontalDivider(color = dividerColor)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Close", fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                            color = ProColors.teal)
                    }
                }
            }
        }
    }
}

@Composable
private fun MarketHelpContactCard(onCall: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        shape = RoundedCornerShape(24.dp),
        color = Color.White,
        border = BorderStroke(1.dp, dividerColor),
        shadowElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 19.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(Icons.Outlined.LocalMall, contentDescription = null,
                tint = ink, modifier = Modifier.size(32.dp))
            Text(
                "Market Pro for Purchasing\nDepartment - Bellagio Ltd",
                modifier = Modifier.fillMaxWidth(),
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                lineHeight = 25.sp,
                textAlign = TextAlign.Center,
                color = ink
            )
            HorizontalDivider(color = dividerColor)
            TextButton(onClick = onCall, modifier = Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Phone, contentDescription = null,
                        tint = ProColors.teal, modifier = Modifier.size(22.dp))
                    Text("Help Line: 0705505250",
                        color = ink, fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.SansSerif)
                }
            }
            HorizontalDivider(color = dividerColor)
            Text("Concept & Developed By", color = Color(0xFF64727A),
                fontFamily = FontFamily.SansSerif, fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center)
            Text(
                "Shanaka Wijesooriya",
                modifier = Modifier.fillMaxWidth(),
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                textAlign = TextAlign.Center,
                color = ink
            )
        }
    }
}
