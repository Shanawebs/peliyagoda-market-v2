// REVIEW ONLY. Server signature verification binds a 60s one-use challenge
// to one SPECIFIC purchase payload+operation. This is NOT the V375 generic
// "proof_verified_once" transcript (which grants no business rights).
import {
  canonicalStaffProofTranscript,base64UrlBytes,derP256ToRaw64
} from "./device_proof.mjs";

const UUID=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const HEX64=/^[0-9a-f]{64}$/;
const VERSION="marketpro-device-proof-purchase-v2";

export function purchaseSignatureTranscript(material,nowMs=Date.now()) {
  const v1=canonicalStaffProofTranscript(material,nowMs);
  const base=new TextDecoder().decode(v1).split("\n");
  if(base.length!==10 || material.purpose!=="write" ||
     material.proof_version!==VERSION ||
     !UUID.test(material.operation_id) || !UUID.test(material.entity_id) ||
     !["create","correct"].includes(material.financial_action) ||
     !HEX64.test(material.operation_sha256) ||
     !(
      (material.financial_action==="create" && material.expected_revision===null) ||
      (material.financial_action==="correct" &&
       Number.isSafeInteger(material.expected_revision) &&
       material.expected_revision>0)
     )
  ) throw new Error("Invalid signed financial request context");
  base[0]=VERSION;
  const revision=material.expected_revision===null?"null":
    String(material.expected_revision);
  return new TextEncoder().encode([
    ...base,material.operation_id,material.entity_id,
    material.financial_action,revision,material.operation_sha256
  ].join("\n"));
}

export async function verifySignedPurchase(material,derBase64url,nowMs=Date.now()) {
  try {
    const bytes=purchaseSignatureTranscript(material,nowMs);
    const spkiB64=material.spki_base64;
    if(typeof spkiB64!=="string"||
       spkiB64.length>800||
       !/^[A-Za-z0-9+/\s]{1,800}={0,2}$/.test(spkiB64))return false;
    const spki=Uint8Array.from(atob(spkiB64.replace(/\s/g,"")),x=>x.charCodeAt(0));
    if(spki.length<64||spki.length>512)return false;
    const sig=derP256ToRaw64(base64UrlBytes(derBase64url));
    const key=await crypto.subtle.importKey("spki",spki,
      {name:"ECDSA",namedCurve:"P-256"},false,["verify"]);
    return crypto.subtle.verify({name:"ECDSA",hash:"SHA-256"},key,sig,bytes);
  } catch {
    return false;
  }
}
