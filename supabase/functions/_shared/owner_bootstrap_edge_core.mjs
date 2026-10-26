// Market Pro TWO-PHONE staging: auth-gated Owner P-256 bootstrap service core.
// REVIEW ONLY — no hosted Edge deployment, no enabled rollout gate.
// Server receives PUBLIC SPKI and DER signature, never Android's private key.
// All identity/session fields are derived from VERIFIED JWT, never request JSON.
import {verifyOwnerBootstrapKeyPossession, OWNER_BOOTSTRAP_VERSION} from "./owner_bootstrap_key_proof.mjs";

const UUID=/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
const B64URL=/^[A-Za-z0-9_-]{12,800}$/;
const STAGING_WORKSPACE="af77cb66-76b2-492a-a460-06132f468713";
const AUTH_FRESH_SECONDS=300;
export const MAX_BODY_CHARS=4096;

function isPlainRecord(x) {
  return !!x && typeof x==="object" && !Array.isArray(x);
}
function validJwtContext(ctx,nowMs) {
  const identity=ctx?.userClaims;
  const jwt=ctx?.jwtClaims;
  if (!isPlainRecord(identity) || !isPlainRecord(jwt) ||
    identity.role!=="authenticated" || !UUID.test(identity.id) ||
    jwt.sub!==identity.id || !UUID.test(jwt.session_id) ||
    jwt.aal!=="aal2" || jwt.is_anonymous===true ||
    !Array.isArray(jwt.amr) || !Number.isSafeInteger(nowMs) || nowMs<=0) {
    return null;
  }
  const nowSec=Math.floor(nowMs/1000);
  if (!jwt.amr.some(m=>isPlainRecord(m) && m.method==="totp" &&
      Number.isSafeInteger(m.timestamp) &&
      m.timestamp>=nowSec-AUTH_FRESH_SECONDS &&
      m.timestamp<=nowSec+15)) {
    return null;
  }
  return {owner:identity.id,session:jwt.session_id};
}
export function validOwnerBootstrapClaims(ctx,nowMs=Date.now()) {
  return validJwtContext(ctx,nowMs)!==null;
}
function hasOnlyKeys(payload,allowed) {
  return Object.keys(payload).every(k=>allowed.includes(k));
}
export async function handleOwnerBootstrapIntent(
  ctx,payload,{verifySignature=verifyOwnerBootstrapKeyPossession,nowMs=Date.now()}={}
) {
  const denied={ok:false,error:"Owner bootstrap denied"};
  const caller=validJwtContext(ctx,nowMs);
  if(!caller || !isPlainRecord(payload) ||
    payload.workspace_id!==STAGING_WORKSPACE) return denied;

  if(payload.intent==="issue" &&
    hasOnlyKeys(payload,["intent","workspace_id","device_id","spki_base64url"]) &&
    UUID.test(payload.device_id) && typeof payload.spki_base64url==="string" &&
    B64URL.test(payload.spki_base64url)) {
    const spki=payload.spki_base64url.replace(/-/g,"+").replace(/_/g,"/");
    const padded=spki.padEnd(Math.ceil(spki.length/4)*4,"=");
    // IMPORTANT: user-scoped RPC MUST be denied until reviewed server grant
    // and disabled private bootstrap gate are deliberately activated.
    const {data,error}=await ctx.supabase.rpc(
      "marketpro_qa_owner_issue_bootstrap",{
        p_workspace:STAGING_WORKSPACE,p_device:payload.device_id,
        p_spki_base64:padded
      });
    if(error || !isPlainRecord(data) ||
       data.proof_version!==OWNER_BOOTSTRAP_VERSION ||
       data.workspace_id!==STAGING_WORKSPACE ||
       data.owner_user_id!==caller.owner ||
       data.session_id!==caller.session ||
       data.device_id!==payload.device_id ||
       data.phone_activated!==false) return denied;
    return {ok:true,challenge:data,phone_activated:false,staff_activated:false};
  }

  if(payload.intent==="verify" &&
    hasOnlyKeys(payload,["intent","workspace_id","challenge_id","signature_der_base64url"]) &&
    UUID.test(payload.challenge_id) &&
    typeof payload.signature_der_base64url==="string" &&
    B64URL.test(payload.signature_der_base64url)) {
    // Only this trusted Edge handler uses server-admin client; never transmit
    // service-role keys to Android. RPC material is authoritatively bound to
    // the JWT-derived Owner and session.
    const args={
      p_challenge:payload.challenge_id,
      p_owner:caller.owner,p_session:caller.session
    };
    const {data:material,error:lookErr}=await ctx.supabaseAdmin.rpc(
      "marketpro_qa_service_owner_bootstrap_material",args
    );
    if(lookErr || !isPlainRecord(material) ||
       material.proof_version!==OWNER_BOOTSTRAP_VERSION ||
       material.workspace_id!==STAGING_WORKSPACE ||
       material.owner_user_id!==caller.owner ||
       material.session_id!==caller.session ||
       material.challenge_id!==payload.challenge_id ||
       !UUID.test(material.device_id)) return denied;

    if(!await verifySignature(material,payload.signature_der_base64url,nowMs))
      return denied;

    const {data:receipt,error:commitErr}=await ctx.supabaseAdmin.rpc(
      "marketpro_qa_service_owner_bootstrap_commit",args
    );
    if(commitErr || !isPlainRecord(receipt) ||
       receipt.proof_verified_and_consumed!==true ||
       receipt.device_id!==material.device_id ||
       receipt.fingerprint_sha256!==material.fingerprint_sha256 ||
       receipt.phone_activated!==false ||
       receipt.can_write!==false) return denied;
    return {ok:true,proof_verified_and_consumed:true,
      phone_activated:false,staff_activated:false,writer_enabled:false};
  }
  return denied;
}
