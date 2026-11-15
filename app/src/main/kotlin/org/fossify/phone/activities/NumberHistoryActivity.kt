package org.fossify.phone.activities

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.provider.CallLog.Calls
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.phone.R
import org.fossify.phone.databinding.ActivityNumberHistoryBinding
import org.fossify.phone.extensions.startCallWithConfirmationCheck
import org.fossify.phone.helpers.NativeCallRecording
import org.fossify.phone.helpers.NumberHistoryEntry
import org.fossify.phone.helpers.NumberHistoryRepository
import org.fossify.phone.helpers.applyDpadFocusHighlight
import java.text.DateFormat
import java.util.Date

/**
 * DOOV R77 Pro call history by number. Inspired by NovaDial's contact-specific call timeline,
 * but designed around the real physical keypad (OK details/play, green Call, Back return).
 */
class NumberHistoryActivity : SimpleActivity() {
    companion object {
        private const val EXTRA_PHONE_NUMBER = "number_history_phone"
        private const val EXTRA_DISPLAY_NAME = "number_history_name"
        private const val AUDIO_PERMISSION_REQUEST = 547

        fun open(context: Context, number: String, displayName: String) {
            if (number.isBlank()) return
            context.startActivity(Intent(context, NumberHistoryActivity::class.java).apply {
                putExtra(EXTRA_PHONE_NUMBER, number)
                putExtra(EXTRA_DISPLAY_NAME, displayName)
            })
        }
    }

    private val binding by viewBinding(ActivityNumberHistoryBinding::inflate)
    private val phoneNumber by lazy { intent.getStringExtra(EXTRA_PHONE_NUMBER).orEmpty() }
    private val displayName by lazy { intent.getStringExtra(EXTRA_DISPLAY_NAME).orEmpty().ifBlank { phoneNumber } }

