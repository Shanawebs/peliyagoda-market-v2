#!/usr/bin/env python3
"""Fail-closed QA contract for the user-safe v0.4.1 remote proof diagnostic."""
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[1]
APP=ROOT/"stagingOwner"
SRC=APP/"src/main/java/lk/peliyagoda/marketpro/stagingowner"

class RemoteProofDiagnostic(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.auth=(SRC/"StagingOwnerAuth.kt").read_text(encoding="utf-8")
        cls.key=(SRC/"OwnerKeystoreLab.kt").read_text(encoding="utf-8")
        cls.ui=(SRC/"MainActivity.kt").read_text(encoding="utf-8")
        cls.gradle=(APP/"build.gradle.kts").read_text(encoding="utf-8")
        cls.manifest=(APP/"src/main/AndroidManifest.xml").read_text(encoding="utf-8")

    def test_production_app_is_not_replaced(self):
        self.assertIn('applicationId = "lk.peliyagoda.marketpro.staging.remoteproofdiag"',self.gradle)
        self.assertIn('versionName = "0.4.1"',self.gradle)
        self.assertNotIn("lkoptqhodgjeuwwfihfr",self.auth)
        self.assertIn('android:allowBackup="false"',self.manifest)
        self.assertIn('android:usesCleartextTraffic="false"',self.manifest)

    def test_error_stages_precise_and_non_sensitive(self):
        for stage in ("P1_SESSION","P2_PUBLIC_KEY","P3_ISSUE_SERVER",
                      "P4_CHECK_CHALLENGE","P5_PHONE_SIGN",
                      "P6_VERIFY_SERVER","P7_CHECK_RECEIPT"):
            self.assertIn('stage = "'+stage+'"',self.auth)
        self.assertIn('throw AuthException("FAIL [" + stage + "]',self.auth)
        self.assertIn('clear() // Ephemeral AAL2 bearer ALWAYS discarded.',self.auth)
        self.assertNotIn("printStackTrace",self.auth)
        self.assertNotIn("Log.",self.auth)
        self.assertNotIn("e.toString()",self.auth)
        self.assertNotIn("sb_secret_",self.auth)

    def test_validates_challenge_identity_and_server_receipt(self):
        for term in (
            'issued.optBoolean("phone_activated", true) != false',
            'challenge.optString("session_id") != claims.optString("session_id")',
            'challenge.optString("device_id") != deviceId',
            'challenge.optString("proof_version") != OwnerProofTranscript.VERSION',
            'lab.signStagingServerChallenge(challenge)',
            '.put("intent", "proof_issue")',
            '.put("intent", "proof_verify")',
            'outcome.optBoolean("device_registered", true) != false',
            'outcome.optBoolean("staff_access", true) != false',
            'outcome.optBoolean("financial_writes", true) != false',
        ):
            self.assertIn(term,self.auth)

    def test_real_non_exportable_signer_preserved(self):
        self.assertIn("AndroidKeyStore",self.key)
        self.assertIn("SHA256withECDSA",self.key)
        self.assertIn("KeyProperties.PURPOSE_SIGN",self.key)
        self.assertIn('No phone registered',self.auth)
        self.assertIn("Diagnose Phone Signature With Server",self.ui)
        self.assertIn("FLAG_SECURE",self.ui)

if __name__=="__main__":
    unittest.main()
