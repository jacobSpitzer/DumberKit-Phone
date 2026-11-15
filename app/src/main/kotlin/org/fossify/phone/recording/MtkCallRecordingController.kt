package org.fossify.phone.recording

import android.telecom.Call
import android.util.Log

/**
 * Matches the OEM DOOV R77 Pro dialer's TelecomAdapter.toggleVoiceRecording().
 *
 * The stock com.android.dialer sends these exact events to the active android.telecom.Call
 * using the public Call.sendCallEvent API. MediaTek's privileged telephony implementation
 * remains responsible for handling recording and capturing both audio channels.
 *
 * sendCallEvent() does not acknowledge that the ConnectionService accepted or acted on it.
 */
object MtkCallRecordingController {
    private const val TAG = "DumberKitMtkRecorder"
    private const val EVENT_START = "mediatek.telecom.event.REQUEST_START_VOICE_RECORDING"
    private const val EVENT_STOP = "mediatek.telecom.event.REQUEST_STOP_VOICE_RECORDING"

    /**
     * Returns null when the event is dispatched without a local exception, NOT when recording
     * has been confirmed. Tests must verify that the device actually saved a recording.
     */
    fun request(call: Call?, start: Boolean): String? {
        if (call == null) return "No active call"
        val event = if (start) EVENT_START else EVENT_STOP
        return try {
            // The OEM stock Dialer passes a null extras Bundle for both commands.
            call.sendCallEvent(event, null)
            Log.i(TAG, "Sent OEM Call.sendCallEvent: $event (no success acknowledgement)")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Unable to dispatch OEM call event: $event", e)
            e.message ?: e.javaClass.simpleName
        }
    }
}
