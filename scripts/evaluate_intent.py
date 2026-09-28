#!/usr/bin/env python3
"""Compare the conservative local fallback with the bounded Ark model on synthetic goals."""

import argparse
import json
import os
import statistics
import time
from pathlib import Path

from voice_proxy import classify_intent

FONT_EXCLUDED = ("短信", "微信", "whatsapp", "message", "send a text", "text me", "照片", "图片",
                 "photo", "zoom", "调小", "缩小", "smaller", "decrease", "颜色", "colour", "color")
FONT_SUPPORTED = ("把字调大", "字体调大", "字体变大", "字太小", "啲字太细", "放大啲字",
                  "increase font", "larger font", "font size bigger", "make the text bigger",
                  "text size bigger", "larger text", "bigger text")
BRIGHTNESS_EXCLUDED = ("自动亮度", "adaptive brightness", "调暗", "降低亮度", "太亮", "dimmer",
                       "decrease brightness", "brightness down", "font", "字体", "短信", "微信",
                       "whatsapp", "照片", "photo", "password", "payment")
BRIGHTNESS_SUPPORTED = ("调高亮度", "提高亮度", "屏幕太暗", "屏幕好暗", "光度太暗", "加光啲",
                        "increase brightness", "raise brightness", "screen too dim", "screen is too dark",
                        "make the screen brighter")


def local_fallback(text: str) -> dict:
    text = text.strip().lower()
    if not any(part in text for part in FONT_EXCLUDED) and any(part in text for part in FONT_SUPPORTED):
        return {"decision": "match", "task_id": "font_size"}
    if not any(part in text for part in BRIGHTNESS_EXCLUDED) and any(
            part in text for part in BRIGHTNESS_SUPPORTED):
        return {"decision": "match", "task_id": "screen_brightness"}
    return {"decision": "unsupported", "task_id": ""}


def summarize(cases, decisions):
    return {
        "exact": sum((case["expected"], case["expected_task_id"]) ==
                     (decision["decision"], decision["task_id"])
                     for case, decision in zip(cases, decisions)),
        "total": len(cases),
        "false_match": sum(case["expected"] != "match" and decision["decision"] == "match"
                           for case, decision in zip(cases, decisions)),
        "matched_supported": sum(case["expected"] == "match" and decision ==
                                 {"decision": "match", "task_id": case["expected_task_id"]}
                                 for case, decision in zip(cases, decisions)),
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--live", action="store_true", help="Call the configured Ark model")
    args = parser.parse_args()
    cases = json.loads((Path(__file__).parent / "intent_cases.json").read_text())
    baseline = [local_fallback(case["text"]) for case in cases]
    result = {"case_count": len(cases), "local_fallback": summarize(cases, baseline)}
    if args.live:
        key = os.environ.get("ARK_API_KEY", "")
        if not key:
            parser.error("ARK_API_KEY is required for --live")
        model = []
        durations = []
        for case in cases:
            start = time.monotonic()
            answer = classify_intent(case["text"], case["language"], key)
            durations.append(time.monotonic() - start)
            model.append(answer)
        result["ark_model"] = summarize(cases, model)
        result["ark_model"]["median_seconds"] = round(statistics.median(durations), 2)
        result["cases"] = [
            {**case, "local_fallback": local, "ark_model": ai, "seconds": round(seconds, 2)}
            for case, local, ai, seconds in zip(cases, baseline, model, durations)
        ]
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
