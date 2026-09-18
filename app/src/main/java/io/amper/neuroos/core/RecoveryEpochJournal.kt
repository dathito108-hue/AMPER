package io.amper.neuroos.core

import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Monotonic local generation journal for non-atomic replacement recovery.
 *
 * G1 entries form a hash-linked append-only tail. G2 mirrored checkpoints bound that tail.
 * Phase 45 adds an independently checksummed G3 authority witness and A1 activation witness.
 *
 * Phase 46 makes the bounded authority set self-healing without turning "two stale copies" into
 * a rollback vote. With an empty G1 tail, G3 must be endorsed by at least one G2 checkpoint.
 * Missing/corrupt G2 files and strictly older rotation survivors are repaired only after that
 * G3+G2 endorsement is established. A newer or same-epoch divergent G2 remains fail-closed.
 *
 * Compaction rotates which G2 slot advances first. G3 and exactly one G2 are durably advanced
 * before G1 truncation while the other G2 deliberately retains the old tail anchor. After the
 * tail is synced empty, the retained slot is advanced last. Every crash boundary therefore
 * leaves either a readable G1 bridge or a G3+G2 quorum at the new generation.
 *
 * Phase 62 removes direct File/RandomAccessFile I/O from this authority set. G1/G2/G3/A1
 * reads, existence checks and compaction truncation now pass through descriptor-bound,
 * NOFOLLOW identity validation and the shared exact-target lease domain.
 *
 * Phase 63 wraps every complete current/advance operation in one persistent authority-set
 * lease. A reader therefore cannot combine G1/G2/G3/A1 snapshots from different points of a
 * concurrent compaction or self-heal, while individual leaves retain their descriptor leases.
 *
 * This is local filesystem anti-replay, not hardware rollback resistance: an attacker able to
 * roll back or remove every quorum authority, activation witness and G1 tail together remains
 * outside this contract.
 */
internal class RecoveryEpochJournal(private val target: File) {
    private val file = File(target.parentFile, target.name + ".recovery-epochs")
    private val checkpointA = File(target.parentFile, target.name + ".recovery-epochs.a")
    private val checkpointB = File(target.parentFile, target.name + ".recovery-epochs.b")
    private val authorityFile = File(target.parentFile, target.name + ".recovery-epochs.authority")
    private val activationFile = File(target.parentFile, target.name + ".recovery-epochs.active")

    fun current(): Long = RecoveryEpochAuthorityLease.withExclusive(target) {
        loadState().epoch
    }

    fun advance(): Long = RecoveryEpochAuthorityLease.withExclusive(target) {
        val state = loadState()
        val next = Math.addExact(state.epoch, 1L)
        DurableJournalIo.ensureFileExistsDurably(file)
        val encoded = encodeEpoch(EpochEntry(next, state.lastDigest))
        DurableJournalIo.appendUtf8Line(file, encoded)
        persistActivationIfNeeded()
        val advanced = State(next, sha256(encoded.toByteArray(StandardCharsets.UTF_8)), state.tailEntries + 1)
        if (advanced.tailEntries >= COMPACT_EVERY) compact(advanced)
        next
    }

    fun fileForTest(): File = file
    internal fun checkpointFilesForTest(): List<File> = listOf(checkpointA, checkpointB)
    internal fun authorityFileForTest(): File = authorityFile
    internal fun activationFileForTest(): File = activationFile
    internal fun authorityLeaseFileForTest(): File = RecoveryEpochAuthorityLease.lockFileFor(target)
    internal fun compactEveryForTest(): Int = COMPACT_EVERY

    private data class State(
        val epoch: Long,
        val lastDigest: String,
        val tailEntries: Int
    )

    private data class EpochEntry(
        val epoch: Long,
        val previousDigest: String
    )

    private data class Checkpoint(
        val epoch: Long,
        val lastDigest: String
    )

    private data class CheckpointSource(
        val checkpoint: Checkpoint,
        val source: File
    ) {
        val epoch: Long get() = checkpoint.epoch
        val lastDigest: String get() = checkpoint.lastDigest
    }

