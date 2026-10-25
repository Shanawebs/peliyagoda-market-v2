import test from "node:test";
import assert from "node:assert/strict";
import {generateKeyPairSync,createSign,createHash} from "node:crypto";
import {
 purchaseSignatureTranscript,verifySignedPurchase
} from "../supabase/functions/_shared/proof_bound_purchase.mjs";

const now=Date.now();
const {privateKey,publicKey}=generateKeyPairSync("ec",{
 namedCurve:"prime256v1"
});
const spki=publicKey.export({format:"der",type:"spki"}).toString("base64");
const payload={business_date:"2026-10-25",
 item_id:"77777777-7777-4777-8777-777777777777",
 quantity_grams:2500,price_cents_per_kg:52000};
const jsonbAsText='{"item_id": "77777777-7777-4777-8777-777777777777", "business_date": "2026-10-25", "quantity_grams": 2500, "price_cents_per_kg": 52000}';
const digest=createHash("sha256").update(jsonbAsText).digest("hex");
// SHA here models the exact 64-hex value returned by the PG17 SQL issuer;
// the Edge never recomputes PostgreSQL's canonical JSONB text representation.
const base={
 workspace_id:"11111111-1111-4111-8111-111111111111",
 staff_user_id:"33333333-3333-4333-8333-333333333333",
 device_id:"f1111111-1111-4111-8111-111111111111",
 session_id:"91111111-1111-4111-8111-111111111111",
 challenge_id:"a1111111-1111-4111-8111-111111111111",
 nonce_hex:"a".repeat(64),purpose:"write",
 issued_at_millis:now-3000,expires_at_millis:now+57000,
 proof_version:"marketpro-device-proof-purchase-v2",
 operation_id:"d1111111-1111-4111-8111-111111111111",
 entity_id:"c1111111-1111-4111-8111-111111111111",
 financial_action:"create",expected_revision:null,
 operation_sha256:digest,operation_payload:payload,
 spki_base64:spki
};
function sign(material) {
 const signer=createSign("SHA256");
 signer.update(purchaseSignatureTranscript(material,now));
 signer.end();
 return signer.sign(privateKey).toString("base64url");
}
test("Valid Android-format P-256 DER signature binds one purchase operation",async()=>{
 const signature=sign(base);
 assert.equal(await verifySignedPurchase(base,signature,now),true);
 assert.equal(new TextDecoder().decode(purchaseSignatureTranscript(base,now))
  .split("\n").length,15);
});
test("Changing financial operation ID, entity UUID, quantity digest or revision invalidates signature",async()=>{
 const sig=sign(base);
 const variants=[
  {operation_id:"d2222222-2222-4222-8222-222222222222"},
  {entity_id:"c2222222-2222-4222-8222-222222222222"},
  {operation_sha256:"b".repeat(64)},
  {financial_action:"correct",expected_revision:1},
  {session_id:"92222222-2222-4222-8222-222222222222"},
  {device_id:"f2222222-2222-4222-8222-222222222222"},
  {nonce_hex:"b".repeat(64)},
  {challenge_id:"a2222222-2222-4222-8222-222222222222"},
  {purpose:"read"},
 ];
 for(const field of variants){
  assert.equal(await verifySignedPurchase({...base,...field},sig,now),
    false,JSON.stringify(field));
 }
});
test("Changed public key, malformed DER and expired server nonce all denied",async()=>{
 const sig=sign(base);
 assert.equal(await verifySignedPurchase(base,"!!!",now),false);
 assert.equal(await verifySignedPurchase({...base,spki_base64:"invalid"},sig,now),false);
 assert.equal(await verifySignedPurchase(base,sig,now+58000),false);
 const {publicKey:another}=generateKeyPairSync("ec",{namedCurve:"prime256v1"});
 assert.equal(await verifySignedPurchase({
    ...base,spki_base64:another.export({format:"der",type:"spki"}).toString("base64")
 },sig,now),false);
});
test("Do not accept generic v1 signature in place of purchase-bound v2 signature",async()=>{
 const msg=new TextEncoder().encode([
 "marketpro-device-proof-v1","write",base.workspace_id,base.staff_user_id,
 base.device_id,base.session_id,base.challenge_id,base.nonce_hex,
 base.issued_at_millis,base.expires_at_millis
 ].join("\n"));
 const signer=createSign("SHA256");
 signer.update(msg);signer.end();
 assert.equal(await verifySignedPurchase(base,
   signer.sign(privateKey).toString("base64url"),now),false);
});
test("The challenge cannot silently fall back to blank payload/zero expected revision",async()=>{
 const sig=sign(base);
 assert.equal(await verifySignedPurchase({...base,operation_sha256:""},sig,now),false);
 assert.equal(await verifySignedPurchase({...base,expected_revision:0},sig,now),false);
 assert.equal(await verifySignedPurchase({...base,proof_version:"marketpro-device-proof-v1"},sig,now),false);
});
