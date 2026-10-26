package lk.peliyagoda.marketpro.stagingowner

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Strictly offline Owner Keystore test harness. Not the purchasing app.
 * Production DB, Staff permission, real Owner Auth and Cloud writer are absent.
 */
class MainActivity : Activity() {
    private val lab = OwnerKeystoreLab()
    private lateinit var output:TextView
    private val darkGreen = Color.rgb(13, 85, 58)

    private fun dp(value:Int):Int = (resources.displayMetrics.density * value).toInt()
    private fun panel(color:Int):GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(16).toFloat()
    }
    private fun text(str:String,size:Float,bold:Boolean=false,color:Int=Color.BLACK) =
        TextView(this).apply {
            text=str
            textSize=size
            setTextColor(color)
            if(bold) setTypeface(null,Typeface.BOLD)
            setPadding(0,dp(6),0,dp(6))
        }
    private fun addButton(root:LinearLayout,label:String,action:()->Unit) {
        val btn=Button(this).apply {
            text=label
            isAllCaps=false
            backgroundTintList=ColorStateList.valueOf(darkGreen)
            setTextColor(Color.WHITE)
            setOnClickListener { action() }
        }
        root.addView(btn,LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,dp(56)).apply {
            topMargin=dp(12)
        })
    }

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll=ScrollView(this).apply { isFillViewport=true }
        val root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(22),dp(32),dp(22),dp(36))
            setBackgroundColor(Color.rgb(247,250,248))
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(text("Market Pro",28f,true,darkGreen))
        root.addView(text("STAGING • Owner Phone Key Test",17f,true,darkGreen))
        val description=text(
            "මෙය ආරක්ෂක පරීක්ෂණ APK එකක් පමණි.\n" +
            "No Cloud Login • No Internet • No Sync • No Staff Access\n" +
            "Production Market Pro එකට මෙයින් බලපෑමක් නැහැ.",14f,
            false,Color.rgb(60,73,66)
        )
        description.background=panel(Color.rgb(227,241,231))
        description.setPadding(dp(16),dp(14),dp(16),dp(14))
        root.addView(description,LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin=dp(16);bottomMargin=dp(16) })

        root.addView(text("Device security",20f,true,darkGreen))
        root.addView(text(
            "Create a non-exportable P-256 key in the Android Keystore. " +
            "Sign and verify a temporary local challenge. No secret or signature is uploaded.",
            15f,false,Color.DKGRAY
        ))

        output=text("Ready for local test. No account is connected.",15f)
        output.background=panel(Color.WHITE)
        output.setPadding(dp(16),dp(15),dp(16),dp(15))
        root.addView(output,LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin=dp(14);bottomMargin=dp(6) })

        addButton(root,"1. Generate / Check Owner Key") {
            try {
                val s=lab.summary()
                output.text="KEY READY ✓\nSHA-256 public fingerprint:\n" +
                    s.fingerprintSha256.chunked(16).joinToString("\n") +
                    "\n\nKeystore hardware backed: " + (if(s.hardwareBacked) "Yes" else "Not verified")
            }catch(_:Exception){output.text="Keystore unavailable on this device. No cloud changes made."}
        }
        addButton(root,"2. Sign & Verify Local Challenge") {
            try {
                output.text=if(lab.verifyLocalChallenge())
                    "PASS ✓  Real Android Keystore P-256 signature verified locally.\n" +
                    "Modified transcript rejected.\nNO phone enrolled on Supabase."
                else "FAILED: Local signature validation failed. No cloud changes made."
            }catch(_:Exception){output.text="Local signature test could not complete. No cloud changes made."}
        }
        root.addView(text("Test version 0.1.0 · Offline-only · Separate app ID",12f,
            false,Color.GRAY))
    }
}
