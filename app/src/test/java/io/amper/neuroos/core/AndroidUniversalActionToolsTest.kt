package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidUniversalActionToolsTest {
    private class RecordingLauncher : AndroidUniversalActionLauncher {
        val apps = mutableListOf<String>()
        val searches = mutableListOf<String>()
        val clipboard = mutableListOf<String>()
        var fileBrowses = 0
        val contacts = mutableListOf<AndroidContactComposeCommand>()
        val events = mutableListOf<AndroidCalendarComposeCommand>()
        val alarms = mutableListOf<AndroidAlarmPrepareCommand>()
        val media = mutableListOf<String>()
        var notificationSettings = 0
        var homeOpens = 0

        override fun launchApp(packageName: String): Result<Unit> = runCatching {
            apps += packageName
        }

        override fun searchWeb(query: String): Result<Unit> = runCatching {
            searches += query
        }

        override fun writeClipboard(text: String): Result<Unit> = runCatching {
            clipboard += text
        }

        override fun browseFiles(): Result<Unit> = runCatching {
            fileBrowses += 1
        }

        override fun composeContact(command: AndroidContactComposeCommand): Result<Unit> = runCatching {
            contacts += command
        }

        override fun composeCalendarEvent(command: AndroidCalendarComposeCommand): Result<Unit> = runCatching {
            events += command
        }

        override fun prepareAlarm(command: AndroidAlarmPrepareCommand): Result<Unit> = runCatching {
            alarms += command
        }

        override fun openMedia(url: String): Result<Unit> = runCatching {
            media += url
        }

        override fun openNotificationSettings(): Result<Unit> = runCatching {
            notificationSettings += 1
        }

        override fun openHome(): Result<Unit> = runCatching {
            homeOpens += 1
        }
    }

    private fun providers(launcher: RecordingLauncher): List<ToolProvider> = listOf(
        AndroidAppLaunchToolProvider(launcher),
        AndroidWebSearchToolProvider(launcher),
        AndroidClipboardWriteToolProvider(launcher),
        AndroidFilesBrowseToolProvider(launcher),
        AndroidContactComposeToolProvider(launcher),
        AndroidCalendarComposeToolProvider(launcher),
        AndroidAlarmPrepareToolProvider(launcher),
        AndroidMediaOpenToolProvider(launcher),
        AndroidNotificationSettingsToolProvider(launcher),
        AndroidHomeOpenToolProvider(launcher)
    )

    @Test
    fun universalActionsStayBehindCanonicalApprovalAndBoundExecution() {
        val launcher = RecordingLauncher()
        val provider = AndroidAppLaunchToolProvider(launcher)
        val registry = InMemoryToolRegistry().also { it.register(provider) }
        val audit = InMemoryToolAuditLog()
        val loop = SovereignActionLoop(
            registry = registry,
            fabric = AuditedToolFabric(
                gate = DenyByDefaultAuthorityGate(setOf(provider.descriptor.capability)),
                registry = registry,
                audit = audit
            ),
            memory = InMemoryMemoryOs(),
            workspace = InMemoryWorkspace()
        )
        val proposal = ActionProposal(
            capability = AndroidAppLaunchToolContract.capability,
            reason = "User asked to open an app",
            input = "com.example.app"
        )

        val pending = loop.evaluate(proposal)
        assertEquals(ActionStatus.REQUIRES_CONFIRMATION, pending.status)
        assertTrue(launcher.apps.isEmpty())
        assertTrue(audit.snapshot().isEmpty())

        val executed = loop.approveBound(
            proposal = proposal,
            expectedToolId = AndroidAppLaunchToolContract.toolId,
            expectedSideEffect = ToolSideEffect.EXTERNAL
        )

        assertEquals(ActionStatus.EXECUTED, executed.status)
        assertEquals(listOf("com.example.app"), launcher.apps)
        assertEquals(1, audit.snapshot().size)
        assertTrue(audit.snapshot().single().authorized)
    }

    @Test
    fun everyNewCapabilityHasExplicitSideEffectClassAndNoGenericIntentExecutor() {
        val launcher = RecordingLauncher()
        val providers = providers(launcher)
        val byCapability = providers.associateBy { it.descriptor.capability.value }

        assertEquals(ToolSideEffect.LOCAL_STATE, byCapability.getValue("android.clipboard.write").descriptor.sideEffect)
        providers
            .filterNot { it.descriptor.capability == AndroidClipboardWriteToolContract.capability }
            .forEach { assertEquals(ToolSideEffect.EXTERNAL, it.descriptor.sideEffect) }

        assertFalse(byCapability.containsKey("android.intent.execute"))
        assertFalse(byCapability.containsKey("android.execute_anything"))
    }

    @Test
    fun typedInputsAreBoundedBeforePlatformLaunch() {
        val launcher = RecordingLauncher()

        assertTrue(AndroidAppLaunchToolProvider(launcher).execute("not a package").isFailure)
        assertTrue(AndroidMediaOpenToolProvider(launcher).execute("file:///sdcard/secret").isFailure)
        assertTrue(AndroidAlarmPrepareToolProvider(launcher).execute("hour=24;minute=0").isFailure)
        assertTrue(
            AndroidCalendarComposeToolProvider(launcher)
                .execute("title=Meeting;start_epoch_ms=bad;duration_minutes=30")
                .isFailure
        )
        assertTrue(
            AndroidContactComposeToolProvider(launcher)
                .execute("name=;phone=;email=")
                .isFailure
        )

        assertTrue(launcher.apps.isEmpty())
        assertTrue(launcher.media.isEmpty())
        assertTrue(launcher.alarms.isEmpty())
        assertTrue(launcher.events.isEmpty())
        assertTrue(launcher.contacts.isEmpty())
    }

    @Test
    fun uiMediatedProvidersReportPreparationInsteadOfClaimingCompletion() {
        val launcher = RecordingLauncher()

        val contact = AndroidContactComposeToolProvider(launcher)
            .execute("name=Ada;phone=+1 555 0100;email=ada@example.com")
            .getOrThrow()
        val event = AndroidCalendarComposeToolProvider(launcher)
            .execute("title=Review;start_epoch_ms=1789830000000;duration_minutes=45;location=Office")
            .getOrThrow()
        val alarm = AndroidAlarmPrepareToolProvider(launcher)
            .execute("hour=7;minute=30;label=Wake")
            .getOrThrow()
        val files = AndroidFilesBrowseToolProvider(launcher).execute("documents").getOrThrow()
        val notifications = AndroidNotificationSettingsToolProvider(launcher).execute("app").getOrThrow()

        assertTrue(contact.contains("saved=false"))
        assertTrue(event.contains("saved=false"))
        assertTrue(alarm.contains("skip_ui=false"))
        assertTrue(files.contains("selection_performed=false"))
        assertTrue(notifications.contains("changed=false"))

        assertEquals(1, launcher.contacts.size)
        assertEquals(1, launcher.events.size)
        assertEquals(1, launcher.alarms.size)
        assertEquals(1, launcher.fileBrowses)
        assertEquals(1, launcher.notificationSettings)
    }

    @Test
    fun toolManifestCarriesAllNewTypedContractsWithinBoundedThirtyTwoSlotSurface() {
        val launcher = RecordingLauncher()
        val descriptors = providers(launcher).map { it.descriptor }
        val manifest = TitanActionProtocol.instructions(
            capabilities = descriptors.mapTo(linkedSetOf()) { it.capability },
            descriptors = descriptors
        )

        listOf(
            "android.app.launch",
            "android.web.search",
            "android.clipboard.write",
            "android.files.browse",
            "android.contact.compose",
            "android.calendar.compose",
            "android.alarm.prepare",
            "android.media.open",
            "android.notifications.settings",
            "android.screen.home"
        ).forEach { capability ->
            assertTrue("missing manifest contract for $capability", manifest.contains("TOOL $capability "))
        }
    }
}
