import test from "node:test";
import assert from "node:assert/strict";
import {createHash,createSign,generateKeyPairSync} from "node:crypto";
import {
  handleOwnerBootstrapIntent,validOwnerBootstrapClaims,MAX_BODY_CHARS
} from "../supabase/functions/_shared/owner_bootstrap_edge_core.mjs";
import {ownerBootstrapTranscript,OWNER_BOOTSTRAP_VERSION}
  from "../supabase/functions/_shared/owner_bootstrap_key_proof.mjs";

const T=1_800_000_000_000;
const WS="af77cb66-76b2-492a-a460-06132f468713";
const OWNER="98c3b101-d452-4b6b-902c-0b1a31fc340c";
const SESSION="11111111-1111-4111-8111-111111111111";
const PHONE="33333333-3333-4333-8333-333333333333";
const CHALLENGE="22222222-2222-4222-8222-222222222222";
const kp=generateKeyPairSync("ec",{namedCurve:"prime256v1"});
const pub=kp.publicKey.export({type:"spki",format:"der"});
const fingerprint=createHash("sha256").update(pub).digest("hex");
const material={
  proof_version:OWNER_BOOTSTRAP_VERSION,
  workspace_id:WS,owner_user_id:OWNER,device_id:PHONE,
  session_id:SESSION,challenge_id:CHALLENGE,
  nonce_hex:"a".repeat(64),
  fingerprint_sha256:fingerprint,
  issued_at_millis:T-1000,expires_at_millis:T+59000,
  spki_base64:pub.toString("base64")
};
function sign(m=material,priv=kp.privateKey){
  const s=createSign("SHA256");
  s.update(ownerBootstrapTranscript(m,T));s.end();
  return s.sign(priv).toString("base64url");
}
const verifyBody=(signature=sign())=>({
  intent:"verify",workspace_id:WS,challenge_id:CHALLENGE,
  signature_der_base64url:signature
});
const issueBody={
  intent:"issue",workspace_id:WS,device_id:PHONE,
  spki_base64url:pub.toString("base64url")
};
function fixture({
  enabled=true, materialOverride={}, commitOverride={}, calls=[]
}={}){
  let used=false;
  const m={...material,...materialOverride};
  const jwt={
    sub:OWNER,aal:"aal2",session_id:SESSION,is_anonymous:false,
    amr:[{method:"totp",timestamp:Math.floor(T/1000)}]
  };
  const ctx={
    userClaims:{id:OWNER,role:"authenticated"},
    jwtClaims:jwt,
    supabase:{async rpc(name,args){
      calls.push({name,args});
      if(name!=="marketpro_qa_owner_issue_bootstrap"||!enabled)
        return {data:null,error:{message:"Permission denied"}};
      return {data:{...m,phone_activated:false},error:null};
    }},
    supabaseAdmin:{async rpc(name,args){
      calls.push({name,args});
      if(!enabled || used || args.p_challenge!==CHALLENGE ||
        args.p_owner!==OWNER || args.p_session!==SESSION)
        return {data:null,error:{message:"denied"}};
      if(name==="marketpro_qa_service_owner_bootstrap_material")
        return {data:m,error:null};
      if(name==="marketpro_qa_service_owner_bootstrap_commit"){
        used=true;
        return {data:{
          proof_verified_and_consumed:true,device_id:PHONE,
          fingerprint_sha256:fingerprint,phone_activated:false,
          can_write:false,...commitOverride
        },error:null};
      }
      return {data:null,error:{message:"no function"}};
    }}
  };
  return {ctx,calls};
}