    private data class DecodedTail(
        val entries: List<EpochEntry>,
        val digests: List<String>
    )

    private fun loadState(): State {
        val checkpoints = loadValidCheckpoints()
        val authority = decodeAuthorityFile(authorityFile)
        val lines = readCompleteLinesRecoveringTornTail()

        if (lines.isEmpty()) {
            return loadEmptyTailState(checkpoints, authority)
        }

        val tail = decodeTail(lines)
        val first = tail.entries.first()
        val last = tail.entries.last()
        val sources = buildList {
            addAll(checkpoints)
            authority?.let(::add)
        }

        val anchor = resolveTailAnchor(first, sources)
        require(first.previousDigest == anchor.lastDigest) {
            "replacement recovery epoch tail anchor diverged"
        }

        var expectedEpoch = first.epoch
        var previousDigest = anchor.lastDigest
        tail.entries.indices.forEach { index ->
            val entry = tail.entries[index]
            require(entry.epoch == expectedEpoch) {
                "replacement recovery epoch sequence is not contiguous"
            }
            require(entry.previousDigest == previousDigest) {
                "replacement recovery epoch chain diverged"
            }
            previousDigest = tail.digests[index]
            expectedEpoch = Math.addExact(expectedEpoch, 1L)
        }

        sources.forEach { source ->
            when {
                source.epoch == anchor.epoch -> {
                    require(source.lastDigest == anchor.lastDigest) {
                        "replacement recovery authority disagrees with tail anchor"
                    }
                }
                source.epoch in first.epoch..last.epoch -> {
                    val index = Math.toIntExact(source.epoch - first.epoch)
                    require(tail.digests[index] == source.lastDigest) {
                        "replacement recovery authority digest diverged from overlapping tail"
                    }
                }
                source.epoch < anchor.epoch -> {
                    error("replacement recovery authority is older than the verified tail anchor")
                }
                else -> error("replacement recovery authority advances beyond verified tail")
            }
        }

        if (authority == null && checkpoints.isNotEmpty()) {
            val checkpointStates = checkpoints.map { it.checkpoint }.distinct()
            when {
                !RecoveryEpochDescriptorIo.existsManaged(authorityFile) -> {
                    persistAuthority(checkpoints.maxBy { it.epoch }.checkpoint)
                }
                checkpointStates.size == 1 && checkpoints.size >= QUORUM -> {
                    persistAuthority(checkpointStates.single())
                }
            }
        }
        persistActivationIfNeeded()

        return State(last.epoch, tail.digests.last(), tail.entries.size)
    }

    private fun resolveTailAnchor(
        first: EpochEntry,
        sources: List<CheckpointSource>
    ): Checkpoint = when {
        first.epoch == 1L -> Checkpoint(0L, GENESIS_DIGEST)
        else -> {
            val anchors = sources.filter { it.epoch == first.epoch - 1L }
                .map { it.checkpoint }
                .distinct()
            require(anchors.size == 1) {
                "replacement recovery epoch tail has no unique durable authority anchor"
            }
            anchors.single()
        }
    }

