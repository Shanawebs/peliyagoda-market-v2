import test from "node:test";
import assert from "node:assert/strict";
import {createHash,createSign,generateKeyPairSync} from "node:crypto";
import {
 ownerBootstrapTranscript,verifyOwnerBootstrapKeyPossession,
 OWNER_BOOTSTRAP_VERSION
} from "../supabase/functions/_shared/owner_bootstrap_key_proof.mjs";

const t=1_800_000_000_000;
const owner=generateKeyPairSync("ec",{namedCurve:"prime256v1"});
const spki=owner.publicKey.export({format:"der",type:"spki"});
const q={
 proof_version:OWNER_BOOTSTRAP_VERSION,
 workspace_id:"af77cb66-76b2-492a-a460-06132f468713",
 owner_user_id:"98c3b101-d452-4b6b-902c-0b1a31fc340c",
 device_id:"33333333-3333-4333-8333-333333333333",
 session_id:"11111111-1111-4111-8111-111111111111",
 challenge_id:"22222222-2222-4222-8222-222222222222",
 nonce_hex:"b".repeat(64),
 fingerprint_sha256:createHash("sha256").update(spki).digest("hex"),
 spki_base64:spki.toString("base64"),
 issued_at_millis:t-1000,
 expires_at_millis:t+59000
};
function sign(m=q,key=owner.privateKey){
  const s=createSign("SHA256"); s.update(ownerBootstrapTranscript(m,t)); s.end();
  return s.sign(key).toString("base64url");
}

test("valid Android DER ECDSA P-256 Owner proof verifies",async()=>{
  const signature=sign();
  assert.equal(await verifyOwnerBootstrapKeyPossession(q,signature,t),true);
  assert.equal(new TextDecoder().decode(ownerBootstrapTranscript(q,t)).split("\n").length,10);
});

test("all challenge context fields and time boundaries are signed",async()=>{
  const signature=sign();
  for(const patch of [
    {workspace_id:"00000000-0000-4000-8000-000000000000"},
    {owner_user_id:"00000000-0000-4000-8000-000000000000"},
    {device_id:"44444444-4444-4444-8444-444444444444"},
    {session_id:"55555555-5555-4555-8555-555555555555"},
    {challenge_id:"66666666-6666-4666-8666-666666666666"},
    {nonce_hex:"c".repeat(64)},
    {fingerprint_sha256:"d".repeat(64)},
    {issued_at_millis:q.issued_at_millis-1},
    {expires_at_millis:q.expires_at_millis+1},
    {proof_version:"marketpro-staff-key-enrollment-v1"}
  ]) assert.equal(await verifyOwnerBootstrapKeyPossession({...q,...patch},signature,t),false,JSON.stringify(patch));

  assert.equal(await verifyOwnerBootstrapKeyPossession(q,signature,q.expires_at_millis),false);
  assert.equal(await verifyOwnerBootstrapKeyPossession(q,signature,q.issued_at_millis-1),false);
  assert.throws(()=>ownerBootstrapTranscript({...q,expires_at_millis:q.issued_at_millis+90000},t));
});

test("different phone key or tampered SPKI/fingerprint cannot enroll",async()=>{
  const another=generateKeyPairSync("ec",{namedCurve:"prime256v1"});
  const wrongSpki=another.publicKey.export({format:"der",type:"spki"});
  const sig=sign();
  assert.equal(await verifyOwnerBootstrapKeyPossession({...q,spki_base64:wrongSpki.toString("base64")},sig,t),false);
  assert.equal(await verifyOwnerBootstrapKeyPossession({
    ...q,spki_base64:wrongSpki.toString("base64"),
    fingerprint_sha256:createHash("sha256").update(wrongSpki).digest("hex")
  },sig,t),false);
  assert.equal(await verifyOwnerBootstrapKeyPossession(q,"bad_der",t),false);
  assert.equal(await verifyOwnerBootstrapKeyPossession(q,sign(q,another.privateKey),t),false);
});

test("malformed payload, fake role, replay-context and injected fields rejected",async()=>{
  const sig=sign();
  for(const patch of [
    {workspace_id:"af77cb66-76b2-492a-a460-06132f468713/other"},
    {session_id:"!!!"},
    {nonce_hex:"Z".repeat(64)},
    {fingerprint_sha256:"A".repeat(64)},
    {issued_at_millis:Infinity},
    {expires_at_millis:q.issued_at_millis},
    {expires_at_millis:q.issued_at_millis+60001},
    {spki_base64:"c2VjcmV0"}, 
  ]) assert.equal(await verifyOwnerBootstrapKeyPossession({...q,...patch},sig,t),false,JSON.stringify(patch));
});
