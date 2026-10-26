// REVIEW ONLY: do NOT deploy while Owner bootstrap gate / Android isolation
// remain unreviewed. Staging project qqiheexpbuzohllugxrk only.
// Pinned SDK; never disable JWT verification or expose an admin API key.
import {withSupabase} from "npm:@supabase/server@1.9.2";
import {
  handleOwnerBootstrapIntent,MAX_BODY_CHARS,validOwnerBootstrapClaims
} from "../_shared/owner_bootstrap_edge_core.mjs";

const HEADERS={"Cache-Control":"no-store","X-Content-Type-Options":"nosniff"};
const deny=()=>Response.json({ok:false,error:"Owner bootstrap denied"},
  {status:403,headers:HEADERS});

export default {
  fetch:withSupabase({auth:"user"},async(req,ctx)=>{
    if(req.method!=="POST")return new Response("Method not allowed",
      {status:405,headers:HEADERS});
    if(Number(req.headers.get("content-length")??0)>MAX_BODY_CHARS)
      return new Response("Payload too large",{status:413,headers:HEADERS});

    // Fail closed before hitting any privileged RPC, even if a valid AAL1
    // JWT is presented. The enrolled TOTP alone is NOT fresh AAL2.
    if(!validOwnerBootstrapClaims(ctx))return deny();

    let body:unknown;
    try {
      const raw=await req.text();
      if(raw.length>MAX_BODY_CHARS)
        return new Response("Payload too large",{status:413,headers:HEADERS});
      body=JSON.parse(raw);
    }catch{return deny();}

    try {
      const result=await handleOwnerBootstrapIntent(ctx,body);
      return Response.json(result,{
        status:result.ok===true?200:403,
        headers:HEADERS
      });
    }catch{
      // Never log JWT, code, SPKI, signature, nonce, database error details.
      return deny();
    }
  })
};
