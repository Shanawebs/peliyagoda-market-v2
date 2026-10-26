// Owner PENDING proposal signature domain, never reused for QA proof or activation.
import {base64UrlBytes,derP256ToRaw64} from "./device_proof.mjs";
const UUID=/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
const HEX64=/^[a-f0-9]{64}$/;
export const PENDING_OWNER_VERSION="marketpro-owner-pending-registration-v1";
export function pendingOwnerTranscript(m,nowMs=Date.now()) {
  if(!m || typeof m!=="object" || Array.isArray(m) ||
    m.proof_version!==PENDING_OWNER_VERSION ||
    !["workspace_id","owner_user_id","device_id","session_id","challenge_id"]
      .every(k=>typeof m[k]==="string" && UUID.test(m[k])) ||
    !HEX64.test(m.nonce_hex) || !HEX64.test(m.fingerprint_sha256) ||
    !Number.isSafeInteger(m.issued_at_millis) ||
    !Number.isSafeInteger(m.expires_at_millis) ||
    !Number.isSafeInteger(nowMs) ||
    m.issued_at_millis<0 ||
    m.expires_at_millis<=m.issued_at_millis ||
    m.expires_at_millis-m.issued_at_millis>60_000 ||
    nowMs<m.issued_at_millis || nowMs>=m.expires_at_millis) {
    throw new Error("Invalid or expired Pending Owner proof");
  }
  return new TextEncoder().encode([
    PENDING_OWNER_VERSION,m.workspace_id,m.owner_user_id,m.device_id,
    m.session_id,m.challenge_id,m.nonce_hex,m.fingerprint_sha256,
    String(m.issued_at_millis),String(m.expires_at_millis)
  ].join("\n"));
}
export async function verifyPendingOwnerSignature(material,signature,nowMs=Date.now()) {
  try {
    const bytes=pendingOwnerTranscript(material,nowMs);
    if(typeof material.spki_base64!=="string" ||
      !/^[A-Za-z0-9+/]{85,800}={0,2}$/.test(material.spki_base64))return false;
    const publicDer=Uint8Array.from(atob(material.spki_base64),c=>c.charCodeAt(0));
    if(publicDer.length<64 || publicDer.length>512)return false;
    const sha=Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256",publicDer)),
      v=>v.toString(16).padStart(2,"0")).join("");
    if(sha!==material.fingerprint_sha256)return false;
    const raw64=derP256ToRaw64(base64UrlBytes(signature));
    const pub=await crypto.subtle.importKey("spki",publicDer,
      {name:"ECDSA",namedCurve:"P-256"},false,["verify"]);
    return await crypto.subtle.verify({name:"ECDSA",hash:"SHA-256"},pub,raw64,bytes);
  }catch{
    return false;
  }
}
