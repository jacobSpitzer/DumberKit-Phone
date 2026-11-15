package org.fossify.phone.recording

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Experimental, non-privileged MediaTek Telecom integration.
 *
 * The DOOV R77 Pro contains the privileged MediaTek CallRecorderService. Its host Telecom
 * package references these actions. Sending them as an ordinary default-dialer application
 * MAY be rejected or silently ignored; this intentionally does not claim audio was recorded.
 *
 * This code does not install root tools, start Shizuku or change system settings.
 */
object MtkCallRecordingController {
    private const val TAG = "DumberKitMtkRecorder"
    private const val TELECOM_PACKAGE = "com.android.server.telecom"

    private const val ACTION_START = "mediatek.telecom.action.CALL_RECORD"
    private const val ACTION_STOP = "mediatek.telecom.action.STOP_CALL_RECORD"

    /**
     * @return null if Android accepted the broadcast submission. This does NOT confirm that
     *         Telecom received it or that recording actually started or stopped.
     *         Otherwise returns a short error message to display.
     */
    fun request(context: Context, start: Boolean): String? {
        val action = if (start) ACTION_START else ACTION_STOP
        return try {
            val intent = Intent(action).setPackage(TELECOM_PACKAGE)
            context.sendBroadcast(intent)
            Log.i(TAG, "Sent experimental Telecom action: $action (not an acknowledgement)")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Telecom refused action $action", e)
            e.message ?: e.javaClass.simpleName
        }
    }
}
