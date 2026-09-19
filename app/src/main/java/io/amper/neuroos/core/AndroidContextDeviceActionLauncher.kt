package io.amper.neuroos.core

import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings

class AndroidContextDeviceActionLauncher(context: Context) :
    AndroidDeviceActionLauncher,
    AndroidUniversalActionLauncher {
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

    override fun launchApp(packageName: String): Result<Unit> = runCatching {
        val intent = appContext.packageManager.getLaunchIntentForPackage(packageName)
            ?: error("no launchable activity for package " + packageName)
        appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun searchWeb(query: String): Result<Unit> = runCatching {
        appContext.startActivity(
            Intent(Intent.ACTION_WEB_SEARCH)
                .putExtra(SearchManager.QUERY, query)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    override fun writeClipboard(text: String): Result<Unit> = runCatching {
        val clipboard = appContext.getSystemService(ClipboardManager::class.java)
            ?: error("Android clipboard service is unavailable")
        clipboard.setPrimaryClip(ClipData.newPlainText("AMPER", text))
    }

    override fun browseFiles(): Result<Unit> = runCatching {
        appContext.startActivity(
            Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    override fun composeContact(command: AndroidContactComposeCommand): Result<Unit> = runCatching {
        val intent = Intent(Intent.ACTION_INSERT)
            .setType(ContactsContract.Contacts.CONTENT_TYPE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        command.name?.let {
            intent.putExtra(ContactsContract.Intents.Insert.NAME, it)
        }
        command.phone?.let {
            intent.putExtra(ContactsContract.Intents.Insert.PHONE, it)
        }
        command.email?.let {
            intent.putExtra(ContactsContract.Intents.Insert.EMAIL, it)
        }
        appContext.startActivity(intent)
    }

    override fun composeCalendarEvent(
        command: AndroidCalendarComposeCommand
    ): Result<Unit> = runCatching {
        val durationMs = Math.multiplyExact(command.durationMinutes.toLong(), 60_000L)
        val endEpochMs = Math.addExact(command.startEpochMs, durationMs)
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, command.title)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, command.startEpochMs)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endEpochMs)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        command.location?.let {
            intent.putExtra(CalendarContract.Events.EVENT_LOCATION, it)
        }
        appContext.startActivity(intent)
    }

    override fun prepareAlarm(command: AndroidAlarmPrepareCommand): Result<Unit> = runCatching {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, command.hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, command.minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        command.label?.let {
            intent.putExtra(AlarmClock.EXTRA_MESSAGE, it)
        }
        appContext.startActivity(intent)
    }

    override fun openMedia(url: String): Result<Unit> = runCatching {
        appContext.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    override fun openNotificationSettings(): Result<Unit> = runCatching {
        appContext.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    override fun openHome(): Result<Unit> = runCatching {
        appContext.startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

}
