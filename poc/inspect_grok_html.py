from pathlib import Path
import re

p = Path(r"D:\eslee\toolchains\tmp\grok-usage.html")
t = p.read_text(encoding="utf-8", errors="replace")
print("len", len(t))
for s in [
    "Weekly usage", "Extra Usage Credits", "Settings", "sign in", "login",
    "ConsumerUiSvc", "prod_mc_billing", "GetRemainingResets", "_s=usage",
    "cli-chat-proxy", "usage pool",
]:
    print(f"{s}\t{t.lower().count(s.lower())}")
srcs = re.findall(r'src="([^"]+\.js[^"]*)"', t)
print("js_count", len(srcs))
for s in srcs[:15]:
    print(" ", s[:200])
