#!/usr/bin/env python3
"""Market Pro Owner signature clock-skew fix; staging-only safety checks."""
from pathlib import Path
import unittest
R=Path(__file__).resolve().parents[1]
S=R/"stagingOwner"
C=S/"src/main/java/lk/peliyagoda/marketpro/stagingowner"

class SecureMonotonicChallenge(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.clock=(C/"ServerChallengeClock.kt").read_text()
        cls.lab=(C/"OwnerKeystoreLab.kt").read_text()
        cls.client=(C/"StagingOwnerAuth.kt").read_text()
        cls.ui=(C/"MainActivity.kt").read_text()
        cls.transcript=(C/"OwnerProofTranscript.kt").read_text()
        cls.gradle=(S/"build.gradle.kts").read_text()
        cls.manifest=(S/"src/main/AndroidManifest.xml").read_text()

    def test_independent_package_and_no_prod_access(self):
        self.assertIn('applicationId = "lk.peliyagoda.marketpro.staging.remoteproofclock"',self.gradle)
        self.assertIn('versionName = "0.4.3"',self.gradle)
        self.assertIn('android:allowBackup="false"',self.manifest)
        self.assertIn('android:usesCleartextTraffic="false"',self.manifest)
        self.assertNotIn('lkoptqhodgjeuwwfihfr',self.client)
        self.assertNotIn('sb_secret_',self.client)
        self.assertNotIn('service_role',self.client.lower())
        self.assertNotIn('registered_devices',self.client)
        self.assertIn('Verify Phone Signature — Clock Fix',self.ui)

    def test_server_time_anchor_with_monotonic_elapsed(self):
        self.assertIn("ServerChallengeClock.signingTimeMillis",self.lab)
        self.assertIn("SystemClock.elapsedRealtime()",self.lab)
        self.assertIn("SystemClock.elapsedRealtime()",self.client)
        self.assertIn("val issueRequestStartElapsedMs = SystemClock.elapsedRealtime()",self.client)
        self.assertIn("lab.signStagingServerChallenge(challenge, issueRequestStartElapsedMs)",self.client)
        self.assertIn("material.bytes(serverAnchoredNow)",self.lab)
        self.assertNotIn("material.bytes(System.currentTimeMillis())",self.lab)
        self.assertIn("currentElapsedMillis - startElapsedMillis",self.clock)
        self.assertIn("elapsed < ttl",self.clock)

    def test_strict_security_preserved(self):
        self.assertIn("ttl in 1L..60_000L",self.clock)
        self.assertIn("expiresAtMillis - issuedAtMillis <= 60_000L",self.transcript)
        self.assertIn("nowMillis >= issuedAtMillis && nowMillis < expiresAtMillis",self.transcript)
        self.assertIn('Signature.getInstance("SHA256withECDSA")',self.lab)
        self.assertIn('outcome.optBoolean("device_registered", true) != false',self.client)
        self.assertIn('outcome.optBoolean("staff_access", true) != false',self.client)
        self.assertIn('clear() // Ephemeral AAL2 bearer ALWAYS discarded.',self.client)
        self.assertIn('ORIGIN = "https://qqiheexpbuzohllugxrk.supabase.co"',self.client)

if __name__=="__main__":
    unittest.main()
