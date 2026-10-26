#!/usr/bin/env python3
"""Offline contract checks for QA Owner bootstrap storage. No Supabase contact."""
import re
import unittest
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
SQL=ROOT/"supabase/staging/staging_qa_owner_key_bootstrap_storage.sql"
PROOF=ROOT/"supabase/functions/_shared/owner_bootstrap_key_proof.mjs"
CRYPTO=ROOT/"tests/staging_owner_bootstrap_key_crypto.test.mjs"

class InertOwnerBootstrapVault(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.s=SQL.read_text(encoding="utf-8")
        cls.m=PROOF.read_text(encoding="utf-8")
        cls.c=CRYPTO.read_text(encoding="utf-8")

    def test_pinned_to_separate_qa_stage(self):
        self.assertIn("qqiheexpbuzohllugxrk",self.s)
        self.assertNotIn("lkoptqhodgjeuwwfihfr",self.s)
        self.assertIn("af77cb66-76b2-492a-a460-06132f468713",self.s)

    def test_no_grants_to_clients_no_functions_or_registration(self):
        body="\n".join(x for x in self.s.splitlines()
                        if not x.lstrip().startswith("--"))
        self.assertNotRegex(body,r"(?im)^\s*GRANT\s+")
        self.assertNotRegex(body,r"(?im)^\s*CREATE\s+(?:OR REPLACE\s+)?FUNCTION")
        self.assertNotRegex(body,r"(?im)^\s*INSERT\s+INTO\s+public\.registered_devices\b")
        self.assertNotRegex(body,r"(?im)^\s*CREATE\s+POLICY\b")
        self.assertIn("CHECK(activated=false)",self.s)
        self.assertIn("CHECK(attestation_verified=false)",self.s)

    def test_private_tables_and_rls(self):
        for table in ("qa_owner_bootstrap_gate",
                      "qa_owner_bootstrap_challenges",
                      "qa_owner_device_public_keys"):
            self.assertIn(f"CREATE TABLE marketpro_private.{table}",self.s)
            self.assertIn(f"REVOKE ALL ON marketpro_private.{table}",self.s)
            self.assertIn(
                f"ALTER TABLE marketpro_private.{table} ENABLE ROW LEVEL SECURITY",self.s)
        self.assertIn("enabled boolean NOT NULL DEFAULT false",self.s)
        self.assertIn("token_sha256",  # existing invitation vault stays intact
                      (ROOT/"supabase/staging/staging_private_pending_invitation_vault.sql").read_text())

    def test_no_plain_private_key_and_one_minute_nonce(self):
        self.assertIn("spki_der bytea",self.s)
        self.assertIn("nonce_hex text NOT NULL",self.s)
        self.assertIn("expires_at<=issued_at+interval '60 seconds'",self.s)
        self.assertIn("signature_verified_at",self.s)
        self.assertNotRegex(self.s,r"(?im)^\s*private_key\b")

    def test_verified_transcript_has_dedicated_domain(self):
        self.assertIn('marketpro-owner-bootstrap-key-v1',self.m)
        self.assertIn('OWNER_BOOTSTRAP_TTL_MS=60_000',self.m)
        self.assertIn('fingerprint_sha256',self.m)
        self.assertIn('crypto.subtle.verify',self.m)
        self.assertIn('wrongSpki',self.c)
        self.assertIn('q.expires_at_millis',self.c)

if __name__=="__main__":
    unittest.main()
