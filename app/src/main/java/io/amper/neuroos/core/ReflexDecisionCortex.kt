package io.amper.neuroos.core

import java.text.Normalizer
import java.util.Locale

enum class ReflexDecisionDisposition {
    ESCALATE_SYSTEM2,
    PROPOSE_ACTION
}

enum class ReflexDecisionSource {
    BOOTSTRAP_DETERMINISTIC,
    NATIVE_SYSTEM1
}

data class ReflexDecisionRequest(
    val userInput: String,
    val descriptors: List<ToolDescriptor>
) {
    init {
        require(userInput.isNotBlank())
        require(userInput.length <= MAX_INPUT_CHARS)
        require(descriptors.distinctBy { it.id }.size == descriptors.size)
    }

    companion object {
        const val MAX_INPUT_CHARS = 1024
    }
}

data class ReflexDecision(
    val disposition: ReflexDecisionDisposition,
    val confidence: Double,
    val uncertainty: Double,
    val source: ReflexDecisionSource,
    val capability: CapabilityId? = null,
    val input: String? = null,
    val reason: String? = null,
    val fastPathConfidenceThreshold: Double = MIN_FAST_PATH_CONFIDENCE,
    val fastPathUncertaintyThreshold: Double = MAX_FAST_PATH_UNCERTAINTY
) {
    init {
        require(confidence in 0.0..1.0)
        require(uncertainty in 0.0..1.0)
        require(fastPathConfidenceThreshold in 0.0..1.0)
        require(fastPathUncertaintyThreshold in 0.0..1.0)
        when (disposition) {
            ReflexDecisionDisposition.ESCALATE_SYSTEM2 -> {
                require(capability == null && input == null && reason == null)
            }
            ReflexDecisionDisposition.PROPOSE_ACTION -> {
                require(capability != null)
                require(input != null)
                require(!reason.isNullOrBlank())
                require(reason.length <= 512)
            }
        }
    }

    val authorityBearing: Boolean
        get() = false

    val fastPathEligible: Boolean
        get() =
            disposition == ReflexDecisionDisposition.PROPOSE_ACTION &&
                confidence >= fastPathConfidenceThreshold &&
                uncertainty <= fastPathUncertaintyThreshold

    fun toActionProposal(): ActionProposal? =
        if (!fastPathEligible) {
            null
        } else {
            ActionProposal(
                capability = requireNotNull(capability),
                reason = requireNotNull(reason),
                input = requireNotNull(input)
            )
        }

    companion object {
        const val MIN_FAST_PATH_CONFIDENCE = 0.99
        const val MAX_FAST_PATH_UNCERTAINTY = 0.01
    }
}

fun interface ReflexDecisionCortex {
    fun decide(request: ReflexDecisionRequest): ReflexDecision
}

/**
 * Bootstrap System-1 decision cortex.
 *
 * This is deliberately deterministic and narrow. It exists so AMPER can use the System-1 execution
 * architecture immediately while Phase396-420 native experience/training infrastructure is prepared
 * to train a learned implementation of [ReflexDecisionCortex]. Unknown or ambiguous input always
 * escalates to System-2. A positive decision is only a typed [ActionProposal]; ToolDescriptor,
 * SovereignActionLoop, AuthorityGate and explicit approval remain authoritative.
 */
object DeterministicReflexDecisionCortex : ReflexDecisionCortex {
    override fun decide(request: ReflexDecisionRequest): ReflexDecision {
        val raw = request.userInput.trim()
        if (raw.length > 512) return escalate()
        val normalized = normalize(raw)
        val descriptors = request.descriptors.associateBy { it.capability }

        matchSettings(normalized, descriptors)?.let { return it }
        matchTimer(normalized, descriptors)?.let { return it }
        matchExplicitShare(raw, descriptors)?.let { return it }
        matchExactAppLaunch(raw, descriptors)?.let { return it }
        matchWebSearch(raw, descriptors)?.let { return it }
        matchClipboardWrite(raw, descriptors)?.let { return it }
        matchFilesBrowse(normalized, descriptors)?.let { return it }
        matchContactCompose(raw, descriptors)?.let { return it }
        matchCalendarCompose(raw, descriptors)?.let { return it }
        matchAlarm(normalized, descriptors)?.let { return it }
        matchMediaOpen(raw, descriptors)?.let { return it }
        matchNotificationSettings(normalized, descriptors)?.let { return it }
        matchHome(normalized, descriptors)?.let { return it }
        matchSovereignStatus(normalized, descriptors)?.let { return it }
        matchDeviceStatus(normalized, descriptors)?.let { return it }

        return escalate()
    }

