// REVIEW ONLY. Authenticated Staff staged candidate key, NOT a writer grant.
import {withSupabase} from "npm:@supabase/server@1.9.1";
import {verifyEnrollmentKeyPossession} from "../_shared/staff_key_enrollment_proof.mjs";
const UUID=/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
const B64URL=/^[A-Za-z0-9_-]{12,800}$/;
const headers={"Cache-Control":"no-store"};
const deny=()=>Response.json({ok:false,error:"Key enrollment denied"},
    {status:403,headers});

export default {
 fetch:withSupabase({auth:"user"},async(req,ctx)=>{
  if(req.method!=="POST")return new Response("Method not allowed",{status:405});
  if(Number(req.headers.get("content-length")??0)>4096)
   return new Response("Payload too large",{status:413});
  const user=ctx.userClaims?.id;
  const jwt=ctx.jwtClaims as Record<string,unknown>|null;
  const session=jwt?.session_id;
  if(typeof user!=="string"||!UUID.test(user)||
    typeof session!=="string"||!UUID.test(session))return deny();
  let body:Record<string,unknown>;
  try{
   const raw=await req.text();
   if(raw.length>4096)return new Response("Payload too large",{status:413});
   const parsed=JSON.parse(raw);
   if(!parsed||typeof parsed!=="object"||Array.isArray(parsed))return deny();
   body=parsed as Record<string,unknown>;
  }catch{return deny();}
  try{
   if(body.intent==="issue"){
    if(typeof body.workspace_id!=="string"||!UUID.test(body.workspace_id)||
       typeof body.device_id!=="string"||!UUID.test(body.device_id)||
       typeof body.spki_base64url!=="string"||
       !B64URL.test(body.spki_base64url))return deny();
    // The public key is not secret; the private signing key NEVER leaves
    // AndroidKeyStore. PostgreSQL derives fingerprint from these bytes.
    const b64=body.spki_base64url.replace(/-/g,"+").replace(/_/g,"/");
    const padded=b64.padEnd(Math.ceil(b64.length/4)*4,"=");
    const {data,error}=await ctx.supabase.rpc(
      "marketpro_staff_issue_key_enrollment",{
        p_workspace_id:body.workspace_id,p_device_id:body.device_id,
        p_spki_base64:padded
      });
    if(error||!data||data.staff_user_id!==user||
       data.session_id!==session||
       data.device_id!==body.device_id||
       data.proof_version!=="marketpro-staff-key-enrollment-v1")
      return deny();
    return Response.json({
       ok:true,challenge:data,owner_approval_granted:false
    },{headers});
   }
   if(body.intent==="verify"){
    if(typeof body.challenge_id!=="string"||!UUID.test(body.challenge_id)||
       typeof body.signature_der_base64url!=="string"||
       !B64URL.test(body.signature_der_base64url))return deny();
    const args={
      p_challenge_id:body.challenge_id,
      p_staff_user_id:user,p_session_id:session
    };
    const {data:material,error:lookupError}=await ctx.supabaseAdmin.rpc(
      "marketpro_service_key_enrollment_material",args);
    if(lookupError||!material||
       material.challenge_id!==body.challenge_id||
       material.staff_user_id!==user||material.session_id!==session||
       !await verifyEnrollmentKeyPossession(
         material,body.signature_der_base64url))return deny();
    const {data,error}=await ctx.supabaseAdmin.rpc(
      "marketpro_service_stage_verified_staff_key",args);
    if(error||data?.proof_verified!==true||
       data?.owner_approval_granted!==false)return deny();
    return Response.json({
       ok:true,proof_verified:true,
       owner_approval_granted:false,
       fingerprint_sha256:data.fingerprint_sha256,
       device_id:data.device_id
    },{headers});
   }
  }catch{/* No JWT, signature, nonce, SPKI or private DB errors in reply */}
  return deny();
 })
};
