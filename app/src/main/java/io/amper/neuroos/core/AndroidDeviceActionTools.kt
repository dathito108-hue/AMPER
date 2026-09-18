package io.amper.neuroos.core

enum class AndroidSettingsTarget(val command: String) {
    WIFI("wifi"),
    BLUETOOTH("bluetooth"),
    DISPLAY("display"),
    SOUND("sound"),
    BATTERY("battery"),
    ACCESSIBILITY("accessibility");

    companion object {
        fun parse(input: String): AndroidSettingsTarget {
            val normalized = input.trim().lowercase()
            return entries.firstOrNull { it.command == normalized }
                ?: error("unsupported Android settings target: $normalized")
        }

        val commands: Set<String> = entries.mapTo(linkedSetOf()) { it.command }
    }
}

data class AndroidTimerCommand(
    val seconds: Int,
    val label: String?
)

object AndroidTimerCommandParser {
    const val MAX_SECONDS = 86_400
    const val MAX_LABEL_CHARS = 80

    fun parse(input: String): AndroidTimerCommand {
        require(input.isNotBlank()) {
            "android.timer.prepare requires seconds=<1..$MAX_SECONDS>[;label=<text>]"
        }
        require('\n' !in input && '\r' !in input && '\u0000' !in input) {
            "timer input must be a valid single line"
        }

        val fields = linkedMapOf<String, String>()
        input.split(';').forEach { segment ->
            val separator = segment.indexOf('=')
            require(separator > 0) { "timer field must use key=value" }
            val key = segment.substring(0, separator).trim().lowercase()
            val value = segment.substring(separator + 1).trim()
            require(key in setOf("seconds", "label")) { "unknown timer field: $key" }
            require(fields.put(key, value) == null) { "duplicate timer field: $key" }
        }

        val rawSeconds = fields["seconds"]
            ?: error("timer input requires seconds")
        require(rawSeconds.matches(Regex("[0-9]{1,5}"))) {
            "timer seconds must be an integer from 1 to $MAX_SECONDS"
        }
        val seconds = rawSeconds.toInt()
        require(seconds in 1..MAX_SECONDS) {
            "timer seconds must be from 1 to $MAX_SECONDS"
        }

        val label = fields["label"]
            ?.takeIf { it.isNotBlank() }
            ?.also {
                require(it.length <= MAX_LABEL_CHARS) {
                    "timer label exceeds $MAX_LABEL_CHARS characters"
                }
            }

        return AndroidTimerCommand(seconds, label)
    }
}

/**
 * Platform boundary for explicit Android UI actions.
 *
 * Core tool providers never hold Context or Intent and can therefore be tested without Android.
 */
interface AndroidDeviceActionLauncher {
    fun openSettings(target: AndroidSettingsTarget): Result<Unit>
    fun openTimer(command: AndroidTimerCommand): Result<Unit>
    fun openShareSheet(text: String): Result<Unit>
}

object AndroidSettingsOpenToolContract {
    val capability = CapabilityId("android.settings.open")
    val toolId = ToolId("android-settings-opener")
}

object AndroidTimerPrepareToolContract {
    val capability = CapabilityId("android.timer.prepare")
    val toolId = ToolId("android-timer-preparer")
    const val MAX_INPUT_CHARS = 160
}

object AndroidShareTextToolContract {
    val capability = CapabilityId("android.share.text")
    val toolId = ToolId("android-share-text")
    const val MAX_TEXT_CHARS = 1024
}

/**
 * Opens only one of a small, declared system Settings screens.
 * This is EXTERNAL because another Android activity becomes active.
 */
class AndroidSettingsOpenToolProvider(
    private val launcher: AndroidDeviceActionLauncher
) : ToolProvider {
    override val descriptor = ToolDescriptor(
        id = AndroidSettingsOpenToolContract.toolId,
        name = "Android settings opener",
        capability = AndroidSettingsOpenToolContract.capability,
        sideEffect = ToolSideEffect.EXTERNAL,
        inputContract = ToolInputContract(
            description =
                "Open one approved Android Settings screen after explicit approval",
            acceptedValues = AndroidSettingsTarget.commands,
            maxLength = 32
        )
    )

    override fun execute(input: String): Result<String> = runCatching {
        val target = AndroidSettingsTarget.parse(input)
        launcher.openSettings(target).getOrThrow()
        "settings_screen_opened;target=${target.command}"
    }
}

/**
 * Prepares a system timer, but deliberately keeps Android's timer UI visible.
 * AMPER never sets EXTRA_SKIP_UI=true and therefore does not silently create the timer.
 */
class AndroidTimerPrepareToolProvider(
    private val launcher: AndroidDeviceActionLauncher
) : ToolProvider {
    override val descriptor = ToolDescriptor(
        id = AndroidTimerPrepareToolContract.toolId,
        name = "Android timer preparer",
        capability = AndroidTimerPrepareToolContract.capability,
        sideEffect = ToolSideEffect.EXTERNAL,
        inputContract = ToolInputContract(
            description =
                "Open Android timer UI prefilled from seconds=<1..86400>[;label=<text>]; Android UI remains visible",
            maxLength = AndroidTimerPrepareToolContract.MAX_INPUT_CHARS
        )
    )

    override fun execute(input: String): Result<String> = runCatching {
        val command = AndroidTimerCommandParser.parse(input)
        launcher.openTimer(command).getOrThrow()
        buildString {
            append("timer_ui_opened;seconds=").append(command.seconds)
            append(";label_chars=").append(command.label?.length ?: 0)
            append(";skip_ui=false")
        }
    }
}

/**
 * Opens the Android chooser/share sheet with text. The tool never selects a recipient or presses Send.
 */
class AndroidShareTextToolProvider(
    private val launcher: AndroidDeviceActionLauncher
) : ToolProvider {
    override val descriptor = ToolDescriptor(
        id = AndroidShareTextToolContract.toolId,
        name = "Android text share sheet",
        capability = AndroidShareTextToolContract.capability,
        sideEffect = ToolSideEffect.EXTERNAL,
        inputContract = ToolInputContract(
            description =
                "Open Android share sheet with one user-approved single-line text; does not choose recipient or send",
            maxLength = AndroidShareTextToolContract.MAX_TEXT_CHARS
        )
    )

    override fun execute(input: String): Result<String> = runCatching {
        val text = input.trim()
        require(text.isNotBlank()) { "share text cannot be blank" }
        require(text.length <= AndroidShareTextToolContract.MAX_TEXT_CHARS) {
            "share text exceeds ${AndroidShareTextToolContract.MAX_TEXT_CHARS} characters"
        }
        require('\n' !in text && '\r' !in text && '\u0000' !in text) {
            "share text must be one valid line"
        }

        launcher.openShareSheet(text).getOrThrow()
        "share_sheet_opened;chars=${text.length};sent=false"
    }
}