    private fun loadEmptyTailState(
        checkpoints: List<CheckpointSource>,
        initialAuthority: CheckpointSource?
    ): State {
        val protocolEvidence = RecoveryEpochDescriptorIo.existsManaged(activationFile) ||
            RecoveryEpochDescriptorIo.existsManaged(checkpointA) ||
            RecoveryEpochDescriptorIo.existsManaged(checkpointB) ||
            RecoveryEpochDescriptorIo.existsManaged(authorityFile)
        if (!protocolEvidence) {
            return State(0L, GENESIS_DIGEST, 0)
        }

        var authority = initialAuthority
        if (authority == null) {
            val checkpointStates = checkpoints.map { it.checkpoint }.distinct()
            require(checkpointStates.isNotEmpty()) {
                "replacement recovery checkpoint authority lost while epoch protocol is active"
            }
            require(checkpointStates.size == 1) {
                "replacement recovery checkpoints disagree with no G1 bridge"
            }

            if (RecoveryEpochDescriptorIo.existsManaged(authorityFile)) {
                require(checkpoints.size >= QUORUM) {
                    "replacement recovery authority corrupt and checkpoint quorum is insufficient"
                }
            }
            authority = persistAuthority(checkpointStates.single())
        }

        val agreed = authority.checkpoint
        val freshCheckpoints = loadValidCheckpoints()
        val matching = freshCheckpoints.filter { it.checkpoint == agreed }
        require(matching.isNotEmpty()) {
            "replacement recovery G3 authority has no G2 endorsement"
        }

        freshCheckpoints.filter { it.checkpoint != agreed }.forEach { outlier ->
            require(outlier.epoch < agreed.epoch) {
                "replacement recovery checkpoint conflicts with G3 quorum"
            }
        }

        healCheckpointSet(agreed)

        val healed = loadValidCheckpoints()
        require(healed.size == 2 && healed.all { it.checkpoint == agreed }) {
            "replacement recovery checkpoint self-heal did not converge"
        }
        require(decodeAuthorityFile(authorityFile)?.checkpoint == agreed) {
            "replacement recovery G3 authority changed during checkpoint self-heal"
        }

        persistActivationIfNeeded()
        return State(agreed.epoch, agreed.lastDigest, 0)
    }

    private fun healCheckpointSet(checkpoint: Checkpoint) {
        listOf(checkpointA, checkpointB).forEach { destination ->
            val persisted = decodeCheckpointFile(destination)
            when {
                persisted == null -> persistCheckpoint(destination, checkpoint)
                persisted.checkpoint == checkpoint -> Unit
                persisted.epoch < checkpoint.epoch -> persistCheckpoint(destination, checkpoint)
                else -> error("replacement recovery checkpoint is not a repairable older rotation survivor")
            }
        }
    }

    private fun decodeTail(lines: List<String>): DecodedTail = DecodedTail(
        entries = lines.map(::decodeEpoch),
        digests = lines.map { sha256(it.toByteArray(StandardCharsets.UTF_8)) }
    )

    private fun compact(state: State) {
        require(state.epoch > 0)
        val parent = target.parentFile ?: error("replacement target has no parent directory")
        DurableJournalIo.ensureDirectoryExistsDurably(parent)
        persistActivationIfNeeded()

        val lines = readCompleteLinesRecoveringTornTail()
        require(lines.isNotEmpty()) { "replacement recovery compaction requires a non-empty G1 tail" }
        val tail = decodeTail(lines)
        require(tail.entries.size == state.tailEntries) {
            "replacement recovery compaction tail size changed"
        }
        require(tail.entries.last().epoch == state.epoch && tail.digests.last() == state.lastDigest) {
            "replacement recovery compaction state diverged from verified G1 head"
        }

        val first = tail.entries.first()
        val sources = buildList {
            addAll(loadValidCheckpoints())
            decodeAuthorityFile(authorityFile)?.let(::add)
        }
        val anchor = resolveTailAnchor(first, sources)
        require(first.previousDigest == anchor.lastDigest) {
            "replacement recovery compaction anchor diverged"
        }

        val checkpoint = Checkpoint(state.epoch, state.lastDigest)
        val advanceSlot = rotationAdvanceSlot(state.epoch)
        val retainedSlot = if (advanceSlot == checkpointA) checkpointB else checkpointA

        if (anchor.epoch > 0L) {
            val retained = decodeCheckpointFile(retainedSlot)
            if (retained?.checkpoint != anchor) {
                persistCheckpoint(retainedSlot, anchor)
            }
        }

        persistAuthority(checkpoint)
        persistCheckpoint(advanceSlot, checkpoint)

        if (RecoveryEpochDescriptorIo.existsManaged(file)) {
            RecoveryEpochDescriptorIo.truncateExistingAndForce(file)
        }

        persistCheckpoint(retainedSlot, checkpoint)
    }

