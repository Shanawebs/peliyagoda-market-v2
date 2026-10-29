import { withSupabase } from "npm:@supabase/server@1.9.2";

const WORKSPACE = "af77cb66-76b2-492a-a460-06132f468713";
const OWNER = "98c3b101-d452-4b6b-902c-0b1a31fc340c";
const FOLDER = "432122545823";
const UUID = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/;
const NO_STORE = {
  "Cache-Control": "no-store, max-age=0",
  "Referrer-Policy": "no-referrer",
  "X-Content-Type-Options": "nosniff"
};

function reply(ok: boolean, status: number, code: string, extra: Record<string, unknown> = {}) {
  return Response.json({ ok, code, ...extra }, { status, headers: NO_STORE });
}
function b64ToBytes(value: string): Uint8Array {
  if (!/^[A-Za-z0-9_-]+$/.test(value)) throw Error("Invalid encrypted token");
  const plain = value.replace(/-/g, "+").replace(/_/g, "/");
  return Uint8Array.from(atob(plain + "=".repeat((4 - plain.length % 4) % 4)), c => c.charCodeAt(0));
}
function bytesToB64(data: Uint8Array): string {
  return btoa(String.fromCharCode(...data)).replace(/\+/g,"-").replace(/\//g,"_").replace(/=+$/,"");
}
async function tokenKey(clientSecret: string, serviceKey: string): Promise<CryptoKey> {
  const material = "marketpro-box-oauth-staging-v1|" + WORKSPACE + "|" + clientSecret + "|" + serviceKey;
  const raw = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(material));
  return crypto.subtle.importKey("raw", raw, { name:"AES-GCM" }, false, ["encrypt", "decrypt"]);
}
async function decryptToken(cipher: string, iv: string, key: CryptoKey): Promise<{
  access_token: string; refresh_token: string; access_expires_at: number; box_user_id: string;
}> {
  const plain = await crypto.subtle.decrypt({
    name: "AES-GCM", iv: b64ToBytes(iv),
    additionalData: new TextEncoder().encode(WORKSPACE)
  }, key, b64ToBytes(cipher));
  const obj = JSON.parse(new TextDecoder().decode(plain));
  if (typeof obj.access_token !== "string" || typeof obj.refresh_token !== "string" ||
      typeof obj.box_user_id !== "string" || !Number.isFinite(obj.access_expires_at)) {
    throw Error("Encrypted Box token structure invalid");
  }
  return obj;
}
async function encryptToken(
  token: {access_token:string;refresh_token:string;access_expires_at:number;box_user_id:string},
  key: CryptoKey
) {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const encrypted = await crypto.subtle.encrypt({
    name:"AES-GCM", iv,
    additionalData: new TextEncoder().encode(WORKSPACE)
  }, key, new TextEncoder().encode(JSON.stringify(token)));
  return { token_iv: bytesToB64(iv), token_ciphertext: bytesToB64(new Uint8Array(encrypted)) };
}
function ownerMfa(ctx: any): boolean {
  const jwt=ctx?.jwtClaims, usr=ctx?.userClaims;
  if (!jwt || !usr || jwt.sub !== OWNER || usr.id !== OWNER ||
      usr.role !== "authenticated" || jwt.aal !== "aal2" ||
      jwt.is_anonymous === true || !UUID.test(jwt.session_id||"") ||
      !Array.isArray(jwt.amr)) return false;
  const now = Math.floor(Date.now()/1000);
  return jwt.amr.some((v:any)=>v?.method==="totp" &&
    Number.isInteger(v.timestamp) && v.timestamp >= now-300 && v.timestamp <= now+15);
}
type BoxConn = {
  workspace_id:string; box_user_id:string; box_folder_id:string;
  token_iv:string; token_ciphertext:string; encryption_version:number;
  access_expires_at:string; refresh_generation:number;
  refresh_lease_id:string|null; refresh_lease_until:string|null; updated_at:string;
};

