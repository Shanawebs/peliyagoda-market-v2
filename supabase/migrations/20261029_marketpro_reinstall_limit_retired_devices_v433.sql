-- Market Pro v4.3.3: retain audit history without exhausting device enrollment.
-- The legacy request limit counted retired phones; after enough legitimate
-- re-installs a user could never register another replacement. Exclude only
-- previously retired devices. No rows are deleted or permissions granted.
--
-- Assert the exact known legacy function body fragment before replacement,
-- aborting on drift/concurrent project changes.
do $migration$
declare
    v_before text;
    v_after text;
    v_old constant text :=
      'WHERE workspace_id=p_workspace_id AND user_id=v_uid)>=8';
    v_new constant text :=
      'WHERE workspace_id=p_workspace_id AND user_id=v_uid AND retired_at IS NULL)>=8';
begin
    select pg_catalog.pg_get_functiondef(
       'public.marketpro_qa_cloud_request_phone(uuid,uuid,text)'::pg_catalog.regprocedure
    ) into v_before;
    if v_before is null or pg_catalog.strpos(v_before,v_old)=0
       or pg_catalog.strpos(v_before,v_new)>0 then
       raise exception 'Cloud phone request RPC drift; do not alter unexpected function';
    end if;
    v_after := pg_catalog.replace(v_before,v_old,v_new);
    execute v_after;
end
$migration$;