    private fun rotationAdvanceSlot(epoch: Long): File {
        val generation = epoch / COMPACT_EVERY.toLong()
        return if ((generation and 1L) == 0L) checkpointA else checkpointB
    }

    private fun persistCheckpoint(destination: File, checkpoint: Checkpoint): CheckpointSource {
        val parent = target.parentFile ?: error("replacement target has no parent directory")
        DurableJournalIo.ensureDirectoryExistsDurably(parent)
        DurableJournalIo.writeUtf8AndSync(destination, encodeCheckpoint(checkpoint) + "\n")
        DurableJournalIo.syncDirectory(parent)
        return requireNotNull(decodeCheckpointFile(destination)) {
            "replacement recovery checkpoint verification failed"
        }.also {
            require(it.checkpoint == checkpoint) {
                "replacement recovery checkpoint persisted wrong state"
            }
        }
    }

    private fun persistAuthority(checkpoint: Checkpoint): CheckpointSource {
        val parent = target.parentFile ?: error("replacement target has no parent directory")
        DurableJournalIo.ensureDirectoryExistsDurably(parent)
        DurableJournalIo.writeUtf8AndSync(authorityFile, encodeAuthority(checkpoint) + "\n")
        DurableJournalIo.syncDirectory(parent)
        return requireNotNull(decodeAuthorityFile(authorityFile)) {
            "replacement recovery authority verification failed"
        }.also {
            require(it.checkpoint == checkpoint) { "replacement recovery authority persisted wrong state" }
        }
    }

    private fun persistActivationIfNeeded() {
        if (activationRecordValid()) return
        val parent = target.parentFile ?: error("replacement target has no parent directory")
        DurableJournalIo.ensureDirectoryExistsDurably(parent)
        val canonical = "$ACTIVATION_VERSION|active"
        val encoded = "$canonical|${sha256(canonical.toByteArray(StandardCharsets.UTF_8))}\n"
        DurableJournalIo.writeUtf8AndSync(activationFile, encoded)
        DurableJournalIo.syncDirectory(parent)
        require(activationRecordValid()) { "replacement recovery activation witness verification failed" }
    }

    private fun activationRecordValid(): Boolean {
        val text = RecoveryEpochDescriptorIo.readTextIfExists(activationFile) ?: return false
        if (!text.endsWith('\n')) return false
        val lines = text.split('\n').dropLast(1)
        if (lines.size != 1 || lines.single().isBlank()) return false
        return runCatching {
            val parts = lines.single().split('|')
            require(parts.size == 3 && parts[0] == ACTIVATION_VERSION && parts[1] == "active")
            require(parts[2].matches(SHA256))
            val canonical = parts.take(2).joinToString("|")
            require(sha256(canonical.toByteArray(StandardCharsets.UTF_8)) == parts[2])
        }.isSuccess
    }

    private fun loadValidCheckpoints(): List<CheckpointSource> = listOf(checkpointA, checkpointB)
        .mapNotNull(::decodeCheckpointFile)
        .sortedBy { it.epoch }

    private fun decodeCheckpointFile(source: File): CheckpointSource? {
        val text = RecoveryEpochDescriptorIo.readTextIfExists(source) ?: return null
        if (!text.endsWith('\n')) return null
        val lines = text.split('\n').dropLast(1)
        if (lines.size != 1 || lines.single().isBlank()) return null
        return runCatching { CheckpointSource(decodeCheckpoint(lines.single()), source) }.getOrNull()
    }

    private fun decodeAuthorityFile(source: File): CheckpointSource? {
        val text = RecoveryEpochDescriptorIo.readTextIfExists(source) ?: return null
        if (!text.endsWith('\n')) return null
        val lines = text.split('\n').dropLast(1)
        if (lines.size != 1 || lines.single().isBlank()) return null
        return runCatching { CheckpointSource(decodeAuthority(lines.single()), source) }.getOrNull()
    }

