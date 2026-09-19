package io.amper.neuroos.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

data class StoredReflexLinearArtifact(
    val sha256: String,
    val byteCount: Long
) {
    init {
        require(sha256.matches(Regex("[0-9a-f]{64}")))
        require(byteCount > 0L)
    }
}

interface ReflexLinearArtifactStore {
    fun put(bytes: ByteArray): StoredReflexLinearArtifact
    fun load(sha256: String): ByteArray?
}

class InMemoryReflexLinearArtifactStore : ReflexLinearArtifactStore {
    private val artifacts = linkedMapOf<String, ByteArray>()

    @Synchronized
    override fun put(bytes: ByteArray): StoredReflexLinearArtifact {
        require(bytes.isNotEmpty())
        val sha = reflexLinearSha256(bytes)
        artifacts[sha]?.let { existing ->
            require(existing.contentEquals(bytes)) {
                "Reflex artifact digest collision"
            }
        } ?: run {
            artifacts[sha] = bytes.copyOf()
        }
        return StoredReflexLinearArtifact(sha, bytes.size.toLong())
    }

    @Synchronized
    override fun load(sha256: String): ByteArray? {
        require(sha256.matches(Regex("[0-9a-f]{64}")))
        return artifacts[sha256]?.copyOf()
    }

    @Synchronized
    fun size(): Int = artifacts.size
}

class FileReflexLinearArtifactStore(
    private val rootDir: File
) : ReflexLinearArtifactStore {
    init {
        require(rootDir.mkdirs() || rootDir.isDirectory) {
            "unable to create Reflex artifact directory"
        }
    }

    @Synchronized
    override fun put(bytes: ByteArray): StoredReflexLinearArtifact {
        require(bytes.isNotEmpty() && bytes.size <= ReflexLinearModelCodec.MAX_ARTIFACT_BYTES)
        val sha = reflexLinearSha256(bytes)
        val target = artifactFile(sha)
        if (target.exists()) {
            val existing = target.readBytes()
            require(reflexLinearSha256(existing) == sha && existing.contentEquals(bytes)) {
                "existing Reflex artifact does not match immutable digest"
            }
            return StoredReflexLinearArtifact(sha, existing.size.toLong())
        }

        val temp = File(rootDir, ".$sha.tmp")
        if (temp.exists()) {
            require(temp.delete()) { "unable to clear stale Reflex artifact temp file" }
        }
        FileOutputStream(temp).use { stream ->
            stream.write(bytes)
            stream.fd.sync()
        }
        require(temp.length() == bytes.size.toLong()) {
            "Reflex artifact durable byte count mismatch"
        }
        require(reflexLinearSha256(temp.readBytes()) == sha) {
            "Reflex artifact durable digest mismatch"
        }
        require(temp.renameTo(target)) {
            "unable to atomically publish Reflex artifact"
        }
        return StoredReflexLinearArtifact(sha, target.length())
    }

    @Synchronized
    override fun load(sha256: String): ByteArray? {
        require(sha256.matches(Regex("[0-9a-f]{64}")))
        val file = artifactFile(sha256)
        if (!file.isFile) return null
        require(file.length() in 1..ReflexLinearModelCodec.MAX_ARTIFACT_BYTES.toLong()) {
            "Reflex artifact size is invalid"
        }
        val bytes = file.readBytes()
        require(reflexLinearSha256(bytes) == sha256) {
            "Reflex artifact SHA-256 mismatch"
        }
        return bytes
    }

    private fun artifactFile(sha256: String): File =
        File(rootDir, "$sha256.${ReflexLinearModelCodec.FILE_EXTENSION}")
}

