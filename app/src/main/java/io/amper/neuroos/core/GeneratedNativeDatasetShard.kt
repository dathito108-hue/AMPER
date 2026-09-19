package io.amper.neuroos.core

/**
 * Canonical contract for any AMPER-generated dataset payload that may be supplied to the native
 * trainer. Goal-experience, Reflex/System-1 and future generated datasets must implement this same
 * contract instead of extending NativeTrainingRequest with type-specific parallel fields.
 */
interface GeneratedNativeDatasetShard {
    val manifest: NativeDatasetShardManifest
    val payload: String

    val authorityBearing: Boolean
        get() = false
}

/**
 * Read-only resolver used by the single canonical native-training pipeline.
 * A store may expose only immutable shards already registered in NativeModelFoundation.
 */
fun interface GeneratedNativeDatasetShardStore {
    fun getGeneratedShard(id: NativeDatasetShardId): GeneratedNativeDatasetShard?
}