test("fresh verified Owner JWT admits correct context; never just MFA enrollment",()=>{
  const {ctx}=fixture();
  assert.equal(validOwnerBootstrapClaims(ctx,T),true);
  const invalid=[
    {jwtClaims:{...ctx.jwtClaims,aal:"aal1"}},
    {jwtClaims:{...ctx.jwtClaims,sub:"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"}},
    {jwtClaims:{...ctx.jwtClaims,session_id:null}},
    {jwtClaims:{...ctx.jwtClaims,is_anonymous:true}},
    {jwtClaims:{...ctx.jwtClaims,amr:[]}},
    {jwtClaims:{...ctx.jwtClaims,amr:[{method:"totp",timestamp:Math.floor(T/1000)-1000}]}},
    {jwtClaims:{...ctx.jwtClaims,amr:[{method:"password",timestamp:Math.floor(T/1000)}]}},
    {jwtClaims:{...ctx.jwtClaims,amr:[{method:"totp",timestamp:"1800000000"}]}},
    {userClaims:{...ctx.userClaims,role:"anon"}}
  ];
  for(const update of invalid)
    assert.equal(validOwnerBootstrapClaims({...ctx,...update},T),false);
});

test("disabled hosted rollout fails closed: no admin work or public key",async()=>{
  const {ctx,calls}=fixture({enabled:false});
  const x=await handleOwnerBootstrapIntent(ctx,issueBody,{nowMs:T});
  assert.deepEqual(x,{ok:false,error:"Owner bootstrap denied"});
  assert.equal(calls.length,1);
  assert.equal(calls[0].name,"marketpro_qa_owner_issue_bootstrap");
});

test("owner issue passes ONLY user-scoped RPC and returns inactive challenge",async()=>{
  const {ctx,calls}=fixture();
  const x=await handleOwnerBootstrapIntent(ctx,issueBody,{nowMs:T});
  assert.equal(x.ok,true);
  assert.equal(x.phone_activated,false);
  assert.equal(x.staff_activated,false);
  assert.equal(x.challenge.session_id,SESSION);
  assert.equal(calls.length,1);
  assert.equal(calls[0].name,"marketpro_qa_owner_issue_bootstrap");
  assert.equal(calls[0].args.p_spki_base64,pub.toString("base64"));
});

test("authenticated fresh Owner with real signature stages pending proof once",async()=>{
  const {ctx,calls}=fixture();
  const x=await handleOwnerBootstrapIntent(ctx,verifyBody(),{nowMs:T});
  assert.equal(x.ok,true);
  assert.equal(x.proof_verified_and_consumed,true);
  assert.equal(x.phone_activated,false);
  assert.equal(x.staff_activated,false);
  assert.equal(x.writer_enabled,false);
  assert.deepEqual(calls.map(c=>c.name),[
    "marketpro_qa_service_owner_bootstrap_material",
    "marketpro_qa_service_owner_bootstrap_commit"
  ]);
  assert.deepEqual(calls[0].args,{
    p_challenge:CHALLENGE,p_owner:OWNER,p_session:SESSION
  });
  // Atomic server-side replay: a consumed challenge is not reaccepted.
  const again=await handleOwnerBootstrapIntent(ctx,verifyBody(),{nowMs:T});
  assert.equal(again.ok,false);
  assert.equal(calls.filter(c=>c.name==="marketpro_qa_service_owner_bootstrap_commit").length,1);
});

test("wrong phone key, material fingerprint, nonce and payload context never commit",async()=>{
  const another=generateKeyPairSync("ec",{namedCurve:"prime256v1"});
  const attempts=[
    {signature:sign(material,another.privateKey)},
    {materialOverride:{nonce_hex:"b".repeat(64)}},
    {materialOverride:{fingerprint_sha256:"0".repeat(64)}},
    {materialOverride:{spki_base64:another.publicKey.export({type:"spki",format:"der"}).toString("base64")}},
    {materialOverride:{session_id:"ffffffff-ffff-4fff-8fff-ffffffffffff"}},
    {materialOverride:{workspace_id:"eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"}},
    {materialOverride:{owner_user_id:"dddddddd-dddd-4ddd-8ddd-dddddddddddd"}},
    {materialOverride:{device_id:"not-a-uuid"}},
    {materialOverride:{challenge_id:"cccccccc-cccc-4ccc-8ccc-cccccccccccc"}}
  ];
  for(const {signature,materialOverride} of attempts){
    const {ctx,calls}=fixture({materialOverride});
    const result=await handleOwnerBootstrapIntent(
      ctx,verifyBody(signature??sign()),{nowMs:T}
    );
    assert.equal(result.ok,false,JSON.stringify(materialOverride));
    assert.equal(calls.filter(x=>x.name==="marketpro_qa_service_owner_bootstrap_commit").length,0);
  }
});

