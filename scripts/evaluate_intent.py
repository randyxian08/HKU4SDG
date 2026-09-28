#!/usr/bin/env python3
"""Compare the conservative local fallback with the bounded Ark model on synthetic goals."""

import argparse
import json
import os
import statistics
import time
from pathlib import Path

from voice_proxy import classify_intent

EXCLUDED = ("短信", "微信", "whatsapp", "message", "send a text", "text me", "照片", "图片",
            "photo", "zoom", "调小", "缩小", "smaller", "decrease", "颜色", "colour", "color")
SUPPORTED = ("把字调大", "字体调大", "字体变大", "字太小", "啲字太细", "放大啲字",
             "increase font", "larger font", "font size bigger", "make the text bigger",
             "text size bigger", "larger text", "bigger text")


def local_fallback(text: str) -> str:
    text = text.strip().lower()
    return "match" if not any(part in text for part in EXCLUDED) and any(
        part in text for part in SUPPORTED) else "unsupported"


def summarize(cases, decisions):
    return {
        "exact": sum(case["expected"] == decision for case, decision in zip(cases, decisions)),
        "total": len(cases),
        "false_match": sum(case["expected"] != "match" and decision == "match"
                           for case, decision in zip(cases, decisions)),
        "matched_supported": sum(case["expected"] == "match" and decision == "match"
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
            model.append(answer["decision"])
        result["ark_model"] = summarize(cases, model)
        result["ark_model"]["median_seconds"] = round(statistics.median(durations), 2)
        result["cases"] = [
            {**case, "local_fallback": local, "ark_model": ai, "seconds": round(seconds, 2)}
            for case, local, ai, seconds in zip(cases, baseline, model, durations)
        ]
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
