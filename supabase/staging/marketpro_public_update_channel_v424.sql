-- Market Pro v4.2.4 read-only update metadata fallback.
-- Only release version, public APK link, exact SHA256, and APK size are exposed.
-- No existing Kitchen, purchasing, auth, device, finance, or sync table is changed.
CREATE TABLE IF NOT EXISTS public.marketpro_public_update_channel (
    channel text PRIMARY KEY,
    version_name text NOT NULL CHECK (version_name ~ '^4[.][0-9]+[.][0-9]+$'),
    version_code integer NOT NULL CHECK (version_code > 0),
    apk_url text NOT NULL CHECK (
        apk_url LIKE
        'https://github.com/Shanawebs/peliyagoda-market-v2/releases/download/%'
    ),
    sha256 text NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    size_bytes bigint NOT NULL CHECK (size_bytes BETWEEN 1 AND 52428800),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT marketpro_channel_allowed CHECK (channel = 'marketpro-staging')
);

ALTER TABLE public.marketpro_public_update_channel ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.marketpro_public_update_channel FROM PUBLIC, anon, authenticated;
GRANT SELECT ON public.marketpro_public_update_channel TO anon, authenticated;

DROP POLICY IF EXISTS marketpro_public_update_read ON public.marketpro_public_update_channel;
CREATE POLICY marketpro_public_update_read
ON public.marketpro_public_update_channel
FOR SELECT TO anon, authenticated
USING (channel = 'marketpro-staging');

INSERT INTO public.marketpro_public_update_channel
    (channel, version_name, version_code, apk_url, sha256, size_bytes)
VALUES (
    'marketpro-staging',
    '4.2.3',
    423,
    'https://github.com/Shanawebs/peliyagoda-market-v2/releases/download/marketpro-menu-order-qa-v4.2.3/MarketPro-v4.2.3-MenuOrder-QA-signed.apk',
    '6a95292574b06cfe90aa02feca474122c1a6a5749af9a0b5d951c5327d4506a0',
    13638284
)
ON CONFLICT (channel) DO NOTHING;

-- Publishing a NEW QA/Stable/Hotfix release requires updating this one
-- metadata row only AFTER GitHub CI signature and digest verification passes.
-- Example release-only operation:
-- UPDATE public.marketpro_public_update_channel
-- SET version_name=?, version_code=?, apk_url=?, sha256=?, size_bytes=?,
--     updated_at=now()
-- WHERE channel='marketpro-staging' AND version_code < ?;
