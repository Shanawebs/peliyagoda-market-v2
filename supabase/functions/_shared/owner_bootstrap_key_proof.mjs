// STAGING REVIEW ONLY. Owner's AndroidKeyStore P-256 key possession proof.
// This does not enroll the phone or grant any database/Staff permission.
// Never log token, nonce, private key or signature. Reuse the audited strict
// DER converter shared with existing Staff proof verification.
import {base64UrlBytes, derP256ToRaw64} from "./device_proof.mjs";

const UUID=/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
const HEX64=/^[a-f0-9]{64}$/;
export const OWNER_BOOTSTRAP_VERSION="marketpro-owner-bootstrap-key-v1";
export const OWNER_BOOTSTRAP_TTL_MS=60_000;

export function ownerBootstrapTranscript(m, nowMs=Date.now()) {
  if(!m || typeof m!=="object" || Array.isArray(m) ||
     m.proof_version!==OWNER_BOOTSTRAP_VERSION ||
     !["workspace_id","owner_user_id","device_id","session_id","challenge_id"]
       .every(k=>typeof m[k]==="string" && UUID.test(m[k])) ||
     !HEX64.test(m.nonce_hex) || !HEX64.test(m.fingerprint_sha256) ||
     !Number.isSafeInteger(m.issued_at_millis) ||
     !Number.isSafeInteger(m.expires_at_millis) ||
     !Number.isSafeInteger(nowMs) || m.issued_at_millis<0 ||
     m.expires_at_millis<=m.issued_at_millis ||
     m.expires_at_millis-m.issued_at_millis>OWNER_BOOTSTRAP_TTL_MS ||
     nowMs<m.issued_at_millis || nowMs>=m.expires_at_millis) {
     throw new Error("Invalid or expired Owner device challenge");
  }
  return new TextEncoder().encode([
    OWNER_BOOTSTRAP_VERSION,
    m.workspace_id,m.owner_user_id,m.device_id,m.session_id,
    m.challenge_id,m.nonce_hex,m.fingerprint_sha256,
    String(m.issued_at_millis),String(m.expires_at_millis)
  ].join("\n"));
}

export async function verifyOwnerBootstrapKeyPossession(
  material, derSignatureBase64Url, nowMs=Date.now()
) {
  try {
    const msg=ownerBootstrapTranscript(material,nowMs);
    if(typeof material.spki_base64!=="string" ||
      !/^[A-Za-z0-9+/]{85,800}={0,2}$/.test(material.spki_base64)) return false;
    const spki=Uint8Array.from(atob(material.spki_base64),c=>c.charCodeAt(0));
    if(spki.length<64||spki.length>512)return false;
    // The fingerprint must come from the actual SPKI, never client claims.
    const computed=Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256",spki)),
      v=>v.toString(16).padStart(2,"0")).join("");
    if(computed!==material.fingerprint_sha256)return false;

    const sig=derP256ToRaw64(base64UrlBytes(derSignatureBase64Url));
    const pub=await crypto.subtle.importKey("spki",spki,
      {name:"ECDSA",namedCurve:"P-256"},false,["verify"]);
    return await crypto.subtle.verify(
      {name:"ECDSA",hash:"SHA-256"},pub,sig,msg);
  }catch{
    // Do not leak details of invalid signatures, SPKI, nonce or session.
    return false;
  }
}
