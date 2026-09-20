package io.amper.neuroos.core

data class AmiDirectExecutionReadiness(
    val ready: Boolean,
    val requiredMatrixPrimitives: Set<AmneKernelPrimitive>,
    val referenceOnlyMatrixPrimitives: Set<AmneKernelPrimitive>,
    val backendByPrimitive: Map<AmneKernelPrimitive, String>
) {
    init {
        require(referenceOnlyMatrixPrimitives.all(requiredMatrixPrimitives::contains))
        require(backendByPrimitive.keys == requiredMatrixPrimitives)
        require(ready == referenceOnlyMatrixPrimitives.isEmpty())
    }
}

/**
 * Model-specific performance gate for direct AMI inference.
 *
 * Correctness reference kernels remain available for tests and small primitives, but a full decoder
 * model is not admitted to Titan's interactive path when any required matrix primitive would execute
 * on the portable reference backend. Matrix multiplies dominate decoder cost; allowing a large
 * quantized model to fall back there can create minute-scale first-token latency on a phone.
 */
object AmiDirectExecutionAdmissionPolicy {
    fun requiredMatrixPrimitives(
        stack: AmiDecoderStackPlan,
        output: AmiOutputHeadPlan
    ): Set<AmneKernelPrimitive> = buildSet {
        stack.layers.forEach { layer ->
            addRequired(layer.attention.queryWeight)
            addRequired(layer.attention.keyWeight)
            addRequired(layer.attention.valueWeight)
            addRequired(layer.attention.outputWeight)
            addRequired(layer.ffn.gateWeight)
            addRequired(layer.ffn.upWeight)
            addRequired(layer.ffn.downWeight)
        }
        addRequired(output.outputWeight)
    }

    fun evaluate(
        requiredMatrixPrimitives: Set<AmneKernelPrimitive>,
        registry: AmneKernelRegistry,
        hardware: AmiHardwareSnapshot?
    ): AmiDirectExecutionReadiness {
        require(requiredMatrixPrimitives.isNotEmpty()) {
            "direct AMI decoder requires at least one matrix primitive"
        }

        val backends = linkedMapOf<AmneKernelPrimitive, String>()
        val referenceOnly = linkedSetOf<AmneKernelPrimitive>()
        requiredMatrixPrimitives
            .sortedBy { it.ordinal }
            .forEach { primitive ->
                val backend = registry.backendFor(primitive, hardware)
                backends[primitive] = backend.descriptor.backendId
                if (backend.descriptor.deterministicReference) {
                    referenceOnly += primitive
                }
            }

        return AmiDirectExecutionReadiness(
            ready = referenceOnly.isEmpty(),
            requiredMatrixPrimitives = requiredMatrixPrimitives.toSet(),
            referenceOnlyMatrixPrimitives = referenceOnly.toSet(),
            backendByPrimitive = backends.toMap()
        )
    }

    private fun MutableSet<AmneKernelPrimitive>.addRequired(
        tensor: AmiTensorDescriptor
    ) {
        val primitive = requireNotNull(
            AmneTensorKernelPlanner.requiredPrimitive(tensor)
        ) {
            "unsupported AMI matrix encoding for direct execution: " + tensor.name
        }
        add(primitive)
    }
}
