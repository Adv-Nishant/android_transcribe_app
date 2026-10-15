package dev.notune.transcribe;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.content.pm.PackageManager;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;

public class RecognizeActivity extends AppCompatActivity {

    private static final String TAG = "OfflineVoiceInput";

    static {
        try {
            System.loadLibrary("c++_shared");
            System.loadLibrary("android_transcribe_app");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native libraries", e);
        }
    }

    private TextView status;
    private boolean isRecording = false;
    private MicLevelView micLevel;
    private final AudioFocusPauser audioPauser = new AudioFocusPauser();
    private boolean pauseAudioActive = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.recognize_activity);

        // Keep the screen awake for the lifetime of this recording screen so it
        // never sleeps mid-capture and cuts the recording short.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        micLevel = findViewById(R.id.mic_level);
        status = findViewById(R.id.txt_status);

        findViewById(R.id.btn_close).setOnClickListener(v -> {
            // discard current recording
            if (isRecording) {
                isRecording = false;
                cancelRecording();   // new native method
            }
            if (pauseAudioActive) {
                audioPauser.abandon(this);
                pauseAudioActive = false;
            }
            setResult(Activity.RESULT_CANCELED);
            finish();
        });

        // Tap anywhere (or on mic) to stop
        findViewById(R.id.root).setOnClickListener(v -> finishRecording());

        // Permission check
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            status.setText(R.string.voice_need_mic);
            return;
        }

        initNative(this);
        isRecording = true;
        status.setText(R.string.voice_listening);
        if (isPauseAudioEnabled()) {
            audioPauser.request(this);
            pauseAudioActive = true;
        }
        startRecording(isAutoStopEnabled());
    }

    /** Stop capture and transcribe — used by both tap-to-stop and auto-stop. */
    private void finishRecording() {
        if (!isRecording) return;
        isRecording = false;
        status.setText(R.string.voice_processing);
        stopRecording();
        if (pauseAudioActive) {
            audioPauser.abandon(this);
            pauseAudioActive = false;
        }
    }

    // Called from Rust (monitor thread) when trailing silence is detected.
    public void onAutoStop() {
        runOnUiThread(this::finishRecording);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // The popup is no longer visible (user switched apps or went home).
        // It runs in its own task (singleTask), so it would otherwise keep
        // recording invisibly in the background and never reappear. Discard
        // and close so the next mic tap starts fresh. (Background recording
        // is a keyboard-only feature; a popup must not record unseen.)
        if (isRecording && !isFinishing()) {
            isRecording = false;
            try { cancelRecording(); } catch (Throwable t) { /* ignore */ }
            if (pauseAudioActive) {
                audioPauser.abandon(this);
                pauseAudioActive = false;
            }
            setResult(Activity.RESULT_CANCELED);
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (pauseAudioActive) {
            audioPauser.abandon(this);
            pauseAudioActive = false;
        }
        try { cleanupNative(this); } catch (Throwable t) { /* ignore */ }
    }

    // Called from Rust
    public void onStatusUpdate(String s) {
        runOnUiThread(() -> {
            final String shown;
            if ("Ready".equals(s)) {
                // The model-ready status can arrive after recording started.
                shown = isRecording ? getString(R.string.voice_listening) : s;
            } else if ("Listening...".equals(s)) {
                shown = getString(R.string.voice_listening);
            } else {
                shown = s;
            }
            status.setText(shown);
        });
    }

    // Called from Rust with 0..1
    public void onAudioLevel(float level) {
        runOnUiThread(() -> micLevel.setLevel(level));
    }

    // Called from Rust – keep same method name as IME for code reuse
    public void onTextTranscribed(String text) {
        runOnUiThread(() -> {
            if (text == null || text.trim().isEmpty()) {
                // Nothing was recognized (e.g. auto-stop after silence only).
                // Say so: otherwise the panel just vanishes as if it broke.
                Toast.makeText(getApplicationContext(), R.string.voice_no_speech,
                        Toast.LENGTH_SHORT).show();
                setResult(Activity.RESULT_CANCELED);
                finish();
                return;
            }

            deliverResult(text.trim());
        });
    }

    private void deliverResult(String text) {
        Intent request = getIntent();
        // Search boxes (e.g. Chrome's omnibox) want a query, not a sentence: a
        // trailing period would turn "example.com" into a search for "example.com.".
        if (RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH.equals(
                request.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL))
                && text.endsWith(".") && !text.endsWith("..")) {
            text = text.substring(0, text.length() - 1);
        }

        ArrayList<String> results = new ArrayList<>();
        results.add(text);

        Intent data = new Intent();
        data.putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, results);
        // Callers such as Chrome's voice search drop results that come without
        // one confidence score per hypothesis. The engine has no calibrated
        // score, so the single hypothesis is reported as confident.
        data.putExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES, new float[]{1.0f});

        // Callers may ask for the results via a PendingIntent instead of the
        // activity result (part of the RecognizerIntent contract).
        PendingIntent resultsIntent = request.getParcelableExtra(
                RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT);
        if (resultsIntent != null) {
            Intent fillIn = new Intent();
            Bundle extra = request.getBundleExtra(
                    RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT_BUNDLE);
            if (extra != null) fillIn.putExtras(extra);
            fillIn.putExtras(data);
            try {
                resultsIntent.send(this, Activity.RESULT_OK, fillIn);
            } catch (PendingIntent.CanceledException e) {
                Log.w(TAG, "Results PendingIntent was cancelled", e);
            }
        }

        setResult(Activity.RESULT_OK, data);
        finish();
    }

    private boolean isPauseAudioEnabled() {
        return new java.io.File(getFilesDir(), "pause_audio").exists();
    }

    /** Opt-in via the "Auto-stop after silence" setting (default off). */
    private boolean isAutoStopEnabled() {
        return new java.io.File(getFilesDir(), "auto_stop").exists();
    }

    // Native methods
    private native void initNative(RecognizeActivity activity);
    private native void cleanupNative(RecognizeActivity self);
    private native void startRecording(boolean autoStop);
    private native void stopRecording();
    private native void cancelRecording();
}
