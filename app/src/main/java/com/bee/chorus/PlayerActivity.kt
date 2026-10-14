package com.bee.chorus

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.Player
import com.bee.chorus.playback.PlayerHolder
import com.bee.chorus.ui.PlayerPanel

/**
 * Full-screen player. Hosts a single [PlayerPanel]; all playback state comes from the shared
 * [PlayerHolder], so entering or leaving this screen never changes what is playing.
 *
 * The screen is kept awake only while audio is actually playing — the flag is driven by the
 * player's isPlaying, so pausing from the notification also drops it.
 */
class PlayerActivity : AppCompatActivity() {

    private var panel: PlayerPanel? = null

    private val keepAwakeListener: (Player) -> Unit = { player ->
        if (player.isPlaying) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
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