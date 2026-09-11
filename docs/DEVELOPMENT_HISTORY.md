# 개발 이력 (로컬 기록)

이 계정에서 Notion 연결이 불가능해 개발 이력을 로컬 Markdown으로 남긴다. 나중에 Notion에 몰아서 옮길 수 있도록 버전별로 같은 구조를 유지한다: **보고된 증상 → 확정된 원인 → 변경 → 검증 → 남은 것**.

v0.1.5 이전 기록은 Notion에서 내보낸 `artifacts/claude-handoff/notion/05_DEVELOPMENT_HISTORY.md`에 있다. `artifacts/`는 Git ignored이므로 이 파일과 별도로 보관한다.

작성 규칙: 테스트 통과를 실서비스 연동 성공으로 적지 않는다. 실기기에서 확인하지 못한 것은 "미검증"으로 남긴다.

---

## v0.1.6 — 위젯 값 의미 수정 (2026-09-11)

### 보고된 증상

> 지금 5hour가 76퍼고 week는 24퍼인데 둘이 반대로 나온다.

추가 확인으로 범위가 좁혀졌다.

- 반대로 보이는 곳은 **위젯뿐**이다. 앱 카드/상세는 정상이었다.
- Codex 페이지의 문구는 **"남음"**이다. 즉 페이지 값은 5시간 76% 남음, 주간 24% 남음이다.

### 확정된 원인

파서와 DB는 정상이었다. 결함은 위젯 표시 계층에만 있었고 두 가지가 겹쳤다.

1. **기본값 불일치.** `SettingsStore.remaining` 기본값은 `true`(남음)인데 `WidgetConfig.remaining` 기본값은 `false`(사용)였다. 앱 화면은 페이지와 같은 "남음"을, 위젯은 그 보수인 "사용"을 표시했다.
2. **위젯이 값의 의미를 표시하지 않았다.** `UsageGlanceWidget`은 `"${label}  ${text}"`만 그렸고 text는 `"24%"`였다. 사용인지 남음인지 알 방법이 없었다.

76 + 24 = 100이라 두 결함이 합쳐지면 화면상 정확히 **좌우가 뒤바뀐 것처럼** 보인다. 실제로는 bucket이 섞인 것이 아니라 같은 bucket의 보수가 라벨 없이 표시된 것이다.

이 때문에 초기 조사에서 bucket swap 가설과 used/remaining 반전 가설을 분리할 수 없었다. 사용자가 "위젯만" + "남음"을 확인해준 시점에 2번 가설로 확정됐다.

### 변경

| 파일 | 변경 |
| --- | --- |
| `widget/WidgetConfig.kt` | `remaining: Boolean` → `Boolean?`, 기본값 `null`. null은 "앱 설정을 따름"을 뜻한다 |
| `widget/WidgetStateMapper.kt` | `effectiveRemaining(config, settingRemaining)` 추가. 순수 함수 `display()`를 분리해 값 문자열에 사용/남음을 항상 포함 |
| `widget/WidgetConfigurationActivity.kt` | 선택지를 2개 → 3개(앱 설정 따름 / 사용 / 남음)로 바꾸고 "표시할 값" 제목 추가 |
| `ui/DetailScreens.kt`, `ui/MainActivity.kt` | 앱 안의 위젯 목록 미리보기도 `effectiveRemaining`을 거치도록 `settings` 전달 |
| `res/values/widget_strings.xml`, `res/values-ko/...` | `widget_value_meaning`, `widget_value_default` 추가 |

이제 위젯은 `"76% 남음"`처럼 숫자와 의미를 함께 표시한다. 값만으로 모호해지는 경우가 없어진다.

### 기존 위젯 마이그레이션

`WidgetConfigStore`의 `Json`은 `encodeDefaults`가 기본 `false`다. 예전 기본값이 `false`였으므로 **`remaining=false`는 저장된 적이 없다**. 따라서 저장된 JSON에 `remaining` 키가 있으면 사용자가 명시적으로 "남음"을 고른 경우뿐이다.

- 키 없음 → `null` → 앱 설정을 따름 → 사용자의 기존 위젯이 자동으로 교정된다.
- `"remaining":true` → 유지된다.

앞으로는 "사용"을 고르면 기본값이 아니므로 `"remaining":false`로 실제 저장된다. 이 성질을 테스트로 고정했다.

### 검증

