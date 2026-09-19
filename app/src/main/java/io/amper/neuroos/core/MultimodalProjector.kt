package io.amper.neuroos.core

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Base64

/**
 * Durable identity for the user-selected multimodal projector paired with one installed model.
 *
 * The projector remains user-owned. AMPER stores only locator + verified GGUF metadata and an
 * explicit expected input-kind declaration derived from the model profile. Actual native libmtmd
 * capability is probed again before model execution.
 */
data class InstalledMultimodalProjector(
    val modelId: ModelId,
    val displayName: String,
    val locator: String,
    val lengthBytes: Long?,
    val sha256: String,
    val ggufVersion: Long,
    val tensorCount: ULong,
    val metadataKeyValueCount: ULong,
    val expectedKinds: Set<InferenceAttachmentKind>,
    val installedAtEpochMs: Long = System.currentTimeMillis()
) {
    init {
        require(modelId.value.isNotBlank())
        require(displayName.isNotBlank())
        require(locator.isNotBlank())
        require(lengthBytes == null || lengthBytes >= 0L)
        require(sha256.matches(Regex("[0-9a-f]{64}")))
        require(ggufVersion > 0L)
        require(expectedKinds.isNotEmpty()) { "projector must declare at least one expected input kind" }
        require(installedAtEpochMs >= 0L)
    }
}

interface MultimodalProjectorCatalog {
    fun put(projector: InstalledMultimodalProjector)
    fun get(modelId: ModelId): InstalledMultimodalProjector?
    fun list(): List<InstalledMultimodalProjector>
    fun remove(modelId: ModelId): Boolean
}

class InMemoryMultimodalProjectorCatalog : MultimodalProjectorCatalog {
    private val values = linkedMapOf<ModelId, InstalledMultimodalProjector>()

    @Synchronized
    override fun put(projector: InstalledMultimodalProjector) {
        values[projector.modelId] = projector
    }

    @Synchronized
    override fun get(modelId: ModelId): InstalledMultimodalProjector? = values[modelId]

    @Synchronized
    override fun list(): List<InstalledMultimodalProjector> = values.values.toList()

    @Synchronized
    override fun remove(modelId: ModelId): Boolean = values.remove(modelId) != null
}

/**
 * Small fail-closed durable projector catalog. Projector bytes are never copied into AMPER storage.
 * The same sovereign atomic rewrite/recovery path used by the model catalog protects this metadata.
 */
class FileMultimodalProjectorCatalog(private val file: File) : MultimodalProjectorCatalog {
    private val values = linkedMapOf<ModelId, InstalledMultimodalProjector>()

    init {
        loadFromDisk()
    }

    @Synchronized
    override fun put(projector: InstalledMultimodalProjector) {
        val next = LinkedHashMap(values)
        next[projector.modelId] = projector
        persist(next.values)
        values.clear()
        values.putAll(next)
    }

    @Synchronized
    override fun get(modelId: ModelId): InstalledMultimodalProjector? = values[modelId]

    @Synchronized
    override fun list(): List<InstalledMultimodalProjector> = values.values.toList()

    @Synchronized
    override fun remove(modelId: ModelId): Boolean {
        if (!values.containsKey(modelId)) return false
        val next = LinkedHashMap(values)
        next.remove(modelId)
        persist(next.values)
        values.clear()
        values.putAll(next)
        return true
    }

    private fun loadFromDisk() {
        val parent = file.parentFile ?: error("projector catalog has no parent directory")
        if (!Files.exists(parent.toPath(), LinkOption.NOFOLLOW_LINKS)) return
        SovereignPathIdentity.requireDirectory(parent, allowMissingLeaf = false)
        DurableJournalIo.recoverInterruptedReplace(file)
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return

        val text = DescriptorBoundFileIo.readUtf8Text(file)
        if (text.isEmpty()) return
        require(text.endsWith('\n')) { "projector catalog has a torn final record" }
        val lines = text.split('\n').dropLast(1)
        require(lines.none { it.isBlank() }) { "projector catalog contains a blank record" }
        lines.forEachIndexed { index, line ->
            val projector = decode(line)
            require(values.putIfAbsent(projector.modelId, projector) == null) {
                "duplicate projector model id at record ${index + 1}: ${projector.modelId.value}"
            }
        }
    }

    private fun persist(projectors: Collection<InstalledMultimodalProjector>) {
        val parent = file.parentFile ?: error("projector catalog has no parent directory")
        DurableJournalIo.ensureDirectoryExistsDurably(parent)
        val lines = projectors
            .sortedBy { it.modelId.value }
            .map(::encode)
        DurableJournalIo.rewriteUtf8LinesAtomically(file, ".projector-catalog", lines)
    }

    private fun encode(projector: InstalledMultimodalProjector): String {
        val kinds = projector.expectedKinds.map { it.name }.sorted().joinToString("\u001f")
        return listOf(
            b64(projector.modelId.value),
            b64(projector.displayName),
            b64(projector.locator),
            projector.lengthBytes?.toString() ?: "-",
            projector.sha256,
            projector.ggufVersion.toString(),
            projector.tensorCount.toString(),
            projector.metadataKeyValueCount.toString(),
            b64(kinds),
            projector.installedAtEpochMs.toString()
        ).joinToString("\t")
    }

