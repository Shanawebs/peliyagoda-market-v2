import test from "node:test";
import assert from "node:assert/strict";
import {createSign,generateKeyPairSync} from "node:crypto";
import {FINANCIAL_TYPES,financeV3Transcript,verifySignedFinancialV3}
 from "../supabase/functions/_shared/proof_bound_finance.mjs";
const now=1_800_000_000_000;
const {privateKey,publicKey}=generateKeyPairSync("ec",{namedCurve:"prime256v1"});
const data={
 workspace_id:"11111111-1111-4111-8111-111111111111",
 staff_user_id:"33333333-3333-4333-8333-333333333333",
 device_id:"f1111111-1111-4111-8111-111111111111",
 session_id:"91111111-1111-4111-8111-111111111111",
 challenge_id:"a1111111-1111-4111-8111-111111111111",
 nonce_hex:"f".repeat(64),purpose:"write",
 issued_at_millis:now-1000,expires_at_millis:now+59000,
 proof_version:"marketpro-device-proof-financial-v3",
 entity_type:"requisition",
 operation_id:"d1111111-1111-4111-8111-111111111111",
 entity_id:"c1111111-1111-4111-8111-111111111111",
 financial_action:"create",expected_revision:null,
 operation_sha256:"b".repeat(64),
 spki_base64:publicKey.export({type:"spki",format:"der"}).toString("base64")
};
function sign(material){
 const s=createSign("SHA256");
 s.update(financeV3Transcript(material,now));s.end();
 return s.sign(privateKey).toString("base64url");
}
test("Four exact Staff finance types cryptographically sign distinct v3 contexts",async()=>{
 const signed=new Set();
 for(const type of FINANCIAL_TYPES) {
  const m={...data,entity_type:type};
  const sig=sign(m);
  assert.equal(await verifySignedFinancialV3(m,sig,now),true,type);
  assert.equal(new TextDecoder().decode(financeV3Transcript(m,now))
    .split("\n").length,16);
  signed.add(sig);
 }
 assert.equal(signed.size,4);
});
test("No cross-type, phone, JWT, amount or revision signature replay",async()=>{
 for(const type of FINANCIAL_TYPES){
  const m={...data,entity_type:type};
  const sig=sign(m);
  for(const changed of [
   {entity_type:FINANCIAL_TYPES.find(x=>x!==type)},
   {device_id:"f2222222-2222-4222-8222-222222222222"},
   {session_id:"92222222-2222-4222-8222-222222222222"},
   {operation_id:"d2222222-2222-4222-8222-222222222222"},
   {entity_id:"c2222222-2222-4222-8222-222222222222"},
   {operation_sha256:"a".repeat(64)},
   {financial_action:"correct",expected_revision:1},
   {nonce_hex:"a".repeat(64)},
   {proof_version:"marketpro-device-proof-purchase-v2"},
   {purpose:"read"},
  ]) assert.equal(await verifySignedFinancialV3({...m,...changed},sig,now),false);
 }
});
test("Malformed key/signature, expired transcript and nonapproved finance types fail closed",async()=>{
 const sig=sign(data);
 assert.equal(await verifySignedFinancialV3({...data,entity_type:"purchase"},sig,now),false);
 assert.equal(await verifySignedFinancialV3({...data,entity_type:"registered_devices"},sig,now),false);
 assert.equal(await verifySignedFinancialV3(data,"bad!",now),false);
 assert.equal(await verifySignedFinancialV3(data,sig,now+60000),false);
 assert.equal(await verifySignedFinancialV3({...data,spki_base64:"test"},sig,now),false);
});