class ReflexLinearModel internal constructor(
    val capabilities: List<CapabilityId>,
    internal val biases: FloatArray,
    internal val weights: FloatArray
) {
    init {
        require(capabilities.size <= MAX_CAPABILITIES)
        require(capabilities.distinct().size == capabilities.size)
        require(capabilities == capabilities.sortedBy { it.value })
        require(biases.size == CLASS_SLOTS)
        require(weights.size == PARAMETER_WEIGHTS)
        require(biases.all { it.isFinite() })
        require(weights.all { it.isFinite() })
    }

    fun predict(input: NativeReflexDecisionInput): NativeReflexDecisionPrediction {
        val projected = ReflexLinearFeatureProjector.project(
            featureHashes = input.featureHashes,
            availableCapabilities = input.availableCapabilities
        )
        val activeRows = buildList {
            add(0)
            capabilities.forEachIndexed { index, capability ->
                if (capability in input.availableCapabilities) add(index + 1)
            }
        }
        val logits = activeRows.map { row ->
            var score = biases[row].toDouble()
            val offset = row * FEATURE_DIMENSION
            projected.forEach { feature ->
                score += weights[offset + feature].toDouble()
            }
            score * LOGIT_SCALE
        }
        val max = logits.maxOrNull() ?: 0.0
        val exps = logits.map { exp((it - max).coerceIn(-60.0, 60.0)) }
        val sum = exps.sum().coerceAtLeast(1e-12)
        var winner = 0
        var winnerProbability = -1.0
        exps.forEachIndexed { index, value ->
            val probability = value / sum
            if (probability > winnerProbability) {
                winner = index
                winnerProbability = probability
            }
        }
        val row = activeRows[winner]
        val confidence = winnerProbability.coerceIn(0.0, 1.0)
        return if (row == 0) {
            NativeReflexDecisionPrediction(
                disposition = ReflexDecisionDisposition.ESCALATE_SYSTEM2,
                confidence = confidence,
                uncertainty = (1.0 - confidence).coerceIn(0.0, 1.0)
            )
        } else {
            NativeReflexDecisionPrediction(
                disposition = ReflexDecisionDisposition.PROPOSE_ACTION,
                capability = capabilities[row - 1],
                confidence = confidence,
                uncertainty = (1.0 - confidence).coerceIn(0.0, 1.0)
            )
        }
    }

    companion object {
        const val FEATURE_DIMENSION = 4096
        const val MAX_CAPABILITIES = 32
        const val CLASS_SLOTS = MAX_CAPABILITIES + 1
        const val PARAMETER_WEIGHTS = CLASS_SLOTS * FEATURE_DIMENSION
        const val PARAMETER_COUNT = CLASS_SLOTS * (FEATURE_DIMENSION + 1)
        const val LOGIT_SCALE = 2.0
    }
}

object ReflexLinearFeatureProjector {
    fun project(
        featureHashes: Set<String>,
        availableCapabilities: Set<CapabilityId>
    ): IntArray {
        require(featureHashes.isNotEmpty())
        val projected = linkedSetOf<Int>()
        featureHashes.sorted().forEach { feature ->
            projected += bucket("lex|$feature")
        }
        availableCapabilities
            .map { it.value }
            .sorted()
            .forEach { capability ->
                projected += bucket("available|$capability")
            }
        return projected.toIntArray()
    }

    private fun bucket(value: String): Int {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
        val positive =
            ((digest[0].toLong() and 0xffL) shl 24) or
                ((digest[1].toLong() and 0xffL) shl 16) or
                ((digest[2].toLong() and 0xffL) shl 8) or
                (digest[3].toLong() and 0xffL)
        return (positive % ReflexLinearModel.FEATURE_DIMENSION.toLong()).toInt()
    }
}

object ReflexLinearModelCodec {
    // Keep the historical .arl1 suffix so already-persisted V1 files remain discoverable.
    const val FILE_EXTENSION = "arl1"
    const val MAX_ARTIFACT_BYTES = 2_000_000
    private const val MAGIC = 0x41524c31
    private const val LEGACY_DENSE_VERSION = 1
    private const val SPARSE_VERSION = 2

    /**
     * Phase509 lossless structural compression for the mobile Reflex artifact.
     *
     * Training touches only a bounded subset of the 4,096 hashed feature buckets. V2 therefore
     * stores only non-zero weight cells while preserving FP32 values exactly. Decode remains
     * backward-compatible with dense V1 artifacts already installed on devices.
     */
    fun encode(model: ReflexLinearModel): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            writeHeader(out, SPARSE_VERSION, model)
            model.biases.forEach { out.writeFloat(it) }

