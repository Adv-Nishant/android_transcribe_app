//! Native backend for `TranscribeFileActivity` (transcribing a shared audio
//! file).
//!
//! The file is streamed: Java decodes it block by block and pushes 16 kHz
//! samples here, and every time more than one model run's worth of audio is
//! buffered, the oldest run is transcribed (split at a quiet point) and
//! dropped. Memory therefore stays bounded by about one run of audio no
//! matter how long the file is, and transcription overlaps with decoding.
//! Inference happens on the pushing (decoder) thread, which naturally
//! throttles decoding to the model's speed.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

use jni::objects::{JClass, JFloatArray, JObject};
use jni::sys::{jboolean, jint, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use once_cell::sync::Lazy;

use crate::engine;

struct FileSession {
    buffer: Vec<f32>,
    text: String,
    failed: bool,
}

struct TranscribeFileState {
    target_ref: jni::objects::GlobalRef,
    session: Arc<Mutex<FileSession>>,
    /// Set when the activity goes away; checked between model runs.
    cancelled: Arc<AtomicBool>,
}

static STATE: Lazy<Mutex<Option<TranscribeFileState>>> = Lazy::new(|| Mutex::new(None));

fn notify_status(env: &mut JNIEnv, obj: &JObject, msg: &str) {
    if let Ok(jmsg) = env.new_string(msg) {
        let _ = env.call_method(
            obj,
            "onStatusUpdate",
            "(Ljava/lang/String;)V",
            &[(&jmsg).into()],
        );
    }
}

fn notify_string(env: &mut JNIEnv, obj: &JObject, method: &str, text: &str) {
    if let Ok(jtxt) = env.new_string(text) {
        let _ = env.call_method(obj, method, "(Ljava/lang/String;)V", &[(&jtxt).into()]);
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_TranscribeFileActivity_initNative(
    env: JNIEnv,
    _class: JClass,
    activity: JObject,
) {
    android_logger::init_once(
        android_logger::Config::default().with_max_level(log::LevelFilter::Info),
    );

    let vm = env.get_java_vm().expect("Failed to get JavaVM");
    let vm_arc = Arc::new(vm);
    let target_ref = env
        .new_global_ref(&activity)
        .expect("Failed to ref activity");

    let state = TranscribeFileState {
        target_ref: target_ref.clone(),
        session: Arc::new(Mutex::new(FileSession {
            buffer: Vec::new(),
            text: String::new(),
            failed: false,
        })),
        cancelled: Arc::new(AtomicBool::new(false)),
    };
    if let Some(old) = STATE.lock().unwrap().replace(state) {
        old.cancelled.store(true, Ordering::SeqCst);
    }

    // Load engine in background
    std::thread::spawn(move || {
        let _ = engine::ensure_loaded_from_thread(&vm_arc, &target_ref);
    });
}

/// Only ends the session if it belongs to `activity`: the previous screen is
/// often destroyed after the next one was created, and must not cancel it.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_TranscribeFileActivity_cleanupNative(
    env: JNIEnv,
    _class: JClass,
    activity: JObject,
) {
    let mut guard = STATE.lock().unwrap();
    if guard
        .as_ref()
        .is_some_and(|s| env.is_same_object(&s.target_ref, &activity).unwrap_or(false))
    {
        if let Some(state) = guard.take() {
            state.cancelled.store(true, Ordering::SeqCst);
        }
    }
}

/// Session handles of `activity`'s session for its decoder thread, taken
/// without holding STATE during inference (the UI thread's cleanup must never
/// wait on a model run). None if the session belongs to another screen.
fn session_handles(
    env: &JNIEnv,
    activity: &JObject,
) -> Option<(Arc<Mutex<FileSession>>, Arc<AtomicBool>, jni::objects::GlobalRef)> {
    let guard = STATE.lock().unwrap();
    guard
        .as_ref()
        .filter(|s| env.is_same_object(&s.target_ref, activity).unwrap_or(false))
        .map(|s| (s.session.clone(), s.cancelled.clone(), s.target_ref.clone()))
}

/// Transcribes `piece` and appends it to the session text. Returns false on
/// error (reported to the activity) or cancellation.
fn transcribe_piece(
    env: &mut JNIEnv,
    target: &JObject,
    session: &mut FileSession,
    piece: Vec<f32>,
    cancelled: &AtomicBool,
) -> bool {
    if cancelled.load(Ordering::SeqCst) || session.failed {
        return false;
    }
    if !engine::is_current() && engine::ensure_loaded(env, target).is_err() {
        session.failed = true;
        return false;
    }
    let Some(eng) = engine::get_engine() else {
        notify_status(env, target, "Error: model not loaded");
        session.failed = true;
        return false;
    };
    match engine::transcribe_speech(&eng, piece) {
        Ok(text) => {
            let text = text.trim();
            if !text.is_empty() {
                if !session.text.is_empty() {
                    session.text.push(' ');
                }
                session.text.push_str(text);
            }
            true
        }
        Err(e) => {
            notify_status(env, target, &format!("Error: {}", e));
            session.failed = true;
            false
        }
    }
}

/// Appends decoded samples; transcribes full runs as they become available.
/// Returns false if decoding should stop (cancelled or failed).
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_TranscribeFileActivity_pushSamples(
    mut env: JNIEnv,
    _class: JClass,
    activity: JObject,
    samples_array: JFloatArray,
    length: jint,
) -> jboolean {
    let Some((session, cancelled, target_ref)) = session_handles(&env, &activity) else {
        return JNI_FALSE;
    };
    let len = length.max(0) as usize;
    let mut chunk = vec![0.0f32; len];
    if len > 0 && env.get_float_array_region(&samples_array, 0, &mut chunk).is_err() {
        log::error!("Failed to read float array from Java");
        return JNI_FALSE;
    }

    let mut session = session.lock().unwrap();
    session.buffer.extend_from_slice(&chunk);
    let target = target_ref.as_obj();
    while engine::exceeds_one_run(&session.buffer) {
        let take = engine::next_run_len(&session.buffer);
        let piece: Vec<f32> = session.buffer.drain(..take).collect();
        if !transcribe_piece(&mut env, target, &mut session, piece, &cancelled) {
            return JNI_FALSE;
        }
        let text = session.text.clone();
        notify_string(&mut env, target, "onPartialText", &text);
    }
    if cancelled.load(Ordering::SeqCst) || session.failed {
        JNI_FALSE
    } else {
        JNI_TRUE
    }
}

/// Transcribes the remaining audio and delivers the full text.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_TranscribeFileActivity_finishSamples(
    mut env: JNIEnv,
    _class: JClass,
    activity: JObject,
) {
    let Some((session, cancelled, target_ref)) = session_handles(&env, &activity) else {
        return;
    };
    let mut session = session.lock().unwrap();
    let target = target_ref.as_obj();
    let rest = std::mem::take(&mut session.buffer);
    if !rest.is_empty() && !transcribe_piece(&mut env, target, &mut session, rest, &cancelled) {
        return;
    }
    if session.failed || cancelled.load(Ordering::SeqCst) {
        return;
    }
    let text = std::mem::take(&mut session.text);
    notify_string(&mut env, target, "onTextTranscribed", &text);
}
