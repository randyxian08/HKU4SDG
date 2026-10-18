#!/usr/bin/env python3
"""Run one Android emulator check, preserving other enabled accessibility services.

Instrumentation restarts the target process. Rebind only Digital Handrail after the
runner reports it is waiting; this is test setup, not an app recovery mechanism.
"""
import argparse
import subprocess
import sys
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("check", choices=["reliability", "integration", "overlay", "phone", "a11y", "ai", "ai-offline", "screen", "screen-offline"])
parser.add_argument("--task", choices=["font", "brightness"], default="font")
parser.add_argument("--serial", default="emulator-5554")
args = parser.parse_args()
adb = ["adb", "-s", args.serial]

def shell(*command):
    return subprocess.check_output(adb + ["shell", *command], text=True).strip()

if shell("getprop", "ro.hardware") not in ("ranchu", "goldfish"):
    sys.exit("This test helper only rebinds services on an emulator.")
guide = "org.hku4sdg.handrail/.GuideAccessibilityService"
full = "org.hku4sdg.handrail/org.hku4sdg.handrail.GuideAccessibilityService"
enabled = shell("settings", "get", "secure", "enabled_accessibility_services").split(":")
if not any(item in (guide, full) for item in enabled):
    sys.exit("Enable Digital Handrail in Settings before running the check.")

def rebind_guide():
    # Never restore a permission the user switched off during a check.
    current = shell("settings", "get", "secure", "enabled_accessibility_services").split(":")
    if not any(item in (guide, full) for item in current):
        return False
    others = [item for item in current if item not in (guide, full, "", "null")]
    if others:
        shell("settings", "put", "secure", "enabled_accessibility_services", ":".join(others))
    else:
        shell("settings", "delete", "secure", "enabled_accessibility_services")
    time.sleep(0.3)
    shell("settings", "put", "secure", "enabled_accessibility_services", ":".join(others + [guide]))
    shell("settings", "put", "secure", "accessibility_enabled", "1")
    return True
command = adb + ["shell", "am", "instrument", "-w", "-e", "check", args.check]
if args.check == "overlay":
    command += ["-e", "task", args.task]
command += ["org.hku4sdg.handrail.test/org.hku4sdg.handrail.AccessibilityCheck"]
process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
output = []
rebound = False
try:
    for line in process.stdout:
        print(line, end="", flush=True)
        output.append(line)
        if "Waiting" in line and not rebound:
            rebound = rebind_guide()
    process.wait()
finally:
    completed = process.poll() is not None
    if not completed:
        process.terminate()
        process.wait(timeout=10)
    # Instrumentation finish kills the process too; leave the emulator usable.
    if completed:
        restored = rebind_guide()
        print("Test cleanup: guide rebound" if restored else "Test cleanup: guide is off; left unchanged", flush=True)
    else:
        print("Check interrupted: inspect device instrumentation before cleanup or retry", flush=True)
text = "".join(output)
sys.exit(0 if process.returncode == 0 and "\nPASS\n" in text and "FAIL" not in text else 1)
