#!/usr/bin/env python3
"""Market Pro v0.3 Owner AAL2 Edge status APK: staging-only security contract."""
from pathlib import Path
import re
import unittest

ROOT=Path(__file__).resolve().parents[1]
APP=ROOT/"stagingOwner"
AUTH=APP/"src/main/java/lk/peliyagoda/marketpro/stagingowner/StagingOwnerAuth.kt"
UI=APP/"src/main/java/lk/peliyagoda/marketpro/stagingowner/MainActivity.kt"
CORE=ROOT/"supabase/functions/_shared/owner_bootstrap_edge_core.mjs"

class SafeOwnerEdgeProbe(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.a=AUTH.read_text()
        cls.ui=UI.read_text()
        cls.core=CORE.read_text()
        cls.manifest=(APP/"src/main/AndroidManifest.xml").read_text()
        cls.build=(APP/"build.gradle.kts").read_text()

    def test_separate_identity_and_no_prod_fallback(self):
        self.assertIn('applicationId = "lk.peliyagoda.marketpro.staging.edgeprobe"',self.build)
        self.assertIn('versionName = "0.3.0"',self.build)
        self.assertIn('ORIGIN = "https://qqiheexpbuzohllugxrk.supabase.co"',self.a)
        self.assertNotIn("lkoptqhodgjeuwwfihfr",self.a)
        self.assertNotIn("lkoptqhodgjeuwwfihfr",self.core)
        self.assertIn('android:allowBackup="false"',self.manifest)
        self.assertIn('android:usesCleartextTraffic="false"',self.manifest)

    def test_read_only_exact_endpoint_no_business_api(self):
        self.assertIn('"/functions/v1/marketpro-qa-owner-bootstrap"',self.a)
        self.assertIn('.put("intent","status")',self.a)
        self.assertIn('intent==="status"',self.core)
        self.assertIn('hasOnlyKeys(payload,["intent","workspace_id"])',self.core)
        self.assertIn('caller.owner==="98c3b101-d452-4b6b-902c-0b1a31fc340c"',self.core)
        self.assertIn('db_calls_made:false',self.core)
        self.assertIn('device_registration_performed:false',self.core)
        self.assertNotIn('"intent","issue"',self.a)
        self.assertNotIn('"intent","verify"',self.a)
        self.assertNotIn('registered_devices',self.a)
        self.assertNotIn('/rest/v1/',self.a)
        self.assertNotIn('marketpro_qa_service_owner_bootstrap_commit',self.a)
        self.assertNotIn('service_role',self.a.lower())
        self.assertNotIn("sb_secret_",self.a)
        self.assertIn("sb_publishable_",self.a)

    def test_ephemeral_token_only_and_strict_response(self):
        self.assertIn("private var aal2TestToken: String? = null",self.a)
        self.assertIn("age > 120_000",self.a)
        self.assertIn("clear()\n            conn.disconnect()",self.a)
        self.assertIn('status != 200',self.a)
        for phrase in ("db_calls_made","device_registration_performed",
                        "staff_access_granted","purchase_sync_started"):
            self.assertIn(phrase,self.a)
            self.assertIn(phrase,self.core)
        self.assertIn("FLAG_SECURE",self.ui)
        self.assertIn("passwordInput.text.clear()",self.ui)
        self.assertIn("codeInput.text.clear()",self.ui)
        self.assertIn("Check Secure Server (Read Only)",self.ui)

    def test_existing_mfa_and_key_requirements_remain(self):
        self.assertIn('claims.optString("aal") != "aal2"',self.a)
        self.assertIn('/auth/v1/factors/$factorId/challenge',self.a)
        self.assertIn('/auth/v1/factors/$factorId/verify',self.a)
        self.assertIn('/auth/v1/user',self.a)
        self.assertIn('Signature.getInstance("SHA256withECDSA")',
            (APP/"src/main/java/lk/peliyagoda/marketpro/stagingowner/OwnerKeystoreLab.kt").read_text())
        self.assertIn("No Staff Access",self.a)

if __name__=="__main__":
    unittest.main()
