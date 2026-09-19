package io.amper.neuroos.core

import java.net.URI

data class AndroidContactComposeCommand(
    val name: String?,
    val phone: String?,
    val email: String?
)

data class AndroidCalendarComposeCommand(
    val title: String,
    val startEpochMs: Long,
    val durationMinutes: Int,
    val location: String?
)

data class AndroidAlarmPrepareCommand(
    val hour: Int,
    val minute: Int,
    val label: String?
)

/**
 * Narrow Android platform boundary for Phase481-485.
 *
 * There is intentionally no generic "execute intent" entry point. Every operation has a typed
 * capability, a bounded parser and a dedicated launcher method so AMPER's canonical ToolDescriptor
 * -> AuthorityGate -> explicit approval -> audited execution path remains authoritative.
 */
interface AndroidUniversalActionLauncher {
    fun launchApp(packageName: String): Result<Unit>
    fun searchWeb(query: String): Result<Unit>
    fun writeClipboard(text: String): Result<Unit>
    fun browseFiles(): Result<Unit>
    fun composeContact(command: AndroidContactComposeCommand): Result<Unit>
    fun composeCalendarEvent(command: AndroidCalendarComposeCommand): Result<Unit>
    fun prepareAlarm(command: AndroidAlarmPrepareCommand): Result<Unit>
    fun openMedia(url: String): Result<Unit>
    fun openNotificationSettings(): Result<Unit>
    fun openHome(): Result<Unit>
}

internal object AndroidUniversalActionInput {
    private val packageRegex = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
    private val phoneRegex = Regex("[0-9+() .-]{3,40}")

    fun singleLine(
        input: String,
        name: String,
        maxChars: Int,
        allowBlank: Boolean = false
    ): String {
        require(input.length <= maxChars) { "$name exceeds $maxChars characters" }
        require('\n' !in input && '\r' !in input && '\u0000' !in input) {
            "$name must be one valid line"
        }
        val value = input.trim()
        require(allowBlank || value.isNotBlank()) { "$name cannot be blank" }
        return value
    }

    fun packageName(input: String): String {
        val value = singleLine(input, "package name", AndroidAppLaunchToolContract.MAX_PACKAGE_CHARS)
        require(packageRegex.matches(value)) { "invalid Android package name" }
        return value
    }

    fun webQuery(input: String): String =
        singleLine(input, "web search query", AndroidWebSearchToolContract.MAX_QUERY_CHARS)

    fun clipboardText(input: String): String =
        singleLine(input, "clipboard text", AndroidClipboardWriteToolContract.MAX_TEXT_CHARS)

    fun mediaUrl(input: String): String {
        val value = singleLine(input, "media URL", AndroidMediaOpenToolContract.MAX_URL_CHARS)
        val uri = runCatching { URI(value) }.getOrElse { error("invalid media URL") }
        require(uri.scheme == "https" || uri.scheme == "http") {
            "media URL must use http or https"
        }
        require(!uri.host.isNullOrBlank()) { "media URL requires a host" }
        return value
    }

    fun contact(input: String): AndroidContactComposeCommand {
        val fields = fields(
            input = input,
            name = "contact compose input",
            maxChars = AndroidContactComposeToolContract.MAX_INPUT_CHARS,
            allowedKeys = setOf("name", "phone", "email")
        )
        val name = fields["name"]?.takeIf { it.isNotBlank() }?.also {
            require(it.length <= 80) { "contact name exceeds 80 characters" }
        }
        val phone = fields["phone"]?.takeIf { it.isNotBlank() }?.also {
            require(phoneRegex.matches(it)) { "contact phone is invalid" }
        }
        val email = fields["email"]?.takeIf { it.isNotBlank() }?.also {
            require(it.length <= 128 && '@' in it && ' ' !in it) { "contact email is invalid" }
        }
        require(name != null || phone != null || email != null) {
            "contact compose requires at least one non-empty field"
        }
        return AndroidContactComposeCommand(name = name, phone = phone, email = email)
    }

