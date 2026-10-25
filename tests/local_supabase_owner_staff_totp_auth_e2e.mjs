#!/usr/bin/env node
/**
 * E2E against *disposable* Supabase CLI GoTrue at 127.0.0.1 ONLY.
 * This script refuses production/remote hosts and cannot write Market Pro data.
 * No API tokens, TOTP secrets, passwords or individual MFA codes are logged.
 */
import assert from "node:assert/strict";
import {createHmac, randomBytes} from "node:crypto";

const LOCAL="http://127.0.0.1:54321";
const origin=process.env.MARKETPRO_LOCAL_SUPABASE_URL;
const apiKey=process.env.MARKETPRO_LOCAL_SUPABASE_KEY;
assert.equal(origin,LOCAL,
  "FATAL: isolated Owner/Staff Auth E2E MUST use local Supabase at 127.0.0.1:54321");
assert.ok(apiKey && apiKey.length>=20 && !/\s/.test(apiKey),
  "Local-only Supabase publishable/anon key not supplied");
assert.ok(!origin.includes("lkoptqhodgjeuwwfihfr"),"Never run against Market Pro Cloud");

const uuid=/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i;
async function call(path,method="GET",body,token){
  assert.ok(/^\/auth\/v1\/[A-Za-z0-9?=&/_-]+$/.test(path) &&
    !path.includes("admin"),"No non-Auth, remote or admin endpoint allowed");
  const headers={apikey:apiKey,Accept:"application/json"};
  if(body!==undefined)headers["Content-Type"]="application/json";
  if(token)headers.Authorization="Bearer "+token;
  const response=await fetch(LOCAL+path,{
    method,headers,redirect:"error",signal:AbortSignal.timeout(15000),
    ...(body!==undefined?{body:JSON.stringify(body)}:{})
  });
  let parsed;
  try{parsed=await response.json()}catch{parsed={}};
  return {status:response.status,data:parsed};
}
function success(result,label){
  assert.ok(result.status>=200 && result.status<300,
    label+" declined by LOCAL Supabase Auth (HTTP "+result.status+")");
  return result.data;
}
function validToken(response,label){
  assert.ok(response && typeof response.access_token==="string" &&
    response.access_token.split(".").length===3,label+" missing JWT");
  assert.ok(response.refresh_token?.length>=10,label+" missing refresh token");
  return response;
}
function claims(accessToken){
  const parts=accessToken.split(".");
  assert.equal(parts.length,3);
  return JSON.parse(Buffer.from(parts[1],"base64url").toString("utf8"));
}
function totpCode(secret,step=Math.floor(Date.now()/30000)){
  // RFC 6238 TOTP/HMAC-SHA1, six digits; no third-party package.
  const alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  const raw=secret.toUpperCase().replace(/=+$/,"");
  assert.match(raw,/^[A-Z2-7]{16,256}$/);
  let buffer=0,bits=0,bytes=[];
  for(const ch of raw) {
    buffer=(buffer<<5)|alphabet.indexOf(ch);
    bits+=5;
    if(bits>=8){bits-=8;bytes.push((buffer>>>bits)&255);}
  }
  const counter=Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(step));
  const mac=createHmac("sha1",Buffer.from(bytes)).update(counter).digest();
  const pos=mac[mac.length-1]&0xf;
  return ((mac.readUInt32BE(pos)&0x7fffffff)%1000000).toString().padStart(6,"0");
}
const randomEmail=(prefix)=>prefix+"-"+randomBytes(10).toString("hex")+"@example.com";
const password=()=>randomBytes(25).toString("base64url")+"Z9!";

console.log("[LOCAL ONLY] Starting isolated Owner and Staff Supabase Auth smoke test.");
// Deliberately never touch Market Pro production Owner account or Supabase workspace.
const ownerEmail=randomEmail("marketpro-owner-ci");
const ownerPassword=password();
const signedUpOwner=success(await call("/auth/v1/signup","POST",{
  email:ownerEmail,password:ownerPassword
}),"local Owner signup");
assert.equal(signedUpOwner.user?.email,ownerEmail);
const ownerLogin=validToken(success(await call(
  "/auth/v1/token?grant_type=password","POST",{
  email:ownerEmail,password:ownerPassword
}),"local Owner password login"),"Owner login");
const initial=claims(ownerLogin.access_token);
assert.ok(uuid.test(initial.sub));
assert.equal(initial.aal,"aal1","Owner without MFA must begin at AAL1");
assert.ok(uuid.test(initial.session_id),"Real Owner session_id required");
const ownerUser=success(await call("/auth/v1/user","GET",undefined,
  ownerLogin.access_token),"read authenticated Owner profile");
