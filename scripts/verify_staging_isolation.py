#!/usr/bin/env python3
"""Read-only Market Pro Android <-> Supabase hosted STAGING isolation audit.

Refuses to mark staging safe if:
* project ref, URL, Owner account or workspace equals the existing Market Pro cloud
* APK/applicationId is not separate
* production project hostname or Owner/Workspace UUID is still hardcoded in
  the staging-targeted Android sources
* any required Staff security server switch is unset/ambiguous

Never connects to Supabase, stores passwords/tokens or mutates any file.
CI "audit" reports not-ready successfully, "enforce" exits nonzero until the
future dedicated staging build is actually disconnected from production.
"""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import re
import sys
from urllib.parse import urlsplit
from uuid import UUID

PROJECT_ROOT = Path(__file__).resolve().parents[1]
CLOUD = PROJECT_ROOT / "app/src/main/java/lk/peliyagoda/marketpro/cloud"
GRADLE = PROJECT_ROOT / "app/build.gradle.kts"
PROD_REF_RE = re.compile(r'https://([a-z0-9]{20})\.supabase\.co')
PROD_OWNER_RE = re.compile(r'const val OWNER_USER_ID\s*=\s*"([a-f0-9-]{36})"')
PROD_WORKSPACE_RE = re.compile(r'const val WORKSPACE_ID\s*=\s*"([a-f0-9-]{36})"')
APP_ID_RE = re.compile(r'applicationId\s*=\s*"([^"]+)"')
FLAGS = (
    "MARKETPRO_STAFF_LOGIN_ENABLED",
    "MARKETPRO_TEAM_PULL_RPC_DEPLOYED",
    "MARKETPRO_STAFF_PAIRING_RPC_DEPLOYED",
    "MARKETPRO_OWNER_TOTP_MFA_AUTH_DEPLOYED",
    "MARKETPRO_STAFF_KEY_ENROLLMENT_DEPLOYED",
    "MARKETPRO_STAFF_WRITE_APPROVAL_RPC_DEPLOYED",
    "MARKETPRO_STAFF_FINANCE_RPC_DEPLOYED",
    "MARKETPRO_STAFF_DEVICE_PROOF_WRITE_ENFORCED",
    "MARKETPRO_STAFF_OUTBOX_AUTO_UPLOAD",
)
UUID_RE = re.compile(r"^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$")
PROJECT_REF_RE = re.compile(r"^[a-z0-9]{20}$")
PKG_RE = re.compile(r"^lk\.peliyagoda\.marketpro(?:\.[a-z][a-z0-9_]{1,30}){2,5}$")

def require_uuid(value: object, what: str) -> str:
    if not isinstance(value,str) or not UUID_RE.fullmatch(value):
        raise ValueError(f"{what} must be an exact canonical lowercase UUID")
    if str(UUID(value)) != value:
        raise ValueError(f"{what} must be normalized")
    return value

def source_baseline(root: Path) -> dict:
    gradle=(root/"app/build.gradle.kts").read_text(encoding="utf-8")
    source=(root/"app/src/main/java/lk/peliyagoda/marketpro/cloud/CloudSyncEngine.kt").read_text(encoding="utf-8")
    def match(regex, body, name):
        m=regex.search(body)
        if m is None:
            raise ValueError(f"Original production {name} cannot be verified; fail closed")
        return m.group(1)
    prod_ref=match(PROD_REF_RE,gradle,"Supabase project")
    prod_owner=require_uuid(match(PROD_OWNER_RE,source,"Owner identity"),"prod_owner")
    prod_workspace=require_uuid(match(PROD_WORKSPACE_RE,source,"workspace"),"prod_workspace")
    app_id=match(APP_ID_RE,gradle,"APK application ID")
    return dict(prod_ref=prod_ref,prod_owner=prod_owner,
                prod_workspace=prod_workspace,production_app_id=app_id)

