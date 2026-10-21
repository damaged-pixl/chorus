package com.bee.chorus.ui

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.bee.chorus.R
import com.bee.chorus.data.Settings

/**
 * Every menu in the app, in one place.
 *
 * The app's menus are alert dialogs — a long-press on a track, a playlist or a queue row
 * opens one — and they used to be built with a bare `AlertDialog.Builder(context)`, which
 * means the platform's stock dialog: the app's own colours nowhere in sight, and cycle
 * mode's big touch targets nowhere either (the Activity's content view is a different
 * tree from a dialog's).
 *
 * [builder] fixes both: it picks the dialog theme that matches the current palette
 * (normal or cycle) and hooks the show listener that applies cycle mode's larger targets
 * to the dialog's own view tree.
 */
object Dialogs {

    /**
     * An alert-dialog builder in the app's dialog theme.
     *
     * The theme is chosen from [Settings.isCycleMode] rather than from the Activity's own
     * theme, so a dialog opened from a panel inside the player matches the rest of the app
     * even if the Activity behind it is mid-recreation.
     */
    fun builder(context: Context): AlertDialog.Builder {
        val theme = if (Settings.isCycleMode(context)) {
            R.style.Theme_Chorus_Cycle_Dialog
        } else {
            R.style.Theme_Chorus_Dialog
        }
        return AlertDialog.Builder(context, theme)
    }

    /**
     * Show the dialog, then apply cycle mode's 72dp targets and larger text to it.
     *
     * The dialog's views are inflated by the dialog, not by the Activity, so the Activity's
     * own `CycleMode.apply` call never reaches them — this is the only thing that puts
     * cycle mode's big targets inside a menu.
     */
    fun show(builder: AlertDialog.Builder): AlertDialog {
        val dialog = builder.show()
        val cycle = Settings.isCycleMode(dialog.context)
        dialog.window?.decorView?.let { CycleMode.apply(it, cycle) }
        return dialog
    }
}

/**
 * Show the dialog with cycle mode's big targets applied — the drop-in replacement for
 * `AlertDialog.Builder.show()` in this app.
 */
fun AlertDialog.Builder.chorusShow(): AlertDialog = Dialogs.show(this)