assert.equal(ownerUser.id,initial.sub);
assert.equal(ownerUser.email,ownerEmail);

const enrolled=success(await call("/auth/v1/factors","POST",{
  factor_type:"totp",friendly_name:"Market Pro Local CI",issuer:"Market Pro CI"
},ownerLogin.access_token),"local TOTP factor enrollment");
assert.ok(uuid.test(enrolled.id),"Authenticator must provide real factor UUID");
assert.equal(enrolled.type,"totp","Expected TOTP enrollment");
const setupSecret=enrolled.totp?.secret;
assert.ok(typeof setupSecret==="string" && setupSecret.length>=16,
  "Supabase did not provide TOTP seed for LOCAL first-time setup");
const challenged=success(await call(
  "/auth/v1/factors/"+enrolled.id+"/challenge","POST",{},ownerLogin.access_token
),"local TOTP challenge");
assert.ok(uuid.test(challenged.id),"Challenge UUID missing");
// Stay inside the current TOTP 30-second window; server permits bounded drift.
let verified;
for(const step of [
  Math.floor(Date.now()/30000),
  Math.floor(Date.now()/30000)-1,
  Math.floor(Date.now()/30000)+1
]){
  const result=await call("/auth/v1/factors/"+enrolled.id+"/verify","POST",{
    challenge_id:challenged.id,code:totpCode(setupSecret,step)
  },ownerLogin.access_token);
  if(result.status>=200&&result.status<300){verified=result.data;break;}
}
assert.ok(verified,"Supabase local Auth did not confirm TOTP factor");
validToken(verified,"Owner TOTP verify");
const upgraded=claims(verified.access_token);
assert.equal(upgraded.sub,initial.sub,"Owner account switched after MFA");
assert.equal(upgraded.session_id,initial.session_id,
  "MFA must upgrade this Owner Auth session, not select a different account");
assert.equal(upgraded.aal,"aal2","Successful Owner TOTP must upgrade to AAL2");
const recent=upgraded.amr?.find(m=>m.method==="totp");
assert.ok(recent && Number.isInteger(recent.timestamp),
  "Actual TOTP signed AMR method+timestamp missing");
assert.ok(Math.abs(Math.floor(Date.now()/1000)-recent.timestamp)<=300,
  "Server requires TOTP auth within the last five minutes");
const upgradedOwner=success(await call("/auth/v1/user","GET",undefined,
  verified.access_token),"Owner AAL2 profile");
assert.equal(upgradedOwner.id,initial.sub);
assert.ok((upgradedOwner.factors??[]).some(
  v=>v.id===enrolled.id && v.status==="verified" &&
    v.factor_type==="totp"),"MFA factor not marked verified in Supabase Auth");

const refreshed=validToken(success(await call(
 "/auth/v1/token?grant_type=refresh_token","POST",{
   refresh_token:verified.refresh_token
 }),"Owner session refresh after MFA"),"Owner refresh");
const afterRefresh=claims(refreshed.access_token);
assert.equal(afterRefresh.sub,initial.sub);
assert.equal(afterRefresh.aal,"aal2",
  "Owner cannot silently regress to AAL1 when reading encrypted refreshed session");
assert.ok(afterRefresh.amr?.some(m=>m.method==="totp"),
  "Refreshed Owner Auth must preserve original verified TOTP AMR");

const staffEmail=randomEmail("marketpro-staff-ci");
const staffPassword=password();
const staffSignup=success(await call("/auth/v1/signup","POST",{
  email:staffEmail,password:staffPassword
}),"separate Staff Auth signup");
assert.equal(staffSignup.user?.email,staffEmail);
const staffLogin=validToken(success(await call(
  "/auth/v1/token?grant_type=password","POST",{
  email:staffEmail,password:staffPassword
}),"separate Staff Auth login"),"Staff login");
const staffJwt=claims(staffLogin.access_token);
assert.ok(uuid.test(staffJwt.sub));
assert.notEqual(staffJwt.sub,initial.sub,
  "Purchasing Team MUST use independent identity, not Owner credentials");
assert.equal(staffJwt.aal,"aal1","Staff password login must not inherit Owner MFA");
assert.ok(!staffJwt.amr?.some(m=>m.method==="totp"),
  "Staff login must not inherit Owner Authenticator proof");