    fun calendar(input: String): AndroidCalendarComposeCommand {
        val fields = fields(
            input = input,
            name = "calendar compose input",
            maxChars = AndroidCalendarComposeToolContract.MAX_INPUT_CHARS,
            allowedKeys = setOf("title", "start_epoch_ms", "duration_minutes", "location")
        )
        val title = fields["title"]?.takeIf { it.isNotBlank() }
            ?: error("calendar compose requires title")
        require(title.length <= 120) { "calendar title exceeds 120 characters" }

        val start = fields["start_epoch_ms"]?.toLongOrNull()
            ?: error("calendar compose requires numeric start_epoch_ms")
        require(start in 946_684_800_000L..4_102_444_800_000L) {
            "calendar start_epoch_ms must be between years 2000 and 2100"
        }

        val duration = fields["duration_minutes"]?.toIntOrNull()
            ?: error("calendar compose requires numeric duration_minutes")
        require(duration in 1..10_080) {
            "calendar duration_minutes must be from 1 to 10080"
        }

        val location = fields["location"]?.takeIf { it.isNotBlank() }?.also {
            require(it.length <= 120) { "calendar location exceeds 120 characters" }
        }
        return AndroidCalendarComposeCommand(
            title = title,
            startEpochMs = start,
            durationMinutes = duration,
            location = location
        )
    }

    fun alarm(input: String): AndroidAlarmPrepareCommand {
        val fields = fields(
            input = input,
            name = "alarm prepare input",
            maxChars = AndroidAlarmPrepareToolContract.MAX_INPUT_CHARS,
            allowedKeys = setOf("hour", "minute", "label")
        )
        val hour = fields["hour"]?.toIntOrNull() ?: error("alarm prepare requires numeric hour")
        val minute = fields["minute"]?.toIntOrNull() ?: error("alarm prepare requires numeric minute")
        require(hour in 0..23) { "alarm hour must be from 0 to 23" }
        require(minute in 0..59) { "alarm minute must be from 0 to 59" }
        val label = fields["label"]?.takeIf { it.isNotBlank() }?.also {
            require(it.length <= 80) { "alarm label exceeds 80 characters" }
        }
        return AndroidAlarmPrepareCommand(hour, minute, label)
    }

    private fun fields(
        input: String,
        name: String,
        maxChars: Int,
        allowedKeys: Set<String>
    ): Map<String, String> {
        val value = singleLine(input, name, maxChars)
        val fields = linkedMapOf<String, String>()
        value.split(';').forEach { segment ->
            val separator = segment.indexOf('=')
            require(separator > 0) { "$name field must use key=value" }
            val key = segment.substring(0, separator).trim().lowercase()
            val fieldValue = segment.substring(separator + 1).trim()
            require(key in allowedKeys) { "unknown $name field: $key" }
            require(fields.put(key, fieldValue) == null) { "duplicate $name field: $key" }
        }
        return fields
    }
}

object AndroidAppLaunchToolContract {
    val capability = CapabilityId("android.app.launch")
    val toolId = ToolId("android-app-launcher")
    const val MAX_PACKAGE_CHARS = 200
}

object AndroidWebSearchToolContract {
    val capability = CapabilityId("android.web.search")
    val toolId = ToolId("android-web-search")
    const val MAX_QUERY_CHARS = 512
}

object AndroidClipboardWriteToolContract {
    val capability = CapabilityId("android.clipboard.write")
    val toolId = ToolId("android-clipboard-writer")
    const val MAX_TEXT_CHARS = 2048
}

object AndroidFilesBrowseToolContract {
    val capability = CapabilityId("android.files.browse")
    val toolId = ToolId("android-files-browser")
    const val COMMAND = "documents"
}

object AndroidContactComposeToolContract {
    val capability = CapabilityId("android.contact.compose")
    val toolId = ToolId("android-contact-composer")
    const val MAX_INPUT_CHARS = 512
}

object AndroidCalendarComposeToolContract {
    val capability = CapabilityId("android.calendar.compose")
    val toolId = ToolId("android-calendar-composer")
    const val MAX_INPUT_CHARS = 512
}

object AndroidAlarmPrepareToolContract {
    val capability = CapabilityId("android.alarm.prepare")
    val toolId = ToolId("android-alarm-preparer")
    const val MAX_INPUT_CHARS = 192
}

object AndroidMediaOpenToolContract {
    val capability = CapabilityId("android.media.open")
    val toolId = ToolId("android-media-opener")
    const val MAX_URL_CHARS = 1024
}

object AndroidNotificationSettingsToolContract {
    val capability = CapabilityId("android.notifications.settings")
    val toolId = ToolId("android-notification-settings")
    const val COMMAND = "app"
}

object AndroidHomeOpenToolContract {
    val capability = CapabilityId("android.screen.home")
    val toolId = ToolId("android-home-opener")
    const val COMMAND = "home"
}

