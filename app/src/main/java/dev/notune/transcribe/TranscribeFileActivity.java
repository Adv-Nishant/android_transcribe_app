package dev.notune.transcribe;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class TranscribeFileActivity extends AppCompatActivity {

    private static final String TAG = "OfflineVoiceInput";

    static {
        try {
            System.loadLibrary("c++_shared");
            System.loadLibrary("android_transcribe_app");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native libraries", e);
        }
    }

    private TextView statusText;
    private ProgressBar progressBar;
    private View progressArea;
    private ScrollView resultArea;
    private TextView resultText;
    private Button copyButton;
    private boolean decodeStarted = false;
    private boolean finished = false;
    private volatile boolean cancelled = false;
    private volatile long decodedSamples = 0;
    private Thread decodeThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.transcribe_file_activity);

        statusText = findViewById(R.id.txt_status);
        progressBar = findViewById(R.id.progress_bar);
        progressArea = findViewById(R.id.progress_area);
        resultArea = findViewById(R.id.result_area);
        resultText = findViewById(R.id.txt_result);
        copyButton = findViewById(R.id.btn_copy);

        findViewById(R.id.btn_close).setOnClickListener(v -> finish());

        copyButton.setOnClickListener(v -> {
            String text = resultText.getText().toString();
            if (!text.isEmpty()) {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("Transcription", text);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show();
            }
        });

        Uri audioUri = getAudioUri();
        if (audioUri == null) {
            statusText.setText("Error: No audio file received");
            progressBar.setVisibility(View.GONE);
            return;
        }

        statusText.setText("Loading model...");
        initNative(this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Another file was shared while this screen is open (singleTask):
        // start over with it instead of silently ignoring it.
        setIntent(intent);
        recreate();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Stops the decoder thread at the next block and the native side
        // before its next model run.
        cancelled = true;
        try { cleanupNative(this); } catch (Throwable t) { /* ignore */ }
    }

    private Uri getAudioUri() {
        Intent intent = getIntent();
        if (intent == null) return null;

        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            return intent.getParcelableExtra(Intent.EXTRA_STREAM);
        } else if (Intent.ACTION_VIEW.equals(action)) {
            return intent.getData();
        }
        return null;
    }

    // Called from Rust when model is ready
    public void onStatusUpdate(String status) {
        runOnUiThread(() -> {
            if (status != null && status.startsWith("Ready")) {
                // A later "Ready" (e.g. after a settings reload) must not
                // start a second decode.
                if (!decodeStarted) {
                    decodeStarted = true;
                    statusText.setText("Decoding audio...");
                    startDecodeAndTranscribe();
                }
            } else if (status != null && status.startsWith("Error")) {
                statusText.setText(status);
                progressBar.setVisibility(View.GONE);
            } else if (!decodeStarted) {
                statusText.setText(status);
            }
        });
    }

    // Called from Rust (decoder thread) with the text transcribed so far.
    public void onPartialText(String text) {
        runOnUiThread(() -> {
            if (finished) return;
            resultArea.setVisibility(View.VISIBLE);
            resultText.setText(text);
        });
    }

    // Called from Rust with transcription result
    public void onTextTranscribed(String text) {
        runOnUiThread(() -> {
            finished = true;
            if (text == null || text.trim().isEmpty()) {
                showError("No speech recognized in this file");
                return;
            }
            // Hide progress, show result
            progressArea.setVisibility(View.GONE);
            resultArea.setVisibility(View.VISIBLE);
            copyButton.setVisibility(View.VISIBLE);

            resultText.setText(text);

            // Auto-copy to clipboard
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("Transcription", text);
            clipboard.setPrimaryClip(clip);

            Toast.makeText(this, "Transcription copied to clipboard", Toast.LENGTH_LONG).show();
        });
    }

    private void startDecodeAndTranscribe() {
        Uri audioUri = getAudioUri();
        if (audioUri == null) {
            statusText.setText("Error: No audio file");
            return;
        }

        decodeThread = new Thread(() -> {
            AudioFileDecoder decoder = new AudioFileDecoder(this, audioUri);
            try {
                long produced = decoder.decode((samples, length) -> {
                    if (cancelled) return false;
                    postProgress(decoder.durationUs());
                    boolean keepGoing = pushSamples(this, samples, length);
                    decodedSamples += length;
                    return keepGoing && !cancelled;
                }, () -> cancelled);
                if (cancelled) return;
                if (produced == 0) {
                    showError("Error: Could not decode audio file");
                    return;
                }
                runOnUiThread(() -> statusText.setText("Transcribing..."));
                finishSamples(this);
            } catch (Exception e) {
                Log.e(TAG, "Error decoding audio", e);
                if (!cancelled) showError("Error: could not decode audio file (" + e.getMessage() + ")");
            }
        }, "transcribe-file");
        decodeThread.start();
    }

    /** Shows how far transcription got, against the file duration when known. */
    private void postProgress(long durationUs) {
        long doneSec = decodedSamples / AudioFileDecoder.TARGET_RATE;
        String msg = durationUs > 0
                ? "Transcribing... " + formatTime(doneSec) + " / " + formatTime(durationUs / 1_000_000)
                : "Transcribing... " + formatTime(doneSec);
        runOnUiThread(() -> {
            if (!finished) statusText.setText(msg);
        });
    }

    private static String formatTime(long seconds) {
        return String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private void showError(String message) {
        runOnUiThread(() -> {
            statusText.setText(message);
            progressBar.setVisibility(View.GONE);
        });
    }

    // Native methods
    private native void initNative(TranscribeFileActivity activity);
    private native void cleanupNative(TranscribeFileActivity self);
    private native boolean pushSamples(TranscribeFileActivity self, float[] samples, int length);
    private native void finishSamples(TranscribeFileActivity self);
}
