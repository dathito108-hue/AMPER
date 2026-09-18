package io.amper.neuroos.core

/** Canonical capability identifiers used by model-routing policy. */
object TitanCapabilities {
    val REASONING = CapabilityId("reasoning")
    val CODE_GENERATION = CapabilityId("code-generation")
    val PLANNING = CapabilityId("planning")
    val VISION = CapabilityId("vision")
    val AUDIO_UNDERSTANDING = CapabilityId("audio-understanding")
}

/**
 * Explicit capability declaration attached to a user-imported model.
 *
 * Reasoning is the sovereign baseline and cannot be removed. Specialist capabilities are opt-in;
 * they are never inferred from a filename, model size, or arbitrary legacy capability set.
 */
data class ModelCapabilityProfile(
    val codeGeneration: Boolean = false,
    val planning: Boolean = false,
    val vision: Boolean = false,
    val audioUnderstanding: Boolean = false
) {
    val capabilities: Set<CapabilityId>
        get() = buildSet {
            add(TitanCapabilities.REASONING)
            if (codeGeneration) add(TitanCapabilities.CODE_GENERATION)
            if (planning) add(TitanCapabilities.PLANNING)
            if (vision) add(TitanCapabilities.VISION)
            if (audioUnderstanding) add(TitanCapabilities.AUDIO_UNDERSTANDING)
        }

    companion object {
        /**
         * Untyped legacy import callers cannot prove specialist intent, so they retain only the
         * mandatory reasoning baseline. This prevents an old UI/caller from silently labeling every
         * GGUF as a code/planning specialist after the explicit-profile contract is introduced.
         */
        fun fromLegacyUntyped(@Suppress("UNUSED_PARAMETER") capabilities: Set<CapabilityId>): ModelCapabilityProfile =
            ModelCapabilityProfile()
    }
}

/**
 * Structured intent extracted from a user request before specialist routing.
 *
 * This describes routing preference only. It never grants a capability and cannot weaken the
 * request's mandatory baseline.
 */
data class InferenceTaskIntent(
    val codeGeneration: Boolean = false
)

/**
 * Produces specialist capability profiles to try before the caller's mandatory baseline.
 * The policy cannot weaken the baseline; [InferenceRequest] enforces that invariant again.
 */
fun interface InferenceCapabilityPolicy {
    fun preferredProfiles(
        userInput: String,
        baseline: Set<CapabilityId>
    ): List<Set<CapabilityId>>
}

/**
 * Deterministic, local task-intent classifier for auditable on-device routing.
 *
 * Code intent is accepted when the request contains a strong code artifact/error signal, or when
 * an engineering action is paired with a programming-domain signal. Merely mentioning a language
 * or tool is intentionally insufficient, so questions such as "What is Python?" remain on the
 * reasoning baseline. The resulting specialist profile is still preference-only: if no compatible
 * specialist is installed or feasible, [InferenceRequest] retains the reasoning fallback.
 */
object DeterministicInferenceCapabilityPolicy : InferenceCapabilityPolicy {
    private val strongCodeArtifacts = listOf(
        "```",
        "stack trace",
        "compiler error",
        "compile error",
        "build failed",
        "build failure",
        "unresolved reference",
        "syntaxerror",
        "traceback",
        "exception in thread",
        "caused by:",
        "lỗi biên dịch",
        "không biên dịch",
        "build lỗi"
    )

    private val codeSyntaxPatterns = listOf(
        Regex("""\b(fun|def|function)\s+[A-Za-z_][A-Za-z0-9_]*\s*\("""),
        Regex("""\b(class|interface|enum)\s+[A-Za-z_][A-Za-z0-9_]*\s*[:{(]"""),
        Regex("""\b(import|package|using)\s+[A-Za-z_][A-Za-z0-9_.]*""")
    )

    private val codingActionPhrases = listOf(
        "lập trình",
        "gỡ lỗi",
        "sửa lỗi",
        "triển khai code",
        "tích hợp api",
        "what is wrong",
        "what's wrong",
        "why does",
        "why is",
        "sai ở đâu",
        "lỗi gì",
        "tại sao lỗi",
        "vì sao lỗi"
    )

    private val codingActionTokens = setOf(
        "implement",
        "refactor",
        "debug",
        "compile",
        "program",
        "script",
        "patch",
        "migrate",
        "port",
        "integrate",
        "troubleshoot"
    )

    private val genericEngineeringActionTokens = setOf(
        "write",
        "create",
        "build",
        "develop",
        "fix",
        "optimize",
        "modify",
        "update",
        "add",
        "remove",
        "generate",
        "test",
        "review",
        "rewrite",
        "viết",
        "tạo",
        "xây",
        "sửa",
        "tối",
        "thêm",
        "xóa",
        "kiểm",
        "nâng",
        "chỉnh"
    )

    private val programmingDomainPhrases = listOf(
        "source code",
        "mã nguồn",
        "unit test",
        "integration test",
        "api endpoint",
        "http client",
        "database schema",
        "sql query",
        "github actions",
        "jetpack compose",
        "docker compose",
        "regular expression",
        "build.gradle",
        "settings.gradle",
        "package.json"
    )

