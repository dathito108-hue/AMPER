package io.amper.neuroos.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

enum class EpisodicMemoryOrigin {
    USER_INTENT,
    PERCEPTION,
    TOOL_OUTCOME,
    GOAL_TRANSITION,
    CONVERSATION_TURN,
    SEMANTIC_KNOWLEDGE,
    PROCEDURAL_MEMORY
}

enum class EpisodicAdmissionStatus {
    ADMITTED,
    DUPLICATE,
    BELOW_IMPORTANCE,
    DISALLOWED_ORIGIN,
    RETENTION_DROPPED
}

data class EpisodicMemoryPolicy(
    val maxEpisodes: Int = DEFAULT_MAX_EPISODES,
    val minImportance: Double = DEFAULT_MIN_IMPORTANCE,
    val maxContentChars: Int = DEFAULT_MAX_CONTENT_CHARS,
    val duplicateWindowMs: Long = DEFAULT_DUPLICATE_WINDOW_MS,
    val maxScanRecords: Int = DEFAULT_MAX_SCAN_RECORDS,
    val allowedOrigins: Set<EpisodicMemoryOrigin> = DEFAULT_ALLOWED_ORIGINS
) {
    init {
        require(maxEpisodes in 1..MAX_EPISODES_LIMIT)
        require(minImportance in 0.0..1.0)
        require(maxContentChars in 64..MAX_CONTENT_CHARS_LIMIT)
        require(duplicateWindowMs >= 0L)
        require(maxScanRecords in maxEpisodes..MAX_SCAN_RECORDS_LIMIT)
        require(allowedOrigins.isNotEmpty())
        require(
            EpisodicMemoryOrigin.CONVERSATION_TURN !in allowedOrigins &&
                EpisodicMemoryOrigin.SEMANTIC_KNOWLEDGE !in allowedOrigins &&
                EpisodicMemoryOrigin.PROCEDURAL_MEMORY !in allowedOrigins
        ) {
            "episodic memory must not duplicate canonical conversation, semantic, or procedural stores"
        }
    }

    companion object {
        const val DEFAULT_MAX_EPISODES: Int = 256
        const val MAX_EPISODES_LIMIT: Int = 512
        const val DEFAULT_MIN_IMPORTANCE: Double = 0.35
        const val DEFAULT_MAX_CONTENT_CHARS: Int = 1024
        const val MAX_CONTENT_CHARS_LIMIT: Int = 4096
        const val DEFAULT_DUPLICATE_WINDOW_MS: Long = 10L * 60L * 1000L
        const val DEFAULT_MAX_SCAN_RECORDS: Int = 4096
        const val MAX_SCAN_RECORDS_LIMIT: Int = 4096

        val DEFAULT_ALLOWED_ORIGINS: Set<EpisodicMemoryOrigin> = linkedSetOf(
            EpisodicMemoryOrigin.USER_INTENT,
            EpisodicMemoryOrigin.PERCEPTION,
            EpisodicMemoryOrigin.TOOL_OUTCOME,
            EpisodicMemoryOrigin.GOAL_TRANSITION
        )
    }
}

data class EpisodicObservation(
    val origin: EpisodicMemoryOrigin,
    val content: String,
    val importance: Double,
    val provenance: Provenance,
    val observedAtEpochMs: Long = provenance.observedAtEpochMs
) {
    init {
        require(content.isNotBlank())
        require(importance in 0.0..1.0)
        require(observedAtEpochMs >= 0L)
    }
}

data class EpisodicMemoryEntry(
    val id: MemoryId,
    val origin: EpisodicMemoryOrigin,
    val content: String,
    val importance: Double,
    val provenance: Provenance,
    val observedAtEpochMs: Long,
    val fingerprintSha256: String
) {
    init {
        require(content.isNotBlank())
        require(importance in 0.0..1.0)
        require(observedAtEpochMs >= 0L)
        require(fingerprintSha256.matches(Regex("[0-9a-f]{64}")))
    }
}

