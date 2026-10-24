// Pure allowlist. Workgroup legacy Owner RPC remains separate.
export const financeTypes = new Set([
  "purchase","purchase_addition","requisition",
  "daily_cash_received","daily_other_expenses"
]);
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const date = /^\d{4}-\d{2}-\d{2}$/;
const allowed={
  purchase:["business_date","item_id","quantity_grams","price_cents_per_kg"],
  purchase_addition:["business_date","item_id","quantity_grams","price_cents_per_kg"],
  requisition:["business_date","item_id","kitchen","quantity_grams"],
  daily_cash_received:["business_date","amount_cents"],
  daily_other_expenses:[
    "business_date","transport_cents","labor_charge_cents",
    "meals_drinks_cents","others_cents","remarks"
  ]
};
export const validUuid = value => typeof value==="string" && uuid.test(value);
export function validateStaffFinanceEnvelope(input) {
  if(!input||typeof input!=="object"||Array.isArray(input)||
    !validUuid(input.workspace_id)||!validUuid(input.device_id)||
    !validUuid(input.operation_id)||!validUuid(input.entity_id)||
    !financeTypes.has(input.entity_type)||
    !["create","correct"].includes(input.action)||
    (input.action==="create"&&input.expected_revision!=null)||
    (input.action==="correct"&&(!Number.isSafeInteger(input.expected_revision)||
      input.expected_revision<1))||
    !input.payload||typeof input.payload!=="object"||
    Array.isArray(input.payload))return false;
  const p=input.payload,keys=allowed[input.entity_type];
  if(!keys||Object.keys(p).length!==keys.length||
    !keys.every(k=>Object.hasOwn(p,k)))return false;
  if(!date.test(p.business_date) ||
    !Number.isFinite(Date.parse(p.business_date+"T00:00:00Z"))||
    new Date(p.business_date+"T00:00:00Z").toISOString().slice(0,10)!==p.business_date)
     return false;
  const ints=keys.filter(k=>k.endsWith("_cents")||
    k==="quantity_grams"||k==="price_cents_per_kg");
  if(!ints.every(k=>Number.isSafeInteger(p[k])&&p[k]>=0&&p[k]<=9999999999))
    return false;
  if("item_id" in p && !validUuid(p.item_id))return false;
  if("kitchen" in p &&
     (typeof p.kitchen!=="string"||!["Main Kitchen","Indian Kitchen","Pastry Kitchen"].includes(p.kitchen)))
    return false;
  if("remarks" in p && (typeof p.remarks!=="string"||
    p.remarks.length>500||p.remarks.includes("\0")))return false;
  return true;
}
export function financeRpcArgs(input) {
  if(!validateStaffFinanceEnvelope(input))throw Error("Rejected Staff finance request");
  return {
    p_workspace_id:input.workspace_id,
    p_device_id:input.device_id,
    p_operation_id:input.operation_id,
    p_entity_type:input.entity_type,
    p_entity_id:input.entity_id,
    p_action:input.action,
    p_expected_revision:input.expected_revision??null,
    p_payload:input.payload
  };
}
