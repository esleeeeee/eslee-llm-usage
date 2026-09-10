# eslee LLM Usage

여러 LLM 서비스의 계정별 사용량과 초기화 시각을 모아 보는 Android 로컬 앱입니다. 홈 화면에 계정 조합이 다른 위젯을 여러 개 배치할 수 있습니다.

**v0.1.5.** Android-only. Codex Usage는 `https://chatgpt.com/codex/settings/usage`, Grok Usage는 `https://grok.com/?_s=usage`에서 읽습니다. Desktop Collector는 사용하지 않습니다. 실기기에서 Usage 숫자를 읽기 전에는 해당 Consumer를 완료로 표시하지 않습니다. 공개 저장소: https://github.com/esleeeeee/eslee-llm-usage

## 기능

- 계정별 대시보드, 상세 사용량, 기록, 별칭 및 주요 항목 선택
- 동일 서비스의 복수 계정과 계정별 Android WebView Profile 격리
- 크기에 따라 정보량이 달라지는 Glance 위젯, 계정·항목·표시 방식별 독립 설정
- 공식 API의 예약 갱신과 소비자 웹 화면의 사용자가 시작하는 갱신
- 마지막 성공 데이터를 유지하는 오류 처리, 오래된 데이터 및 알 수 없는 값 표시
- Android Keystore AES-256-GCM 인증정보 보관, Room 사용량 저장, 한국어·영어 UI
- 디버그 빌드 전용 데모 계정

## Provider 지원

| 서비스 | 수집 범위 | 갱신 | 검증 상태 |
| --- | --- | --- | --- |
| Codex (ChatGPT 계정) | 5시간·주간 한도, reset, reserve, banked reset, credits | 계정 profile을 재사용하는 자동 WebView 수집 | 실험적 parser, 실기기 숫자 미확인 |
| Claude | 표시된 세션·주간 한도 | 앱 내 웹 화면 | 실험적 parser, 실로그인 미검증 |
| Grok | Settings Usage 주간 %, 제품별 비율, reset, Extra Usage Credits | 자동 WebView 수집, `?_s=usage` | 실험적 parser, 실기기 숫자 미확인 |
| OpenAI API | UTC 오늘 completions 토큰·요청 | Admin key, 백그라운드 | 공식 API 구현, 실계정 미검증 |
| Anthropic API | UTC 오늘 messages 토큰 | Admin key, 백그라운드 | 공식 API 구현, 실계정 미검증 |
| xAI API | UTC 오늘 팀 API 비용 | Management key와 Team ID, 백그라운드 | 공식 API 구현, 실계정 미검증 |
| Gemini / Perplexity | 검증된 연결 없음 | 없음 | 미지원 사유 표시 |

API 사용량은 ChatGPT Plus, Claude Pro/Max, SuperGrok 구독 한도와 다른 상품의 데이터입니다. API에서 구독 잔여 퍼센트를 만들지 않습니다. 전체 한도를 모르는 데이터는 실제 토큰·요청·비용만 표시합니다.

## 설치와 사용

1. debug APK를 Android 9(API 28) 이상 기기에 설치합니다. 로컬 산출물 이름과 checksum은 BUILD_REPORT.md에 있습니다.
2. 계정 추가에서 서비스를 선택합니다. 디버그에서는 Demo로 화면을 먼저 확인할 수 있습니다.
3. 소비자 서비스는 계정별로 로그인하면 기존 세션으로 사용량을 자동 수집합니다. 웹 계정은 백그라운드에서 15분 주기(절전 시 지연 가능), 앱 화면을 열어 둔 동안 5분 주기로 갱신합니다. 앱/위젯 새로고침은 수집·저장을 실행하며 인증이 만료된 경우에만 로그인이 필요합니다. 설정에서 자동 갱신을 끄면 정기 수집을 중지합니다.
4. 홈 화면의 위젯 선택에서 eslee LLM Usage를 추가하고 계정·항목·표시 옵션을 저장합니다.
5. 홈 화면에서 위젯 크기를 변경하면 실제 dp 크기에 맞춰 요약 정보가 조절됩니다.

Android System WebView의 MULTI_PROFILE 지원이 필요합니다. 미지원 환경에서는 안전한 세션 격리를 위해 소비자 웹 계정 추가를 차단합니다. API 계정은 영향을 받지 않습니다.

## 개발

- JDK 21(최소 17), Android SDK API 37.0, Build Tools 36.0.0
- Gradle wrapper 9.3.1, AGP 9.1.1, Kotlin/Compose, Room, Hilt, WorkManager, Glance, AndroidX WebKit
- `local.properties`에 `sdk.dir`을 지정하거나 `ANDROID_HOME`을 설정합니다. 이 파일은 커밋하지 않습니다.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
./gradlew connectedDebugAndroidTest
```

APK 기본 경로는 `app/build/outputs/apk/`입니다. 릴리스 서명은 `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` 환경 변수로 설정합니다. 서명정보가 없으면 release APK는 unsigned입니다.

## 구조

단일 app 모듈 내 `core/model`, `core/database`, `core/security`, `core/web`, `provider`, `usage`, `sync`, `settings`, `ui`, `widget` 패키지로 역할을 분리합니다. Provider가 공통 snapshot으로 정규화한 결과를 Room 트랜잭션으로 저장하며 앱과 위젯이 같은 캐시를 읽습니다. 개별 계정 잠금과 독립 오류 처리로 서비스 간 실패를 격리합니다.

자체 서버, 광고, 분석 SDK, 비밀번호 저장, 브라우저 쿠키 가져오기, 구독 한도 우회 기능은 없습니다. 사용자는 Provider 공식 웹 화면에서 직접 인증합니다. 아이콘은 자체 제작한 단순 막대 표시이며 Provider 로고를 배포하지 않습니다.

[알려진 제한](KNOWN_LIMITATIONS.md) · [보안](SECURITY.md) · [검증 기록](BUILD_REPORT.md)