    private fun matchSettings(
        normalized: String,
        descriptors: Map<CapabilityId, ToolDescriptor>
    ): ReflexDecision? {
        val opensSettings =
            normalized.contains("mo cai dat") ||
                normalized.contains("mo settings") ||
                normalized.contains("open settings") ||
                normalized.contains("open setting")
        if (!opensSettings) return null

        val target = SETTINGS_ALIASES.entries.firstOrNull { (_, aliases) ->
            aliases.any { alias -> containsPhrase(normalized, alias) }
        }?.key ?: return null
        return propose(
            capability = AndroidSettingsOpenToolContract.capability,
            input = target,
            reason = "AMPER Reflex Cortex matched an explicit Android settings request",
            descriptors = descriptors
        )
    }

    private fun matchTimer(
        normalized: String,
        descriptors: Map<CapabilityId, ToolDescriptor>
    ): ReflexDecision? {
        if (
            !normalized.contains("hen gio") &&
            !normalized.contains("timer") &&
            !normalized.contains("countdown")
        ) {
            return null
        }
        val match = TIMER_DURATION.find(normalized) ?: return null
        val amount = match.groupValues[1].toIntOrNull() ?: return null
        val unit = match.groupValues[2]
        val secondsLong = when (unit) {
            "giay", "second", "seconds", "sec", "secs", "s" -> amount.toLong()
            "phut", "minute", "minutes", "min", "mins", "m" -> amount.toLong() * 60L
            "gio", "hour", "hours", "hr", "hrs", "h" -> amount.toLong() * 3600L
            else -> return null
        }
        if (secondsLong !in 1L..AndroidTimerCommandParser.MAX_SECONDS.toLong()) return null
        return propose(
            capability = AndroidTimerPrepareToolContract.capability,
            input = "seconds=$secondsLong",
            reason = "AMPER Reflex Cortex matched an explicit timer request",
            descriptors = descriptors
        )
    }

    private fun matchExplicitShare(
        raw: String,
        descriptors: Map<CapabilityId, ToolDescriptor>
    ): ReflexDecision? {
        val match = SHARE_PREFIX.matchEntire(raw) ?: return null
        val text = match.groupValues[1].trim()
        if (text.isBlank() || text.length > AndroidShareTextToolContract.MAX_TEXT_CHARS) return null
        if ('\n' in text || '\r' in text || '\u0000' in text) return null
        return propose(
            capability = AndroidShareTextToolContract.capability,
            input = text,
            reason = "AMPER Reflex Cortex matched an explicit share request",
            descriptors = descriptors
        )
    }

    private fun matchExactAppLaunch(
        raw: String,
        descriptors: Map<CapabilityId, ToolDescriptor>
    ): ReflexDecision? {
        val match = APP_LAUNCH_PREFIX.matchEntire(raw) ?: return null
        val packageName = runCatching {
            AndroidUniversalActionInput.packageName(match.groupValues[1])
        }.getOrNull() ?: return null
        return propose(
            capability = AndroidAppLaunchToolContract.capability,
            input = packageName,
            reason = "AMPER Reflex Cortex matched an explicit exact-package app launch",
            descriptors = descriptors
        )
    }

    private fun matchWebSearch(
        raw: String,
        descriptors: Map<CapabilityId, ToolDescriptor>
    ): ReflexDecision? {
        val match = WEB_SEARCH_PREFIX.matchEntire(raw) ?: return null
        val query = runCatching {
            AndroidUniversalActionInput.webQuery(match.groupValues[1])
        }.getOrNull() ?: return null
        return propose(
            capability = AndroidWebSearchToolContract.capability,
            input = query,
            reason = "AMPER Reflex Cortex matched an explicit web-search request",
            descriptors = descriptors
        )
    }

    private fun matchClipboardWrite(
        raw: String,
        descriptors: Map<CapabilityId, ToolDescriptor>
    ): ReflexDecision? {
        val match = CLIPBOARD_PREFIX.matchEntire(raw) ?: return null
        val text = runCatching {
            AndroidUniversalActionInput.clipboardText(match.groupValues[1])
        }.getOrNull() ?: return null
        return propose(
            capability = AndroidClipboardWriteToolContract.capability,
            input = text,
            reason = "AMPER Reflex Cortex matched an explicit clipboard-write request",
            descriptors = descriptors
        )
    }

