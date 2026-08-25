package dev.synesthesia.core.export

import dev.synesthesia.core.billing.Entitlements

/** SPEC §4.4 canonical free/premium matrix as DATA. Resolver is authoritative. */
data class ExportLimits(
    val maxDurationMs: Long,
    val maxHeightPx: Int,
    val maxFps: Int,
    val watermark: Boolean,
    val alphaLane: Boolean,
    val styleGate: Set<String>,
)

fun interface ExportLimitsResolver {
    fun resolve(entitlements: Entitlements): ExportLimits
}
