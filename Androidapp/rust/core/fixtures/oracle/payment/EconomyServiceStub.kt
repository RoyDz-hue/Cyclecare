package com.cyclecare.app.data.economy

sealed class EconomyResult<out T> {
    data class Success<out T>(val data: T, val message: String = "") : EconomyResult<T>()
    data class Error(val errorMessage: String) : EconomyResult<Nothing>()
}

class SupabaseEconomyService {
    suspend fun fetchVerifiedProfile(userId: String): EconomyResult<EconomyProfile> = EconomyResult.Error("stub")
}
