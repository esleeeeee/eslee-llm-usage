from pathlib import Path
import re
import urllib.request

html = Path(r"D:\eslee\toolchains\tmp\grok-usage.html").read_text(encoding="utf-8", errors="replace")
print("html_login", bool(re.search(r"sign[- ]?in|log[- ]?in", html, re.I)))
print("html__s_usage", "_s=usage" in html)
for m in re.finditer(r".{0,80}_s=usage.{0,80}", html):
    print("ctx", m.group(0).replace("\n", " ")[:200])

needles = (
    "Extra Usage Credits", "weekly usage", "ConsumerUiSvc", "GetRemainingResets",
    "prod_mc_billing", "cli-chat-proxy", "usagePercent", "remaining_resets",
)
srcs = re.findall(r'src="(https://cdn\.grok\.com/_next/static/chunks/[^"]+\.js[^"]*)"', html)
print("chunk_count", len(srcs))
hits = []
for i, url in enumerate(srcs[:40]):
    try:
        with urllib.request.urlopen(url, timeout=20) as resp:
            body = resp.read(2_000_000).decode("utf-8", "replace")
    except Exception as exc:
        print("fail", i, type(exc).__name__)
        continue
    found = [n for n in needles if n.lower() in body.lower()]
    if found:
        hits.append((url.split("/")[-1], found, len(body)))
        print("HIT", url.split("/")[-1], found, "len", len(body))
print("hits", len(hits))
