package io.amper.neuroos.core

import java.nio.ByteOrder
import kotlin.math.max

data class AmiDecoderFfnPlan(
    val layerIndex: Int,
    val hiddenSize: Int,
    val feedForwardSize: Int,
    val rmsEpsilon: Float,
    val normWeight: AmiTensorDescriptor,
    val gateWeight: AmiTensorDescriptor,
    val upWeight: AmiTensorDescriptor,
    val downWeight: AmiTensorDescriptor
) {
    init {
        require(layerIndex >= 0)
        require(hiddenSize > 0)
        require(feedForwardSize > 0)
        require(rmsEpsilon > 0f && rmsEpsilon.isFinite())
    }
}

object AmiDecoderFfnPlanner {
    fun plan(
        graph: AmiTensorGraph,
        metadata: AmiGgufMetadataSnapshot,
        layerIndex: Int
    ): Result<AmiDecoderFfnPlan> = runCatching {
        require(layerIndex >= 0)
        val prefix = "blk.$layerIndex"
        val norm = requireTensor(graph, "$prefix.ffn_norm.weight")
        val gate = requireTensor(graph, "$prefix.ffn_gate.weight")
        val up = requireTensor(graph, "$prefix.ffn_up.weight")
        val down = requireTensor(graph, "$prefix.ffn_down.weight")

        require(norm.dimensions.size == 1) {
            "FFN norm tensor must be rank-1"
        }
        val hidden = checkedInt(norm.dimensions.single(), "hidden size")

        val gateShape = matrixShape(gate)
        val upShape = matrixShape(up)
        val downShape = matrixShape(down)

        require(gateShape.columns == hidden) {
            "FFN gate input width does not match hidden size"
        }
        require(upShape.columns == hidden) {
            "FFN up input width does not match hidden size"
        }
        require(gateShape.rows == upShape.rows) {
            "FFN gate/up output widths differ"
        }
        val ffn = gateShape.rows
        require(downShape.columns == ffn && downShape.rows == hidden) {
            "FFN down projection shape is incompatible with gate/up"
        }

        requireVectorEncoding(norm)
        requireMatrixEncoding(gate)
        requireMatrixEncoding(up)
        requireMatrixEncoding(down)

        val architecture = graph.architecture.value
        val epsilon = metadata.floating(
            "$architecture.attention.layer_norm_rms_epsilon"
        ) ?: error(
            "preserved GGUF metadata is missing " +
                "$architecture.attention.layer_norm_rms_epsilon"
        )
        require(epsilon > 0.0 && epsilon.isFinite()) {
            "invalid decoder RMSNorm epsilon"
        }

        AmiDecoderFfnPlan(
            layerIndex = layerIndex,
            hiddenSize = hidden,
            feedForwardSize = ffn,
            rmsEpsilon = epsilon.toFloat(),
            normWeight = norm,
            gateWeight = gate,
            upWeight = up,
            downWeight = down
        )
    }

    private fun requireTensor(
        graph: AmiTensorGraph,
        name: String
    ): AmiTensorDescriptor =
        requireNotNull(graph.tensor(name)) {
            "AMI decoder tensor is missing: $name"
        }

    private fun requireVectorEncoding(tensor: AmiTensorDescriptor) {
        val encoding = AmneTensorEncoding.fromGgmlType(tensor.sourceEncodingType)
        require(
            encoding == AmneTensorEncoding.F32 ||
                encoding == AmneTensorEncoding.F16
        ) {
            "AMNE decoder norm does not support source encoding " +
                tensor.sourceEncodingType
        }
        requireNotNull(tensor.storageBytes) {
            "AMI decoder norm has unknown byte footprint"
        }
    }

    private fun requireMatrixEncoding(tensor: AmiTensorDescriptor) {
        require(AmneTensorKernelPlanner.requiredPrimitive(tensor) != null) {
            "AMNE decoder matrix does not support source encoding " +
                tensor.sourceEncodingType + " for " + tensor.name
        }
        requireNotNull(tensor.storageBytes) {
            "AMI decoder matrix has unknown byte footprint"
        }
    }

    internal data class MatrixShape(
        val rows: Int,
        val columns: Int
    )