    private fun matchFilesBrowse(
        normalized: String,
        descriptors: Map<CapabilityId, ToolDescriptor>
    ): ReflexDecision? {
        if (normalized !in FILE_BROWSER_PHRASES) return null
        return propose(
            capability = AndroidFilesBrowseToolContract.capability,
            input = AndroidFilesBrowseToolContract.COMMAND,
            reason = "AMPER Reflex Cortex matched an explicit document-browser request",
            descriptors = descriptors
        )
    }

    private fun matchContactCompose(
        raw: String,
        descriptors: Map<CapabilityId, ToolDescriptor>
    ): ReflexDecision? {
        val match = CONTACT_PREFIX.matchEntire(raw) ?: return null
        val input = match.groupValues[1].trim()
        if (runCatching { AndroidUniversalActionInput.contact(input) }.isFailure) return null
        return propose(
            capability = AndroidContactComposeToolContract.capability,
            input = input,
            reason = "AMPER Reflex Cortex matched an explicit typed contact-compose request",
            descriptors = descriptors
        )
    }

    private fun matchCalendarCompose(
        raw: String,
        descriptors: Map<CapabilityId, ToolDescriptor>
    ): ReflexDecision? {
        val match = CALENDAR_PREFIX.matchEntire(raw) ?: return null
        val input = match.groupValues[1].trim()
        if (runCatching { AndroidUniversalActionInput.calendar(input) }.isFailure) return null
        return propose(
            capability = AndroidCalendarComposeToolContract.capability,
            input = input,
            reason = "AMPER Reflex Cortex matched an explicit typed calendar-compose request",
            descriptors = descriptors
        )
    }

    private fun matchAlarm(
        normalized: String,
        descriptors: Map<CapabilityId, ToolDescriptor>
    ): ReflexDecision? {
        if (!ALARM_PREFIX.containsMatchIn(normalized)) return null
        val match = ALARM_TIME.find(normalized) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        val input = "hour=$hour;minute=$minute"
        if (runCatching { AndroidUniversalActionInput.alarm(input) }.isFailure) return null
        return propose(
            capability = AndroidAlarmPrepareToolContract.capability,
            input = input,
            reason = "AMPER Reflex Cortex matched an explicit alarm-time request",
            descriptors = descriptors
        )
    }

    private fun matchMediaOpen(
        raw: String,
        descriptors: Map<CapabilityId, ToolDescriptor>
    ): ReflexDecision? {
        val match = MEDIA_PREFIX.matchEntire(raw) ?: return null
        val url = runCatching {
            AndroidUniversalActionInput.mediaUrl(match.groupValues[1])
        }.getOrNull() ?: return null
        return propose(
            capability = AndroidMediaOpenToolContract.capability,
            input = url,
            reason = "AMPER Reflex Cortex matched an explicit http/https media request",
            descriptors = descriptors
        )
    }

    private fun matchNotificationSettings(
        normalized: String,
        descriptors: Map<CapabilityId, ToolDescriptor>
    ): ReflexDecision? {
        if (normalized !in NOTIFICATION_SETTINGS_PHRASES) return null
        return propose(
            capability = AndroidNotificationSettingsToolContract.capability,
            input = AndroidNotificationSettingsToolContract.COMMAND,
            reason = "AMPER Reflex Cortex matched an explicit notification-settings request",
            descriptors = descriptors
        )
    }

    private fun matchHome(
        normalized: String,
        descriptors: Map<CapabilityId, ToolDescriptor>
    ): ReflexDecision? {
        if (normalized !in HOME_PHRASES) return null
        return propose(
            capability = AndroidHomeOpenToolContract.capability,
            input = AndroidHomeOpenToolContract.COMMAND,
            reason = "AMPER Reflex Cortex matched an explicit home-screen request",
            descriptors = descriptors
        )
    }

    private fun matchSovereignStatus(
        normalized: String,
        descriptors: Map<CapabilityId, ToolDescriptor>
    ): ReflexDecision? {
        if (!containsPhrase(normalized, "amper")) return null
        val input = when {
            containsAny(normalized, "backend", "backends") -> "backends"
            containsAny(normalized, "model", "models", "mo hinh") -> "models"
            containsAny(normalized, "resource", "resources", "tai nguyen") -> "resources"
            containsAny(normalized, "status", "trang thai", "tinh trang") -> "summary"
            else -> return null
        }
        return propose(
            capability = SovereignStatusToolContract.capability,
            input = input,
            reason = "AMPER Reflex Cortex matched an explicit AMPER runtime-status request",
            descriptors = descriptors
        )
    }