            var nonZero = 0
            model.weights.forEach { value ->
                if (value != 0.0f) nonZero += 1
            }
            out.writeInt(nonZero)
            model.weights.forEachIndexed { index, value ->
                if (value != 0.0f) {
                    out.writeInt(index)
                    out.writeFloat(value)
                }
            }
        }
        return bytes.toByteArray().also {
            require(it.size <= MAX_ARTIFACT_BYTES)
        }
    }

    fun decode(bytes: ByteArray): ReflexLinearModel {
        require(bytes.isNotEmpty() && bytes.size <= MAX_ARTIFACT_BYTES)
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC) { "unsupported AMPER Reflex artifact magic" }
            val version = input.readInt()
            require(version == LEGACY_DENSE_VERSION || version == SPARSE_VERSION) {
                "unsupported AMPER Reflex artifact version"
            }
            require(input.readInt() == ReflexLinearModel.FEATURE_DIMENSION) {
                "Reflex feature dimension mismatch"
            }
            require(input.readInt() == ReflexLinearModel.CLASS_SLOTS) {
                "Reflex class-slot count mismatch"
            }
            val capabilityCount = input.readInt()
            require(capabilityCount in 0..ReflexLinearModel.MAX_CAPABILITIES)
            val capabilities = buildList {
                repeat(capabilityCount) {
                    val length = input.readInt()
                    require(length in 1..256)
                    val value = ByteArray(length)
                    input.readFully(value)
                    add(CapabilityId(String(value, StandardCharsets.UTF_8)))
                }
            }
            require(capabilities.distinct().size == capabilities.size)
            require(capabilities == capabilities.sortedBy { it.value })
            val biases = FloatArray(ReflexLinearModel.CLASS_SLOTS) { input.readFloat() }
            val weights = when (version) {
                LEGACY_DENSE_VERSION ->
                    FloatArray(ReflexLinearModel.PARAMETER_WEIGHTS) { input.readFloat() }

                SPARSE_VERSION -> {
                    val result = FloatArray(ReflexLinearModel.PARAMETER_WEIGHTS)
                    val nonZero = input.readInt()
                    require(nonZero in 0..ReflexLinearModel.PARAMETER_WEIGHTS) {
                        "Reflex sparse weight count is invalid"
                    }
                    var previousIndex = -1
                    repeat(nonZero) {
                        val index = input.readInt()
                        require(index in 0 until ReflexLinearModel.PARAMETER_WEIGHTS) {
                            "Reflex sparse weight index is invalid"
                        }
                        require(index > previousIndex) {
                            "Reflex sparse weight indexes must be strictly increasing"
                        }
                        val value = input.readFloat()
                        require(value.isFinite() && value != 0.0f) {
                            "Reflex sparse weight value is invalid"
                        }
                        result[index] = value
                        previousIndex = index
                    }
                    result
                }

                else -> error("unreachable Reflex artifact version")
            }
            require(input.available() == 0) { "unexpected trailing Reflex artifact bytes" }
            return ReflexLinearModel(capabilities, biases, weights)
        }
    }

    internal fun denseReferenceByteCount(model: ReflexLinearModel): Int {
        var bytes = 5 * Int.SIZE_BYTES
        model.capabilities.forEach { capability ->
            bytes += Int.SIZE_BYTES
            bytes += capability.value.toByteArray(StandardCharsets.UTF_8).size
        }
        bytes += ReflexLinearModel.CLASS_SLOTS * Float.SIZE_BYTES
        bytes += ReflexLinearModel.PARAMETER_WEIGHTS * Float.SIZE_BYTES
        return bytes
    }

    private fun writeHeader(
        out: DataOutputStream,
        version: Int,
        model: ReflexLinearModel
    ) {
        out.writeInt(MAGIC)
        out.writeInt(version)
        out.writeInt(ReflexLinearModel.FEATURE_DIMENSION)
        out.writeInt(ReflexLinearModel.CLASS_SLOTS)
        out.writeInt(model.capabilities.size)
        model.capabilities.forEach { capability ->
            val value = capability.value.toByteArray(StandardCharsets.UTF_8)
            require(value.size in 1..256)
            out.writeInt(value.size)
            out.write(value)
        }
    }
}

