package lk.peliyagoda.marketpro.stagingowner

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
import android.text.InputType
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Two fully separated QA tasks: local Keystore proof + Supabase Owner login/MFA.
 * NO purchase, Staff invite, catalog or phone registration interface in APK.
 */
class MainActivity : Activity() {
    private val lab = OwnerKeystoreLab()
    private val auth = StagingOwnerAuth()
    private lateinit var output: TextView
    private lateinit var loginButton: Button
    private lateinit var verifyButton: Button
    private lateinit var passwordInput: EditText
    private lateinit var codeInput: EditText
    @Volatile private var busy = false
    private var awaitingMfa = false
    private val green = Color.rgb(13, 85, 58)

    private fun dp(n:Int)= (resources.displayMetrics.density*n).toInt()
    private fun panel(color:Int)=GradientDrawable().apply {
        setColor(color);cornerRadius=dp(16).toFloat()
    }
    private fun text(body:String,size:Float,bold:Boolean=false,color:Int=Color.BLACK)=
        TextView(this).apply {
            text=body;textSize=size;setTextColor(color)
            if(bold)setTypeface(null,Typeface.BOLD)
            setPadding(0,dp(6),0,dp(6))
        }
    private fun button(root:LinearLayout,title:String,action:()->Unit):Button {
        val b=Button(this).apply {
            text=title;isAllCaps=false;setTextColor(Color.WHITE)
            backgroundTintList=ColorStateList.valueOf(green)
            setOnClickListener { if(!busy)action() }
        }
        root.addView(b,LinearLayout.LayoutParams(-1,dp(54)).apply {
            topMargin=dp(10)
        })
        return b
    }
    private fun field(hintText:String,numeric:Boolean=false):EditText =
        EditText(this).apply {
            hint=hintText
            textSize=16f
            isSingleLine=true
            setPadding(dp(14),dp(7),dp(14),dp(7))
            background=panel(Color.WHITE)
            inputType=if(numeric)
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

    private fun work(task:()->String,success:()->Unit={}) {
        if(busy)return
        busy=true
        loginButton.isEnabled=false
        verifyButton.isEnabled=false
        output.text="Staging Auth server සමඟ ආරක්ෂිතව සම්බන්ධ වෙමින්..."
        Thread {
            val response=try { Pair(true,task()) }
            catch (e:StagingOwnerAuth.AuthException) {
                Pair(false,e.message ?: "Staging Auth failed.")
            }
            catch (_:Exception) {Pair(false,"Staging Auth test failed. No phone enrolled.")}
            runOnUiThread {
                busy=false
                if(isFinishing||isDestroyed)return@runOnUiThread
                output.text=response.second
                if(response.first)success()
                loginButton.isEnabled=true
                verifyButton.isEnabled=awaitingMfa
            }
        }.start()
    }

    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        // Secure screenshots during password/OTP entry; clear after successful
        // MFA so the user can share the GREEN status without revealing secrets.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val scroll=ScrollView(this).apply {isFillViewport=true}
        val root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(22),dp(25),dp(22),dp(40))
            setBackgroundColor(Color.rgb(247,250,248))
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(text("Market Pro",27f,true,green))
        root.addView(text("STAGING • Owner Security v0.2.0",17f,true,green))
        val note=text(
            "වෙනම ආරක්ෂක පරීක්ෂණ App එකක්.\n" +
            "Staging Auth Login පමණයි. No Purchase Sync • No Staff Access.\n" +
            "Production Market Pro එකට බලපෑමක් නැහැ.",
            14f,false,Color.rgb(54,75,64))
        note.background=panel(Color.rgb(225,241,231))
        note.setPadding(dp(15),dp(13),dp(15),dp(13))
        root.addView(note,LinearLayout.LayoutParams(-1,-2).apply {
            topMargin=dp(12);bottomMargin=dp(14)
        })

        root.addView(text("1 • Phone Security",20f,true,green))
        output=text("Ready. No Supabase session.",15f)
        output.background=panel(Color.WHITE)
        output.setPadding(dp(15),dp(13),dp(15),dp(13))
        root.addView(output,LinearLayout.LayoutParams(-1,-2).apply {
            topMargin=dp(10);bottomMargin=dp(6)
        })
        button(root,"Generate / Check Owner Key") {
            try {
                val summary=lab.summary()
                output.text="LOCAL KEY READY ✓\nFingerprint:\n"+
                    summary.fingerprintSha256.chunked(16).joinToString("\n")+
                    "\nHardware-backed: "+
                    (if(summary.hardwareBacked)"Yes" else "Not confirmed")
            }catch(_:Exception){
                output.text="Keystore unavailable. No cloud changes."
            }
        }
        button(root,"Sign & Verify Local Challenge") {
            try {
                output.text=if(lab.verifyLocalChallenge())
                    "PASS ✓ Local P-256 Signature Verified.\n" +
                    "Tampered message rejected.\nNo phone registered."
                else "FAILED • Local signing check."
            }catch(_:Exception){
                output.text="Local signing failed. No cloud changes."
            }
        }

        root.addView(text("2 • Staging Owner Login",20f,true,green).apply {
            setPadding(0,dp(26),0,dp(8))
        })
        root.addView(text("Owner: mywin7070@gmail.com\nSupabase: Two-Phone STAGING only",
            14f,false,Color.DKGRAY))
        passwordInput=field("Staging Owner Password")
        root.addView(passwordInput,LinearLayout.LayoutParams(-1,dp(52)).apply {
            topMargin=dp(10)
        })
        loginButton=button(root,"Login to Staging (Password)") {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            awaitingMfa=false
            val pwd=passwordInput.text.toString()
            passwordInput.text.clear()
            if(pwd.isBlank()) {
                output.text="Staging Owner Password ඇතුළත් කරන්න."
            }else{
                // Password is only held within this immediate request; no
                // saved state, SharedPreferences, database or background task.
                work({auth.signIn(pwd)}) {
                    awaitingMfa=true
                }
            }
        }
        codeInput=field("Authenticator Code • 6 Digits",numeric=true)
        root.addView(codeInput,LinearLayout.LayoutParams(-1,dp(52)).apply {
            topMargin=dp(12)
        })
        verifyButton=button(root,"Verify Authenticator — AAL2") {
            val code=codeInput.text.toString()
            codeInput.text.clear()
            work({auth.verify(code)}) {
                // No token, password or code remains stored by the app.
                // Unlock screenshots only for the status-only result.
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                awaitingMfa=false
            }
        }
        verifyButton.isEnabled=false

        root.addView(text("Test v0.2.0 • Separate App ID • No Cloud Data Writes",
            12f,false,Color.GRAY).apply {
            setPadding(0,dp(20),0,0)
        })
    }

    override fun onDestroy() {
        auth.clear()
        if(::passwordInput.isInitialized)passwordInput.text.clear()
        if(::codeInput.isInitialized)codeInput.text.clear()
        super.onDestroy()
    }
}
