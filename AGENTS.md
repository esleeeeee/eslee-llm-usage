# Working on eslee LLM Usage

Read `docs/HANDOFF_HOME.md` and the latest section of `docs/DEVELOPMENT_HISTORY.md` first.

- Android-only Kotlin/Compose app. No desktop collector or app backend.
- Rings always show remaining capacity. Never guess whether an unlabeled percentage means used or remaining. Unknown is not zero.
- Keep account WebView profiles isolated. Never use global cookie/storage deletion to sign out one account.
- Loaded WebView profiles cannot be physically deleted in the same process. `ProfileSessions` purges data, retires the name, and retries directory deletion at next startup. Logout assigns a fresh profile name.
- Preserve the last successful snapshot when sync fails. One account failure must not cancel other accounts.
- Release signing lives in GitHub Actions secrets. Never commit or extract keys/cookies/API credentials. Local debug APKs have a different signature from distributed releases.
- Run JVM tests and lint for logic changes. Run relevant Android tests on the local emulator for WebView, UI, database, or widget changes. Screenshots use explicitly synthetic QA data; passing fixtures does not prove live provider authentication.
- On this Windows machine use `scripts/verify-windows.ps1`; its ASCII junction references the original repository without moving files. Toolchains and emulator images are outside Git.
- Do not treat old dated notes in `BUILD_REPORT.md` or development history as the current support status. Current limitations are in `KNOWN_LIMITATIONS.md`.
