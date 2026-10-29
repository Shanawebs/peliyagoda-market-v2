import {withSupabase} from "npm:@supabase/server@1.9.2";
const WORKSPACE="af77cb66-76b2-492a-a460-06132f468713";
const OWNER="98c3b101-d452-4b6b-902c-0b1a31fc340c";
const UUID=/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/;
const H={"Cache-Control":"no-store","X-Content-Type-Options":"nosniff","Referrer-Policy":"no-referrer"};
const reply=(status:number,code:string,extra:Record<string,unknown>={})=>Response.json({ok:status<300,code,...extra},{status,headers:H});
function decode(value:string):Uint8Array {
 if(typeof value!=="string"||value.length>256||!/^[A-Za-z0-9_-]+$/.test(value))throw Error("INVALID_BASE64");
 const p=value.replace(/-/g,"+").replace(/_/g,"/");
 return Uint8Array.from(atob(p+"=".repeat((4-p.length%4)%4)),x=>x.charCodeAt(0));
}
async function sha256(bytes:Uint8Array):Promise<string>{
 const digest=await crypto.subtle.digest("SHA-256",bytes);
 return [...new Uint8Array(digest)].map(b=>b.toString(16).padStart(2,"0")).join("");
}
async function verify(body:Uint8Array,action:string,device:string,ts:number,nonce:string,signature:string,pub:string):Promise<boolean>{
 if(!["enroll","approve"].includes(action)||!UUID.test(device)||!Number.isSafeInteger(ts)||
    Math.abs(Date.now()/1000-ts)>60||typeof nonce!=="string"||nonce.length<22||nonce.length>64||
    !/^[A-Za-z0-9_-]+$/.test(nonce))return false;
 try{
  const keyBytes=decode(pub);const sig=decode(signature);
  if(keyBytes.length<80||keyBytes.length>100||sig.length!==64)return false;
  const key=await crypto.subtle.importKey("spki",keyBytes,{name:"ECDSA",namedCurve:"P-256"},false,["verify"]);
  const hash=await sha256(body);
  const canonical="MARKETPRO-PHOTO-V1\n"+action+"\n"+device+"\n"+ts+"\n"+nonce+"\n"+hash;
  return await crypto.subtle.verify({name:"ECDSA",hash:"SHA-256"},key,sig,new TextEncoder().encode(canonical));
 }catch{return false;}
}
function freshOwner(ctx:any):boolean{
 const j=ctx?.jwtClaims,u=ctx?.userClaims,now=Math.floor(Date.now()/1000);
 return u?.id===OWNER&&j?.sub===OWNER&&j?.aal==="aal2"&&
 Array.isArray(j.amr)&&j.amr.some((m:any)=>m?.method==="totp"&&
 Number.isInteger(m.timestamp)&&m.timestamp>=now-300&&m.timestamp<=now+15);
}
export default {fetch:withSupabase({auth:"user"},async(req:any,ctx:any)=>{
 if(req.method!=="POST")return reply(405,"METHOD_NOT_ALLOWED");
 const jwt=ctx?.jwtClaims,usr=ctx?.userClaims;
 if(!usr||!jwt||usr.id!==jwt.sub||usr.role!=="authenticated"||
 jwt.is_anonymous===true||!UUID.test(jwt.session_id||""))return reply(403,"LOGIN_REQUIRED");
 const limit=Number(req.headers.get("content-length")||0);
 if(limit>4096)return reply(413,"BODY_TOO_LARGE");
 let bytes:Uint8Array;
 try{bytes=new Uint8Array(await req.arrayBuffer());}catch{return reply(400,"BAD_BODY");}
 if(bytes.length<10||bytes.length>4096)return reply(400,"BAD_BODY_SIZE");
 let payload:any;
 try{payload=JSON.parse(new TextDecoder().decode(bytes));}catch{return reply(400,"INVALID_JSON");}
 const action=payload?.action,device=payload?.device_id;
 if(!["enroll","approve"].includes(action)||!UUID.test(device))
 return reply(400,"INVALID_ACTION");
 if(req.headers.get("X-MP-Device")!==device)return reply(403,"DEVICE_ID_MISMATCH");
 const ts=Number(req.headers.get("X-MP-Timestamp"));
 const nonce=req.headers.get("X-MP-Nonce")||"";
 const signature=req.headers.get("X-MP-Signature")||"";
 const hash=await sha256(bytes);
 if(req.headers.get("X-MP-Body-Sha256")!==hash)return reply(403,"BODY_HASH_MISMATCH");
 const db=ctx.supabaseAdmin;
 try{
 const [member,phone]=await Promise.all([
  db.from("workspace_members").select("role").eq("workspace_id",WORKSPACE).eq("user_id",usr.id).maybeSingle(),
  db.from("registered_devices").select("id,user_id,enabled,retired_at,can_write")
  .eq("workspace_id",WORKSPACE).eq("id",device).eq("user_id",usr.id).maybeSingle()
 ]);
 if(member.error||phone.error||!["owner","editor"].includes(member.data?.role)||
 !phone.data?.enabled||phone.data.retired_at!==null)return reply(403,"DEVICE_NOT_APPROVED");
 if(action==="enroll"){
  const pub=payload.public_key_spki;
  if(typeof pub!=="string"||pub.length<100||pub.length>250||
  !await verify(bytes,action,device,ts,nonce,signature,pub))
  return reply(403,"INVALID_NEW_KEY_PROOF");
  const pubHash=await sha256(decode(pub));
  const {data:existing,error:existingErr}=await db.from("marketpro_photo_device_keys")
  .select("key_sha256,status").eq("workspace_id",WORKSPACE).eq("device_id",device).maybeSingle();
  if(existingErr)return reply(503,"PHOTO_KEY_STORAGE_UNAVAILABLE");
  if(existing){
   if(existing.key_sha256!==pubHash)return reply(409,"KEY_RECOVERY_REQUIRES_OWNER");
   return reply(200,"ALREADY_ENROLLED",{status:existing.status});
  }
  const isOwner=usr.id===OWNER&&member.data.role==="owner";
  if(isOwner&&(!freshOwner(ctx)||phone.data.can_write!==true))
    return reply(403,"RECENT_OWNER_MFA_REQUIRED");
  if(usr.id===OWNER&&!isOwner)return reply(403,"OWNER_ROLE_MISMATCH");
  const {error:nonceErr}=await db.from("marketpro_photo_request_nonces")
   .insert({workspace_id:WORKSPACE,device_id:device,nonce});
  if(nonceErr)return reply(409,"REQUEST_REPLAYED");
  const {error:saveErr}=await db.from("marketpro_photo_device_keys").insert({
   workspace_id:WORKSPACE,device_id:device,user_id:usr.id,
   public_key_spki:pub,key_sha256:pubHash,
   status:isOwner?"approved":"pending",
   approved_by:isOwner?OWNER:null,approved_at:isOwner?new Date().toISOString():null
  });
  if(saveErr)return reply(503,"KEY_ENROLLMENT_FAILED");
  return reply(201,isOwner?"OWNER_KEY_APPROVED":"TEAM_KEY_PENDING_OWNER_APPROVAL");
 }
 if(usr.id!==OWNER||member.data.role!=="owner"||!freshOwner(ctx)||
 phone.data.can_write!==true)return reply(403,"OWNER_MFA_REQUIRED");
 const {data:ownerKey,error:ownerKeyError}=await db.from("marketpro_photo_device_keys")
  .select("public_key_spki,status").eq("workspace_id",WORKSPACE)
  .eq("device_id",device).eq("user_id",OWNER).maybeSingle();
 if(ownerKeyError||ownerKey?.status!=="approved"||
 !UUID.test(payload.target_device_id||""))return reply(403,"OWNER_KEY_REQUIRED");
 if(!await verify(bytes,"approve",device,ts,nonce,signature,ownerKey.public_key_spki))
 return reply(403,"INVALID_OWNER_SIGNATURE");
 const {error:replayErr}=await db.from("marketpro_photo_request_nonces")
  .insert({workspace_id:WORKSPACE,device_id:device,nonce});
 if(replayErr)return reply(409,"REQUEST_REPLAYED");
 const {data:target,error:targetError}=await db.from("registered_devices")
  .select("user_id,enabled,retired_at").eq("workspace_id",WORKSPACE)
  .eq("id",payload.target_device_id).maybeSingle();
 if(targetError||!target?.enabled||target.retired_at!==null||target.user_id===OWNER)
 return reply(403,"TEAM_DEVICE_INACTIVE");
 const {data:targetMember}=await db.from("workspace_members").select("role")
  .eq("workspace_id",WORKSPACE).eq("user_id",target.user_id).maybeSingle();
 if(targetMember?.role!=="editor")return reply(403,"TARGET_NOT_TEAM");
 const {data:approved,error:saveError}=await db.from("marketpro_photo_device_keys")
  .update({status:"approved",approved_by:OWNER,approved_at:new Date().toISOString()})
  .eq("workspace_id",WORKSPACE).eq("device_id",payload.target_device_id)
  .eq("user_id",target.user_id).eq("status","pending")
  .select("device_id").maybeSingle();
 if(saveError||!approved)return reply(409,"PENDING_TEAM_KEY_NOT_FOUND");
 return reply(200,"TEAM_KEY_APPROVED");
 }catch{return reply(503,"ENROLLMENT_SERVICE_UNAVAILABLE");}
})};