class ReflexLinearNativeTrainer(
    private val artifacts: ReflexLinearArtifactStore,
    private val epochs: Int = DEFAULT_EPOCHS
) : NativeTrainerPort {
    init {
        require(epochs in 1..MAX_EPOCHS)
    }

    override fun train(request: NativeTrainingRequest): Result<NativeTrainingArtifact> = runCatching {
        validateRequest(request)
        val payload = request.generatedDatasetPayloads.single()
        val rows = ReflexLinearTrainingParser.parse(payload.payload)
        require(rows.size.toLong() == payload.manifest.exampleCount) {
            "Reflex trainer example count does not match immutable dataset manifest"
        }
        require(rows.isNotEmpty())
        require(rows.any { it.targetCapability != null })
        require(rows.any { it.targetCapability == null })

        val currentCapabilities = rows.mapNotNull { it.targetCapability }.toSet()
        val parent = request.parentCheckpoint?.let { parentCheckpoint ->
            val bytes = requireNotNull(artifacts.load(parentCheckpoint.weightArtifactSha256)) {
                "parent Reflex artifact is unavailable"
            }
            ReflexLinearModelCodec.decode(bytes)
        }
        val capabilities = (parent?.capabilities.orEmpty() + currentCapabilities)
            .distinct()
            .sortedBy { it.value }
        require(capabilities.size <= ReflexLinearModel.MAX_CAPABILITIES) {
            "Reflex model exceeds capability output capacity"
        }

        val biases = FloatArray(ReflexLinearModel.CLASS_SLOTS)
        val weights = FloatArray(ReflexLinearModel.PARAMETER_WEIGHTS)
        parent?.let {
            biases[0] = it.biases[0]
            System.arraycopy(
                it.weights,
                0,
                weights,
                0,
                ReflexLinearModel.FEATURE_DIMENSION
            )
            capabilities.forEachIndexed { newIndex, capability ->
                val oldIndex = it.capabilities.indexOf(capability)
                if (oldIndex >= 0) {
                    biases[newIndex + 1] = it.biases[oldIndex + 1]
                    System.arraycopy(
                        it.weights,
                        (oldIndex + 1) * ReflexLinearModel.FEATURE_DIMENSION,
                        weights,
                        (newIndex + 1) * ReflexLinearModel.FEATURE_DIMENSION,
                        ReflexLinearModel.FEATURE_DIMENSION
                    )
                }
            }
        }

        val classCount = capabilities.size + 1
        val labelIndex = capabilities.withIndex().associate { (index, capability) ->
            capability to index + 1
        }
        val frequencies = IntArray(classCount)
        rows.forEach { row ->
            val label = row.targetCapability?.let {
                requireNotNull(labelIndex[it]) { "Reflex target capability is not represented" }
            } ?: 0
            frequencies[label] += 1
        }
        val classWeights = DoubleArray(classCount) { index ->
            val frequency = frequencies[index].coerceAtLeast(1)
            (rows.size.toDouble() / (classCount.toDouble() * frequency.toDouble()))
                .coerceIn(0.5, 4.0)
        }

        repeat(epochs) { epoch ->
            val learningRate =
                request.manifest.recipe.learningRate / sqrt(1.0 + epoch.toDouble() * 0.05)
            val sequence = if (epoch % 2 == 0) rows else rows.asReversed()
            sequence.forEach { row ->
                val features = ReflexLinearFeatureProjector.project(
                    row.featureHashes,
                    row.availableCapabilities
                )
                val label = row.targetCapability?.let { requireNotNull(labelIndex[it]) } ?: 0
                val probabilities = probabilities(
                    biases = biases,
                    weights = weights,
                    classCount = classCount,
                    features = features,
                    logitScale = 1.0
                )
                val sampleWeight = row.labelConfidence * classWeights[label]
                for (classIndex in 0 until classCount) {
                    val expected = if (classIndex == label) 1.0 else 0.0
                    val gradient = (probabilities[classIndex] - expected) * sampleWeight
                    biases[classIndex] =
                        (biases[classIndex] - learningRate * gradient).toFloat()
                    val offset = classIndex * ReflexLinearModel.FEATURE_DIMENSION
                    features.forEach { feature ->
                        val current = weights[offset + feature].toDouble()
                        val regularized = gradient + L2 * current
                        weights[offset + feature] =
                            (current - learningRate * regularized).toFloat()
                    }
                }
            }
        }

        val model = ReflexLinearModel(capabilities, biases, weights)
        val encoded = ReflexLinearModelCodec.encode(model)
        val stored = artifacts.put(encoded)
        val finalLoss = averageLoss(model, rows, labelIndex)
        NativeTrainingArtifact(
            runId = request.runId,
            manifestDigest = request.manifest.canonicalDigest,
            trainingBackendId = BACKEND_ID,
            weightArtifactSha256 = stored.sha256,
            outputFormat = OUTPUT_FORMAT,
            quantization = QUANTIZATION,
            artifactBytes = stored.byteCount,
            examplesSeen = rows.size.toLong(),
            completedSteps = epochs.toLong() * rows.size.toLong(),
            finalLoss = finalLoss,
            executionBindingDigest = request.executionBindingDigest,
            datasetSnapshotDigest = request.manifest.datasetSnapshotDigest,
            curriculumDigest = request.manifest.curriculumDigest
        )
    }

    private fun validateRequest(request: NativeTrainingRequest) {
        require(request.contract.ownedByAmper)
        require(request.contract.capabilities == setOf(TitanCapabilities.REFLEX_DECISION))
        require(request.contract.familyVersion == 1)
        require(request.contract.parameterCount == ReflexLinearModel.PARAMETER_COUNT.toLong())
        require(request.contract.layerCount == 1)
        require(request.contract.hiddenSize == ReflexLinearModel.FEATURE_DIMENSION)
        require(
            request.manifest.target.outputFormat.equals(OUTPUT_FORMAT, ignoreCase = true)
        )
        require(
            request.manifest.target.quantization.equals(QUANTIZATION, ignoreCase = true)
        )
        require(request.generatedDatasetPayloads.size == 1)
        require(
            request.generatedDatasetPayloads.single().kind ==
                NativeGeneratedDatasetKind.REFLEX_DECISION_EXPERIENCE
        )
    }

    private fun averageLoss(
        model: ReflexLinearModel,
        rows: List<ReflexLinearTrainingRow>,
        labelIndex: Map<CapabilityId, Int>
    ): Double {
        var total = 0.0
        var weight = 0.0
        val classCount = model.capabilities.size + 1
        rows.forEach { row ->
            val features = ReflexLinearFeatureProjector.project(
                row.featureHashes,
                row.availableCapabilities
            )
            val label = row.targetCapability?.let { requireNotNull(labelIndex[it]) } ?: 0
            val probability = probabilities(
                model.biases,
                model.weights,
                classCount,
                features,
                ReflexLinearModel.LOGIT_SCALE
            )[label].coerceAtLeast(1e-12)
            total += -ln(probability) * row.labelConfidence
            weight += row.labelConfidence
        }
        return (total / weight.coerceAtLeast(1e-12)).coerceAtLeast(0.0)
    }

    private fun probabilities(
        biases: FloatArray,
        weights: FloatArray,
        classCount: Int,
        features: IntArray,
        logitScale: Double
    ): DoubleArray {
        val logits = DoubleArray(classCount) { classIndex ->
            var score = biases[classIndex].toDouble()
            val offset = classIndex * ReflexLinearModel.FEATURE_DIMENSION
            features.forEach { feature ->
                score += weights[offset + feature].toDouble()
            }
            score * logitScale
        }
        val max = logits.maxOrNull() ?: 0.0
        val values = DoubleArray(classCount) { index ->
            exp((logits[index] - max).coerceIn(-60.0, 60.0))
        }
        val sum = values.sum().coerceAtLeast(1e-12)
        return DoubleArray(classCount) { index -> values[index] / sum }
    }

    companion object {
        const val BACKEND_ID = "amper-reflex-linear-cpu-v1"
        const val OUTPUT_FORMAT = "amper-reflex-linear-v1"
        const val QUANTIZATION = "fp32"
        const val DEFAULT_EPOCHS = 160
        const val MAX_EPOCHS = 512
        private const val L2 = 1e-5
    }
}

