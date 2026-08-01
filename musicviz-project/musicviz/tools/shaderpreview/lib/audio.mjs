// Scriptable audio, shaped like `analysis/AudioFeatures.kt`.
//
// Every scene in the app is driven by an AudioFeatures snapshot per frame, so
// the preview is only meaningful if it can produce plausible ones. The three
// built-in models below are the three states a bug hides in: nothing playing
// (does the idle drive work at all?), something steady (does the style settle
// somewhere, or does it keep integrating?), and a hit (does the beat path
// fire, and does it recover?).
//
// `bands` is 64 wide and `waveform` 128, matching AudioFeatures.empty(); the
// 64x2 uAudioTex the shader family reads is built from them exactly as
// ShaderScene.update does.

const BANDS = 64;
const WAVEFORM = 128;

function base() {
  return {
    bands: new Float32Array(BANDS),
    waveform: new Float32Array(WAVEFORM),
    rms: 0, bass: 0, mid: 0, treble: 0,
    beat: false, beatStrength: 0, transient: 0, bpm: 0,
    macroEnergy: 0,
  };
}

/** AudioFeatures.motionImpulse, verbatim. */
export function motionImpulse(f) {
  const beatImpulse = !f.beat ? 0 : (f.beatStrength > 0 ? f.beatStrength : 1);
  return Math.max(beatImpulse, f.transient * 0.5);
}

/** Nothing playing. Exercises every scene's idle/synthetic drive. */
export function silence() {
  return () => base();
}

/**
 * A steady tone: constant level, no transients, no beat flag. The state a
 * style has to be able to SIT in - anything that grows without bound shows up
 * here and nowhere else.
 */
export function steadyTone({ level = 0.45, bassBias = 1.1, trebleBias = 0.6 } = {}) {
  return (t) => {
    const f = base();
    f.rms = level;
    f.macroEnergy = level;
    f.bass = level * bassBias;
    f.mid = level;
    f.treble = level * trebleBias;
    for (let i = 0; i < BANDS; i++) {
      // 1/f-ish spectrum with a slow wobble, so band-reading styles are not
      // handed a flat line (which several of them special-case away).
      f.bands[i] = level * (1.2 / (1 + i * 0.09)) * (0.85 + 0.15 * Math.sin(t * 0.8 + i * 0.4));
    }
    for (let i = 0; i < WAVEFORM; i++) {
      f.waveform[i] = level * Math.sin((i / WAVEFORM) * Math.PI * 8 + t * 6);
    }
    return f;
  };
}

/**
 * A beat impulse train at [bpm]: quiet between hits, a full-strength transient
 * plus a `beat` edge on them. `beat` is an EDGE in the app (FluidEmitters
 * edge-detects it), so it is true for exactly one frame per hit.
 */
export function beatImpulse({ bpm = 120, level = 0.5, peak = 1 } = {}) {
  const period = 60 / bpm;
  let lastBeatIndex = -1;
  return (t) => {
    const f = steadyTone({ level })(t);
    const idx = Math.floor(t / period);
    const phase = t - idx * period;
    const env = Math.exp(-phase / 0.12);
    f.bpm = bpm;
    f.beat = idx !== lastBeatIndex && phase < 0.05;
    if (f.beat) lastBeatIndex = idx;
    f.beatStrength = f.beat ? peak : 0;
    f.transient = peak * env;
    f.rms = Math.min(1.5, level + 0.5 * peak * env);
    f.macroEnergy = Math.min(1, level + 0.35 * peak * env);
    f.bass = Math.min(1.5, f.bass + 0.6 * peak * env);
    return f;
  };
}

/** A long-form arc: quiet verse, loud chorus. Drives HYPERSPACE's journey. */
export function musicArc({ bpm = 120, periodSeconds = 60 } = {}) {
  const beats = beatImpulse({ bpm });
  return (t) => {
    const f = beats(t);
    const arc = 0.5 - 0.5 * Math.cos((t / periodSeconds) * 2 * Math.PI);
    const level = 0.18 + 0.62 * arc;
    f.rms = Math.min(1.5, level + (f.rms - 0.5));
    f.macroEnergy = level;
    f.bass = Math.min(1.5, level * 1.15 + (f.transient * 0.6));
    f.mid = level;
    f.treble = level * 0.65;
    return f;
  };
}

export const MODELS = {
  silence,
  tone: steadyTone,
  beat: beatImpulse,
  arc: musicArc,
};

/** ShaderScene's 64x2 uAudioTex, row 0 bands, row 1 waveform. */
export function audioTexRows(f, drive) {
  const row = new Float32Array(64 * 2);
  for (let i = 0; i < 64; i++) {
    const v = f.bands[Math.floor((i * f.bands.length) / 64)] * drive;
    row[i] = Math.min(1.5, Math.max(0, v));
  }
  for (let i = 0; i < 64; i++) {
    const s = f.waveform[Math.floor((i * f.waveform.length) / 64)];
    row[64 + i] = Math.min(1, Math.max(0, s * drive * 0.5 + 0.5));
  }
  return row;
}
