package com.bee.chorus.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bee.chorus.R
import com.bee.chorus.data.Track
import com.bee.chorus.playback.PlayerHolder
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Live search over the whole library.
 *
 * Matching is a plain case-insensitive `contains` against title, artist and album. Typing is
 * debounced by [DEBOUNCE_MS], the actual scan runs on one background thread, and results whose
 * generation counter has been superseded are dropped instead of rendered — so a fast typist
 * never sees results from an older keystroke.
 *
 * The host owns the library: call [setTracks] whenever it changes. Row taps play the
 * *filtered* list from the tapped index, which is what a search result list should do.
 */
class SearchPanel @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : LinearLayout(ctx, attrs) {

    private val input: EditText
    private val status: TextView
    private val emptyView: TextView
    private val listView: RecyclerView
    private val adapter = TrackAdapter()

    private val main = Handler(Looper.getMainLooper())

    /**
     * One worker thread, daemon so it can never hold the process open. Created lazily so a
     * panel that is never typed into costs no thread.
     */
    private val executor by lazy {
        Executors.newSingleThreadExecutor(DaemonThreadFactory)
    }

    /** Bumped for every accepted query; stale filter results compare against it and bail. */
    private val generation = AtomicInteger(0)

    private var allTracks: List<Track> = emptyList()

    /** What is on screen right now — the list a row click plays from. */
    private var results: List<Track> = emptyList()

    private var pendingQuery: String = ""

    private val debounce = Runnable { runSearch(pendingQuery) }

    init {
        orientation = LinearLayout.VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_search, this, true)

        input = findViewById(R.id.search_input)
        status = findViewById(R.id.search_status)
        emptyView = findViewById(R.id.search_empty)
        listView = findViewById(R.id.search_list)

        listView.layoutManager = LinearLayoutManager(context)
        listView.adapter = adapter

        adapter.onClick = { index, list ->
            if (index in list.indices) PlayerHolder.play(list, index)
        }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                pendingQuery = s?.toString().orEmpty()
                main.removeCallbacks(debounce)
                main.postDelayed(debounce, DEBOUNCE_MS)
            }
        })

        showResults(emptyList())
    }

    // ---------------------------------------------------------------- public

    /**
     * Replace the searchable library. Re-runs the current query, so calling this after a
     * library scan refreshes an on-screen search without the host touching the EditText.
     */
    fun setTracks(list: List<Track>) {
        allTracks = list
        main.removeCallbacks(debounce)
        main.post(debounce)
    }

    /** The list currently on screen (the filtered results). */
    fun results(): List<Track> = results

    // ---------------------------------------------------------------- search

    private fun runSearch(query: String) {
        val source = allTracks
        val needle = query.trim()
        val gen = generation.incrementAndGet()

        if (needle.isEmpty()) {
            // Blank query: everything, no background work needed.
            showResults(source)
            return
        }

        executor.execute {
            val hits = source.filter { matches(it, needle) }
            main.post {
                if (generation.get() != gen) return@post // superseded by a newer keystroke
                showResults(hits)
            }
        }
    }

    private fun showResults(list: List<Track>) {
        results = list
        adapter.submit(list)
        if (list.isEmpty()) {
            // The centred overlay carries the friendly message; the tiny status line
            // stands down so two empty-state lines never stack up.
            emptyView.text =
                if (allTracks.isEmpty()) EMPTY_LIBRARY else NO_MATCHES
            emptyView.visibility = View.VISIBLE
            status.text = ""
        } else {
            emptyView.visibility = View.GONE
            status.text = "${list.size} ${if (list.size == 1) "result" else "results"}"
        }
    }

    /** Case-insensitive match on title, artist and album. */
    private fun matches(t: Track, needle: String): Boolean =
        t.title.contains(needle, ignoreCase = true) ||
            t.artist.contains(needle, ignoreCase = true) ||
            t.album.contains(needle, ignoreCase = true)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Paired with the detach below, so a destroyed Activity leaks no star listener.
        adapter.observeFavourites()
    }

    override fun onDetachedFromWindow() {
        // Drop any queued keystroke so a detached panel cannot fire a late update.
        main.removeCallbacks(debounce)
        adapter.stopObservingFavourites()
        super.onDetachedFromWindow()
    }

    // ---------------------------------------------------------------- utils

    private object DaemonThreadFactory : ThreadFactory {
        override fun newThread(r: Runnable): Thread =
            Thread(r, "chorus-search").apply { isDaemon = true }
    }

    private companion object {
        const val DEBOUNCE_MS = 150L
        const val NO_MATCHES = "No matches — try another word"
        const val EMPTY_LIBRARY = "No music on this phone yet"
    }
}