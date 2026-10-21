-- ==============================================================================
-- CycleCare Migration: 20261021150000_cosmetic_catalog.sql
-- Real cosmetic catalog (avatars, frames, themes, badges) replacing the
-- hardcoded VALUES catalogs inside purchase/equip RPCs. Existing item ids and
-- prices are preserved exactly; premium_included items unlock free for premium
-- members. Equip state extends to avatar art and badge slots.
-- ==============================================================================

-- 1. Catalog table
CREATE TABLE IF NOT EXISTS public.cosmetic_catalog (
    item_id TEXT PRIMARY KEY,
    kind TEXT NOT NULL CHECK (kind IN ('ARTIFACT','FRAME','THEME','AVATAR_ART','BADGE')),
    name TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    price_coins BIGINT NOT NULL DEFAULT 0 CHECK (price_coins >= 0),
    premium_included BOOLEAN NOT NULL DEFAULT FALSE,
    rarity TEXT NOT NULL DEFAULT 'CLASSIC',
    asset_key TEXT NOT NULL DEFAULT '',
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE public.cosmetic_catalog ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS "Active cosmetics visible to authenticated" ON public.cosmetic_catalog;
CREATE POLICY "Active cosmetics visible to authenticated" ON public.cosmetic_catalog
    FOR SELECT TO authenticated USING (is_active = TRUE);

-- 2. New equip slots
ALTER TABLE public.economy_profiles ADD COLUMN IF NOT EXISTS active_avatar_art TEXT;
ALTER TABLE public.economy_profiles ADD COLUMN IF NOT EXISTS active_badge TEXT;

-- 3. Seed: legacy items keep their exact ids/prices, then the new cosmetic set.
INSERT INTO public.cosmetic_catalog (item_id, kind, name, description, price_coins, premium_included, rarity, asset_key, sort_order) VALUES
    ('cycle_coin',        'ARTIFACT',   'Cycle Coin',          'The original CycleCare keepsake.',                              0,    FALSE, 'CLASSIC',   'cycle_coin',          0),
    ('artifact_moon',     'ARTIFACT',   'Moon Artifact',       'A calm lunar keepsake for your vault.',                         250,  FALSE, 'CLASSIC',   'artifact_moon',       1),
    ('artifact_bloom',    'ARTIFACT',   'Bloom Artifact',      'A flowering keepsake for your vault.',                          450,  FALSE, 'CLASSIC',   'artifact_bloom',      2),
    ('artifact_crystal',  'ARTIFACT',   'Crystal Artifact',    'A radiant crystal keepsake for your vault.',                    800,  FALSE, 'RARE',       'artifact_crystal',    3),
    ('artifact_orbit',    'ARTIFACT',   'Orbit Artifact',      'A celestial keepsake for your vault.',                          1200, FALSE, 'RARE',       'artifact_orbit',      4),
    ('frame_elegant',     'FRAME',      'Elegant Frame',       'An elegant pearl frame for your community avatar.',             350,  FALSE, 'CLASSIC',   'frame_elegant',       10),
    ('frame_pearl_glow',  'FRAME',      'Pearl Glow Frame',    'A soft glowing pearl frame for your community avatar.',         450,  FALSE, 'CLASSIC',   'frame_pearl_glow',    11),
    ('frame_gilded_orchid','FRAME',     'Gilded Orchid Frame', 'A gilded orchid frame for your community avatar.',              700,  FALSE, 'RARE',       'frame_gilded_orchid', 12),
    ('frame_celestial_3d','FRAME',      'Celestial 3D Frame',  'A dimensional celestial frame, included with Premium.',         1000, TRUE,  'SIGNATURE',  'frame_celestial_3d',  13),
    ('avatar_aurora_3d',  'AVATAR_ART', 'Aurora 3D Avatar',    'A dimensional aurora portrait for your profile and circles.',   600,  FALSE, 'RARE',       'avatar_aurora_3d',    20),
    ('avatar_luna_3d',    'AVATAR_ART', 'Luna 3D Avatar',      'A dimensional moonlit portrait for your profile and circles.',  600,  FALSE, 'RARE',       'avatar_luna_3d',      21),
    ('avatar_zuri_3d',    'AVATAR_ART', 'Zuri 3D Avatar',      'A dimensional golden portrait, included with Premium.',         750,  TRUE,  'SIGNATURE',  'avatar_zuri_3d',      22),
    ('avatar_nala_3d',    'AVATAR_ART', 'Nala 3D Avatar',      'A dimensional savanna portrait for your profile and circles.',  750,  FALSE, 'RARE',       'avatar_nala_3d',      23),
    ('avatar_imani_3d',   'AVATAR_ART', 'Imani 3D Avatar',     'A dimensional orchid portrait for your profile and circles.',   900,  FALSE, 'SIGNATURE',  'avatar_imani_3d',     24),
    ('avatar_amara_3d',   'AVATAR_ART', 'Amara 3D Avatar',     'A dimensional twilight portrait, included with Premium.',       900,  TRUE,  'SIGNATURE',  'avatar_amara_3d',     25),
    ('theme_midnight_bloom','THEME',    'Midnight Bloom',      'A deep evening color story for your app.',                      500,  FALSE, 'RARE',       'theme_midnight_bloom',30),
    ('theme_savanna_gold','THEME',      'Savanna Gold',        'A warm golden color story for your app.',                       500,  FALSE, 'RARE',       'theme_savanna_gold',  31),
    ('theme_ocean_mist',  'THEME',      'Ocean Mist',          'A cool coastal color story for your app.',                      650,  FALSE, 'RARE',       'theme_ocean_mist',    32),
    ('theme_orchid_noir', 'THEME',      'Orchid Noir',         'A dramatic orchid color story, included with Premium.',         650,  TRUE,  'SIGNATURE',  'theme_orchid_noir',   33),
    ('badge_cycle_sage',  'BADGE',      'Cycle Sage Badge',    'A sage badge shown beside your name in circles.',               800,  FALSE, 'RARE',       'badge_cycle_sage',    40),
    ('badge_community_star','BADGE',    'Community Star Badge','A star badge shown beside your name in circles.',               800,  FALSE, 'RARE',       'badge_community_star',41)
ON CONFLICT (item_id) DO NOTHING;

-- 4. Purchase: catalog-driven, premium_included unlocks free for premium tier.
CREATE OR REPLACE FUNCTION public.purchase_economy_item(p_item_id TEXT)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_user_id TEXT := auth.uid()::TEXT;
    v_item RECORD;
    v_profile RECORD;
    v_unlocked JSONB;
    v_is_premium BOOLEAN;
BEGIN
    IF v_user_id IS NULL THEN RETURN jsonb_build_object('success', FALSE, 'message', 'Authentication required'); END IF;
    IF public.is_cyclecare_account_restricted(v_user_id) THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'This account is suspended or banned');
    END IF;
    SELECT * INTO v_item FROM public.cosmetic_catalog WHERE item_id = p_item_id AND is_active = TRUE;
    IF NOT FOUND OR v_item.price_coins <= 0 THEN RETURN jsonb_build_object('success', FALSE, 'message', 'Item is not available'); END IF;
    SELECT * INTO v_profile FROM public.economy_profiles WHERE user_id = v_user_id FOR UPDATE;
    IF NOT FOUND THEN RETURN jsonb_build_object('success', FALSE, 'message', 'Economy profile not found'); END IF;
    v_unlocked := CASE
        WHEN jsonb_typeof(v_profile.unlocked_items) = 'array' THEN v_profile.unlocked_items
        ELSE '["cycle_coin"]'::JSONB
    END;
    IF v_unlocked ? p_item_id THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'Item already unlocked');
    END IF;
    v_is_premium := UPPER(COALESCE(v_profile.tier, 'FREE')) = 'PREMIUM';
    IF v_item.premium_included AND v_is_premium THEN
        UPDATE public.economy_profiles SET unlocked_items = v_unlocked || jsonb_build_array(p_item_id), updated_at = NOW()
        WHERE user_id = v_user_id;
        RETURN jsonb_build_object('success', TRUE, 'message', 'Included with Premium - item unlocked', 'item_id', p_item_id);
    END IF;
    IF COALESCE(v_profile.coin_balance, 0) < v_item.price_coins THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'Insufficient Cycle Coins');
    END IF;
    UPDATE public.economy_profiles SET coin_balance = COALESCE(coin_balance, 0) - v_item.price_coins,
        lifetime_spent = COALESCE(lifetime_spent, 0) + v_item.price_coins,
        unlocked_items = v_unlocked || jsonb_build_array(p_item_id),
        active_artifact = CASE WHEN v_item.kind = 'ARTIFACT' THEN p_item_id ELSE active_artifact END,
        active_frame = CASE WHEN v_item.kind = 'FRAME' THEN p_item_id ELSE active_frame END,
        active_theme = CASE WHEN v_item.kind = 'THEME' THEN p_item_id ELSE active_theme END,
        active_avatar_art = CASE WHEN v_item.kind = 'AVATAR_ART' THEN p_item_id ELSE active_avatar_art END,
        active_badge = CASE WHEN v_item.kind = 'BADGE' THEN p_item_id ELSE active_badge END,
        updated_at = NOW()
    WHERE user_id = v_user_id;
    RETURN jsonb_build_object('success', TRUE, 'message', 'Item unlocked', 'item_id', p_item_id);
