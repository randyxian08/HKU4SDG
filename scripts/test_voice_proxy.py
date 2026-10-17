import base64
import io
import http.client
import json
import os
import threading
import unittest
from unittest.mock import patch
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer

import voice_proxy


class VoiceProxyTest(unittest.TestCase):
    def test_cantonese_sets_dialect_and_joins_streamed_audio(self):
        captured = {}

        def fake_open(request, timeout):
            captured["request"] = request
            captured["timeout"] = timeout
            return io.BytesIO(b"\n".join([
                json.dumps({"code": 0, "data": base64.b64encode(b"first").decode()}).encode(),
                json.dumps({"code": 0, "data": base64.b64encode(b"second").decode()}).encode(),
                json.dumps({"code": 20000000, "message": "OK", "data": None}).encode(),
            ]))

        with patch.object(voice_proxy.urllib.request, "urlopen", side_effect=fake_open):
            self.assertEqual(voice_proxy.synthesize("請㩒呢度", "cantonese", "test-key"), b"firstsecond")
        sent = json.loads(captured["request"].data)
        self.assertEqual(json.loads(sent["req_params"]["additions"])["explicit_dialect"], "yue")
        self.assertEqual(captured["request"].get_header("X-api-key"), "test-key")

    def test_documented_audio_stream_accepts_clean_eof_without_legacy_marker(self):
        def fake_open(request, timeout):
            return io.BytesIO(json.dumps({"code": 0, "data": base64.b64encode(b"partial").decode()}).encode())

        with patch.object(voice_proxy.urllib.request, "urlopen", side_effect=fake_open):
            self.assertEqual(voice_proxy.synthesize("hello", "english", "test-key"), b"partial")

    def test_empty_stream_and_broken_http_stream_are_rejected(self):
        with patch.object(voice_proxy.urllib.request, "urlopen", return_value=io.BytesIO(b"")):
            with self.assertRaisesRegex(RuntimeError, "did not complete"):
                voice_proxy.synthesize("hello", "english", "test-key")

    def test_malformed_provider_data_is_not_an_invalid_user_request(self):
        for event in (b"{broken", b"[]", b'{"code":0,"data":"bad base64"}'):
            with self.subTest(event=event), patch.object(voice_proxy.urllib.request, "urlopen", return_value=io.BytesIO(event)):
                with self.assertRaises(RuntimeError):
                    voice_proxy.synthesize("hello", "english", "test-key")

        class Broken(io.BytesIO):
            def __next__(self):
                raise http.client.IncompleteRead(b"partial", 20)

        with patch.object(voice_proxy.urllib.request, "urlopen", return_value=Broken()):
            with self.assertRaises(http.client.IncompleteRead):
                voice_proxy.synthesize("hello", "english", "test-key")

    def test_missing_key_and_invalid_language_do_not_call_provider(self):
        with patch.object(voice_proxy.urllib.request, "urlopen") as request:
            with self.assertRaises(ValueError):
                voice_proxy.synthesize("hello", "english", "")
            with self.assertRaises(ValueError):
                voice_proxy.synthesize("hello", "unsupported", "test-key")
            request.assert_not_called()

    def test_intent_uses_only_submitted_goal_and_validates_approved_id(self):
        captured = {}

        def fake_open(request, timeout):
            captured["request"] = request
            captured["timeout"] = timeout
            return io.BytesIO(json.dumps({"choices": [{"finish_reason": "stop", "message": {
                "content": '{"decision":"match","task_id":"font_size"}'
            }}]}).encode())

        with patch.object(voice_proxy.urllib.request, "urlopen", side_effect=fake_open):
            self.assertEqual(voice_proxy.classify_intent("啲字太细", "cantonese", "test-ark-key"),
                             {"decision": "match", "task_id": "font_size"})
        sent = json.loads(captured["request"].data)
        self.assertEqual(sent["model"], voice_proxy.ARK_MODEL)
        self.assertEqual(sent["messages"][1]["content"], "Input language: cantonese\nUser goal: 啲字太细")
        self.assertEqual(sent["response_format"]["type"], "json_schema")
        self.assertNotIn("screen", sent["messages"][1]["content"].lower())

    def test_intent_rejects_model_created_task_or_incomplete_response(self):
        for content, finish in [
            ('{"decision":"match","task_id":"make_payment"}', "stop"),
            ('{"decision":"match","task_id":"font_size"}', "length"),
            ('{"decision":"unsupported","task_id":"font_size"}', "stop"),
        ]:
            answer = {"choices": [{"finish_reason": finish, "message": {"content": content}}]}
            with self.subTest(content=content, finish=finish), patch.object(
                    voice_proxy.urllib.request, "urlopen", return_value=io.BytesIO(json.dumps(answer).encode())):
                with self.assertRaises(RuntimeError):
                    voice_proxy.classify_intent("hello", "english", "test-ark-key")

    def test_intent_accepts_only_reviewed_brightness_task(self):
        answer = {"choices": [{"finish_reason": "stop", "message": {
            "content": '{"decision":"match","task_id":"screen_brightness"}'}}]}
        with patch.object(voice_proxy.urllib.request, "urlopen",
                          return_value=io.BytesIO(json.dumps(answer).encode())):
            self.assertEqual(voice_proxy.classify_intent("Make the screen brighter", "english", "test-ark-key"),
                             {"decision": "match", "task_id": "screen_brightness"})

    def test_intent_endpoint_fails_closed_without_ark_key(self):
        server = ThreadingHTTPServer(("127.0.0.1", 0), voice_proxy.VoiceHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            request = urllib.request.Request(
                f"http://127.0.0.1:{server.server_port}/intent",
                data=b'{"text":"make the text bigger","language":"english"}',
                headers={"Content-Type": "application/json"}, method="POST")
            with patch.dict(os.environ, {"ARK_API_KEY": ""}):
                with self.assertRaises(urllib.error.HTTPError) as error:
                    urllib.request.urlopen(request, timeout=2)
            self.assertEqual(error.exception.code, 503)
            error.exception.close()
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

    def test_sensitive_goal_is_blocked_before_provider(self):
        server = ThreadingHTTPServer(("127.0.0.1", 0), voice_proxy.VoiceHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            request = urllib.request.Request(
                f"http://127.0.0.1:{server.server_port}/intent",
                data=b'{"text":"please enter password 12345678","language":"english"}',
                headers={"Content-Type": "application/json"}, method="POST")
            with patch.dict(os.environ, {"ARK_API_KEY": "test-key"}), patch.object(
                    voice_proxy, "classify_intent") as provider:
                with urllib.request.urlopen(request, timeout=2) as response:
                    self.assertEqual(json.load(response), {"decision": "unsupported", "task_id": ""})
                provider.assert_not_called()
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

    def test_asr_accepts_bounded_pcm_and_rejects_invalid_audio(self):
        server = ThreadingHTTPServer(("127.0.0.1", 0), voice_proxy.VoiceHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            base = f"http://127.0.0.1:{server.server_port}/asr"
            with patch.dict(os.environ, {"VOLCENGINE_API_KEY": "test-key"}), patch(
                    "asr_client.recognize", return_value="我想收藏網頁") as provider:
                request = urllib.request.Request(base, data=b"\0" * 16000,
                    headers={"X-Guide-Language": "cantonese"}, method="POST")
                with urllib.request.urlopen(request, timeout=2) as response:
                    self.assertEqual(json.load(response), {"text": "我想收藏網頁"})
                provider.assert_called_once_with(b"\0" * 16000, "cantonese", "test-key")
                for audio, language in [(b"\0" * 15998, "english"),
                                        (b"\0" * 16001, "english"),
                                        (b"\0" * 16000, "unlisted")]:
                    with self.subTest(size=len(audio), language=language):
                        bad = urllib.request.Request(base, data=audio,
                            headers={"X-Guide-Language": language}, method="POST")
                        with self.assertRaises(urllib.error.HTTPError) as error:
                            urllib.request.urlopen(bad, timeout=2)
                        self.assertEqual(error.exception.code, 400)
                        error.exception.close()
                self.assertEqual(provider.call_count, 1)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)


if __name__ == "__main__":
    unittest.main()
