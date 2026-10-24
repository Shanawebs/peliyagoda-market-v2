// Market Pro V3.7.5 REVIEW ONLY. DO NOT DEPLOY UNTIL STAFF RLS and
// full server/Android cutover are security-reviewed. Never log JWTs,
// SPKI, invitation codes, ECDSA signatures, or challenge nonces.
import { withSupabase } from "npm:@supabase/server@1.9.1";
import { verifyAndroidDeviceProof } from "../_shared/device_proof.mjs";

const UUID=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const B64URL=/^[A-Za-z0-9_-]{12,256}$/;
const genericError=()=>Response.json({ok:false,error:"Device proof rejected"},
  {status:403,headers:{"Cache-Control":"no-store"}});

export default {
 fetch: withSupabase({auth:"user"},async(req,ctx)=>{
  if(req.method!=="POST")return new Response("Method not allowed",{status:405});
  const length=Number(req.headers.get("content-length")??0);
  if(!Number.isFinite(length)||length>4096)return new Response("Request too large",{status:413});
  const claims=ctx.jwtClaims as Record<string,unknown>|null;
  const sessionId=claims?.session_id;
  const subject=ctx.userClaims?.id;
  if(typeof subject!=="string"||!UUID.test(subject)||
     typeof sessionId!=="string"||!UUID.test(sessionId))
    return genericError();
  let input:Record<string,unknown>;
  try{
   const raw=await req.text();
   if(raw.length>4096)return new Response("Request too large",{status:413});
   const parsed=JSON.parse(raw);
   if(!parsed||typeof parsed!=="object"||Array.isArray(parsed))
    return genericError();
   input=parsed as Record<string,unknown>;
  }catch{return genericError();}

  if(input.action==="issue"){
   if(typeof input.workspace_id!=="string"||!UUID.test(input.workspace_id)||
      typeof input.device_id!=="string"||!UUID.test(input.device_id)||
      (input.purpose!=="read"&&input.purpose!=="write"))
     return genericError();
   // Caller-scoped Supabase client; SQL rechecks auth.uid(), current
   // JWT session row, Staff membership/device, and Owner-approved key.
   const {data,error}=await ctx.supabase.rpc(
     "marketpro_staff_issue_device_challenge",{
       p_workspace_id:input.workspace_id,
       p_device_id:input.device_id,
       p_purpose:input.purpose
     });
   if(error||!data||data.staff_user_id!==subject||
       data.session_id!==sessionId)return genericError();
   return Response.json({ok:true,challenge:data},
      {headers:{"Cache-Control":"no-store"}});
  }

  if(input.action==="verify"){
   if(typeof input.challenge_id!=="string"||!UUID.test(input.challenge_id)||
      typeof input.signature_der_base64url!=="string"||
      !B64URL.test(input.signature_der_base64url))return genericError();
   // Admin client is used ONLY for service_role-only allowlisted RPCs.
   // The caller never supplies staff_user_id/session_id (signed JWT only).
   const args={
      p_challenge_id:input.challenge_id,
      p_staff_user_id:subject,
      p_session_id:sessionId
   };
   const {data:material,error:materialError}=await ctx.supabaseAdmin.rpc(
     "marketpro_service_device_challenge_material",args);
   if(materialError||!material||material.challenge_id!==input.challenge_id||
      material.staff_user_id!==subject||
      material.session_id!==sessionId)return genericError();
   const verified=await verifyAndroidDeviceProof(
      material,input.signature_der_base64url);
   if(!verified)return genericError();
   // Atomic, single-use finalization re-checks the Staff binding,
   // live auth.sessions, key approval, device and Owner write lease.
   const {data:result,error:consumeError}=await ctx.supabaseAdmin.rpc(
      "marketpro_service_consume_device_challenge",args);
   if(consumeError||result?.proof_verified_once!==true||
      result?.business_write_permission_granted!==false)
     return genericError();
   return Response.json({
     ok:true,proof_verified_once:true,
     business_write_permission_granted:false
   },{headers:{"Cache-Control":"no-store"}});
  }
  return genericError();
 })
};