internal data class ReflexLinearTrainingRow(
    val featureHashes: Set<String>,
    val availableCapabilities: Set<CapabilityId>,
    val targetCapability: CapabilityId?,
    val labelConfidence: Double
)

internal object ReflexLinearTrainingParser {
    private val FEATURE = Regex("[ub]:[0-9a-f]{16}")

    fun parse(payload: String): List<ReflexLinearTrainingRow> =
        payload.lineSequence()
            .filter { it.isNotBlank() }
            .map(::parseLine)
            .toList()

    private fun parseLine(line: String): ReflexLinearTrainingRow {
        val fields = line.split('\t').associate { field ->
            val separator = field.indexOf('=')
            require(separator > 0)
            field.substring(0, separator) to field.substring(separator + 1)
        }
        val features = requireNotNull(fields["features"])
            .split(',')
            .filter { it.isNotBlank() }
            .toCollection(linkedSetOf())
        require(features.isNotEmpty())
        require(features.size <= ReflexDecisionFeatureEncoder.MAX_FEATURES)
        require(features.all { FEATURE.matches(it) })

        val available = requireNotNull(fields["available"])
            .split(',')
            .filter { it.isNotBlank() }
            .mapTo(linkedSetOf()) { CapabilityId(it) }
        require(available.size <= ReflexExperienceTrainingExample.MAX_AVAILABLE_CAPABILITIES)

        val target = requireNotNull(fields["target"])
        val capability = when {
            target == "ESCALATE_SYSTEM2" -> null
            target.startsWith("ACTION:") -> {
                val body = target.removePrefix("ACTION:")
                val separator = body.lastIndexOf(':')
                require(separator > 0)
                CapabilityId(body.substring(0, separator))
            }
            else -> error("unsupported Reflex training target")
        }
        if (capability != null) require(capability in available)

        val confidence = requireNotNull(fields["confidence"]).toDouble()
        require(confidence in 0.0..1.0)
        return ReflexLinearTrainingRow(
            featureHashes = features,
            availableCapabilities = available,
            targetCapability = capability,
            labelConfidence = confidence
        )
    }
}

