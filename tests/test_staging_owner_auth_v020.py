#!/usr/bin/env python3
"""Standalone Owner Auth APK safety contract. No network access, no credentials."""
from pathlib import Path
import re
import unittest

ROOT=Path(__file__).resolve().parents[1]
STAGE=ROOT/"stagingOwner"
PROD="lkoptqhodgjeuwwfihfr"
PROD_OWNER="98c3b101-d452-4b6b-902c-0b1a31fc340c"
PROD_WORKSPACE="af77cb66-76b2-492a-a460-06132f468713"

class StagingOwnerAuthSafety(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.gradle=(STAGE/"build.gradle.kts").read_text()
        cls.manifest=(STAGE/"src/main/AndroidManifest.xml").read_text()
        cls.sources={p.name:p.read_text() for p in (STAGE/"src/main/java/lk/peliyagoda/marketpro/stagingowner").glob("*.kt")}
        cls.auth=cls.sources["StagingOwnerAuth.kt"]
        cls.ui=cls.sources["MainActivity.kt"]

    def test_separate_package_and_version(self):
        self.assertIn('applicationId = "lk.peliyagoda.marketpro.staging.auth"',self.gradle)
        self.assertIn('versionName = "0.2.0"',self.gradle)
        self.assertNotIn("lk.peliyagoda.marketpro.security.foundation.v350",self.gradle)

    def test_network_bounded_https_only_to_staging_auth(self):
        self.assertIn('ORIGIN = "https://qqiheexpbuzohllugxrk.supabase.co"',self.auth)
        self.assertIn('url.host != "qqiheexpbuzohllugxrk.supabase.co"',self.auth)
        self.assertIn('url.protocol != "https"',self.auth)
        self.assertIn("instanceFollowRedirects = false",self.auth)
        self.assertIn('path == "/auth/v1/token?grant_type=password"',self.auth)
        self.assertIn('path == "/auth/v1/user"',self.auth)
        self.assertIn('path == "/auth/v1/factors"',self.auth)
        self.assertIn("MAX_RESPONSE = 64 * 1024",self.auth)
        self.assertNotIn(PROD,self.auth)
        self.assertIn('android.permission.INTERNET',self.manifest)
        self.assertNotIn('uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"',self.manifest)
        self.assertIn('android:usesCleartextTraffic="false"',self.manifest)

    def test_credentials_and_tokens_not_stored(self):
        self.assertIn("sb_publishable_",self.auth)
        self.assertNotIn("sb_secret_",self.auth)
        self.assertNotIn("service_role",self.auth.lower())
        for p,s in self.sources.items():
            self.assertNotRegex(s,r"(?i)sharedpreferences|android\.util\.log\.|fileoutputstream|sqlite|roomdatabase")
        self.assertIn("fun clear()",self.auth)
        self.assertIn("aal1 = null",self.auth)
        self.assertIn("factor = null",self.auth)
        self.assertIn("passwordInput.text.clear()",self.ui)
        self.assertIn("codeInput.text.clear()",self.ui)
        self.assertIn("FLAG_SECURE",self.ui)
        self.assertIn('android:allowBackup="false"',self.manifest)

    def test_mfa_requires_server_challenge_and_live_aal2_token(self):
        for snippet in (
            "/auth/v1/factors/$factorId/challenge",
            "/auth/v1/factors/$factorId/verify",
            'claims.optString("aal") != "aal2"',
            'claims.optString("sub") != OWNER_ID',
            'val confirmedUser = http("/auth/v1/user", "GET", null, upgraded)',
            'status == "verified"',
        ):
            self.assertIn(snippet,self.auth)
        self.assertNotIn("marketpro_qa_owner_issue_bootstrap",self.auth)
        self.assertNotIn("marketpro_qa_service_owner_bootstrap_commit",self.auth)

    def test_no_business_write_client(self):
        for p,s in self.sources.items():
            for forbidden in ("/rest/v1/","/functions/v1/","/storage/v1/","/realtime/v1/",
                              "purchase_additions","registered_devices","service_role"):
                self.assertNotIn(forbidden,s,p)
        self.assertNotIn(PROD_WORKSPACE,"\n".join(self.sources.values()))
        self.assertIn(PROD_OWNER,self.auth) # This UUID is Owner in Staging only, not Production.

if __name__=="__main__":
    unittest.main()
