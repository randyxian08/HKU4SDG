"""HTTP boundary checks for the local, development-only screen bridge."""
import json
import os
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer
from unittest.mock import patch

import voice_proxy
from test_screen_planner import page


class ScreenEndpointTest(unittest.TestCase):
    def setUp(self):
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), voice_proxy.VoiceHandler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.client = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def tearDown(self):
        self.server.shutdown(); self.server.server_close(); self.thread.join(timeout=2)

    def post(self, data):
        request = urllib.request.Request(f"http://127.0.0.1:{self.server.server_port}/screen-step",
            data=json.dumps(data).encode(), headers={"Content-Type": "application/json"}, method="POST")
        try:
            response = self.client.open(request, timeout=2)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            return response.code, json.load(response)

    def test_missing_key_and_budget_exhaustion_never_call_model(self):
        with patch("screen_planner.plan") as model:
            with patch.dict(os.environ, {"ARK_API_KEY": ""}):
                self.assertEqual(self.post(page())[0], 503)
            with patch.dict(os.environ, {"ARK_API_KEY": "test-key"}), patch.object(voice_proxy.VoiceHandler, "used_screen_requests", 60):
                self.assertEqual(self.post(page())[0], 429)
            model.assert_not_called()

    def test_invalid_input_is_rejected_and_provider_error_details_are_not_exposed(self):
        with patch.dict(os.environ, {"ARK_API_KEY": "test-key"}), patch.object(voice_proxy.VoiceHandler, "used_screen_requests", 0), patch(
                "screen_planner.plan", side_effect=RuntimeError("private-provider-detail")) as model:
            self.assertEqual(self.post({"goal": "missing fields"})[0], 400)
            model.assert_not_called()
            status, body = self.post(page())
            self.assertEqual(status, 502)
            self.assertNotIn("private-provider-detail", json.dumps(body))


if __name__ == "__main__": unittest.main()
