import base64
import copy
import io
import json
import unittest
from unittest.mock import patch
import screen_planner as planner


def page():
    return {"goal": "Open this app's settings", "package": "org.example.reader", "snapshot_id": "a" * 64,
        "text_language": "mandarin", "voice_language": "cantonese", "image": "",
        "nodes": [{"id": "n0", "label": "Settings", "role": "Button", "clickable": True, "editable": False,
                   "scrollable": False, "enabled": True, "bounds": [0, 20, 200, 100], "state": "selected=false"}]}


def reply(**kwargs):
    return {"action": "tap", "node_id": "n0", "instruction": "请点设置。", "spoken_instruction": "请㩒设置。", **kwargs}


def provider_reply(decision="tap:n0"):
    return {"decision": decision, "instruction": "请点设置。", "spoken_instruction": "请㩒设置。"}


class ScreenPlannerTest(unittest.TestCase):
    def test_public_password_manager_name_does_not_block_an_app_store_page(self):
        data = page(); data["nodes"][0]["label"] = "AIPOS Password Manager"
        self.assertFalse(planner.blocked_request(data))
        data["nodes"][0]["label"] = "Enter your password"
        self.assertTrue(planner.blocked_request(data))
    def test_private_page_or_goal_is_blocked_before_cloud_including_optional_image(self):
        for label in ("Password", "user@example.com", "12345678", "付款"):
            data = page(); data["nodes"][0]["label"] = label
            data["image"] = base64.b64encode(b"\xff\xd8\xfftest\xff\xd9").decode()
            with patch.object(planner.urllib.request, "urlopen") as cloud:
                self.assertEqual(planner.plan(data, "test-key")["action"], "unsupported")
                cloud.assert_not_called()

    def test_provider_gets_grounded_context_and_independent_languages(self):
        captured = {}
        def fake_open(request, timeout):
            captured.update(json.loads(request.data))
            return io.BytesIO(json.dumps({"choices": [{"finish_reason": "stop", "message": {"content": json.dumps(provider_reply())}}]}).encode())
        with patch.object(planner.urllib.request, "urlopen", side_effect=fake_open):
            result = planner.plan(page(), "test-key")
        context = json.loads(captured["messages"][1]["content"][0]["text"])
        self.assertEqual(context["text_language"], "mandarin")
        self.assertEqual(context["voice_language"], "cantonese")
        self.assertEqual(result["snapshot_id"], "a" * 64)
        self.assertEqual(captured["model"], planner.MODEL)
        self.assertEqual(captured["thinking"], {"type": "disabled"})
        self.assertNotIn("test-key", json.dumps(captured))

    def test_nonexistent_disabled_wrong_kind_or_risky_target_is_rejected(self):
        for decision in (reply(node_id="invented"), reply(action="type"), reply(action="scroll_forward")):
            with self.assertRaises(RuntimeError): planner.validate_result(decision, page())
        for changed in ({"enabled": False}, {"label": "Delete all files"}):
            data = page(); data["nodes"][0].update(changed)
            with self.assertRaises(RuntimeError): planner.validate_result(reply(), data)

    def test_static_context_cannot_be_selected_through_the_provider_schema(self):
        data = page(); heading = copy.deepcopy(data["nodes"][0]); heading.update(id="n1", clickable=False)
        data["nodes"].append(heading)
        captured = {}
        def fake_open(request, timeout):
            captured.update(json.loads(request.data))
            return io.BytesIO(json.dumps({"choices": [{"finish_reason": "stop", "message": {"content": json.dumps(provider_reply())}}]}).encode())
        with patch.object(planner.urllib.request, "urlopen", side_effect=fake_open): planner.plan(data, "test-key")
        schema = captured["response_format"]["json_schema"]["schema"]
        self.assertEqual(schema["properties"]["decision"]["enum"], ["clarify", "complete", "unsupported", "tap:n0"])
        context = json.loads(captured["messages"][1]["content"][0]["text"])
        self.assertEqual(context["action_targets"]["tap"], ["n0"])
        self.assertEqual(context["action_targets"]["type"], [])
        self.assertIn("n1", [n["id"] for n in context["nodes"]])

    def test_coordinates_extra_fields_and_false_completion_target_are_rejected(self):
        for decision in (reply(x=10), reply(action="complete", node_id="n999"), reply(action="tap", instruction="Send a message")):
            with self.assertRaises(RuntimeError): planner.validate_result(decision, page())

    def test_completion_evidence_is_never_forwarded_as_an_action_target(self):
        result = planner.validate_result(reply(action="complete", node_id="n0"), page())
        self.assertEqual(result["action"], "complete")
        self.assertEqual(result["node_id"], "")
        self.assertEqual(result["snapshot_id"], page()["snapshot_id"])

    def test_duplicate_ids_and_bad_geometry_are_not_sent_to_model(self):
        for changed in ("duplicate", "bounds", "extra", "too_many"):
            data = page()
            if changed == "duplicate": data["nodes"] *= 2
            elif changed == "bounds": data["nodes"][0]["bounds"] = [10, 0, 5, 50]
            elif changed == "extra": data["nodes"][0]["private_text"] = "hidden"
            else: data["nodes"] *= 81
            with self.assertRaises(ValueError): planner.validate_request(data)

    def test_images_are_inline_only_and_bounded(self):
        for image in ("https://example.com/private.png", "not-base64", base64.b64encode(b"not-jpeg").decode(), "A" * 700001):
            data = page(); data["image"] = image
            with self.assertRaises(ValueError): planner.validate_request(data)

    def test_incomplete_or_malformed_provider_reply_does_not_become_advice(self):
        for provider in ({"choices": [{"finish_reason": "length", "message": {"content": json.dumps(reply())}}]}, {"choices": []}):
            with patch.object(planner.urllib.request, "urlopen", return_value=io.BytesIO(json.dumps(provider).encode())):
                with self.assertRaises(RuntimeError): planner.plan(page(), "test-key")

    def test_complete_and_clarify_require_no_target(self):
        for action in ("complete", "clarify", "unsupported"):
            self.assertEqual(planner.validate_result(reply(action=action, node_id=""), page())["action"], action)

    def test_focused_editors_do_not_get_repeated_tap_suggestions(self):
        data = page(); data["nodes"][0].update(editable=True, state="focused=true;has_text=false")
        self.assertEqual(planner.validate_result(reply(action="type"), data)["action"], "type")
        with self.assertRaises(RuntimeError): planner.validate_result(reply(action="tap"), data)
        data["nodes"][0]["state"] = "focused=false"
        self.assertEqual(planner.validate_result(reply(action="tap"), data)["action"], "tap")
        with self.assertRaises(RuntimeError): planner.validate_result(reply(action="type"), data)

    def test_provider_schema_binds_action_and_control_in_one_choice(self):
        data = page(); data["nodes"][0].update(editable=True, state="focused=true;has_text=false")
        captured = {}
        def fake_open(request, timeout):
            captured.update(json.loads(request.data))
            return io.BytesIO(json.dumps({"choices": [{"finish_reason": "stop", "message": {"content": json.dumps(provider_reply("type:n0"))}}]}).encode())
        with patch.object(planner.urllib.request, "urlopen", side_effect=fake_open):
            self.assertEqual(planner.plan(data, "test-key")["action"], "type")
        options = captured["response_format"]["json_schema"]["schema"]["properties"]["decision"]["enum"]
        self.assertIn("type:n0", options); self.assertNotIn("tap:n0", options)
        malformed = {"choices": [{"finish_reason": "stop", "message": {"content": json.dumps(provider_reply("tap:n0"))}}]}
        with patch.object(planner.urllib.request, "urlopen", return_value=io.BytesIO(json.dumps(malformed).encode())):
            with self.assertRaises(RuntimeError): planner.plan(data, "test-key")


if __name__ == "__main__": unittest.main()
