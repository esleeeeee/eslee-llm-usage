# 집 컴퓨터 인수인계 — 2026-09-22

## 현재 작업: v0.2.4

사용자가 로그인 재시작, Grok 수집 실패, 위젯 새로고침 시 앱 실행을 보고했다. 새 프로필 재시작과 계정별 수집 취소, 분리된 숫자/% 및 영문 reset 파싱, 48dp 위젯 터치 영역을 수정했다. 실제 계정이 있는 Pixel Launcher에서 위젯 터치 → 수집 → 표시 갱신을 검증했다. 자세한 증거는 `QA_V024.md`.

- `emulator-5554` / `eslee_usage_api35`: 사용자가 로그인한 실계정 보유. 서명된 release 업데이트만 설치한다. uninstall, clear data, synthetic 계정 삭제 테스트 금지.
- `emulator-5556` / `eslee_qa_api35`: 자동 테스트 전용. debug/androidTest APK 설치 및 전체 테스트는 여기에 실행한다.
- PC 키보드 활성화, host GPU(RTX 4070 Ti), 720×1600/280dpi. 사용자 조작용 AVD는 RAM 4096MB, 테스트용은 3072MB.
- 단위 테스트 75개, Android 테스트 23개. 위젯 테스트는 실제 창에 부착한 호스트의 화면 좌표로 touchscreen 이벤트를 주입한다. 함수 직접 호출이나 미부착 뷰의 dispatchTouchEvent는 실제 클릭을 검증하지 못했다.
- 실제 계정 캡처와 로그는 Git-ignored artifacts/live-v024에만 보관한다.
## 프로젝트와 사용자의 의도

회사 Claude Code에서 개발하던 Android 앱을 집 Codex에서 계속 개발한다. 회사 로컬 파일을 수동으로 옮기지 않는 것이 조건이다. 원격은 `https://github.com/esleeeeee/eslee-llm-usage`. 인수 당시 main은 `739a3ab`, 배포본은 v0.2.2였다. 이번 작업은 앱의 오류를 수정하고 이 PC의 실제 Android 에뮬레이터로 검증하는 것이다.

여러 Codex/Claude/Grok 계정의 사용량과 reset을 계정별 WebView 세션으로 수집하고 Room에 저장해 앱과 홈 위젯에 표시한다. 공식 API 사용량은 consumer 구독 한도와 별개다. 위젯은 사용자가 지정한 갤럭시 배터리 위젯 스타일: 공식 서비스 마크, 아래가 열린 링, 남은 %, 계정 별칭. 기본 4×1, 세로 확장 시 카운트다운. 데스크톱 수집기는 사용하지 않는다.

## 이미 확인했던 상태

- 이전 개발 이력에 Codex(v0.1.11 이후)와 Grok(v0.1.16)의 사용자 실기기 수집 성공이 기록되어 있다. 이번 에뮬 검증에서 실계정으로 로그인한 것은 아니다.
- v0.2.1: 공식 로고, Grok reset 검색 범위 확대, 앱 항목별 세로 배치.
- v0.2.2: 위젯 높이 정렬, 전체 새로고침 버튼, 크기 축소.
- 오래된 README 지원 표와 BUILD_REPORT의 v0.1.5 머리말은 최신 개발 이력과 불일치했다. 현재 문서에서는 이를 정리했다.

## v0.2.3 수정 범위

- 전체 갱신이 끝날 때까지 위젯 갱신 상태 유지. 실패/취소/빈 계정 목록도 정리. 오프라인 수동 갱신이 네트워크 제약에서 무한 대기하지 않도록 함.
- 각 계정 수집 실패 격리. 저장 실패를 숨기지 않고 다른 계정까지 완료한 후 전달.
- 사용/남음 의미 없는 % 추측 금지, 일반 Codex 제목 오인 제거, reset 키워드 다음 줄 상대 시간 처리.
- 계정 추가 화면 스크롤과 키보드 인셋, 저장 후 키보드 닫기. 작은 화면의 상세 항목 버튼 배치 개선.
- 전 화면에서 오류 메시지 표시, 중복 동작 방지, 다크 모드 상태바 대비, Wi-Fi 설정 변경의 전경 갱신 반영.
- 로드된 WebView 프로필 삭제 제약 처리. 프로필별 데이터 삭제, 이름 재사용 차단, 다음 프로세스의 물리 삭제. 로그아웃은 새 프로필 이름을 받음.
- 에뮬레이터의 계정 관리 여정, 세션 격리/삭제, 실제 위젯 호스트와 WorkManager 갱신 검증. CI도 일부 WebView 테스트만 실행하던 구성에서 전체 Android 테스트 실행으로 확대.

## 집 PC 환경

원본 저장소는 OneDrive의 `코딩/eslee-llm-usage` 폴더다. 한글 경로에서는 Gradle JVM 테스트가 ClassNotFoundException으로 실패하여 영문 junction을 사용한다. **복사본이 아니라 같은 파일이다.**

- 도구 루트: `%USERPROFILE%\.local\android-dev`
- JDK: `jdk/jdk-21.0.12.1+1`
- SDK: `sdk` — platform 37.0, build-tools 36.0.0, platform-tools, emulator, API 35 google_apis x86_64 이미지
- junction: `project` → 원본 저장소
- AVD: `eslee_usage_api35`, 기본 Pixel 7, WHPX 가속, RAM 3072MB, CPU 4개
- Gradle: wrapper 9.3.1, AGP 9.1.1
- 기기: `emulator-5554`. 다른 실제 휴대폰을 대상으로 테스트하지 않는다.

PowerShell에서:

```powershell
./scripts/verify-windows.ps1 -Connected
```

이 스크립트는 설치된 도구를 사용한다. 원격 다운로드/설치나 사용자 앱 데이터 삭제는 하지 않는다. 먼저 에뮬레이터를 실행해야 한다. 테스트 전용 설치에서 실행한다. 위젯 전체 갱신 테스트는 실제 활성 계정이 있으면 실패하도록 되어 있다.

```powershell
$env:ANDROID_HOME = "$env:USERPROFILE\.local\android-dev\sdk"
& "$env:ANDROID_HOME\emulator\emulator.exe" -avd eslee_usage_api35 -no-audio -no-snapshot -gpu host -memory 4096 -cores 4
```

테스트 로그/PNG는 ignored `artifacts/`와 `app/build/reports`에 저장된다. 공유 가능한 검증 요약/선별 캡처는 `docs/QA_V023.md`에 기록한다.

## 배포와 남은 검증

GitHub Actions의 기존 네 개 `SIGNING_*` secrets로 서명해야 기존 설치본을 업데이트할 수 있다. 로컬 debug 서명은 다르므로 사용자 실기기에 덮어쓰기용으로 배포하지 않는다. main push는 자동 프리릴리스를 만든다. 브랜치 workflow_dispatch는 검증과 서명 산출물만 만든다.

테스트 fixture 성공과 실제 provider 로그인 성공은 구분한다. Claude 및 공식 API의 실계정 수집, Google OAuth 정책, 삼성 One UI의 위젯 셀 크기/절전 동작은 에뮬 통과만으로 보장하지 않는다. 실패 시 설정 → 진단의 앱 버전과 로그를 먼저 확인하고, 실제 증거 없이 파서를 추측 수정하지 않는다.