test("server receipt cannot claim phone activated or writing enabled",async()=>{
  for(const changed of [
    {phone_activated:true},{can_write:true},{proof_verified_and_consumed:false},
    {fingerprint_sha256:"0".repeat(64)},{device_id:"ffffffff-ffff-4fff-8fff-ffffffffffff"}
  ]){
    const {ctx}=fixture({commitOverride:changed});
    const result=await handleOwnerBootstrapIntent(ctx,verifyBody(),{nowMs:T});
    assert.equal(result.ok,false,JSON.stringify(changed));
  }
});

test("invalid payload fields, unauthorized identity and stale TOTP do not call RPCs",async()=>{
  const illegal=[
    {...verifyBody(),p_owner:OWNER},
    {...issueBody,service_role:"secret"},
    {...issueBody,workspace_id:"ffffffff-ffff-4fff-8fff-ffffffffffff"},
    {...issueBody,spki_base64url:"a"},
    {...verifyBody(),signature_der_base64url:"---"},
    {...verifyBody(),challenge_id:"broken"},
    {...verifyBody(),intent:"delete"},
    null,
    [],
    {}
  ];
  for(const data of illegal){
    const {ctx,calls}=fixture();
    const result=await handleOwnerBootstrapIntent(ctx,data,{nowMs:T});
    assert.equal(result.ok,false);
    assert.equal(calls.length,0);
  }
  const {ctx,calls}=fixture();
  const bad={...ctx,jwtClaims:{...ctx.jwtClaims,aal:"aal1"}};
  assert.equal((await handleOwnerBootstrapIntent(bad,issueBody,{nowMs:T})).ok,false);
  assert.equal(calls.length,0);
  assert.equal(MAX_BODY_CHARS,4096);
});


test("Owner fresh AAL2 Edge status probe is read-only and never calls any RPC",async()=>{
  const {ctx,calls}=fixture();
  const reply=await handleOwnerBootstrapIntent(ctx,{
    intent:"status",workspace_id:WS
  },{nowMs:T});
  assert.deepEqual(reply,{
    ok:true,check:"qa_owner_aal2_edge_authenticated",
    db_calls_made:false,device_registration_performed:false,
    staff_access_granted:false,purchase_sync_started:false
  });
  assert.equal(calls.length,0);
});

test("AAL1, stale TOTP, other users, and malformed probe rejected without RPC",async()=>{
  const {ctx,calls}=fixture();
  const status={intent:"status",workspace_id:WS};
  const cases=[
    [{...ctx,jwtClaims:{...ctx.jwtClaims,aal:"aal1"}},status],
    [{...ctx,jwtClaims:{...ctx.jwtClaims,amr:[{method:"totp",timestamp:Math.floor(T/1000)-310}]}},status],
    [{...ctx,userClaims:{...ctx.userClaims,id:"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"},
      jwtClaims:{...ctx.jwtClaims,sub:"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"}},status],
    [ctx,{...status,staff_user_id:"b805598b-421f-447b-aa4c-ffb66f721338"}],
    [ctx,{...status,workspace_id:"ffffffff-ffff-4fff-8fff-ffffffffffff"}],
    [ctx,{...status,intent:"issue"}]
  ];
  for(const [who,what] of cases){
    const result=await handleOwnerBootstrapIntent(who,what,{nowMs:T});
    assert.equal(result.ok,false);
  }
  assert.equal(calls.length,0);
});
