package io.amper.neuroos.core

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64

interface MemoryJournal {
    fun append(record: MemoryRecord)
    fun tombstone(id: MemoryId)
    fun replay(): List<MemoryRecord>
    fun compact(): Int = 0
}

class InMemoryMemoryJournal : MemoryJournal {
    private val records = linkedMapOf<MemoryId, MemoryRecord>()

    @Synchronized override fun append(record: MemoryRecord) { records[record.id] = record }
    @Synchronized override fun tombstone(id: MemoryId) { records.remove(id) }
    @Synchronized override fun replay(): List<MemoryRecord> = records.values.toList()
}

class FileMemoryJournal(private val file: File) : MemoryJournal {
    private val lock = Any()
    private val processLock = MemoryJournalProcessLock(file)
    private val headAnchor = PlaintextMemoryJournalHeadAnchor(headFileFor(file))
    private var chainState = MemoryJournalChainState.GENESIS

    init {
        DurableJournalIo.ensureFileExistsDurably(file)
        synchronized(lock) {
            processLock.exclusive {
                migrateAndLoadChainLocked()
                chainState = verifyCurrentHeadLocked()
            }
        }
    }

    override fun append(record: MemoryRecord) = synchronized(lock) {
        processLock.exclusive {
            chainState = verifyCurrentHeadLocked()
            appendPayloadLocked(MemoryJournalCodec.encodeRecord(record))
        }
    }

    override fun tombstone(id: MemoryId) = synchronized(lock) {
        processLock.exclusive {
            chainState = verifyCurrentHeadLocked()
            appendPayloadLocked(MemoryJournalCodec.encodeTombstone(id))
        }
    }

    override fun replay(): List<MemoryRecord> = synchronized(lock) {
        processLock.exclusive {
            val segment = readSegmentLocked()
            chainState = MemoryJournalHeadVerifier.verifyAndReconcile(segment.entries, headAnchor, segment.base)
            liveRecords(segment.entries)
        }
    }

    override fun compact(): Int = synchronized(lock) {
        processLock.exclusive {
            val physicalLines = DurableJournalIo.readCompleteUtf8LinesRecoveringTornTail(file)
            if (physicalLines.isEmpty()) return@exclusive 0

            val segment = readSegmentLocked(physicalLines)
            val oldHead = MemoryJournalHeadVerifier.verifyAndReconcile(segment.entries, headAnchor, segment.base)
            val live = liveRecords(segment.entries).sortedBy { it.id.value }

            var state = oldHead
            val snapshotEntries = live.map { record ->
                MemoryJournalChainCodec.encode(MemoryJournalCodec.encodeRecord(record), state).also {
                    state = MemoryJournalChainState(it.sequence, it.digest)
                }
            }
            val compactedLines = buildList {
                add(MemoryJournalBaseCodec.encode(oldHead))
                snapshotEntries.forEach { add(it.encoded) }
            }
            if (compactedLines.size >= physicalLines.size) return@exclusive 0

            // The compacted file descends from oldHead through B1. If the process dies after
            // this atomic rewrite but before H1 moves to state, old H1 == B1 and startup can
            // safely recognize the new snapshot as a verified extension.
            DurableJournalIo.rewriteUtf8LinesAtomically(file, ".compacting", compactedLines)
            headAnchor.store(state)
            chainState = state
            physicalLines.size - compactedLines.size
        }
    }

    private fun verifyCurrentHeadLocked(): MemoryJournalChainState {
        val segment = readSegmentLocked()
        return MemoryJournalHeadVerifier.verifyAndReconcile(segment.entries, headAnchor, segment.base)
    }

    private fun appendPayloadLocked(payload: String) {
        validateMutationPayload(payload)
        val entry = MemoryJournalChainCodec.encode(payload, chainState)
        DurableJournalIo.appendUtf8Line(file, entry.encoded)
        chainState = MemoryJournalChainState(entry.sequence, entry.digest)
        headAnchor.store(chainState)
    }

    private fun readSegmentLocked(
        lines: List<String> = DurableJournalIo.readCompleteUtf8LinesRecoveringTornTail(file)
    ): MemoryJournalSegment {
        val segment = MemoryJournalSegmentCodec.decodePlaintext(lines).getOrThrow()
        segment.entries.forEach { validateMutationPayload(it.payload) }
        return segment
    }

