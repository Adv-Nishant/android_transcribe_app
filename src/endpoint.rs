//! Energy-based speech endpointing, shared by the voice popup's auto-stop and
//! the RecognitionService, and trimming of quiet stretches around finished
//! recordings before they are transcribed.
//!
//! Works on fixed 20 ms frames (callback buffer sizes differ between devices)
//! and compares each frame's energy to an adaptive noise floor: a low
//! percentile of the last two seconds. Because the floor follows the quiet
//! parts of whatever is recorded, it adapts to loud steady background noise
//! (fan, car, café) as well as to quiet or loud microphones, instead of
//! relying on absolute thresholds that some devices never cross. Time is
//! measured in audio samples, so the result doesn't depend on thread timing.

use std::collections::VecDeque;

const SAMPLE_RATE: u64 = 16_000;
/// 20 ms frames.
const FRAME: usize = 320;
/// Noise floor window: the last 2 s of frames.
const FLOOR_FRAMES: usize = 100;
/// Percentile of the window used as the floor (robust to dropout frames).
const FLOOR_PERCENTILE: f32 = 0.1;
/// A frame is voiced this far above the noise floor.
const SPEECH_DB: f32 = 10.0;
/// ...and above this absolute level (ignores near-silent hiss).
const MIN_SPEECH_DB: f32 = -55.0;
/// Consecutive voiced frames (60 ms) that count as speech, not a click.
const MIN_VOICED_RUN: u32 = 3;
/// Frames below this are digital silence (many devices deliver zeros while
/// the input stream warms up); they would drag the noise floor far below the
/// real background, so they are skipped.
const DIGITAL_SILENCE_DB: f32 = -90.0;

/// Audio kept before the first and after the last speech when trimming.
const TRIM_MARGIN: usize = 16_000;
/// Quiet stretches shorter than this are left alone.
const MIN_TRIM: usize = 2 * 16_000;

/// The part of a finished recording worth transcribing. Long quiet stretches
/// before the first and after the last speech are cut, keeping a margin:
/// after ~10 s of quiet the model drops or garbles a short phrase, and it
/// invents sentences in long trailing silence (the user paused before
/// speaking, or forgot to stop). Unlike the live endpointer, the noise floor
/// is taken over the whole recording, so speech right at the start still
/// stands out. Empty if the recording is digital silence only; the whole
/// recording if no speech is found (very quiet speech is left to the model).
pub fn speech_range(samples: &[f32]) -> std::ops::Range<usize> {
    let mut prev: Option<f32> = None;
    let smoothed: Vec<Option<f32>> = samples
        .chunks(FRAME)
        .map(|frame| {
            let energy = frame.iter().map(|x| x * x).sum::<f32>() / frame.len() as f32;
            let db = 10.0 * (energy + 1e-10).log10();
            if db < DIGITAL_SILENCE_DB {
                prev = None;
                return None;
            }
            let s = prev.map_or(db, |p| 0.5 * p + 0.5 * db);
            prev = Some(s);
            Some(s)
        })
        .collect();

    let mut live: Vec<f32> = smoothed.iter().flatten().copied().collect();
    if live.is_empty() {
        return 0..0;
    }
    live.sort_by(|a, b| a.total_cmp(b));
    let floor = live[((live.len() - 1) as f32 * FLOOR_PERCENTILE) as usize];

    let (mut first, mut last, mut run) = (None, 0, 0);
    for (i, s) in smoothed.iter().enumerate() {
        let voiced = s.is_some_and(|s| s > floor + SPEECH_DB && s > MIN_SPEECH_DB);
        run = if voiced { run + 1 } else { 0 };
        if run >= MIN_VOICED_RUN as usize {
            first.get_or_insert(i + 1 - run);
            last = i + 1;
        }
    }
    let Some(first) = first else {
        return 0..samples.len();
    };
    let mut start = (first * FRAME).saturating_sub(TRIM_MARGIN);
    let mut end = (last * FRAME + TRIM_MARGIN).min(samples.len());
    if start < MIN_TRIM {
        start = 0;
    }
    if samples.len() - end < MIN_TRIM {
        end = samples.len();
    }
    start..end
}

pub struct Endpointer {
    partial: Vec<f32>,
    /// Smoothed frame energies (dB) of the floor window.
    history: VecDeque<f32>,
    smoothed: Option<f32>,
    voiced_run: u32,
    speech_started: bool,
    frames: u64,
    last_voice_frame: u64,
}

impl Default for Endpointer {
    fn default() -> Self {
        Self::new()
    }
}

impl Endpointer {
    pub fn new() -> Self {
        Endpointer {
            partial: Vec::with_capacity(FRAME),
            history: VecDeque::with_capacity(FLOOR_FRAMES + 1),
            smoothed: None,
            voiced_run: 0,
            speech_started: false,
            frames: 0,
            last_voice_frame: 0,
        }
    }

    /// Feeds 16 kHz samples. Returns true when this call detected the start
    /// of speech (only once per endpointer).
    pub fn push(&mut self, samples: &[f32]) -> bool {
        let was_started = self.speech_started;
        let mut rest = samples;
        while !rest.is_empty() {
            let take = (FRAME - self.partial.len()).min(rest.len());
            self.partial.extend_from_slice(&rest[..take]);
            rest = &rest[take..];
            if self.partial.len() == FRAME {
                let energy = self.partial.iter().map(|x| x * x).sum::<f32>() / FRAME as f32;
                self.partial.clear();
                self.frame(10.0 * (energy + 1e-10).log10());
            }
        }
        !was_started && self.speech_started
    }

    fn frame(&mut self, db: f32) {
        self.frames += 1;
        if db < DIGITAL_SILENCE_DB {
            self.voiced_run = 0;
            self.smoothed = None;
            return;
        }
        // Light smoothing so single noisy frames don't decide anything.
        let s = match self.smoothed {
            Some(prev) => 0.5 * prev + 0.5 * db,
            None => db,
        };
        self.smoothed = Some(s);

        let floor = self.floor().unwrap_or(s);
        let voiced = s > floor + SPEECH_DB && s > MIN_SPEECH_DB;
        self.voiced_run = if voiced { self.voiced_run + 1 } else { 0 };
        if self.voiced_run >= MIN_VOICED_RUN {
            self.speech_started = true;
            self.last_voice_frame = self.frames;
        }

        self.history.push_back(s);
        if self.history.len() > FLOOR_FRAMES {
            self.history.pop_front();
        }
    }

    fn floor(&self) -> Option<f32> {
        if self.history.is_empty() {
            return None;
        }
        let mut sorted: Vec<f32> = self.history.iter().copied().collect();
        sorted.sort_by(|a, b| a.total_cmp(b));
        let idx = ((sorted.len() - 1) as f32 * FLOOR_PERCENTILE) as usize;
        Some(sorted[idx])
    }

    pub fn speech_started(&self) -> bool {
        self.speech_started
    }

    /// Milliseconds of audio since the last speech (since the start if none).
    pub fn silence_ms(&self) -> u64 {
        (self.frames - self.last_voice_frame) * FRAME as u64 * 1000 / SAMPLE_RATE
    }
}
