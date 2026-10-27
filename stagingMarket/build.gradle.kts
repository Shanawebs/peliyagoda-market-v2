plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
}

// Single source of truth for user-visible version labels.
// Next release: update this number only; both header and launcher follow it.
val marketProDisplayVersion = "4.0.3-staging"

android {
    namespace = "lk.peliyagoda.marketpro"
    compileSdk = 35

    defaultConfig {
        // Only PUBLIC Supabase identifiers; never put service-role credentials in an APK.
        // Integrated Staging may test Owner Auth; all Staff/financial writer gates below remain OFF.
        buildConfigField("boolean", "MARKETPRO_LOCAL_PREVIEW", "false")
        buildConfigField("boolean", "MARKETPRO_REQUIRE_CLOUD_ROLE", "true")
        // Remains FALSE until server v2, staff enrollment and two-phone QA are approved.
        buildConfigField("boolean", "MARKETPRO_TEAM_PULL_RPC_DEPLOYED", "true")
        // Explicitly blocked until verified, complete staged pull and
        // all catalog/financial UUID reconciliation are signed off.
        buildConfigField("boolean", "MARKETPRO_TEAM_DOMAIN_MERGE_DEPLOYED", "false")
        // Preview fields are disabled; no Owner/Staff password collection or network login.
        buildConfigField("boolean", "MARKETPRO_STAFF_LOGIN_ENABLED", "true")
        buildConfigField("boolean", "MARKETPRO_PHONE_REGISTRY_RPC_DEPLOYED", "true")
        buildConfigField("boolean", "MARKETPRO_CLOUD_RECEIVE_AUTO_ENABLED", "true")
        buildConfigField("boolean", "MARKETPRO_STAFF_KEY_ENROLLMENT_DEPLOYED", "false")
        buildConfigField("boolean", "MARKETPRO_OWNER_TOTP_MFA_AUTH_DEPLOYED", "true")
        buildConfigField("boolean", "MARKETPRO_STAFF_FINANCE_RPC_DEPLOYED", "false")
        buildConfigField("boolean", "MARKETPRO_STAFF_DEVICE_PROOF_WRITE_ENFORCED", "false")
        buildConfigField("boolean", "MARKETPRO_STAFF_OUTBOX_AUTO_UPLOAD", "false")
        // V3.6.5 candidate upload/Owner review RPC is still REVIEW_ONLY SQL.
        buildConfigField("boolean", "MARKETPRO_CATALOG_REVIEW_RPC_DEPLOYED", "false")
        // 3.6.8 replace/revoke APIs are still proposal SQL; never enable in preview.
        buildConfigField("boolean", "MARKETPRO_TEAM_PHONE_TRANSFER_RPC_DEPLOYED", "false")
        // V3.7.3 Dormant Auth-session pairing. No staff OTP or password input in preview.
        buildConfigField("boolean", "MARKETPRO_STAFF_PAIRING_RPC_DEPLOYED", "false")
        // Dedicated Owner confirmation + server audit not activated.
        buildConfigField("boolean", "MARKETPRO_STAFF_WRITE_APPROVAL_RPC_DEPLOYED", "false")
        // Explicit, build-specific destinations. Existing preview keeps its
        // original Production values but has NO INTERNET and all writer flags OFF.
        buildConfigField("String", "MARKETPRO_SUPABASE_HOST", "\"qqiheexpbuzohllugxrk.supabase.co\"")
        buildConfigField("String", "MARKETPRO_WORKSPACE_ID", "\"af77cb66-76b2-492a-a460-06132f468713\"")
        buildConfigField("String", "MARKETPRO_OWNER_USER_ID", "\"98c3b101-d452-4b6b-902c-0b1a31fc340c\"")
        buildConfigField("String", "MARKETPRO_SUPABASE_URL", "\"https://qqiheexpbuzohllugxrk.supabase.co\"")
        buildConfigField("String", "MARKETPRO_SUPABASE_PUBLISHABLE_KEY", "\"sb_publishable_1c7T0dHILKzKeY-xb2e-lA_uRWLF3oF\"")
        applicationId = "lk.peliyagoda.marketpro.staging.fullapp"
        minSdk = 26
        targetSdk = 35
        versionCode = 403
        versionName = marketProDisplayVersion
        // Generated from the SAME version as the APK metadata. Never hardcode
        // versions separately in AndroidManifest or a Compose text component.
        resValue("string", "marketpro_version_caption", "• v$marketProDisplayVersion")
        resValue("string", "marketpro_launcher_name", "Market Pro Staging ✦ v$marketProDisplayVersion")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("../app/src/main/java")
            res.srcDirs("../app/src/main/res")
        }
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