    private fun migrateAndLoadChainLocked(): MemoryJournalChainState {
        val lines = DurableJournalIo.readCompleteUtf8LinesRecoveringTornTail(file)
        if (lines.isEmpty()) return MemoryJournalChainState.GENESIS
        lines.forEach { require(it.isNotBlank()) { "blank complete memory journal line" } }

        if (MemoryJournalBaseCodec.isBase(lines.first())) {
            return readSegmentLocked(lines).head
        }
        require(lines.none(MemoryJournalBaseCodec::isBase)) { "memory journal base checkpoint must appear only first" }

        val chainedCount = lines.count { MemoryJournalChainCodec.isChained(it) }
        if (chainedCount == lines.size) {
            val entries = MemoryJournalChainCodec.decodeSequence(lines).getOrThrow()
            entries.forEach { validateMutationPayload(it.payload) }
            return MemoryJournalChainCodec.stateOf(entries)
        }
        require(chainedCount == 0) { "mixed chained and legacy memory journal framing" }

        val payloads = lines.map { stored ->
            MemoryJournalFrameCodec.unwrapFramedOrLegacy(stored).getOrThrow().also(::validateMutationPayload)
        }
        var state = MemoryJournalChainState.GENESIS
        val migrated = payloads.map { payload ->
            val entry = MemoryJournalChainCodec.encode(payload, state)
            state = MemoryJournalChainState(entry.sequence, entry.digest)
            entry.encoded
        }
        DurableJournalIo.rewriteUtf8LinesAtomically(file, ".chaining", migrated)
        return state
    }

    private fun liveRecords(entries: List<MemoryJournalChainEntry>): List<MemoryRecord> {
        val live = linkedMapOf<MemoryId, MemoryRecord>()
        entries.forEach { entry ->
            val payload = entry.payload
            when {
                payload.startsWith("R|") -> {
                    val record = requireNotNull(MemoryJournalCodec.decodeRecord(payload)) {
                        "invalid complete memory journal record"
                    }
                    live[record.id] = record
                }
                payload.startsWith("D|") -> {
                    val recordId = requireNotNull(MemoryJournalCodec.decodeTombstone(payload)) {
                        "invalid complete memory journal tombstone"
                    }
                    live.remove(recordId)
                }
                else -> error("unrecognized complete memory journal payload")
            }
        }
        return live.values.toList()
    }

    private fun validateMutationPayload(payload: String) {
        when {
            payload.startsWith("R|") -> require(MemoryJournalCodec.decodeRecord(payload) != null) {
                "invalid complete memory journal record"
            }
            payload.startsWith("D|") -> require(MemoryJournalCodec.decodeTombstone(payload) != null) {
                "invalid complete memory journal tombstone"
            }
            else -> error("unrecognized complete memory journal payload")
        }
    }

    companion object {
        internal fun headFileFor(file: File): File = File(file.parentFile, file.name + ".head")
        internal fun lockFileFor(file: File): File = File(file.parentFile, file.name + ".lock")
    }
}