class AndroidAppLaunchToolProvider(
    private val launcher: AndroidUniversalActionLauncher
) : ToolProvider {
    override val descriptor = ToolDescriptor(
        id = AndroidAppLaunchToolContract.toolId,
        name = "Android app launcher",
        capability = AndroidAppLaunchToolContract.capability,
        sideEffect = ToolSideEffect.EXTERNAL,
        inputContract = ToolInputContract(
            description = "Open one exact Android package after approval; input is package.name",
            maxLength = AndroidAppLaunchToolContract.MAX_PACKAGE_CHARS
        )
    )

    override fun execute(input: String): Result<String> = runCatching {
        val packageName = AndroidUniversalActionInput.packageName(input)
        launcher.launchApp(packageName).getOrThrow()
        "app_launch_requested;package=$packageName"
    }
}

class AndroidWebSearchToolProvider(
    private val launcher: AndroidUniversalActionLauncher
) : ToolProvider {
    override val descriptor = ToolDescriptor(
        id = AndroidWebSearchToolContract.toolId,
        name = "Android web search",
        capability = AndroidWebSearchToolContract.capability,
        sideEffect = ToolSideEffect.EXTERNAL,
        inputContract = ToolInputContract(
            description = "Open the system web-search UI for one approved query",
            maxLength = AndroidWebSearchToolContract.MAX_QUERY_CHARS
        )
    )

    override fun execute(input: String): Result<String> = runCatching {
        val query = AndroidUniversalActionInput.webQuery(input)
        launcher.searchWeb(query).getOrThrow()
        "web_search_opened;query_chars=${query.length}"
    }
}

class AndroidClipboardWriteToolProvider(
    private val launcher: AndroidUniversalActionLauncher
) : ToolProvider {
    override val descriptor = ToolDescriptor(
        id = AndroidClipboardWriteToolContract.toolId,
        name = "Android clipboard writer",
        capability = AndroidClipboardWriteToolContract.capability,
        sideEffect = ToolSideEffect.LOCAL_STATE,
        inputContract = ToolInputContract(
            description = "Write one approved single-line text value to the Android clipboard",
            maxLength = AndroidClipboardWriteToolContract.MAX_TEXT_CHARS
        )
    )

    override fun execute(input: String): Result<String> = runCatching {
        val text = AndroidUniversalActionInput.clipboardText(input)
        launcher.writeClipboard(text).getOrThrow()
        "clipboard_written;chars=${text.length}"
    }
}

class AndroidFilesBrowseToolProvider(
    private val launcher: AndroidUniversalActionLauncher
) : ToolProvider {
    override val descriptor = ToolDescriptor(
        id = AndroidFilesBrowseToolContract.toolId,
        name = "Android document browser",
        capability = AndroidFilesBrowseToolContract.capability,
        sideEffect = ToolSideEffect.EXTERNAL,
        inputContract = ToolInputContract(
            description = "Open Android's document picker; selection remains user-controlled",
            acceptedValues = setOf(AndroidFilesBrowseToolContract.COMMAND),
            maxLength = 16
        )
    )

    override fun execute(input: String): Result<String> = runCatching {
        require(input.trim().lowercase() == AndroidFilesBrowseToolContract.COMMAND) {
            "files browse input must be ${AndroidFilesBrowseToolContract.COMMAND}"
        }
        launcher.browseFiles().getOrThrow()
        "document_picker_opened;selection_performed=false"
    }
}

class AndroidContactComposeToolProvider(
    private val launcher: AndroidUniversalActionLauncher
) : ToolProvider {
    override val descriptor = ToolDescriptor(
        id = AndroidContactComposeToolContract.toolId,
        name = "Android contact composer",
        capability = AndroidContactComposeToolContract.capability,
        sideEffect = ToolSideEffect.EXTERNAL,
        inputContract = ToolInputContract(
            description = "Open contact insert UI with name=<text>;phone=<number>;email=<address>; saving remains user-controlled",
            maxLength = AndroidContactComposeToolContract.MAX_INPUT_CHARS
        )
    )

    override fun execute(input: String): Result<String> = runCatching {
        val command = AndroidUniversalActionInput.contact(input)
        launcher.composeContact(command).getOrThrow()
        "contact_ui_opened;has_name=${command.name != null};has_phone=${command.phone != null};has_email=${command.email != null};saved=false"
    }
}

