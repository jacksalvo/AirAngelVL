package com.airangelvl.core.updater

import android.net.Uri

interface UpdateRepository {
    suspend fun verifySignature(bundle: UpdateBundle): VerificationResult
    suspend fun applyUpdate(bundle: UpdateBundle): UpdateResult
    suspend fun rollback(): UpdateResult
    suspend fun preSwitchSmokeTest(): SmokeTestResult
}

data class UpdateBundle(
    val apkUri: Uri?,
    val splitUris: List<Uri>,
    val signature: ByteArray
)

sealed class VerificationResult {
    data object Verified : VerificationResult()
    data class Failed(val reason: String) : VerificationResult()
}

sealed class UpdateResult {
    data object Success : UpdateResult()
    data class Failed(val reason: String) : UpdateResult()
}

sealed class SmokeTestResult {
    data object Passed : SmokeTestResult()
    data class Failed(val checks: List<String>) : SmokeTestResult()
}
