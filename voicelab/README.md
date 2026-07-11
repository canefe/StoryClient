# VoiceLab

Calibrate each ElevenLabs voice's **safe DSP ranges** (pitch / gain / low-pass /
tone tilt) by ear, against real TTS, **without launching Minecraft**.

Voice DSP runs server-side: StoryMC's `VoiceFxProcessor` bakes per-character
values into the WAV after TTS, and chargen samples those values from a per-voice
`{min, max}` envelope. This tool exists to find good envelopes: drag the sliders
until the voice still sounds plausible at the extremes, then put those min/max
values in the chargen template's voice rule `fx` block (see
`story-chargen/src/story_chargen/seed.py`).

`dsp.py` is a 1:1 port of `VoiceFxProcessor` — what you hear here is what the
server will produce. Fetched lines are cached in `cache/` so you don't re-spend
API credits.

## Run

```bash
cd voicelab
uv sync                      # one-time: create env + install deps
uv run jupyter lab voicelab.ipynb
```

Then in the notebook: Setup → list voices → set `VOICE_ID`/`TEXT` → drag sliders.

## API key

Read automatically from (in order):
1. `ELEVENLABS_API_KEY` env var
2. `../../Story/run/plugins/Story/config.yml` (`elevenLabsApiKey:`)

If you get a `401`, the key is invalid/expired — set a fresh one:

```bash
ELEVENLABS_API_KEY=sk_... uv run jupyter lab voicelab.ipynb
```

## Keep in sync

If you change the DSP in `VoiceFxProcessor.kt`, mirror it in `dsp.py` (and the
shared constant `TONE_TILT_SPLIT`). The chain order is:
`pitch (resample) → low-pass → tone tilt → gain`, then int16 clamp.
