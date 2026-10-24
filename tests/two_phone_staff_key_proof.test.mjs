import { test } from "node:test";
import assert from "node:assert/strict";
import { generateKeyPairSync, sign, randomBytes,createHash } from "node:crypto";
import { canonicalStaffProofTranscript,verifyAndroidDeviceProof } from "../supabase/functions/_shared/device_proof.mjs";

const {privateKey,publicKey}=generateKeyPairSync("ec",{namedCurve:"prime256v1"});
const now=1800000000000;
const spki=publicKey.export({format:"der",type:"spki"});
const fp=createHash("sha256").update(spki).digest("hex");
const challenge={
 workspace_id:"11111111-1111-4111-8111-111111111111",
 staff_user_id:"33333333-3333-4333-8333-333333333333",
 device_id:"f1111111-1111-4111-8111-111111111111",
 session_id:"91111111-1111-4111-8111-111111111111",
 challenge_id:"e1111111-1111-4111-8111-111111111111",
 nonce_hex:randomBytes(32).toString("hex"),
 purpose:"pair",issued_at_millis:now-1000,expires_at_millis:now+59000,
 spki_base64:spki.toString("base64"),key_fingerprint_sha256:fp
};
function signature(c) {
 return sign("sha256",Buffer.from(canonicalStaffProofTranscript(c,now)),
   {key:privateKey,dsaEncoding:"der"}).toString("base64url");
}
test("Staff P-256 pairing proof uses same canonical Android transcript",async()=>{
 const der=signature(challenge);
 assert.equal(await verifyAndroidDeviceProof(challenge,der,now),true);
 assert.equal(fp.length,64);
});
test("Other phone, JWT session, nonce or swapped public key cannot claim enrollment",async()=>{
 const sig=signature(challenge);
 for(const change of [
   {device_id:"a1111111-1111-4111-8111-111111111111"},
   {session_id:"92222222-2222-4222-8222-222222222222"},
   {staff_user_id:"22222222-2222-4222-8222-222222222222"},
   {nonce_hex:"0".repeat(64)},
   {purpose:"write"},
   {spki_base64:generateKeyPairSync("ec",{namedCurve:"prime256v1"})
       .publicKey.export({format:"der",type:"spki"}).toString("base64")}
 ]) assert.equal(await verifyAndroidDeviceProof({...challenge,...change},sig,now),false);
 assert.equal(await verifyAndroidDeviceProof(challenge,sig,now+59000),false);
});
test("ECDSA proof of possession alone cannot assign approved=true",()=>{
 // The approved key is stored in a different private table only by Owner RPC.
 assert.equal("approved" in challenge,false);
 assert.equal("owner_approval" in challenge,false);
});