class PersistentMemoryOs(
    private val journal: MemoryJournal,
    private val maxRecords: Int = 4096
) : MemoryOs {
    private val lock = Any()
    private val records = linkedMapOf<MemoryId, MemoryRecord>()

    init {
        require(maxRecords > 0)
        journal.replay().forEach { records[it.id] = it }
    }

    override fun remember(record: MemoryRecord) = synchronized(lock) {
        val evictions = plannedEvictionsFor(record)
        journal.append(record)
        records[record.id] = record
        applyEvictions(evictions)
    }

    override fun rememberIfAbsent(record: MemoryRecord): Boolean = synchronized(lock) {
        if (records.containsKey(record.id)) return@synchronized false
        val evictions = plannedEvictionsFor(record)
        journal.append(record)
        records[record.id] = record
        applyEvictions(evictions)
        true
    }

    override fun <T> transaction(block: MemoryOs.() -> T): T = synchronized(lock) { block(this) }

    override fun compact(): Int = synchronized(lock) { journal.compact() }

    override fun recall(query: String, limit: Int): List<MemoryRecord> = synchronized(lock) {
        require(limit >= 0)
        if (limit == 0) return@synchronized emptyList()

        records.values.asSequence()
            .map { record -> record to MemoryRetrievalScorer.score(record, query) }
            .filter { (_, score) -> score.matched }
            .sortedWith(
                compareByDescending<Pair<MemoryRecord, MemoryRetrievalScorer.Score>> { it.second.value }
                    .thenByDescending { it.first.createdAtEpochMs }
                    .thenBy { it.first.id.value }
            )
            .take(limit)
            .map { it.first }
            .toList()
    }

    override fun get(id: MemoryId): MemoryRecord? = synchronized(lock) { records[id] }

    override fun forget(id: MemoryId): Boolean = synchronized(lock) {
        if (!records.containsKey(id)) return@synchronized false
        if (id in referencedIds(records)) return@synchronized false
        records.remove(id)
        journal.tombstone(id)
        true
    }

    override fun size(): Int = synchronized(lock) { records.size }

    private fun plannedEvictionsFor(record: MemoryRecord): List<MemoryId> {
        val next = LinkedHashMap(records)
        next[record.id] = record
        val overflow = next.size - maxRecords
        if (overflow <= 0) return emptyList()

        val referenced = referencedIds(next)
        val candidates = next.values
            .asSequence()
            .filterNot { it.id == record.id }
            .filterNot { it.id in referenced }
            .sortedWith(
                compareBy<MemoryRecord> { it.importance }
                    .thenBy { it.createdAtEpochMs }
                    .thenBy { it.id.value }
            )
            .take(overflow)
            .map { it.id }
            .toList()

        require(candidates.size == overflow) {
            "memory capacity cannot be reduced without deleting referenced lineage"
        }
        return candidates
    }

    private fun applyEvictions(evictions: List<MemoryId>) {
        evictions.forEach { id ->
            if (records.remove(id) != null) {
                journal.tombstone(id)
            }
        }
    }

    private fun referencedIds(live: Map<MemoryId, MemoryRecord>): Set<MemoryId> {
        if (live.isEmpty()) return emptySet()
        val referenced = linkedSetOf<MemoryId>()

        live.values.forEach { record ->
            record.provenance.parents
                .filterTo(referenced) { it in live }
        }

        val recordsWithContent = live.values.toList()
        live.keys.forEach { candidateId ->
            if (
                recordsWithContent.any { record ->
                    record.id != candidateId &&
                        record.content.contains(candidateId.value)
                }
            ) {
                referenced += candidateId
            }
        }
        return referenced
    }
}

internal object MemoryJournalCodec {
    fun encodeRecord(record: MemoryRecord): String = listOf(
        "R", enc(record.id.value), enc(record.kind), enc(record.content), record.importance.toString(),
        record.createdAtEpochMs.toString(), enc(record.provenance.source), enc(record.provenance.producer),
        record.provenance.observedAtEpochMs.toString(), record.provenance.confidence.toString(),
        if (record.provenance.parents.isEmpty()) "~" else record.provenance.parents.joinToString(",") { enc(it.value) }
    ).joinToString("|")

    fun encodeTombstone(id: MemoryId): String = "D|${enc(id.value)}"

    fun decodeRecord(line: String): MemoryRecord? = runCatching {
        val p = line.split('|')
        require(p.size == 11 && p[0] == "R")
        val parents = if (p[10] == "~") emptySet() else p[10].split(',').map { MemoryId(dec(it)) }.toSet()
        MemoryRecord(
            id = MemoryId(dec(p[1])), kind = dec(p[2]), content = dec(p[3]), importance = p[4].toDouble(),
            createdAtEpochMs = p[5].toLong(),
            provenance = Provenance(
                source = dec(p[6]), producer = dec(p[7]), observedAtEpochMs = p[8].toLong(),
                confidence = p[9].toDouble(), parents = parents
            )
        )
    }.getOrNull()

    fun decodeTombstone(line: String): MemoryId? = runCatching {
        val p = line.split('|')
        require(p.size == 2 && p[0] == "D")
        MemoryId(dec(p[1]))
    }.getOrNull()

    private fun enc(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))
    private fun dec(value: String): String = String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
}