const staffUser=success(await call("/auth/v1/user","GET",undefined,
  staffLogin.access_token),"independent Staff profile");
assert.equal(staffUser.id,staffJwt.sub);
assert.equal(staffUser.email,staffEmail);

// Gmail ignores dots before @gmail.com; third-party Auth may or may not
// canonicalize that mailbox. Establish the actual GoTrue outcome, WITHOUT
// touching real user mailboxes, remote project, production Auth or SMTP.
// These random .gmail.com usernames are never used beyond disposable GoTrue.
const localSuffix="marketproqa"+randomBytes(8).toString("hex");
const aliasOwner=localSuffix+"@gmail.com";
const aliasStaff="marketpro.qa"+localSuffix.slice("marketpro".length)+"@gmail.com";
assert.equal(aliasOwner.replace(/\./g,""),aliasStaff.replace(/\./g,""),
  "Test alias pair does not map to the SAME Gmail mailbox");
const aliasOwnerPassword=password();
const aliasStaffPassword=password();
const ownerAliasSignup=success(await call("/auth/v1/signup","POST",{
  email:aliasOwner,password:aliasOwnerPassword
}),"local synthetic undotted Gmail signup");
assert.equal(ownerAliasSignup.user?.email,aliasOwner);
const staffAliasSignup=success(await call("/auth/v1/signup","POST",{
  email:aliasStaff,password:aliasStaffPassword
}),"local synthetic dotted Gmail signup");
assert.equal(staffAliasSignup.user?.email,aliasStaff);
assert.ok(uuid.test(ownerAliasSignup.user.id));
assert.ok(uuid.test(staffAliasSignup.user.id));
assert.notEqual(ownerAliasSignup.user.id,staffAliasSignup.user.id,
  "GoTrue DID NOT separate dotted and undotted Gmail-like login identities");
const undottedLogin=validToken(success(await call(
  "/auth/v1/token?grant_type=password","POST",{
    email:aliasOwner,password:aliasOwnerPassword
  }),"local undotted Gmail password session"),"alias-owner");
const dottedLogin=validToken(success(await call(
  "/auth/v1/token?grant_type=password","POST",{
    email:aliasStaff,password:aliasStaffPassword
  }),"local dotted Gmail password session"),"alias-staff");
const undottedClaims=claims(undottedLogin.access_token);
const dottedClaims=claims(dottedLogin.access_token);
assert.equal(undottedClaims.sub,ownerAliasSignup.user.id);
assert.equal(dottedClaims.sub,staffAliasSignup.user.id);
assert.notEqual(undottedClaims.sub,dottedClaims.sub);
assert.notEqual(undottedClaims.session_id,dottedClaims.session_id);
assert.equal(undottedClaims.aal,"aal1");
assert.equal(dottedClaims.aal,"aal1");
const swappedOwner=await call("/auth/v1/token?grant_type=password","POST",{
  email:aliasOwner,password:aliasStaffPassword
});
const swappedStaff=await call("/auth/v1/token?grant_type=password","POST",{
  email:aliasStaff,password:aliasOwnerPassword
});
assert.equal(swappedOwner.status,400,"Staff password must NOT open Owner user");
assert.equal(swappedStaff.status,400,"Owner password must NOT open Staff user");
const realUndotted=success(await call("/auth/v1/user","GET",
  undefined,undottedLogin.access_token),"local synthetic undotted identity");
const realDotted=success(await call("/auth/v1/user","GET",
  undefined,dottedLogin.access_token),"local synthetic dotted identity");
assert.equal(realUndotted.id,ownerAliasSignup.user.id);
assert.equal(realDotted.id,staffAliasSignup.user.id);
// Critically, unique GoTrue subjects do NOT imply Gmail mailbox recovery
// independence; Google's dot aliases still share one real mailbox.
console.log("PASS: synthetic dotted/undotted Gmail aliases have DISTINCT local GoTrue identities");
console.log("PASS: distinct passwords, login sessions and JWT subjects; cross-account password is rejected");
console.log("LIMITATION: both aliases share Gmail inbox/password recovery; STAGING TEST ONLY");

console.log("PASS: real LOCAL Owner signup/login, Authenticator enrollment,");
console.log("PASS: TOTP challenge/verify, Owner AAL2 and fresh totp AMR,");
console.log("PASS: encrypted-token-compatible refresh stays AAL2,");
console.log("PASS: separate Staff password session is AAL1 with different UUID.");
console.log("PASS: test is locked to localhost; NO hosted/project financial data accessed.");