    private val programmingDomainTokens = setOf(
        "kotlin",
        "java",
        "python",
        "javascript",
        "typescript",
        "rust",
        "golang",
        "swift",
        "c++",
        "c#",
        "cpp",
        "csharp",
        "gradle",
        "maven",
        "npm",
        "pnpm",
        "yarn",
        "docker",
        "dockerfile",
        "sql",
        "sqlite",
        "postgres",
        "postgresql",
        "mysql",
        "android",
        "compose",
        "coroutine",
        "api",
        "endpoint",
        "compiler",
        "parser",
        "code",
        "codebase",
        "bug",
        "exception",
        "traceback",
        "regex",
        "json",
        "xml",
        "yaml",
        "git",
        "github",
        "database",
        "schema"
    )

    private val nonCodingContextPhrases = listOf(
        "what is",
        "what was",
        "history of",
        "tell me about",
        "who created",
        "explain the history",
        "write an essay",
        "write an article",
        "lịch sử",
        "là gì",
        "giới thiệu về",
        "ai tạo ra",
        "nguồn gốc"
    )

    private val tokenPattern = Regex("""[\p{L}\p{N}_+#.-]+""")

    override fun preferredProfiles(
        userInput: String,
        baseline: Set<CapabilityId>
    ): List<Set<CapabilityId>> {
        require(userInput.isNotBlank())
        require(baseline.isNotEmpty())
        return if (classify(userInput).codeGeneration) {
            listOf(baseline + TitanCapabilities.CODE_GENERATION)
        } else {
            emptyList()
        }
    }

    fun classify(userInput: String): InferenceTaskIntent {
        require(userInput.isNotBlank())
        val normalized = userInput.lowercase()
        if (strongCodeArtifacts.any(normalized::contains)) {
            return InferenceTaskIntent(codeGeneration = true)
        }
        if (codeSyntaxPatterns.any { it.containsMatchIn(userInput) }) {
            return InferenceTaskIntent(codeGeneration = true)
        }

        val tokens = tokenPattern.findAll(normalized).map { it.value }.toSet()
        val hasDomain = programmingDomainPhrases.any(normalized::contains) ||
            tokens.any(programmingDomainTokens::contains)
        if (!hasDomain) return InferenceTaskIntent()

        val hasCodingAction = codingActionPhrases.any(normalized::contains) ||
            tokens.any(codingActionTokens::contains)
        if (hasCodingAction) return InferenceTaskIntent(codeGeneration = true)

        val hasGenericEngineeringAction = tokens.any(genericEngineeringActionTokens::contains)
        val nonCodingContext = nonCodingContextPhrases.any(normalized::contains)
        return InferenceTaskIntent(
            codeGeneration = hasGenericEngineeringAction && !nonCodingContext
        )
    }

    fun planningProfile(baseline: Set<CapabilityId>): List<Set<CapabilityId>> {
        require(baseline.isNotEmpty())
        return listOf(baseline + TitanCapabilities.PLANNING)
    }
}

/**
 * Phase 120 bounded cross-turn specialist continuity.
 *
 * A completed turn's actual specialist capability profile may be reused only when the next user
 * input is an explicit short continuation. This is preference-only and recognizes only canonical
 * specialist capabilities. Explicit current-turn intent is resolved by the caller first and wins.
 * Unrelated/new-topic inputs therefore cannot inherit a specialist merely because one was used
 * earlier in the conversation.
 */
object ConversationSpecialistContinuityPolicy {
    private val continuationPhrases = listOf(
        "tiếp tục",
        "tiếp theo",
        "làm tiếp",
        "sửa tiếp",
        "chỉnh tiếp",
        "hoàn thiện tiếp",
        "bổ sung tiếp",
        "đoạn đó",
        "phần đó",
        "từ đó",
        "continue",
        "keep going",
        "keep working",
        "continue from there",
        "fix that",
        "fix it",
        "update that",
        "update it",
        "refactor that",
        "refactor it",
        "finish that",
        "finish it",
        "do the same"
    )

    private val explicitTopicResetPhrases = listOf(
        "chủ đề mới",
        "new topic",
        "what is",
        "what was",
        "history of",
        "tell me about",
        "lịch sử",
        "là gì",
        "giới thiệu về",
        "nguồn gốc"
    )

    private val canonicalSpecialists = setOf(
        TitanCapabilities.CODE_GENERATION,
        TitanCapabilities.PLANNING
    )

    fun preferredProfiles(
        userInput: String,
        previousSelectedCapabilities: Set<CapabilityId>,
        baseline: Set<CapabilityId>
    ): List<Set<CapabilityId>> {
        require(userInput.isNotBlank())
        require(baseline.isNotEmpty())
        if (!isContinuation(userInput)) return emptyList()
        val specialists = previousSelectedCapabilities.intersect(canonicalSpecialists)
        return if (specialists.isEmpty()) emptyList() else listOf(baseline + specialists)
    }

    fun isContinuation(userInput: String): Boolean {
        require(userInput.isNotBlank())
        val normalized = userInput.trim().lowercase()
        if (normalized.length > 240) return false
        if (explicitTopicResetPhrases.any(normalized::contains)) return false
        return continuationPhrases.any(normalized::contains)
    }
}
