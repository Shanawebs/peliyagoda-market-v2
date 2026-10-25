// REVIEW ONLY: do NOT deploy before Owner-controlled key enrollment,
// direct REST restrictions and two-physical-phone proof+finance end-to-end QA.
// This one-purpose Edge endpoint supports only PURCHASES. All other financial
// record types intentionally fail closed until implemented and audited.
import {withSupabase} from "npm:@supabase/server@1.9.1";
import {verifySignedPurchase} from "../_shared/proof_bound_purchase.mjs";

const UUID=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const B64URL=/^[A-Za-z0-9_-]{12,256}$/;
const deny=()=>Response.json({ok:false,error:"Signed purchase rejected"},
 {status:403,headers:{"Cache-Control":"no-store"}});
const noCache={"Cache-Control":"no-store"};

export default {
 fetch:withSupabase({auth:"user"},async(req,ctx)=>{
  if(req.method!=="POST")return new Response("Method not allowed",{status:405});
  if(Number(req.headers.get("content-length")??0)>4096)
   return new Response("Request too large",{status:413});
  const user=ctx.userClaims?.id;
  const jwt=ctx.jwtClaims as Record<string,unknown>|null;
  const session=jwt?.session_id;
  if(typeof user!=="string"||!UUID.test(user)||
    typeof session!=="string"||!UUID.test(session))return deny();
  let data:Record<string,unknown>;
  try {
   const raw=await req.text();
   if(raw.length>4096) return new Response("Request too large",{status:413});
   const value=JSON.parse(raw);
   if(!value||typeof value!=="object"||Array.isArray(value))return deny();
   data=value as Record<string,unknown>;
  }catch{return deny();}
  try {
   if(data.intent==="issue"){
    if(typeof data.workspace_id!=="string"||!UUID.test(data.workspace_id)||
       typeof data.device_id!=="string"||!UUID.test(data.device_id)||
       typeof data.operation_id!=="string"||!UUID.test(data.operation_id)||
       typeof data.entity_id!=="string"||!UUID.test(data.entity_id)||
       (data.purchase_action!=="create"&&data.purchase_action!=="correct")||
       !data.payload||typeof data.payload!=="object"||
       Array.isArray(data.payload))return deny();
    const p=data.payload as Record<string,unknown>;
    const fields=["business_date","item_id",
      "quantity_grams","price_cents_per_kg"];
    if(Object.keys(p).sort().join(",")!==fields.sort().join(",") ||
       typeof p.business_date!=="string" ||
       !/^\d{4}-\d{2}-\d{2}$/.test(p.business_date) ||
       typeof p.item_id!=="string"||!UUID.test(p.item_id)||
       !Number.isSafeInteger(p.quantity_grams)||
       !Number.isSafeInteger(p.price_cents_per_kg)||
       Number(p.quantity_grams)<0||Number(p.quantity_grams)>1000000000||
       Number(p.price_cents_per_kg)<0||Number(p.price_cents_per_kg)>1000000000)
      return deny();
    const expected=data.expected_revision??null;
    if(!((data.purchase_action==="create" && expected===null)||
      (data.purchase_action==="correct" &&
       Number.isSafeInteger(expected) && Number(expected)>0)))return deny();
    const {data:challenge,error}=await ctx.supabase.rpc(
      "marketpro_staff_issue_purchase_challenge",{
       p_workspace_id:data.workspace_id,p_device_id:data.device_id,
       p_operation_id:data.operation_id,p_entity_id:data.entity_id,
       p_action:data.purchase_action,p_expected_revision:expected,
       p_payload:p
      });
    if(error||!challenge||challenge.staff_user_id!==user||
       challenge.session_id!==session ||
       challenge.proof_version!=="marketpro-device-proof-purchase-v2")
      return deny();
    return Response.json({ok:true,challenge},{headers:noCache});
   }
   if(data.intent==="commit"){
    if(typeof data.challenge_id!=="string"||
       !UUID.test(data.challenge_id)||
       typeof data.signature_der_base64url!=="string"||
       !B64URL.test(data.signature_der_base64url))return deny();
    // This privileged lookup rechecks current linked JWT session, active
    // paired physical phone, Owner lease, non-revoked key and rollout gate.
    const claims={
      p_challenge_id:data.challenge_id,
      p_staff_user_id:user,p_session_id:session
    };
    const {data:material,error:lookupError}=await ctx.supabaseAdmin.rpc(
      "marketpro_service_purchase_proof_material",claims);
    if(lookupError||!material ||
       material.challenge_id!==data.challenge_id ||
       material.staff_user_id!==user || material.session_id!==session ||
       !await verifySignedPurchase(
         material,data.signature_der_base64url as string))
      return deny();
    // No separate "proof verified" grant. Exactly ONE service-role-only SQL
    // transaction consumes the challenge and writes the bound purchase.
    // Never use client-supplied operation fields in this commit phase.
    const {data:receipt,error:commitError}=await ctx.supabaseAdmin.rpc(
      "marketpro_service_commit_signed_purchase",{
       ...claims,p_workspace_id:material.workspace_id,
       p_device_id:material.device_id,p_operation_id:material.operation_id,
       p_entity_id:material.entity_id,p_action:material.financial_action,
       p_expected_revision:material.expected_revision,
       p_payload:material.operation_payload
      });
    if(commitError||receipt?.accepted!==true||
       receipt?.proof_consumed_in_financial_transaction!==true||
       receipt?.operation_id!==material.operation_id)
      return deny();
    return Response.json({
      ok:true,accepted:true,operation_id:receipt.operation_id,
      revision:receipt.revision,replayed:receipt.replayed===true
    },{headers:noCache});
   }
  }catch{ /* do not leak claims, SPKI, ciphertext or server SQL errors */ }
  return deny();
 })
};
