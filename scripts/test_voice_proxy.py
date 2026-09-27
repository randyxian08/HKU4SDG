import base64
import io
import json
import unittest
from unittest.mock import patch

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
            ]))

        with patch.object(voice_proxy.urllib.request, "urlopen", side_effect=fake_open):
            self.assertEqual(voice_proxy.synthesize("請㩒呢度", "cantonese", "test-key"), b"firstsecond")
        sent = json.loads(captured["request"].data)
        self.assertEqual(json.loads(sent["req_params"]["additions"])["explicit_dialect"], "yue")
        self.assertEqual(captured["request"].get_header("X-api-key"), "test-key")

    def test_missing_key_and_invalid_language_do_not_call_provider(self):
        with patch.object(voice_proxy.urllib.request, "urlopen") as request:
            with self.assertRaises(ValueError):
                voice_proxy.synthesize("hello", "english", "")
            with self.assertRaises(ValueError):
                voice_proxy.synthesize("hello", "unsupported", "test-key")
            request.assert_not_called()


if __name__ == "__main__":
    unittest.main()
