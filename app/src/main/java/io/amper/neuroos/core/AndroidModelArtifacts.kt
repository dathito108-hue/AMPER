package io.amper.neuroos.core

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException
import java.io.InputStream
import java.util.UUID

class ContentUriModelArtifactSource(
    private val resolver: ContentResolver,
    private val uri: Uri
) : DescriptorBoundNativeModelPathSource {
    private val metadata: Pair<String, Long?> by lazy { queryMetadata() }

    override val locator: String = uri.toString()
    override val displayName: String get() = metadata.first
    override val lengthBytes: Long? get() = metadata.second

    override fun openStream(): InputStream = resolver.openInputStream(uri)
        ?: throw IOException("unable to open model URI: $uri")

    /**
     * Expose the already-authorized user document to llama.cpp without copying the
     * GGUF into the sovereign store. The ParcelFileDescriptor remains open while
     * the native loader consumes /proc/self/fd/<fd>, satisfying the descriptor-bound
     * native path contract through the entire callback.
     */
    override fun <T> withNativePath(block: (String) -> T): T {
        val descriptor = resolver.openFileDescriptor(uri, "r")
            ?: throw IOException("unable to open native descriptor for model URI: $uri")
        descriptor.use {
            return block("/proc/self/fd/${it.fd}")
        }
    }

    private fun queryMetadata(): Pair<String, Long?> {
        var name = uri.lastPathSegment ?: "model.gguf"
        var size: Long? = null
        resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        }
        return name to size
    }
}

class AndroidModelImportService(
    context: Context,
    private val catalog: InstalledModelCatalog,
    private val registry: ModelRegistry
) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val installer = LocalModelInstallService(GgufInspector(), catalog, registry)

    fun persistReadPermission(uri: Uri): Result<Unit> = runCatching {
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** Canonical typed import path. Specialist capabilities require an explicit profile. */
    fun install(
        uri: Uri,
        profile: ModelCapabilityProfile = ModelCapabilityProfile()
    ): Result<InstalledModel> = installWithCapabilities(uri, profile.capabilities)

    /**
     * Compatibility path for pre-Phase-111 callers.
     *
     * Untyped capability sets are intentionally reduced to the reasoning baseline. This keeps old
     * callers working without allowing them to silently over-claim code-generation/planning support.
     */
    @Deprecated(
        message = "Use install(uri, ModelCapabilityProfile) for explicit specialist capabilities",
        replaceWith = ReplaceWith("install(uri, ModelCapabilityProfile())")
    )
    fun install(
        uri: Uri,
        capabilities: Set<CapabilityId>
    ): Result<InstalledModel> = install(
        uri = uri,
        profile = ModelCapabilityProfile.fromLegacyUntyped(capabilities)
    )

    private fun installWithCapabilities(
        uri: Uri,
        capabilities: Set<CapabilityId>
    ): Result<InstalledModel> {
        val source = ContentUriModelArtifactSource(resolver, uri)
        val id = ModelId("gguf-${UUID.randomUUID()}")
        return installer.install(id, source, capabilities)
    }
}

class ContentUriArtifactResolver(private val resolver: ContentResolver) : ModelArtifactResolver {
    override fun resolve(model: InstalledModel): ModelArtifactSource? = runCatching {
        val uri = Uri.parse(model.locator)
        require(uri.scheme == ContentResolver.SCHEME_CONTENT) { "not a content URI" }
        ContentUriModelArtifactSource(resolver, uri)
    }.getOrNull()
}
