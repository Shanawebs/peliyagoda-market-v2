// REVIEW ONLY. Authenticated Staff signed paginated Cloud reads.
// No live Edge deployment; service_role key stays only in server environment.
import { withSupabase } from "npm:@supabase/server@1.9.1";
import { verifyAndroidDeviceProof } from "../_shared/device_proof.mjs";

const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const types=new Set(["category","item","purchase","purchase_addition",
  "requisition","daily_cash_received","daily_other_expenses"]);
const signature=/^[A-Za-z0-9_-]{12,256}$/;
const denied=()=>Response.json({ok:false,error:"Staff signed pull denied"},
 {status:403,headers:{"Cache-Control":"no-store"}});
const success=(data:unknown)=>Response.json(data,
 {headers:{"Cache-Control":"no-store"}});
export default {
 fetch:withSupabase({auth:"user"},async(req,ctx)=>{
  if(req.method!=="POST")return new Response("Method not allowed",{status:405});
  const size=Number(req.headers.get("content-length")??0);
  if(!Number.isFinite(size)||size>4096)
    return new Response("Body too large",{status:413});
  const staff=ctx.userClaims?.id;
  const sid=(ctx.jwtClaims as Record<string,unknown>|null)?.session_id;
  if(typeof staff!=="string"||!uuid.test(staff)||
     typeof sid!=="string"||!uuid.test(sid))return denied();
  let i:Record<string,unknown>;
  try{
    const raw=await req.text();
    if(raw.length>4096)return denied();
    const decoded=JSON.parse(raw);
    if(!decoded||typeof decoded!=="object"||Array.isArray(decoded))
      return denied();
    i=decoded as Record<string,unknown>;
  }catch{return denied();}
  if(typeof i.workspace_id!=="string"||!uuid.test(i.workspace_id)||
    typeof i.device_id!=="string"||!uuid.test(i.device_id)||
    typeof i.entity_type!=="string"||!types.has(i.entity_type)||
    !Number.isSafeInteger(i.page_size)|| (i.page_size as number)<1||
    (i.page_size as number)>50)return denied();
  const timestamp=i.after_updated_at;
  const cloudId=i.after_id;
  const cursorAbsent=timestamp==null && cloudId==null;
  if(!cursorAbsent && (
    typeof timestamp!=="string"||timestamp.length>50||
    !Number.isFinite(Date.parse(timestamp))||
    typeof cloudId!=="string"||!uuid.test(cloudId)))return denied();
  try{
    const args={
      p_workspace_id:i.workspace_id,p_device_id:i.device_id,
      p_entity_type:i.entity_type,
      p_after_updated_at:timestamp??null,p_after_id:cloudId??null,
      p_page_size:i.page_size
    };
    if(i.action==="issue_read") {
      const {data,error}=await ctx.supabase.rpc(
        "marketpro_staff_issue_read_page_challenge",args);
      if(error||!data||data.purpose!=="read"||
        data.staff_user_id!==staff||data.session_id!==sid||
        data.workspace_id!==i.workspace_id||
        data.device_id!==i.device_id||
        typeof data.key_fingerprint_sha256!=="string"||
        !/^[a-f0-9]{64}$/.test(data.key_fingerprint_sha256))return denied();
      return success({ok:true,challenge:data});
    }
    if(i.action!=="submit_read" ||
      typeof i.challenge_id!=="string"||!uuid.test(i.challenge_id)||
      typeof i.signature_der_base64url!=="string"||
      !signature.test(i.signature_der_base64url))return denied();
    const signed={
      p_challenge_id:i.challenge_id,p_staff_user_id:staff,p_session_id:sid
    };
    const {data:material,error:matErr}=await ctx.supabaseAdmin.rpc(
      "marketpro_service_device_challenge_material",signed);
    if(matErr||!material||material.purpose!=="read"||
      material.challenge_id!==i.challenge_id||
      material.workspace_id!==i.workspace_id||
      material.device_id!==i.device_id||
      material.staff_user_id!==staff||
      material.session_id!==sid)return denied();
    if(!(await verifyAndroidDeviceProof(
      material,i.signature_der_base64url)))return denied();
    const {data:page,error:pullErr}=await ctx.supabaseAdmin.rpc(
      "marketpro_service_pull_signed_staff_page",{
        ...signed,...args
      });
    if(pullErr||!page||page.entity_type!==i.entity_type||
       !Array.isArray(page.rows)||
       typeof page.has_more!=="boolean"||
       typeof page.server_today_colombo!=="string"||
       typeof page.server_time_utc!=="string")return denied();
    // Return same JSON page shape expected by existing verified Room stager.
    // Do not return JWT, nonce, public key or a reusable signed ticket.
    return success(page);
  }catch{return denied();}
 })
};
