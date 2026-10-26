#!/usr/bin/env python3
"""Safe v0.4.2 diagnostic staging contract; no external API calls."""
from pathlib import Path
import unittest
R=Path(__file__).resolve().parents[1]
APP=R/"stagingOwner"
SRC=APP/"src/main/java/lk/peliyagoda/marketpro/stagingowner"
class OwnerP5Diagnostics(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  cls.lab=(SRC/"OwnerKeystoreLab.kt").read_text(encoding="utf-8")
  cls.auth=(SRC/"StagingOwnerAuth.kt").read_text(encoding="utf-8")
  cls.ui=(SRC/"MainActivity.kt").read_text(encoding="utf-8")
  cls.build=(APP/"build.gradle.kts").read_text()
  cls.manifest=(APP/"src/main/AndroidManifest.xml").read_text()
 def test_production_unchanged_and_new_package(self):
  self.assertIn('applicationId = "lk.peliyagoda.marketpro.staging.remoteproofdiag2"',self.build)
  self.assertIn('versionName = "0.4.2"',self.build)
  self.assertNotIn("lkoptqhodgjeuwwfihfr",self.auth)
  self.assertIn('android:allowBackup="false"',self.manifest)
  self.assertIn('android:usesCleartextTraffic="false"',self.manifest)
 def test_safe_deterministic_p5_errors(self):
  for code in (
   "P5_VERSION", "P5_KEY_FINGERPRINT","P5_UNEXPECTED_DEVICE_STATE",
   "P5_UUID_FORMAT","P5_HEX_FORMAT","P5_INVALID_TIME_WINDOW",
   "P5_EXCESSIVE_TTL","P5_PHONE_CLOCK_BEHIND",
   "P5_PHONE_CLOCK_AHEAD_OR_EXPIRED","P5_INVALID_TRANSCRIPT"
  ):
   self.assertIn('"'+code+'"',self.lab)
  self.assertIn("class ProofValidation(val safeCode: String)",self.lab)
  self.assertIn("OwnerKeystoreLab.ProofValidation",self.auth)
  self.assertIn("e.safeCode",self.auth)
  self.assertNotIn("e.printStackTrace",self.auth)
  self.assertNotIn("android.util.Log",self.auth)
  self.assertNotIn("service_role",self.auth.lower())
  self.assertNotIn("sb_secret_",self.auth)
 def test_freshness_not_weakened(self):
  transcript=(SRC/"OwnerProofTranscript.kt").read_text()
  self.assertIn("nowMillis >= issuedAtMillis && nowMillis < expiresAtMillis",transcript)
  self.assertIn("expiresAtMillis - issuedAtMillis <= 60_000L",transcript)
  self.assertIn("material.bytes(now)",self.lab)
  self.assertIn("KeyProperties.PURPOSE_SIGN",self.lab)
  self.assertIn('Signature.getInstance("SHA256withECDSA")',self.lab)
  self.assertIn('clear() // Ephemeral AAL2 bearer ALWAYS discarded.',self.auth)
  self.assertIn("P5_PHONE_SIGN",self.auth)
 def test_no_phone_registration_or_staff_data(self):
  self.assertIn("Diagnose P5 Signature With Server",self.ui)
  self.assertNotIn('"/rest/v1/"',self.auth)
  self.assertNotIn('"/storage/v1/"',self.auth)
  self.assertNotIn("registered_devices",self.auth)
  self.assertIn('outcome.optBoolean("device_registered", true) != false',self.auth)
  self.assertIn('outcome.optBoolean("staff_access", true) != false',self.auth)
if __name__=="__main__":unittest.main()