    private fun encodeEpoch(entry: EpochEntry): String {
        require(entry.epoch > 0)
        require(entry.previousDigest.matches(SHA256))
        val canonical = listOf(EPOCH_VERSION, entry.epoch.toString(), entry.previousDigest).joinToString("|")
        return "$canonical|${sha256(canonical.toByteArray(StandardCharsets.UTF_8))}"
    }

    private fun decodeEpoch(value: String): EpochEntry {
        val parts = value.split('|')
        require(parts.size == 4 && parts[0] == EPOCH_VERSION) {
            "unsupported replacement recovery epoch entry"
        }
        val epoch = parts[1].toLong()
        require(epoch > 0) { "invalid replacement recovery epoch" }
        val previousDigest = parts[2]
        require(previousDigest.matches(SHA256)) { "invalid replacement recovery predecessor digest" }
        val checksum = parts[3]
        require(checksum.matches(SHA256)) { "invalid replacement recovery epoch checksum" }
        val canonical = parts.take(3).joinToString("|")
        require(sha256(canonical.toByteArray(StandardCharsets.UTF_8)) == checksum) {
            "replacement recovery epoch checksum mismatch"
        }
        return EpochEntry(epoch, previousDigest)
    }

    private fun encodeCheckpoint(checkpoint: Checkpoint): String =
        encodeBoundedAuthority(CHECKPOINT_VERSION, checkpoint)

    private fun decodeCheckpoint(value: String): Checkpoint = decodeBoundedAuthority(
        value,
        CHECKPOINT_VERSION,
        "checkpoint"
    )

    private fun encodeAuthority(checkpoint: Checkpoint): String =
        encodeBoundedAuthority(AUTHORITY_VERSION, checkpoint)

    private fun decodeAuthority(value: String): Checkpoint = decodeBoundedAuthority(
        value,
        AUTHORITY_VERSION,
        "authority"
    )

    private fun encodeBoundedAuthority(version: String, checkpoint: Checkpoint): String {
        require(checkpoint.epoch > 0)
        require(checkpoint.lastDigest.matches(SHA256))
        val canonical = listOf(version, checkpoint.epoch.toString(), checkpoint.lastDigest).joinToString("|")
        return "$canonical|${sha256(canonical.toByteArray(StandardCharsets.UTF_8))}"
    }

    private fun decodeBoundedAuthority(value: String, version: String, label: String): Checkpoint {
        val parts = value.split('|')
        require(parts.size == 4 && parts[0] == version) {
            "unsupported replacement recovery $label"
        }
        val epoch = parts[1].toLong()
        require(epoch > 0) { "invalid replacement recovery $label epoch" }
        val lastDigest = parts[2]
        require(lastDigest.matches(SHA256)) { "invalid replacement recovery $label digest" }
        val checksum = parts[3]
        require(checksum.matches(SHA256)) { "invalid replacement recovery $label checksum" }
        val canonical = parts.take(3).joinToString("|")
        require(sha256(canonical.toByteArray(StandardCharsets.UTF_8)) == checksum) {
            "replacement recovery $label checksum mismatch"
        }
        return Checkpoint(epoch, lastDigest)
    }

    private fun readCompleteLinesRecoveringTornTail(): List<String> =
        RecoveryEpochDescriptorIo.readCompleteLinesRecoveringTornTailIfExists(file).also { lines ->
            require(lines.none { it.isBlank() }) { "blank replacement recovery epoch entry" }
        }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        private const val EPOCH_VERSION = "G1"
        private const val CHECKPOINT_VERSION = "G2"
        private const val AUTHORITY_VERSION = "G3"
        private const val ACTIVATION_VERSION = "A1"
        private const val COMPACT_EVERY = 64
        private const val QUORUM = 2
        private val SHA256 = Regex("[0-9a-f]{64}")
        private const val GENESIS_DIGEST =
            "0000000000000000000000000000000000000000000000000000000000000000"
    }
}
