package com.cyclecare.app.data.economy

import androidx.annotation.DrawableRes
import com.cyclecare.app.R

/**
 * Maps server catalog `asset_key` values (cosmetic_catalog) and persona
 * `avatar_asset` values (personas) to the bundled WebP art. Art is bundled at
 * 160px WebP (<8KB each) so every asset renders offline with no network fetch.
 */
object CosmeticAssets {

    @DrawableRes
    fun avatarArtRes(assetKey: String?): Int? = when (assetKey?.trim()?.lowercase()) {
        "avatar_aurora_3d" -> R.drawable.avatar_aurora_3d
        "avatar_luna_3d" -> R.drawable.avatar_luna_3d
        "avatar_zuri_3d" -> R.drawable.avatar_zuri_3d
        "avatar_nala_3d" -> R.drawable.avatar_nala_3d
        "avatar_imani_3d" -> R.drawable.avatar_imani_3d
        "avatar_amara_3d" -> R.drawable.avatar_amara_3d
        else -> null
    }

    @DrawableRes
    fun personaAvatarRes(personaId: String?): Int? = when (personaId?.trim()?.lowercase()) {
        "zuri" -> R.drawable.persona_zuri
        "amara" -> R.drawable.persona_amara
        "nala" -> R.drawable.persona_nala
        "imani" -> R.drawable.persona_imani
        "aisha" -> R.drawable.persona_aisha
        "wanjiru" -> R.drawable.persona_wanjiru
        "fatuma" -> R.drawable.persona_fatuma
        "neema" -> R.drawable.persona_neema
        "tabitha" -> R.drawable.persona_tabitha
        "mwende" -> R.drawable.persona_mwende
        else -> null
    }
}
