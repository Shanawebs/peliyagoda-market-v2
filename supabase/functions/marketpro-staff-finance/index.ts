// REVIEW ONLY: no backend deployment/Android network switch has occurred.
// Requires single reviewed SQL migration including ALL RLS and challenge
// protections and Owner-approved Staff public key enrollment.
import { withSupabase } from "npm:@supabase/server@1.9.1";
import { verifyAndroidDeviceProof } from "../_shared/device_proof.mjs";
import { financeRpcArgs,validUuid } from "../_shared/finance_wire.mjs";

const proofBase64=/^[A-Za-z0-9_-]{12,256}$/;
const reject=()=>Response.json({ok:false,error:"Staff finance request rejected"},
 {status:403,headers:{"Cache-Control":"no-store"}});
const safeResponse = (value:unknown) => Response.json(value,
 {headers:{"Cache-Control":"no-store"}});
export default {
 fetch:withSupabase({auth:"user"},async(req,ctx)=>{
  if(req.method!=="POST")return new Response("Method not allowed",{status:405});
  const size=Number(req.headers.get("content-length")??0);
  if(!Number.isFinite(size)||size>8192)
    return new Response("Payload too large",{status:413});
  const subject=ctx.userClaims?.id;
  const sessionId=(ctx.jwtClaims as Record<string,unknown>|null)?.session_id;
  if(!validUuid(subject)||!validUuid(sessionId))return reject();
  let input:Record<string,unknown>;
  try {
    const raw=await req.text();
    if(raw.length>8192)return reject();
    const parsed=JSON.parse(raw);
    if(!parsed||typeof parsed!=="object"||Array.isArray(parsed))
      return reject();
    input=parsed as Record<string,unknown>;
  }catch{return reject();}
  try {
    // The same immutable typed body is sent on issue and submit.
    const args=financeRpcArgs(input);
    if(input.action_type==="issue_finance") {
      const {data,error}=await ctx.supabase.rpc(
        "marketpro_staff_issue_finance_challenge",args
      );
      if(error||!data||data.purpose!=="write"||
        data.staff_user_id!==subject||data.session_id!==sessionId||
        data.device_id!==input.device_id ||
        data.workspace_id!==input.workspace_id)return reject();
      return safeResponse({ok:true,challenge:data});
    }
    if(input.action_type!=="submit_finance" ||
      !validUuid(input.challenge_id)||
      typeof input.signature_der_base64url!=="string"||
      !proofBase64.test(input.signature_der_base64url))return reject();

    // ONLY the two allowlisted privileged RPCs receive the service key.
    // Staff cannot call them from Android/PostgREST. First read checks
    // session binding, registered phone and Owner writer lease.
    const proofArgs={
      p_challenge_id:input.challenge_id,
      p_staff_user_id:subject,
      p_session_id:sessionId
    };
    const {data:material,error:materialError}=await ctx.supabaseAdmin.rpc(
      "marketpro_service_device_challenge_material",proofArgs
    );
    if(materialError||!material||material.purpose!=="write"||
      material.challenge_id!==input.challenge_id ||
      material.workspace_id!==input.workspace_id ||
      material.device_id!==input.device_id ||
      material.staff_user_id!==subject||
      material.session_id!==sessionId)return reject();

    if(!(await verifyAndroidDeviceProof(
      material,input.signature_der_base64url)))return reject();

    // ONE PostgreSQL transaction, not a separate proof-consume/finance pair.
    // SQL re-locks workspace, revalidates active Staff session/device/key,
    // compares server-side operation request commitment, consumes nonce and
    // applies exactly one optimistic revision-controlled financial change.
    const {data:receipt,error:writeError}=await ctx.supabaseAdmin.rpc(
      "marketpro_service_apply_proof_bound_finance",{
        p_challenge_id:input.challenge_id,
        p_staff_user_id:subject,
        p_session_id:sessionId,
        p_operation_id:args.p_operation_id,
        p_entity_type:args.p_entity_type,
        p_entity_id:args.p_entity_id,
        p_action:args.p_action,
        p_expected_revision:args.p_expected_revision,
        p_payload:args.p_payload
      }
    );
    if(writeError||receipt?.accepted!==true||
      receipt?.challenge_consumed!==true ||
      receipt?.operation_id!==input.operation_id||
      receipt?.entity_id!==input.entity_id ||
      !Number.isSafeInteger(receipt?.revision)||
      receipt.revision<1)return reject();
    return safeResponse({
      ok:true,accepted:true,operation_id:receipt.operation_id,
      revision:receipt.revision,replayed:receipt.replayed===true
    });
  } catch {
    // No server error detail, token, nonce, invoice contents or SQL leaks.
    return reject();
  }
 })
};
