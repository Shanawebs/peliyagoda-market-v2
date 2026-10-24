// Shared by the review-only Supabase Edge verifier and Node.js regression
// tests. Android's SHA256withECDSA yields ASN.1 DER; WebCrypto ECDSA verify
// requires IEEE P1363 r||s (64 bytes). Reject malformed/non-minimal DER.
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const HEX64 = /^[0-9a-f]{64}$/;
const B64URL = /^[A-Za-z0-9_-]{1,512}$/;
const DOMAIN = "marketpro-device-proof-v1";

export function base64UrlBytes(value) {
  if (typeof value !== "string" || !B64URL.test(value)) {
    throw new Error("Invalid encoded proof");
  }
  const normalized = value.replace(/-/g, "+").replace(/_/g, "/");
  const data = atob(normalized.padEnd(Math.ceil(normalized.length / 4) * 4, "="));
  return Uint8Array.from(data, c => c.charCodeAt(0));
}

export function derP256ToRaw64(der) {
  if (!(der instanceof Uint8Array) || der.length < 8 || der.length > 80) {
    throw new Error("Invalid P-256 DER signature size");
  }
  let off=0;
  const take=()=> {
    if(off>=der.length)throw new Error("Truncated DER");
    return der[off++];
  };
  if(take()!==0x30)throw new Error("Invalid DER sequence");
  const seqLen=take(); // DER ECDSA P-256 sequence is always short-form.
  if(seqLen>=0x80 || seqLen!==der.length-off)throw new Error("Non-canonical DER length");
  function integer() {
    if(take()!==0x02)throw new Error("Expected DER integer");
    const len=take();
    if(len<1 || len>33 || off+len>der.length)
      throw new Error("Invalid ECDSA integer length");
    const bytes=der.subarray(off,off+len);
    off+=len;
    if((bytes[0] & 0x80)!==0)throw new Error("Negative ECDSA integer");
    if(len>1 && bytes[0]===0 && (bytes[1] & 0x80)===0)
      throw new Error("Non-minimal ECDSA integer");
    const clean=(len===33 && bytes[0]===0) ? bytes.subarray(1) : bytes;
    if(clean.length>32)throw new Error("Out-of-range ECDSA integer");
    const raw=new Uint8Array(32);
    raw.set(clean,32-clean.length);
    return raw;
  }
  const r=integer();
  const s=integer();
  if(off!==der.length)throw new Error("Trailing ECDSA DER data");
  const raw=new Uint8Array(64);
  raw.set(r,0);
  raw.set(s,32);
  return raw;
}

export function canonicalStaffProofTranscript(challenge,nowMs) {
  if (!challenge || typeof challenge!=="object" ||
    !["workspace_id","staff_user_id","device_id","session_id","challenge_id"]
      .every(k=>UUID.test(challenge[k])) ||
    !HEX64.test(challenge.nonce_hex) ||
    !["read","write"].includes(challenge.purpose) ||
    !Number.isSafeInteger(challenge.issued_at_millis) ||
    !Number.isSafeInteger(challenge.expires_at_millis) ||
    !Number.isSafeInteger(nowMs) ||
    challenge.issued_at_millis<0 ||
    challenge.expires_at_millis<=challenge.issued_at_millis ||
    challenge.expires_at_millis-challenge.issued_at_millis>120000 ||
    nowMs<challenge.issued_at_millis || nowMs>=challenge.expires_at_millis
  ) throw new Error("Expired or invalid server challenge");

  return new TextEncoder().encode([
    DOMAIN,challenge.purpose,challenge.workspace_id,
    challenge.staff_user_id,challenge.device_id,challenge.session_id,
    challenge.challenge_id,challenge.nonce_hex,
    String(challenge.issued_at_millis),String(challenge.expires_at_millis)
  ].join("\n"));
}

export async function verifyAndroidDeviceProof(material,signatureBase64Url,nowMs=Date.now()) {
  try {
    const transcript=canonicalStaffProofTranscript(material,nowMs);
    if(typeof material.spki_base64!=="string" ||
       material.spki_base64.length>800) return false;
    const spkiB64=material.spki_base64.replace(/\s/g,"");
    if(!/^[A-Za-z0-9+/]{1,800}={0,2}$/.test(spkiB64))return false;
    const spki=Uint8Array.from(atob(spkiB64),c=>c.charCodeAt(0));
    if(spki.length<64 || spki.length>512)return false;
    const signature=derP256ToRaw64(base64UrlBytes(signatureBase64Url));
    const key=await crypto.subtle.importKey(
      "spki",spki,{name:"ECDSA",namedCurve:"P-256"},false,["verify"]);
    return await crypto.subtle.verify(
      {name:"ECDSA",hash:"SHA-256"},key,signature,transcript);
  }catch {
    // Never include the signature, SPKI, JWT or nonce in error messages/logs.
    return false;
  }
}
