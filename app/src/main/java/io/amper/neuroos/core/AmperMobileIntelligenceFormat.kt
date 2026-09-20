package io.amper.neuroos.core

/**
 * Canonical AMPER mobile-model container contract.
 *
 * GGUF and other model formats are import sources only. AMI is the runtime-facing mobile format.
 * A valid AMI v1 container always retains one canonical foundation-weight section so disposable,
 * hardware-specific execution profiles can never become the only copy of the imported intelligence.
 */
object AmperMobileIntelligenceFormat {
    const val FILE_EXTENSION: String = ".ami"
    const val MAGIC_ASCII: String = "AMI1"
    const val MAJOR_VERSION: Int = 1
    const val MINOR_VERSION: Int = 0

    /** Runtime-critical sections are page aligned for mmap-friendly Android access. */
    const val PAGE_ALIGNMENT_BYTES: Int = 4096

    /** Tensor payload starts are at least one common ARM cache line apart/aligned. */
    const val TENSOR_ALIGNMENT_BYTES: Int = 64

    const val MAX_SECTIONS: Int = 64
    const val MAX_EXECUTION_PROFILES: Int = 8

    val mandatorySections: Set<AmiSectionType> = setOf(
        AmiSectionType.MANIFEST,
        AmiSectionType.TOKENIZER,
        AmiSectionType.GRAPH_IR,
        AmiSectionType.TENSOR_INDEX,
        AmiSectionType.FOUNDATION_WEIGHTS,
        AmiSectionType.INTEGRITY
    )
}

@JvmInline
value class AmiArchitectureId(val value: String) {
    init {
        require(value.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}"))) {
            "AMI architecture id must be a stable lowercase identifier"
        }
    }
}

enum class AmiSourceFormat {
    GGUF,
    AMI,
    OTHER
}

/**
 * Conversion fidelity is explicit. SOURCE_EXACT is the default contract and forbids lossy
 * requantization of the canonical foundation weights. Hardware execution profiles may still
 * repack tensor layout losslessly.
 */
enum class AmiPrecisionPolicy {
    SOURCE_EXACT,
    MIXED_PRECISION,
    COMPACT_QUANTIZED
}

enum class AmiSectionType {
    MANIFEST,
    TOKENIZER,
    GRAPH_IR,
    TENSOR_INDEX,

    /** Canonical imported intelligence; never hardware-specific and never disposable. */
    FOUNDATION_WEIGHTS,

    /**
     * Optional prepacked weights/layout for one Android hardware profile.
     * It is a cache/acceleration representation and cannot replace FOUNDATION_WEIGHTS.
     */
    EXECUTION_PROFILE,

    QUANTIZATION_TABLE,
    KV_PROFILE,

    /** AMPER-owned learning delta; kept separate from the immutable foundation intelligence. */
    ADAPTATION_DELTA,

    INTEGRITY
}

enum class AmiHardwareFeature {
    ARM64,
    NEON,
    DOTPROD,
    I8MM,
    FP16,
    VULKAN,
    VULKAN_FP16,
    VULKAN_INT8
}

data class AmiSourceLineage(
    val sourceFormat: AmiSourceFormat,
    val sourceSha256: String,
    val sourceByteLength: Long,
    val sourcePrecisionPreserved: Boolean
) {
    init {
        require(sourceSha256.matches(Regex("[0-9a-f]{64}"))) {
            "AMI source digest must be lowercase SHA-256"
        }
        require(sourceByteLength > 0)
    }
}

data class AmiManifest(
    val architecture: AmiArchitectureId,
    val tensorCount: Int,
    val vocabularySize: Int,
    val source: AmiSourceLineage,
    val canonicalPrecisionPolicy: AmiPrecisionPolicy = AmiPrecisionPolicy.SOURCE_EXACT,
    val contextTiers: Set<Int> = sortedSetOf(512, 1024, 2048, 4096)
) {
    init {
        require(tensorCount > 0)
        require(vocabularySize > 0)
        require(contextTiers.isNotEmpty())
        require(contextTiers.all { it > 0 })
        require(contextTiers.toList() == contextTiers.sorted()) {
            "AMI context tiers must be unique and ascending"
        }
        if (canonicalPrecisionPolicy == AmiPrecisionPolicy.SOURCE_EXACT) {
            require(source.sourcePrecisionPreserved) {
                "SOURCE_EXACT AMI manifest requires source precision preservation"
            }
        }
    }
}

