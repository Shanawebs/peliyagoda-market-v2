import {withSupabase} from "npm:@supabase/server@1.9.2";
const W="af77cb66-76b2-492a-a460-06132f468713",F="432122545823";
const UUID=/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/, DIG=/^[a-f0-9]{64}$/;
const MAX=16*1024*1024;
const H={"Cache-Control":"no-store","Referrer-Policy":"no-referrer","X-Content-Type-Options":"nosniff"};
const say=(status:number,code:string,data:Record<string,unknown>={})=>
 Response.json({ok:status>=200&&status<300,code,...data},{status,headers:H});
const encode=(bytes:Uint8Array):string=>btoa(String.fromCharCode(...bytes)).replace(/\+/g,"-").replace(/\//g,"_").replace(/=+$/,"");
function decode(v:string):Uint8Array{
 if(typeof v!=="string"||v.length>2048||!/^[A-Za-z0-9_-]+$/.test(v))throw Error("INVALID_TOKEN_ENCODING");
 const x=v.replace(/-/g,"+").replace(/_/g,"/");
 return Uint8Array.from(atob(x+"=".repeat((4-x.length%4)%4)),c=>c.charCodeAt(0));
}
async function digest(body:Uint8Array,algorithm="SHA-256"){
 const b=new Uint8Array(await crypto.subtle.digest(algorithm,body));
 return [...b].map(x=>x.toString(16).padStart(2,"0")).join("");
}
async function verify(action:string,device:string,ts:number,nonce:string,sig:string,pub:string,sha:string):Promise<boolean>{
 if(!["upload","list","download"].includes(action)||!UUID.test(device)||!DIG.test(sha)||
 !Number.isSafeInteger(ts)||Math.abs(Date.now()/1000-ts)>60||
 typeof nonce!=="string"||nonce.length<22||nonce.length>64||!/^[A-Za-z0-9_-]+$/.test(nonce))
 return false;
 try{
 const keyBytes=decode(pub),signature=decode(sig);
 if(keyBytes.length<80||keyBytes.length>100||signature.length!==64)return false;
 const key=await crypto.subtle.importKey("spki",keyBytes,{name:"ECDSA",namedCurve:"P-256"},false,["verify"]);
 const message="MARKETPRO-PHOTO-V1\n"+action+"\n"+device+"\n"+ts+"\n"+nonce+"\n"+sha;
 return await crypto.subtle.verify({name:"ECDSA",hash:"SHA-256"},key,signature,new TextEncoder().encode(message));
 }catch{return false;}
}
async function tokenCrypto(){
 const secret=Deno.env.get("BOX_CLIENT_SECRET"),srv=Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
 if(!secret||!srv||!Deno.env.get("BOX_CLIENT_ID")||Deno.env.get("BOX_PHOTO_FOLDER_ID")!==F)
 throw Error("BOX_CONFIGURATION_UNAVAILABLE");
 const raw=await crypto.subtle.digest("SHA-256",new TextEncoder().encode("marketpro-box-oauth-staging-v1|"+W+"|"+secret+"|"+srv));
 return crypto.subtle.importKey("raw",raw,{name:"AES-GCM"},false,["decrypt","encrypt"]);
}
async function boxToken(db:any):Promise<string>{
 const {data:conn,error}=await db.from("marketpro_box_connections").select("*")
  .eq("workspace_id",W).maybeSingle();
 if(error||!conn)throw Error("BOX_NOT_CONNECTED");
 if(conn.box_folder_id!==F||conn.encryption_version!==1)throw Error("BOX_CONFIGURATION_UNAVAILABLE");
 const key=await tokenCrypto();
 const aad=new TextEncoder().encode(W);
 let obj:any;
 try{
 const raw=await crypto.subtle.decrypt({name:"AES-GCM",iv:decode(conn.token_iv),additionalData:aad},
  key,decode(conn.token_ciphertext));
 obj=JSON.parse(new TextDecoder().decode(raw));
 }catch{throw Error("BOX_REAUTHORIZE");}
 if(typeof obj.access_token!=="string"||typeof obj.refresh_token!=="string"||
 obj.box_user_id!==conn.box_user_id)throw Error("BOX_REAUTHORIZE");
 const now=Date.now();
 if(Date.parse(conn.access_expires_at)>now+120000 && obj.access_expires_at>now+120000)
 return obj.access_token;
 const lease=crypto.randomUUID();
 const {data:held,error:leaseError}=await db.from("marketpro_box_connections")
 .update({refresh_lease_id:lease,refresh_lease_until:new Date(now+90_000).toISOString()})
 .eq("workspace_id",W).eq("refresh_generation",conn.refresh_generation)
 .eq("updated_at",conn.updated_at)
 .or("refresh_lease_id.is.null,refresh_lease_until.lt."+new Date(now).toISOString())
 .select("refresh_lease_id").maybeSingle();
 if(leaseError||held?.refresh_lease_id!==lease)throw Error("BOX_REFRESH_BUSY");
 let consumed=false;
 try{
 const form=new URLSearchParams({grant_type:"refresh_token",refresh_token:obj.refresh_token,
 client_id:Deno.env.get("BOX_CLIENT_ID")!,client_secret:Deno.env.get("BOX_CLIENT_SECRET")!});
 const resp=await fetch("https://api.box.com/oauth2/token",{
 method:"POST",headers:{"Content-Type":"application/x-www-form-urlencoded"},
 body:form,signal:AbortSignal.timeout(20000)});
 if(!resp.ok)throw Error("BOX_REAUTHORIZE");
 const pair=await resp.json();
 consumed=true;
 if(typeof pair.access_token!=="string"||typeof pair.refresh_token!=="string"||
 !Number.isFinite(pair.expires_in)||pair.expires_in<60)throw Error("BOX_REAUTHORIZE");
 const expires=Date.now()+Math.floor(pair.expires_in)*1000;
 const iv=crypto.getRandomValues(new Uint8Array(12));
 const payload=new TextEncoder().encode(JSON.stringify({access_token:pair.access_token,
 refresh_token:pair.refresh_token,access_expires_at:expires,box_user_id:conn.box_user_id}));
 const cipher=new Uint8Array(await crypto.subtle.encrypt({name:"AES-GCM",iv,additionalData:aad},key,payload));
 const {data:saved,error:saveError}=await db.from("marketpro_box_connections").update({
 token_iv:encode(iv),token_ciphertext:encode(cipher),
 access_expires_at:new Date(expires).toISOString(),
 refresh_generation:conn.refresh_generation+1,
 refresh_lease_id:null,refresh_lease_until:null,
 updated_at:new Date().toISOString()
 }).eq("workspace_id",W).eq("refresh_generation",conn.refresh_generation)
 .eq("refresh_lease_id",lease).eq("updated_at",conn.updated_at)
 .select("refresh_generation").maybeSingle();
 if(saveError||saved?.refresh_generation!==conn.refresh_generation+1)throw Error("BOX_REAUTHORIZE");
 return pair.access_token;
 }finally{
 if(!consumed)await db.from("marketpro_box_connections")
 .update({refresh_lease_id:null,refresh_lease_until:null})
 .eq("workspace_id",W).eq("refresh_lease_id",lease);
 }
}
function boxHeaders(token:string){return {Authorization:"Bearer "+token};}
async function verifyBoxFile(token:string,id:string,photo:string,sha1:string,bytes:number){
 if(!/^\d{1,32}$/.test(id))throw Error("BOX_FILE_INVALID");
 const resp=await fetch("https://api.box.com/2.0/files/"+id+"?fields=id,name,sha1,size,parent",{
 headers:boxHeaders(token),signal:AbortSignal.timeout(18000)});
 if(!resp.ok)throw Error("BOX_FILE_NOT_ACCESSIBLE");
 const f=await resp.json();
 if(String(f.id)!==id||String(f.parent?.id)!==F||f.name!==photo+".jpg"||
 Number(f.size)!==bytes||String(f.sha1).toLowerCase()!==sha1)
 throw Error("BOX_FILE_INTEGRITY_MISMATCH");
}
async function boxUpload(token:string,id:string,body:Uint8Array):Promise<{boxId:string,sha1:string}>{
 const sha1=await digest(body,"SHA-1");
 const form=new FormData();
 form.append("attributes",JSON.stringify({name:id+".jpg",parent:{id:F}}));
 form.append("file",new Blob([body],{type:"image/jpeg"}),id+".jpg");
 const res=await fetch("https://upload.box.com/api/2.0/files/content",{
 method:"POST",headers:boxHeaders(token),body:form,signal:AbortSignal.timeout(75000)});
 let fileId="";
 if(res.status===201){const value=await res.json();fileId=String(value.entries?.[0]?.id||"");}
 else if(res.status===409){
 const value=await res.json().catch(()=>null);
 const conflict=value?.context_info?.conflicts;
 fileId=String(Array.isArray(conflict)?conflict[0]?.id:conflict?.id||"");
 }else throw Error(res.status===401?"BOX_REAUTHORIZE":"BOX_UPLOAD_UNAVAILABLE");
 await verifyBoxFile(token,fileId,id,sha1,body.length);
 return {boxId:fileId,sha1};
}
async function acquire(db:any,id:string){
 const lease=crypto.randomUUID(),now=new Date();
 const record={photo_id:id,workspace_id:W,lease_id:lease,
 lease_until:new Date(now.getTime()+120000).toISOString()};
 const {error}=await db.from("marketpro_photo_upload_locks").insert(record);
 if(error){
 const {data:stale}=await db.from("marketpro_photo_upload_locks")
 .update({lease_id:lease,lease_until:record.lease_until})
 .eq("photo_id",id).eq("workspace_id",W).lt("lease_until",now.toISOString())
 .select("lease_id").maybeSingle();
 if(stale?.lease_id!==lease)throw Error("PHOTO_UPLOAD_BUSY");
 }
 return lease;
}
function today(){
 return new Intl.DateTimeFormat("en-CA",{timeZone:"Asia/Colombo",year:"numeric",
 month:"2-digit",day:"2-digit"}).format(new Date());
}
export default {fetch:withSupabase({auth:"user"},async(req:any,ctx:any)=>{
 if(req.method!=="POST")return say(405,"METHOD_NOT_ALLOWED");
 const jwt=ctx?.jwtClaims,user=ctx?.userClaims;
 if(!jwt||!user||jwt.sub!==user.id||user.role!=="authenticated"||
 jwt.is_anonymous===true||!UUID.test(jwt.session_id||""))
 return say(403,"SIGN_IN_REQUIRED");
 const action=req.headers.get("X-MP-Photo-Action")||"";
 if(!["upload","list","download"].includes(action))return say(400,"UNKNOWN_ACTION");
 const device=req.headers.get("X-MP-Device")||"";
 const ts=Number(req.headers.get("X-MP-Timestamp")||NaN);
 const nonce=req.headers.get("X-MP-Nonce")||"";
 const signature=req.headers.get("X-MP-Signature")||"";
 const length=Number(req.headers.get("Content-Length")||0);
 if(!UUID.test(device)||!Number.isFinite(length)||length>MAX)return say(400,"INVALID_INPUT");
 let bytes:Uint8Array;
 try{bytes=new Uint8Array(await req.arrayBuffer());}catch{return say(400,"INVALID_BODY");}
 if(bytes.length===0||bytes.length>MAX)return say(413,"INVALID_BODY_SIZE");
 const hash=await digest(bytes);
 if(hash!==req.headers.get("X-MP-Body-Sha256"))return say(403,"HASH_MISMATCH");
 const db=ctx.supabaseAdmin;
 try{
 const [member,registered,key]=await Promise.all([
 db.from("workspace_members").select("role").eq("workspace_id",W).eq("user_id",user.id).maybeSingle(),
 db.from("registered_devices").select("enabled,retired_at,user_id").eq("workspace_id",W)
 .eq("id",device).eq("user_id",user.id).maybeSingle(),
 db.from("marketpro_photo_device_keys").select("public_key_spki,status")
 .eq("workspace_id",W).eq("device_id",device).eq("user_id",user.id).maybeSingle()
 ]);
 if(member.error||registered.error||key.error||
 !["owner","editor"].includes(member.data?.role)||
 !registered.data?.enabled||registered.data.retired_at!==null||
 key.data?.status!=="approved")return say(403,"PHOTO_DEVICE_NOT_APPROVED");
 if(!await verify(action,device,ts,nonce,signature,key.data.public_key_spki,hash))
 return say(403,"INVALID_PHONE_SIGNATURE");
 const {error:replayError}=await db.from("marketpro_photo_request_nonces")
 .insert({workspace_id:W,device_id:device,nonce});
 if(replayError)return say(409,"REPLAY_REJECTED");
 if(action==="upload"){
 const item=req.headers.get("X-MP-Item")||"",photo=req.headers.get("X-MP-Photo")||"";
 const day=req.headers.get("X-MP-Day")||"",captured=req.headers.get("X-MP-Captured-At")||"";
 if(!UUID.test(item)||!UUID.test(photo)||!/^\d{4}-\d{2}-\d{2}$/.test(day)||
 !Number.isFinite(Date.parse(captured))||bytes.length<100||
 bytes[0]!==255||bytes[1]!==216)return say(400,"INVALID_IMAGE_METADATA");
 const serverToday=today();
 if(day>serverToday||(member.data.role==="editor"&&day!==serverToday))
 return say(403,"HISTORICAL_PHOTOS_OWNER_ONLY");
 const {data:itemRow,error:itemErr}=await db.from("items").select("id")
 .eq("workspace_id",W).eq("id",item).is("deleted_at",null).maybeSingle();
 if(itemErr||!itemRow)return say(409,"CLOUD_ITEM_NOT_FOUND");
 const {data:existing,error:existingError}=await db.from("marketpro_item_photos")
 .select("item_id,business_date,sha256,origin_device_id").eq("workspace_id",W).eq("photo_id",photo).maybeSingle();
 if(existingError)return say(503,"PHOTO_METADATA_UNAVAILABLE");
 if(existing){
 if(existing.item_id!==item||existing.business_date!==day||
 existing.sha256!==hash||existing.origin_device_id!==device)
 return say(409,"PHOTO_IDENTITY_CONFLICT");
 return say(200,"ALREADY_UPLOADED",{photo_id:photo});
 }
 const lease=await acquire(db,photo);
 try{
 const token=await boxToken(db);
 const file=await boxUpload(token,photo,bytes);
 const {error:saveError}=await db.from("marketpro_item_photos").insert({
 photo_id:photo,workspace_id:W,item_id:item,business_date:day,
 origin_device_id:device,uploaded_by:user.id,box_file_id:file.boxId,
 box_sha1:file.sha1,sha256:hash,bytes:bytes.length,captured_at:captured
 });
 if(saveError)return say(503,"PHOTO_METADATA_SAVE_RETRY");
 return say(201,"UPLOADED",{photo_id:photo});
 }finally{
 await db.from("marketpro_photo_upload_locks").delete()
 .eq("photo_id",photo).eq("workspace_id",W).eq("lease_id",lease);
 }
 }
 let body:any;
 try{body=JSON.parse(new TextDecoder().decode(bytes));}catch{return say(400,"INVALID_JSON");}
 if(action==="list"){
 if(!UUID.test(body.item_id||"")||!/^\d{4}-\d{2}-\d{2}$/.test(body.business_date||""))
 return say(400,"INVALID_FILTER");
 const {data:items,error}=await db.from("marketpro_item_photos").select("photo_id,sha256,bytes,captured_at")
 .eq("workspace_id",W).eq("item_id",body.item_id).eq("business_date",body.business_date)
 .order("captured_at",{ascending:true}).limit(200);
 if(error)return say(503,"PHOTO_LIST_FAILED");
 return say(200,"OK",{photos:items||[]});
 }
 if(action==="download"){
 if(!UUID.test(body.photo_id||""))return say(400,"INVALID_PHOTO_ID");
 const {data:entry,error}=await db.from("marketpro_item_photos")
 .select("box_file_id,box_sha1,sha256,bytes,photo_id")
 .eq("workspace_id",W).eq("photo_id",body.photo_id).maybeSingle();
 if(error||!entry)return say(404,"PHOTO_NOT_FOUND");
 const token=await boxToken(db);
 await verifyBoxFile(token,entry.box_file_id,entry.photo_id,entry.box_sha1,Number(entry.bytes));
 const res=await fetch("https://api.box.com/2.0/files/"+entry.box_file_id+"/content",{
 headers:boxHeaders(token),signal:AbortSignal.timeout(45000)});
 if(!res.ok)return say(503,"PHOTO_DOWNLOAD_UNAVAILABLE");
 const received=new Uint8Array(await res.arrayBuffer());
 if(received.length>MAX||received.length!==Number(entry.bytes)||
 await digest(received)!==entry.sha256)return say(503,"DOWNLOADED_PHOTO_INTEGRITY_MISMATCH");
 return new Response(received,{status:200,headers:{...H,
 "Content-Type":"image/jpeg","Content-Disposition":"inline; filename=\"marketpro-photo.jpg\""}});
 }
 return say(400,"UNKNOWN_ACTION");
 }catch(e:any){
 const code=String(e?.message||"SERVER_ERROR");
 const safe=new Set(["BOX_NOT_CONNECTED","BOX_REAUTHORIZE","BOX_REFRESH_BUSY",
 "BOX_CONFIGURATION_UNAVAILABLE","BOX_UPLOAD_UNAVAILABLE","PHOTO_UPLOAD_BUSY",
 "BOX_FILE_INTEGRITY_MISMATCH","BOX_FILE_NOT_ACCESSIBLE"]);
 return say(code.includes("BUSY")?409:503,safe.has(code)?code:"SERVER_ERROR");
 }
})};
