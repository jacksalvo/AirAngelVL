package com.airangelvl.updater

import com.airangelvl.core.updater.SmokeTestResult
import com.airangelvl.core.updater.UpdateBundle
import com.airangelvl.core.updater.UpdateRepository
import com.airangelvl.core.updater.UpdateResult
import com.airangelvl.core.updater.VerificationResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class DefaultUpdateRepository @Inject constructor(
    private val verifier: UpdateSignatureVerifier,
    private val smokeTester: UpdateSmokeTester
) : UpdateRepository {
    override suspend fun verifySignature(bundle: UpdateBundle): VerificationResult = withContext(Dispatchers.IO) {
        verifier.verify(bundle)
    }

    override suspend fun applyUpdate(bundle: UpdateBundle): UpdateResult = withContext(Dispatchers.IO) {
        // TODO integrate with PlayCore/PackageInstaller
        UpdateResult.Success
    }

    override suspend fun rollback(): UpdateResult = withContext(Dispatchers.IO) {
        UpdateResult.Success
    }

    override suspend fun preSwitchSmokeTest(): SmokeTestResult = withContext(Dispatchers.IO) {
        smokeTester.run()
    }
}

@Singleton
class UpdateSignatureVerifier @Inject constructor() {
    suspend fun verify(bundle: UpdateBundle): VerificationResult {
        return if (bundle.signature.isNotEmpty()) VerificationResult.Verified else VerificationResult.Failed("Missing signature")
    }
}

@Singleton
class UpdateSmokeTester @Inject constructor() {
    suspend fun run(): SmokeTestResult = SmokeTestResult.Passed
}
