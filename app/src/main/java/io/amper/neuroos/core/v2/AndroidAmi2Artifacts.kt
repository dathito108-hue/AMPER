package io.amper.neuroos.core.v2

import io.amper.neuroos.core.DurableJournalIo
import io.amper.neuroos.core.InstalledModel
import io.amper.neuroos.core.ModelArtifactResolver
import io.amper.neuroos.core.ModelId
import io.amper.neuroos.core.SovereignPathIdentity
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

data class StoredAmi2Artifact(
    val modelId: ModelId,
    val file: File,
    val loaded: Ami2LoadedBinaryArtifact
) {
    val architectureId: String
        get() = loaded.bundle.foundation.architectureId

    val foundationSha256: String
        get() = loaded.bundle.foundation.canonicalWeightsSha256

    val semanticSha256: String
        get() = loaded.bundle.foundation.semanticSha256

    val migratedFromLegacyAmi1: Boolean
        get() = loaded.migrationEvidence != null
}

/**
 * App-private canonical AMI2 store.
 *
 * New installed GGUF sources compile directly into AMI2. Existing AMI1 artifacts may be migrated
 * explicitly through [migrateLegacy], but AMI1 is never selected as the canonical output for a
 * newly imported source.
 */
class AndroidAmi2CompilationService(
    private val rootDir: File,
    private val modelArtifacts: ModelArtifactResolver,
    private val directCompiler: GgufToAmi2StreamingCompiler = GgufToAmi2StreamingCompiler(),
    private val legacyMigrator: Ami1ToAmi2StreamingMigrationEmitter =
        Ami1ToAmi2StreamingMigrationEmitter(),
    private val reader: Ami2CanonicalBinaryReader = Ami2CanonicalBinaryReader()
) {
    @Synchronized
    fun compileDirect(model: InstalledModel): Result<StoredAmi2Artifact> = runCatching {
        require(model.descriptor.format.equals("gguf", ignoreCase = true)) {
            "only installed GGUF models can compile directly to AMI2"
        }
        require(model.sha256.matches(SHA256)) {
            "installed GGUF has invalid source digest"
        }

        val target = targetFile(model)
        existingVerified(model, target, requireDirect = true)?.let {
            return@runCatching it
        }
        deleteInvalidTargetIfPresent(target)

        val source = requireNotNull(modelArtifacts.resolve(model)) {
            "installed GGUF artifact is unavailable for direct AMI2 compilation"
        }
        val compiled = directCompiler.compile(source, target).getOrThrow()
        require(compiled.sourceSha256 == model.sha256) {
            "GGUF source identity changed during direct AMI2 compilation"
        }

        val loaded = reader.read(target, verifySectionDigests = true).getOrThrow()
        validateLineage(model, loaded)
        require(loaded.migrationEvidence == null) {
            "direct AMI2 compilation must not synthesize AMI1 migration evidence"
        }

        publishReadOnly(target)
        StoredAmi2Artifact(model.descriptor.id, target, loaded)
    }

    /**
     * Legacy-only compatibility route. This method accepts an already verified/owned AMI1 file and
     * emits the same canonical AMI2 target. New imports must use [compileDirect].
     */
    @Synchronized
    fun migrateLegacy(
        model: InstalledModel,
        legacyAmi1: File
    ): Result<StoredAmi2Artifact> = runCatching {
        require(model.descriptor.format.equals("gguf", ignoreCase = true)) {
            "legacy AMI1 migration must bind to an installed GGUF source"
        }
        require(model.sha256.matches(SHA256))
        require(legacyAmi1.exists() && legacyAmi1.isFile) {
            "legacy AMI1 artifact does not exist"
        }

        val target = targetFile(model)
        existingVerified(model, target, requireDirect = false)?.let {
            return@runCatching it
        }
        deleteInvalidTargetIfPresent(target)

        val migrated = legacyMigrator.migrate(legacyAmi1, target).getOrThrow()
        val loaded = reader.read(target, verifySectionDigests = true).getOrThrow()
        validateLineage(model, loaded)
        require(loaded.migrationEvidence?.legacyAmi1Sha256 == migrated.sourceAmi1Sha256) {
            "legacy AMI1 migration evidence is missing or inconsistent"
        }

        publishReadOnly(target)
        StoredAmi2Artifact(model.descriptor.id, target, loaded)
    }

    fun existing(model: InstalledModel): StoredAmi2Artifact? {
        if (!model.sha256.matches(SHA256)) return null
        return runCatching {
            existingVerified(model, targetFile(model), requireDirect = false)
        }.getOrNull()
    }

    private fun targetFile(model: InstalledModel): File {
        DurableJournalIo.ensureDirectoryExistsDurably(rootDir)
        SovereignPathIdentity.requireDirectory(rootDir, allowMissingLeaf = false)
        val fileName = "source-sha256-" + model.sha256 + "-ami2.ami"
        require(fileName.matches(FILE_NAME)) { "invalid AMI2 cache file name" }
        return File(rootDir, fileName).also(SovereignPathIdentity::requireManagedFile)
    }

    private fun existingVerified(
        model: InstalledModel,
        target: File,
        requireDirect: Boolean
    ): StoredAmi2Artifact? {
        if (!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        SovereignPathIdentity.requireManagedFile(target, allowMissingLeaf = false)
        val loaded = reader.read(target, verifySectionDigests = true).getOrNull() ?: return null
        return runCatching {
            validateLineage(model, loaded)
            if (requireDirect) {
                require(loaded.migrationEvidence == null) {
                    "existing AMI2 cache is legacy-migrated; direct canonical rebuild required"
                }
            }
            StoredAmi2Artifact(model.descriptor.id, target, loaded)
        }.getOrNull()
    }

    private fun validateLineage(
        model: InstalledModel,
        loaded: Ami2LoadedBinaryArtifact
    ) {
        val foundation = loaded.bundle.foundation
        require(foundation.lineage.source == Ami2ImportSource.GGUF_WEIGHTS) {
            "AMI2 canonical store only admits GGUF-origin foundation lineage"
        }
        require(foundation.lineage.sourceSha256 == model.sha256) {
            "AMI2 source lineage does not match installed GGUF"
        }
        model.lengthBytes?.let { expected ->
            require(foundation.lineage.sourceByteLength == expected) {
                "AMI2 source length does not match installed GGUF"
            }
        }
    }

    private fun deleteInvalidTargetIfPresent(target: File) {
        if (!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) return
        SovereignPathIdentity.requireManagedFile(target, allowMissingLeaf = false)
        target.setWritable(true)
        require(target.delete()) {
            "existing AMI2 cache failed verification and could not be removed"
        }
        DurableJournalIo.syncDirectory(rootDir)
    }

    private fun publishReadOnly(target: File) {
        SovereignPathIdentity.requireManagedFile(target, allowMissingLeaf = false)
        target.setReadOnly()
        DurableJournalIo.syncDirectory(rootDir)
    }

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val FILE_NAME = Regex("[A-Za-z0-9._-]{1,192}")
    }
}
