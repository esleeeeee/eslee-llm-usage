"""Read Codex usage via official local app-server JSON-RPC.

Does not open auth.json, cookies, or tokens. The Codex CLI process keeps
credentials in its own session and returns only usage fields.
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import time
from typing import Any

CLIENT = {
    "name": "eslee_llm_usage_poc",
    "title": "eslee LLM Usage Feasibility POC",
    "version": "0.0.1",
}


def redact(obj: Any) -> Any:
    sensitive = {
        "accessToken", "refreshToken", "idToken", "token", "authorization",
        "email", "emailAddress", "chatgptAccountId", "accountId", "userId",
        "name", "displayName", "phoneNumber",
    }
    if isinstance(obj, dict):
        out = {}
        for k, v in obj.items():
            if k in sensitive or "token" in k.lower() or "secret" in k.lower():
                out[k] = "<redacted>"
            else:
                out[k] = redact(v)
        return out
    if isinstance(obj, list):
        return [redact(v) for v in obj]
    return obj


def send(proc: subprocess.Popen, message: dict) -> None:
    line = json.dumps(message, separators=(",", ":"))
    assert proc.stdin is not None
    proc.stdin.write(line + "\n")
    proc.stdin.flush()


def read_until(proc: subprocess.Popen, request_id: int, timeout: float = 25.0) -> dict:
    assert proc.stdout is not None
    deadline = time.time() + timeout
    notes: list[dict] = []
    while time.time() < deadline:
        remaining = deadline - time.time()
        if remaining <= 0:
            break
        line = proc.stdout.readline()
        if not line:
            raise RuntimeError("app-server closed stdout")
        line = line.strip()
        if not line:
            continue
        try:
            msg = json.loads(line)
        except json.JSONDecodeError:
            continue
        if msg.get("id") == request_id:
            msg["_notifications"] = notes
            return msg
        if "id" not in msg:
            notes.append(msg)
    raise TimeoutError(f"no response for id={request_id}")


def summarize_windows(result: dict) -> dict:
    buckets = []
    by_id = result.get("rateLimitsByLimitId") or {}
    if not by_id and result.get("rateLimits"):
        item = result["rateLimits"]
        by_id = {item.get("limitId") or "default": item}
    for limit_id, item in by_id.items():
        for key, window in (("primary", item.get("primary")), ("secondary", item.get("secondary"))):
            if not window:
                continue
            minutes = window.get("windowDurationMins")
            label = "unknown"
            if minutes is not None:
                if 280 <= minutes <= 320:
                    label = "5h"
                elif 9000 <= minutes <= 11000:
                    label = "weekly"
                else:
                    label = f"{minutes}m"
            buckets.append({
                "limitId": limit_id,
                "limitName": item.get("limitName"),
                "window": key,
                "label": label,
                "usedPercent": window.get("usedPercent"),
                "windowDurationMins": minutes,
                "resetsAt": window.get("resetsAt"),
                "planType": item.get("planType") or result.get("planType"),
                "rateLimitReachedType": item.get("rateLimitReachedType"),
            })
    credits = result.get("credits")
    resets = result.get("rateLimitResetCredits") or {}
    return {
        "buckets": buckets,
        "credits": redact(credits) if credits else None,
        "resetCreditsAvailable": resets.get("availableCount"),
        "resetCreditCountKnown": bool(resets.get("credits") is not None),
    }


def main() -> int:
    codex = shutil.which("codex")
    if not codex:
        print(json.dumps({"ok": False, "error": "codex CLI not on PATH"}))
        return 2
    version = subprocess.check_output([codex, "--version"], text=True, timeout=20).strip()
    login = subprocess.run(
        [codex, "login", "status"],
        capture_output=True,
        text=True,
        timeout=30,
    )
    print(json.dumps({
        "cli": codex,
        "version": version,
        "loginStatusExit": login.returncode,
        "loginStatusStdout": login.stdout.strip()[:500],
        "loginStatusStderr": login.stderr.strip()[:500],
    }, ensure_ascii=False))

    proc = subprocess.Popen(
        [codex, "app-server", "--listen", "stdio://"],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        bufsize=1,
        env={**os.environ, "NO_COLOR": "1"},
    )
    try:
        send(proc, {
            "method": "initialize",
            "id": 0,
            "params": {
                "clientInfo": CLIENT,
                "capabilities": {"experimentalApi": True},
            },
        })
        init = read_until(proc, 0, timeout=30)
        print(json.dumps({"initialize": redact(init.get("result") or init.get("error"))}, ensure_ascii=False))
        send(proc, {"method": "initialized", "params": {}})

        send(proc, {"method": "account/read", "id": 1, "params": {"refreshToken": False}})
        account = read_until(proc, 1, timeout=30)
        account_result = account.get("result") or account.get("error")
        print(json.dumps({"accountRead": redact(account_result)}, ensure_ascii=False))

        send(proc, {"method": "account/rateLimits/read", "id": 6})
        limits = read_until(proc, 6, timeout=40)
        if "error" in limits:
            print(json.dumps({"rateLimitsError": limits["error"]}, ensure_ascii=False))
            return 1
        result = limits.get("result") or {}
        print(json.dumps({
            "rateLimitsSummary": summarize_windows(result),
            "rawKeys": sorted(result.keys()),
        }, ensure_ascii=False))

        send(proc, {"method": "account/usage/read", "id": 7})
        usage = read_until(proc, 7, timeout=40)
        if "error" in usage:
            print(json.dumps({"usageError": usage["error"]}, ensure_ascii=False))
        else:
            print(json.dumps({"usageRead": redact(usage.get("result"))}, ensure_ascii=False))
        return 0
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()


if __name__ == "__main__":
    sys.exit(main())
