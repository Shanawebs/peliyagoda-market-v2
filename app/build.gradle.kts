plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
}

// Single source of truth for user-visible version labels.
// Next release: update this number only; both header and launcher follow it.
val marketProDisplayVersion = "3.5.0"

android {
    namespace = "lk.peliyagoda.marketpro"
    compileSdk = 35

    defaultConfig {
        // Only PUBLIC Supabase identifiers; never put service-role credentials in an APK.
        buildConfigField("boolean", "MARKETPRO_LOCAL_PREVIEW", "true")
        // Remains FALSE until server v2, staff enrollment and two-phone QA are approved.
        buildConfigField("boolean", "MARKETPRO_TEAM_PULL_RPC_DEPLOYED", "false")
        // Explicitly blocked until verified, complete staged pull and
        // all catalog/financial UUID reconciliation are signed off.
        buildConfigField("boolean", "MARKETPRO_TEAM_DOMAIN_MERGE_DEPLOYED", "false")
        // Preview fields are disabled; no Owner/Staff password collection or network login.
        buildConfigField("boolean", "MARKETPRO_STAFF_LOGIN_ENABLED", "false")
        // V3.6.5 candidate upload/Owner review RPC is still REVIEW_ONLY SQL.
        buildConfigField("boolean", "MARKETPRO_CATALOG_REVIEW_RPC_DEPLOYED", "false")
        // 3.6.8 replace/revoke APIs are still proposal SQL; never enable in preview.
        buildConfigField("boolean", "MARKETPRO_TEAM_PHONE_TRANSFER_RPC_DEPLOYED", "false")
        // V3.7.3 Dormant Auth-session pairing. No staff OTP or password input in preview.
        buildConfigField("boolean", "MARKETPRO_STAFF_PAIRING_RPC_DEPLOYED", "false")
        // Dedicated Owner confirmation + server audit not activated.
        buildConfigField("boolean", "MARKETPRO_STAFF_WRITE_APPROVAL_RPC_DEPLOYED", "false")
        buildConfigField("String", "MARKETPRO_SUPABASE_URL", "\"https://lkoptqhodgjeuwwfihfr.supabase.co\"")
        buildConfigField("String", "MARKETPRO_SUPABASE_PUBLISHABLE_KEY", "\"sb_publishable__7F9sfydgBtHiZNPV9kNzg_hGfazjxg\"")
        applicationId = "lk.peliyagoda.marketpro.security.foundation.v350"
        minSdk = 26
        targetSdk = 35
        versionCode = 350
        versionName = marketProDisplayVersion
        // Generated from the SAME version as the APK metadata. Never hardcode
        // versions separately in AndroidManifest or a Compose text component.
        resValue("string", "marketpro_version_caption", "• v$marketProDisplayVersion")
        resValue("string", "marketpro_launcher_name", "Market Pro Security Preview ✦ v$marketProDisplayVersion")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

kapt { correctErrorTypes = true }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.09.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    kapt("androidx.room:room-compiler:2.7.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
