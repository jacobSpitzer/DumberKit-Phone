package org.fossify.phone.recording

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Optional native PhoneRecord filename updates, granted once through SAF.
 * This never writes the audio contents and never needs root or MANAGE_EXTERNAL_STORAGE.
 * Only recordings initiated through our own Record button are eligible.
 */
object RecordingNames {
    private const val PREFS = "r77_recording_filenames"
    private const val TREE = "phone_record_tree"
    private const val ACTIVE = "active_session"
    private const val PENDING = "pending_sessions"
    private const val TAG = "R77RecordingNames"
    private const val MAX_PENDING = 30
    private const val MAX_SESSION_AGE_MS = 7L * 24L * 60L * 60L * 1000L
    private val timestampPattern = Regex("""\d{4}-\d{2}-\d{2}_\d{2}\.\d{2}\.\d{2}""")
    private val fileTypes = setOf("aac", "amr", "3gpp", "3gp", "m4a")

    fun folderUri(context: Context): Uri? = context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(TREE, null)?.let(Uri::parse)

    /**
     * Any user-selected *recording* directory is supported; different MTK ROMs
     * use different names. Do not guess a directory from its label.
     */
    fun useFolder(context: Context, uri: Uri): Boolean = try {
        val root = DocumentsContract.buildDocumentUriUsingTree(
            uri, DocumentsContract.getTreeDocumentId(uri)
        )
        val nameAndType = context.contentResolver.query(
            root, arrayOf(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ), null, null, null
        )?.use {
            if (it.moveToFirst()) (it.getString(0).orEmpty() to it.getString(1).orEmpty())
            else null
        }
        if (nameAndType == null || nameAndType.second != DocumentsContract.Document.MIME_TYPE_DIR) {
            false
        } else {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(TREE, uri.toString())
                .putString("recording_folder_display_name", nameAndType.first)
                .apply()
            true
        }
    } catch (e: Exception) {
        Log.w(TAG, "Recording folder permission was not granted", e)
        false
    }

    fun folderName(context: Context): String? = context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString("recording_folder_display_name", null)

    fun hasReadableFolder(context: Context): Boolean {
        val uri = folderUri(context) ?: return false
        return context.contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == uri && permission.isReadPermission
        }
    }

    /** Call only after native Telecom accepted dispatching REQUEST_START_VOICE_RECORDING. */
    fun markStart(context: Context, phoneNumber: String, displayName: String) {
        val item = JSONObject().put("number", phoneNumber)
            .put("name", displayName).put("started", System.currentTimeMillis())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(ACTIVE, item.toString()).apply()
    }

    /** A stopped recording is queued until MediaTek closes and indexes its audio file. */
    @Synchronized
    fun markStop(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val active = prefs.getString(ACTIVE, null) ?: return
        val session = try { JSONObject(active) } catch (_: Exception) { return }
        session.put("stopped", System.currentTimeMillis())
        val queued = readPending(prefs.getString(PENDING, null))
        queued.add(session)
        prefs.edit()
            .remove(ACTIVE)
            .putString(PENDING, JSONArray(queued.takeLast(MAX_PENDING)).toString())
            .apply()
    }

    private fun readPending(raw: String?): MutableList<JSONObject> {
        val list = mutableListOf<JSONObject>()
        try {
            val array = JSONArray(raw ?: "[]")
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.let(list::add)
            }
        } catch (_: Exception) {}
        return list
    }

    private fun sanitizedPart(text: String, maxLength: Int): String =
        text.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_")
            .replace(Regex("\\s+"), "_")
            .trim('_', '.', ' ')
            .take(maxLength)

    private fun readableDate(ms: Long) =
        SimpleDateFormat("yyyy-MM-dd_HH.mm.ss", Locale.US).format(Date(ms))

    /** Returns how many files were renamed; call off the main thread only. */
    @Synchronized
    fun renamePending(context: Context): Int {
        val tree = folderUri(context)?.takeIf { hasReadableFolder(context) } ?: return 0
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val sessions = readPending(prefs.getString(PENDING, null))
        if (sessions.isEmpty()) return 0

        data class FileInFolder(val uri: Uri, val name: String, val time: Long)
        val files = mutableListOf<FileInFolder>()
        try {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree)
            )
            context.contentResolver.query(children, arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            ), null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1).orEmpty()
                    val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    if (ext !in fileTypes) continue
                    val timeText = timestampPattern.find(name)?.value ?: continue
                    val time = try {
                        SimpleDateFormat("yyyy-MM-dd_HH.mm.ss", Locale.US)
                            .apply { isLenient = false }.parse(timeText)?.time
                    } catch (_: Exception) { null } ?: continue
                    files.add(FileInFolder(
                        DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)),
                        name, time
                    ))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not access user-selected PhoneRecord folder", e)
            return 0
        }

        var count = 0
        val keep = mutableListOf<JSONObject>()
        val now = System.currentTimeMillis()
        for (session in sessions) {
            val started = session.optLong("started")
            val stopped = session.optLong("stopped")
            val number = session.optString("number")
            val name = session.optString("name")
            if (started <= 0 || now - started > MAX_SESSION_AGE_MS) continue
            // Wait until the OEM has closed its audio file before changing its name.
            if (stopped <= started || number.isBlank() || now - stopped < 5_000L) {
                keep.add(session)
                continue
            }
            // Only associate an original timestamped OEM file with the known start
            // event for THIS call. Ambiguous timestamps are deliberately left alone.
            val candidates = files.filter {
                it.time in (started - 12_000L)..(started + 35_000L)
            }.sortedBy { abs(it.time - started) }
            val file = candidates.firstOrNull()
            val ambiguous = candidates.size > 1 &&
                abs(candidates[1].time - started) - abs(candidates[0].time - started) < 2_500L
            if (file == null || ambiguous) {
                keep.add(session)
                continue
            }
            val extension = file.name.substringAfterLast('.')
            val numberPart = sanitizedPart(number, 25).ifBlank { "Unknown" }
            val namePart = sanitizedPart(name, 40)
                .takeUnless { it == numberPart || it == number || it.isBlank() }
            val label = listOfNotNull(namePart, numberPart).joinToString("_")
            val newName = "${label}_${readableDate(file.time)}.${extension}"
            if (file.name == newName) continue
            try {
                val renamed = DocumentsContract.renameDocument(context.contentResolver, file.uri, newName)
                if (renamed != null) {
                    count++
                    files.remove(file)
                } else keep.add(session)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to rename MediaTek recording", e)
                keep.add(session)
            }
        }
        prefs.edit().putString(PENDING, JSONArray(keep).toString()).apply()
        return count
    }
}
