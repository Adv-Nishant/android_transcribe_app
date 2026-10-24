use std::path::Path;

/// Native inference implementation selected for a model.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BackendKind {
    /// Existing transcribe.cpp integration. This remains the default for all
    /// models not explicitly recognized as IndicConformer Hindi.
    TranscribeCpp,
    /// Hindi IndicConformer models require the compatible IndicConformer C API.
    IndicConformerHindi,
}

/// Selects a backend from an explicit, narrow model-family identifier.
///
/// GGUF is only a container format, so the extension must never determine the
/// backend. The current Hindi artifact has a stable family/name identifier;
/// keep this match intentionally narrow until its GGUF metadata is verified.
pub fn for_model(path: &Path) -> BackendKind {
    let name = path
        .file_name()
        .and_then(|name| name.to_str())
        .unwrap_or_default()
        .to_ascii_lowercase();
    if name == "indicconformer-hindi.q8_0.gguf" {
        BackendKind::IndicConformerHindi
    } else {
        BackendKind::TranscribeCpp
    }
}

#[cfg(test)]
mod tests {
    use super::{for_model, BackendKind};
    use std::path::Path;

    #[test]
    fn existing_models_stay_on_transcribe_cpp() {
        for name in [
            "parakeet-tdt-0.6b-v3-Q4_K_M.gguf",
            "whisper-small-Q8_0.gguf",
            "some-future-model.gguf",
        ] {
            assert_eq!(for_model(Path::new(name)), BackendKind::TranscribeCpp);
        }
    }

    #[test]
    fn exact_hindi_model_selects_indic_backend() {
        assert_eq!(
            for_model(Path::new("/models/indicconformer-hindi.q8_0.gguf")),
            BackendKind::IndicConformerHindi
        );
    }

    #[test]
    fn near_matches_are_not_routed_speculatively() {
        assert_eq!(
            for_model(Path::new("indicconformer-hindi.f32.gguf")),
            BackendKind::TranscribeCpp
        );
    }
}
