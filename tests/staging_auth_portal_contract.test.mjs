import test from "node:test";
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {runInNewContext} from "node:vm";

const html=readFileSync(new URL("../tools/staging-auth/marketpro-staging-auth-setup.html",import.meta.url),"utf8");
const source=html.match(/<script>([\s\S]+?)<\/script>/)?.[1];
assert.ok(source,"Staging portal script missing");
const exactOrigin="https://qqiheexpbuzohllugxrk.supabase.co";
const forbidden="lkoptqhodgjeuwwfihfr";
function setup(onFetch){
  const elements={};
  const ids=["email","password","result","signup","signin","clear","auth-form"];
  for(const id of ids){
    elements[id]={value:"",disabled:false,dataset:{},
      handlers:{},addEventListener(type,listener){this.handlers[type]=listener;}};
  }
  const events=[];
  const fetch=async (url,options)=>{
    events.push({url,options});
    return onFetch(url,options);
  };
  runInNewContext(source,{
    document:{getElementById:(id)=>elements[id]},
    fetch,AbortSignal
  },{timeout:1800,filename:"local-staging-auth-page.js"});
  async function trigger(target,type){
    const e=elements[target].handlers[type];
    assert.equal(typeof e,"function","Missing handler for "+target+"/"+type);
    let prevented=false;e({preventDefault(){prevented=true;}});
    await new Promise(resolve=>setImmediate(resolve));
    return prevented;
  }
  return {elements,events,trigger};
}
function response(status,data){
  return {ok:status>=200&&status<300,status,
    async json(){return data;}};
}
test("staging portal is ONE pinned public-host origin, no token persistence/production/referrer",()=>{
  assert.ok(html.includes("connect-src "+exactOrigin));
  assert.ok(!html.includes(forbidden),"Do not include production project ref anywhere");
  assert.ok(html.includes("No cookies"));
  assert.ok(html.includes('http-equiv="Content-Security-Policy"'));
  assert.ok(!html.includes("service_role"),"No backend secrets in HTML");
  assert.ok(!source.includes("localStorage")&&!source.includes("sessionStorage"));
  assert.ok(!source.includes("console.log")&&!source.includes("document.cookie"));
  assert.ok(!source.includes("eval(")&&!source.includes("window.open("));
  assert.ok(source.includes('referrerPolicy:"no-referrer"'));
  assert.ok(source.includes('cache:"no-store"'));
  assert.ok(source.includes('redirect:"error"'));
});
test("user-controlled email/password signup only reaches Staging HTTPS Auth; clears password",async()=>{
  const app=setup((url,opt)=>{
    assert.equal(url,exactOrigin+"/auth/v1/signup");
    assert.equal(opt.method,"POST");
    assert.ok(opt.signal);
    assert.equal(opt.redirect,"error");
    assert.equal(opt.referrerPolicy,"no-referrer");
    assert.equal(opt.cache,"no-store");
    assert.equal(opt.headers.Authorization,undefined);
    assert.ok(opt.headers.apikey.startsWith("sb_publishable_"));
    assert.deepEqual(JSON.parse(opt.body),{
      email:"owner.qa@example.com",password:"Owner-password-1234"
    });
    return response(200,{user:{id:"test-user-not-yet-verified"}});
  });
  const {elements,events,trigger}=app;
  elements.email.value=" Owner.QA@example.com ";
  elements.password.value="Owner-password-1234";
  assert.equal(await trigger("auth-form","submit"),true);
  assert.equal(events.length,1);
  assert.equal(elements.password.value,"");
  assert.equal(elements.result.dataset.state,"success");
  assert.match(elements.result.textContent,/Check your mailbox/);
  assert.doesNotMatch(elements.result.textContent,/Owner-password-1234/);
});
test("short passwords blocked locally, nothing sent to remote server",async()=>{
  const a=setup(()=>{throw Error("no calls expected")});
  a.elements.email.value="staff.qa@example.com";
  a.elements.password.value="short";
  await a.trigger("auth-form","submit");
  assert.equal(a.events.length,0);
  assert.equal(a.elements.password.value,"");
  assert.equal(a.elements.result.dataset.state,"error");
});
test("login verifies staging /auth/v1/user and confirmed email; does not persist JWT",async()=>{
  const requests=[];
  const a=setup((url,opt)=>{
    requests.push({url,opt});
    if(url.endsWith("/token?grant_type=password"))return response(200,{access_token:"TEMP_JWT",refresh_token:"TEMP_REFRESH"});
    if(url.endsWith("/user")){
      assert.equal(opt.method,"GET");
      assert.equal(opt.headers.Authorization,"Bearer TEMP_JWT");
      return response(200,{email:"staff.qa@example.com",id:"test-uuid-123",
        email_confirmed_at:"2026-10-25T12:00:00Z"});
    }
    throw Error("blocked unknown host/path");
  });
  a.elements.email.value="staff.qa@example.com";
  a.elements.password.value="STAFF-password-22222";
  await a.trigger("signin","click");
  assert.equal(requests.length,2);
  assert.equal(requests[0].url,exactOrigin+"/auth/v1/token?grant_type=password");
  assert.equal(requests[1].url,exactOrigin+"/auth/v1/user");
  assert.equal(a.elements.result.dataset.state,"success");
  assert.match(a.elements.result.textContent,/test-uuid-123/);
  assert.ok(!a.elements.result.textContent.includes("TEMP_JWT"));
  assert.ok(!a.elements.result.textContent.includes("TEMP_REFRESH"));
  assert.equal(a.elements.password.value,"");
});
test("unconfirmed or mismatched accounts are never marked verified",async()=>{
  for(const bad of [
    {email:"owner.qa@example.com",id:"some-uuid",email_confirmed_at:null},
    {email:"wrong@example.com",id:"some-uuid",email_confirmed_at:"2026-10-25"},
  ]){
    const a=setup(url=>url.includes("/token?")?
      response(200,{access_token:"TOKEN"}):
      response(200,bad));
    a.elements.email.value="owner.qa@example.com";
    a.elements.password.value="Owner-password-1234";
    await a.trigger("signin","click");
    assert.notEqual(a.elements.result.dataset.state,"success");
    assert.equal(a.elements.password.value,"");
  }
});