로컬 `testDebugUnitTest` **39 tests / failures 0 / errors 0** (이전 29건에서 10건 추가). `lintDebug` errors 0, warnings 55. `assembleDebugAndroidTest` 컴파일 통과.

신규 회귀 `WidgetValueSemanticsTest` 9건은 사용자가 보고한 값을 그대로 쓴다.

- 자기 설정이 없는 위젯 + 앱 설정 남음 → 5시간 `76% 남음`, 주간 `24% 남음`
- "사용"으로 고정한 위젯 → `24% 사용` / `76% 사용`. 보수 자체는 정당한 표현이지만 라벨 없는 숫자로는 절대 나오지 않는다
- 합이 100이 아닌 76/31 → 76·31과 24·69가 각각 유지된다 (bucket swap과 보수 반전을 구분)
- unknown은 0이 되지 않고 진행률도 null
- 저장된 JSON 마이그레이션 2건

**수정 전 실패를 실증했다.** `WidgetConfig.remaining` 기본값을 `false`로 되돌려 실행하면 9건 중 3건이 실패한다(`widgetWithoutOwnChoiceFollowsAppSettingInsteadOfShowingTheComplement`, `storedWidgetsMigrateWithoutLosingADeliberateRemainingChoice`, `anExplicitUsedChoiceIsPersistedNowThatItIsNoLongerTheDefault`). 되돌린 뒤 전원 통과한다.

파서 쪽에는 `usage_codex_korean_uneven` fixture(5시간 76% 남음 / 주간 31% 남음)와 회귀 1건을 추가했다. 기존 파서 fixture는 전부 45/66 즉 합이 111이어서 우연히 구분이 됐을 뿐, 합이 100인 입력에서 두 결함을 갈라내는 사례가 없었다.

### 미검증 / 남은 것

- **실기기 위젯 표시는 미검증이다.** 이 PC에 연결된 Android 기기가 없다(`adb devices` 비어 있음). 사용자가 APK를 설치해 런처 위젯에서 `76% 남음`이 뜨는지 확인해야 완료다.
- `WidgetRenderingTest`(instrumentation)는 `WidgetValue`를 직접 만들어 렌더링만 확인하므로 이번 의미 결함을 잡지 못한다. 기기가 생기면 mapper를 거치는 경로로 보강한다.
- Grok Usage 도달·수집, Google 인증 알림은 이번 버전에서 손대지 않았다. 여전히 미해결이다.

### 이번에 발견했으나 손대지 않은 잠재 결함

사용자 증상의 원인이 아니어서 이번 범위에서 제외했다. 파서를 다시 만질 때 함께 처리한다.

1. `ConsumerUsageParser.parsePercentValues`는 의미를 판별하지 못한 %를 **used로 기록한다**. `classifyPercent`가 앞뒤 1줄만 보므로 라벨과 "남음"이 2줄 이상 떨어지면 반대로 저장될 수 있다. 값이 모호하면 unknown으로 두는 편이 이 프로젝트 원칙에 맞다.
2. `WebUsageReader.CAPTURE_JS`는 progressbar의 aria-label/valuetext를 본문 뒤에 덧붙인다. 원래 카드와 떨어진 위치에 "라벨/값" 쌍이 생기므로 1번 경로로 들어가기 쉽다.
3. chatgpt 라벨 사전에 `session` id가 두 번 있다(5-hour, Codex usage). 한국어 화면의 `Codex 및 Work 분석`, `Codex와 Work는 동일한 사용 한도를 공유합니다.`가 모두 session으로 매칭돼 유령 섹션을 만든다. 지금은 그 구간에 %가 없어 버려지지만, 상단 그래프에 %가 있으면 진짜 5시간 섹션과 evidence 경쟁을 한다.
4. `PageAuthState`는 grok에서 **query에 usage가 있기만 하면** SIGNED_IN으로 판정한다. 화면이 실제로 뜨지 않아도 통과할 수 있다.

---

## v0.1.6 — 인증·세션 수정 (2026-09-11, 같은 릴리즈에 포함)

### 보고된 증상

1. 앱 안의 Google 로그인이 휴대폰에 저장된 계정을 쓰지 못하고 매번 초기화된 브라우저처럼 처음부터 인증해야 한다.
2. Google 2단계 인증 중 **번호 맞추기 알림이 폰에 절대 오지 않는다.**
3. 힘들게 로그인해도 Grok이 `Completing sign-in, verifying your device to keep your account secure`에서 **무한 로딩**에 걸린다.

### 원인과 대응

