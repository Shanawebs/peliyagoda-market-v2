package lk.peliyagoda.marketpro.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Market Pro for Purchasing Department - Bellagio Ltd",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = ProColors.navy
                )
                Text(
                    "භාවිත මාර්ගෝපදේශය",
                    style = MaterialTheme.typography.titleSmall,
                    color = ProColors.teal,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .heightIn(max = 490.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(13.dp)
            ) {
                Text(
                    "Market Pro මඟින් කුස්සි අවශ්‍යතා, මිලදී ගැනීම්, මිල ගණන් සහ වියදම් පහසුවෙන් කළමනාකරණය කළ හැකිය.",
                    style = MaterialTheme.typography.bodyMedium
                )
                helpSections.forEach { section ->
                    Text(
                        section.heading,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = ProColors.navy
                    )
                    Text(section.description, style = MaterialTheme.typography.bodyMedium)
                }
                HorizontalDivider()
                Text(
                    "Market Pro for Purchasing Department - Bellagio Ltd",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.ExtraBold
                )
                TextButton(
                    onClick = {
                        // ACTION_DIAL opens the phone dialer without placing a call.
                        val dial = Intent(Intent.ACTION_DIAL, Uri.parse("tel:0705505250"))
                        if (dial.resolveActivity(context.packageManager) != null) {
                            context.startActivity(dial)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("☎ Help Line: 0705505250", fontWeight = FontWeight.Bold)
                }
                Text(
                    "Concept & Developed By",
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Shanaka Wijesooriya",
                    fontWeight = FontWeight.ExtraBold,
                    color = ProColors.navy,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp)
    )
}
