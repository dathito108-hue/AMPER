package io.amper.neuroos.core

/**
 * Canonical tool capabilities exposed by the production sovereign assistant host.
 *
 * The same set must be used both for model advertisement and the deny-by-default authority grant so
 * the model cannot be told about a capability that the host did not explicitly authorize, and the
 * authority gate cannot silently grant a capability that was not advertised to the model.
 */
object SovereignAssistantToolExposure {
    val capabilities: Set<CapabilityId> = linkedSetOf(
        DeviceStatusToolContract.capability,
        SovereignStatusToolContract.capability,
        SovereignNoteToolContract.capability,
        SovereignNoteSearchToolContract.capability,
        SovereignNoteDeleteToolContract.capability,
        AndroidSettingsOpenToolContract.capability,
        AndroidTimerPrepareToolContract.capability,
        AndroidShareTextToolContract.capability,
        AndroidAppLaunchToolContract.capability,
        AndroidWebSearchToolContract.capability,
        OmegaInternetReadToolContract.capability,
        AndroidClipboardWriteToolContract.capability,
        AndroidFilesBrowseToolContract.capability,
        AndroidContactComposeToolContract.capability,
        AndroidCalendarComposeToolContract.capability,
        AndroidAlarmPrepareToolContract.capability,
        AndroidMediaOpenToolContract.capability,
        AndroidNotificationSettingsToolContract.capability,
        AndroidHomeOpenToolContract.capability
    )
}