def validate_manifest(obj: object, baseline: dict)->dict:
    if not isinstance(obj,dict):
        raise ValueError("Staging manifest must be a JSON object")
    expected={"project_ref","supabase_url","owner_user_id","workspace_id","application_id"}
    if set(obj)!=expected:
        raise ValueError("Manifest requires exactly project_ref, supabase_url, owner_user_id, workspace_id, application_id; no secrets")
    ref=obj["project_ref"]
    if not isinstance(ref,str) or not PROJECT_REF_RE.fullmatch(ref):
        raise ValueError("Staging project_ref must be 20 lowercase ASCII letters or digits")
    if ref==baseline["prod_ref"]:
        raise ValueError("Staging must NOT point at the existing Market Pro Supabase project")
    url=obj["supabase_url"]
    if not isinstance(url,str):
        raise ValueError("Staging URL must be HTTPS")
    parts=urlsplit(url)
    if parts.scheme!="https" or parts.netloc!=ref+".supabase.co" or parts.username or parts.password or parts.port or parts.path not in ("","/") or parts.query or parts.fragment:
        raise ValueError("Staging URL must be exact HTTPS project-ref.supabase.co origin without extras")
    owner=require_uuid(obj["owner_user_id"],"owner_user_id")
    workspace=require_uuid(obj["workspace_id"],"workspace_id")
    if owner==baseline["prod_owner"] or workspace==baseline["prod_workspace"] or owner==workspace:
        raise ValueError("Staging MUST use distinct Owner Auth and Workspace UUIDs")
    app_id=obj["application_id"]
    if not isinstance(app_id,str) or not PKG_RE.fullmatch(app_id) or app_id==baseline["production_app_id"]:
        raise ValueError("Staging applicationId must have its own distinct safe package name")
    return obj

def audit(root: Path, manifest: dict)->dict:
    prod=source_baseline(root)
    validate_manifest(manifest,prod)
    cloud=root/"app/src/main/java/lk/peliyagoda/marketpro/cloud"
    gradle=(root/"app/build.gradle.kts").read_text(encoding="utf-8")
    issues=[]
    for path in sorted(cloud.rglob("*.kt")):
        body=path.read_text(encoding="utf-8")
        for term,label in [
            (prod["prod_ref"],"production-host-ref"),
            (prod["prod_owner"],"production-owner-id"),
            (prod["prod_workspace"],"production-workspace-id")
        ]:
            if term in body:
                issues.append(f"{path.relative_to(root)}: {label} still hardcoded")
    if prod["prod_ref"] in gradle:
        issues.append("app/build.gradle.kts: production-host-ref still compiled as default")
    if manifest["project_ref"] not in gradle:
        issues.append("app/build.gradle.kts: no separate STAGING project ref is compiled")
    if manifest["application_id"] not in gradle:
        issues.append("app/build.gradle.kts: no distinct STAGING applicationId variant exists")
    for flag in FLAGS:
        if f'"{flag}", "false"' not in gradle:
            issues.append("app/build.gradle.kts: missing safe preview default for "+flag)
    # No live permissions are enabled in the existing review build: this is
    # a staging BLOCKER report, not a bypass to flip release booleans.
    if '"MARKETPRO_LOCAL_PREVIEW", "true"' not in gradle:
        issues.append("app/build.gradle.kts: review build did not retain offline safety flag")
    return {
        "staging_safe_to_deploy":len(issues)==0,
        "staging_project_ref":manifest["project_ref"],
        "blocked_reason_count":len(issues),
        "blockers":issues,
        "note":"Read-only audit only. Production flags/SQL/APK never modified.",
    }

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest",help="Non-secret proposed staging identity JSON")
    parser.add_argument("--mode",choices=("audit","enforce"),default="enforce")
    p=parser.parse_args()
    try:
        obj=json.loads(Path(p.manifest).read_text(encoding="utf-8"))
        result=audit(PROJECT_ROOT,obj)
    except (ValueError,OSError,UnicodeError) as err:
        print(json.dumps({"staging_safe_to_deploy":False,"error":str(err)}))
        return 2
    print(json.dumps(result,indent=2))
    return 2 if p.mode=="enforce" and not result["staging_safe_to_deploy"] else 0

if __name__=="__main__":
    sys.exit(main())
