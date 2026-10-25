"""Security regression tests for Market Pro hosted STAGING isolation preflight.

Run: python3 -m unittest discover -s tests -p 'test_staging_isolation.py'
No credentials, HTTP, Android emulator, production Supabase or DB writes.
"""
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/"scripts"))
from verify_staging_isolation import source_baseline,validate_manifest,audit

class StagingIsolationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.live=source_baseline(ROOT)

    def proposed(self):
        return dict(
            project_ref="z"*20,
            supabase_url="https://"+"z"*20+".supabase.co",
            owner_user_id="33333333-3333-4333-8333-333333333333",
            workspace_id="11111111-1111-4111-8111-111111111111",
            application_id="lk.peliyagoda.marketpro.staging.isolated"
        )

    def denied(self,case):
        with self.assertRaises(ValueError):
            validate_manifest(case,self.live)

    def test_valid_distinct_synthetic_proposal_can_be_parsed_without_secrets(self):
        self.assertEqual(validate_manifest(self.proposed(),self.live),self.proposed())
        n=self.proposed()
        n["project_ref"]="abcde0123456789fghij"
        n["supabase_url"]="https://"+n["project_ref"]+".supabase.co"
        self.assertEqual(validate_manifest(n,self.live),n)

    def test_rejects_any_production_identity_reuse(self):
        for key,value in (
            ("project_ref",self.live["prod_ref"]),
            ("owner_user_id",self.live["prod_owner"]),
            ("workspace_id",self.live["prod_workspace"]),
            ("application_id",self.live["production_app_id"])
        ):
            c=self.proposed()
            c[key]=value
            if key=="project_ref":
                c["supabase_url"]="https://"+value+".supabase.co"
            self.denied(c)

    def test_rejects_wrong_origin_or_hidden_url_parts(self):
        for uri in (
            "http://"+"z"*20+".supabase.co",
            "https://"+self.live["prod_ref"]+".supabase.co",
            "https://"+"z"*20+".supabase.co.evil.com",
            "https://"+"z"*20+".supabase.co@evil.com",
            "https://user@"+"z"*20+".supabase.co",
            "https://"+"z"*20+".supabase.co:444",
            "https://"+"z"*20+".supabase.co/auth/v1",
            "https://"+"z"*20+".supabase.co/?test=1",
            "https://"+"z"*20+".supabase.co/#x"
        ):
            c=self.proposed()
            c["supabase_url"]=uri
            self.denied(c)

    def test_rejects_missing_or_malformed_workspace_and_secrets(self):
        for key,value in (
            ("owner_user_id","not-a-uuid"),
            ("owner_user_id","A"*36),
            ("workspace_id",self.proposed()["owner_user_id"]),
            ("project_ref","../../prod"),
            ("project_ref","abcde"),("application_id","lk.peliyagoda.marketpro..."),
            ("application_id","com.example.sameowner")
        ):
            c=self.proposed()
            c[key]=value
            self.denied(c)
        c=self.proposed()
        c["service_role_key"]="must-not-store-any-keys"
        self.denied(c)

    def test_current_production_bound_android_is_explicitly_blocked(self):
        report=audit(ROOT,self.proposed())
        self.assertFalse(report["staging_safe_to_deploy"])
        self.assertGreaterEqual(report["blocked_reason_count"],5)
        self.assertTrue(any("production-host-ref" in x for x in report["blockers"]))
        self.assertTrue(any("production-owner-id" in x for x in report["blockers"]))
        self.assertTrue(any("production-workspace-id" in x for x in report["blockers"]))
        self.assertTrue(any("staging applicationId" in x for x in report["blockers"]))

    def test_audit_outputs_blockers_but_enforce_fails_closed(self):
        with tempfile.TemporaryDirectory() as d:
            manifest=Path(d)/"manifest.json"
            manifest.write_text(json.dumps(self.proposed()))
            cli=[sys.executable,str(ROOT/"scripts/verify_staging_isolation.py"),
                str(manifest)]
            a=subprocess.run(cli+["--mode","audit"],capture_output=True,text=True)
            self.assertEqual(a.returncode,0,a.stderr)
            self.assertFalse(json.loads(a.stdout)["staging_safe_to_deploy"])
            b=subprocess.run(cli+["--mode","enforce"],capture_output=True,text=True)
            self.assertEqual(b.returncode,2,b.stderr)
            self.assertFalse(json.loads(b.stdout)["staging_safe_to_deploy"])

if __name__=="__main__":
    unittest.main()