    internal fun matrixShape(tensor: AmiTensorDescriptor): MatrixShape {
        require(tensor.dimensions.size == 2) {
            "AMNE matrix tensor must be rank-2: " + tensor.name
        }
        // GGML/GGUF ne[0] is the contiguous input width, ne[1] is row count.
        return MatrixShape(
            rows = checkedInt(tensor.dimensions[1], tensor.name + " rows"),
            columns = checkedInt(tensor.dimensions[0], tensor.name + " columns")
        )
    }

    private fun checkedInt(value: ULong, label: String): Int {
        require(value in 1UL..Int.MAX_VALUE.toULong()) {
            "$label exceeds AMNE Int shape limit"
        }
        return value.toInt()
    }
}

data class AmiTensorVectorRead(
    val values: FloatArray,
    val mappedBytes: Long
)

data class AmiMatVecExecution(
    val output: FloatArray,
    val primitive: AmneKernelPrimitive,
    val backendId: String,
    val mappedBytes: Long,
    val windows: Int
)

/**
 * Bounded tensor execution over verified AMI FOUNDATION_WEIGHTS.
 *
 * Matrices are processed by row tiles. A multi-hundred-megabyte model matrix is therefore never
 * copied into one JVM array. Each tile is mmap'd read-only, converted only as required by the
 * selected AMNE ABI, executed, then released for GC/unmapping by the runtime.
 */