    private fun matchDeviceStatus(
        normalized: String,
        descriptors: Map<CapabilityId, ToolDescriptor>
    ): ReflexDecision? {
        val subject = containsAny(
            normalized,
            "battery", "pin", "ram", "memory", "bo nho", "storage", "dung luong",
            "thermal", "nhiet", "cpu", "processor", "device", "thiet bi"
        )
        val asksCurrentState = containsAny(
            normalized,
            "status", "trang thai", "tinh trang", "kiem tra", "xem",
            "bao nhieu", "con bao nhieu", "how much", "current", "right now",
            "hien tai", "con lai"
        )
        if (!subject || !asksCurrentState) return null
        return propose(
            capability = DeviceStatusToolContract.capability,
            input = "summary",
            reason = "AMPER Reflex Cortex matched a current device-status request",
            descriptors = descriptors
        )
    }

    private fun propose(
        capability: CapabilityId,
        input: String,
        reason: String,
        descriptors: Map<CapabilityId, ToolDescriptor>
    ): ReflexDecision? {
        val descriptor = descriptors[capability] ?: return null
        if (!inputAccepted(descriptor, input)) return null
        return ReflexDecision(
            disposition = ReflexDecisionDisposition.PROPOSE_ACTION,
            confidence = 0.995,
            uncertainty = 0.005,
            source = ReflexDecisionSource.BOOTSTRAP_DETERMINISTIC,
            capability = capability,
            input = input,
            reason = reason
        )
    }

    private fun inputAccepted(descriptor: ToolDescriptor, input: String): Boolean {
        val contract = descriptor.inputContract
        if (input.length > contract.maxLength) return false
        if (contract.acceptedValues.isEmpty()) return true
        val accepted = contract.acceptedValues.map { it.trim().lowercase(Locale.ROOT) }.toSet()
        return input.trim().lowercase(Locale.ROOT) in accepted
    }

    private fun escalate(): ReflexDecision = ReflexDecision(
        disposition = ReflexDecisionDisposition.ESCALATE_SYSTEM2,
        confidence = 0.0,
        uncertainty = 1.0,
        source = ReflexDecisionSource.BOOTSTRAP_DETERMINISTIC
    )

    private fun containsAny(value: String, vararg needles: String): Boolean =
        needles.any { containsPhrase(value, it) }

    private fun containsPhrase(value: String, phrase: String): Boolean =
        Regex("(^|\\s)" + Regex.escape(phrase) + "(\\s|$)").containsMatchIn(value)

    private fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .replace('đ', 'd')
            .replace(WHITESPACE, " ")
            .trim()

