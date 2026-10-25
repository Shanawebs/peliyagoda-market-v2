import test from "node:test";
import assert from "node:assert/strict";
import {createSign,generateKeyPairSync,createHash} from "node:crypto";
import {
 keyEnrollmentTranscript,verifyEnrollmentKeyPossession
} from "../supabase/functions/_shared/staff_key_enrollment_proof.mjs";
const now=1_800_000_000_000;
const pair=generateKeyPairSync("ec",{namedCurve:"prime256v1"});
const spki=pair.publicKey.export({format:"der",type:"spki"});
const base={
 proof_version:"marketpro-staff-key-enrollment-v1",
 workspace_id:"11111111-1111-4111-8111-111111111111",
 staff_user_id:"33333333-3333-4333-8333-333333333333",
 device_id:"f1111111-1111-4111-8111-111111111111",
 session_id:"91111111-1111-4111-8111-111111111111",
 challenge_id:"a1111111-1111-4111-8111-111111111111",
 nonce_hex:"b".repeat(64),
 fingerprint_sha256:createHash("sha256").update(spki).digest("hex"),
 issued_at_millis:now-1000,
 expires_at_millis:now+59000,
 spki_base64:spki.toString("base64")
};
function sign(m){
 const s=createSign("SHA256");
 s.update(keyEnrollmentTranscript(m,now));s.end();
 return s.sign(pair.privateKey).toString("base64url");
}
test("Only holder of candidate EC private key can prove Staff enrollment",async()=>{
 const sig=sign(base);
 assert.equal(await verifyEnrollmentKeyPossession(base,sig,now),true);
 assert.equal(new TextDecoder().decode(keyEnrollmentTranscript(base,now))
    .split("\n").length,10);
});
test("Exact device, session, nonce, fingerprint and 60s expiry are signed",async()=>{
 const sig=sign(base);
 for(const diff of [
  {workspace_id:"22222222-2222-4222-8222-222222222222"},
  {staff_user_id:"22222222-2222-4222-8222-222222222222"},
  {device_id:"f2222222-2222-4222-8222-222222222222"},
  {session_id:"92222222-2222-4222-8222-222222222222"},
  {challenge_id:"a2222222-2222-4222-8222-222222222222"},
  {fingerprint_sha256:"c".repeat(64)},
  {nonce_hex:"c".repeat(64)},
  {proof_version:"marketpro-device-proof-financial-v3"},
 ]) assert.equal(await verifyEnrollmentKeyPossession(
   {...base,...diff},sig,now),false,JSON.stringify(diff));
 assert.equal(await verifyEnrollmentKeyPossession(base,sig,now+59000),false);
});
test("Wrong public key cannot claim a pending candidate key",async()=>{
 const another=generateKeyPairSync("ec",{namedCurve:"prime256v1"});
 assert.equal(await verifyEnrollmentKeyPossession({
  ...base,spki_base64:another.publicKey.export({format:"der",type:"spki"}).toString("base64")
 },sign(base),now),false);
 assert.equal(await verifyEnrollmentKeyPossession(base,"...",now),false);
});