data class EpisodicAdmissionResult(
    val status: EpisodicAdmissionStatus,
    val entry: EpisodicMemoryEntry?
) {
    init {
        require((status == EpisodicAdmissionStatus.ADMITTED) == (entry != null))
    }
}

interface EpisodicMemoryStore {
    fun admit(observation: EpisodicObservation): Result<EpisodicAdmissionResult>
    fun recent(query: String = "", limit: Int = 8): Result<List<EpisodicMemoryEntry>>
}

/**
 * Canonical episodic admission/retention over the one sovereign MemoryOs.
 *
 * No second episodic database or index is created. The bounded index is a read-only projection over
 * MemoryOs episodic-v1 records. Conversation turns, semantic knowledge and procedural memory are
 * disallowed origins so those canonical stores are never copied into episodic memory.
 */
class CanonicalEpisodicMemoryStore(
    private val memory: MemoryOs,
    private val policy: EpisodicMemoryPolicy = EpisodicMemoryPolicy()
) : EpisodicMemoryStore {
    override fun admit(
        observation: EpisodicObservation
    ): Result<EpisodicAdmissionResult> = runCatching {
        if (observation.origin !in policy.allowedOrigins) {
            return@runCatching EpisodicAdmissionResult(
                EpisodicAdmissionStatus.DISALLOWED_ORIGIN,
                null
            )
        }
        if (observation.importance < policy.minImportance) {
            return@runCatching EpisodicAdmissionResult(
                EpisodicAdmissionStatus.BELOW_IMPORTANCE,
                null
            )
        }

        val content = sanitize(observation.content)
        require(content.isNotBlank()) { "episodic content became blank after sanitization" }
        val fingerprint = fingerprint(
            origin = observation.origin,
            content = content,
            provenance = observation.provenance
        )
        val candidate = EpisodicMemoryEntry(
            id = MemoryId(
                "episodic:" + sha256(
                    listOf(fingerprint, observation.observedAtEpochMs.toString()).joinToString("|")
                )
            ),
            origin = observation.origin,
            content = content,
            importance = observation.importance,
            provenance = observation.provenance,
            observedAtEpochMs = observation.observedAtEpochMs,
            fingerprintSha256 = fingerprint
        )

        memory.transaction {
            val existing = loadAll()
            if (
                existing.any { entry ->
                    entry.fingerprintSha256 == candidate.fingerprintSha256 &&
                        withinDuplicateWindow(
                            entry.observedAtEpochMs,
                            candidate.observedAtEpochMs
                        )
                }
            ) {
                return@transaction EpisodicAdmissionResult(
                    EpisodicAdmissionStatus.DUPLICATE,
                    null
                )
            }

            val retained = (existing + candidate)
                .sortedWith(RETENTION_ORDER)
                .take(policy.maxEpisodes)
            if (retained.none { it.id == candidate.id }) {
                return@transaction EpisodicAdmissionResult(
                    EpisodicAdmissionStatus.RETENTION_DROPPED,
                    null
                )
            }

            val inserted = rememberIfAbsent(EpisodicMemoryCodec.encode(candidate))
            if (!inserted) {
                return@transaction EpisodicAdmissionResult(
                    EpisodicAdmissionStatus.DUPLICATE,
                    null
                )
            }

            val retainedIds = retained.mapTo(hashSetOf()) { it.id }
            existing
                .asSequence()
                .filterNot { it.id in retainedIds }
                .forEach { forget(it.id) }

            EpisodicAdmissionResult(EpisodicAdmissionStatus.ADMITTED, candidate)
        }
    }

    override fun recent(
        query: String,
        limit: Int
    ): Result<List<EpisodicMemoryEntry>> = runCatching {
        require(limit in 0..MAX_QUERY_LIMIT)
        if (limit == 0) return@runCatching emptyList()

        loadAllRecords()
            .asSequence()
            .map { record ->
                Triple(
                    EpisodicMemoryCodec.decode(record),
                    record,
                    MemoryRetrievalScorer.score(record, query.trim())
                )
            }
            .filter { it.third.matched }
            .sortedWith(
                compareByDescending<Triple<EpisodicMemoryEntry, MemoryRecord, MemoryRetrievalScorer.Score>> {
                    it.third.value
                }.thenByDescending { it.first.observedAtEpochMs }
                    .thenBy { it.first.id.value }
            )
            .take(limit)
            .map { it.first }
            .toList()
    }

    private fun loadAll(): List<EpisodicMemoryEntry> =
        loadAllRecords().map(EpisodicMemoryCodec::decode)

    private fun loadAllRecords(): List<MemoryRecord> {
        val total = memory.size()
        require(total <= policy.maxScanRecords) {
            "episodic scan bound exceeded: " + total + " > " + policy.maxScanRecords
        }
        if (total == 0) return emptyList()
        return memory.recall("", total).filter { it.kind == KIND }
    }

    private fun sanitize(value: String): String =
        value
            .replace(Regex("\\p{Cc}"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(policy.maxContentChars)

    private fun withinDuplicateWindow(first: Long, second: Long): Boolean {
        val older = minOf(first, second)
        val newer = maxOf(first, second)
        return newer - older <= policy.duplicateWindowMs
    }

    private fun fingerprint(
        origin: EpisodicMemoryOrigin,
        content: String,
        provenance: Provenance
    ): String =
        sha256(
            listOf(
                EPISODIC_FINGERPRINT_VERSION,
                origin.name,
                content.lowercase(),
                provenance.source,
                provenance.producer
            ).joinToString("|")
        )

    companion object {
        const val KIND = "episodic-v1"
        const val MAX_QUERY_LIMIT = 64
        private const val EPISODIC_FINGERPRINT_VERSION = "AMPER_EPISODIC_FINGERPRINT_V1"

        private val RETENTION_ORDER =
            compareByDescending<EpisodicMemoryEntry> { it.importance }
                .thenByDescending { it.observedAtEpochMs }
                .thenBy { it.id.value }
    }
}

internal object EpisodicMemoryCodec {
    private const val VERSION = "AMPER_EPISODIC_V1"

    fun encode(entry: EpisodicMemoryEntry): MemoryRecord {
        val searchable = entry.content.replace('\n', ' ').replace('\r', ' ').trim()
        val payload = listOf(
            VERSION,
            entry.origin.name,
            enc(entry.content),
            entry.importance.toString(),
            entry.observedAtEpochMs.toString(),
            entry.fingerprintSha256
        ).joinToString("|")
        return MemoryRecord(
            id = entry.id,
            kind = CanonicalEpisodicMemoryStore.KIND,
            content = "search=" + searchable + "\npayload=" + payload,
            importance = entry.importance,
            provenance = entry.provenance,
            createdAtEpochMs = entry.observedAtEpochMs
        )
    }

    fun decode(record: MemoryRecord): EpisodicMemoryEntry {
        require(record.kind == CanonicalEpisodicMemoryStore.KIND)
        val payload = record.content.lineSequence()
            .firstOrNull { it.startsWith("payload=") }
            ?.removePrefix("payload=")
            ?: error("episodic payload missing")
        val fields = payload.split('|')
        require(fields.size == 6 && fields[0] == VERSION) { "unsupported episodic record" }
        val entry = EpisodicMemoryEntry(
            id = record.id,
            origin = EpisodicMemoryOrigin.valueOf(fields[1]),
            content = dec(fields[2]),
            importance = fields[3].toDouble(),
            provenance = record.provenance,
            observedAtEpochMs = fields[4].toLong(),
            fingerprintSha256 = fields[5]
        )
        require(record.importance == entry.importance) { "episodic importance drift" }
        require(record.createdAtEpochMs == entry.observedAtEpochMs) { "episodic timestamp drift" }
        return entry
    }

    private fun enc(value: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun dec(value: String): String =
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
}

private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
