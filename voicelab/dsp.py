"""
1:1 Python port of StoryMC's server-side voice DSP chain.

Voice DSP runs SERVER-SIDE now: StoryMC bakes per-character pitch/gain/low-pass/
tone into the WAV after TTS. This module mirrors that so you can calibrate each
voice's safe [min, max] ranges here (by ear) before putting them in the chargen
template's voice rule `fx` block.

Keep this in sync with:
  Story/src/main/kotlin/com/canefe/story/audio/VoiceFxProcessor.kt

Signal chain per sample (matches Kotlin order exactly):
  pitch (linear-interp resample) -> one-pole low-pass -> tone tilt -> gain

The Kotlin mixer works in 16-bit integer samples and the final mix is clamped
to int16 range by AudioMixer.mixLoop, so we clamp here too to reproduce the
audible result (including any clipping you'd hear in-game).
"""

from __future__ import annotations

import numpy as np

# Fixed one-pole coefficient for the tone-tilt band split. Kept separate from
# the user low-pass so the two stages don't interfere. ~mid-band split.
# Must match VoiceFxProcessor.TONE_TILT_SPLIT.
TONE_TILT_SPLIT = 0.25


def _resample_linear(x: np.ndarray, ratio: float) -> np.ndarray:
    """Linear-interpolation resample, exactly like the mixInto read cursor.

    `x` is float samples, shape (n,) mono or (n, 2) stereo. The output advances
    a fractional read position by `ratio` per output sample (ratio>1 = higher
    pitch + faster, ratio<1 = lower + slower), matching `srcPos += ratio`.
    """
    n = x.shape[0]
    if n < 2 or ratio == 1.0:
        return x.copy()
    # Output length: how many steps of `ratio` fit before running out of source
    # (need base and base+1 available -> last valid pos is n-1).
    out_len = int(np.floor((n - 1) / ratio))
    pos = np.arange(out_len, dtype=np.float64) * ratio
    base = np.floor(pos).astype(np.int64)
    frac = (pos - base).astype(np.float64)
    nxt = np.minimum(base + 1, n - 1)
    if x.ndim == 1:
        return x[base] + (x[nxt] - x[base]) * frac
    frac = frac[:, None]
    return x[base] + (x[nxt] - x[base]) * frac


def _one_pole_lowpass(x: np.ndarray, lp: float) -> np.ndarray:
    """state += lp * (sample - state), per channel. lp in (0,1]; 1 = passthrough."""
    if lp >= 1.0:
        return x.copy()
    y = np.empty_like(x)
    if x.ndim == 1:
        state = 0.0
        for i in range(x.shape[0]):
            state += lp * (x[i] - state)
            y[i] = state
        return y
    state = np.zeros(x.shape[1], dtype=x.dtype)
    for i in range(x.shape[0]):
        state += lp * (x[i] - state)
        y[i] = state
    return y


def process(
    samples: np.ndarray,
    *,
    pitch: float = 1.0,
    gain: float = 1.0,
    low_pass: float = 1.0,
    tone_tilt: float = 0.0,
) -> np.ndarray:
    """Apply the full StoryClient voice DSP chain to int16-range float samples.

    Parameters mirror the in-game sliders 1:1:
      pitch     0.50 .. 2.00   (clamped)
      gain      0.50 .. 2.00   (clamped 0..4 like Kotlin; UI exposes 0.5..2.0)
      low_pass  0.05 .. 1.00   (clamped 0.02..1.0; 1.0 = open)
      tone_tilt -1.00 .. 1.00  (clamped; -1 bassy, +1 bright)

    `samples` is float array shape (n,) or (n, 2), values in int16 scale.
    Returns float array (same shape, resampled length) clamped to int16 range.
    """
    pitch = float(np.clip(pitch, 0.5, 2.0))
    gain = float(np.clip(gain, 0.0, 4.0))
    lp = float(np.clip(low_pass, 0.02, 1.0))
    tilt = float(np.clip(tone_tilt, -1.0, 1.0))

    x = samples.astype(np.float64)

    # 1) Pitch via linear-interp resample (couples pitch + speed, like the mod).
    x = _resample_linear(x, pitch)

    # 2) One-pole low-pass: this stage actually muffles. lp=1.0 = passthrough.
    if lp < 1.0:
        x = _one_pole_lowpass(x, lp)

    # 3) Tone tilt: independent bass/treble balance using its OWN fixed split
    #    (does not depend on the low-pass cutoff above). tilt=0 = passthrough.
    if tilt != 0.0:
        split = _one_pole_lowpass(x, TONE_TILT_SPLIT)
        high = x - split
        x = split * (1.0 - tilt) + high * (1.0 + tilt)

    # 4) Flat gain.
    x = x * gain

    # Mixer clamps the summed output to int16; reproduce that here.
    return np.clip(x, -32768.0, 32767.0)
