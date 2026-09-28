#!/usr/bin/env python3
"""Local emulator bridge for Volcano TTS 2.0. Run with VOLCENGINE_API_KEY in the host environment."""

import base64
import binascii
import json
import os
import threading
import urllib.error
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ENDPOINT = "https://openspeech.bytedance.com/api/v3/tts/unidirectional"
SPEAKER = "zh_female_vv_uranus_bigtts"
LANGUAGES = {
    "mandarin": {"explicit_language": "zh-cn"},
    "cantonese": {"explicit_language": "zh-cn", "explicit_dialect": "yue"},
    "english": {"explicit_language": "en"},
}
MAX_AUDIO_BYTES = 2_000_000
MAX_DEMO_REQUESTS = 100
MAX_DEMO_CHARACTERS = 5_000


def synthesize(text: str, language: str, api_key: str) -> bytes:
    if not api_key:
        raise ValueError("VOLCENGINE_API_KEY is not configured")
    if language not in LANGUAGES or not 1 <= len(text) <= 250:
        raise ValueError("Invalid language or text length")
    body = json.dumps({"req_params": {
        "text": text,
        "speaker": SPEAKER,
        "audio_params": {"format": "mp3", "sample_rate": 24000},
        "additions": json.dumps(LANGUAGES[language]),
    }}, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(ENDPOINT, data=body, headers={
        "Content-Type": "application/json",
        "X-Api-Key": api_key,
        "X-Api-Resource-Id": "seed-tts-2.0",
        "X-Api-Request-Id": str(uuid.uuid4()),
    }, method="POST")
    chunks = []
    total = 0
    completed = False
    with urllib.request.urlopen(request, timeout=20) as response:
        for line in response:
            if not line.strip():
                continue
            event = json.loads(line)
            if event.get("code") == 20000000:
                completed = True
                break
            if event.get("code") != 0:
                raise RuntimeError("Volcano TTS returned a nonzero code")
            encoded = event.get("data")
            if encoded:
                chunk = base64.b64decode(encoded, validate=True)
                total += len(chunk)
                if total > MAX_AUDIO_BYTES:
                    raise RuntimeError("Voice response exceeded the demo limit")
                chunks.append(chunk)
    if not completed or not chunks:
        raise RuntimeError("Volcano TTS did not complete with audio")
    return b"".join(chunks)


class VoiceHandler(BaseHTTPRequestHandler):
    budget_lock = threading.Lock()
    used_requests = 0
    used_characters = 0

    def do_GET(self):
        if self.path != "/health":
            self.send_error(404)
            return
        self.respond_json(200, {
            "ready": bool(os.environ.get("VOLCENGINE_API_KEY")),
            "remaining_demo_characters": MAX_DEMO_CHARACTERS - self.used_characters,
        })

    def do_POST(self):
        if self.path != "/tts":
            self.send_error(404)
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if not 1 <= length <= 2048:
                raise ValueError("Invalid request size")
            data = json.loads(self.rfile.read(length))
            if data["language"] not in LANGUAGES or not isinstance(data["text"], str) or not 1 <= len(data["text"]) <= 250:
                raise ValueError("Invalid language or text")
            api_key = os.environ.get("VOLCENGINE_API_KEY", "")
            if not api_key:
                self.respond_json(503, {"error": "Voice bridge is not configured"})
                return
            with self.budget_lock:
                if self.used_requests >= MAX_DEMO_REQUESTS or self.used_characters + len(data["text"]) > MAX_DEMO_CHARACTERS:
                    self.respond_json(429, {"error": "Demo budget exhausted"})
                    return
                type(self).used_requests += 1
                type(self).used_characters += len(data["text"])
            audio = synthesize(data["text"], data["language"], api_key)
        except (ValueError, KeyError, TypeError, json.JSONDecodeError):
            self.respond_json(400, {"error": "Invalid TTS request or missing API configuration"})
            return
        except (urllib.error.URLError, RuntimeError, TimeoutError, binascii.Error):
            self.respond_json(502, {"error": "Voice provider unavailable"})
            return
        self.send_response(200)
        self.send_header("Content-Type", "audio/mpeg")
        self.send_header("Content-Length", str(len(audio)))
        self.end_headers()
        self.wfile.write(audio)

    def respond_json(self, status: int, payload: dict):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        # Do not log guide copy or credentials in terminal output.
        pass


if __name__ == "__main__":
    server = ThreadingHTTPServer(("127.0.0.1", 8765), VoiceHandler)
    print("Voice bridge listening on 127.0.0.1:8765", flush=True)
    server.serve_forever()
