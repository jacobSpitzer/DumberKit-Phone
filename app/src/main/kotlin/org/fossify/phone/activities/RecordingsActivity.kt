package org.fossify.phone.activities

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.phone.R
import org.fossify.phone.databinding.ActivityRecordingsBinding
import org.fossify.phone.helpers.NativeCallRecording
import org.fossify.phone.helpers.NumberHistoryRepository
import org.fossify.phone.helpers.applyDpadFocusHighlight
import org.fossify.phone.recording.RecordingNames
import java.text.DateFormat
import java.util.Date

/**
 * Browse native phone recordings independently of call-log association.
 * Works with arbitrary user-selected recording folder names and media files.
 */
class RecordingsActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityRecordingsBinding::inflate)
    private val recordings = mutableListOf<NativeCallRecording>()
    private val recordingsAdapter by lazy { RecordingRows() }
    private var player: MediaPlayer? = null
    private var activeUri: Uri? = null

    private val folderPicker: ActivityResultLauncher<Uri?> =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                if (RecordingNames.useFolder(this, uri)) {
                    toast(R.string.dpad_recording_folder_saved)
                    refresh()
                } else {
                    toast(R.string.dpad_recording_folder_invalid)
                }
            }
        }

    private val audioPermission: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        binding.recordingsList.layoutManager = LinearLayoutManager(this)
        binding.recordingsList.adapter = recordingsAdapter
        binding.recordingsFolder.apply {
            isFocusable = true
            isClickable = true
            applyDpadFocusHighlight()
            setOnClickListener { folderPicker.launch(null) }
        }
        updateColors()
        refresh()
        if (!NumberHistoryRepository.canReadAudio(this)) {
            val requested = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_AUDIO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
            if (checkSelfPermission(requested) != PackageManager.PERMISSION_GRANTED) {
                audioPermission.launch(requested)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateColors()
        refresh()
    }

    private fun updateColors() {
        val fg = getProperTextColor()
        val bg = getProperBackgroundColor()
        binding.recordingsRoot.setBackgroundColor(bg)
        listOf(binding.recordingsTitle, binding.recordingsStatus, binding.recordingsHint)
            .forEach { it.setTextColor(fg) }
        binding.recordingsFolder.setTextColor(getProperPrimaryColor())
        window.statusBarColor = bg
    }

    private fun refresh() {
        binding.recordingsFolder.text = RecordingNames.folderName(this)?.let { folder ->
            getString(R.string.dpad_selected_recording_folder, folder)
        } ?: getString(R.string.dpad_recording_folder_setup)
        binding.recordingsStatus.setText(R.string.dpad_history_loading)
        ensureBackgroundThread {
            val items = NumberHistoryRepository.queryRecordings(applicationContext)
                .distinctBy { it.uri }
                .sortedByDescending { it.startedAt }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                recordings.clear()
                recordings.addAll(items)
                recordingsAdapter.notifyDataSetChanged()
                binding.recordingsStatus.text = if (items.isEmpty()) {
                    getString(R.string.dpad_no_recordings_found)
                } else {
                    getString(R.string.dpad_recordings_found, items.size)
                }
                if (items.isNotEmpty()) {
                    binding.recordingsList.post {
                        binding.recordingsList.findViewHolderForAdapterPosition(0)
                            ?.itemView?.requestFocusFromTouch()
                    }
                }
            }
        }
    }

    private fun playOrStop(entry: NativeCallRecording) {
        if (activeUri == entry.uri) {
            stopPlayback()
            return
        }
        stopPlayback()
        try {
            val next = MediaPlayer.create(this, entry.uri)
            if (next == null) {
                toast(R.string.dpad_history_playback_failed)
                return
            }
            player = next
            activeUri = entry.uri
            next.setOnCompletionListener { stopPlayback() }
            next.setOnErrorListener { _, _, _ ->
                stopPlayback()
                true
            }
            next.start()
            recordingsAdapter.notifyDataSetChanged()
        } catch (_: Exception) {
            stopPlayback()
            toast(R.string.dpad_history_playback_failed)
        }
    }

    private fun stopPlayback() {
        player?.let { old ->
            try { old.stop() } catch (_: Exception) {}
            old.release()
        }
        player = null
        activeUri = null
        if (!isDestroyed) recordingsAdapter.notifyDataSetChanged()
    }

    override fun onDestroy() {
        stopPlayback()
        super.onDestroy()
    }

    private inner class RecordingRows : RecyclerView.Adapter<RecordingRows.Holder>() {
        inner class Holder(val row: LinearLayout, val title: TextView, val subtitle: TextView) :
            RecyclerView.ViewHolder(row)

        override fun getItemCount(): Int = recordings.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val px = (14 * resources.displayMetrics.density).toInt()
            val py = (8 * resources.displayMetrics.density).toInt()
            val row = LinearLayout(this@RecordingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px, py, px, py)
                minimumHeight = (58 * resources.displayMetrics.density).toInt()
                isFocusable = true
                isClickable = true
                applyDpadFocusHighlight()
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            val title = TextView(this@RecordingsActivity).apply {
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(getProperTextColor())
                maxLines = 2
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            val subtitle = TextView(this@RecordingsActivity).apply {
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(getProperTextColor())
                alpha = 0.75f
            }
            row.addView(title)
            row.addView(subtitle)
            return Holder(row, title, subtitle)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val recording = recordings[position]
            holder.title.text = recording.fileName
            val whenText = DateFormat.getDateTimeInstance().format(Date(recording.startedAt))
            val playing = if (activeUri == recording.uri) "  ■" else "  ▶"
            holder.subtitle.text = whenText + playing
            holder.row.contentDescription = recording.fileName + ", " + whenText
            holder.row.setOnClickListener { playOrStop(recording) }
        }
    }
}
