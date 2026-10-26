// Isolated Staging Owner PENDING registration Edge handler.
// No phone activation, permissions, Staff invites or financial writes.
import {validOwnerBootstrapClaims} from "./owner_bootstrap_edge_core.mjs";
import {verifyPendingOwnerSignature,PENDING_OWNER_VERSION} from "./pending_owner_proof.mjs";
const WS="af77cb66-76b2-492a-a460-06132f468713";
const OWNER="98c3b101-d452-4b6b-902c-0b1a31fc340c";
const UUID=/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
const B64=/^[A-Za-z0-9_-]{85,800}$/;
const deny={ok:false,error:"Pending Owner registration denied"};
function record(v){return !!v && typeof v==="object" && !Array.isArray(v)}
function allowed(v,fields){return Object.keys(v).every(k=>fields.includes(k))}
export async function handlePendingOwnerIntent(ctx,payload,
 {nowMs=Date.now(),verifySignature=verifyPendingOwnerSignature}={}) {
 if(!validOwnerBootstrapClaims(ctx,nowMs) ||
  !record(payload) || payload.workspace_id!==WS ||
  ctx.userClaims.id!==OWNER || ctx.jwtClaims.sub!==OWNER ||
  !UUID.test(ctx.jwtClaims.session_id))return deny;
 const owner=ctx.userClaims.id,session=ctx.jwtClaims.session_id;

 if(payload.intent==="pending_issue" &&
  allowed(payload,["intent","workspace_id","device_id","spki_base64url","explicit_consent"]) &&
  payload.explicit_consent===true &&
  UUID.test(payload.device_id) &&
  typeof payload.spki_base64url==="string" &&
  B64.test(payload.spki_base64url)) {
   const publicKey=payload.spki_base64url.replace(/-/g,"+").replace(/_/g,"/");
   const b64=publicKey.padEnd(Math.ceil(publicKey.length/4)*4,"=");
   const {data,error}=await ctx.supabaseAdmin.rpc(
    "marketpro_qa_service_pending_owner_issue",{
       p_owner:owner,p_session:session,p_device:payload.device_id,p_spki_base64:b64
    });
   if(error || !record(data) || data.proof_version!==PENDING_OWNER_VERSION ||
     data.workspace_id!==WS || data.owner_user_id!==owner ||
     data.session_id!==session || data.device_id!==payload.device_id ||
     !UUID.test(data.challenge_id) ||
     data.registration_status!=="not_registered" ||
     data.device_activated!==false || data.staff_access!==false)return deny;
   return {ok:true,challenge:data,registration_status:"not_registered",
       device_activated:false,staff_access:false};
 }
 if(payload.intent==="pending_verify" &&
  allowed(payload,["intent","workspace_id","challenge_id","signature_der_base64url"]) &&
  UUID.test(payload.challenge_id) &&
  typeof payload.signature_der_base64url==="string" &&
  B64.test(payload.signature_der_base64url)) {
   const args={p_challenge:payload.challenge_id,p_owner:owner,p_session:session};
   const {data:material,error}=await ctx.supabaseAdmin.rpc(
    "marketpro_qa_service_pending_owner_material",args);
   if(error || !record(material) || material.proof_version!==PENDING_OWNER_VERSION ||
      material.workspace_id!==WS || material.owner_user_id!==owner ||
      material.session_id!==session || material.challenge_id!==payload.challenge_id ||
      !UUID.test(material.device_id))return deny;
   if(!await verifySignature(material,payload.signature_der_base64url,nowMs))return deny;
   const {data:receipt,error:commitError}=await ctx.supabaseAdmin.rpc(
    "marketpro_qa_service_pending_owner_commit",args);
   if(commitError || !record(receipt) || receipt.ok!==true ||
       receipt.registration_status!=="pending" ||
       receipt.owner_user_id!==owner || receipt.device_id!==material.device_id ||
       receipt.fingerprint_sha256!==material.fingerprint_sha256 ||
       receipt.nonce_consumed_once!==true ||
       receipt.device_activated!==false ||
       receipt.hardware_attestation_verified!==false ||
       receipt.can_write!==false || receipt.staff_access!==false)return deny;
   return {ok:true,registration_status:"pending",
     device_activated:false,hardware_attestation_verified:false,
     can_write:false,staff_access:false,purchase_sync:false};
 }
 return deny;
}
