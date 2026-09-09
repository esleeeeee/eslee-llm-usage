"""Parse SuperGrok Settings → Usage visible text into a UsageSnapshot-shaped dict.

This is the collector-side equivalent of ConsumerUsageParser for Grok, using
only page text. No cookies, tokens, or credentials.
"""
from __future__ import annotations

import json
import re
from datetime import datetime, timezone

WEEKLY = re.compile(r"(weekly(?: usage| pool| limit)?|주간 사용량|주간 한도)", re.I)
PERCENT = re.compile(r"(?<![\d.])(\d{1,3}(?:\.\d+)?)\s*%")
RESET = re.compile(
    r"(?:resets?(?:\s+on)?|reset(?:s)?|리셋|초기화)\s*[:\-]?\s*(.+)",
    re.I,
)
CREDIT = re.compile(r"extra usage credits\s*[:\-]?\s*\$?\s*([0-9]+(?:\.[0-9]+)?)", re.I)
PRODUCTS = ("Chat", "Imagine", "Voice", "Build", "API")
PLAN = re.compile(r"\b(SuperGrok(?:\s+(?:Plus|Heavy|Pro))?)\b", re.I)


def parse_visible_text(text: str, fetched_at: int | None = None) -> dict:
    fetched_at = fetched_at or int(datetime.now(timezone.utc).timestamp() * 1000)
    lines = [ln.strip() for ln in text.splitlines() if ln.strip()]
    joined = "\n".join(lines)
    buckets = []

    weekly_percent = None
    for i, line in enumerate(lines):
        if WEEKLY.search(line):
            window = "\n".join(lines[i:i + 8])
            m = PERCENT.search(window)
            if m:
                weekly_percent = float(m.group(1))
            break
    if weekly_percent is None:
        m = PERCENT.search(joined)
        if m:
            weekly_percent = float(m.group(1))
    reset_at = None
    reset_raw = None
    for line in lines:
        m = RESET.search(line)
        if m:
            reset_raw = m.group(1).strip()
            break

    if weekly_percent is not None or reset_raw:
        buckets.append({
            "id": "weekly",
            "label": "Weekly usage",
            "unit": "PERCENT",
            "usedPercent": weekly_percent,
            "resetRaw": reset_raw,
            "resetAt": reset_at,
            "scope": "ACCOUNT",
        })

    for product in PRODUCTS:
        for i, line in enumerate(lines):
            if re.fullmatch(product, line, re.I):
                window = "\n".join(lines[i:i + 3])
                m = PERCENT.search(window)
                if m:
                    buckets.append({
                        "id": product.lower(),
                        "label": product,
                        "unit": "PERCENT",
                        "usedPercent": float(m.group(1)),
                        "scope": "FEATURE",
                    })
                break

    credit = CREDIT.search(joined)
    plan = PLAN.search(joined)
    ok = any(b["id"] == "weekly" and (b.get("usedPercent") is not None or b.get("resetRaw")) for b in buckets)
    return {
        "ok": ok,
        "providerId": "grok",
        "planName": plan.group(1) if plan else None,
        "extraCreditsUsd": float(credit.group(1)) if credit else None,
        "buckets": buckets,
        "fetchedAt": fetched_at,
        "source": "VISIBLE_PAGE",
    }


FIXTURE = """
Settings
Usage
SuperGrok
Weekly usage
42%
Resets on 2026-09-16 at 14:00 UTC
Chat
18%
Imagine
12%
Voice
4%
Build
8%
API
0%
Extra Usage Credits $5.00
"""


if __name__ == "__main__":
    print(json.dumps(parse_visible_text(FIXTURE), ensure_ascii=False, indent=2))
