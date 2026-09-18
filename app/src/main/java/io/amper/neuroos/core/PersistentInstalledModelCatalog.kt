package io.amper.neuroos.core

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Base64

/**
 * Small durable catalog containing only model metadata and user-owned artifact locators.
 * Model bytes are never copied into the sovereign store.
 *
 * Phase 68 publishes the catalog through the sovereign durable rewrite path instead of a
 * static .tmp + delete/rename sequence. Startup first runs replacement recovery, then reads
 * through descriptor-bound I/O and decodes every complete record fail-closed. A malformed or
 * duplicate record can therefore never be silently discarded while the rest of the catalog
 * is accepted.
 */
class FileInstalledModelCatalog(private val file: File) : InstalledModelCatalog {
    private val models = linkedMapOf<ModelId, InstalledModel>()

    init {
        loadFromDisk()
    }

    @Synchronized
    override fun put(model: InstalledModel) {
        validate(model)
        val next = LinkedHashMap(models)
        next[model.descriptor.id] = model
        persist(next.values)
        models.clear()
        models.putAll(next)
    }

    @Synchronized
    override fun get(id: ModelId): InstalledModel? = models[id]

    @Synchronized
    override fun list(): List<InstalledModel> = models.values.toList()

    @Synchronized
    override fun remove(id: ModelId): Boolean {
        if (!models.containsKey(id)) return false
        val next = LinkedHashMap(models)
        next.remove(id)
        persist(next.values)
        models.clear()
        models.putAll(next)
        return true
    }

    private fun loadFromDisk() {
        val parent = file.parentFile ?: error("model catalog has no parent directory")
        if (!Files.exists(parent.toPath(), LinkOption.NOFOLLOW_LINKS)) return
        SovereignPathIdentity.requireDirectory(parent, allowMissingLeaf = false)
        DurableJournalIo.recoverInterruptedReplace(file)
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return

        val text = DescriptorBoundFileIo.readUtf8Text(file)
        if (text.isEmpty()) return
        require(text.endsWith('\n')) { "model catalog has a torn final record" }
        val lines = text.split('\n').dropLast(1)
        require(lines.none { it.isBlank() }) { "model catalog contains a blank record" }

        lines.forEachIndexed { index, line ->
            val model = decode(line)
            require(models.putIfAbsent(model.descriptor.id, model) == null) {
                "duplicate model id in catalog at record ${index + 1}: ${model.descriptor.id.value}"
            }
        }
    }

    private fun persist(values: Collection<InstalledModel>) {
        val parent = file.parentFile ?: error("model catalog has no parent directory")
        DurableJournalIo.ensureDirectoryExistsDurably(parent)
        val lines = values
            .sortedBy { it.descriptor.id.value }
            .map { model ->
                validate(model)
                encode(model)
            }
        DurableJournalIo.rewriteUtf8LinesAtomically(file, ".model-catalog", lines)
    }

    private fun encode(model: InstalledModel): String {
        val capabilities = model.descriptor.capabilities
            .map { it.value }
            .sorted()
            .joinToString("\u001f")
        return listOf(
            b64(model.descriptor.id.value),
            b64(model.descriptor.format),
            model.descriptor.local.toString(),
            b64(capabilities),
            b64(model.displayName),
            b64(model.locator),
            model.lengthBytes?.toString() ?: "-",
            model.sha256,
            model.ggufVersion.toString(),
            model.tensorCount.toString(),
            model.metadataKeyValueCount.toString(),
            model.installedAtEpochMs.toString()
        ).joinToString("\t")
    }

    private fun decode(line: String): InstalledModel {
        val p = line.split('\t')
        require(p.size == 12) { "invalid model catalog record field count" }
        val capabilityText = unb64(p[3])
        val descriptor = ModelDescriptor(
            id = ModelId(unb64(p[0])),
            format = unb64(p[1]),
            capabilities = capabilityText
                .split('\u001f')
                .filter { it.isNotBlank() }
                .map(::CapabilityId)
                .toSet(),
            local = p[2].toBooleanStrict()
        )
        return InstalledModel(
            descriptor = descriptor,
            displayName = unb64(p[4]),
            locator = unb64(p[5]),
            lengthBytes = p[6].takeUnless { it == "-" }?.toLong(),
            sha256 = p[7],
            ggufVersion = p[8].toLong(),
            tensorCount = p[9].toULong(),
            metadataKeyValueCount = p[10].toULong(),
            installedAtEpochMs = p[11].toLong()
        ).also(::validate)
    }

    private fun validate(model: InstalledModel) {
        require(model.descriptor.id.value.isNotBlank()) { "model id is blank" }
        require(model.descriptor.format.isNotBlank()) { "model format is blank" }
        require(model.descriptor.capabilities.isNotEmpty()) { "model capabilities are empty" }
        require(model.descriptor.capabilities.none { it.value.isBlank() }) { "blank model capability" }
        require(model.displayName.isNotBlank()) { "model display name is blank" }
        require(model.locator.isNotBlank()) { "model locator is blank" }
        require(model.lengthBytes == null || model.lengthBytes >= 0L) { "negative model length" }
        require(model.sha256.matches(SHA256)) { "invalid model artifact SHA-256" }
        require(model.ggufVersion > 0L) { "invalid GGUF version" }
        require(model.installedAtEpochMs >= 0L) { "invalid model installation time" }
    }

    private fun b64(value: String): String = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun unb64(value: String): String = String(
        Base64.getUrlDecoder().decode(value),
        StandardCharsets.UTF_8
    )

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

class InstalledModelRegistryBootstrap(
    private val catalog: InstalledModelCatalog,
    private val registry: ModelRegistry
) {
    fun restore(): Int {
        val installed = catalog.list()
        installed.forEach { registry.register(it.descriptor) }
        return installed.size
    }
}
