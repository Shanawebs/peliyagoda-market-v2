import test from "node:test";
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
const purchase=readFileSync(
 "supabase/functions/marketpro-staff-signed-purchase/index.ts","utf8");
const financial=readFileSync(
 "supabase/functions/marketpro-staff-signed-financial/index.ts","utf8");
const worker=readFileSync(
 "app/src/main/java/lk/peliyagoda/marketpro/cloud/StaffFinanceAutoSyncScheduler.kt","utf8");
const router=readFileSync(
 "app/src/main/java/lk/peliyagoda/marketpro/cloud/StaffSignedFinanceRouting.kt","utf8");
const build=readFileSync("app/build.gradle.kts","utf8");
const uuid="a1111111-1111-4111-8111-111111111111";
function uuidRegex(s){
 const literal=s.match(/const UUID=(\/[^\n;]+\/);/);
 assert.ok(literal,"Edge endpoint must define strict UUID pattern");
 return new RegExp(literal[1].slice(1,-1));
}
test("Both signed financial endpoints accept all 36-character UUIDs",()=>{
 for(const src of [purchase,financial]){
  const v=uuidRegex(src);
  assert.equal(v.test(uuid),true);
  assert.equal(v.test("f1111111-1111-4111-8111-111111111111"),true);
  for(const bad of ["","a".repeat(36),"11111111-1111-1111-111111111111",
    uuid.toUpperCase(),uuid+"-extra",uuid.slice(0,-1)])
    assert.equal(v.test(bad),false,bad);
 }
});
test("Five financial categories are routed only to signed V2/V3 endpoints",()=>{
 assert.match(worker,/transport=SignedAllStaffFinancialWriteTransport\(\)/);
 assert.match(router,/ProofBoundStaffPurchaseHttpTransport\(\)/);
 assert.match(router,/ProofBoundStaffFinancialHttpTransport\(\)/);
 for(const name of [
 "purchase","requisition","purchase_addition",
 "daily_cash_received","daily_other_expenses"])
  assert.ok(router.includes('"' + name + '"'),name);
 assert.match(router,/throw IllegalArgumentException/);
 assert.doesNotMatch(router,/marketpro_apply_change|marketpro_service_commit_signed/);
});
test("Worker cannot turn on unless distinct Staff MFA/key/writer/security switches explicitly enabled",()=>{
 const flags=[
 "MARKETPRO_LOCAL_PREVIEW",
 "MARKETPRO_STAFF_LOGIN_ENABLED",
 "MARKETPRO_TEAM_PULL_RPC_DEPLOYED",
 "MARKETPRO_STAFF_PAIRING_RPC_DEPLOYED",
 "MARKETPRO_OWNER_TOTP_MFA_AUTH_DEPLOYED",
 "MARKETPRO_STAFF_KEY_ENROLLMENT_DEPLOYED",
 "MARKETPRO_STAFF_WRITE_APPROVAL_RPC_DEPLOYED",
 "MARKETPRO_STAFF_FINANCE_RPC_DEPLOYED",
 "MARKETPRO_STAFF_DEVICE_PROOF_WRITE_ENFORCED",
 "MARKETPRO_STAFF_OUTBOX_AUTO_UPLOAD"
 ];
 for(const flag of flags)assert.ok(worker.includes("BuildConfig."+flag),flag);
 for(const flag of flags)assert.ok(build.includes(
  '"'+flag+'", "'+(flag==="MARKETPRO_LOCAL_PREVIEW"?"true":"false")+'"'),
   flag+" preview must stay OFF except local preview");
});