class ReflexLinearDecisionPort(
    override val checkpointId: NativeCheckpointId,
    override val weightArtifactSha256: String,
    private val model: ReflexLinearModel
) : NativeReflexDecisionPort {
    override fun predict(
        input: NativeReflexDecisionInput
    ): Result<NativeReflexDecisionPrediction> = runCatching {
        model.predict(input)
    }
}

class ReflexLinearDecisionPortResolver(
    private val foundation: NativeModelFoundation,
    private val artifacts: ReflexLinearArtifactStore
) : NativeReflexDecisionPortResolver {
    override fun resolve(
        checkpointId: NativeCheckpointId,
        weightArtifactSha256: String
    ): Result<NativeReflexDecisionPort> = runCatching {
        require(weightArtifactSha256.matches(Regex("[0-9a-f]{64}")))
        val checkpoint = requireNotNull(foundation.getCheckpoint(checkpointId)) {
            "Reflex checkpoint is unavailable"
        }
        require(checkpoint.weightArtifactSha256 == weightArtifactSha256) {
            "Reflex artifact identity does not match checkpoint lineage"
        }
        val contract = requireNotNull(foundation.getContract(checkpoint.contractId))
        validateReflexLinearContract(contract)
        val bytes = requireNotNull(artifacts.load(weightArtifactSha256)) {
            "Reflex artifact bytes are unavailable"
        }
        require(reflexLinearSha256(bytes) == weightArtifactSha256)
        ReflexLinearDecisionPort(
            checkpointId = checkpointId,
            weightArtifactSha256 = weightArtifactSha256,
            model = ReflexLinearModelCodec.decode(bytes)
        )
    }
}

class ReflexLinearCheckpointEvaluator(
    private val foundation: NativeModelFoundation,
    private val artifacts: ReflexLinearArtifactStore
) : ReflexDecisionEvaluatorPort {
    override fun evaluate(
        request: ReflexDecisionEvaluationRequest
    ): Result<List<ReflexDecisionPrediction>> = runCatching {
        val contract = requireNotNull(foundation.getContract(request.checkpoint.contractId))
        validateReflexLinearContract(contract)
        val bytes = requireNotNull(artifacts.load(request.checkpoint.weightArtifactSha256)) {
            "Reflex evaluation artifact is unavailable"
        }
        require(reflexLinearSha256(bytes) == request.checkpoint.weightArtifactSha256)
        val model = ReflexLinearModelCodec.decode(bytes)
        request.examples.map { example ->
            val prediction = model.predict(
                NativeReflexDecisionInput(
                    featureHashes = example.featureHashes,
                    availableCapabilities = example.availableCapabilities
                )
            )
            ReflexDecisionPrediction(
                exampleId = example.id,
                disposition = prediction.disposition,
                capability = prediction.capability,
                confidence = prediction.confidence,
                uncertainty = prediction.uncertainty
            )
        }
    }
}

internal fun validateReflexLinearContract(contract: AmperNativeModelContract) {
    require(contract.ownedByAmper)
    require(contract.capabilities == setOf(TitanCapabilities.REFLEX_DECISION))
    require(contract.familyVersion == 1)
    require(contract.parameterCount == ReflexLinearModel.PARAMETER_COUNT.toLong())
    require(contract.layerCount == 1)
    require(contract.hiddenSize == ReflexLinearModel.FEATURE_DIMENSION)
}

internal fun reflexLinearSha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

internal fun reflexLinearSha256(value: String): String =
    reflexLinearSha256(value.toByteArray(StandardCharsets.UTF_8))
