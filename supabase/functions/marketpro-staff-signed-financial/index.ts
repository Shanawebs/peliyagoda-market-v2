// REVIEW-ONLY signed finance endpoint for FOUR exact types (Purchase stays v2).
// NEVER DEPLOY until Owner-approved Staff key registration, restricted RLS,
// attached Android Worker transport, real paired JWT and 2-phone QA pass.
import {withSupabase} from "npm:@supabase/server@1.9.1";
import {
 FINANCIAL_TYPES,verifySignedFinancialV3
} from "../_shared/proof_bound_finance.mjs";

const UUID=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const B64URL=/^[A-Za-z0-9_-]{12,256}$/;
const headers={"Cache-Control":"no-store"};
const denied=()=>Response.json({ok:false,error:"Financial signature rejected"},
 {status:403,headers});
const specs:Record<string,string[]>={
 requisition:["business_date","item_id","kitchen","quantity_grams"],
 purchase_addition:["business_date","item_id","quantity_grams","price_cents_per_kg"],
 daily_cash_received:["business_date","amount_cents"],
 daily_other_expenses:["business_date","transport_cents","labor_charge_cents",
  "meals_drinks_cents","others_cents","remarks"]
};
function validate(type:string,p:unknown):p is Record<string,unknown>{
 if(!p||typeof p!=="object"||Array.isArray(p)||!specs[type])return false;
 const v=p as Record<string,unknown>;
 const expected=specs[type].slice().sort().join(",");
 if(Object.keys(v).sort().join(",")!==expected)return false;
 if(typeof v.business_date!=="string"||
    !/^\d{4}-\d{2}-\d{2}$/.test(v.business_date))return false;
 if("item_id" in v &&
    (typeof v.item_id!=="string"||!UUID.test(v.item_id)))return false;
 if("kitchen" in v &&
   !["Main Kitchen","Indian Kitchen","Pastry Kitchen"].includes(String(v.kitchen)))
  return false;
 if("remarks" in v &&
    (typeof v.remarks!=="string"||v.remarks.length>500||
     v.remarks.includes("\u0000")))return false;
 for(const [key,val] of Object.entries(v)){
  if(!key.endsWith("_cents") && key!=="amount_cents" &&
     key!=="quantity_grams" && key!=="price_cents_per_kg")continue;
  const max=key==="quantity_grams"||key==="price_cents_per_kg"
    ?1_000_000_000:9_999_999_999;
  if(typeof val!=="number"||!Number.isSafeInteger(val)||
     val<0||val>max)return false;
 }
 return true;
}

export default {
 fetch:withSupabase({auth:"user"},async(req,ctx)=>{
  if(req.method!=="POST")return new Response("Method not allowed",{status:405});
  if(Number(req.headers.get("content-length")??0)>4096)
    return new Response("Request too large",{status:413});
  const staff=ctx.userClaims?.id;
  const claims=ctx.jwtClaims as Record<string,unknown>|null;
  const sid=claims?.session_id;
  if(typeof staff!=="string"||!UUID.test(staff)||
     typeof sid!=="string"||!UUID.test(sid))return denied();
  let request:Record<string,unknown>;
  try {
    const body=await req.text();
    if(body.length>4096)return new Response("Request too large",{status:413});
    const parsed=JSON.parse(body);
    if(!parsed||typeof parsed!=="object"||Array.isArray(parsed))
     return denied();
    request=parsed as Record<string,unknown>;
  }catch{return denied()}

  try {
   if(request.intent==="issue"){
    const type=request.entity_type;
    if(typeof type!=="string"||!FINANCIAL_TYPES.includes(type)||
       typeof request.workspace_id!=="string"||!UUID.test(request.workspace_id)||
       typeof request.device_id!=="string"||!UUID.test(request.device_id)||
       typeof request.operation_id!=="string"||!UUID.test(request.operation_id)||
       typeof request.entity_id!=="string"||!UUID.test(request.entity_id)||
       (request.financial_action!=="create"&&request.financial_action!=="correct")||
       !validate(type,request.payload))return denied();
    const revision=request.expected_revision??null;
    if(!((request.financial_action==="create" && revision===null)||
       (request.financial_action==="correct"&&
        Number.isSafeInteger(revision)&&Number(revision)>0)))return denied();
    const {data,error}=await ctx.supabase.rpc(
      "marketpro_staff_issue_financial_challenge",{
        p_workspace_id:request.workspace_id,p_device_id:request.device_id,
        p_operation_id:request.operation_id,p_entity_type:type,
        p_entity_id:request.entity_id,p_action:request.financial_action,
        p_expected_revision:revision,p_payload:request.payload
      });
    if(error||!data||data.staff_user_id!==staff||
       data.session_id!==sid ||data.entity_type!==type||
       data.proof_version!=="marketpro-device-proof-financial-v3")
      return denied();
    return Response.json({ok:true,challenge:data},{headers});
   }
   if(request.intent==="commit"){
    if(typeof request.challenge_id!=="string"||!UUID.test(request.challenge_id)||
      typeof request.signature_der_base64url!=="string"||
      !B64URL.test(request.signature_der_base64url))return denied();
    const ids={
      p_challenge_id:request.challenge_id,
      p_staff_user_id:staff,
      p_session_id:sid
    };
    const {data:material,error:lookupError}=await ctx.supabaseAdmin.rpc(
      "marketpro_service_financial_proof_material",ids);
    if(lookupError||!material||
       material.challenge_id!==request.challenge_id||
       material.staff_user_id!==staff||material.session_id!==sid||
       !await verifySignedFinancialV3(
         material,request.signature_der_base64url as string))return denied();
    // Only the server's previously signed challenge material is used.
    // The Staff can NOT pass arbitrary tables, amounts or revisions to commit.
    const {data:receipt,error:commitError}=await ctx.supabaseAdmin.rpc(
      "marketpro_service_commit_signed_financial",{
        ...ids,
        p_workspace_id:material.workspace_id,p_device_id:material.device_id,
        p_operation_id:material.operation_id,p_entity_type:material.entity_type,
        p_entity_id:material.entity_id,p_action:material.financial_action,
        p_expected_revision:material.expected_revision,
        p_payload:material.operation_payload
      });
    if(commitError||receipt?.accepted!==true||
       receipt?.proof_consumed_in_financial_transaction!==true||
       receipt?.operation_id!==material.operation_id||
       receipt?.entity_type!==material.entity_type)return denied();
    return Response.json({
      ok:true,accepted:true,operation_id:receipt.operation_id,
      revision:receipt.revision,replayed:receipt.replayed===true,
      entity_type:receipt.entity_type
    },{headers});
   }
  }catch{/* Don't surface JWT, nonce, signature, SPKI or SQL errors */}
  return denied();
 })
};
