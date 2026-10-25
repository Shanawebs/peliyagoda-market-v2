// Review-only. Proof-of-possession for a NEW candidate public key.
 // Does not authorize writing. Owner must separately approve the fingerprint.
import {base64UrlBytes,derP256ToRaw64} from "./device_proof.mjs";
const UUID=/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
const HEX64=/^[a-f0-9]{64}$/;
const VERSION="marketpro-staff-key-enrollment-v1";
export function keyEnrollmentTranscript(m,nowMs=Date.now()){
 if(!m||typeof m!=="object"||m.proof_version!==VERSION ||
    !["workspace_id","staff_user_id","device_id","session_id","challenge_id"]
      .every(k=>typeof m[k]==="string"&&UUID.test(m[k])) ||
    !HEX64.test(m.nonce_hex)||!HEX64.test(m.fingerprint_sha256)||
    !Number.isSafeInteger(m.issued_at_millis)||
    !Number.isSafeInteger(m.expires_at_millis)||
    !Number.isSafeInteger(nowMs)||m.issued_at_millis<0 ||
    m.expires_at_millis<=m.issued_at_millis||
    m.expires_at_millis-m.issued_at_millis>120000||
    nowMs<m.issued_at_millis||nowMs>=m.expires_at_millis)
  throw Error("Invalid enrollment challenge");
 return new TextEncoder().encode([
    VERSION,m.workspace_id,m.staff_user_id,m.device_id,
    m.session_id,m.challenge_id,m.nonce_hex,m.fingerprint_sha256,
    String(m.issued_at_millis),String(m.expires_at_millis)
 ].join("\n"));
}
export async function verifyEnrollmentKeyPossession(m,derSignature,nowMs=Date.now()){
 try{
   const transcript=keyEnrollmentTranscript(m,nowMs);
   if(typeof m.spki_base64!=="string"||
      m.spki_base64.length>800||
      !/^[A-Za-z0-9+/\s]{85,800}={0,2}$/.test(m.spki_base64))return false;
   const spki=Uint8Array.from(atob(m.spki_base64.replace(/\s/g,"")),
      c=>c.charCodeAt(0));
   if(spki.length<64||spki.length>512)return false;
   const sig=derP256ToRaw64(base64UrlBytes(derSignature));
   const key=await crypto.subtle.importKey("spki",spki,
       {name:"ECDSA",namedCurve:"P-256"},false,["verify"]);
   return crypto.subtle.verify(
       {name:"ECDSA",hash:"SHA-256"},key,sig,transcript);
 } catch{return false;}
}