class AndroidCalendarComposeToolProvider(
    private val launcher: AndroidUniversalActionLauncher
) : ToolProvider {
    override val descriptor = ToolDescriptor(
        id = AndroidCalendarComposeToolContract.toolId,
        name = "Android calendar event composer",
        capability = AndroidCalendarComposeToolContract.capability,
        sideEffect = ToolSideEffect.EXTERNAL,
        inputContract = ToolInputContract(
            description = "Open calendar insert UI with title=<text>;start_epoch_ms=<ms>;duration_minutes=<1..10080>[;location=<text>]; saving remains user-controlled",
            maxLength = AndroidCalendarComposeToolContract.MAX_INPUT_CHARS
        )
    )

    override fun execute(input: String): Result<String> = runCatching {
        val command = AndroidUniversalActionInput.calendar(input)
        launcher.composeCalendarEvent(command).getOrThrow()
        "calendar_ui_opened;title_chars=${command.title.length};duration_minutes=${command.durationMinutes};saved=false"
    }
}

class AndroidAlarmPrepareToolProvider(
    private val launcher: AndroidUniversalActionLauncher
) : ToolProvider {
    override val descriptor = ToolDescriptor(
        id = AndroidAlarmPrepareToolContract.toolId,
        name = "Android alarm preparer",
        capability = AndroidAlarmPrepareToolContract.capability,
        sideEffect = ToolSideEffect.EXTERNAL,
        inputContract = ToolInputContract(
            description = "Open alarm UI with hour=<0..23>;minute=<0..59>[;label=<text>]; skip_ui is always false",
            maxLength = AndroidAlarmPrepareToolContract.MAX_INPUT_CHARS
        )
    )

    override fun execute(input: String): Result<String> = runCatching {
        val command = AndroidUniversalActionInput.alarm(input)
        launcher.prepareAlarm(command).getOrThrow()
        "alarm_ui_opened;hour=${command.hour};minute=${command.minute};label_chars=${command.label?.length ?: 0};skip_ui=false"
    }
}

class AndroidMediaOpenToolProvider(
    private val launcher: AndroidUniversalActionLauncher
) : ToolProvider {
    override val descriptor = ToolDescriptor(
        id = AndroidMediaOpenToolContract.toolId,
        name = "Android media URL opener",
        capability = AndroidMediaOpenToolContract.capability,
        sideEffect = ToolSideEffect.EXTERNAL,
        inputContract = ToolInputContract(
            description = "Open one approved http/https media URL in an external handler",
            maxLength = AndroidMediaOpenToolContract.MAX_URL_CHARS
        )
    )

    override fun execute(input: String): Result<String> = runCatching {
        val url = AndroidUniversalActionInput.mediaUrl(input)
        launcher.openMedia(url).getOrThrow()
        val uri = URI(url)
        "media_opened;scheme=${uri.scheme};host=${uri.host}"
    }
}

class AndroidNotificationSettingsToolProvider(
    private val launcher: AndroidUniversalActionLauncher
) : ToolProvider {
    override val descriptor = ToolDescriptor(
        id = AndroidNotificationSettingsToolContract.toolId,
        name = "AMPER notification settings opener",
        capability = AndroidNotificationSettingsToolContract.capability,
        sideEffect = ToolSideEffect.EXTERNAL,
        inputContract = ToolInputContract(
            description = "Open AMPER's Android notification settings; changes remain user-controlled",
            acceptedValues = setOf(AndroidNotificationSettingsToolContract.COMMAND),
            maxLength = 8
        )
    )

    override fun execute(input: String): Result<String> = runCatching {
        require(input.trim().lowercase() == AndroidNotificationSettingsToolContract.COMMAND) {
            "notification settings input must be ${AndroidNotificationSettingsToolContract.COMMAND}"
        }
        launcher.openNotificationSettings().getOrThrow()
        "notification_settings_opened;changed=false"
    }
}

class AndroidHomeOpenToolProvider(
    private val launcher: AndroidUniversalActionLauncher
) : ToolProvider {
    override val descriptor = ToolDescriptor(
        id = AndroidHomeOpenToolContract.toolId,
        name = "Android home screen opener",
        capability = AndroidHomeOpenToolContract.capability,
        sideEffect = ToolSideEffect.EXTERNAL,
        inputContract = ToolInputContract(
            description = "Open the Android home screen after approval",
            acceptedValues = setOf(AndroidHomeOpenToolContract.COMMAND),
            maxLength = 8
        )
    )

    override fun execute(input: String): Result<String> = runCatching {
        require(input.trim().lowercase() == AndroidHomeOpenToolContract.COMMAND) {
            "home input must be ${AndroidHomeOpenToolContract.COMMAND}"
        }
        launcher.openHome().getOrThrow()
        "home_screen_opened"
    }
}