    private var calls: List<NumberHistoryEntry> = emptyList()
    private var recordings: Map<Long, NativeCallRecording> = emptyMap()
    private var player: MediaPlayer? = null
    private var playingCallId: Long? = null
    private val historyAdapter by lazy { HistoryAdapter() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        if (phoneNumber.isBlank()) {
            finish()
            return
        }

        binding.numberHistoryList.adapter = historyAdapter
        binding.numberHistoryList.isFocusable = false
        binding.numberHistoryTitle.text = if (displayName == phoneNumber) {
            getString(R.string.dpad_history_title)
        } else displayName
        binding.numberHistoryNumber.text = phoneNumber
        binding.numberHistoryStats.text = ""
        binding.numberHistoryPlaceholder.text = getString(R.string.dpad_history_loading)
        updateColors()
        loadHistory()

        if (!NumberHistoryRepository.canReadAudio(this)) {
            val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_AUDIO
            } else Manifest.permission.READ_EXTERNAL_STORAGE
            requestPermissions(arrayOf(permission), AUDIO_PERMISSION_REQUEST)
        }
    }

    override fun onResume() {
        super.onResume()
        updateColors()
    }

    private fun updateColors() {
        val background = getProperBackgroundColor()
        val foreground = getProperTextColor()
        binding.numberHistoryRoot.setBackgroundColor(background)
        binding.numberHistoryTitle.setTextColor(foreground)
        binding.numberHistoryNumber.setTextColor(getProperPrimaryColor())
        listOf(binding.numberHistoryStats, binding.numberHistoryPlaceholder, binding.numberHistoryHint)
            .forEach { it.setTextColor(foreground) }
        window.statusBarColor = background
    }

    private fun loadHistory() {
        ensureBackgroundThread {
            val history = NumberHistoryRepository.queryCalls(this, phoneNumber)
            val media = NumberHistoryRepository.queryRecordings(this)
            val linked = NumberHistoryRepository.associate(history, media)
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                calls = history
                recordings = linked
                historyAdapter.notifyDataSetChanged()
                binding.numberHistoryStats.text = getString(
                    R.string.dpad_history_stats,
                    history.size,
                    history.count { it.type == Calls.INCOMING_TYPE },
                    history.count { it.type == Calls.OUTGOING_TYPE },
                    history.count { it.type == Calls.MISSED_TYPE }
                )
                binding.numberHistoryPlaceholder.visibility =
                    if (history.isEmpty()) View.VISIBLE else View.GONE
                binding.numberHistoryPlaceholder.text = getString(R.string.dpad_history_empty)
                binding.numberHistoryList.post {
                    if (!isFinishing && history.isNotEmpty()) {
                        binding.numberHistoryList.findViewHolderForAdapterPosition(0)
                            ?.itemView?.requestFocusFromTouch()
                    }
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == AUDIO_PERMISSION_REQUEST &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            loadHistory()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_CALL) {
            if (event.repeatCount == 0) {
                startCallWithConfirmationCheck(phoneNumber, displayName)
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun onEntryPressed(call: NumberHistoryEntry) {
        val audio = recordings[call.id]
        if (audio != null) {
            togglePlayback(call.id, audio)
        } else {
            val message = getString(
                R.string.dpad_history_call_details,
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(call.startedAt)),
                direction(call.type),
                formatDuration(call.durationSeconds)
            )
            AlertDialog.Builder(this)
                .setTitle(displayName)
                .setMessage(message)
                .setPositiveButton(R.string.dpad_history_call_again) { _, _ ->
                    startCallWithConfirmationCheck(phoneNumber, displayName)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun togglePlayback(callId: Long, audio: NativeCallRecording) {
        if (playingCallId == callId && player != null) {
            stopPlayback()
            return
        }
        stopPlayback()
        try {
            val next = MediaPlayer.create(this, audio.uri)
            if (next == null) {
                toast(R.string.dpad_history_playback_failed)
                return
            }
            player = next
            playingCallId = callId
            next.setOnCompletionListener { stopPlayback() }
            next.setOnErrorListener { _, _, _ ->
                stopPlayback()
                true
            }
            next.start()
            historyAdapter.notifyDataSetChanged()
        } catch (e: Exception) {
            stopPlayback()
            toast(R.string.dpad_history_playback_failed)
        }
    }

    private fun stopPlayback() {
        player?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        player = null
        playingCallId = null
        if (!isDestroyed) historyAdapter.notifyDataSetChanged()
    }

    override fun onDestroy() {
        stopPlayback()
        super.onDestroy()
    }

    private fun direction(type: Int): String = when (type) {
        Calls.INCOMING_TYPE -> getString(R.string.dpad_history_incoming)
        Calls.OUTGOING_TYPE -> getString(R.string.dpad_history_outgoing)
        Calls.MISSED_TYPE -> getString(R.string.dpad_history_missed)
        Calls.REJECTED_TYPE -> getString(R.string.dpad_history_rejected)
        Calls.BLOCKED_TYPE -> getString(R.string.dpad_history_blocked)
        else -> getString(R.string.dpad_history_other)
    }

    private fun formatDuration(seconds: Long): String {
        val minutes = seconds / 60
        val remainder = seconds % 60
        return "${minutes}m ${remainder}s"
    }

    private inner class HistoryAdapter : RecyclerView.Adapter<HistoryAdapter.HistoryHolder>() {
        inner class HistoryHolder(val row: LinearLayout, val title: TextView, val subtitle: TextView)
            : RecyclerView.ViewHolder(row)

        override fun getItemCount() = calls.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryHolder {
            val paddingX = (14 * resources.displayMetrics.density).toInt()
            val paddingY = (10 * resources.displayMetrics.density).toInt()
            val row = LinearLayout(this@NumberHistoryActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(paddingX, paddingY, paddingX, paddingY)
                isFocusable = true
                isClickable = true
                applyDpadFocusHighlight()
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            val title = TextView(this@NumberHistoryActivity).apply {
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17f)
                setTextColor(getProperTextColor())
                maxLines = 1
            }
            val subtitle = TextView(this@NumberHistoryActivity).apply {
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(getProperTextColor())
                alpha = 0.75f
                maxLines = 1
            }
            row.addView(title)
            row.addView(subtitle)
            return HistoryHolder(row, title, subtitle)
        }

        override fun onBindViewHolder(holder: HistoryHolder, position: Int) {
            val entry = calls[position]
            val recording = recordings[entry.id]
            val whenText = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(entry.startedAt))
            val recorded = when {
                recording == null -> ""
                playingCallId == entry.id -> "  ■ ${getString(R.string.dpad_history_stop_playback)}"
                else -> "  ▶ ${getString(R.string.dpad_history_play_recording)}"
            }
            holder.title.text = "${direction(entry.type)}$recorded"
            holder.subtitle.text = "$whenText · ${formatDuration(entry.durationSeconds)}"
            holder.row.contentDescription = "${holder.title.text}, ${holder.subtitle.text}"
            holder.row.setOnClickListener { onEntryPressed(entry) }
        }
    }
}
