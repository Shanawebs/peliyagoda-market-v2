-- Market Pro v4.1.5 STAGING ONLY
-- Fix the QA finance audit ledger so the already-deployed five-entity writer
-- can atomically record Daily Other Expenses operations.
--
-- Root cause observed 2026-10-27:
-- marketpro_qa_finance_apply_v1 accepts daily_other_expenses and writes the
-- domain row, but the final sync_operations insert was rejected by the older
-- six-type CHECK constraint. PostgreSQL then rolled the whole RPC back.
--
-- This migration changes only the allow-list CHECK. It does not delete,
-- rewrite, or backfill any financial row.

begin;

alter table public.sync_operations
    drop constraint if exists sync_operations_entity_type_check;

alter table public.sync_operations
    add constraint sync_operations_entity_type_check
    check (
        entity_type = any (
            array[
                'category'::text,
                'item'::text,
                'purchase'::text,
                'purchase_addition'::text,
                'requisition'::text,
                'daily_cash_received'::text,
                'daily_other_expenses'::text
            ]
        )
    );

commit;
