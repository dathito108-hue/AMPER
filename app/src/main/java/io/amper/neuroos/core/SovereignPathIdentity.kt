package io.amper.neuroos.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * Fail-closed filesystem identity checks for sovereign journal state.
 *
 * AMPER never accepts a symbolic link as a managed file or as an existing component of
 * its parent path. Existing managed leaves must be regular files; existing parent
 * components must be real directories when inspected with NOFOLLOW_LINKS.
 *
 * [snapshot] also captures the provider fileKey when one is available. Callers can use
 * [requireSameIdentity] around multi-step operations to detect a path being rebound to a
 * different inode/file object between validation points. Providers that do not expose a
 * fileKey still retain the mandatory symlink/type checks.
 */
internal object SovereignPathIdentity {
    private val TRANSACTION_ID = Regex("[0-9a-f]{32}")
    private val REWRITE_PURPOSE = Regex("[A-Za-z0-9_-]+")
    private val LEGACY_REWRITE_SUFFIXES = setOf(
        ".updating",
        ".compacting",
        ".chaining",
        ".compacting-encrypted",
        ".rekeying",
        ".encrypting-compacted-segment",
        ".encrypting-chain"
    )
    private val FIXED_NAMESPACE_SUFFIXES = setOf(
        ".head",
        ".keys",
        ".lock",
        ".rewrite-lock",
        ".replace-pending",
        ".replace-backup",
        ".replace-staged",
        ".recovery-epochs",
        ".recovery-epochs.a",
        ".recovery-epochs.b",
        ".recovery-epochs.authority",
        ".recovery-epochs.active",
        ".recovery-epochs.lock"
    ) + LEGACY_REWRITE_SUFFIXES
    private val ORDERED_FIXED_SUFFIXES = FIXED_NAMESPACE_SUFFIXES.sortedByDescending(String::length)
    private val REPLACEMENT_SIDECAR_NAME = Regex("^(.+)\\.replace-([0-9a-f]{32})\\.(backup|staged)$")
    private val REWRITE_TEMP_NAME = Regex("^(.+)\\.rewrite-([0-9a-f]{32})\\.([A-Za-z0-9_-]+)$")

    internal data class Snapshot(
        val normalizedPath: String,
        val fileKey: String?
    )

    /**
     * A sovereign journal owns every filename derived from its basename. Reject a proposed
     * root only when its name is a sidecar/temp shape *and* the owning namespace is actually
     * materialized in the same directory. This prevents cross-journal aliasing without
     * outlawing otherwise ordinary standalone names such as "notes.head".
     *
     * This is deliberately a journal-root check. Generic durable I/O also operates on H1,
     * K1 and recovery sidecars, so [requireManagedNamespace] must not reinterpret those
     * legitimate sidecars as new journal roots.
     */
    fun requireNamespaceRootName(target: File) {
        val name = target.name
        require(name.isNotBlank() && name != "." && name != "..") {
            "invalid sovereign namespace root name"
        }
        val ownerName = namespaceOwnerName(name) ?: return
        val parent = target.parentFile ?: error("managed sovereign file has no parent directory")
        require(!namespaceOwnerMaterialized(parent, ownerName, ignoredName = name)) {
            "sovereign namespace root collides with materialized namespace owner $ownerName: $name"
        }
    }

