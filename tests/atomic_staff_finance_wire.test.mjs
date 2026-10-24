import {test} from "node:test";
import assert from "node:assert/strict";
import { financeRpcArgs,validateStaffFinanceEnvelope } from "../supabase/functions/_shared/finance_wire.mjs";

const base=Object.freeze({
 workspace_id:"11111111-1111-4111-8111-111111111111",
 device_id:"f1111111-1111-4111-8111-111111111111",
 operation_id:"a1111111-1111-4111-8111-111111111111",
 entity_type:"purchase",
 entity_id:"b1111111-1111-4111-8111-111111111111",
 action:"create",expected_revision:null,
 payload:{business_date:"2026-10-24",
  item_id:"d1111111-1111-4111-8111-111111111111",
  quantity_grams:2200,price_cents_per_kg:13000}
});
test("Only exact financial intent whitelist is allowed",()=>{
 assert.equal(validateStaffFinanceEnvelope(base),true);
 assert.equal(Object.keys(financeRpcArgs(base)).length,8);
 for(const bad of [
  {...base,entity_type:"item"}, {...base,entity_type:"invoice_photo"},
  {...base,action:"delete"}, {...base,expected_revision:1},
  {...base,device_id:"copied"}, {...base,payload:{...base.payload,file_path:"/sdcard/a.jpg"}},
  {...base,payload:{...base.payload,quantity_grams:-5}},
  {...base,payload:{...base.payload,price_cents_per_kg:0.5}},
  {...base,payload:{...base.payload,business_date:"2026-02-30"}},
  {...base,payload:{...base.payload,item_id:"wrong"}},
 ]) assert.equal(validateStaffFinanceEnvelope(bad),false,JSON.stringify(bad));
});
test("all four expenses and remarks are individually included",()=>{
 const e={...base,entity_type:"daily_other_expenses",payload:{
  business_date:"2026-10-24",transport_cents:100,
  labor_charge_cents:230,meals_drinks_cents:400,others_cents:125,remarks:"OK"
 }};
 assert.equal(validateStaffFinanceEnvelope(e),true);
 assert.equal(validateStaffFinanceEnvelope({...e,payload:{...e.payload,
   invoice_photo:"photo"}}),false);
 assert.equal(validateStaffFinanceEnvelope({...e,payload:{...e.payload,
   transport_cents:-1}}),false);
});
test("revisions required on corrections, never synthetic zero baseline",()=>{
 assert.equal(validateStaffFinanceEnvelope({...base,action:"correct",expected_revision:2}),true);
 assert.equal(validateStaffFinanceEnvelope({...base,action:"correct",expected_revision:null}),false);
 assert.equal(validateStaffFinanceEnvelope({...base,action:"correct",expected_revision:0}),false);
});
