-- SAFE READ-ONLY verification for *isolated staging only*.
-- Run via tools.mcp__Supabase__execute_sql ONLY with project_id
-- qqiheexpbuzohllugxrk; never source production secrets.
WITH datasets AS (
 SELECT c.relname AS relation, c.relrowsecurity AS rls_enabled,
   has_table_privilege('anon',c.oid,'SELECT') AS anon_select,
   has_table_privilege('anon',c.oid,'INSERT,UPDATE,DELETE') AS anon_any_write,
   has_table_privilege('authenticated',c.oid,'INSERT,UPDATE,DELETE') AS user_any_write
 FROM pg_class c
 JOIN pg_namespace n ON n.oid=c.relnamespace
 WHERE n.nspname='public' AND c.relkind='r'
)
SELECT count(*) AS total_tables,
  count(*) FILTER (WHERE rls_enabled) AS rls_tables,
  coalesce(bool_and(rls_enabled),false) AS every_table_rls_on,
  coalesce(bool_or(anon_select OR anon_any_write OR user_any_write),true)
    AS any_direct_unauthorized_access,
  exists(SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
   WHERE n.nspname='public' AND p.proname='marketpro_apply_change'
    AND has_function_privilege('authenticated',p.oid,'EXECUTE'))
    AS legacy_writer_available_to_app
FROM datasets;
-- Expected: total_tables = rls_tables = 11;
-- any_direct_unauthorized_access = FALSE, legacy_writer_available_to_app = FALSE.
-- Staging OWNER/STAFF Auth accounts are intentionally not created by SQL.
