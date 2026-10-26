import test from "node:test";
import assert from "node:assert/strict";
import {createHash,createSign,generateKeyPairSync} from "node:crypto";
import {handlePendingOwnerIntent} from "../supabase/functions/_shared/pending_owner_edge_core.mjs";
import {pendingOwnerTranscript,PENDING_OWNER_VERSION,verifyPendingOwnerSignature}
  from "../supabase/functions/_shared/pending_owner_proof.mjs";
import {ownerBootstrapTranscript} from "../supabase/functions/_shared/owner_bootstrap_key_proof.mjs";
const T=1_800_000_000_000;
const WS="af77cb66-76b2-492a-a460-06132f468713";
const OWNER="98c3b101-d452-4b6b-902c-0b1a31fc340c";
const SESSION="11111111-1111-4111-8111-111111111111";
const DEVICE="33333333-3333-4333-8333-333333333333";
const CHALLENGE="44444444-4444-4444-8444-444444444444";
const key=generateKeyPairSync("ec",{namedCurve:"prime256v1"});
const der=key.publicKey.export({format:"der",type:"spki"});
const fingerprint=createHash("sha256").update(der).digest("hex");
const material={
 proof_version:PENDING_OWNER_VERSION,
 workspace_id:WS,owner_user_id:OWNER,device_id:DEVICE,session_id:SESSION,
 challenge_id:CHALLENGE,nonce_hex:"b".repeat(64),
 fingerprint_sha256:fingerprint,issued_at_millis:T-1000,
 expires_at_millis:T+59_000,spki_base64:der.toString("base64")
};
function sign(privateKey=key.privateKey){
 const c=createSign("SHA256");c.update(pendingOwnerTranscript(material,T));c.end();
 return c.sign(privateKey).toString("base64url");
}
const issue={intent:"pending_issue",workspace_id:WS,device_id:DEVICE,
 spki_base64url:der.toString("base64url"),explicit_consent:true};
const verify=(sig=sign())=>({intent:"pending_verify",workspace_id:WS,
 challenge_id:CHALLENGE,signature_der_base64url:sig});
