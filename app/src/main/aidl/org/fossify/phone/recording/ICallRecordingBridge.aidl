package org.fossify.phone.recording;

import android.os.ParcelFileDescriptor;

/** IPC boundary: implementation executes under the Shizuku shell UID. */
interface ICallRecordingBridge {
    void destroy() = 16777114;
    String startRecording(in ParcelFileDescriptor destination) = 1;
    String stopRecording() = 2;
}
