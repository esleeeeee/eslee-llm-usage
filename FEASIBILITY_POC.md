# Consumer Usage Feasibility POC

Date: 2026-09-09  
Scope: prove whether Codex Usage and SuperGrok Usage can be collected on a PC and sent to the Android app as `UsageSnapshot` only.  
Not in scope: v0.1.3 feature implementation, Android UI rewrite, WebView login bypass, cookie/token extraction.

v0.1.2 real-device round 3 showed that Android Consumer WebView cannot be the default collection path. This document records what this machine actually read, what official surfaces exist, and which architecture should replace WebView scraping.

## Verdict

| Question | Result |
| --- | --- |
| Codex Usage actually readable? | **Yes**, live, this machine |
| Values read | 5-hour %, weekly %, extra named limit %, each `resetsAt`, plan, banked reset count, extra credits field (null here) |
| Grok SuperGrok Usage actually readable? | **Not live**. Official Settings URL and field list confirmed. Visible-text parser works on a fixture. Logged-in page was not read |
| Multi-account | **Possible** with isolated collector profiles paired to Android Account IDs |
| Auto refresh | **Codex yes** (local poll of official app-server). **Grok only while a logged-in browser profile can open Settings → Usage** |
| Auth exposed to Android? | **Must not be**. Collector keeps sessions on the PC and sends snapshots only |
| Recommended architecture | Desktop Collector → normalized `UsageSnapshot` → Android viewer/widget |
| Unstable / do not use | Android WebView OAuth as default; reading `auth.json` / cookies; undocumented WHAM/gRPC calls from our code; PTY scraping of `/status` when app-server works |

This POC does **not** mark device widget update, Grok live login, or end-to-end phone sync as complete.

## v0.1.2 device facts this POC starts from

From Notion 05, round 3:

1. ChatGPT login eventually succeeded, but **사용량 확인** opened `https://chatgpt.com/` because `ProviderRegistry` sets `usageUrl` to the ChatGPT home. The product target is **Codex usage**, not generic ChatGPT chat usage.
2. Grok `usageUrl` is `https://grok.com/`, not Settings → Usage.
3. Phone apps already logged in cannot donate cookies/tokens to this app.
4. Embedded WebView Google OAuth is a poor default (repeated consent, phone-number verification).
5. Grok sign-in stalled on device verification.

Current code still has:

```49:54:app/src/main/java/com/eslee/llmusage/provider/ProviderRegistry.kt
        add(consumer("chatgpt", "ChatGPT", "chatgpt.com", "https://chatgpt.com/auth/login", "https://chatgpt.com/",
            setOf("auth.openai.com", "auth0.openai.com", "chat.openai.com")))
        ...
        add(consumer("grok", "Grok", "grok.com", "https://grok.com/sign-in", "https://grok.com/", setOf("accounts.x.ai")))
```

Those URLs are wrong for the usage surfaces OpenAI and xAI document. They were **not** changed in this POC.

## Official surfaces

### Codex

OpenAI documents two consumer-facing checks:

- Settings dashboard: `https://chatgpt.com/codex/settings/usage`
- Active Codex CLI session: `/status`

Sources checked 2026-09-09:

- [Using Codex with your ChatGPT plan](https://help.openai.com/articles/11369540): “open Settings or your usage dashboard… In an active Codex CLI session, enter /status.”
- [Codex pricing](https://learn.chatgpt.com/docs/pricing): same dashboard URL and `/status`.
- [Slash commands](https://learn.chatgpt.com/docs/reference/slash-commands): `/status` shows chat ID, context usage, and rate limits.
- [Developer commands](https://learn.chatgpt.com/docs/developer-commands?surface=cli): TUI also has `/usage` for token activity and redeeming an earned reset. There is **no** `codex status` shell subcommand in the command table.
- [App server](https://learn.chatgpt.com/docs/app-server): official JSON-RPC `account/rateLimits/read` and `account/usage/read`.
- [Analytics API](https://learn.chatgpt.com/docs/enterprise/analytics-api): **Enterprise workspace admin** metrics, not a personal Plus Codex read API.

Personal Codex has **no documented public HTTP usage API** for third-party apps. The documented programmatic path is the local Codex app-server, which already holds the ChatGPT login.

GitHub issue [openai/codex#10233](https://github.com/openai/codex/issues/10233) (still open) asked for a headless `/status`. App-server now covers that need without scraping the TUI.

### SuperGrok

xAI documents Settings → Usage on web and mobile. Fields:

- weekly usage percent / progress bar
- product breakdown (API, Build, Chat, Imagine, Voice)
- weekly reset date and time
- Extra Usage Credits balance

Source: [FAQ - Grok Website / Apps](https://docs.x.ai/grok/faq)

Billing is `https://grok.com/?_s=billing`. This POC fetched `https://grok.com/?_s=usage` and received HTTP 200 with a Next.js SPA whose router state includes `"q":"?_s=usage"`. That is the Settings Usage deep link to use instead of `https://grok.com/`.

xAI Management API / Console Usage Explorer is **API spend**, not SuperGrok weekly pool. Grok Build `grok usage <session>` prints **session token/cost**, not SuperGrok weekly %.

No documented SuperGrok consumer read API was found.

## Codex live POC

Machine: Windows, `codex-cli 0.153.4` at `C:\Users\user\AppData\Local\OpenAI\Codex\bin\...\codex.exe`.  
`codex login status`: `Logged in using ChatGPT`.  
Script: `poc/codex_app_server_rate_limits.py`. It talks to `codex app-server` over stdio. It does **not** open `~\.codex\auth.json`.

`account/read` (email redacted in logs): ChatGPT auth, `planType=plus`.

`account/rateLimits/read` returned live windows:

| Bucket | Window | usedPercent | Duration | Reset (UTC) |
| --- | --- | --- | --- | --- |
| `codex` primary | 5-hour | 20 | 300 min | 2026-09-09T09:40:46Z |
| `codex` secondary | weekly | 25 | 10080 min | 2026-09-15T05:18:29Z |
| `base_model_inference` / `gpt-reserve` | weekly | 0 | 10080 min | 2026-09-16T08:17:39Z |

Also present:

- `rateLimitResetCredits.availableCount = 3` (banked resets)
- `credits = null` on this account (no extra credit balance in this response)
- `rateLimitReachedType = null`

Redacted capture: `poc/codex_live_rate_limits_redacted.json`.

`account/usage/read` returned a token-activity summary and daily buckets. Those are analytics, not the 5h/weekly allowance. Token totals were redacted in the POC log.

### Mapping to `UsageSnapshot`

Collector output should look like the existing Android model (`UsageBucket` + `CreditBalance` + `UsageSnapshot`), with `source` a new collector value later. Suggested buckets:

- `session` / 5-hour: `usedPercent=20`, `resetAt` from `primary.resetsAt`, `windowLabel=5h`
- `weekly`: `usedPercent=25`, `resetAt` from `secondary.resetsAt`
- optional `gpt-reserve` feature bucket
- `extraCredits` from `credits.balance` when non-null
- note/status for `rateLimitResetCredits.availableCount`

Windows are identified by `windowDurationMins` (~300 → 5h, ~10080 → weekly), not by primary/secondary position. OpenAI has shipped weekly-only responses before.

### Other Codex paths

| Path | Result | Use? |
| --- | --- | --- |
| Official dashboard URL | Documented. Unauthenticated fetch from this host returned 403 (bot protection). Not scraped | Human check / optional browser companion |
| TUI `/status` | Documented. Interactive only | Fallback if app-server fails |
| TUI `/usage` | Documented. Token activity + redeem reset | Do not redeem from collector |
| `codex exec` | Does not replace rate-limit read | No |
| PTY send `/status` | Not needed; app-server worked | Unstable, skip |
| `GET chatgpt.com/backend-api/wham/usage` using `auth.json` | Undocumented; requires extracting tokens | **Forbidden** |
| Enterprise Analytics API | Workspace admin key | Not Plus consumer |

Non-interactive collection is **stable enough to productize** for Codex on a PC that already has Codex CLI logged in.

## Grok live POC

### URL and API

| Check | Result |
| --- | --- |
| Official Settings → Usage | Documented. Target values match the product: weekly %, product breakdown, reset, Extra Usage Credits |
| Deep link | `https://grok.com/?_s=usage` returns 200 SPA; router includes `?_s=usage` |
| `https://grok.com/settings/usage` | Request aborted / not a confirmed route |
| Public consumer Usage API | **None found** |
| xAI Management API | API prepaid/spend, not SuperGrok |
| `grok usage` CLI | Session token/cost only. Not SuperGrok weekly pool |
| Logged-in Settings page | **Not read**. Cookies were not copied. Chrome/Edge profiles exist (`Chrome\User Data\Profile 2`, `Edge\User Data\Default`, `Edge\User Data\Profile 3`) but were not attached |

Community tools call undocumented Grok billing/gRPC endpoints with a Grok Build bearer. This POC does **not** call those and does **not** treat them as a product path.

### Visible-text parser

`poc/grok_usage_parser.py` parses a fixture that matches the official FAQ layout. It extracted:

- plan `SuperGrok`
- weekly 42%
- reset raw `2026-09-16 at 14:00 UTC`
- Chat 18 / Imagine 12 / Voice 4 / Build 8 / API 0
- Extra Usage Credits `$5.00`

That proves the **snapshot shape**, not a live account. Reset string → epoch parsing still needs work (`resetAt` was null on the fixture).

### Browser extension collector

`poc/extension/` is a Manifest V3 sketch:

- host `https://grok.com/*`
- reads `document.body.innerText` only
- never reads `document.cookie` or storage tokens
- intended to run in an already logged-in Chrome/Edge profile

This is the only Grok path that keeps the session inside the browser and still produces a `UsageSnapshot`. Live install/read was not performed (would require the user to load an unpacked extension in their logged-in profile).

Until that happens, **Grok Usage is not proven live**. It is proven **collectable in principle** if the official page is visible.

## Multi-account pairing

| Source | Isolation | Pairing |
| --- | --- | --- |
| Codex CLI | Separate `CODEX_HOME` or `codex --profile` / extra ChatGPT login | Each home/profile → one Android `Account.id` |
| Chrome/Edge | Separate browser profiles (`Profile 2`, Edge `Default`, Edge `Profile 3` already present) | Each browser profile → one Grok Android account |
| Android | Existing `Account.id` UUID | Display a short pairing code / QR on both sides; store only `collectorProfileId` + `accountId` |

Do not merge two ChatGPT or Grok logins into one snapshot. Widget selections already key off `accountId`.

This POC did not log into a second Codex or Grok account. The isolation mechanism exists; cross-account live proof does not.

## Transport candidates (not chosen yet)

Android widgets need a fresh snapshot without the user opening the collector. Background WorkManager can apply a snapshot it already has; it cannot log into ChatGPT/Grok from the phone.

| Mode | How | Pros | Cons | Widget background |
| --- | --- | --- | --- | --- |
| Same LAN direct | PC collector listens on HTTPS localhost/LAN; phone pairs with a code; mDNS or manual IP | No third party; snapshots only; low latency | Phone and PC must share a network or VPN; NAT/firewall; PC must be on | **Yes**, if collector is running and reachable |
| User-owned relay | User’s NAS / VPS / Tailscale / Cloudflare Tunnel they control | Works off home LAN; still no vendor cloud | User must operate a host; more moving parts | **Yes**, if relay and collector are up |
| Encrypted cloud relay | App-operated mailbox; snapshot encrypted to device key | Easiest off-LAN | We would run infra; abuse/cost; still must never see plaintext if done right | **Yes**, with push or poll |

Recommendation: implement LAN pairing first, keep the snapshot schema relay-agnostic, add user-owned tunnel later. Do not build a company cloud mailbox until LAN sync works.

Payload: `UsageSnapshot` JSON (and maybe a signed envelope). Never cookies, refresh tokens, `auth.json`, or WebView profiles.

## Recommended architecture

```
PC Codex CLI login ──► codex app-server account/rateLimits/read
PC Chrome/Edge profile ──► grok.com/?_s=usage visible text (extension)
        │
        ▼
 Desktop Collector (normalize to UsageSnapshot, poll 15–30 min)
        │  pairing: collectorProfileId ↔ Android Account.id
        ▼
 Transport (LAN first)
        │
        ▼
 Android app Room + Glance widget
```

Android responsibilities after this shift:

- store/display snapshots
- widgets, account list, pairing UI
- official API connectors (OpenAI/Anthropic/xAI **API** spend) unchanged
- Consumer WebView marked **experimental fallback**, not default

Codex connector rename: **Codex** (ChatGPT plan), usage from collector, not `https://chatgpt.com/`.

Grok connector: SuperGrok Settings Usage via collector, not `https://grok.com/`.

## Security boundary (kept)

- Did not read `~\.codex\auth.json`
- Did not copy Chrome/Edge cookies
- Did not call undocumented WHAM or Grok gRPC with extracted tokens
- Did not send credentials to Android
- POC logs redacted email, account IDs, and token counts
- Did not redeem banked Codex resets

## Implementation-not-now list

Do not do these in v0.1.3 until pairing/transport is designed:

- Large Android UI rewrite
- WebView host allowlist expansion as the “fix”
- Custom Tab OAuth without a documented callback into an isolated profile
- Shipping the Grok extension as the only login
- Treating undocumented vendor endpoints as official APIs

## Scripts in this repo

- `poc/codex_app_server_rate_limits.py` — live Codex read
- `poc/codex_live_rate_limits_redacted.json` — captured windows
- `poc/grok_usage_parser.py` — SuperGrok visible-text parser + fixture
- `poc/extension/` — Manifest V3 sketch
- `poc/inspect_grok_html.py`, `poc/search_grok_js.py` — URL/SPA inspection

## Required answers

1. **Codex Usage actually read?** Yes, via official local `account/rateLimits/read`.
2. **Which values?** 5h 20% (reset 2026-09-09 09:40 UTC), weekly 25% (reset 2026-09-15 05:18 UTC), gpt-reserve 0% weekly, 3 banked resets, credits null, plan Plus.
3. **Grok Usage actually read?** No live SuperGrok weekly %. Official page URL and field list confirmed; fixture parser works.
4. **Which Grok values?** Only fixture: weekly 42%, product percents, Extra Credits $5, reset string. Live account unknown.
5. **Multi-account?** Yes in design (CODEX_HOME / browser profiles / Android Account ID). Not live-tested with two logins.
6. **Auto refresh?** Codex: yes, poll app-server. Grok: only if a logged-in browser profile can load Settings Usage. Phone cannot refresh Consumer usage by itself.
7. **Auth exposure?** None in this POC. Product rule: snapshots only.
8. **Recommended architecture?** Desktop Collector → `UsageSnapshot` → Android widget; LAN pairing first.
9. **Impossible / unstable?** Android WebView as default; cookie transplant; PTY `/status` scraping; undocumented WHAM/gRPC from our process; Grok CLI `usage` as SuperGrok pool; `https://chatgpt.com/` as Codex usage URL.
