# -*- coding: utf-8 -*-
"""Fallback daily reboot used by the Windows scheduled task.

The main service already reboots at the configured time. This script only
acts when the service did not (PC was asleep, service not running, etc.) by
checking the last_reboot_date marker written by the service.
"""
import os
import json
import datetime
import subprocess

BASE = os.path.dirname(os.path.abspath(__file__))
STATE = os.path.join(BASE, "state.json")
ADB = os.path.abspath(os.path.join(BASE, "..", "tools", "platform-tools", "adb.exe"))
# Box address is user-specific: set BOX_ADDR env var, e.g. 192.168.1.50:5555
DEV = os.environ.get("BOX_ADDR", "192.168.1.100:5555")
CREATE_NO_WINDOW = 0x08000000 if os.name == "nt" else 0


def run(args, timeout=10):
    try:
        return subprocess.run(args, capture_output=True, timeout=timeout,
                              creationflags=CREATE_NO_WINDOW).returncode
    except Exception:
        return -1


def main():
    try:
        with open(STATE, "r", encoding="utf-8") as f:
            st = json.load(f)
    except Exception:
        st = {}
    today = datetime.date.today().isoformat()
    if st.get("last_reboot_date") == today:
        return  # service already rebooted today
    run([ADB, "connect", DEV])
    run([ADB, "-s", DEV, "reboot"])
    st["last_reboot_date"] = today
    st["note"] = "计划任务执行每日重启"
    try:
        with open(STATE, "w", encoding="utf-8") as f:
            json.dump(st, f, ensure_ascii=False, indent=2)
    except Exception:
        pass


if __name__ == "__main__":
    main()
