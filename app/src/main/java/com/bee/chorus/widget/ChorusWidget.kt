package com.bee.chorus.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.media3.common.Player
import com.bee.chorus.MainActivity
import com.bee.chorus.R
import com.bee.chorus.playback.PlayerHolder
import com.bee.chorus.ui.Art

/**
 * Home-screen mini player: current track, previous / play-pause / next.
 *
 * How it drives playback: each button is a PendingIntent to *this* receiver, which then
 * goes through [PlayerHolder] — the one shared MediaController the whole app already uses.
 * The alternative (building a MediaController here from the session token) would create a
 * second controller and duplicate the binding logic, so the widget deliberately does not
 * hold a token of its own. The trade-off is that a button press on a cold process starts
 * the service via the same binding path the UI uses, which is exactly what a media button
 * on a notification does anyway.
 *
 * How it stays current: [push] is called from PlaybackService's player listener on every
 * player event, so play/pause/track changes/queue edits all land without polling. Nothing
 * runs while the widget is idle (updatePeriodMillis = 0).
 *
 * Art is the widget's one concession to looks: the same bounded, cached decode as the list
 * rows ([Art.bitmap], subsampled, never a full-size bitmap), applied as an asynchronous
 * second pass so a slow album never delays the text. A late decode cannot paint over a
 * newer track — the requested key is checked before it is applied.
 */
class ChorusWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // Adding the widget must not bind the controller (that would start the service
        // from the launcher); show whatever the process already knows, or "Chorus".
        push(context, PlayerHolder.player)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return
        if (action != ACTION_PREV && action != ACTION_TOGGLE && action != ACTION_NEXT) return
        val app = context.applicationContext
        // bind() is idempotent; on a cold process it connects the same controller the UI
        // would, and the action runs as soon as it is ready.
        PlayerHolder.bind(app)
        PlayerHolder.whenReady { player ->
            if (player.mediaItemCount == 0) return@whenReady
            when (action) {
                ACTION_PREV -> if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem()
                ACTION_NEXT -> if (player.hasNextMediaItem()) player.seekToNextMediaItem()
                else -> if (player.isPlaying) player.pause() else player.play()
            }
        }
    }

    companion object {

        private const val ACTION_PREV = "com.bee.chorus.widget.PREV"
        private const val ACTION_TOGGLE = "com.bee.chorus.widget.TOGGLE"
        private const val ACTION_NEXT = "com.bee.chorus.widget.NEXT"

        /** 128 px covers a 44dp thumbnail on any density; bounded, and cached by [Art]. */
        private const val ART_TARGET_PX = 128
        private const val DISABLED_ALPHA = 0.35f


        /** The art URI the pending decode belongs to, so a slow decode cannot paint over
         *  a track that has since changed. Main thread only, like every widget update. */
        private var artKey: String? = null

        /** Re-render every placed widget from [player] (null shows the idle state). */
        fun push(context: Context, player: Player?) {
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app)
            val ids = manager.getAppWidgetIds(ComponentName(app, ChorusWidget::class.java))
            if (ids.isEmpty()) return

            val views = build(app, player)
            manager.updateAppWidget(ids, views)

            val artUri = player?.currentMediaItem?.mediaMetadata?.artworkUri
            val key = artUri?.toString()
            artKey = key
            if (artUri == null || key == null) return
            Art.bitmap(app, artUri, ART_TARGET_PX) { bmp ->
                if (artKey != key) return@bitmap
                if (bmp != null) {
                    views.setImageViewBitmap(R.id.widget_art, bmp)
                } else {
                    // An art URI that will not decode must not leave the previous track's
                    // sleeve on screen (Art negative-caches it, so this is the only chance).
                    views.setImageViewResource(R.id.widget_art, R.drawable.art_placeholder)
                }
                manager.updateAppWidget(ids, views)
            }
        }

        private fun build(context: Context, player: Player?): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_player)
            val metadata = player?.currentMediaItem?.mediaMetadata

            val title = metadata?.title?.toString()?.takeIf { it.isNotBlank() }
            val artist = metadata?.artist?.toString()?.takeIf { it.isNotBlank() }
            views.setTextViewText(R.id.widget_title, title ?: context.getString(R.string.app_name))
            views.setTextViewText(R.id.widget_artist, artist.orEmpty())
            views.setViewVisibility(
                R.id.widget_artist,
                if (artist == null) View.GONE else View.VISIBLE
            )
            if (metadata?.artworkUri == null) {
                views.setImageViewResource(R.id.widget_art, R.drawable.art_placeholder)
            }

            val playing = player?.isPlaying == true
            views.setImageViewResource(R.id.widget_play, if (playing) R.drawable.ic_pause else R.drawable.ic_play)
            views.setContentDescription(R.id.widget_play, if (playing) "Pause" else "Play")
            views.setFloat(
                R.id.widget_play,
                "setAlpha",
                if ((player?.mediaItemCount ?: 0) > 0) 1f else DISABLED_ALPHA
            )
            views.setFloat(
                R.id.widget_prev,
                "setAlpha",
                if (player?.hasPreviousMediaItem() == true) 1f else DISABLED_ALPHA
            )
            views.setFloat(
                R.id.widget_next,
                "setAlpha",
                if (player?.hasNextMediaItem() == true) 1f else DISABLED_ALPHA
            )

            views.setOnClickPendingIntent(R.id.widget_prev, action(context, ACTION_PREV, 1))
            views.setOnClickPendingIntent(R.id.widget_play, action(context, ACTION_TOGGLE, 2))
            views.setOnClickPendingIntent(R.id.widget_next, action(context, ACTION_NEXT, 3))
            views.setOnClickPendingIntent(R.id.widget_text, open(context))
            return views
        }

        private fun action(context: Context, action: String, request: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                request,
                Intent(context, ChorusWidget::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        private fun open(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                ),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
    }
}
