package io.amper.neuroos.core

/**
 * Exact storage geometry for one ggml tensor type in a specific native ABI profile.
 * [blockSize] is expressed in logical tensor elements and [typeSizeBytes] is the encoded
 * byte size of one complete block.
 */
data class GgufTensorLayout(
    val blockSize: ULong,
    val typeSizeBytes: Long
) {
    init {
        require(blockSize > 0UL) { "GGUF tensor layout blockSize must be positive" }
        require(typeSizeBytes > 0L) { "GGUF tensor layout typeSizeBytes must be positive" }
    }
}

enum class GgufUnknownTensorTypePolicy {
    /** Preserve install/tooling forward compatibility while withholding guessed byte geometry. */
    ALLOW_STRUCTURAL_ONLY,

    /** Native-load boundary: every tensor type must be explicitly covered by this ABI profile. */
    REJECT
}

/**
 * Versioned tensor-layout knowledge used by GGUF admission.
 *
 * Profiles deliberately contain only layouts whose native ABI is known. Generic admission can
 * remain forward-compatible by allowing unprofiled types to receive shape/offset/resource checks
 * only. A native-load profile can instead reject unprofiled types so JNI never becomes the first
 * component asked to interpret an unknown storage ABI.
 */
class GgufTensorLayoutProfile(
    val id: String,
    layouts: Map<Long, GgufTensorLayout>,
    val unknownTensorTypePolicy: GgufUnknownTensorTypePolicy =
        GgufUnknownTensorTypePolicy.ALLOW_STRUCTURAL_ONLY
) {
    private val layoutsByType = layouts.toMap()

    init {
        require(id.isNotBlank()) { "GGUF tensor layout profile id must not be blank" }
        require(layoutsByType.keys.all { it >= 0L }) { "GGUF tensor type ids must be non-negative" }
    }

    fun layoutFor(type: Long): GgufTensorLayout? {
        val layout = layoutsByType[type]
        require(layout != null || unknownTensorTypePolicy != GgufUnknownTensorTypePolicy.REJECT) {
            "GGUF tensor type $type is not profiled for ABI $id"
        }
        return layout
    }

    fun isProfiled(type: Long): Boolean = layoutsByType.containsKey(type)

    fun knownTypeIds(): Set<Long> = layoutsByType.keys
}

object GgufTensorLayoutProfiles {
    private val classicLayouts = mapOf(
        0L to GgufTensorLayout(1UL, 4L), // F32
        1L to GgufTensorLayout(1UL, 2L), // F16
        2L to GgufTensorLayout(32UL, 18L), // Q4_0
        3L to GgufTensorLayout(32UL, 20L), // Q4_1
        6L to GgufTensorLayout(32UL, 22L), // Q5_0
        7L to GgufTensorLayout(32UL, 24L), // Q5_1
        8L to GgufTensorLayout(32UL, 34L), // Q8_0
        9L to GgufTensorLayout(32UL, 40L), // Q8_1
        10L to GgufTensorLayout(256UL, 84L), // Q2_K
        11L to GgufTensorLayout(256UL, 110L), // Q3_K
        12L to GgufTensorLayout(256UL, 144L), // Q4_K
        13L to GgufTensorLayout(256UL, 176L), // Q5_K
        14L to GgufTensorLayout(256UL, 210L), // Q6_K
        15L to GgufTensorLayout(256UL, 292L), // Q8_K
        24L to GgufTensorLayout(1UL, 1L), // I8
        25L to GgufTensorLayout(1UL, 2L), // I16
        26L to GgufTensorLayout(1UL, 4L), // I32
        27L to GgufTensorLayout(1UL, 8L), // I64
        28L to GgufTensorLayout(1UL, 8L), // F64
        30L to GgufTensorLayout(1UL, 2L) // BF16
    )

    /** Generic admission profile retained for install-time/tooling inspection. */
    val CLASSIC_GGML = GgufTensorLayoutProfile(
        id = "ggml-classic-v1",
        layouts = classicLayouts,
        unknownTensorTypePolicy = GgufUnknownTensorTypePolicy.ALLOW_STRUCTURAL_ONLY
    )

    /**
     * Strict source-layout profile admitted by the AMPER AMI v1 compiler.
     *
     * GGUF is import data only; every tensor encoding that reaches AMPER Core execution must have
     * explicit storage geometry. Unknown source encodings fail before conversion/runtime admission.
     */
    val AMPER_AMI_V1 = GgufTensorLayoutProfile(
        id = "amper-ami-v1",
        layouts = classicLayouts,
        unknownTensorTypePolicy = GgufUnknownTensorTypePolicy.REJECT
    )
}
