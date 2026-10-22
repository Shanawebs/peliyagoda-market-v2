package lk.peliyagoda.marketpro.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
        "📅 1. දිනය තෝරාගැනීම",
        "ඉහළ Calendar සලකුණ ඔබා අවශ්‍ය දිනය තෝරන්න. වම් සහ දකුණු ඊතල මඟින් දින මාරු කළ හැකිය. " +
            "දත්ත ඇතුළත් කර ඇති දින Calendar එකේ සලකුණු කර ඇත. එක් එක් දිනයේ දත්ත වෙන වෙනම පවතී."
    ),
    MarketHelpSection(
        "🍽️ 2. Kitchens – කුස්සි අවශ්‍යතා",
        "Kitchens පිටුවෙන් Main, Indian හෝ Bar/Pastry කුස්සිය තෝරන්න. " +
            "Add Items ඔබා අවශ්‍ය භාණ්ඩ තෝරාගෙන Required/Require කොටසට ප්‍රමාණය kg වලින් ඇතුළත් කරන්න. " +
            "වලංගු ප්‍රමාණ ස්වයංක්‍රීයව Save වේ."
    ),
    MarketHelpSection(
        "🛒 3. Market – භාණ්ඩ මිලදී ගැනීම",
        "Market පිටුවේ භාණ්ඩ Category අනුව පෙන්වයි. Required යනු කුස්සිවල මුළු අවශ්‍යතාවයයි. " +
            "Purchased කොටසට මිලදී ගත් ප්‍රමාණයත් Market Price කොටසට කිලෝග්‍රෑම් එකක මිලත් ඇතුළත් කරන්න. " +
            "ඉන්පසු වියදම ස්වයංක්‍රීයව ගණනය වේ. හිඟ ප්‍රමාණයක් තිබේ නම් මුල් මිලදී ගැනීමෙන් පසු (+) " +
            "ලකුණෙන් වෙනත් මිලකට අමතර මිලදී ගැනීමක් ඇතුළත් කළ හැකිය. අවශ්‍ය නම් Invoice ඡායාරූපයක්ද එක් කරන්න."
    ),
    MarketHelpSection(
        "📊 4. Dashboard – සාරාංශය",
        "සියලු කුස්සිවල අවශ්‍ය ප්‍රමාණය, මිලදී ගත් ප්‍රමාණය, ඇස්තමේන්තු වියදම සහ භාණ්ඩ තත්ත්වය මෙහි පෙන්වයි. " +
            "Pending = තවම මිලදී ගෙන නැත. Shortage = අවශ්‍යතාවයට වඩා අඩුවෙන් මිලදී ගෙන ඇත. " +
            "Completed = අවශ්‍ය ප්‍රමාණය සම්පූර්ණ වී ඇත."
    ),
    MarketHelpSection(
        "💰 5. Cash Received – මුදල්",
        "Market පිටුවේ Cash Received විවෘත කර මිලදී ගැනීම් සඳහා ලැබුණු මුදල ඇතුළත් කරන්න. " +
            "Total Spent සහ ඉතිරි මුදල ගණනය කර පෙන්වයි."
    ),
    MarketHelpSection(
        "📈 6. PDF, Excel සහ Price Charts",
        "Dashboard එකේ PDF Button එකෙන් සම්පූර්ණ කළ මිලදී ගැනීම් වාර්තාව ලබාගත හැකිය. " +
            "ඉහළ Menu එකෙන් Export Excel (.xlsx) භාවිත කර වාර්තාව ලබාගන්න. " +
            "Items Price Chart මඟින් කලින් සටහන් වූ මිල ගණන් සහ මිලදී ගත් ප්‍රමාණවල වෙනස්වීම් බලන්න."
    ),
    MarketHelpSection(
        "🗂️ 7. Master Item Catalog",
        "ඉහළ Item Catalog සලකුණ ඔබා භාණ්ඩ Category අනුව සොයන්න. " +
            "නව භාණ්ඩයක් එක් කිරීමට Add Item භාවිත කරන්න. දැනට ඇති භාණ්ඩ නමක් අවශ්‍ය නම් සංස්කරණය කළ හැකිය."
    ),
    MarketHelpSection(
        "💾 8. Backup සහ Restore",
        "Backup All Records (JSON) මඟින් දත්තවල පිටපතක් සුරක්ෂිත කරගන්න. " +
            "Restore Backup (JSON) භාවිත කිරීමට Owner විසින් පිහිටුවන ලද අංක 6ක PIN එක අවශ්‍යයි. " +
            "Restore කිරීමෙන් දැනට ඇති Catalog, Kitchen සහ Purchase දත්ත Backup එකේ දත්ත මඟින් Replace විය හැකිය. " +
            "Restore කිරීමට පෙර අලුත් Backup එකක් Save කරන්න. Invoice ඡායාරූප JSON Backup එකට ඇතුළත් නොවේ."
    ),
    MarketHelpSection(
        "☁️ 9. Cloud Sync (DEV)",
        "Cloud Sync තවම සංවර්ධන/පරීක්ෂණ පහසුකමකි. වගකිවයුතු පුද්ගලයාගේ අවසරයකින් තොරව එය Enable නොකරන්න. " +
            "Cloud Sync සක්‍රිය වී ඇති විට දත්ත අහඹු ලෙස Replace වීම වැළැක්වීමට Restore අවහිර කර ඇත."
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
                    fontSize = 21.sp,
                    lineHeight = 32.sp,
                    textAlign = TextAlign.Center
                )
                HorizontalDivider(
                    modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
                    color = dividerColor
                )

                // Keep the heading and close button stationary while guide scrolls.
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(top = 8.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(17.dp)
                ) {
                    Text(
                        "Market Pro මඟින් කුස්සි අවශ්‍යතා, මිලදී ගැනීම්, මිල ගණන් සහ වියදම් පහසුවෙන් කළමනාකරණය කළ හැකිය.",
                        fontFamily = sinhalaGuideFont,
                        fontSize = 18.sp,
                        lineHeight = 31.sp,
                        color = ink
                    )
                    helpSections.forEach { section ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                section.heading,
                                fontFamily = sinhalaGuideFont,
                                fontWeight = FontWeight.Bold,
                                fontSize = 19.sp,
                                lineHeight = 31.sp,
                                color = ink
                            )
                            Text(
                                section.description,
                                fontFamily = sinhalaGuideFont,
                                fontSize = 18.sp,
                                lineHeight = 31.sp,
                                color = Color(0xFF3D4650)
                            )
                        }
                    }
                    MarketHelpContactCard {
                        val dial = Intent(Intent.ACTION_DIAL, Uri.parse("tel:0705505250"))
                        runCatching { context.startActivity(dial) }
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
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Icon(Icons.Outlined.LocalMall, contentDescription = null,
                tint = ink, modifier = Modifier.size(32.dp))
            Text(
                "Market Pro for Purchasing\nDepartment - Bellagio Ltd",
                modifier = Modifier.fillMaxWidth(),
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 18.sp,
                lineHeight = 26.sp,
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
                        color = ink, fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.SansSerif)
                }
            }
            HorizontalDivider(color = dividerColor)
            Text("Concept & Developed By", color = Color(0xFF64727A),
                fontFamily = FontFamily.SansSerif, fontSize = 16.sp,
                textAlign = TextAlign.Center)
            Text(
                "Shanaka Wijesooriya",
                modifier = Modifier.fillMaxWidth(),
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 20.sp,
                textAlign = TextAlign.Center,
                color = ink
            )
        }
    }
}
