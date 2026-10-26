import test from "node:test";
import assert from "node:assert/strict";
import {createHash,createSign,generateKeyPairSync} from "node:crypto";
import {handleOwnerBootstrapIntent} from "../supabase/functions/_shared/owner_bootstrap_edge_core.mjs";
import {ownerBootstrapTranscript,OWNER_BOOTSTRAP_VERSION} from "../supabase/functions/_shared/owner_bootstrap_key_proof.mjs";
const T=1_800_000_000_000;
const w="af77cb66-76b2-492a-a460-06132f468713";
const owner="98c3b101-d452-4b6b-902c-0b1a31fc340c";
const session="11111111-1111-4111-8111-111111111111";
const device="33333333-3333-4333-8333-333333333333";
const challenge="44444444-4444-4444-8444-444444444444";
const kp=generateKeyPairSync("ec",{namedCurve:"prime256v1"});
const spki=kp.publicKey.export({format:"der",type:"spki"});
const fp=createHash("sha256").update(spki).digest("hex");
const material={
 proof_version:OWNER_BOOTSTRAP_VERSION,workspace_id:w,owner_user_id:owner,
 session_id:session,device_id:device,challenge_id:challenge,
 nonce_hex:"a".repeat(64),fingerprint_sha256:fp,
 issued_at_millis:T-1000,expires_at_millis:T+59000,
 spki_base64:spki.toString("base64")
};
const issue={intent:"proof_issue",workspace_id:w,device_id:device,spki_base64url:spki.toString("base64url")};
function sign(key=kp.privateKey){const s=createSign("SHA256");s.update(ownerBootstrapTranscript(material,T));s.end();return s.sign(key).toString("base64url");}
const verify=(sig=sign())=>({intent:"proof_verify",workspace_id:w,challenge_id:challenge,signature_der_base64url:sig});
function fixture(m=material){
 const calls=[];let consumed=false;let offered=false;
 const ctx={userClaims:{role:"authenticated",id:owner},
 jwtClaims:{sub:owner,session_id:session,aal:"aal2",is_anonymous:false,
 amr:[{method:"totp",timestamp:Math.floor(T/1000)}]},
 supabaseAdmin:{async rpc(name,args){
  calls.push({name,args});
  if(!["marketpro_qa_service_remote_proof_issue","marketpro_qa_service_remote_proof_material",
        "marketpro_qa_service_remote_proof_consume"].includes(name))
    throw new Error("Unexpected business or enrollment RPC!");
  if(name==="marketpro_qa_service_remote_proof_issue"){
    offered=true;return {data:{...m,phone_activated:false,staff_access_granted:false},error:null};
  }
  if(!offered||consumed)return {data:null,error:{message:"not allowed"}};
  if(args.p_owner!==owner||args.p_session!==session||args.p_challenge!==challenge)
    return {data:null,error:{message:"session mismatch"}};
  if(name==="marketpro_qa_service_remote_proof_material")
    return {data:m,error:null};
  consumed=true;
  return {data:{verified_and_consumed:true,device_id:device,
    fingerprint_sha256:fp,device_registered:false,staff_access:false,can_write:false},error:null};
 }}};return {ctx,calls};
}
test("Owner fresh AAL2 issues a server nonce then verifies Android ECDSA with no enrollment",async()=>{
 const {ctx,calls}=fixture();
 const first=await handleOwnerBootstrapIntent(ctx,issue,{nowMs:T});
 assert.equal(first.ok,true);
 assert.equal(first.phone_activated,false);
 assert.equal(calls[0].name,"marketpro_qa_service_remote_proof_issue");
 assert.equal(calls[0].args.p_owner,owner);
 assert.equal(calls[0].args.p_session,session);
 const signed=await handleOwnerBootstrapIntent(ctx,verify(),{nowMs:T});
 assert.deepEqual(signed,{
  ok:true,remote_p256_proof_verified:true,nonce_consumed_once:true,
  device_registered:false,staff_access:false,financial_writes:false
 });
 assert.deepEqual(calls.map(x=>x.name),[
  "marketpro_qa_service_remote_proof_issue",
  "marketpro_qa_service_remote_proof_material",
  "marketpro_qa_service_remote_proof_consume"
 ]);
 assert.equal((await handleOwnerBootstrapIntent(ctx,verify(),{nowMs:T})).ok,false);
});
test("wrong EC key, nonce, fingerprint, session and expiry never consume proof",async()=>{
 const wrong=generateKeyPairSync("ec",{namedCurve:"prime256v1"});
 for(const [change,sig] of [
  [{},sign(wrong.privateKey)],[{nonce_hex:"b".repeat(64)},sign()],
  [{fingerprint_sha256:"c".repeat(64)},sign()],
  [{session_id:"55555555-5555-4555-8555-555555555555"},sign()],
  [{spki_base64:wrong.publicKey.export({type:"spki",format:"der"}).toString("base64")},sign()],
  [{expires_at_millis:T-100},sign()]
 ]){
  const {ctx,calls}=fixture({...material,...change});
  assert.equal((await handleOwnerBootstrapIntent(ctx,issue,{nowMs:T})).ok,true);
  const result=await handleOwnerBootstrapIntent(ctx,verify(sig),{nowMs:T});
  assert.equal(result.ok,false);
  assert.equal(calls.filter(x=>x.name==="marketpro_qa_service_remote_proof_consume").length,0);
 }
});
test("non Owner JWT, AAL1, stale TOTP, extra fields, wrong workspace reject before database calls",async()=>{
 const {ctx,calls}=fixture();
 const cases=[
  [{...ctx,jwtClaims:{...ctx.jwtClaims,aal:"aal1"}},issue],
  [{...ctx,jwtClaims:{...ctx.jwtClaims,amr:[{method:"totp",timestamp:Math.floor(T/1000)-600}]}},issue],
  [{...ctx,userClaims:{...ctx.userClaims,id:"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"},
   jwtClaims:{...ctx.jwtClaims,sub:"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"}},issue],
  [ctx,{...issue,service_role:"not_allowed"}],
  [ctx,{...issue,workspace_id:"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"}],
  [ctx,{...verify(),signature_der_base64url:"!"}]
 ];
 for(const [c,p] of cases)assert.equal((await handleOwnerBootstrapIntent(c,p,{nowMs:T})).ok,false);
 assert.equal(calls.length,0);
});
