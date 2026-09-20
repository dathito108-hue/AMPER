package io.amper.neuroos.core

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption

data class StoredAmiArtifact(
    val modelId: ModelId,
    val file: File,
    val loaded: AmiLoadedArtifact
) {
    val architecture: AmiArchitectureId
        get() = loaded.index.manifest.architecture

    val foundationSha256: String
        get() = loaded.index.sections.single {
            it.type == AmiSectionType.FOUNDATION_WEIGHTS
        }.sha256
}

/**
 * App-private AMI compiler/store.
 *
 * Source GGUF remains user-owned and unchanged. AMI output is content-addressed by the complete
 * admitted source SHA-256 and is published only after AmiBinaryReader verifies every section.
 */
class AndroidAmiCompilationService(
    private val rootDir: File,
    private val modelArtifacts: ModelArtifactResolver,
    private val compiler: GgufToAmiCompiler = GgufToAmiCompiler(),
    private val reader: AmiBinaryReader = AmiBinaryReader()
) {
    @Synchronized
    fun compile(model: InstalledModel): Result<StoredAmiArtifact> = runCatching {
        require(model.descriptor.format.equals("gguf", ignoreCase = true)) {
            "only installed GGUF models can be compiled to AMI"
        }
        require(model.sha256.matches(Regex("[0-9a-f]{64}"))) {
            "installed GGUF has invalid source digest"
        }

        DurableJournalIo.ensureDirectoryExistsDurably(rootDir)
        SovereignPathIdentity.requireDirectory(rootDir, allowMissingLeaf = false)

        val fileName = "source-sha256-" + model.sha256 + AmperMobileIntelligenceFormat.FILE_EXTENSION
        require(fileName.matches(AndroidAppPrivateAmiArtifactSource.FILE_NAME))
        val target = File(rootDir, fileName)
        SovereignPathIdentity.requireManagedFile(target)

        if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            SovereignPathIdentity.requireManagedFile(target, allowMissingLeaf = false)
            val existing = reader.read(target, verifySectionDigests = true).getOrNull()
            if (
                existing != null &&
                existing.index.manifest.source.sourceSha256 == model.sha256 &&
                existing.index.manifest.source.sourceByteLength == model.lengthBytes
            ) {
                return@runCatching StoredAmiArtifact(
                    modelId = model.descriptor.id,
                    file = target,
                    loaded = existing
                )
            }

            require(target.delete()) {
                "existing AMI cache failed verification and could not be removed"
            }
            DurableJournalIo.syncDirectory(rootDir)
        }

        val source = requireNotNull(modelArtifacts.resolve(model)) {
            "installed GGUF artifact is unavailable for AMI compilation"
        }
        val compiled = compiler.compile(source, target).getOrThrow()
        require(compiled.sourceSha256 == model.sha256) {
            "GGUF source identity changed during AMI compilation"
        }
        model.lengthBytes?.let { expected ->
            require(compiled.index.manifest.source.sourceByteLength == expected) {
                "GGUF source length changed during AMI compilation"
            }
        }

        SovereignPathIdentity.requireManagedFile(target, allowMissingLeaf = false)
        val loaded = reader.read(target, verifySectionDigests = true).getOrThrow()
        require(loaded.index.manifest.source.sourceSha256 == model.sha256) {
            "compiled AMI lineage does not match installed GGUF"
        }
        target.setReadOnly()
        DurableJournalIo.syncDirectory(rootDir)

        StoredAmiArtifact(
            modelId = model.descriptor.id,
            file = target,
            loaded = loaded
        )
    }

    fun existing(model: InstalledModel): StoredAmiArtifact? {
        if (!model.sha256.matches(Regex("[0-9a-f]{64}"))) return null
        val fileName = "source-sha256-" + model.sha256 + AmperMobileIntelligenceFormat.FILE_EXTENSION
        if (!fileName.matches(AndroidAppPrivateAmiArtifactSource.FILE_NAME)) return null
        val target = File(rootDir, fileName)
        return runCatching {
            SovereignPathIdentity.requireDirectory(rootDir, allowMissingLeaf = false)
            SovereignPathIdentity.requireManagedFile(target, allowMissingLeaf = false)
            if (!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                return@runCatching null
            }
            val loaded = reader.read(target, verifySectionDigests = true).getOrThrow()
            require(loaded.index.manifest.source.sourceSha256 == model.sha256)
            StoredAmiArtifact(model.descriptor.id, target, loaded)
        }.getOrNull()
    }
}

/**
 * Stable native-readable source for an AMPER-owned .ami artifact.
 */
class AndroidAppPrivateAmiArtifactSource(
    private val rootDir: File,
    private val fileName: String
) : AppPrivateNativeModelPathSource {
    private val file: File

    init {
        require(fileName.matches(FILE_NAME)) { "invalid AMI artifact file name" }
        require(fileName.endsWith(AmperMobileIntelligenceFormat.FILE_EXTENSION)) {
            "AMI artifact must use .ami extension"
        }
        SovereignPathIdentity.requireDirectory(rootDir, allowMissingLeaf = false)
        file = File(rootDir, fileName)
        SovereignPathIdentity.requireManagedFile(file, allowMissingLeaf = false)
    }

    override val locator: String = locatorFor(fileName)
    override val displayName: String = fileName
    override val lengthBytes: Long
        get() {
            SovereignPathIdentity.requireManagedFile(file, allowMissingLeaf = false)
            return file.length()
        }

    override fun openStream(): InputStream {
        SovereignPathIdentity.requireManagedFile(file, allowMissingLeaf = false)
        return FileInputStream(file)
    }

    override fun <T> withNativePath(block: (String) -> T): T {
        SovereignPathIdentity.requireManagedFile(file, allowMissingLeaf = false)
        val before = SovereignPathIdentity.snapshot(file)
        val result = block(file.toPath().toAbsolutePath().normalize().toString())
        SovereignPathIdentity.requireSameIdentity(before, file)
        return result
    }

    companion object {
        internal val FILE_NAME = Regex("[A-Za-z0-9._-]{1,192}")
        private const val PREFIX = "amper-private://ami-model/"

        fun locatorFor(fileName: String): String {
            require(fileName.matches(FILE_NAME))
            require(fileName.endsWith(AmperMobileIntelligenceFormat.FILE_EXTENSION))
            return PREFIX + fileName
        }
    }
}