END;
$$;
REVOKE ALL ON FUNCTION public.purchase_economy_item(TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.purchase_economy_item(TEXT) TO authenticated;

-- 5. Equip: ownership from unlocked_items, or premium_included while premium.
CREATE OR REPLACE FUNCTION public.equip_economy_item(p_item_id TEXT)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_user_id TEXT := auth.uid()::TEXT;
    v_item RECORD;
    v_profile RECORD;
    v_unlocked JSONB;
    v_is_premium BOOLEAN;
BEGIN
    IF v_user_id IS NULL THEN RETURN jsonb_build_object('success', FALSE, 'message', 'Authentication required'); END IF;
    IF public.is_cyclecare_account_restricted(v_user_id) THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'This account is suspended or banned');
    END IF;
    SELECT * INTO v_item FROM public.cosmetic_catalog WHERE item_id = p_item_id AND is_active = TRUE;
    IF NOT FOUND THEN RETURN jsonb_build_object('success', FALSE, 'message', 'Item is not available'); END IF;
    SELECT * INTO v_profile FROM public.economy_profiles WHERE user_id = v_user_id FOR UPDATE;
    IF NOT FOUND THEN RETURN jsonb_build_object('success', FALSE, 'message', 'Economy profile not found'); END IF;
    v_unlocked := CASE
        WHEN jsonb_typeof(v_profile.unlocked_items) = 'array' THEN v_profile.unlocked_items
        ELSE '["cycle_coin"]'::JSONB
    END;
    v_is_premium := UPPER(COALESCE(v_profile.tier, 'FREE')) = 'PREMIUM';
    IF NOT (v_unlocked ? p_item_id) AND NOT (v_item.premium_included AND v_is_premium) THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'Item is not unlocked');
    END IF;
    UPDATE public.economy_profiles SET
        active_artifact = CASE WHEN v_item.kind = 'ARTIFACT' THEN p_item_id ELSE active_artifact END,
        active_frame = CASE WHEN v_item.kind = 'FRAME' THEN p_item_id ELSE active_frame END,
        active_theme = CASE WHEN v_item.kind = 'THEME' THEN p_item_id ELSE active_theme END,
        active_avatar_art = CASE WHEN v_item.kind = 'AVATAR_ART' THEN p_item_id ELSE active_avatar_art END,
        active_badge = CASE WHEN v_item.kind = 'BADGE' THEN p_item_id ELSE active_badge END,
        updated_at = NOW()
    WHERE user_id = v_user_id;
    RETURN jsonb_build_object('success', TRUE, 'item_id', p_item_id);
END;
$$;
REVOKE ALL ON FUNCTION public.equip_economy_item(TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.equip_economy_item(TEXT) TO authenticated;
