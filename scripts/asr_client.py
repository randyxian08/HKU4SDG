"""Bounded, emulator-only client for Volcengine's one-utterance ASR WebSocket.

Only 16 kHz mono PCM from a user-triggered recording is accepted. Audio stays in
memory on the host and is not logged or stored by this client.
"""

import asyncio
import gzip
import io
import json
import struct
import uuid
import wave

ASR_URL = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_nostream"
RESOURCE_ID = "volc.seedasr.sauc.duration"
LANGUAGE_CODES = {"mandarin": "zh-CN", "cantonese": "yue-CN", "english": "en-US"}
SAMPLE_RATE = 16_000
SEGMENT_BYTES = 6_400  # 200 ms of mono PCM16
MAX_PCM_BYTES = SAMPLE_RATE * 2 * 8


def _packet(kind: int, flags: int, payload: bytes, sequence: int) -> bytes:
    compressed = gzip.compress(payload)
    return bytes([0x11, (kind << 4) | flags, 0x11, 0x00]) + struct.pack(">iI", sequence, len(compressed)) + compressed


def _response(packet: bytes) -> tuple[int, bool, dict]:
    if len(packet) < 8 or packet[0] >> 4 != 1:
        raise RuntimeError("Invalid ASR response")
    kind, flags = packet[1] >> 4, packet[1] & 15
    position = (packet[0] & 15) * 4
    if flags & 1:
        position += 4  # sequence
    if flags & 4:
        position += 4  # event
    code = 0
    if kind == 15:
        code = struct.unpack_from(">i", packet, position)[0]
        position += 4
    elif kind != 9:
        raise RuntimeError("Unexpected ASR response type")
    size = struct.unpack_from(">I", packet, position)[0]
    position += 4
    if size > 64_000 or len(packet) - position != size:
        raise RuntimeError("Invalid ASR payload size")
    body = packet[position:]
    if packet[2] & 15 == 1:
        body = gzip.decompress(body)
    if packet[2] >> 4 != 1:
        raise RuntimeError("Unexpected ASR serialization")
    return code, bool(flags & 2), json.loads(body)


def _wav(pcm: bytes) -> bytes:
    output = io.BytesIO()
    with wave.open(output, "wb") as stream:
        stream.setnchannels(1)
        stream.setsampwidth(2)
        stream.setframerate(SAMPLE_RATE)
        stream.writeframes(pcm)
    return output.getvalue()


async def _recognize(pcm: bytes, language: str, api_key: str) -> str:
    import aiohttp  # optional: TTS and intent still run without this dependency

    payload = {
        "audio": {"format": "wav", "codec": "raw", "rate": SAMPLE_RATE,
                  "bits": 16, "channel": 1, "language": LANGUAGE_CODES[language]},
        "request": {"model_name": "bigmodel", "enable_itn": True,
                    "enable_punc": True, "enable_ddc": False},
    }
    headers = {"X-Api-Key": api_key, "X-Api-Resource-Id": RESOURCE_ID,
               "X-Api-Request-Id": str(uuid.uuid4())}
    timeout = aiohttp.ClientTimeout(total=25)
    async with aiohttp.ClientSession(timeout=timeout) as session:
        async with session.ws_connect(ASR_URL, headers=headers) as socket:
            await socket.send_bytes(_packet(1, 1, json.dumps(payload).encode(), 1))
            first = await socket.receive()
            if first.type != aiohttp.WSMsgType.BINARY:
                raise RuntimeError("ASR handshake failed")
            code, _, _ = _response(first.data)
            if code:
                raise RuntimeError("ASR rejected the request")

            async def send_audio():
                data = _wav(pcm)
                segments = [data[i:i + SEGMENT_BYTES] for i in range(0, len(data), SEGMENT_BYTES)]
                for index, segment in enumerate(segments, start=2):
                    final = index == len(segments) + 1
                    await socket.send_bytes(_packet(2, 3 if final else 1, segment,
                                                    -index if final else index))
                    await asyncio.sleep(0.2)

            sender = asyncio.create_task(send_audio())
            result = ""
            try:
                async for message in socket:
                    if message.type != aiohttp.WSMsgType.BINARY:
                        raise RuntimeError("ASR connection closed")
                    code, final, body = _response(message.data)
                    if code:
                        raise RuntimeError("ASR failed")
                    result = (body.get("result") or {}).get("text", result)
                    if final:
                        break
                await sender
            finally:
                if not sender.done():
                    sender.cancel()
            return result.strip()[:160]


def recognize(pcm: bytes, language: str, api_key: str) -> str:
    if not api_key or language not in LANGUAGE_CODES or len(pcm) % 2 or not 16_000 <= len(pcm) <= MAX_PCM_BYTES:
        raise ValueError("Invalid ASR input")
    return asyncio.run(_recognize(pcm, language, api_key))