function fixture({keyMaterial=material,receiptOverride={},gate=true}={}){
 const calls=[];let pending=false;let consumed=false;
 const ctx={
  userClaims:{id:OWNER,role:"authenticated"},
  jwtClaims:{sub:OWNER,session_id:SESSION,aal:"aal2",is_anonymous:false,
   amr:[{method:"totp",timestamp:Math.floor(T/1000)}]},
  supabaseAdmin:{async rpc(name,args){
   calls.push({name,args});
   if(!gate)return {data:null,error:{message:"disabled"}};
   if(name==="marketpro_qa_service_pending_owner_issue"){
    pending=true;
    return {data:{...keyMaterial,registration_status:"not_registered",
       device_activated:false,staff_access:false},error:null};
   }
   if(!pending||consumed)return {data:null,error:{message:"already used"}};
   if(name==="marketpro_qa_service_pending_owner_material")
    return {data:keyMaterial,error:null};
   if(name==="marketpro_qa_service_pending_owner_commit"){
    consumed=true;
    return {data:{ok:true,registration_status:"pending",
      owner_user_id:OWNER,device_id:DEVICE,fingerprint_sha256:fingerprint,
      nonce_consumed_once:true,device_activated:false,
      hardware_attestation_verified:false,can_write:false,staff_access:false,
      ...receiptOverride},error:null};
   }
   throw Error("Unauthorized RPC: "+name);
  }}
 };
 return {ctx,calls};
}
test("distinct registration version cannot be swapped for QA proof",()=>{
 assert.notEqual(PENDING_OWNER_VERSION,"marketpro-owner-bootstrap-key-v1");
 assert.throws(()=>ownerBootstrapTranscript(material,T));
 assert.equal(pendingOwnerTranscript(material,T).toString().length>100,true);
});
test("real ECDSA P256 pending Owner signature verifies on correct key",async()=>{
 assert.equal(await verifyPendingOwnerSignature(material,sign(),T),true);
 const wrong=generateKeyPairSync("ec",{namedCurve:"prime256v1"});
 assert.equal(await verifyPendingOwnerSignature(material,sign(wrong.privateKey),T),false);
 assert.equal(await verifyPendingOwnerSignature({...material,nonce_hex:"0".repeat(64)},sign(),T),false);
 assert.equal(await verifyPendingOwnerSignature({...material,proof_version:"marketpro-owner-bootstrap-key-v1"},sign(),T),false);
 assert.equal(await verifyPendingOwnerSignature(material,sign(),T+59_000),false);
});
test("real signed fresh Owner MFA -> pending only, no activation, one-use",async()=>{
 const {ctx,calls}=fixture();
 const issued=await handlePendingOwnerIntent(ctx,issue,{nowMs:T});
 assert.equal(issued.ok,true);
 assert.equal(issued.registration_status,"not_registered");
 assert.equal(issued.device_activated,false);
 const verified=await handlePendingOwnerIntent(ctx,verify(),{nowMs:T});
 assert.deepEqual(verified,{ok:true,registration_status:"pending",
   device_activated:false,hardware_attestation_verified:false,
   can_write:false,staff_access:false,purchase_sync:false});
 assert.deepEqual(calls.map(x=>x.name),[
   "marketpro_qa_service_pending_owner_issue",
   "marketpro_qa_service_pending_owner_material",
   "marketpro_qa_service_pending_owner_commit"
 ]);
 assert.equal((await handlePendingOwnerIntent(ctx,verify(),{nowMs:T})).ok,false);
});
test("closed gate denies and never inserts proposed pending registration",async()=>{
 const {ctx,calls}=fixture({gate:false});
 const x=await handlePendingOwnerIntent(ctx,issue,{nowMs:T});
 assert.equal(x.ok,false);
 assert.deepEqual(calls.map(x=>x.name),["marketpro_qa_service_pending_owner_issue"]);
});
test("missing consent, stale MFA, AAL1, wrong Owner and extra JSON denied before RPC",async()=>{
 const {ctx,calls}=fixture();
 const tests=[
  [ctx,{...issue,explicit_consent:false}],
  [ctx,{...issue,explicit_consent:"true"}],
  [ctx,{...issue,owner_user_id:OWNER}],
  [ctx,{...issue,workspace_id:"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"}],
  [{...ctx,jwtClaims:{...ctx.jwtClaims,aal:"aal1"}},issue],
  [{...ctx,jwtClaims:{...ctx.jwtClaims,amr:[]}},issue],
  [{...ctx,jwtClaims:{...ctx.jwtClaims,amr:[{method:"totp",timestamp:Math.floor(T/1000)-1000}]}},issue],
  [{...ctx,userClaims:{...ctx.userClaims,id:"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"},
     jwtClaims:{...ctx.jwtClaims,sub:"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"}},issue],
  [ctx,{intent:"pending_verify",workspace_id:WS,challenge_id:CHALLENGE,
    signature_der_base64url:"x"}],
  [ctx,{intent:"pending_activate",workspace_id:WS}]
 ];
 for(const [identity,payload] of tests)
  assert.equal((await handlePendingOwnerIntent(identity,payload,{nowMs:T})).ok,false);
 assert.equal(calls.length,0);
});
test("tampered proof, wrong phone, wrong session, activated forged receipt denied",async()=>{
 const another=generateKeyPairSync("ec",{namedCurve:"prime256v1"});
 for(const [override,sig,receiptOverride] of [
  [{nonce_hex:"f".repeat(64)},sign(),{}],
  [{spki_base64:another.publicKey.export({type:"spki",format:"der"}).toString("base64")},sign(),{}],
  [{session_id:"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"},sign(),{}],
  [{device_id:"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"},sign(),{}],
  [{},sign(another.privateKey),{}],
  [{},sign(),{device_activated:true}],
  [{},sign(),{can_write:true}],
  [{},sign(),{staff_access:true}],
 ]){
  const {ctx,calls}=fixture({keyMaterial:{...material,...override},receiptOverride});
  const first=await handlePendingOwnerIntent(ctx,issue,{nowMs:T});
  if(override.session_id || override.device_id){assert.equal(first.ok,false);continue;}
  assert.equal(first.ok,true);
  const v=await handlePendingOwnerIntent(ctx,verify(sig),{nowMs:T});
  assert.equal(v.ok,false);
  if(Object.keys(receiptOverride).length===0)
    assert.equal(calls.some(x=>x.name==="marketpro_qa_service_pending_owner_commit"),false);
 }
});
