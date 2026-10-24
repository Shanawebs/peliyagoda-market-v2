import { test } from "node:test";
import assert from "node:assert/strict";
import { generateKeyPairSync, sign as nodeSign, randomBytes } from "node:crypto";
import { canonicalStaffProofTranscript, derP256ToRaw64, verifyAndroidDeviceProof } from "../supabase/functions/_shared/device_proof.mjs";

const {privateKey,publicKey}=generateKeyPairSync("ec",{namedCurve:"prime256v1"});
const now=1800000000000;
const model=Object.freeze({
 workspace_id:"11111111-1111-4111-8111-111111111111",
 staff_user_id:"33333333-3333-4333-8333-333333333333",
 device_id:"f1111111-1111-4111-8111-111111111111",
 session_id:"91111111-1111-4111-8111-111111111111",
 challenge_id:"c1111111-1111-4111-8111-111111111111",
 nonce_hex:randomBytes(32).toString("hex"),
 purpose:"write",
 issued_at_millis:now-12000,expires_at_millis:now+30000,
 spki_base64:publicKey.export({format:"der",type:"spki"}).toString("base64")
});
function sign(value){
 return nodeSign("sha256",Buffer.from(canonicalStaffProofTranscript(value,now)),
 {key:privateKey,dsaEncoding:"der"}).toString("base64url");
}

test("Android DER ECDSA signature crosses into Web Crypto P-256 raw verify",async()=>{
 const der=Buffer.from(sign(model),"base64url");
 assert.equal(derP256ToRaw64(der).length,64);
 assert.equal(await verifyAndroidDeviceProof(model,der.toString("base64url"),now),true);
});

test("second phone, workspace, session, nonce, challenge ID and purpose fail",async()=>{
 const sig=sign(model);
 for(const [key,value] of Object.entries({
  workspace_id:"33333333-3333-4333-8333-333333333333",
  staff_user_id:"22222222-2222-4222-8222-222222222222",
  device_id:"b1111111-1111-4111-8111-111111111111",
  session_id:"92222222-2222-4222-8222-222222222222",
  challenge_id:"d1111111-1111-4111-8111-111111111111",
  nonce_hex:"b".repeat(64),purpose:"read"
 })){
  assert.equal(await verifyAndroidDeviceProof({...model,[key]:value},sig,now),
    false,key);
 }
});

test("challenge boundary, invalid signatures and unrelated key fail",async()=>{
 const sig=sign(model);
 assert.equal(await verifyAndroidDeviceProof(model,sig,now+30000),false);
 assert.equal(await verifyAndroidDeviceProof(model,sig,now-12001),false);
 assert.equal(await verifyAndroidDeviceProof({...model,expires_at_millis:now+130000},sig,now),false);
 assert.equal(await verifyAndroidDeviceProof({...model,nonce_hex:"AAAA"},sig,now),false);
 assert.equal(await verifyAndroidDeviceProof(model,"!!!",now),false);
 const another=generateKeyPairSync("ec",{namedCurve:"prime256v1"});
 assert.equal(await verifyAndroidDeviceProof({...model,
   spki_base64:another.publicKey.export({format:"der",type:"spki"}).toString("base64")
 },sig,now),false);
});

test("DER canonicalization rejects non-minimal and trailing bytes",()=>{
 const sig=Buffer.from(sign(model),"base64url");
 assert.throws(()=>derP256ToRaw64(Uint8Array.of(...sig,0)),/DER/);
 assert.throws(()=>derP256ToRaw64(Uint8Array.of(0x30,0x81,...sig.subarray(2))),/DER/);
 assert.throws(()=>derP256ToRaw64(new Uint8Array(0)),/DER/);
 assert.throws(()=>derP256ToRaw64(Uint8Array.of(0x30,3,0x02,1,0x80)),/DER|integer/);
});

test("signature/canonical text never includes extra delimiter or dynamic fields",()=>{
 const bytes=canonicalStaffProofTranscript(model,now);
 const lines=new TextDecoder().decode(bytes).split("\n");
 assert.equal(lines.length,10);
 assert.equal(lines[0],"marketpro-device-proof-v1");
 assert.equal(lines[1],"write");
 assert.equal(lines[7],model.nonce_hex);
 assert.throws(()=>canonicalStaffProofTranscript({...model,device_id:"f\nother"},now));
});
