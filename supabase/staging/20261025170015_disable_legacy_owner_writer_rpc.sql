-- Applied to isolated STAGING project qqiheexpbuzohllugxrk ONLY on 2026-10-25.
-- Audit trail: Supabase migration marketpro_staging_disable_legacy_owner_writer_rpc.
-- Absolutely NEVER apply this override to the existing Owner production database.
-- Existing V1 single-writer RPC is inert until a separately audited 2-phone cutover.
REVOKE EXECUTE ON FUNCTION public.marketpro_apply_change(
  uuid,uuid,uuid,text,uuid,text,jsonb
) FROM PUBLIC, anon, authenticated;
COMMENT ON FUNCTION public.marketpro_apply_change(
  uuid,uuid,uuid,text,uuid,text,jsonb
) IS 'STAGING QUARANTINE: legacy single-writer RPC disabled for anon/authenticated; no client rollout permitted.';
