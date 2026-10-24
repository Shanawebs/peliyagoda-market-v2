// REVIEW-ONLY Supabase Edge Function. No production deployment.
// Bootstrap a pending P-256 signing key ONLY after authenticated Staff
// activation. The Staff proves possession by signing the server challenge.
// Even valid possession is NOT Owner authorization: explicit Owner match
// of exact fingerprint is required by a separate audited SQL RPC.
import { withSupabase } from "npm:@supabase/server@1.9.1";
import { base64UrlBytes, verifyAndroidDeviceProof } from "../_shared/device_proof.mjs";

const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const b64Url=/^[A-Za-z0-9_-]{80,720}$/;
const sigRegex=/^[A-Za-z0-9_-]{12,256}$/;
const deny=()=>Response.json({ok:false,error:"Key enrollment not authorized"},
 {status:403,headers:{"Cache-Control":"no-store"}});
const respond=(body:unknown)=>Response.json(body,
 {headers:{"Cache-Control":"no-store"}});
function normalize(bytes:Uint8Array):string {
 let text="";
 for(const b of bytes)text+=String.fromCharCode(b);
 return btoa(text);
}
export default {
 fetch:withSupabase({auth:"user"},async(req,ctx)=>{
  if(req.method!=="POST")return new Response("Method not allowed",{status:405});
  const declaredSize=Number(req.headers.get("content-length")??0);
  if(!Number.isFinite(declaredSize)||declaredSize>4096)
    return new Response("Too large",{status:413});
  const staff=ctx.userClaims?.id;
  const session=(ctx.jwtClaims as Record<string,unknown>|null)?.session_id;
  if(typeof staff!=="string"||!uuid.test(staff)||
     typeof session!=="string"||!uuid.test(session))return deny();
  let input:Record<string,unknown>;
  try{
    const raw=await req.text();
    if(raw.length>4096)return deny();
    const parsed=JSON.parse(raw);
    if(!parsed||typeof parsed!=="object"||Array.isArray(parsed))return deny();
    input=parsed as Record<string,unknown>;
  }catch{return deny();}
  try{
    if(input.action==="begin"){
      if(typeof input.workspace_id!=="string"||!uuid.test(input.workspace_id)||
         typeof input.device_id!=="string"||!uuid.test(input.device_id)||
         typeof input.spki_base64url!=="string"||
         !b64Url.test(input.spki_base64url))return deny();
      const keyBytes=base64UrlBytes(input.spki_base64url);
      if(keyBytes.length<64||keyBytes.length>512)return deny();
      // Only actual SPKI P-256 public keys, not arbitrary 100-byte content.
      await crypto.subtle.importKey(
         "spki",keyBytes,{name:"ECDSA",namedCurve:"P-256"},false,["verify"]);
      const {data,error}=await ctx.supabase.rpc(
        "marketpro_staff_begin_key_enrollment",{
          p_workspace_id:input.workspace_id,
          p_device_id:input.device_id,
          p_spki_base64:normalize(keyBytes)
        });
      if(error||!data||data.purpose!=="pair"||
         data.staff_user_id!==staff||data.session_id!==session||
         data.device_id!==input.device_id||
         data.workspace_id!==input.workspace_id)return deny();
      return respond({ok:true,challenge:data});
    }
    if(input.action==="prove"){
      if(typeof input.challenge_id!=="string"||
         !uuid.test(input.challenge_id)||
         typeof input.signature_der_base64url!=="string"||
         !sigRegex.test(input.signature_der_base64url))return deny();
      const args={
        p_challenge_id:input.challenge_id,
        p_staff_user_id:staff,
        p_session_id:session
      };
      const {data:material,error:err}=await ctx.supabaseAdmin.rpc(
        "marketpro_service_key_enrollment_material",args);
      if(err||!material||material.purpose!=="pair"||
         material.challenge_id!==input.challenge_id||
         material.staff_user_id!==staff||material.session_id!==session)
         return deny();
      if(!(await verifyAndroidDeviceProof(
        material,input.signature_der_base64url)))return deny();
      const {data:proof,error:proofErr}=await ctx.supabaseAdmin.rpc(
        "marketpro_service_prove_key_enrollment",args);
      if(proofErr||proof?.proof_of_possession_verified!==true||
         proof?.key_pending_owner_approval!==true||
         proof?.challenge_id!==input.challenge_id||
         proof?.key_fingerprint_sha256!==material.key_fingerprint_sha256)
         return deny();
      return respond({
         ok:true,proof_of_possession_verified:true,
         key_pending_owner_approval:true,
         key_fingerprint_sha256:proof.key_fingerprint_sha256
      });
    }
    return deny();
  }catch{return deny();}
 })
};