class AmiTensorWindowExecutor(
    private val loaded: AmiLoadedArtifact,
    private val graph: AmiTensorGraph,
    private val hardware: AmiHardwareSnapshot?,
    private val maxWindowBytes: Int = 8 * 1024 * 1024,
    private val registryProvider: () -> AmneKernelRegistry = {
        AmneProcessKernelRuntime.registry()
    }
) {
    init {
        require(maxWindowBytes > 0)
        require(graph.foundationSection.type == AmiSectionType.FOUNDATION_WEIGHTS)
    }

    private val mapped = AmiMappedSectionAccess(
        file = loaded.file,
        descriptor = graph.foundationSection,
        maxWindowBytes = maxWindowBytes
    )

    fun readVector(tensor: AmiTensorDescriptor): AmiTensorVectorRead {
        require(tensor.dimensions.size == 1)
        val count = tensor.dimensions.single()
        require(count <= Int.MAX_VALUE.toULong())
        val elements = count.toInt()
        val encoding = requireNotNull(
            AmneTensorEncoding.fromGgmlType(tensor.sourceEncodingType)
        ) {
            "unsupported AMNE vector encoding: " + tensor.sourceEncodingType
        }

        val bytesPerElement = when (encoding) {
            AmneTensorEncoding.F32 -> 4
            AmneTensorEncoding.F16 -> 2
            else -> error(
                "quantized AMNE vector encoding is not supported: " + encoding
            )
        }
        val expectedBytes = Math.multiplyExact(
            elements.toLong(),
            bytesPerElement.toLong()
        )
        require(tensor.storageBytes == expectedBytes) {
            "AMI vector byte footprint mismatch: " + tensor.name
        }
        require(expectedBytes <= maxWindowBytes.toLong()) {
            "AMI vector exceeds bounded mmap window: " + tensor.name
        }

        val buffer = mapped.mapWindow(
            relativeOffset = tensor.foundationOffset,
            length = expectedBytes.toInt()
        ).order(ByteOrder.LITTLE_ENDIAN)

        val values = FloatArray(elements)
        when (encoding) {
            AmneTensorEncoding.F32 -> {
                for (index in 0 until elements) {
                    values[index] = buffer.float
                }
            }
            AmneTensorEncoding.F16 -> {
                for (index in 0 until elements) {
                    val bits = buffer.short.toInt() and 0xffff
                    values[index] = AmneReferenceCpuKernels.halfToFloat(bits)
                }
            }
            else -> error("unreachable vector encoding")
        }

        return AmiTensorVectorRead(
            values = values,
            mappedBytes = expectedBytes
        )
    }

    fun matVec(
        tensor: AmiTensorDescriptor,
        vector: FloatArray
    ): AmiMatVecExecution {
        val shape = AmiDecoderFfnPlanner.matrixShape(tensor)
        require(vector.size == shape.columns) {
            "AMNE matrix input width mismatch for " + tensor.name
        }

        val encoding = requireNotNull(
            AmneTensorEncoding.fromGgmlType(tensor.sourceEncodingType)
        ) {
            "unsupported AMNE matrix encoding: " + tensor.sourceEncodingType
        }
        val primitive = requireNotNull(
            AmneTensorKernelPlanner.requiredPrimitive(tensor)
        ) {
            "no AMNE matrix primitive for " + tensor.name
        }
        val backend = registryProvider().backendFor(
            primitive = primitive,
            hardware = hardware
        )

        val bytesPerRow = when (encoding) {
            AmneTensorEncoding.F32 ->
                Math.multiplyExact(shape.columns, 4)
            AmneTensorEncoding.F16 ->
                Math.multiplyExact(shape.columns, 2)
            AmneTensorEncoding.Q4_0 -> {
                require(shape.columns % encoding.blockSize == 0)
                Math.multiplyExact(
                    shape.columns / encoding.blockSize,
                    encoding.blockBytes
                )
            }
            AmneTensorEncoding.Q8_0,
            AmneTensorEncoding.Q4_K,
            AmneTensorEncoding.Q5_K,
            AmneTensorEncoding.Q6_K -> {
                require(shape.columns % encoding.blockSize == 0)
                Math.multiplyExact(
                    shape.columns / encoding.blockSize,
                    encoding.blockBytes
                )
            }
        }

        val expectedBytes = Math.multiplyExact(
            shape.rows.toLong(),
            bytesPerRow.toLong()
        )
        require(tensor.storageBytes == expectedBytes) {
            "AMI matrix byte footprint mismatch: " + tensor.name
        }
        require(bytesPerRow <= maxWindowBytes) {
            "single AMI matrix row exceeds bounded mmap window: " + tensor.name
        }

        val rowsPerWindow = max(1, maxWindowBytes / bytesPerRow)
        val output = FloatArray(shape.rows)
        var rowStart = 0
        var windows = 0
        var mappedBytes = 0L

        while (rowStart < shape.rows) {
            val rows = minOf(rowsPerWindow, shape.rows - rowStart)
            val byteLength = Math.multiplyExact(rows, bytesPerRow)
            val relativeOffset = Math.addExact(
                tensor.foundationOffset,
                Math.multiplyExact(rowStart.toLong(), bytesPerRow.toLong())
            )
            val buffer = mapped.mapWindow(
                relativeOffset = relativeOffset,
                length = byteLength
            ).order(ByteOrder.LITTLE_ENDIAN)

            val tileOutput = when (encoding) {
                AmneTensorEncoding.F32 -> {
                    val matrix = FloatArray(
                        Math.multiplyExact(rows, shape.columns)
                    )
                    for (index in matrix.indices) {
                        matrix[index] = buffer.float
                    }
                    backend.matVecF32(
                        matrixRowMajor = matrix,
                        rows = rows,
                        columns = shape.columns,
                        vector = vector
                    )
                }
                AmneTensorEncoding.Q4_0 -> {
                    val bytes = ByteArray(byteLength)
                    buffer.get(bytes)
                    backend.matVecQ4_0(
                        matrixBlocks = bytes,
                        rows = rows,
                        columns = shape.columns,
                        vector = vector
                    )
                }
                AmneTensorEncoding.Q8_0 -> {
                    val bytes = ByteArray(byteLength)
                    buffer.get(bytes)
                    backend.matVecQ8_0(
                        matrixBlocks = bytes,
                        rows = rows,
                        columns = shape.columns,
                        vector = vector
                    )
                }
                AmneTensorEncoding.Q4_K -> {
                    val bytes = ByteArray(byteLength)
                    buffer.get(bytes)
                    backend.matVecQ4K(
                        matrixBlocks = bytes,
                        rows = rows,
                        columns = shape.columns,
                        vector = vector
                    )
                }
                AmneTensorEncoding.Q5_K -> {
                    val bytes = ByteArray(byteLength)
                    buffer.get(bytes)
                    backend.matVecQ5K(
                        matrixBlocks = bytes,
                        rows = rows,
                        columns = shape.columns,
                        vector = vector
                    )
                }
                AmneTensorEncoding.Q6_K -> {
                    val bytes = ByteArray(byteLength)
                    buffer.get(bytes)
                    backend.matVecQ6K(
                        matrixBlocks = bytes,
                        rows = rows,
                        columns = shape.columns,
                        vector = vector
                    )
                }
                AmneTensorEncoding.F16 -> {
                    val matrix = FloatArray(
                        Math.multiplyExact(rows, shape.columns)
                    )
                    for (index in matrix.indices) {
                        matrix[index] = AmneReferenceCpuKernels.halfToFloat(
                            buffer.short.toInt() and 0xffff
                        )
                    }
                    backend.matVecF32(
                        matrixRowMajor = matrix,
                        rows = rows,
                        columns = shape.columns,
                        vector = vector
                    )
                }
            }

            require(tileOutput.size == rows)
            tileOutput.copyInto(
                destination = output,
                destinationOffset = rowStart
            )
            rowStart += rows
            windows += 1
            mappedBytes += byteLength.toLong()
        }

        return AmiMatVecExecution(
            output = output,
            primitive = primitive,
            backendId = backend.descriptor.backendId,
            mappedBytes = mappedBytes,
            windows = windows
        )
    }
}

