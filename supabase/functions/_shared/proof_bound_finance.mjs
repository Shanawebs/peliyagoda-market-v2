// Exact signed Staff finance v3 transcript (16 lines, type-bound).
// Distinct domain from V375 generic read/write and signed Purchase V2.
import {
 canonicalStaffProofTranscript,base64UrlBytes,derP256ToRaw64
} from "./device_proof.mjs";

export const FINANCIAL_TYPES=Object.freeze([
  "requisition","purchase_addition","daily_cash_received","daily_other_expenses"
]);
const VERSION="marketpro-device-proof-financial-v3";
const UUID=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const HEX64=/^[0-9a-f]{64}$/;

export function financeV3Transcript(material,nowMs=Date.now()) {
 const parent=new TextDecoder().decode(
   canonicalStaffProofTranscript(material,nowMs)).split("\n");
 if(parent.length!==10||material.purpose!=="write"||
    material.proof_version!==VERSION||
    !FINANCIAL_TYPES.includes(material.entity_type)||
    !UUID.test(material.operation_id)||!UUID.test(material.entity_id)||
    !["create","correct"].includes(material.financial_action)||
    !HEX64.test(material.operation_sha256)||
    !((material.financial_action==="create" && material.expected_revision===null)||
      (material.financial_action==="correct" &&
       Number.isSafeInteger(material.expected_revision) &&
       material.expected_revision>0)))
  throw new Error("Invalid exact financial challenge");
 parent[0]=VERSION;
 return new TextEncoder().encode([
    ...parent,material.entity_type,material.operation_id,
    material.entity_id,material.financial_action,
    material.expected_revision===null?"null":String(material.expected_revision),
    material.operation_sha256
 ].join("\n"));
}

export async function verifySignedFinancialV3(material,signature,nowMs=Date.now()) {
 try{
  const transcript=financeV3Transcript(material,nowMs);
  const b64=material.spki_base64;
  if(typeof b64!=="string"||b64.length>800||
     !/^[A-Za-z0-9+/\s]{1,800}={0,2}$/.test(b64))return false;
  const spki=Uint8Array.from(atob(b64.replace(/\s/g,"")),c=>c.charCodeAt(0));
  if(spki.length<64||spki.length>512)return false;
  const sig=derP256ToRaw64(base64UrlBytes(signature));
  const key=await crypto.subtle.importKey(
    "spki",spki,{name:"ECDSA",namedCurve:"P-256"},false,["verify"]);
  return crypto.subtle.verify(
    {name:"ECDSA",hash:"SHA-256"},key,sig,transcript);
 } catch{return false}
}
