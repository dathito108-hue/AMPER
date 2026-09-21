package io.amper.neuroos.core.v2

import io.amper.neuroos.core.MemoryId
import io.amper.neuroos.core.MemoryOs
import io.amper.neuroos.core.MemoryRecord
import io.amper.neuroos.core.PlanId
import io.amper.neuroos.core.Provenance
import java.security.MessageDigest
import java.nio.charset.StandardCharsets
import java.util.Base64

data class AmperAgentProactiveAttentionAcknowledgement(
    val planId: PlanId,
    val revisionSha256: String,
    val acknowledgedAtEpochMs: Long
) {
    init {
        require(revisionSha256.matches(Regex("[0-9a-f]{64}")))
        require(acknowledgedAtEpochMs >= 0L)
    }
}

/**
 * Phase664 revision identity for discoverability acknowledgement only.
 *
 * Deliberately excludes goal, source id, trigger payload, tool input, and plan contents. A new
 * canonical attention kind, approval step, observation epoch, or PlanId therefore produces a new
 * revision and cannot be hidden by an acknowledgement of an older notification.
 */
object AmperAgentProactiveAttentionRevision {
    fun sha256(decision: AmperAgentProactiveAttentionDecision): String {
        require(decision.kind != AmperAgentProactiveAttentionKind.NONE)
        val canonical = buildString {
            append(decision.planId.value)
            append('|')
            append(decision.kind.name)
            append('|')
            append(decision.waitingApprovalStepIndex ?: -1)
            append('|')
            append(decision.observedAtEpochMs)
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

object AmperAgentProactiveAttentionAcknowledgementPolicy {
    fun matchesCurrentRevision(
        decision: AmperAgentProactiveAttentionDecision,
        expectedRevisionSha256: String
    ): Boolean =
        decision.kind != AmperAgentProactiveAttentionKind.NONE &&
            expectedRevisionSha256.matches(Regex("[0-9a-f]{64}")) &&
            AmperAgentProactiveAttentionRevision.sha256(decision) == expectedRevisionSha256
}

interface AmperAgentProactiveAttentionAcknowledgementLedger {
    val capacity: Int

    fun acknowledge(
        decision: AmperAgentProactiveAttentionDecision,
        acknowledgedAtEpochMs: Long = System.currentTimeMillis()
    ): Result<AmperAgentProactiveAttentionAcknowledgement>

    fun isAcknowledged(
        decision: AmperAgentProactiveAttentionDecision
    ): Result<Boolean>

    fun retainPlanIds(planIds: Set<PlanId>): Result<Int>

    fun recent(limit: Int = 16): Result<List<AmperAgentProactiveAttentionAcknowledgement>>
}

/**
 * Bounded acknowledgement index inside the existing sovereign MemoryOs.
 *
 * This is UI/discoverability state only. It stores no task state, planner state, approval decision,
 * scheduler state, tool receipt, goal, trigger payload, or tool input. Canonical persistent plans
 * remain the only execution truth.
 */
class MemoryBackedAmperAgentProactiveAttentionAcknowledgementLedger(
    private val memory: MemoryOs
) : AmperAgentProactiveAttentionAcknowledgementLedger {
    override val capacity: Int
        get() = MAX_ACKNOWLEDGEMENTS

    override fun acknowledge(
        decision: AmperAgentProactiveAttentionDecision,
        acknowledgedAtEpochMs: Long
    ): Result<AmperAgentProactiveAttentionAcknowledgement> = runCatching {
        require(decision.kind != AmperAgentProactiveAttentionKind.NONE)
        require(acknowledgedAtEpochMs >= 0L)
        val acknowledgement = AmperAgentProactiveAttentionAcknowledgement(
            planId = decision.planId,
            revisionSha256 = AmperAgentProactiveAttentionRevision.sha256(decision),
            acknowledgedAtEpochMs = acknowledgedAtEpochMs
        )
        memory.transaction {
            val entries = load()
            val existing = entries.singleOrNull { it.planId == acknowledgement.planId }
            if (existing?.revisionSha256 == acknowledgement.revisionSha256) {
                return@transaction existing
            }
            val retained = entries.filterNot { it.planId == acknowledgement.planId }
            require(retained.size < MAX_ACKNOWLEDGEMENTS) {
                "proactive attention acknowledgement ledger is full"
            }
            save(retained + acknowledgement)
            acknowledgement
        }
    }

    override fun isAcknowledged(
        decision: AmperAgentProactiveAttentionDecision
    ): Result<Boolean> = runCatching {
        if (decision.kind == AmperAgentProactiveAttentionKind.NONE) {
            return@runCatching false
        }
        val revision = AmperAgentProactiveAttentionRevision.sha256(decision)
        memory.transaction {
            load().singleOrNull { it.planId == decision.planId }
                ?.revisionSha256 == revision
        }
    }

    override fun retainPlanIds(planIds: Set<PlanId>): Result<Int> = runCatching {
        memory.transaction {
            val entries = load()
            val retained = entries.filter { it.planId in planIds }
            val removed = entries.size - retained.size
            if (removed > 0) save(retained)
            removed
        }
    }

    override fun recent(
        limit: Int
    ): Result<List<AmperAgentProactiveAttentionAcknowledgement>> = runCatching {
        require(limit >= 0)
        if (limit == 0) return@runCatching emptyList()
        memory.transaction {
            load()
                .sortedWith(
                    compareByDescending<AmperAgentProactiveAttentionAcknowledgement> {
                        it.acknowledgedAtEpochMs
                    }.thenBy { it.planId.value }
                )
                .take(limit)
        }
    }

    private fun MemoryOs.load(): List<AmperAgentProactiveAttentionAcknowledgement> {
        val record = get(RECORD_ID) ?: return emptyList()
        require(record.kind == KIND) {
            "proactive attention acknowledgement record kind drifted"
        }
        return AmperAgentProactiveAttentionAcknowledgementCodec
            .decode(record.content)
            .getOrThrow()
    }

    private fun MemoryOs.save(
        entries: List<AmperAgentProactiveAttentionAcknowledgement>
    ) {
        require(entries.size <= MAX_ACKNOWLEDGEMENTS)
        if (entries.isEmpty()) {
            forget(RECORD_ID)
            return
        }
        val updatedAt = entries.maxOf { it.acknowledgedAtEpochMs }
        remember(
            MemoryRecord(
                id = RECORD_ID,
                kind = KIND,
                content = AmperAgentProactiveAttentionAcknowledgementCodec.encode(entries),
                importance = 0.72,
                provenance = Provenance(
                    source = "proactive-attention-ui",
                    producer = "agent-proactive-attention-ack-ledger",
                    observedAtEpochMs = updatedAt,
                    confidence = 1.0
                ),
                createdAtEpochMs = updatedAt
            )
        )
    }

    companion object {
        const val MAX_ACKNOWLEDGEMENTS: Int = 64
        const val KIND: String = "agent-proactive-attention-ack-v1"
        internal val RECORD_ID = MemoryId("agent-proactive-attention-ack:index")
    }
}

internal object AmperAgentProactiveAttentionAcknowledgementCodec {
    private const val VERSION = "AMPER_AGENT_PROACTIVE_ATTENTION_ACK_V1"

    fun encode(
        entries: List<AmperAgentProactiveAttentionAcknowledgement>
    ): String = buildString {
        appendLine(VERSION)
        entries
            .sortedWith(
                compareBy<AmperAgentProactiveAttentionAcknowledgement> {
                    it.acknowledgedAtEpochMs
                }.thenBy { it.planId.value }
            )
            .forEach { ack ->
                appendLine(
                    listOf(
                        "ACK",
                        enc(ack.planId.value),
                        ack.revisionSha256,
                        ack.acknowledgedAtEpochMs.toString()
                    ).joinToString("\t")
                )
            }
    }.trimEnd()

    fun decode(
        content: String
    ): Result<List<AmperAgentProactiveAttentionAcknowledgement>> = runCatching {
        val lines = content.lineSequence().filter { it.isNotBlank() }.toList()
        require(lines.firstOrNull() == VERSION) {
            "unsupported proactive attention acknowledgement ledger"
        }
        val entries = lines.drop(1).map { line ->
            val parts = line.split('\t')
            require(parts.size == 4 && parts[0] == "ACK") {
                "malformed proactive attention acknowledgement"
            }
            AmperAgentProactiveAttentionAcknowledgement(
                planId = PlanId(dec(parts[1])),
                revisionSha256 = parts[2],
                acknowledgedAtEpochMs = parts[3].toLong()
            )
        }
        require(
            entries.size <=
                MemoryBackedAmperAgentProactiveAttentionAcknowledgementLedger.MAX_ACKNOWLEDGEMENTS
        )
        require(entries.map { it.planId }.distinct().size == entries.size)
        entries
    }

    private fun enc(value: String): String =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun dec(value: String): String =
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
}
