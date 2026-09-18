package io.amper.neuroos.core

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.Settings

class AndroidContextDeviceActionLauncher(context: Context) : AndroidDeviceActionLauncher {
    private val appContext = context.applicationContext

    override fun openSettings(target: AndroidSettingsTarget): Result<Unit> = runCatching {
        val action = when (target) {
            AndroidSettingsTarget.WIFI -> Settings.ACTION_WIFI_SETTINGS
            AndroidSettingsTarget.BLUETOOTH -> Settings.ACTION_BLUETOOTH_SETTINGS
            AndroidSettingsTarget.DISPLAY -> Settings.ACTION_DISPLAY_SETTINGS
            AndroidSettingsTarget.SOUND -> Settings.ACTION_SOUND_SETTINGS
            AndroidSettingsTarget.BATTERY -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            AndroidSettingsTarget.ACCESSIBILITY -> Settings.ACTION_ACCESSIBILITY_SETTINGS
        }
        appContext.startActivity(
            Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    override fun openTimer(command: AndroidTimerCommand): Result<Unit> = runCatching {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, command.seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        command.label?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        appContext.startActivity(intent)
    }

    override fun openShareSheet(text: String): Result<Unit> = runCatching {
        val sendIntent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
        val chooser = Intent.createChooser(sendIntent, "Share with")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(chooser)
    }
}
