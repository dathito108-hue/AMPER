package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidDeviceActionToolsTest {
    private class RecordingLauncher : AndroidDeviceActionLauncher {
        val settings = mutableListOf<AndroidSettingsTarget>()
        val timers = mutableListOf<AndroidTimerCommand>()
        val shares = mutableListOf<String>()

        override fun openSettings(target: AndroidSettingsTarget): Result<Unit> = runCatching {
            settings += target
        }

        override fun openTimer(command: AndroidTimerCommand): Result<Unit> = runCatching {
            timers += command
        }

        override fun openShareSheet(text: String): Result<Unit> = runCatching {
            shares += text
        }
    }

    @Test
    fun allAndroidActionProvidersAreExternalAndRequireExplicitApproval() {
        val launcher = RecordingLauncher()
        val providers = listOf(
            AndroidSettingsOpenToolProvider(launcher),
            AndroidTimerPrepareToolProvider(launcher),
            AndroidShareTextToolProvider(launcher)
        )
        val registry = InMemoryToolRegistry().also { registry ->
            providers.forEach(registry::register)
        }
        val audit = InMemoryToolAuditLog()
        val loop = SovereignActionLoop(
            registry = registry,
            fabric = AuditedToolFabric(
                gate = DenyByDefaultAuthorityGate(
                    providers.mapTo(linkedSetOf()) { it.descriptor.capability }
                ),
                registry = registry,
                audit = audit
            ),
            memory = InMemoryMemoryOs(),
            workspace = InMemoryWorkspace()
        )

        providers.forEach { provider ->
            assertEquals(ToolSideEffect.EXTERNAL, provider.descriptor.sideEffect)
        }

        val proposal = ActionProposal(
            capability = AndroidSettingsOpenToolContract.capability,
            reason = "User asked to open Wi-Fi settings",
            input = "wifi"
        )
        val pending = loop.evaluate(proposal)

        assertEquals(ActionStatus.REQUIRES_CONFIRMATION, pending.status)
        assertTrue(launcher.settings.isEmpty())
        assertTrue(audit.snapshot().isEmpty())

        val approved = loop.approveBound(
            proposal = proposal,
            expectedToolId = AndroidSettingsOpenToolContract.toolId,
            expectedSideEffect = ToolSideEffect.EXTERNAL
        )

        assertEquals(ActionStatus.EXECUTED, approved.status)
        assertEquals(listOf(AndroidSettingsTarget.WIFI), launcher.settings)
        assertEquals(1, audit.snapshot().size)
        assertTrue(audit.snapshot().single().authorized)
    }

    @Test
    fun wrongApprovedProviderBindingFailsClosedBeforeIntentLaunch() {
        val launcher = RecordingLauncher()
        val provider = AndroidSettingsOpenToolProvider(launcher)
        val registry = InMemoryToolRegistry().also { it.register(provider) }
        val audit = InMemoryToolAuditLog()
        val loop = SovereignActionLoop(
            registry = registry,
            fabric = AuditedToolFabric(
                gate = DenyByDefaultAuthorityGate(
                    setOf(AndroidSettingsOpenToolContract.capability)
                ),
                registry = registry,
                audit = audit
            ),
            memory = InMemoryMemoryOs(),
            workspace = InMemoryWorkspace()
        )
        val proposal = ActionProposal(
            capability = AndroidSettingsOpenToolContract.capability,
            reason = "Open settings",
            input = "bluetooth"
        )

        val outcome = loop.approveBound(
            proposal = proposal,
            expectedToolId = ToolId("different-provider"),
            expectedSideEffect = ToolSideEffect.EXTERNAL
        )

        assertEquals(ActionStatus.DENIED, outcome.status)
        assertTrue(launcher.settings.isEmpty())
        assertEquals(1, audit.snapshot().size)
        assertFalse(audit.snapshot().single().authorized)
    }

    @Test
    fun settingsOutsideWhitelistIsRejectedBeforeApproval() {
        val launcher = RecordingLauncher()
        val provider = AndroidSettingsOpenToolProvider(launcher)
        val registry = InMemoryToolRegistry().also { it.register(provider) }
        val loop = SovereignActionLoop(
            registry = registry,
            fabric = AuditedToolFabric(
                DenyByDefaultAuthorityGate(
                    setOf(AndroidSettingsOpenToolContract.capability)
                ),
                registry,
                InMemoryToolAuditLog()
            ),
            memory = InMemoryMemoryOs(),
            workspace = InMemoryWorkspace()
        )

        val outcome = loop.evaluate(
            ActionProposal(
                capability = AndroidSettingsOpenToolContract.capability,
                reason = "Open developer settings",
                input = "developer"
            )
        )

        assertEquals(ActionStatus.MALFORMED, outcome.status)
        assertTrue(launcher.settings.isEmpty())
    }

    @Test
    fun timerParserIsBoundedAndProviderNeverClaimsSilentTimerCreation() {
        val command = AndroidTimerCommandParser.parse(
            "seconds=90;label=Tea"
        )
        assertEquals(90, command.seconds)
        assertEquals("Tea", command.label)

        assertTrue(
            runCatching {
                AndroidTimerCommandParser.parse("seconds=0;label=bad")
            }.isFailure
        )
        assertTrue(
            runCatching {
                AndroidTimerCommandParser.parse("seconds=86401")
            }.isFailure
        )
        assertTrue(
            runCatching {
                AndroidTimerCommandParser.parse("minutes=5")
            }.isFailure
        )

        val launcher = RecordingLauncher()
        val provider = AndroidTimerPrepareToolProvider(launcher)
        val result = provider.execute("seconds=90;label=Tea").getOrThrow()

        assertEquals(listOf(command), launcher.timers)
        assertTrue(result.contains("timer_ui_opened"))
        assertTrue(result.contains("skip_ui=false"))
    }

    @Test
    fun shareProviderOnlyOpensChooserAndReportsNotSent() {
        val launcher = RecordingLauncher()
        val provider = AndroidShareTextToolProvider(launcher)

        val result = provider.execute("hello from AMPER").getOrThrow()

        assertEquals(listOf("hello from AMPER"), launcher.shares)
        assertTrue(result.contains("share_sheet_opened"))
        assertTrue(result.contains("sent=false"))
        assertTrue(provider.execute("   ").isFailure)
        assertTrue(provider.execute("line one\nline two").isFailure)
    }

    @Test
    fun manifestInstructionsExposeExactExternalContracts() {
        val descriptors = listOf(
            AndroidSettingsOpenToolProvider(RecordingLauncher()).descriptor,
            AndroidTimerPrepareToolProvider(RecordingLauncher()).descriptor,
            AndroidShareTextToolProvider(RecordingLauncher()).descriptor
        )
        val capabilities = descriptors.mapTo(linkedSetOf()) { it.capability }

        val manifest = TitanActionProtocol.instructions(
            capabilities = capabilities,
            descriptors = descriptors
        )

        assertTrue(manifest.contains("android.settings.open side_effect=EXTERNAL"))
        assertTrue(manifest.contains("input_values=accessibility|battery|bluetooth|display|sound|wifi"))
        assertTrue(manifest.contains("android.timer.prepare side_effect=EXTERNAL"))
        assertTrue(manifest.contains("android.share.text side_effect=EXTERNAL"))
    }
}
