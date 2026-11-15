package org.fossify.phone.helpers

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.provider.MediaStore
import android.telephony.PhoneNumberUtils
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Locale

/** One real call-log row, never a synthetic grouping. */
data class NumberHistoryEntry(
    val id: Long,
    val number: String,
    val startedAt: Long,
    val durationSeconds: Long,
    val type: Int
)

data class NativeCallRecording(val uri: Uri, val fileName: String, val startedAt: Long)

/**
 * Adapted from NovaDial's per-number timeline concept, with a direct, background
 * CallLog query and no dependency on the current recents limit or grouping preference.
 */
object NumberHistoryRepository {
    private const val TAG = "R77NumberHistory"
    private val projection = arrayOf(
        CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.DATE,
        CallLog.Calls.DURATION, CallLog.Calls.TYPE
    )

    @Suppress("DEPRECATION")
    fun queryCalls(context: Context, requestedNumber: String): List<NumberHistoryEntry> {
        if (requestedNumber.isBlank() ||
            context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED
        ) return emptyList()

        val result = ArrayList<NumberHistoryEntry>()
        try {
            // NovaDial's approach uses an unbounded history rather than the Recents 100-row limit.
            // One scan is sufficient; it avoids repeated contacts lookup per call-log row.
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI, projection, null, null, "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                val id = cursor.getColumnIndexOrThrow(CallLog.Calls._ID)
                val number = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val started = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
                val duration = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                val type = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                while (cursor.moveToNext()) {
                    val candidate = cursor.getString(number).orEmpty()
                    if (candidate.isNotBlank() && samePhoneNumber(candidate, requestedNumber)) {
                        result += NumberHistoryEntry(
                            id = cursor.getLong(id),
                            number = candidate,
                            startedAt = cursor.getLong(started),
                            durationSeconds = cursor.getLong(duration),
                            type = cursor.getInt(type)
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read call log", e)
        }
        return result
    }

    private fun samePhoneNumber(a: String, b: String): Boolean {
        val x = PhoneNumberUtils.normalizeNumber(a).filter(Char::isDigit)
        val y = PhoneNumberUtils.normalizeNumber(b).filter(Char::isDigit)
        if (x.isEmpty() || y.isEmpty()) return false
        // Require at least 9 matching digits to avoid the Android API's loose
        // seven-digit comparison accidentally merging different numbers.
        return x == y || (x.length >= 9 && y.length >= 9 && x.takeLast(9) == y.takeLast(9))
    }

    fun canReadAudio(context: Context): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * The DOOV MediaTek recorder publishes audio files in PhoneRecord. Filename-derived
     * timestamps are preferred over MediaStore DATE_ADDED (which can be delayed).
     * No file is renamed, copied, or deleted.
     */
    fun queryRecordings(context: Context): List<NativeCallRecording> {
        if (!canReadAudio(context)) return emptyList()
        val result = ArrayList<NativeCallRecording>()
        try {
            val columns = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.RELATIVE_PATH
            )
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                columns,
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf("%PhoneRecord%"),
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val addedIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIndex).orEmpty()
                    if (!name.endsWith(".aac", true) &&
                        !name.endsWith(".amr", true) &&
                        !name.endsWith(".3gpp", true) &&
                        !name.endsWith(".3gp", true) &&
                        !name.endsWith(".m4a", true)
                    ) continue
                    val timestamp = recordingTimestamp(name)
                        ?: cursor.getLong(addedIndex) * 1000L
                    val uri = android.content.ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        cursor.getLong(idIndex)
                    )
                    result += NativeCallRecording(uri, name, timestamp)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not search PhoneRecord audio", e)
        }
        return result
    }

    private fun recordingTimestamp(fileName: String): Long? {
        // Example native DOOV filename: 2026-11-15_21.43.57.aac
        val pattern = Regex("""\d{4}-\d{2}-\d{2}_\d{2}\.\d{2}\.\d{2}""")
        val text = pattern.find(fileName)?.value ?: return null
        return try {
            SimpleDateFormat("yyyy-MM-dd_HH.mm.ss", Locale.US).apply {
                isLenient = false
            }.parse(text)?.time
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Time-based matching is necessarily approximate for the OEM's timestamp-only filenames.
     * Pick at most one nearby recording per call. Never use a contact-number guess.
     */
    fun associate(
        calls: List<NumberHistoryEntry>,
        recordings: List<NativeCallRecording>
    ): Map<Long, NativeCallRecording> {
        val matches = HashMap<Long, NativeCallRecording>()
        val unused = recordings.toMutableList()
        for (call in calls.sortedByDescending { it.startedAt }) {
            val end = call.startedAt + (call.durationSeconds.coerceAtLeast(20L) * 1000L) + 5000L
            val candidate = unused
                .filter { it.startedAt >= call.startedAt - 5000L && it.startedAt <= end }
                .minByOrNull { kotlin.math.abs(it.startedAt - call.startedAt) }
            if (candidate != null) {
                matches[call.id] = candidate
                unused.remove(candidate)
            }
        }
        return matches
    }
}