#### 3번 — Grok 무한 검증: 코드 결함이었다. 수정함

`ProviderWebActivity.blockIfDisallowed`가 허용 목록에 없는 호스트로의 이동을 **프레임 종류와 무관하게 차단**했다. `mainFrame` 인자는 토스트를 띄울지만 결정했을 뿐이다.

기기 검증과 captcha 챌린지는 provider 허용 목록으로 열거할 수 없는 호스트의 **하위 프레임(iframe)** 에서 돌아간다. 그 프레임이 차단되면 챌린지가 끝나지 않고 화면은 계속 로딩 상태로 남는다. 증상과 정확히 일치한다.

같은 호스트의 script·XHR·이미지는 `shouldOverrideUrlLoading`을 아예 거치지 않아 이미 그대로 로드되고 있었다. 즉 프레임만 막는 것은 페이지가 접근할 수 있는 범위를 좁히지 못하면서 로그인만 망가뜨리고 있었다.

`WebNavigationPolicy.blocks(url, allowedHosts, mainFrame)`로 규칙을 옮기고, 호스트 허용 목록은 **메인 프레임 이동에만** 적용한다. 사용자가 고른 provider 밖으로 끌려가지 않게 하는 보호는 그대로 유지된다. https가 아닌 scheme(`intent://`, `market://`, `http://`, `javascript:`)은 프레임과 무관하게 계속 차단한다.

Grok 허용 호스트에 `x.ai`, `www.x.ai`를 추가했다.

#### 1번 — 매번 재인증: 절반은 코드 결함이었다. 수정함

두 가지가 섞여 있다.

**(a) 세션이 디스크에 남지 않았다 — 수정함.** 코드 어디에도 `CookieManager.flush()`가 없었다. WebView는 쿠키를 메모리에 두고 자체 일정으로 기록하므로, 로그인 직후 WebView가 destroy되거나 프로세스가 죽으면 **방금 만든 세션이 사라진다.** 그러면 다음에 열 때 처음부터 다시 인증해야 한다.

`ProfileSessions.flush(webView)`를 추가하고 세션이 막 바뀐 지점에서 호출한다: 팝업에서 로그인 확인 직후, 메인 화면에서 로그인 감지 직후, 사용량 저장 성공 직후, `onStop`, WebView 파기 직전, 그리고 백그라운드 수집기가 임시 WebView를 버리기 직전. 마지막 것은 provider가 매 요청마다 세션 쿠키를 회전시키기 때문에 중요하다. 회전된 쿠키를 기록하지 않으면 계정이 조용히 만료된다.

**(b) 휴대폰에 저장된 Google 계정은 쓸 수 없다 — 구조적 제약이며 고칠 수 없다.** 계정 격리를 위해 쓰는 WebView MULTI_PROFILE은 독립된 쿠키 저장소다. Chrome의 세션이나 Android AccountManager의 계정에 접근할 방법이 없다. Chrome Custom Tabs는 Chrome 세션을 쓰지만 그 DOM을 앱이 읽을 수 없어 사용량 수집이 불가능하다. 이건 앱 코드로 해결되지 않는다.

#### 2번 — 번호 맞추기 알림 미수신: 앱이 고칠 수 있는 문제가 아니다

Google은 embedded WebView에서의 로그인을 정책으로 제한한다. 기기 프롬프트는 Google Play 서비스가 보내는데, Google이 신뢰하지 않는 흐름에는 그 challenge를 내주지 않거나 다른 방식으로 대체한다. 서버 쪽 판단이라 앱에서 바꿀 수 없다.

사용자가 추측한 "같은 폰에서 보내고 받아서"는 원인이 아니다. 같은 기기에서의 번호 맞추기는 일반 브라우저에서는 정상 동작한다.

UA를 위장해 embedded WebView 탐지를 피하는 방법은 채택하지 않았다. 사용자가 앞서 거부한 방식이고, 보안 정책 우회이며, Google은 UA 외의 신호도 보기 때문에 성공도 보장되지 않는다.

**대신 앱이 할 수 있는 것을 했다.** `WebNavigationPolicy.isGoogleSignIn(url)`로 Google 로그인 페이지 진입을 감지해, 막다른 길에 들어서기 **전에** 상태줄에 경고한다: 저장된 계정을 쓸 수 없고 번호 확인 알림이 오지 않을 수 있으니 이메일 로그인을 쓰라는 안내다. 기존에는 Google이 차단 페이지를 띄운 **뒤에야** 알려줬다.

