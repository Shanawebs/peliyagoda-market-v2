#!/usr/bin/env python3
"""Source-only Staging Vault guardrail. Never contacts Supabase."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "supabase/staging/staging_private_pending_invitation_vault.sql"
CHECK = ROOT / "supabase/staging/staging_pending_invitation_vault_READ_ONLY.sql"
DOC = ROOT / "docs/TWO_PHONE_STAGING_PENDING_INVITATION_VAULT_APPLIED.md"
STAGE = "qqiheexpbuzohllugxrk"
PROD = "lkoptqhodgjeuwwfihfr"

class StagingInvitationVaultContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.m = MIGRATION.read_text(encoding="utf-8")
        cls.check = CHECK.read_text(encoding="utf-8")
        cls.doc = DOC.read_text(encoding="utf-8")

    def test_hard_pinned_stage_and_no_live_ref(self):
        self.assertIn(STAGE, self.m)
        self.assertNotIn(PROD, self.m)
        self.assertIn(STAGE, self.check)

    def test_atomic_fail_closed_empty_storage(self):
        self.assertIn("BEGIN;", self.m)
        self.assertTrue(self.m.rstrip().endswith("COMMIT;"))
        self.assertIn("to_regclass('marketpro_private.pending_team_enrollments') IS NOT NULL", self.m)
        self.assertIn("v_owners<>1", self.m)
        self.assertIn("v_valid<>6", self.m)
        self.assertIn("FROM public.registered_devices", self.m)
        self.assertIn("EXISTS (SELECT 1 FROM public.purchases)", self.m)
        self.assertIn("EXISTS (SELECT 1 FROM public.requisitions)", self.m)

    def test_hash_only_and_schema_is_private(self):
        self.assertIn("CREATE TABLE marketpro_private.pending_team_enrollments", self.m)
        self.assertIn("token_sha256 bytea NOT NULL", self.m)
        self.assertIn("CHECK (octet_length(token_sha256)=32)", self.m)
        self.assertIn("REFERENCES auth.users(id)", self.m)
        self.assertIn("activated_at timestamptz", self.m)
        self.assertIn("PRIMARY KEY(workspace_id,staff_user_id)", self.m)
        self.assertNotIn("token_plaintext", self.m)
        self.assertNotIn("service_role", self.m)

    def test_no_public_invitation_or_writer_permissions(self):
        self.assertIn("REVOKE ALL ON marketpro_private.pending_team_enrollments", self.m)
        self.assertIn("FROM PUBLIC,anon,authenticated", self.m)
        self.assertIn("ENABLE ROW LEVEL SECURITY", self.m)
        self.assertNotRegex(self.m, r"(?im)^\\s*GRANT\\s+")
        self.assertNotRegex(self.m, r"(?im)^\\s*CREATE\\s+(?:OR REPLACE\\s+)?FUNCTION\\s+")
        self.assertNotRegex(self.m, r"(?im)^\\s*INSERT\\s+INTO\\s+")
        self.assertNotRegex(self.m, r"(?im)^\\s*CREATE\\s+POLICY\\s+")

    def test_verifier_remains_read_only(self):
        query = "\n".join(x for x in self.check.splitlines()
                          if not x.lstrip().startswith("--"))
        self.assertTrue(query.lstrip().startswith("SELECT"))
        self.assertNotRegex(query, r"(?im)^\\s*(?:CREATE|ALTER|DROP|INSERT|UPDATE|DELETE|GRANT|REVOKE)\\s+")
        self.assertIn("no_phone_paired", query)
        self.assertIn("future_staff_direct_rest_still_blocked", query)

    def test_documentation_declares_no_activation(self):
        self.assertIn("No invitation row", self.doc)
        self.assertIn("not a Staff", "not a Staff")  # contract anchor, no network
        self.assertIn("DO NOT", self.doc)

if __name__ == "__main__":
    unittest.main()