data class AmiExecutionProfile(
    val profileId: Int,
    val requiredFeatures: Set<AmiHardwareFeature>,
    val precisionPolicy: AmiPrecisionPolicy,
    val contextTier: Int,
    val preferredThreads: Int,
    val gpuLayerHint: Int = 0
) {
    init {
        require(profileId in 1..AmperMobileIntelligenceFormat.MAX_EXECUTION_PROFILES)
        require(AmiHardwareFeature.ARM64 in requiredFeatures) {
            "AMI v1 execution profiles target Android ARM64"
        }
        require(contextTier > 0)
        require(preferredThreads > 0)
        require(gpuLayerHint >= 0)
    }
}

/**
 * Logical section descriptor. Binary offsets/lengths are validated before any native mmap.
 *
 * profileId=0 denotes canonical/shared content. EXECUTION_PROFILE sections must bind to a positive
 * profile id; other section types are canonical in v1.
 */
data class AmiSectionDescriptor(
    val type: AmiSectionType,
    val offset: Long,
    val length: Long,
    val alignmentBytes: Int,
    val sha256: String,
    val profileId: Int = 0
) {
    init {
        require(offset >= 0)
        require(length > 0)
        require(alignmentBytes > 0 && alignmentBytes.countOneBits() == 1) {
            "AMI section alignment must be a positive power of two"
        }
        require(offset % alignmentBytes == 0L) {
            "AMI section offset must satisfy its declared alignment"
        }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) {
            "AMI section digest must be lowercase SHA-256"
        }
        if (type == AmiSectionType.EXECUTION_PROFILE) {
            require(profileId in 1..AmperMobileIntelligenceFormat.MAX_EXECUTION_PROFILES)
        } else {
            require(profileId == 0) {
                "only EXECUTION_PROFILE sections may use a non-zero profile id in AMI v1"
            }
        }

        if (
            type == AmiSectionType.FOUNDATION_WEIGHTS ||
            type == AmiSectionType.EXECUTION_PROFILE ||
            type == AmiSectionType.ADAPTATION_DELTA
        ) {
            require(alignmentBytes >= AmperMobileIntelligenceFormat.PAGE_ALIGNMENT_BYTES) {
                "runtime tensor sections must be page aligned"
            }
        }
    }

    val endExclusive: Long
        get() = Math.addExact(offset, length)
}

data class AmiContainerIndex(
    val manifest: AmiManifest,
    val sections: List<AmiSectionDescriptor>,
    val executionProfiles: List<AmiExecutionProfile> = emptyList()
) {
    init {
        validate()
    }

    fun validate() {
        require(sections.isNotEmpty())
        require(sections.size <= AmperMobileIntelligenceFormat.MAX_SECTIONS)

        AmperMobileIntelligenceFormat.mandatorySections.forEach { required ->
            require(sections.count { it.type == required } == 1) {
                "AMI v1 requires exactly one " + required + " section"
            }
        }

        require(sections.count { it.type == AmiSectionType.FOUNDATION_WEIGHTS } == 1) {
            "AMI canonical foundation intelligence must never be replaced by execution profiles"
        }

        require(executionProfiles.distinctBy { it.profileId }.size == executionProfiles.size) {
            "AMI execution profile ids must be unique"
        }
        require(
            executionProfiles.size <= AmperMobileIntelligenceFormat.MAX_EXECUTION_PROFILES
        )

        val declaredProfileIds = executionProfiles.mapTo(linkedSetOf()) { it.profileId }
        val sectionProfileIds = sections.asSequence()
            .filter { it.type == AmiSectionType.EXECUTION_PROFILE }
            .mapTo(linkedSetOf()) { it.profileId }
        require(sectionProfileIds == declaredProfileIds) {
            "AMI execution-profile sections and manifest profiles must match exactly"
        }

        val ordered = sections.sortedBy { it.offset }
        ordered.zipWithNext().forEach { (left, right) ->
            require(left.endExclusive <= right.offset) {
                "AMI sections must not overlap: " + left.type + " and " + right.type
            }
        }

        val foundation = sections.single { it.type == AmiSectionType.FOUNDATION_WEIGHTS }
        require(
            foundation.alignmentBytes >= AmperMobileIntelligenceFormat.PAGE_ALIGNMENT_BYTES
        )

        if (manifest.canonicalPrecisionPolicy == AmiPrecisionPolicy.SOURCE_EXACT) {
            require(manifest.source.sourcePrecisionPreserved) {
                "AMI SOURCE_EXACT foundation must preserve imported source precision"
            }
        }
    }
}
