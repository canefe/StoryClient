"""ElevenLabs TTS fetch + cache, matching StoryClient's request settings.

Mirrors ElevenLabsAudioManager.generateSpeech:
  model_id = eleven_flash_v2_5
  voice_settings = {stability: 0.5, similarity_boost: 0.5}
  Accept: audio/mpeg  (returns MP3)

The API key is read from (in priority order):
  1. ELEVENLABS_API_KEY env var
  2. run/plugins/Story/config.yml  (elevenLabsApiKey: ...)
"""

from __future__ import annotations

import hashlib
import io
import os
import re
from pathlib import Path

import numpy as np
import requests
import soundfile as sf

BASE_URL = "https://api.elevenlabs.io/v1"
MODEL_ID = "eleven_flash_v2_5"
CACHE_DIR = Path(__file__).parent / "cache"

# Path to the plugin run config that holds the key (relative to this repo's sibling).
_DEFAULT_CONFIG = (
    Path(__file__).resolve().parents[2] / "Story" / "run" / "plugins" / "Story" / "config.yml"
)


def get_api_key() -> str:
    env = os.environ.get("ELEVENLABS_API_KEY")
    if env:
        return env.strip()
    if _DEFAULT_CONFIG.exists():
        text = _DEFAULT_CONFIG.read_text()
        m = re.search(r"elevenLabsApiKey:\s*(\S+)", text)
        if m:
            return m.group(1).strip()
    raise RuntimeError(
        "No ElevenLabs API key found. Set ELEVENLABS_API_KEY or check "
        f"{_DEFAULT_CONFIG}"
    )


def list_voices() -> list[dict]:
    """Return [{name, voice_id}, ...] for the account's voices.

    Note: a TTS-scoped key (like the plugin's) returns 401 here even though
    text-to-speech works fine — listing needs the `voices_read` permission.
    In that case this returns [] and you should set VOICE_ID manually.
    """
    r = requests.get(
        f"{BASE_URL}/voices",
        headers={"xi-api-key": get_api_key()},
        timeout=30,
    )
    if r.status_code == 401:
        print(
            "list_voices: 401 — your key is TTS-scoped (no voices_read). "
            "TTS still works; just set VOICE_ID manually below."
        )
        return []
    r.raise_for_status()
    return [
        {"name": v["name"], "voice_id": v["voice_id"]}
        for v in r.json().get("voices", [])
    ]


def _cache_path(text: str, voice_id: str) -> Path:
    key = hashlib.sha1(f"{voice_id}|{MODEL_ID}|{text}".encode()).hexdigest()[:16]
    safe = re.sub(r"[^a-zA-Z0-9]+", "_", text)[:40].strip("_")
    return CACHE_DIR / f"{safe}_{key}.mp3"


def fetch_mp3(text: str, voice_id: str, *, force: bool = False) -> bytes:
    """Fetch (or load cached) MP3 bytes for the given line + voice."""
    CACHE_DIR.mkdir(exist_ok=True)
    path = _cache_path(text, voice_id)
    if path.exists() and not force:
        return path.read_bytes()

    body = {
        "text": text,
        "model_id": MODEL_ID,
        "voice_settings": {"stability": 0.5, "similarity_boost": 0.5},
    }
    r = requests.post(
        f"{BASE_URL}/text-to-speech/{voice_id}",
        headers={
            "Accept": "audio/mpeg",
            "Content-Type": "application/json",
            "xi-api-key": get_api_key(),
        },
        json=body,
        timeout=60,
    )
    r.raise_for_status()
    path.write_bytes(r.content)
    return r.content


def fetch_samples(
    text: str, voice_id: str, *, force: bool = False
) -> tuple[np.ndarray, int]:
    """Fetch a line and decode to (samples, sample_rate).

    Returns float samples in int16 scale, shape (n,) mono or (n, 2) stereo,
    so they feed straight into dsp.process (which works in int16 scale).
    soundfile decodes MP3 via libsndfile (>= 1.1) / bundled support.
    """
    mp3 = fetch_mp3(text, voice_id, force=force)
    data, sr = sf.read(io.BytesIO(mp3), dtype="float32", always_2d=False)
    # soundfile returns float in [-1, 1]; scale to int16 range for the DSP.
    return data.astype(np.float64) * 32768.0, sr