    private val SETTINGS_ALIASES = linkedMapOf(
        "wifi" to setOf("wifi", "wi fi"),
        "bluetooth" to setOf("bluetooth"),
        "display" to setOf("display", "man hinh"),
        "sound" to setOf("sound", "am thanh"),
        "battery" to setOf("battery", "pin"),
        "accessibility" to setOf("accessibility", "tro nang")
    )
    private val TIMER_DURATION = Regex(
        """\b([0-9]{1,5})\s*(giay|seconds?|secs?|s|phut|minutes?|mins?|m|gio|hours?|hrs?|h)\b"""
    )
    private val SHARE_PREFIX = Regex(
        """^\s*(?:share|chia\s+sẻ|chia\s+se)\s*:\s*(.+?)\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val APP_LAUNCH_PREFIX = Regex(
        """^\s*(?:(?:open|launch)\s+app|(?:mở|mo)\s+(?:ứng\s+dụng|ung\s+dung|app))\s+([A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+)\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val WEB_SEARCH_PREFIX = Regex(
        """^\s*(?:search\s+web(?:\s+for)?|web\s+search(?:\s+for)?|tìm\s+web|tim\s+web|tìm\s+kiếm\s+web|tim\s+kiem\s+web)\s*:?\s+(.+?)\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val CLIPBOARD_PREFIX = Regex(
        """^\s*(?:copy|clipboard|sao\s+chép|sao\s+chep)\s*:\s*(.+?)\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val CONTACT_PREFIX = Regex(
        """^\s*(?:contact|liên\s+hệ|lien\s+he)\s*:\s*(.+?)\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val CALENDAR_PREFIX = Regex(
        """^\s*(?:calendar|lịch|lich)\s*:\s*(.+?)\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val MEDIA_PREFIX = Regex(
        """^\s*(?:media|open\s+media|play\s+media|mở\s+media|mo\s+media)\s*:\s*(https?://\S+)\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val ALARM_PREFIX = Regex("""(?:^|\s)(?:dat\s+bao\s+thuc|set\s+alarm|alarm)(?:\s|$)""")
    private val ALARM_TIME = Regex("""\b([01]?[0-9]|2[0-3]):([0-5][0-9])\b""")
    private val FILE_BROWSER_PHRASES = setOf(
        "open files",
        "open file browser",
        "mo tep",
        "mo file",
        "mo trinh chon tep"
    )
    private val NOTIFICATION_SETTINGS_PHRASES = setOf(
        "open notification settings",
        "mo cai dat thong bao",
        "cai dat thong bao"
    )
    private val HOME_PHRASES = setOf(
        "go home",
        "open home",
        "mo man hinh chinh",
        "ve man hinh chinh"
    )
    private val COMBINING_MARKS = Regex("\\p{M}+")
    private val WHITESPACE = Regex("\\s+")
}

object ReflexDecisionRuntimeContract {
    const val BACKEND_ID = "amper-reflex-local"
    val MODEL_ID = ModelId("amper-reflex-system1")

    fun syntheticActionResponse(decision: ReflexDecision): InferenceResponse {
        val proposal = requireNotNull(decision.toActionProposal())
        val text = buildString {
            appendLine("<AMPER_ACTION_V1>")
            appendLine("capability=" + proposal.capability.value)
            appendLine("reason=" + proposal.reason.replace('\n', ' ').replace('\r', ' '))
            appendLine("input=" + proposal.input.replace('\n', ' ').replace('\r', ' '))
            append("</AMPER_ACTION_V1>")
        }
        return InferenceResponse(
            modelId = MODEL_ID,
            backendId = BACKEND_ID,
            text = text,
            promptTokens = 0,
            outputTokens = 0,
            generationTimeMs = 0L
        )
    }

    fun finalResponse(text: String): InferenceResponse = InferenceResponse(
        modelId = MODEL_ID,
        backendId = BACKEND_ID,
        text = text,
        promptTokens = 0,
        outputTokens = 0,
        generationTimeMs = 0L
    )
}

object ReflexFastResponseRenderer {
    fun render(action: ActionOutcome): String {
        val output = action.output.orEmpty()
        return when (action.proposal?.capability) {
            DeviceStatusToolContract.capability -> renderDeviceStatus(output)
            SovereignStatusToolContract.capability ->
                output.ifBlank { "AMPER runtime status is unavailable." }
            AndroidAppLaunchToolContract.capability ->
                "Android accepted the app-launch request."
            AndroidWebSearchToolContract.capability ->
                "Opened the approved web search."
            AndroidClipboardWriteToolContract.capability ->
                "Copied the approved text to the clipboard."
            AndroidFilesBrowseToolContract.capability ->
                "Opened Android's document picker; no document was selected by AMPER."
            AndroidContactComposeToolContract.capability ->
                "Opened the contact editor; the contact has not been saved by AMPER."
            AndroidCalendarComposeToolContract.capability ->
                "Opened the calendar editor; the event has not been saved by AMPER."
            AndroidAlarmPrepareToolContract.capability ->
                "Opened the alarm UI; AMPER did not skip Android's confirmation UI."
            AndroidMediaOpenToolContract.capability ->
                "Opened the approved media link in an external handler."
            AndroidNotificationSettingsToolContract.capability ->
                "Opened AMPER notification settings; no setting was changed by AMPER."
            AndroidHomeOpenToolContract.capability ->
                "Opened the Android home screen."
            else -> output.ifBlank {
                action.detail ?: "AMPER completed the fast-path action."
            }
        }
    }

    fun renderFailure(action: ActionOutcome): String =
        "AMPER fast path could not complete the request: " +
            (action.detail ?: action.status.name.lowercase(Locale.ROOT))

    private fun renderDeviceStatus(output: String): String {
        val values = output.split(';')
            .mapNotNull { part ->
                val separator = part.indexOf('=')
                if (separator <= 0) null else {
                    part.substring(0, separator) to part.substring(separator + 1)
                }
            }
            .toMap()
        if (values.isEmpty()) return output
        val battery = values["battery_percent"] ?: "unknown"
        val charging = when (values["charging"]) {
            "true" -> "charging"
            "false" -> "not charging"
            else -> "charging state unknown"
        }
        val memory = values["memory_available_mb"] ?: "unknown"
        val free = values["app_storage_free_mb"] ?: "unknown"
        val total = values["app_storage_total_mb"] ?: "unknown"
        val thermal = values["thermal_status"] ?: "unknown"
        val processors = values["processors"] ?: "unknown"
        return "Battery $battery% ($charging); RAM available $memory MB; " +
            "storage free $free/$total MB; thermal=$thermal; CPUs=$processors."
    }
}
