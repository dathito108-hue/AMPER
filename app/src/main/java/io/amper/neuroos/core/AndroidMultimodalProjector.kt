package io.amper.neuroos.core

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri

class AndroidMultimodalProjectorImportService(
    context: Context,
    private val catalog: MultimodalProjectorCatalog
) {
    private val resolver = context.applicationContext.contentResolver
    private val installer = MultimodalProjectorInstallService(GgufInspector(), catalog)

    fun persistReadPermission(uri: Uri): Result<Unit> = runCatching {
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun install(
        model: InstalledModel,
        uri: Uri,
        expectedKinds: Set<InferenceAttachmentKind>
    ): Result<InstalledMultimodalProjector> {
        val source = ContentUriModelArtifactSource(resolver, uri)
        return installer.install(model, source, expectedKinds)
    }
}

class ContentUriProjectorArtifactResolver(
    private val resolver: ContentResolver
) : MultimodalProjectorArtifactResolver {
    override fun resolve(projector: InstalledMultimodalProjector): ModelArtifactSource? = runCatching {
        val uri = Uri.parse(projector.locator)
        require(uri.scheme == ContentResolver.SCHEME_CONTENT) { "projector is not a content URI" }
        ContentUriModelArtifactSource(resolver, uri)
    }.getOrNull()
}