실질적인 우회 경로는 Google을 아예 쓰지 않는 것이다. ChatGPT는 chatgpt.com에서 이메일+비밀번호 로그인, Grok은 X 계정 또는 이메일 로그인을 쓴다. Google로만 가입한 계정이라면 해당 서비스에서 비밀번호를 새로 설정한 뒤 이메일 로그인을 쓸 수 있다.

### 변경

| 파일 | 변경 |
| --- | --- |
| `core/web/WebNavigationPolicy.kt` | `blocks(url, hosts, mainFrame)`, `isGoogleSignIn(url)` 추가 |
| `core/web/ProviderWebActivity.kt` | 차단을 메인 프레임 한정으로, Google 로그인 사전 경고, 세션 flush 5개 지점 |
| `core/web/ProfileSessions.kt` | `flush(webView)` 추가 |
| `core/web/WebUsageReader.kt` | 임시 WebView 파기 직전 flush |
| `provider/ProviderRegistry.kt` | Grok 허용 호스트에 `x.ai`, `www.x.ai` |
| `res/values*/widget_strings.xml` | `web_google_signin_warning` |

### 검증

로컬 `testDebugUnitTest` **42 tests / failures 0 / errors 0**. `lintDebug` errors 0. `assembleDebug`, `assembleDebugAndroidTest` 통과.

신규 회귀 3건(`WebNavigationPolicyTest`):

- 챌린지 호스트(cloudflare turnstile, arkoselabs, hcaptcha)가 **하위 프레임에서는 로드되고 메인 프레임은 여전히 차단**된다
- https가 아닌 scheme은 두 프레임 모두에서 차단된다
- Google 로그인 호스트가 인식되고, provider 로그인 페이지나 일반 google.com 검색은 오탐하지 않는다

### 미검증

- **세 가지 모두 실기기 검증이 필요하다.** 이 PC에 Android 기기가 연결되지 않아 실제 Grok 검증 통과, 실제 세션 지속, 실제 Google 화면 동작을 확인하지 못했다.
- Grok 무한 로딩의 원인을 코드에서 특정했지만, 실제 챌린지가 정말 하위 프레임 차단 때문이었는지는 사용자가 새 APK로 확인해야 확정된다. 다른 원인이 겹쳐 있을 수 있다.
- Google 번호 맞추기는 이번 변경으로 **해결되지 않는다.** 경고를 앞당겼을 뿐이다.

---

## v0.1.7 — 진단 수단 확보와 로그인 화면 수정 (2026-09-11)

### 상황

v0.1.6 배포 후 사용자 확인 결과:

- 위젯 수치 반전은 다루지 않음(별도 확인 필요).
- **Grok 무한 로딩은 그대로다.** v0.1.6의 iframe 차단 해제로 해결되지 않았다.
- "인증 창 닫기" 버튼을 눌러도 변화가 없고, 이 버튼이 왜 있는지 모르겠다는 지적.
- 로그인 화면 상단 영역이 화면을 가려서 불편하다. 쓸어내려 접는 기능을 원한다.

### 판단: 추측을 멈추고 증거를 확보한다

v0.1.6에서 iframe 차단을 원인으로 지목했지만 틀렸거나 부분 원인이었다. 기기를 연결할 수 없는 상태에서 같은 방식으로 또 추측하면 같은 왕복을 반복하게 된다. 그래서 이번 버전의 핵심은 **폰에서 실제로 무슨 일이 일어나는지 사용자가 내보낼 수 있게 만드는 것**이다.

### 변경

#### 1. `WebTrace` — 공유 가능한 로그인 브라우저 추적

`core/web/WebTrace.kt`. 최근 300건의 링 버퍼. 설정 → 진단 화면에서 보고 공유(내보내기)할 수 있다.

기록 항목: 메인/팝업의 페이지 시작·완료, 이동 차단 결정과 프레임 종류, 네트워크 오류 코드, HTTP 상태 코드, SSL 오류, 팝업 생성(제스처 여부 포함)·종료, 진행률, JS 콘솔 에러, 페이지 분류(`AuthPageKind`)와 로그인 상태(`PageAuthState`).

**자격증명 보호.** URL은 `WebNavigationPolicy.redact`로 scheme·host·path만 남긴다. 쿼리를 통째로 버리므로 OAuth code·state·token이 들어가지 않는다. 자유 텍스트는 이메일 주소와 24자 이상 연속 문자열을 치환한 뒤 160자로 자른다. 페이지 본문이나 쿠키는 애초에 기록하지 않는다.

