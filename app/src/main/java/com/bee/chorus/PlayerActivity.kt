package com.bee.chorus

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.Player
import com.bee.chorus.data.Settings
import com.bee.chorus.playback.PlayerHolder
import com.bee.chorus.ui.CycleMode
import com.bee.chorus.ui.PlayerPanel

/**
 * Full-screen player. Hosts a single [PlayerPanel]; all playback state comes from the shared
 * [PlayerHolder], so entering or leaving this screen never changes what is playing.
 *
 * The screen is kept awake only while audio is actually playing — the flag is driven by the
 * player's isPlaying, so pausing from the notification also drops it.
 *
 * Theming: with cycle mode off this screen follows the same DayNight theme as the rest of
 * the app (it used to be pinned dark so the artwork backdrop would read; the artwork now
 * sits on its own dark card, so the page can be light). With cycle mode on it takes the
 * bright high-contrast theme and the 72dp touch targets, exactly as MainActivity does.
 */
class PlayerActivity : AppCompatActivity() {

    private var panel: PlayerPanel? = null
    private var cycle = false

    private val keepAwakeListener: (Player) -> Unit = { player ->
        if (player.isPlaying) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /**
     * Cycle mode can be flipped on the Setup tab or from this screen's own sun control.
     * Both land here, and both need a `recreate()` — the theme is chosen before anything
     * inflates, so the running views cannot be re-themed in place.
     *
     * Held strongly by [Settings] for the life of the process: paired with
     * `removeListener` in `onDestroy` below.
     */
    private val settingsListener: (Boolean) -> Unit = { on ->
        if (on != cycle) recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Cycle mode swaps the Activity theme, so it has to be chosen before anything
        // inflates — before super.onCreate(), certainly before setContentView().
        cycle = Settings.isCycleMode(this)
        CycleMode.applyTheme(this, cycle)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        // Edge-to-edge is forced at targetSdk 36; inset so controls clear the
        // status and navigation bars.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        // 72dp touch targets for gloved hands, posted because the panel's rows only exist
        // once the layout pass has run — same discipline as MainActivity.
        findViewById<View>(android.R.id.content).post {
            CycleMode.apply(findViewById(android.R.id.content), cycle)
        }

        // Added after the first read of the setting so restoring it on create is not
        // mistaken for the user changing it.
        Settings.addListener(settingsListener)

        panel = findViewById<PlayerPanel>(R.id.player_panel)
        panel?.show()
    }

    override fun onStart() {
        super.onStart()
        PlayerHolder.addPlayerListener(keepAwakeListener)
    }

    override fun onStop() {
        PlayerHolder.removePlayerListener(keepAwakeListener)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }

    override fun onDestroy() {
        // Settings holds its listeners strongly and process-wide: anything left behind here
        // leaks this Activity for good, including across the recreate() from the toggle.
        Settings.removeListener(settingsListener)
        panel?.hide()
        panel = null
        super.onDestroy()
    }

    companion object {
        /** Open the full-screen player. */
        fun start(context: Context) {
            context.startActivity(Intent(context, PlayerActivity::class.java))
        }
    }
}