    fun requireManagedFile(file: File, allowMissingLeaf: Boolean = true) {
        val path = normalized(file)
        requireSafeExistingComponents(path)
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            require(allowMissingLeaf) { "managed sovereign file is missing: $path" }
            return
        }
        require(!Files.isSymbolicLink(path)) { "managed sovereign path is a symbolic link: $path" }
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            "managed sovereign path is not a regular file: $path"
        }
    }

    /**
     * Validates the target plus every currently materialized file in its sovereign sidecar
     * namespace. This covers journal/H1/K1 leaves, process/rewrite locks, R1-R3 replacement
     * metadata, G1/G2/G3/A1 recovery authority, the authority-set lease, and both legacy and
     * transaction-scoped temps.
     */
    fun requireManagedNamespace(target: File) {
        requireManagedFile(target)
        val parent = target.parentFile ?: error("managed sovereign file has no parent directory")
        requireDirectory(parent)

        FIXED_NAMESPACE_SUFFIXES.forEach { suffix ->
            requireManagedFile(File(parent, target.name + suffix))
        }

        val replacement = Regex(
            "^${Regex.escape(target.name)}\\.replace-([0-9a-f]{32})\\.(backup|staged)$"
        )
        val rewrite = Regex(
            "^${Regex.escape(target.name)}\\.rewrite-([0-9a-f]{32})\\.([A-Za-z0-9_-]+)$"
        )
        parent.listFiles().orEmpty().forEach { candidate ->
            val replacementMatch = replacement.matchEntire(candidate.name)
            val rewriteMatch = rewrite.matchEntire(candidate.name)
            when {
                replacementMatch != null -> {
                    require(replacementMatch.groupValues[1].matches(TRANSACTION_ID))
                    requireManagedFile(candidate, allowMissingLeaf = false)
                }
                rewriteMatch != null -> {
                    require(rewriteMatch.groupValues[1].matches(TRANSACTION_ID))
                    require(rewriteMatch.groupValues[2].matches(REWRITE_PURPOSE))
                    requireManagedFile(candidate, allowMissingLeaf = false)
                }
            }
        }
    }

    fun requireDirectory(directory: File, allowMissingLeaf: Boolean = true) {
        val path = normalized(directory)
        requireSafeExistingComponents(path)
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            require(allowMissingLeaf) { "managed sovereign directory is missing: $path" }
            return
        }
        require(!Files.isSymbolicLink(path)) { "managed sovereign directory is a symbolic link: $path" }
        require(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            "managed sovereign directory path is not a directory: $path"
        }
    }

    fun snapshot(file: File): Snapshot {
        requireManagedFile(file, allowMissingLeaf = false)
        val path = normalized(file)
        val attributes = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS
        )
        require(attributes.isRegularFile) { "managed sovereign identity is not a regular file: $path" }
        return Snapshot(path.toString(), attributes.fileKey()?.toString())
    }

    fun snapshotDirectory(directory: File): Snapshot {
        requireDirectory(directory, allowMissingLeaf = false)
        val path = normalized(directory)
        val attributes = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS
        )
        require(attributes.isDirectory) { "managed sovereign identity is not a directory: $path" }
        return Snapshot(path.toString(), attributes.fileKey()?.toString())
    }

    fun requireSameIdentity(expected: Snapshot, file: File) {
        val actual = snapshot(file)
        require(expected.normalizedPath == actual.normalizedPath) {
            "managed sovereign path changed during operation"
        }
        if (expected.fileKey != null && actual.fileKey != null) {
            require(expected.fileKey == actual.fileKey) {
                "managed sovereign file identity changed during operation: ${actual.normalizedPath}"
            }
        }
    }

    fun requireSameDirectoryIdentity(expected: Snapshot, directory: File) {
        val actual = snapshotDirectory(directory)
        require(expected.normalizedPath == actual.normalizedPath) {
            "managed sovereign directory path changed during operation"
        }
        if (expected.fileKey != null && actual.fileKey != null) {
            require(expected.fileKey == actual.fileKey) {
                "managed sovereign directory identity changed during operation: ${actual.normalizedPath}"
            }
        }
    }

    private fun namespaceOwnerName(candidateName: String): String? {
        REPLACEMENT_SIDECAR_NAME.matchEntire(candidateName)?.let { return it.groupValues[1] }
        REWRITE_TEMP_NAME.matchEntire(candidateName)?.let { return it.groupValues[1] }
        ORDERED_FIXED_SUFFIXES.forEach { suffix ->
            if (candidateName.endsWith(suffix)) {
                val owner = candidateName.removeSuffix(suffix)
                if (owner.isNotBlank()) return owner
            }
        }
        return null
    }

    private fun namespaceOwnerMaterialized(parent: File, ownerName: String, ignoredName: String): Boolean {
        if (!Files.exists(parent.toPath(), LinkOption.NOFOLLOW_LINKS)) return false
        val ownerPath = File(parent, ownerName).toPath()
        if (ownerName != ignoredName && Files.exists(ownerPath, LinkOption.NOFOLLOW_LINKS)) return true

        val fixedNames = FIXED_NAMESPACE_SUFFIXES.mapTo(hashSetOf()) { ownerName + it }
        val replacement = Regex(
            "^${Regex.escape(ownerName)}\\.replace-[0-9a-f]{32}\\.(backup|staged)$"
        )
        val rewrite = Regex(
            "^${Regex.escape(ownerName)}\\.rewrite-[0-9a-f]{32}\\.[A-Za-z0-9_-]+$"
        )
        return parent.listFiles().orEmpty().any { sibling ->
            sibling.name != ignoredName && (
                sibling.name in fixedNames ||
                    replacement.matches(sibling.name) ||
                    rewrite.matches(sibling.name)
                )
        }
    }

    private fun requireSafeExistingComponents(path: Path) {
        val root = requireNotNull(path.root) { "managed sovereign path must be absolute: $path" }
        var cursor = root
        for (component in path) {
            cursor = cursor.resolve(component)
            if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) return
            require(!Files.isSymbolicLink(cursor)) {
                "managed sovereign path contains a symbolic link: $cursor"
            }
            if (cursor != path) {
                require(Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) {
                    "managed sovereign parent component is not a directory: $cursor"
                }
            }
        }
    }

    private fun normalized(file: File): Path = file.toPath().toAbsolutePath().normalize()
}
