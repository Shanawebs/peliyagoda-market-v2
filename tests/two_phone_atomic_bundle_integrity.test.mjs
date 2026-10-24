import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";

const sources=[
  "v352_team_write_gate_REVIEW_ONLY.sql",
  "v353_team_purchase_rpc_REVIEW_ONLY.sql",
  "v354_team_financial_rpc_REVIEW_ONLY.sql",
  "v354_team_paged_pull_REVIEW_ONLY.sql",
  "v358_owner_invite_staff_PENDING_ONLY.sql",
  "v359_staff_readonly_activation_rls_REVIEW_ONLY.sql",
  "v362_owner_approved_catalog_links_REVIEW_ONLY.sql",
  "v364_staff_candidate_owner_review_REVIEW_ONLY.sql",
  "v367_staff_approved_catalog_fetch_REVIEW_ONLY.sql",
  "v368_owner_replace_team_phone_REVIEW_ONLY.sql",
  "v370_dual_channel_staff_writer_REVIEW_ONLY.sql",
  "v371_owner_staff_write_approval_REVIEW_ONLY.sql",
  "v372_staff_auth_session_binding_REVIEW_ONLY.sql",
  "v375_staff_device_challenge_ledger_REVIEW_ONLY.sql",
  "two_phone_other_expenses_REVIEW_ONLY.sql",
  "two_phone_other_expenses_pull_REVIEW_ONLY.sql",
  "two_phone_atomic_proof_financial_write_REVIEW_ONLY.sql",
  "two_phone_owner_staff_key_enrollment_REVIEW_ONLY.sql",
  "two_phone_daily_summary_expense_reconciliation_REVIEW_ONLY.sql",
  "two_phone_signed_staff_pull_REVIEW_ONLY.sql"
];
const read=relative=>fs.readFileSync(path.join(process.cwd(),relative),"utf8");
const bundle=read("supabase/release_candidates/two_phone_atomic_cutover_STAGING_ONLY.sql");

test("Review bundle contains each source proposal exactly in dependency order",()=>{
  let previousIndex=-1;
  for(const name of sources){
    const sourcePath="supabase/proposals/"+name;
    const raw=read(sourcePath);
    const opens=(raw.match(/^BEGIN;$/gm)||[]).length;
    const closes=(raw.match(/^COMMIT;$/gm)||[]).length;
    assert.equal(opens,closes,name+" has unmatched source transactions");
    assert.ok(opens>=1,name+" should have a top-level transaction");
    const transformed=raw
      .replace(/^BEGIN;$/gm,"-- TOP-LEVEL BEGIN moved to one enclosing transaction")
      .replace(/^COMMIT;$/gm,"-- TOP-LEVEL COMMIT moved to one enclosing transaction");
    const exact="-- SOURCE: "+sourcePath+"\n"+transformed+"\n";
    const start=bundle.indexOf(exact);
    assert.ok(start>previousIndex,name+" was reordered, changed or omitted");
    assert.equal(bundle.indexOf(exact,start+1),-1,name+" duplicated");
    previousIndex=start;
  }
  assert.equal((bundle.match(/^BEGIN;$/gm)||[]).length,1);
  assert.equal((bundle.match(/^COMMIT;$/gm)||[]).length,1);
  assert.ok(bundle.trimEnd().endsWith("COMMIT;"));
});
test("Bundle requires explicit disposable test override and preserves direct Staff bypass denial",()=>{
  assert.match(bundle,/marketpro\.staging_disposable/);
  assert.match(bundle,/DROP POLICY IF EXISTS member_purchases_read/);
  assert.match(bundle,/REVOKE ALL ON FUNCTION public\.marketpro_apply_team_purchase_change\(/);
  assert.match(bundle,/REVOKE ALL ON FUNCTION public\.marketpro_apply_team_financial_change\(/);
  assert.match(bundle,/REVOKE ALL ON FUNCTION public\.marketpro_apply_team_other_expense\(/);
  assert.match(bundle,/security_invoker=true,security_barrier=true/);
  assert.match(bundle,/CREATE OR REPLACE FUNCTION public\.marketpro_service_apply_proof_bound_finance\(/);
  assert.match(bundle,/CREATE OR REPLACE FUNCTION public\.marketpro_owner_approve_staff_signing_key\(/);
  assert.match(bundle,/transport_expenses_cents/);
});
