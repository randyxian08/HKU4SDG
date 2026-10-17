#!/usr/bin/env python3
"""Start the local AI/voice bridge without sourcing configuration as shell code."""
import importlib.util
import os
from pathlib import Path
import shlex
import sys
from http.server import ThreadingHTTPServer

from voice_proxy import VoiceHandler


def load_local_keys(path: Path, environ):
    """Accept the two documented single-line credentials; never evaluate shell text."""
    if not path.exists():
        return
    for line in path.read_text().splitlines():
        key, sep, value = line.strip().partition("=")
        if not sep or key not in {"ARK_API_KEY", "VOLCENGINE_API_KEY"}:
            continue
        parts = shlex.split(value, comments=True)
        if len(parts) > 1:
            raise ValueError("Credentials must be a single value")
        if parts and not environ.get(key):
            environ[key] = parts[0]


def main():
    try:
        load_local_keys(Path(__file__).resolve().parent.parent / ".env", os.environ)
    except (OSError, ValueError):
        sys.exit("Cannot read .env. Check the two single-line credential fields; values are not logged.")
    missing = [key for key in ("ARK_API_KEY", "VOLCENGINE_API_KEY") if not os.environ.get(key)]
    if missing:
        sys.exit("Missing configuration: " + ", ".join(missing) + ". See .env.example.")
    if importlib.util.find_spec("aiohttp") is None:
        sys.exit("Install scripts/requirements-voice.txt in the current Python environment first.")
    if sys.platform == "darwin" and Path("/etc/ssl/cert.pem").exists():
        os.environ.setdefault("SSL_CERT_FILE", "/etc/ssl/cert.pem")
    try:
        server = ThreadingHTTPServer(("127.0.0.1", 8765), VoiceHandler)
    except OSError:
        sys.exit("Port 8765 is unavailable. Check the existing bridge; no process was stopped.")
    print("AI/voice demo: http://127.0.0.1:8765 (emulator only). Ctrl-C stops it.", flush=True)
    print("Credentials are loaded; live service availability is checked separately with check-ai-demo.py --live.", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
