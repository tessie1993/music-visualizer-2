package dev.synesthesia.core.billing

/** Serverless client-side truth (D-SAFE-3): refreshed via queryPurchasesAsync. */
data class Entitlements(
    val premium: Boolean,
    val styleGate: Set<String>,
) {
    companion object {
        val FREE = Entitlements(premium = false, styleGate = emptySet())
    }
}

interface PurchasePort {
    suspend fun refresh(): Entitlements
}

interface EntitlementRepository {
    val current: kotlinx.coroutines.flow.StateFlow<Entitlements>
    suspend fun refresh()
}