    private fun decode(line: String): InstalledMultimodalProjector {
        val p = line.split('\t')
        require(p.size == 10) { "invalid projector catalog record field count" }
        val kinds = unb64(p[8])
            .split('\u001f')
            .filter { it.isNotBlank() }
            .map(InferenceAttachmentKind::valueOf)
            .toSet()
        return InstalledMultimodalProjector(
            modelId = ModelId(unb64(p[0])),
            displayName = unb64(p[1]),
            locator = unb64(p[2]),
            lengthBytes = p[3].takeUnless { it == "-" }?.toLong(),
            sha256 = p[4],
            ggufVersion = p[5].toLong(),
            tensorCount = p[6].toULong(),
            metadataKeyValueCount = p[7].toULong(),
            expectedKinds = kinds,
            installedAtEpochMs = p[9].toLong()
        )
    }

    private fun b64(value: String): String = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun unb64(value: String): String = String(
        Base64.getUrlDecoder().decode(value),
        StandardCharsets.UTF_8
    )
}

interface MultimodalProjectorArtifactResolver {
    fun resolve(projector: InstalledMultimodalProjector): ModelArtifactSource?
}

class LocatorMultimodalProjectorArtifactResolver(
    private val factories: List<(InstalledMultimodalProjector) -> ModelArtifactSource?>
) : MultimodalProjectorArtifactResolver {
    override fun resolve(projector: InstalledMultimodalProjector): ModelArtifactSource? =
        factories.firstNotNullOfOrNull { it(projector) }
}

class MultimodalProjectorInstallService(
    private val inspector: GgufInspector,
    private val catalog: MultimodalProjectorCatalog
) {
    fun install(
        model: InstalledModel,
        source: ModelArtifactSource,
        expectedKinds: Set<InferenceAttachmentKind>
    ): Result<InstalledMultimodalProjector> = runCatching {
        require(model.descriptor.local) { "multimodal projector requires a local installed model" }
        require(model.descriptor.format.equals("gguf", ignoreCase = true)) {
            "multimodal projector requires a GGUF text model"
        }
        require(expectedKinds.isNotEmpty()) { "projector input kinds are empty" }
        val descriptorKinds = buildSet {
            if (TitanCapabilities.VISION in model.descriptor.capabilities) {
                add(InferenceAttachmentKind.IMAGE)
            }
            if (TitanCapabilities.AUDIO_UNDERSTANDING in model.descriptor.capabilities) {
                add(InferenceAttachmentKind.AUDIO)
            }
        }
        require(descriptorKinds.containsAll(expectedKinds)) {
            "projector kinds exceed the model's explicit multimodal capability profile"
        }

        val inspection = inspector.inspect(source).getOrThrow()
        val projector = InstalledMultimodalProjector(
            modelId = model.descriptor.id,
            displayName = inspection.displayName,
            locator = inspection.locator,
            lengthBytes = inspection.lengthBytes,
            sha256 = inspection.sha256,
            ggufVersion = inspection.header.version,
            tensorCount = inspection.header.tensorCount,
            metadataKeyValueCount = inspection.header.metadataKeyValueCount,
            expectedKinds = expectedKinds
        )
        catalog.put(projector)
        projector
    }
}

class MultimodalProjectorIdentityVerifier(
    private val inspector: GgufInspector = GgufInspector()
) {
    fun verify(
        projector: InstalledMultimodalProjector,
        source: ModelArtifactSource
    ): Result<Unit> = runCatching {
        require(source.locator == projector.locator) {
            "projector locator changed after installation"
        }
        requireMatches(projector, inspector.inspect(source).getOrThrow())
    }

    fun verifyNativePath(
        projector: InstalledMultimodalProjector,
        source: ModelArtifactSource,
        nativePath: String
    ): Result<Unit> = runCatching {
        require(nativePath.isNotBlank()) { "native projector path is blank" }
        require(source.locator == projector.locator) {
            "projector locator changed after installation"
        }
        val inspection = inspector
            .inspect(FileModelArtifactSource(File(nativePath)))
            .getOrThrow()
        requireMatches(projector, inspection)
    }

    private fun requireMatches(
        projector: InstalledMultimodalProjector,
        inspection: GgufArtifactInspection
    ) {
        require(inspection.sha256 == projector.sha256) {
            "projector SHA-256 does not match installed identity"
        }
        projector.lengthBytes?.let { installedLength ->
            require(inspection.lengthBytes == installedLength) {
                "projector length does not match installed identity"
            }
        }
        require(inspection.header.version == projector.ggufVersion) {
            "projector GGUF version changed after installation"
        }
        require(inspection.header.tensorCount == projector.tensorCount) {
            "projector tensor count changed after installation"
        }
        require(inspection.header.metadataKeyValueCount == projector.metadataKeyValueCount) {
            "projector metadata count changed after installation"
        }
    }
}