data class AmiDecoderFfnTrace(
    val layerIndex: Int,
    val hiddenSize: Int,
    val feedForwardSize: Int,
    val normBackendId: String,
    val gateBackendId: String,
    val upBackendId: String,
    val activationBackendId: String,
    val downBackendId: String,
    val mappedBytes: Long,
    val matrixWindows: Int,
    val wallTimeMs: Long
)

data class AmiDecoderFfnExecutionResult(
    val output: FloatArray,
    val trace: AmiDecoderFfnTrace
)

/**
 * First neural execution path over AMI model weights.
 *
 * Implements the decoder FFN residual sub-block:
 *
 *   h_norm = RMSNorm(h)
 *   gate   = W_gate * h_norm
 *   up     = W_up   * h_norm
 *   ffn    = SwiGLU(gate, up)
 *   down   = W_down * ffn
 *   out    = h + down
 *
 * Every primitive is dispatched through the Phase605 qualified registry.
 */
class AmiDecoderFfnExecutor(
    private val maxWindowBytes: Int = 8 * 1024 * 1024
) {
    fun execute(
        loaded: AmiLoadedArtifact,
        graph: AmiTensorGraph,
        plan: AmiDecoderFfnPlan,
        input: FloatArray,
        hardware: AmiHardwareSnapshot?
    ): Result<AmiDecoderFfnExecutionResult> = runCatching {
        require(input.size == plan.hiddenSize)
        require(input.all { it.isFinite() })

        val startedNs = System.nanoTime()
        val tensorExecutor = AmiTensorWindowExecutor(
            loaded = loaded,
            graph = graph,
            hardware = hardware,
            maxWindowBytes = maxWindowBytes
        )
        val registry = AmneProcessKernelRuntime.registry()

        val normWeight = tensorExecutor.readVector(plan.normWeight)
        val normBackend = registry.backendFor(
            AmneKernelPrimitive.RMS_NORM_F32,
            hardware
        )
        val normalized = normBackend.rmsNormF32(
            input = input,
            weight = normWeight.values,
            epsilon = plan.rmsEpsilon
        )

        val gate = tensorExecutor.matVec(
            tensor = plan.gateWeight,
            vector = normalized
        )
        val up = tensorExecutor.matVec(
            tensor = plan.upWeight,
            vector = normalized
        )

        val activationBackend = registry.backendFor(
            AmneKernelPrimitive.SWIGLU_F32,
            hardware
        )
        val activated = activationBackend.swiGluF32(
            gate = gate.output,
            up = up.output
        )
        require(activated.size == plan.feedForwardSize)

        val down = tensorExecutor.matVec(
            tensor = plan.downWeight,
            vector = activated
        )
        require(down.output.size == plan.hiddenSize)

        val output = FloatArray(plan.hiddenSize) { index ->
            input[index] + down.output[index]
        }
        require(output.all { it.isFinite() }) {
            "AMNE decoder FFN produced non-finite output"
        }

        AmiDecoderFfnExecutionResult(
            output = output,
            trace = AmiDecoderFfnTrace(
                layerIndex = plan.layerIndex,
                hiddenSize = plan.hiddenSize,
                feedForwardSize = plan.feedForwardSize,
                normBackendId = normBackend.descriptor.backendId,
                gateBackendId = gate.backendId,
                upBackendId = up.backendId,
                activationBackendId = activationBackend.descriptor.backendId,
                downBackendId = down.backendId,
                mappedBytes = normWeight.mappedBytes +
                    gate.mappedBytes +
                    up.mappedBytes +
                    down.mappedBytes,
                matrixWindows = gate.windows + up.windows + down.windows,
                wallTimeMs =
                    (System.nanoTime() - startedNs) / 1_000_000L
            )
        )
    }
}