이전에는 `Log.i(NAV_LOG, ...)`로 logcat에만 남겼는데, USB 디버깅을 쓸 수 없는 이 환경에서는 볼 방법이 없었다.

#### 2. 로그인 화면에서 실제로 고친 것

**HTTP·네트워크 오류가 아예 기록되지 않고 있었다.** `onReceivedError`, `onReceivedHttpError`를 재정의하지 않아 요청이 4xx/5xx로 실패해도 앱은 아무것도 몰랐다. 이제 둘 다 추적에 남는다. 무한 로딩의 원인이 실패한 요청이라면 이걸로 드러난다.

**제스처 없는 팝업을 거부하고 있었다.** `onCreateWindow`가 `if (!isUserGesture) return false`였고 `javaScriptCanOpenWindowsAutomatically = false`였다. 로그인과 기기 검증은 탭 직후가 아니라 **리다이렉트 이후에** 다음 창을 여는 경우가 있다. 그러면 창이 영영 생기지 않고 페이지는 계속 기다린다. 둘 다 허용으로 바꾸고 팝업 생성을 제스처 여부와 함께 기록한다. 팝업은 여전히 같은 프로필에 묶이고 메인 프레임 이동은 허용 목록의 통제를 받는다.

**"인증 창 닫기" 버튼이 대부분의 상황에서 아무 일도 하지 않았다.** 이 버튼은 OAuth 팝업이 스스로 닫히지 않을 때 닫으라고 만든 것인데, `popups.lastOrNull()`이 null이면 조용히 아무것도 안 했다. Grok 무한 로딩처럼 **멈춘 곳이 팝업이 아니라 메인 창일 때**가 그렇다. 사용자가 "왜 있는지 모르겠다"고 한 이유다.

이제 상태에 따라 라벨과 동작이 바뀐다. 팝업이 열려 있으면 `인증 창 닫기`, 없으면 `로그인 다시 시작`(로그인 URL로 되돌림). 항상 빠져나갈 수단을 준다.

#### 3. 상단 영역 접기

툴바를 `host`(상태 줄, 항상 보임) + `details`(제목·안내·버튼들, 접힘) + `handle`(손잡이)로 나눴다. 손잡이를 **위로 쓸어올리면 접히고 아래로 쓸어내리면 펼쳐진다.** 탭해도 토글된다. 드래그 판정은 `ViewConfiguration.scaledTouchSlop` 기준이다.

접으면 상태 줄 한 줄과 손잡이만 남아서 provider의 로그인 폼이 화면을 거의 다 쓴다.

### 검증

로컬 `testDebugUnitTest` **47 tests / failures 0 / errors 0** (42 → 47). `lintDebug` errors 0. `assembleDebug`, `assembleDebugAndroidTest` 통과.

신규 `WebTraceTest` 5건은 추적이 자격증명을 흘리지 않는지를 고정한다.

- OAuth code와 state가 들어간 URL에서 host·path만 남고 code·state는 사라진다
- 이메일 주소와 24자 이상 토큰이 치환된다
- `status=403`, `code=-2` 같은 짧은 진단 값은 그대로 남는다(치환이 과하지 않은지)
- 버퍼가 300건으로 제한되고 최신 것이 남는다
- 모든 항목에 밀리초 타임스탬프가 붙는다(멈춘 지점을 보기 위해)

### 미검증 / 다음

- **Grok 무한 로딩의 원인은 아직 모른다.** 이번 변경은 원인을 볼 수 있게 만든 것이고, 팝업 거부 해제가 원인이었다면 부수적으로 고쳐질 수 있다. 확정은 사용자가 재현한 뒤 추적을 보내줘야 가능하다.
- 받아야 할 것: Grok 로그인을 재현하고 무한 로딩 상태에서 설정 → 진단 → 내보내기로 추적을 공유.
- 추적에서 볼 것: 멈춘 시점의 마지막 `start`/`finish` 호스트, `neterr`/`http` 코드, `console` 에러, 팝업이 열렸는지, 진행률이 몇 %에서 멈췄는지.
- 확인되지 않은 가설로 남는 것: x.ai가 embedded WebView를 탐지해 기기 검증을 의도적으로 통과시키지 않을 가능성. 그렇다면 앱 코드로는 해결되지 않는다. UA 위장은 여전히 채택하지 않는다.