export default {
  fetch: withSupabase({auth:"user"}, async(req:any, ctx:any) => {
    if (req.method !== "POST") return reply(false,405,"METHOD_NOT_ALLOWED");
    if (!ownerMfa(ctx)) return reply(false,403,"FRESH_OWNER_MFA_REQUIRED");
    const clientId=Deno.env.get("BOX_CLIENT_ID");
    const clientSecret=Deno.env.get("BOX_CLIENT_SECRET");
    const serviceKey=Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
    const configuredFolder=Deno.env.get("BOX_PHOTO_FOLDER_ID");
    if (!clientId || !clientSecret || !serviceKey || configuredFolder !== FOLDER) {
      return reply(false,503,"BOX_CONFIGURATION_UNAVAILABLE");
    }
    const body=await req.json().catch(()=>null);
    if (!body || typeof body!=="object" || Array.isArray(body) ||
        Object.keys(body).length !== 1 || !UUID.test(body.device_id||"")) {
      return reply(false,400,"DEVICE_ID_REQUIRED");
    }
    const admin=ctx.supabaseAdmin;
    try {
      const [member, device]=await Promise.all([
        admin.from("workspace_members").select("role")
          .eq("workspace_id",WORKSPACE).eq("user_id",OWNER).maybeSingle(),
        admin.from("registered_devices")
          .select("enabled,can_write,retired_at,user_id,workspace_id")
          .eq("id",body.device_id).eq("workspace_id",WORKSPACE).eq("user_id",OWNER)
          .maybeSingle()
      ]);
      if (member.error || device.error || member.data?.role !== "owner" ||
          !device.data?.enabled || !device.data?.can_write ||
          device.data?.retired_at !== null ||
          device.data?.workspace_id !== WORKSPACE || device.data?.user_id !== OWNER) {
        return reply(false,403,"OWNER_DEVICE_NOT_APPROVED");
      }
      const {data:row,error}=await admin.from("marketpro_box_connections")
        .select("workspace_id,box_user_id,box_folder_id,token_iv,token_ciphertext,encryption_version,access_expires_at,refresh_generation,refresh_lease_id,refresh_lease_until,updated_at")
        .eq("workspace_id",WORKSPACE).maybeSingle();
      if (error || !row) return reply(false,404,"BOX_NOT_CONNECTED");
      const conn=row as BoxConn;
      if (conn.box_folder_id !== FOLDER || conn.encryption_version !== 1 ||
          !/^\d{1,32}$/.test(conn.box_user_id) || conn.refresh_generation < 0) {
        return reply(false,503,"BOX_CONNECTION_NEEDS_REVIEW");
      }
      const key=await tokenKey(clientSecret,serviceKey);
      const oldToken=await decryptToken(conn.token_ciphertext,conn.token_iv,key);
      if (oldToken.box_user_id !== conn.box_user_id) return reply(false,503,"TOKEN_OWNER_MISMATCH");
      const now=Date.now();
      if (new Date(conn.access_expires_at).getTime() > now+180_000 &&
          oldToken.access_expires_at > now+180_000) {
        // Do not return access or refresh tokens to any Android device.
        return reply(true,200,"BOX_TOKEN_READY",{connected:true,refresh_needed:false});
      }
      const claim=crypto.randomUUID(),until=new Date(now+90000).toISOString();
      const {data:lease,error:leaseError}=await admin.from("marketpro_box_connections")
        .update({refresh_lease_id:claim,refresh_lease_until:until})
        .eq("workspace_id",WORKSPACE)
        .eq("refresh_generation",conn.refresh_generation)
        .eq("updated_at",conn.updated_at)
        .or("refresh_lease_id.is.null,refresh_lease_until.lt."+new Date(now).toISOString())
        .select("refresh_lease_id").maybeSingle();
      if (leaseError) return reply(false,503,"TOKEN_LEASE_UNAVAILABLE");
      if (lease?.refresh_lease_id!==claim) return reply(false,409,"TOKEN_REFRESH_IN_PROGRESS");

      // Refresh tokens are single-use. Perform one network call only under the DB lease.
      // A failed persistence is reported as REAUTH_REQUIRED, never blindly retried.
      let consumed=false;
      try {
        const post=new URLSearchParams({
          grant_type:"refresh_token",refresh_token:oldToken.refresh_token,
          client_id:clientId,client_secret:clientSecret
        });
        const resp=await fetch("https://api.box.com/oauth2/token",{
          method:"POST",
          headers:{"Content-Type":"application/x-www-form-urlencoded"},
          body:post,
          signal:AbortSignal.timeout(20000)
        });
        if (!resp.ok) return reply(false,resp.status===400?409:503,
          resp.status===400?"BOX_REAUTH_REQUIRED":"BOX_TOKEN_REFRESH_FAILED");
        const data=await resp.json();
        consumed=true;
        if (typeof data.access_token!=="string" || !data.access_token ||
            typeof data.refresh_token!=="string" || !data.refresh_token ||
            !Number.isFinite(data.expires_in) || data.expires_in<60) {
          return reply(false,503,"BOX_REAUTH_REQUIRED");
        }
        const expires=Date.now()+Number(data.expires_in)*1000;
        const tokenEncrypted=await encryptToken({
          access_token:data.access_token,refresh_token:data.refresh_token,
          access_expires_at:expires,box_user_id:conn.box_user_id
        },key);
        const {data:saved,error:saveError}=await admin.from("marketpro_box_connections")
          .update({
            ...tokenEncrypted,
            access_expires_at:new Date(expires).toISOString(),
            refresh_generation:conn.refresh_generation+1,
            refresh_lease_id:null,refresh_lease_until:null,
            updated_at:new Date().toISOString()
          })
          .eq("workspace_id",WORKSPACE)
          .eq("refresh_generation",conn.refresh_generation)
          .eq("refresh_lease_id",claim)
          .eq("updated_at",conn.updated_at)
          .select("refresh_generation").maybeSingle();
        if (saveError || saved?.refresh_generation!==conn.refresh_generation+1) {
          return reply(false,503,"BOX_REAUTH_REQUIRED");
        }
        return reply(true,200,"BOX_TOKEN_REFRESHED",{connected:true,refresh_needed:false});
      } finally {
        // Release lease after failures only. In-flight Box refresh may have consumed
        // a token before a network failure; caller must never try concurrently.
        if (!consumed) {
          await admin.from("marketpro_box_connections")
            .update({refresh_lease_id:null,refresh_lease_until:null})
            .eq("workspace_id",WORKSPACE).eq("refresh_lease_id",claim);
        }
      }
    } catch {
      return reply(false,503,"BOX_TOKEN_HEALTH_UNAVAILABLE");
    }
  })
};