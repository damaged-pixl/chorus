package com.bee.chorus.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bee.chorus.data.Settings

/**
 * Bring the saved queue back after a reboot — a setting, off by default, because it starts
 * the playback service at every boot for a queue the user may never have meant to keep.
 *
 * Starting a foreground service from `BOOT_COMPLETED` is on the platform's allowlist, so
 * this is allowed under targetSdk 34+ without exemption wrangling. What comes back is the
 * queue the app already restores *paused*: the phone does not start playing music by
 * itself at boot, it just stops being empty — the notification, the widget and the lock
 * screen all show where you left off.
 *
 * The receiver is not exported: `BOOT_COMPLETED` is a system broadcast, and a receiver
 * with `exported="false"` still receives those, so nothing but the system (and this app)
 * can reach it. `QUICKBOOT_POWERON` is not a platform action — some OEMs send it instead
 * of `BOOT_COMPLETED` on a fast boot — so it is accepted beside the real one.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != ACTION_QUICKBOOT
        ) return
        if (!Settings.restoreOnBoot(context)) return

        context.startForegroundService(
            Intent(context, PlaybackService::class.java)
                .setAction(PlaybackService.ACTION_RESTORE_ON_BOOT)
        )
    }

    private companion object {
        /** Some OEMs' fast-boot action; harmless to accept beside the platform's. */
        const val ACTION_QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON"
    }
}
