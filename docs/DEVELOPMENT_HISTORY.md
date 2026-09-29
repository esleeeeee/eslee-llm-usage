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

---

## v0.1.8 — 추적 노출 수정과 로그인 화면 조작감 (2026-09-11)

### 사용자 확인 결과

- **위젯 수치 반전은 해결됐다.** v0.1.6의 수정이 실기기에서 확인됐다. 이 건은 닫는다.
- Grok 무한 로딩은 **여전하다.** v0.1.7의 팝업 허용도 원인이 아니었거나 부분 원인이었다.
- 접기 방향이 반대다. 아래로 쓸어내리면 숨고, 위로 쓸어올리면 나와야 한다.
- 전환이 툭툭 끊긴다. 부드러운 모션을 원한다.

### 추적이 사용자에게 도달하지 못했다

사용자가 보낸 내보내기에 **추적 섹션이 없었다.** 기기 정보와 동기화 로그만 있었다.

v0.1.7의 진단 화면은 추적을 **동기화 로그 아래**에 놓았다. 사용자의 동기화 로그는 수십 건이라 화면에서도 공유 텍스트에서도 추적이 한참 아래에 묻힌다. 정작 보내달라고 요청한 것이 가장 찾기 어려운 위치에 있었다.

수정:

- 진단 화면과 내보내기 모두에서 **추적을 맨 위로** 올렸다. 내보내기는 추적과 그 건수로 시작한다.
- 내보내기의 동기화 로그를 최근 20건으로 제한했다. 추적을 밀어내지 않게 한다.
- 추적은 **다른 액티비티**(`ProviderWebActivity`)가 기록하므로, 진단 화면이 `ON_RESUME`마다 다시 읽도록 했다. 이전에는 첫 composition 시점의 스냅샷을 들고 있어, 화면을 떠나지 않고 로그인을 다녀오면 갱신되지 않을 수 있었다.

### 로그인 화면 조작

**방향을 뒤집었다.** `setExpanded(delta > 0)` → `setExpanded(delta < 0)`. 손잡이를 아래로 끌면 패널이 내려가 숨고, 위로 밀면 올라온다. 안내 문구와 화살표도 함께 바꿨다(`▼ 아래로 쓸어내리면 숨겨집니다` / `▲ 위로 쓸어올리면 버튼이 나옵니다`).

**전환에 모션을 넣었다.** `TransitionManager.beginDelayedTransition`에 220ms `AutoTransition`과 감속 `PathInterpolator(0.2, 0, 0, 1)`를 쓴다. 패널의 페이드와 WebView 높이 변화가 함께 애니메이션된다. 첫 표시에는 애니메이션을 쓰지 않는다(`animate = false`).

드래그 한 번당 전환도 한 번만 일어나도록 `dragged` 플래그로 막아 두어, 손가락이 움직이는 동안 애니메이션이 겹치지 않는다.

### 검증

로컬 `testDebugUnitTest` **47 tests / failures 0 / errors 0**. `lintDebug` errors 0. `assembleDebug` 통과.

이번 변경은 뷰 배치와 화면 구성이라 단위 테스트로 고정되지 않는다. 실기기 확인이 필요하다.

### 다음

Grok은 아직 **원인 미상**이다. v0.1.6(iframe), v0.1.7(팝업 거부) 두 가설이 모두 빗나갔다. 이제 추적이 사용자에게 실제로 도달할 것이므로, 다음 라운드는 추측이 아니라 로그를 근거로 한다.

받아야 할 것: Grok 로그인을 무한 로딩까지 재현한 직후의 추적. 볼 것은 마지막 `start`/`finish` 호스트, `neterr`/`http` 코드, `console` 에러, `popup-open`의 유무와 `gesture` 값, 진행률이 멎은 지점.

---

## v0.1.9 — Grok 무한 로딩: 추적으로 원인 특정 (2026-09-11)

### 추적이 보여준 것

v0.1.8에서 사용자가 Grok 로그인을 재현하고 추적을 보냈다. 자격증명 없이 호스트·경로·시각만 담긴 로그였고, 그것으로 충분했다.

```
16:36:39.573 restart-login
16:36:41.286 start  · https://accounts.x.ai/sign-in
16:36:44.829 start  · https://accounts.google.com/v3/signin/accountchooser
16:36:45.235 neterr · https://accounts.x.ai/monitoring code=-1 main=false
16:36:50.333 start  · https://accounts.x.ai/oauth-complete
16:36:51.240 finish · https://accounts.x.ai/oauth-complete
16:36:52.478 progress · 100%
(이후 10초 이상 analytics 콘솔 노이즈만 있고 이동 없음)
```

확정된 사실:

1. **Google OAuth는 이번 실행에서 통과했다.** accountchooser → `oauth-complete`로 돌아왔다. 이 경로에서는 Google이 막지 않았다.
2. **멈춘 곳은 `https://accounts.x.ai/oauth-complete`다.** 로드가 끝난 뒤 다시는 이동하지 않는다. 화면의 "Completing sign-in, verifying your device…"가 이 페이지다.
3. **팝업은 한 번도 열리지 않았다.** `popup-open` 항목이 없고 모든 `start`가 메인 프레임이다. v0.1.7의 팝업 허용은 이 경로에서 아예 쓰이지 않았다.
4. HTTP 4xx/5xx 없음. JS 예외 없음. 하위 프레임 오류는 `accounts.x.ai/monitoring` 하나뿐이며 로그인과 무관하다.

### 원인

`oauth-complete`는 **팝업으로 열렸을 때를 전제로 만들어진 종단 페이지**다. 할 일은 두 가지뿐이다: `window.opener`에 완료를 알리고 `window.close()`로 자신을 닫는 것. 그러면 원래 창(grok.com)이 세션을 이어받는다.

이 앱에서는 흐름 전체가 **메인 프레임**에서 돌았다. opener가 없으니 알릴 대상이 없고, 메인 WebView의 `window.close()`는 `onCloseWindow`가 팝업만 처리하므로 조용히 무시된다. 페이지는 할 일을 다 했는데 아무 일도 일어나지 않고, 사용자는 무한 로딩을 본다.

v0.1.6(하위 프레임 차단)과 v0.1.7(팝업 거부)이 빗나간 이유도 여기 있다. 둘 다 "무언가가 막혔다"는 가정이었는데, 실제로는 **아무것도 막히지 않았고 페이지가 정상적으로 끝에 도달한 뒤 갈 곳이 없었다.**

세션 쿠키는 이 시점에 이미 설정돼 있을 가능성이 높다. `oauth-complete`가 하는 일이 그것이기 때문이다.

### 변경

`core/web/UsageSurface.kt`에 `isAuthCompletionPage(url)`를 추가했다. `/oauth-complete`, `/oauth/callback`, `/auth/complete` 등 OAuth 종단 경로를 https에서만 인식한다.

`core/web/ProviderWebActivity.kt`:

- 메인 프레임이 종단 페이지에 도달하면 `completeSignIn`이 쿠키를 flush하고 **4초**를 기다린다. 페이지가 스스로 이동하면 아무것도 하지 않는다. 같은 URL에 그대로 있으면 `auth-complete-stalled`를 기록하고 provider의 Usage URL로 이동한다. 이미 설정된 세션 쿠키가 그 이동에 실린다.
- `onCloseWindow`가 **메인 WebView**에 대해서도 동작한다. 종단 페이지가 `window.close()`를 부르면 `close-main`을 기록하고 같은 경로로 넘어간다. 이전에는 팝업이 아니면 무시했다.
- `blockIfDisallowed`가 차단한 이동을 `nav-blocked`로 추적에 남긴다. v0.1.7·v0.1.8에서는 logcat에만 남아 추적에서 보이지 않았다. 이번 로그에 차단 항목이 없었던 것이 "막힌 게 아니다"라는 판단의 근거였는데, 그 판단은 이 사각지대 때문에 간접 추론이었다. 이제 직접 보인다.
- 콘솔 오류 중 analytics·광고·CSP 보고는 추적에서 제외한다. 이번 로그의 3분의 2가 이런 노이즈였고, 300건 버퍼를 신호 대신 채우고 있었다.

### 검증

로컬 `testDebugUnitTest` **48 tests / failures 0 / errors 0** (47 → 48). `lintDebug` errors 0. `assembleDebug` 통과.

신규 회귀 1건: `oauthCompletionPagesAreRecognisedInTheMainFrame`. 실제 추적의 URL(`https://accounts.x.ai/oauth-complete`)을 포함해 종단 페이지를 인식하고, 로그인·Usage·Google accountchooser·http는 오탐하지 않는다.

### 미검증

- **실기기에서 Grok 로그인 완료와 Usage 수집은 아직 확인되지 않았다.** 원인은 추적으로 특정했지만 4초 대기 후 이동이 실제로 세션을 이어받는지는 사용자가 확인해야 한다.
- 세션 쿠키가 `oauth-complete` 시점에 정말 설정돼 있는지는 가정이다. 아니라면 Usage URL로 이동해도 로그인 페이지가 나올 것이고, 그 경우 추적에 `start · https://accounts.x.ai/sign-in`이 다시 찍힐 것이다.
- 근본 대안(grok.com에서 팝업으로 로그인을 열게 하기)은 provider 페이지의 동작에 달려 있어 앱이 강제할 수 없다.

---

## v0.1.10 — Grok: 세션 쿠키 설정 페이지 차단이 원인 (2026-09-11)

### 추적이 확정한 것

v0.1.9에서 사용자가 재현한 추적:

```
16:58:36.210 finish     · accounts.x.ai/oauth-complete
16:58:37.541 nav-blocked · https://auth.grok.com/set-cookie main=true BLOCK_HOST
16:58:40.216 auth-complete-stalled · accounts.x.ai/oauth-complete
16:58:40.765 start      · grok.com
16:58:41.949 http       · grok.com/rest/suggestions/profile status=401
16:58:42.483 http       · grok.com/rest/rate-limits status=401
16:58:42.492 http       · grok.com/rest/products status=401
```

`oauth-complete`는 v0.1.9에서 추정한 "죽은 페이지"가 아니었다. 로드 직후 **메인 프레임을 `https://auth.grok.com/set-cookie`로 이동**시켜 세션 쿠키를 심으려 했다. 그런데 `auth.grok.com`이 grok 허용 호스트에 없어서 `blockIfDisallowed`가 BLOCK_HOST로 막았다.

그 결과:

1. 세션 쿠키가 설정되지 않았다.
2. v0.1.9의 스톨 타이머(4초)가 발동해 grok.com으로 강제 이동했다.
3. grok.com이 미인증 상태라 usage 엔드포인트(`/rest/rate-limits`, `/rest/products`)가 401.
4. 사용자 화면: 나이 입력만 있고 "로그인 또는 회원가입" 요구.

이번엔 `nav-blocked` 항목이 추적에 보였기 때문에 원인이 바로 드러났다. v0.1.9에서 이 로깅을 추가한 것이 이번 진단을 가능하게 했다. v0.1.6~v0.1.9 세 번의 추정(하위 프레임 차단, 팝업 거부, 죽은 종단 페이지)이 모두 빗나간 진짜 이유는 **provider의 세션 설정용 서브도메인을 막고 있었기 때문**이다.

### 변경

- `provider/ProviderRegistry.kt`: grok 허용 호스트에 `auth.grok.com` 추가. 이제 `oauth-complete` → `auth.grok.com/set-cookie` → grok.com(로그인됨) 흐름이 막히지 않는다. 자연 리다이렉트가 진행되면 v0.1.9의 스톨 타이머는 URL 변경으로 스스로 취소된다.
- `core/web/ProviderWebActivity.kt`: `advanceAfterSignIn`에서 `verified = true`를 제거했다. 종단 페이지에 도달한 것만으로 로그인 성공을 단정하지 않는다. Usage로 이동한 뒤 실제 읽기 또는 signed-in 감지가 verified를 정한다. 401 페이지에서 verified가 참이 되던 문제를 없앤다.

### 검증

로컬 `testDebugUnitTest` **48 tests / failures 0 / errors 0**. `lintDebug` errors 0. `assembleDebug` 통과.

신규 회귀: 실제 차단됐던 URL `https://auth.grok.com/set-cookie`가 이제 grok 허용 호스트에서 통과하고, `auth.grok.com.evil.example` 같은 유사 호스트는 여전히 차단됨을 확인한다.

### 미검증 / 다음

- **실기기에서 Grok 로그인 완료와 usage 수집은 아직 확인되지 않았다.** 원인은 확정됐고 수정은 그 원인을 직접 겨냥했지만, `set-cookie` 통과 후 grok.com이 실제로 인증 상태가 되고 usage가 수집되는지는 사용자 확인이 필요하다.
- 확인 방법: v0.1.10으로 Grok 로그인 후 추적에서 `nav-blocked · auth.grok.com`이 사라지고, `grok.com/rest/rate-limits`가 200으로 바뀌는지 본다. 여전히 401이면 다른 서브도메인이 추가로 필요할 수 있고, 그 호스트는 이제 `nav-blocked`로 보인다.
- provider의 auth 서브도메인은 관측될 때마다 허용 목록에 추가하는 방식이다. 임의 호스트를 넓게 허용하지 않는다.

---

## v0.1.11 — Grok Usage 파싱 + 배터리식 게이지 (2026-09-11)

### 상황

v0.1.10으로 **Grok 로그인이 성공했다**(`auth.grok.com` 허용이 통했다). 그러나 Usage 화면의 값을 읽지 못했다. 사용자가 실제 페이지 텍스트를 보냈다:

```
매주 SuperGrok 한도
0%
중고
2026년 9월 18일 오후 4:39 초기화
...
추가 사용 크레딧
US$0.00
```

### 원인

파서가 이 페이지의 어느 것과도 매칭되지 않았다.

1. **라벨.** grok weekly 패턴은 `weekly usage|주간 사용량`뿐이었다. 실제 라벨은 **"매주 SuperGrok 한도"**. 매칭 실패 → 버킷이 생성되지 않음.
2. **used 마커.** "0%" 옆의 **"중고"** 는 Grok이 "Used"를 기계번역한 것이다. `usedMarker`에 없어 0%가 의미 불명으로 처리됐다.
3. **크레딧.** "추가 사용 크레딧" 라벨과 "US$" 접두어를 크레딧 정규식이 인식하지 못했다.

### 변경 (`ConsumerUsageParser`)

- grok weekly 라벨에 `매주.*한도`, `주간.*한도`, `weekly (supergrok) usage|limit` 추가.
- `usedMarker`에 `중고` 추가.
- 크레딧 정규식에 `추가 사용 크레딧|추가 크레딧` 라벨과 `US$|₩|€|£` 통화 접두어 추가.

기존 grok fixture(`Weekly usage`, `$15.00`)는 새 패턴에도 매칭되어 회귀 없음.

### 배터리식 게이지

사용자 지시: **게이지는 항상 남은 용량을 채운다. 안 쓰면 꽉 참, 다 쓰면 빔. 휴대폰 배터리처럼.** 숫자 텍스트가 '사용'이든 '남음'이든 무관하다.

Grok의 "0% 사용"은 배터리로 치면 100% 남음 = 꽉 참인데, 기존에는 위젯을 '사용' 모드로 두면 막대가 사용률(0%)을 따라 **빈 상태**로 보였다.

- `WidgetStateMapper.display`: 막대 채움 비율을 텍스트 모드와 분리해 **항상 `remainingPercent`** 로 계산한다. 텍스트는 사용/남음 선택을 그대로 따른다.
- `ui/UsageComponents.BucketContent`: 앱 화면의 `LinearProgressIndicator`도 동일하게 남은 용량으로 채운다.

### 검증

로컬 `testDebugUnitTest` **49 tests / failures 0 / errors 0**. `lintDebug` errors 0. `assembleDebug` 통과.

신규 회귀:
- `grokKoreanWeeklyLimitAndCreditsFromLivePage`: 실제 페이지 fixture로 weekly used=0/remaining=100, reset=2026-09-18 16:39 KST, credit=US$0.00, primary=weekly 확인.
- `gaugeAlwaysShowsCapacityLeftLikeABattery`: 사용/남음 모드 모두에서 게이지=남은 용량. 0% 사용 → 꽉 참(1.0), 95% 사용 → 거의 빔(0.05).

### 미검증

- 실기기에서 v0.1.11로 Grok Usage가 위젯·앱에 실제로 뜨는지는 사용자 확인 필요.
- Grok 계정에 주간 한도 외 제품별 사용량(Chat/Imagine 등)이 있는 화면은 이번 fixture에 없다. 그런 화면이 나오면 라벨 보강이 더 필요할 수 있다.
- "재설정 가능 / 1일 후 만료"(banked reset)는 한국어 파싱 대상이 아니어서 아직 읽지 않는다. 이번 요구 범위 밖.

---

## v0.1.12 — 앱에서 웹 화면으로 가는 길 복구 + release APK 단일화 (2026-09-21)

### 보고된 증상

> 앱에서 웹으로 갈 방법이 없다니까? 코덱스던 그록이던?

Grok Usage를 다시 열 수 없어 수집을 재시도할 수 없었다. 로그아웃 후 재로그인 말고는 방법이 없는 상태였다.

### 원인: 세션 유지를 고친 부작용

`ProviderWebActivity`를 여는 곳이 세 군데뿐이었다.

1. 계정 최초 추가 시 (`MainActivity` `newAccount=true`) — 1회성
2. 새로고침이 **`AUTH_REQUIRED`로 실패했을 때만** (`MainActivity.refresh`)
3. 위젯 탭 동작을 "제공자 열기"로 설정한 경우

앱 화면에 "웹 열기" 버튼이 **아예 없었다.** v0.1.6에서 `CookieManager.flush()`로 세션 지속을 고친 뒤로 2번 조건이 성립하지 않게 되면서, 로그인이 살아 있는 정상 계정일수록 웹 화면에 **영구히 도달할 수 없게** 됐다. 수집이 실패해도(PARSE_FAILED) 웹을 열어주지 않으므로 사용자가 개입할 방법이 없었다.

### 변경

- `ui/UsageComponents.AccountCard`: `onOpenWeb` 콜백을 추가하고 새로고침 옆에 "웹에서 열기" 버튼을 둔다. WEB_PROFILE 계정에만 표시한다.
- `ui/MainActivity.AccountList`: `onOpenWeb`를 받아 대시보드 카드에 연결한다. 호출부에서 `ProviderWebActivity`를 연다.
- `ui/DetailScreens.DetailScreen`: 새로고침 옆에 같은 버튼을 둔다. 카드를 탭하면 상세로 오므로 두 경로 모두에서 접근 가능하다.
- `res/values*/strings.xml`: `open_web` 추가.

### 릴리즈 APK 단일화

사용자 요청으로 다음 릴리즈부터 서명된 release APK 하나(`eslee-llm-usage-v<버전>.apk`)만 게시한다. debug APK를 빼는 이유는 세 가지다.

1. `debuggable`이라 ADB가 붙으면 앱이 저장한 provider 세션 쿠키를 읽을 수 있다.
2. `BuildConfig.DEBUG` 게이트 때문에 "Demo · 테스트 데이터" 가짜 provider가 보인다.
3. 약 68 MB 대 5 MB로 13배 크다. 축소(R8) 미적용과 Compose `ui-tooling` 포함이 원인이다.

둘 다 같은 `distribution` 키로 서명되고 applicationId가 같아 서로 덮어쓰기 설치가 되므로, 한쪽을 빼도 기존 설치본이 고립되지 않는다. debug 빌드는 계기 테스트(`connectedDebugAndroidTest`)에 필요해 CI에서 계속 빌드하며 업로드만 하지 않는다. 신규 릴리즈는 `--prerelease`로 생성한다. v0.1.11 이하 릴리즈의 debug 에셋은 그대로 둔다.

기존 릴리즈 12개는 사용자 요청으로 모두 프리릴리즈로 전환했다. 전부 프리릴리즈가 되면 GitHub는 "Latest" 배지를 부여하지 않으므로 `releases/latest`가 비게 된다.

### 검증

로컬 `testDebugUnitTest` **49 tests / failures 0 / errors 0**. `lintDebug` errors 0. `assembleDebug` 통과.

UI 배치 변경이라 단위 테스트로 고정되지 않는다. 실기기 확인이 필요하다.

### 미검증 / 다음

- **Grok Usage 화면의 실제 주소를 아직 모른다.** `https://grok.com/?_s=usage`는 인계 시점의 추측이며 Usage 화면을 열지 않는다는 것이 이번에 드러났다. "사용량 페이지" 버튼이 홈으로만 가는 이유다.
- `UsageSurface.canCollect`는 호스트만 확인하므로, 사용자가 Grok 자체 메뉴로 Usage에 도달한 뒤 "사용량 읽기"를 누르면 수집은 된다. 이 경로로 한 번 도달한 뒤 진단 추적을 받아 실제 경로를 확인하고 `usageUrl`을 교체해야 한다.
- 추적에 `grok.com/` 만 남고 경로가 바뀌지 않으면 Usage가 주소 없는 모달이라는 뜻이며, URL 기반 자동 수집이 불가능해 다른 설계가 필요하다.

---

## v0.1.13 — 패널 전체 스와이프 + 파싱 결과 추적 (2026-09-21)

### v0.1.12 실기기 확인 결과

확인된 것:

- **배터리 게이지 정상.** v0.1.11의 게이지 분리가 실기기에서 확인됐다. 이 건은 닫는다.
- **Codex 정상.**
- **"웹에서 열기" 정상 동작.** 누르면 자동으로 사용량 페이지로 이동한다.
- **`https://grok.com/?_s=usage`는 실제로 동작한다.** 예전에 "Usage 화면을 열지 않는다"고 판단한 것은 틀렸다. 미인증 상태였기 때문에 홈으로 떨어진 것이고, 로그인된 세션에서는 정상적으로 Usage로 간다. v0.1.12 기록의 해당 서술을 정정한다.

남은 문제 둘:

1. 패널을 접을 때 **손잡이만** 끌 수 있고 패널 본체를 쓸어내려도 반응하지 않는다. UX가 어색하다.
2. "사용량 읽기"가 **완료됐다고 표시되는데 앱 카드에는 결과가 보이지 않는다.**

### 2번에 대한 진단 한계

추적만으로는 원인을 특정할 수 없었다. `WebNavigationPolicy.redact`가 쿼리를 통째로 버리기 때문에 `?_s=usage`가 붙었는지조차 추적에 남지 않았고, 파싱 결과도 기록되지 않아 "저장 성공"과 "표시 가능한 값이 있음"을 구분할 수 없었다.

`ConsumerUsageParser`는 **초기화 시각만 있어도 버킷을 만든다**(`percent == null && used == null && resetAt != null`이면 버킷 생성). 이 경우 `recordWeb`은 Success를 반환하고 토스트는 "완료"를 띄우지만, 카드의 `BucketContent`는 값이 없어 `—` / `알 수 없음`을 그린다. 증상과 정확히 일치하는 경로다. 다만 확정은 아니다.

### 변경

- `ProviderWebActivity.readUsage`: 읽기 결과를 추적에 남긴다. 성공 시 `weekly=0%used+reset` 형태로 **버킷 id와 값**을, 실패 시 오류 코드를 기록한다. 수집을 건너뛴 경우(`read-skipped`)에는 캡처 실패인지 페이지 분류 때문인지를 남긴다. 숫자와 버킷 id만 기록하므로 페이지 본문이나 자격증명은 들어가지 않는다.
- `ProviderWebActivity`: 툴바를 `SwipePanel`로 바꿨다. `onInterceptTouchEvent`로 세로 드래그를 가로채므로 **패널 어디를 쓸어도**(버튼 위 포함) 접히고 펴진다. 탭은 그대로 버튼에 전달된다. 손잡이는 탭 토글만 담당한다.

### 검증

로컬 `testDebugUnitTest` **49 tests / failures 0 / errors 0**. `lintDebug` errors 0. `assembleDebug` 통과.

터치 가로채기와 파싱 결과 기록은 단위 테스트로 고정되지 않는다. 실기기 확인이 필요하다.

### 다음

v0.1.13에서 "사용량 읽기"를 한 뒤 추적의 `read` 줄을 보면 원인이 갈린다.

- `read · weekly=novalue+reset` → 파서가 수치를 못 읽은 것. 페이지 텍스트를 받아 파서를 고친다.
- `read · success but no buckets` → 라벨 매칭 실패.
- `read · failed PARSE_FAILED` → 저장 자체가 안 된 것이며 토스트 표시와 모순되므로 별도 조사.
- `read · weekly=0%used+reset` 인데도 카드가 비어 있으면 → 저장은 정상이고 표시 경로 문제다.

---

## v0.1.14 — 섹션에 퍼센트가 여러 개면 값을 전부 버리던 문제 (2026-09-21)

### 결정적 증거

v0.1.13에서 추가한 읽기 추적이 원인을 한 줄로 확정했다.

```
17:27:04.276 read · failed PARSE_FAILED
17:27:06.039 read · weekly=novalue+reset
17:27:07.956 read · weekly=novalue+reset
```

파서가 weekly 버킷을 만들긴 했으나 **수치가 하나도 없었다**(`novalue`). 초기화 시각만 있었다. 카드는 그릴 숫자가 없어 "알 수 없음"을 표시했다. 저장·조회 경로는 정상이었다.

중요한 추론: **reset이 읽혔다는 것은 섹션 범위가 `0%` 줄을 포함했다는 뜻**이다(페이지에서 `0%`가 reset보다 앞에 온다). 즉 값이 범위 밖이라 놓친 것이 아니라 **찾고도 버린** 것이다.

### 원인

`parsePercentValues`가 `singleOrNull()`을 썼다.

```kotlin
val used = usedValues.singleOrNull()
val remaining = remainingValues.singleOrNull()
...
return PercentValues(unlabeledValues.singleOrNull(), null)
```

같은 의미로 분류된 퍼센트가 **둘 이상이면 전부 null**이 된다. 사용자가 손으로 복사해 보낸 페이지에는 퍼센트가 하나뿐이라 단위 테스트는 통과했지만, 실제 `document.body.innerText`에는 같은 섹션 안에 퍼센트가 더 들어 있었다. 그래서 로컬 테스트는 성공하고 기기에서는 실패하는 괴리가 생겼다.

### 변경

- `ConsumerUsageParser.parsePercentValues`: 퍼센트 후보에 줄 번호를 붙이고, 같은 의미가 여럿이면 **라벨에 가장 가까운(첫 번째) 값**을 채택한다. 카드 레이아웃이 라벨 → 값 순서이므로 첫 값이 그 한도의 값이다.
- used/remaining이 둘 다 명시됐는데 합이 100이 아니면 여전히 unknown으로 둔다. 이 보호는 유지한다.
- 의미 표시가 없는 퍼센트(UNKNOWN)는 **라벨 바로 아래 2줄 이내**일 때만 채택한다. 그보다 멀면 페이지의 다른 요소로 본다.
- `ProviderWebActivity`: 읽기 결과에 수치가 하나도 없으면 `read-context`로 **라벨 주변 8줄만** 추적에 남긴다. `WebTrace.scrub`로 이메일·긴 토큰을 치우고 160자로 자른다. 페이지 전체나 본문을 남기지 않는다.

### 검증

로컬 `testDebugUnitTest` **52 tests / failures 0 / errors 0** (49 → 52). `lintDebug` errors 0. `assembleDebug` 통과.

신규 회귀 3건:

- 섹션에 `0%`와 `15%`가 함께 있어도 라벨 바로 아래 `0%`를 채택하고 reset도 유지한다
- 라벨 직하의 의미 없는 퍼센트는 채택하고, 4줄 아래 퍼센트는 채택하지 않는다
- 2026-09-21 실제 페이지 fixture(앞선 커밋)도 계속 통과한다

기존 "합이 100이 아닌 모순된 두 값은 unknown" 회귀도 그대로 통과한다.

### 미검증

- **실기기에서 Grok 값이 실제로 표시되는지는 아직 확인되지 않았다.** 섹션에 퍼센트가 여러 개였다는 것은 추론이며, 만약 `0%`가 애초에 `innerText`에 없다면(SVG/canvas 렌더링 등) 이 수정으로는 해결되지 않는다.
- 그 경우를 위해 `read-context`를 넣었다. 다음 추적에 라벨 주변 텍스트가 찍히므로 어느 쪽인지 바로 갈린다.

---

## v0.1.15 — 숫자가 innerText에 없었다 (2026-09-22)

### 결정적 증거

v0.1.14에서 추가한 `read-context`가 원인을 한 줄로 드러냈다.

```
read-context · 사용량 | 사용량 | 매주 SuperGrok 한도 | 중고 | 2026년 9월 25일 오후 4:39 초기화 | 추가 사용 크레딧 | 추가 크레딧 | 크레딧 구매
read · weekly=novalue+reset
```

`매주 SuperGrok 한도` 다음이 바로 `중고`다. **`0%`가 캡처된 텍스트에 존재하지 않는다.** `US$0.00`도 없다. 라벨과 문구는 전부 있는데 **숫자만 빠져 있다.**

즉 파싱 문제가 아니라 **캡처 문제**였다. v0.1.14의 "섹션에 퍼센트가 여러 개"라는 가설은 틀렸다. 퍼센트는 여러 개가 아니라 **하나도 없었다.** (v0.1.14의 수정 자체는 유효한 개선이라 유지한다.)

`document.body.innerText`는 레이아웃이 그리는 텍스트만 반환한다. provider가 숫자를 SVG 차트 텍스트, shadow root, 또는 엔진이 건너뛴 영역에 그리면 innerText는 그것을 보지 못한다. Grok 사용량 화면이 정확히 그런 경우다.

### 변경

- `WebUsageReader.CAPTURE_JS`: 기존 `innerText` 캡처는 그대로 두고, **구조적 캡처**를 하나 더 만든다. DOM을 문서 순서로 순회하며 텍스트 노드를 모으고, shadow root로 들어가며, `display:none`·`visibility:hidden`과 script/style/template은 건너뛴다. SVG 텍스트도 포함된다. 결과를 `rich` 필드로 함께 반환한다.
- `WebUsageReader.Page`: `rich` 필드 추가.
- `UsageRepository.recordWeb(id, text, richText)`: **1차 파싱이 숫자를 하나도 얻지 못했을 때만** `rich`로 재파싱하고, 그쪽이 숫자를 내놓을 때만 채택한다. 이미 파싱되는 provider(Codex 등)는 원래 텍스트를 그대로 쓴다.
- `WebUsageReader`의 백그라운드 수집 경로에도 같은 폴백을 적용한다.
- `ProviderWebActivity`: 읽기 버튼이 `page.rich`를 함께 넘긴다.

폴백 조건을 "숫자가 없을 때"로 좁힌 이유는 회귀 방지다. 구조적 캡처는 줄 구성이 innerText와 달라 이미 동작하는 파싱을 흔들 수 있다.

### 검증

로컬 `testDebugUnitTest` **52 tests / failures 0 / errors 0**. `lintDebug` errors 0. `assembleDebug`, `assembleDebugAndroidTest` 통과.

신규 계기 회귀 2건(CI 에뮬레이터에서 실행):

- 라벨과 `중고`만 있고 퍼센트가 없는 visible 텍스트 + 퍼센트가 있는 rich 텍스트 → weekly 0% 사용 / 100% 남음으로 저장되고, visible에서 읽은 reset도 유지된다
- visible에 이미 42%가 있으면 rich의 7%를 쓰지 않는다

### 미검증

- **실기기에서 Grok 값이 표시되는지는 아직 확인되지 않았다.** 구조적 캡처가 그 숫자에 실제로 닿는지는 기기에서만 알 수 있다. 숫자가 canvas로 그려졌거나 CSS 생성 콘텐츠라면 이 방법으로도 닿지 않으며, 그 경우 `read-context`에 여전히 숫자가 없는 것으로 나타난다.

---

## v0.1.16 — 값만 빠지는 캡처: 범위 확대와 앱 버전 표시 (2026-09-22)

### v0.1.15 결과

사용자가 v0.1.15에서 재현한 추적은 v0.1.14와 동일했다.

```
read-context · 사용량 | 사용량 | 매주 SuperGrok 한도 | 중고 | 2026년 9월 25일 ... | 추가 사용 크레딧 | 추가 크레딧 | 크레딧 구매
read · weekly=novalue+reset
```

**구조적 DOM 캡처로도 숫자를 찾지 못했다.** 즉 `0%`와 `US$0.00`은 텍스트 노드에도, open shadow root에도, SVG 텍스트에도 없다.

사용자가 보낸 화면 캡처가 결정적인 패턴을 보여줬다. **크고 굵은 숫자(`0%`, `US$0.00`)만 빠지고, 그 옆의 작은 회색 라벨(`중고`, `추가 크레딧`)과 초기화 날짜는 모두 잡힌다.** 값만 다른 방식으로 렌더링된다는 뜻이다.

### 변경

`WebUsageReader.CAPTURE_JS`의 구조적 캡처가 텍스트 노드 외에 세 경로를 더 읽는다. 숫자를 포함한 값만 채택해 텍스트가 불필요하게 불어나지 않게 했다.

- **CSS 생성 콘텐츠**: `getComputedStyle(el, '::before' | '::after').content`. `content`로 그린 숫자는 DOM 텍스트에 존재하지 않는다. `::before`는 자식보다 먼저, `::after`는 나중에 넣어 문서 순서를 지킨다.
- **접근성 속성**: `aria-valuetext`, `aria-valuenow`, `aria-label`, `title` 중 숫자를 포함한 첫 값.
- **폼 컨트롤**: `INPUT`·`TEXTAREA`의 `value`. 값이 자식 노드가 아니라 속성에 있다.

진단도 보강했다.

- `ProviderWebActivity`: 수치를 못 얻으면 `read-context`(innerText 기준)에 더해 **`rich-context`(구조적 캡처 기준)** 도 남긴다. 두 캡처 모두에 숫자가 없으면 문서 자체에 없다는 뜻이 된다.

### 앱 버전 표시

사용자 요청 사항이자 이번 조사에서 실제로 필요했던 기능이다. v0.1.15 추적을 받았을 때 그것이 v0.1.14인지 v0.1.15인지 구분할 수 없어 게시 시각과 추적 시각을 대조해야 했다.

- 설정 → 진단 화면 **맨 위에 `앱 버전: 0.1.16 (17)`** 을 표시한다.
- **진단 내보내기의 첫 줄**도 앱 버전이다. 받은 로그가 어느 빌드인지 즉시 알 수 있다.

### 검증

로컬 `testDebugUnitTest` **52 tests / failures 0 / errors 0**. `lintDebug` errors 0. `assembleDebug` 통과.

캡처 JS는 실제 DOM이 필요해 단위 테스트로 고정되지 않는다. v0.1.15에서 추가한 폴백 계기 테스트 2건은 그대로 유지된다.

### 미검증 / 다음

- **이 캡처 확장이 Grok의 숫자에 닿는지는 기기에서만 알 수 있다.** 닿지 않으면 `rich-context`에도 숫자가 없는 것으로 나타나고, 그 경우 남는 가능성은 `<canvas>` 렌더링이다. 그때는 DOM에서 읽는 접근 자체가 불가능하다.
- 그 경우의 대안은 페이지가 스스로 호출하는 `grok.com/rest/rate-limits` 응답을 쓰는 것인데, 이는 "private endpoint를 쓰지 않는다"는 기존 방침과 충돌한다. 채택 여부는 사용자 판단이 필요하다.

### v0.1.16 실기기 결과: 해결됨

> 오 웹 들어갈 필요도없이 들어가자마자 갱신된다

**Grok 사용량 수집이 동작한다.** 웹 화면을 열지 않아도 앱 진입 시 갱신되므로, 읽기 버튼 경로뿐 아니라 **백그라운드 수집기(`WebUsageReader`)도 숫자를 읽고 있다.** 임시 WebView로 `?_s=usage`를 로드해 구조적 캡처로 값을 얻는 경로가 실제로 작동한다는 뜻이다.

원인 정리: `0%`와 `US$0.00`은 DOM 텍스트 노드에 존재하지 않았다. CSS 생성 콘텐츠·aria 속성·폼 컨트롤 value 중 하나로 그려져 있었고, v0.1.16에서 그 세 경로를 읽으면서 해결됐다.

이로써 이 프로젝트의 원래 목표 — **여러 provider의 사용량을 로그인된 세션으로 자동 수집해 위젯에 표시** — 가 Grok까지 포함해 처음으로 완결됐다. Codex는 v0.1.11부터 동작 중이었다.

진단 여정 요약. 네 번의 가설이 빗나갔고 각각이 다음 계측을 낳았다.

1. v0.1.6 하위 프레임 차단 → 아니었음. 차단 이동을 추적에 남기게 됨
2. v0.1.7 팝업 거부 → 아니었음. 추적을 사용자가 내보낼 수 있게 됨
3. v0.1.9 종단 페이지 정체 → 아니었음. 실제 원인(`auth.grok.com` 차단)이 추적에 드러남
4. v0.1.14 섹션 내 퍼센트 중복 → 아니었음. `read-context`로 숫자 부재가 드러남

정적 분석만으로는 매번 틀렸고, 기기에서 나온 로그가 매번 답을 줬다. 추측 대신 계측을 먼저 넣는 편이 결과적으로 빨랐다.

### 남은 확인

- 표시된 수치가 페이지와 일치하는지(0% 사용 / 100% 남음, 초기화 2026-09-25 16:39, 크레딧 US$0.00)는 사용자 확인 대기.
- Grok 계정에 제품별 사용량(Chat/Imagine 등)이 보이는 화면은 아직 fixture가 없다.

---

## 미배포 — 초기화 시각이 다른 줄에 있을 때 (2026-09-22, 다음 UI 패치에 포함)

### 보고

v0.1.16에서 Grok 값·게이지·크레딧은 맞지만 **위젯에 초기화 시각이 "미제공"** 으로 나온다. Codex도 weekly는 초기화 시각이 나오는데 5-hour는 안 나온다.

### 원인

화면에서 "2026년 9월 25일 오후 4:39"(검정)와 "초기화"(회색)는 **서로 다른 요소**다. `innerText`는 인라인 요소를 한 줄로 합치지만, v0.1.15의 구조적 캡처는 **텍스트 노드마다 한 줄**이라 시각과 "초기화"가 다른 줄로 갈라진다.

`parseReset`은 `firstOrNull { resetLabel.containsMatchIn(it) }`로 **"초기화"가 든 줄 하나만** 골라 시각을 찾았다. 그 줄에 "초기화"만 있으니 시각 파싱이 실패해 `resetAt = null`이 됐다. v0.1.16으로 값은 얻었지만 그 대가로 reset을 잃은 것이다.

Codex 5-hour는 innerText 경로라 같은 원인은 아닐 수 있다. 사용자 말대로 상대 시각("N시간 후")이라 우선순위는 낮다.

### 변경

- `ConsumerUsageParser.parseReset`: reset 키워드가 있는 줄의 **앞뒤 한 줄씩을 합쳐** 시각을 찾는다. 키워드 줄 ±1로 제한해 다른 항목의 시각을 끌어오지 않는다.

### 검증

로컬 `testDebugUnitTest` **54 tests / failures 0 / errors 0** (52 → 54). 신규 회귀 2건: 절대 시각이 "초기화"와 다른 줄에 있는 Grok 형태, 상대 시각("3시간 후" / "초기화")이 갈라진 Codex 형태.

### 배포

**사용자 지시로 릴리즈하지 않는다.** 중요한 패치는 끝났으므로 다음 UI/UX 개편 릴리즈에 함께 나간다. 코드는 main에 `[skip ci]`로 올려 두었다.

---

## v0.2.0 — UI/UX 전면 개편 (2026-09-22)

### 요청

> 현재 어플의 전체적인 UIUX가 너무 수준이 떨어진다. 사용하기 편리하도록 바꿔. (…) 위젯에 한에서는 생각해둔 디자인이 있음. 갤럭시 웨어러블의 배터리 위젯인데, 이것처럼 게이지가 위에 보이고, 퍼센트는 숫자로 보이고, 가운데 휴대폰이나 버즈 마크 대신 gpt, 클로드, 그록의 마크가 보이고 이런식으로. 그 아래 어떤 계정인지 계정명이 보이는 방식.

위젯의 큰 틀은 사용자 지정, 나머지 디자인·UX는 위임. 릴리즈까지 진행.

### 설계 결정

**위젯 = 배터리 링.** 270° 아크(아래 열림) + 중앙 제공자 마크 + 아래 열린 곳에 숫자 + 계정 별칭 + 초기화 캡션(`↻ 3시간`). 폭에 따라 링 수가 자동으로 늘고(64dp당 1개), 높이가 있으면 두 번째 줄이 생긴다. 높이가 모자라면 캡션 → 별칭 순으로 줄을 버리고 링은 항상 남긴다.

**숫자는 항상 남은 양.** 배터리 숫자가 남은 양이듯 링의 숫자도 남은 %다. v0.1.6에서 넣었던 위젯별 '사용/남음' 선택과 앱 전역 '기본 사용량 표시' 설정은 제거했다. 두 읽기가 공존하는 한 "24인데 76으로 보인다"류의 혼동이 재발할 수 있어서다. 앱 화면은 `76% 남음 · 24% 사용`처럼 두 값을 같이 적는다.

**색은 배터리 임계값.** 남은 양 35% 초과 초록, 15% 초과 노랑, 그 이하 빨강. 값을 믿을 수 없는 상태(로그인 필요·실패·오래됨)면 링을 노랑으로 그리고 캡션에 상태를 적는다. 알 수 없음은 빈 트랙에 `—`.

**앱 = 홈 하나 + 밀어 올리는 화면들.** 탭 4개(대시보드/계정/위젯/설정)는 대시보드와 계정 탭이 같은 목록의 다른 표현이라 없앴다. 홈은 계정 카드 목록(카드 안에 위젯과 같은 링), 당겨서 새로고침, FAB로 계정 추가, 상단 설정 아이콘. 계정 상세·계정 추가·설정·진단·지원 상태·위젯 목록은 홈 위에 쌓이는 화면이고 뒤로 가기는 항상 스택을 한 단계 내린다.

**마크는 자체 제작 추상 글리프.** 공식 로고를 배포하지 않는다는 기존 방침을 유지한다. Codex는 6장 바람개비, Claude는 8방향 별, Grok은 긴 사선+짧은 사선, API/데모는 터미널 프롬프트. 앱 카드와 위젯이 같은 drawable을 쓴다.

### 변경

| 영역 | 파일 | 내용 |
| --- | --- | --- |
| 공유 | `ui/gauge/GaugeGeometry.kt` | 아크 각도·굵기·색 임계값·숫자 규칙을 한 곳에 둠. 앱과 위젯이 같은 값을 그린다 |
| 앱 | `ui/gauge/UsageGauge.kt` | Compose Canvas 링 |
| 위젯 | `widget/GaugeBitmap.kt` | Glance는 아크를 못 그리므로 링만 비트맵으로 렌더. 마크와 숫자는 실제 뷰라 글꼴 크기를 따르고 접근성에 읽힌다 |
| 위젯 | `widget/UsageGlanceWidget.kt` | 링 격자 레이아웃, 반투명/불투명/없음 배경, 링 탭 → 계정 상세 |
| 위젯 | `widget/WidgetLayoutResolver.kt` | 크기(dp)와 링 수 → 열·행·링 지름·표시할 텍스트 줄 |
| 위젯 | `widget/WidgetStateMapper.kt` | `WidgetSlot`(별칭·제공자·남은 %·숫자·캡션·경고). 선택마다 링 하나, '모든 항목'이면 항목마다 링 하나 |
| 위젯 | `widget/WidgetConfig.kt` | 스타일·탭 동작·표시 토글·사용/남음 키 제거. 저장된 옛 JSON은 무시하고 디코드되며 계정 선택은 유지 |
| 위젯 | `widget/WidgetConfigurationActivity.kt` | 미리보기를 맨 위로. 계정 체크 + 항목(대표/모든 항목) + 테마 + 배경만 |
| 앱 | `ui/MainActivity.kt` | 스택 내비게이션, 홈 화면 |
| 앱 | `ui/UsageComponents.kt` | `ScreenScaffold`, `ProviderMark`, `StatusChip`, `BucketGauge`, `AccountCard`, `EmptyState` |
| 앱 | `ui/DetailScreens.kt` | 상세(상단 바 액션·메뉴, 큰 링, 항목 카드에 대표 칩, 영역 그래프 기록), 위젯 목록, 진단, 지원 상태 |
| 앱 | `ui/SettingsScreen.kt` | 그룹 카드 + 목록 항목 + 선택 다이얼로그. '기본 사용량 표시', '기본 위젯 테마' 제거 |
| 앱 | `ui/AddAccountScreen.kt` | 2단계(서비스 선택 → 별칭/키), 미지원 서비스는 회색 |
| 앱 | `ui/UsageLabels.kt` | `5시간`/`주간` 짧은 라벨, `3시간 20분 후 초기화` / `↻ 3시간` 카운트다운 |
| 리소스 | `drawable/ic_mark_*.xml`, `values*/strings.xml`, `xml/widget_info.xml` | 마크 4종, 문자열 정리(미사용 14개 삭제), 위젯 기본 4×1 |
| 파서 | `provider/ConsumerUsageParser.kt` | 미배포였던 초기화 시각 줄 분리 수정(f3c3977)이 이 버전에 포함됨 |

### 검증

로컬 `testDebugUnitTest` **58 tests / failures 0 / errors 0** (54 → 58). `lintDebug` errors 0. `assembleDebug`, `assembleDebugAndroidTest` 통과. CI에서 `WebUsageCollectionTest`(API 35 에뮬레이터)가 위젯 슬롯 숫자 `45` → `40`과 RemoteViews의 `40` 텍스트를 확인한다.

새 단위 테스트: 링 숫자/채움/색 규칙 6건, 옛 위젯 JSON 호환 2건, 격자 계산 6건. 계기 테스트는 새 `WidgetSlot`으로 다시 썼다(0과 알 수 없음 구분, 캡션 줄이 있으면 경고 문구 생존).

### 미검증

- **실기기 표시.** 링의 숫자가 아크 열린 곳에 정확히 앉는지, One UI 셀 높이에서 캡션이 잘리지 않는지는 기기에서만 확인된다. 이 환경에 에뮬레이터가 없다.
- `↻` 글리프가 기기 글꼴에서 렌더되는지. 안 되면 문자열 4개(`widget_reset_*`)만 바꾸면 된다.
- 마크의 시각적 인지도(한눈에 서비스가 구분되는지).

---

## v0.2.1 — Grok 초기화 시각, 공식 로고, 세로 여백 (2026-09-22)

### 보고 (v0.2.0 실기기)

> 그록 초기화 시간 여전히 안불러와진다. 그리고 로고는 왜 니가 만들었어? 공식 로고를 가져와야지. 그리고 지금 너무 빽빽하게 보이잖아. y축도 사용하라고 한층에 다 몰아넣지 말고

### 1. Grok 초기화 시각 — 확정된 원인

파서는 라벨("매주 SuperGrok 한도")이 있는 줄부터 **6줄까지만** 한 항목의 섹션으로 자른다(`start + 6`). innerText에서는 `0% / 중고 / 2026년 9월 25일 오후 4:39 초기화`가 라벨 뒤 3줄 안에 있어 충분했다. 그러나 Grok 값은 v0.1.16부터 **구조적 캡처**로 읽는데, 이 캡처는 DOM 노드마다 한 줄을 쓰고 진행 막대의 aria 값·CSS 생성 콘텐츠·폼 value도 각각 한 줄을 차지한다. 그 줄들이 라벨과 "초기화" 사이에 끼면 "초기화"가 7번째 줄 밖으로 밀려나 **리셋 파서가 보는 텍스트에 아예 들어오지 않는다.**

미배포 수정(f3c3977, 이웃 줄 합치기)은 시각과 "초기화"가 갈라진 문제만 다뤘고, 둘 다 6줄 안에 있을 때만 통했다. 그래서 v0.2.0에서도 그대로였다.

### 변경

- `ConsumerUsageParser.parse`: 수치는 지금처럼 6줄 안에서만 읽되, **리셋 시각은 다음 항목 라벨 전까지 최대 24줄**에서 다시 찾는다(`resetWindow`). 다른 항목의 시각을 끌어오지 않도록 다음 라벨에서 멈춘다.
- `parseReset`: 키워드("초기화/reset/재설정")가 있는 줄을 모두 순회한다. **절대 시각**은 키워드 앞뒤 3줄 안에서, **상대 시간("3시간 후")은 키워드 줄과 바로 앞 줄에서만** 인정한다. 후자를 좁게 잡은 이유: Grok 화면의 `재설정 가능 / 1일 후 만료`는 적립 리셋의 만료이지 이 항목의 초기화가 아니다. 키워드 줄 어디서도 못 찾으면 창 전체에서 절대 시각만 한 번 더 찾는다.
- `ProviderWebActivity.usageContext`: 진단 추적의 `read-context`/`rich-context`를 8줄 → 16줄로 늘려, 다음에 같은 유형의 문제가 나면 추적만으로 판단할 수 있게 했다.

### 2. 공식 로고

v0.2.0의 자체 글리프는 "Provider 로고를 배포하지 않는다"는 README 방침을 따른 것이었으나 사용자가 공식 로고를 요구했다. 각 서비스 식별 목적의 상표 사용이며, 벡터 경로는 배포 패키지에서 가져왔다.

| 서비스 | 출처 | 비고 |
| --- | --- | --- |
| Codex(ChatGPT) | `simple-icons` `openai.svg` | OpenAI 로고 |
| Claude | `simple-icons` `claude.svg` | Claude 별 모양 마크(Anthropic 워드마크 아님) |
| Grok | `@lobehub/icons-static-svg` `xai.svg` | 2025년 사선형 마크. 같은 패키지의 `grok.svg`는 구형 나선 로고이고 Android 경로 파서가 못 읽는 압축 arc 표기를 써서 쓰지 않았다 |
| API/데모 | 자체 프롬프트 글리프 | 공식 로고가 없는 항목 |

drawable은 `ic_mark_*.xml`에 그대로 두어 앱·위젯 호출부는 바뀌지 않았다. lint의 vector path 검사 통과.

### 3. 세로 여백

**위젯.** 기본 배치를 4×1 → **4×2**로 바꿨다(`targetCellHeight=2`, `minHeight=110dp`). 격자 선택도 바꿨다: 열 수별 후보를 모두 계산해 링이 가장 큰 배치를 고르되, **줄을 더 쓰는 배치가 그 크기의 3/4 이상을 유지하면 줄이 많은 쪽**을 택한다. 4×2에 계정 4개면 한 줄에 68dp가 아니라 **2×2에 64dp**가 되고, 세로로 긴 2×2 위젯에 계정 2개면 위아래로 쌓인다. 한 칸 높이(4×1)에서는 참조한 배터리 위젯처럼 링과 이름만 두고 초기화 캡션은 생략한다(캡션은 줄 높이 118dp 이상에서만). 링과 이름 사이 6dp, 이름과 캡션 사이 2dp 간격을 넣었다.

**앱.** 홈 카드의 링 가로 나열을 없애고 **항목마다 한 줄**로 쌓는다: 왼쪽 링(64dp), 오른쪽에 `5시간 한도` / `76% 남음`(큰 글씨) / `24% 사용 · 3시간 20분 후 초기화`. 카드 안 간격 18dp, 패딩 20dp. 상세 화면은 위쪽의 큰 링 가로 줄을 없애고 각 항목 카드 왼쪽에 링(72dp)을 넣어 같은 정보를 두 번 보여주지 않는다.

### 검증

로컬 `testDebugUnitTest` **62 tests / failures 0 / errors 0** (58 → 62). `lintDebug` errors 0(InvalidVectorPath 0). `assembleDebug`, `assembleDebugAndroidTest` 통과.

새 테스트: 숨은 노드 때문에 "초기화"가 7번째 줄로 밀린 Grok 형태에서 시각을 찾는지, 적립 리셋의 "1일 후 만료"를 초기화로 오인하지 않는지, 4×2에 4개가 2×2로 퍼지는지, 좁고 긴 위젯에 2개가 세로로 쌓이는지, 4×1에서 캡션이 빠지는지.

### 미검증

- 실제 Grok 페이지의 구조적 캡처에서 "초기화"가 라벨로부터 24줄 안에 있는지. 벗어나면 이번에는 16줄짜리 `rich-context` 추적으로 바로 확인할 수 있다.
- 공식 로고의 링 안 가독성(특히 Claude 마크의 가는 선이 작은 링에서 뭉개지는지).

---

## v0.2.2 — 위젯 정렬 · 새로고침 버튼 · 크기 (2026-09-22)

### 보고 (v0.2.1 실기기)

> 1. 초기화시간이 있는애랑 없는애랑 정렬 다르게된다. 있는애가 더 위로 올라가있음
> 2. 위젯에 새로고침 버튼 만들어서 그거 누르면 등록된 계정 다 새로고침할수 있도록. 앱에서는 새로고침 했는데 위젯은 바로바로 반영안됨
> 3. 지금 위젯 크기가 전체적으로 너무 크다

### 1. 정렬 — 원인과 변경

슬롯 Column은 내용 높이만큼만 차지하고 Row는 세로 중앙 정렬이다. 캡션(초기화 시각) 줄이 없는 슬롯은 한 줄 짧아 중앙에 맞춰지면서 링이 아래로 내려갔다. 캡션 줄이 있는 배치에서는 **캡션이 없어도 공백 한 줄을 그대로 둬** 모든 링이 같은 높이에 앉는다.

### 2. 새로고침 버튼

- 위젯 오른쪽 위에 ↻ 버튼. 22dp 열을 따로 비워 링과 겹치지 않는다. 폭 120dp 미만(1칸 위젯)에는 두지 않는다.
- 누르면 즉시 버튼이 노란색으로 바뀌고(Glance 위젯 상태 `refreshing`), WorkManager 단일 작업 `sync_all`(`SyncScheduler.refreshAll`)이 등록된 모든 계정을 한 번 수집한다. 데이터가 저장되어 `updateWidgets`가 돌 때 표시가 풀린다. `ActionCallback` 안에서 직접 수집하지 않는 이유: 웹 수집은 계정당 최대 40초라 브로드캐스트 수명 안에 끝나지 않는다.
- "앱에서 새로고침했는데 위젯 미반영": 코드상 `refresh()`와 `recordWeb()` 모두 저장 직후 `updateWidgets()`를 호출하고 있어 누락은 없었다. 다만 `updateWidgets`가 위젯 설정 정리(prune)에서 예외가 나면 `updateAll`까지 건너뛰는 구조였다. 정리·상태 초기화는 실패해도 `updateAll`은 항상 실행하도록 바꿨다. 실기기에서 다시 보이면 버튼으로 강제 갱신한 뒤 진단 로그로 좁힌다.

### 3. 크기

- 기본 배치를 **4×1로 되돌렸다**(v0.2.1의 4×2 기본은 홈 화면에서 너무 컸다). 링 최대 지름 120 → **76dp**, 제목 11–13sp, 캡션 9–11sp.
- 4×1: 링(약 56dp) + 별칭. 세로로 2칸 이상 늘리면 초기화 캡션이 생기고, 계정이 4개면 2×2로 퍼진다. 줄을 더 쓰는 배치는 링이 최대 배치의 2/3 이상일 때 채택하며, 캡션은 링이 44dp 이상 남을 때만 넣는다.

### 검증

로컬 `testDebugUnitTest` **62 tests / failures 0 / errors 0**. `lintDebug` errors 0. `assembleDebug`, `assembleDebugAndroidTest` 통과. 레이아웃 테스트는 새 규칙(76dp 상한, 2/3 기준)에 맞춰 조정했다.

### 미검증

- 새로고침 버튼: 탭 → 노란색 → 수집 후 복귀가 기기에서 보이는지.
- 정렬 수정, 4×1 기본 크기의 체감.

---

## v0.2.3 — 집 PC 에뮬레이터 검증과 안정성 수정 (2026-09-22)

사용자 요청: 앱을 완성하고 집 컴퓨터의 모바일 에뮬레이션으로 직접 검증.

- 갱신 완료 표시를 전체 계정 pass 수명에 맞춤. 오프라인 수동 작업의 무한 대기 제거, 저장 실패 격리 및 전달.
- 의미 없는 %를 used로 추측하던 파서, 일반 Codex 제목 오인, reset 다음 줄 상대 시간 처리 수정.
- 계정 추가 폼 스크롤/키보드 인셋, 상세 좁은 레이아웃, 전 화면 오류 메시지, 중복 동작, 다크 상태바 대비 수정.
- 전체 Android 테스트를 실행하니 기존 ProfileIsolationTest가 실패했다. destroy 누락이 아니라 loaded profile의 deleteProfile 금지 제약이었다. 계정별 쿠키/웹 저장소 삭제와 이름 재사용 차단, 다음 프로세스 물리 삭제, 로그아웃 새 프로필 할당으로 해결. 구형 WebView의 대체 경로도 검증했다.
- 계정 CRUD/테마/새로고침/키보드와 실제 위젯 호스트→WorkManager 갱신을 테스트에 추가. 기존 CI는 WebUsageCollectionTest만 실행했으나 이제 전체 Android 테스트와 화면 캡처를 보관한다.

검증: JVM 71개, Android 20개, 한국어 추가 3개 통과. 360×640dp와 글꼴 130%, 화면 키보드 조건 포함. Lint 오류 0/경고 51. debug/release(unsigned)/test APK 빌드 통과. 프로세스 재시작 후 삭제 대기 6→0 확인.

실제 제공자 로그인 성공으로 확대 해석하지 않는다. 범위/캡처는 [QA_V023.md](QA_V023.md), 집 PC 재개 방법은 [HANDOFF_HOME.md](HANDOFF_HOME.md).
---

## v0.2.4 — 로그인 재시작·Grok·실제 위젯 터치 (2026-09-22)

- 로그인 재시작은 해당 계정의 수집을 취소하고 프로필을 교체한다. 기존 실패 세션 재사용 및 수집 mutex를 40초 기다리던 문제를 해결했다. 다른 계정의 전체 갱신은 유지한다.
- Grok의 분리된 숫자/%를 연결하고 Grok/Codex의 영문 월 이름 초기화 시각을 지원한다. 실제로 차단된 auth.grokusercontent.com 인증 콜백만 호스트 허용 목록에 추가했다.
- 위젯 새로고침 48dp 터치 영역을 확보하고 패널 전체의 앱 열기를 제거했다. 에뮬레이터의 실제 Pixel Launcher에서 기존 앱 실행 문제와 수정 후 백그라운드 수집·표시 갱신을 확인했다.
- 상태 메시지에 계정 이름이 밀리는 홈/상세 배치를 수정했다.
- 실제 세션과 자동 테스트를 별도 AVD에 분리했다. 새 로그인 환경, 수집 중단 격리, Grok 실제 WebView DOM, 부착된 위젯의 실제 터치 회귀 테스트를 추가했다.

검증과 재현 환경: [QA_V024.md](QA_V024.md). 실계정 캡처는 공개 저장소에 올리지 않는다.

---

## v0.2.5 — 위젯 배열 고정 · 갱신 완료 표시 · 새 버전 알림 · 아이콘 (2026-09-23)

### 보고 (v0.2.4 실기기, 회사 PC로 복귀 후)

> 1. 위젯 스크린샷의 뒷배경을 깔끔하게 지워 README 맨 위에 첨부
> 2. 4×1을 4×2로 키우면 배열이 2×2가 되는데, 4×n이면 4로 고정. 2×3이면 2개씩 3줄
> 3. 초기화 시간은 항상 아래에
> 4. 새로고침의 주황색이 진행 중이면, 끝났을 때 초록색을 3초 보여주고 원복
> 5. 구버전 앱을 열면 업데이트를 권장하는 팝업
> 6. 앱 아이콘을 usage 느낌으로, 다른 리포처럼 상단에 eslee

### 변경

**배열.** `WidgetLayoutResolver.cells(width)`가 위젯 폭을 72dp 단위로 나눠 가로 칸 수를 추정하고, 열 수는 계정 수와 무관하게 이 값으로 고정된다. 줄 수는 `ceil(계정 수 / 열 수)`이며 높이가 허용하는 만큼만 쓴다(줄당 최소 70dp). 4×2에 계정 4개면 한 줄에 4개, 5개째부터 둘째 줄. 2×3에 6개면 2개씩 3줄. 슬롯 폭은 항상 칸 폭이라 계정이 칸보다 적어도 링 크기는 같고 남는 줄은 가운데 정렬된다. v0.2.2의 "링이 2/3 이상이면 줄을 늘림" 규칙은 제거했다.

**초기화 캡션.** 링이 최소 크기(36dp)를 지키는 한 항상 별칭 아래에 둔다. 그 대신 텍스트 예산을 줄였다: 제목 12sp/15dp, 캡션 10sp/13dp, 간격 3/1dp, 패널 여백 6dp. 실기기 4×1(약 85dp 높이)에서 링 43dp + 두 줄이 들어간다. 새로고침 버튼(48dp 열)은 폭 200dp 이상에서만 두어 2칸 위젯의 링이 작아지지 않게 했다.

**새로고침 표시.** 위젯 상태에 `refreshed_at`을 추가했다. 전체 pass가 끝나면 `finishWidgetRefresh`가 `refreshing`을 지우고 시각을 기록해 버튼을 초록으로 그린 뒤, 3초 후 한 번 더 그려 원래 색으로 돌린다. 시각 기반이라 다른 이유로 늦게 다시 그려져도 초록이 남지 않는다. 진행 중은 그대로 주황.

**새 버전 알림.** `UpdateChecker`가 `api.github.com/repos/esleeeeee/eslee-llm-usage/releases`(프리릴리즈 포함, 최근 5개)를 읽어 설치 버전보다 높은 태그가 있으면 팝업을 띄운다. 앱을 열 때 6시간에 한 번만 확인하며(`updateCheckedAt`), "이 버전 건너뛰기"는 `updateSkipped`에 남고, 설정 → 정보의 "새 버전 알림" 스위치로 끌 수 있다. 다운로드 버튼은 릴리즈의 APK 자산(없으면 릴리즈 페이지)을 브라우저로 연다. 기기 정보는 보내지 않는다.

**아이콘.** 다른 eslee 리포와 같은 형식: 흰 둥근 사각 판 위에 "eslee" 워드마크, 구분선, 그리고 이 앱의 배터리 링(76). GDI+ 스크립트로 렌더한 적응형 아이콘 전경 PNG 5종(`mipmap-*/ic_launcher_foreground.png`, 배경 `#F6F8FA`)과 README용 `assets/branding/eslee-llm-usage.png`. 예전 막대 아이콘 `drawable/ic_launcher.xml`은 삭제.

**README.** 다른 리포처럼 아이콘·제목·설명·배지·다운로드 링크를 가운데 정렬하고, 실기기 4×1 위젯 스크린샷(배경 제거, 모서리 둥글게)을 `docs/images/widget-4x1.png`로 첨부했다.

### 검증

로컬 `testDebugUnitTest` **80 tests / failures 0 / errors 0** (75 → 80: UpdateChecker 5건 추가, 레이아웃 테스트는 새 규칙으로 교체). `lintDebug` errors 0. `assembleDebug`, `assembleDebugAndroidTest` 통과. 계기 테스트는 CI에서 실행.

### 미검증

- 실기기에서 4×1에 두 줄(별칭+초기화)이 들어가는지, 4×2에서 4개가 한 줄로 유지되는지.
- 초록 3초 → 원복이 런처에서 보이는지.
- 업데이트 팝업은 다음 릴리즈(v0.2.6)가 나와야 실제로 뜬다.
- 삼성 런처의 아이콘 마스크 안에서 워드마크가 잘리지 않는지(안전 영역 66% 안에 그렸다).

### CI에서 잡힌 결함 (2026-09-23, fbe7672)

첫 CI 실행이 `WidgetRefreshIntegrationTest`에서 실패했다. 보관된 증거(`widget-refresh-failure.txt`)를 보면 첫 탭은 정상이었다: 터치가 48dp 버튼에 전달됐고 워커가 SUCCEEDED, 두 계정의 스냅샷이 바뀌었다. 그런데 두 번째 탭 대상을 찾는 15초 동안 버튼의 설명이 계속 `Refreshed`였다. **초록 상태가 원복되지 않은 것**이다.

원인: `finishWidgetRefresh`가 3초 뒤 `updateAll()`만 다시 호출했는데, Glance는 위젯 **상태가 바뀔 때만** 다시 그린다. 시간만 지난 재호출은 같은 상태를 다시 읽고 끝나서 화면이 그대로였다. 실기기에서도 새로고침 뒤 버튼이 다음 데이터 갱신 때까지 초록으로 남았을 결함이다.

수정: 완료 시각 기록 → 초록으로 그림 → 3초 후 **완료 시각 키를 제거**(상태 변경) → 다시 그림. 렌더는 키의 존재로 초록을 판단하고, 프로세스가 중간에 죽어 키가 남는 경우만 12초 시간 가드로 무시한다. `RefreshStateTest` 4건 추가. 같은 증거로 링 배열(2개, 72dp 슬롯)과 탭 전달은 새 레이아웃에서도 정상임이 확인됐다.

### CI에서 잡힌 결함 2 (2026-09-23, 930992e)

위젯 테스트는 통과했고 이번엔 `EmulatorJourneyTest`가 설정에서 다크 테마를 누른 뒤 1초 안에 `theme == "DARK"`가 되지 않아 실패했다. 두 경쟁이 겹칠 수 있는 구조였다.

1. 새 버전 확인 효과가 `settings.current()`를 읽고 `updateCheckedAt`만 바꿔 **전체 설정을 다시 썼다**. 그 사이 사용자가 고른 테마가 있으면 이전 값으로 되돌린다. `SettingsStore.edit`(DataStore 트랜잭션 안에서 read-modify-write)를 추가해 확인 시각·건너뛴 버전 기록은 이 경로만 쓴다.
2. v0.2.3에서 `action`에 들어간 `if (busy) return` 가드가 **새로고침이 끝나기 전에 들어온 설정 변경을 조용히 버렸다**. 테스트는 새로고침 직후 설정으로 이동해 테마를 바꾸므로 타이밍에 따라 재현된다. 설정 변경은 busy 가드가 없는 `settle`로 저장한다(계정 작업 중복 방지라는 가드의 목적과 무관).

CI 3회차(607182e)도 같은 지점에서 실패했다. 릴리즈 API 응답(v0.2.4가 최신)으로 팝업이 뜬 것은 아니었고, 통과한 회차와의 앱 코드 차이도 위젯 파일뿐이었다. 남는 설명은 느린 CI 에뮬레이터에서 DataStore 쓰기가 1초를 넘긴 것이다. 조치: (1) 새 버전 확인을 앱 시작 5초 뒤로 미룬다 — 시작 직후 TLS 핸드셰이크가 첫 조작과 CPU를 다투지 않고, 사용자에게도 열자마자 팝업이 뜨지 않는 편이 낫다. (2) 여정 테스트는 `updatePrompts=false`로 시작하고 테마 저장 대기를 다른 저장 대기와 같은 10초로 둔다(검증 내용은 그대로).

---

## v0.2.6 — 리셋권: 개수·만료 표시와 전용 위젯 (2026-09-23)

### 요청

> 리셋권 뭔지 알아? usage 초기화 시켜주는거. 그게 만료기간이 있거든? 계정별로 그거 만료가 언제까지인지, 리셋권 몇개남았는지도 알고싶어. 얘는 리셋권 전용 위젯을 따로 만들어서.

### 페이지 문구 (fixture)

- Codex 한국어: `사용량 한도 재설정` 아래 리셋권마다 `전체 재설정(주간 + 5시간)` / `9월 21일 오전 7:41에 만료` / `재설정 사용`. 개수 = 항목 수.
- Codex 영어: `Usage limit resets` / `Available 2` / `Full reset` / `Expires Oct 4, 1:58 AM`.
- 구 대시보드: `3 resets available`.
- Grok 한국어: `사용 한도 재설정` / `재설정 가능` / `1일 후 만료` (하나, 하루 뒤 만료).

### 변경

- 모델: `ResetCredit(label, expiresAt)`, `UsageSnapshot.resetCredits`. 이전의 `resets` 항목(개수만 REQUESTS 단위로 넣던 가짜 버킷)은 제거했다. 저장된 옛 스냅샷은 필드 기본값(빈 목록)으로 읽힌다.
- 파서 `parseResetCredits`: 헤더 뒤 구간을 크레딧/자동 충전 문구 전까지 읽어 `만료|expir` 줄마다 리셋권 하나(직전 항목 줄이 라벨). 만료 시각은 연도 없는 `M월 D일 오전 H:MM` / `Oct 4, 1:58 AM`을 기기 시간대로 해석하고(과거 60일 이전이면 다음 해), ISO·상대 시간도 받는다. 페이지가 개수(`Available 2`, `3 resets available`)를 더 크게 말하면 만료 없는 항목으로 채운다.
- 앱: 홈 카드 하단 `리셋권 2개 · 3일 후 만료`, 상세 화면에 리셋권 카드(항목별 라벨 · N일 후 만료 · 절대 시각).
- **리셋권 위젯** `ResetsGlanceWidget`/`ResetsWidgetReceiver`(위젯 목록에 "LLM 리셋권"으로 별도 등장, 기본 3×1): 계정마다 한 줄 — 마크 · 별칭 · **N개** · `3일 후 만료`(없으면 `리셋권 없음`, 로그인 필요 등 경고는 노란색). 링 위젯과 같은 설정 화면·저장소를 쓰며 항목 선택만 숨긴다. `updateWidgets`는 두 위젯을 모두 다시 그리고, 설정 정리(prune)도 두 리시버의 id를 합쳐서 본다(안 그러면 리셋권 위젯 설정이 삭제된다).
- 설정 → 위젯 목록도 리셋권 위젯을 줄 형태로 미리 보여준다.

### 검증

로컬 `testDebugUnitTest` **88 tests / failures 0 / errors 0** (84 → 88: Codex 한/영, Grok, 없음). `lintDebug` errors 0. `assembleDebug`, `assembleDebugAndroidTest` 통과. 계기 테스트는 CI.

### 미검증

- 실기기에서 리셋권 개수·만료가 페이지와 일치하는지(특히 영문 만료 형식은 fixture 한 줄뿐).
- 리셋권 위젯의 줄 높이(30dp)가 One UI 셀에서 잘리지 않는지.
- 만료가 지난 리셋권을 페이지가 계속 보여주는 경우의 표시("만료됨").

---

## v0.2.7 — 일주일 사용 후 수집 안정성·정확도 수정 (2026-09-29)

### 보고 (실기기 일주일 사용)

> 1. 새로고침이 10번 중 7번은 실패. 2. 새로고침이 느림, 주황색이 30초 이상. 3. 초록색으로 끝났는데 92% 그대로, 실제로는 52% 사용/48% 남음. 앱 안에서 새로고침해도 반영 안 됨. 주간 한도랑 Imagine 한도가 왜 나뉘어 있나, 통합인데. 4. 위젯 2행이면 "n일 n시간 남음"과 "n일 몇 시" 표시. 5. 계정 안에서 대표 전환(5시간/주간)이 제대로 안 됨. 6. 리셋권 위젯이 전부 0개. 정해둔 주기의 자동 새로고침도 안 되는 것 같다.

실기기 진단 기록 없이 코드로 확정한 원인과, 아직 기기 확인이 필요한 부분을 구분한다.

### 확정된 원인과 변경

**A. Grok 제품별 비율이 별도 한도가 되어 주간 수치를 가렸다 (#3 "Imagine", "92%").** Grok은 주간 한도 하나를 두고 제품별(Chat·Imagine 등) 사용 비율을 그 아래 나열한다. 파서는 이 제품 줄을 각각 한도 버킷으로 만들었다. 주간 수치는 innerText 밖(구조 캡처에서만 보임)에 그려지는데, 페이지에 제품 비율 숫자가 생기자 "보이는 텍스트에 숫자가 하나라도 있으면 구조 캡처를 쓰지 않는다"는 규칙 때문에 주간 수치를 영영 못 읽고 Imagine 비율(8% → 링 92)이 대신 표시될 수 있었다. 사용 초기(0%)에는 제품 줄이 없어 v0.1.16에서는 드러나지 않았다.
- 제품 라벨은 섹션 경계로만 쓰고 버킷을 만들지 않는다(`Label.quota=false`).
- `ConsumerUsageParser.parseBest`: 보이는 텍스트에 **대표 항목(Grok 주간, Codex/Claude 5시간→없으면 주간)** 수치가 없으면 구조 캡처를 쓴다. 판단 기준이 "숫자 아무거나"에서 "대표 항목 수치"로 바뀌었다.
- 스냅샷 `status`도 대표 항목 기준. 대표 항목 수치가 없는 결과는 성공으로 저장하지 않고(PARSE_FAILED) 마지막 정상값을 유지한다.

**B. 첫 숫자에서 바로 멈춰 캐시된 옛 값을 저장했다 (#3 "그대로", #6 리셋권 0개).** 백그라운드 수집기는 대표 수치가 처음 보이는 순간 페이지를 닫았다. 페이지가 이전 방문에서 캐시한 값을 먼저 그리고 서버 응답으로 교체하는 구조라면, 매번 캐시 값을 읽고 새 응답이 오기 전에 WebView를 파기해 캐시도 갱신되지 않는다 — 값이 영구히 고정되는 경로다. 같은 이유로 늦게 그려지는 리셋권 목록도 읽히지 않았다.
- 수치가 **2초간 변하지 않고**, 페이지의 새 요청이 **1.5초간 없고**, 첫 수치 후 **최소 3초**가 지나야 읽는다(계속 폴링하는 페이지는 수치가 6초 유지되면 읽음). 요청 감지는 `shouldInterceptRequest`의 시작 시각만 쓴다 — 페이지 JS를 수정하지 않는다(봇 탐지 위험 회피).
- 첫 수치 후 한 번 맨 아래로 스크롤해 아래쪽 섹션(리셋권)이 그려지게 한다.
- 이 방식은 캐시 값을 **완화**할 뿐 보장하지 않는다. 서버 응답이 첫 수치 후 3초 넘게 걸리면 여전히 캐시 값을 읽을 수 있다(미검증).

**C. 느림 (#2).** (1) 대표 외 항목에 수치가 없으면 PARTIAL로 판정해 계정마다 40초를 다 기다렸다(A와 겹쳐 Grok이 매번 40초). (2) 모든 계정이 하나의 잠금으로 순서대로 수집됐다. (3) 수동 새로고침이 일반 작업이라 대기 버킷이 낮은 앱에서는 시작 자체가 늦었다. (4) 실패한 수동 새로고침이 숨은 재시도(30초 백오프) 동안 주황색을 붙잡았다.
- 대표 항목 기준 완료 판정 + 안정화 대기(보통 첫 수치 후 3~4초), 동시 3개 페이지(`Semaphore(3)`), 이미지 차단, 폴링 1초→0.5초.
- 위젯 수동 새로고침은 **expedited** 작업(Android 12+ 즉시 실행, 11 이하에서는 짧은 포그라운드 알림)이며 재시도하지 않는다.
- 로그인 페이지가 6초 유지되면 40초를 기다리지 않고 즉시 로그인 필요로 끝낸다. 메인 프레임 네트워크 오류는 한 번 다시 불러오고, 또 실패하면 바로 실패 처리한다.

**D. 새로고침이 "아무것도 안 함" (#1, #3 앱 안 새로고침).** 계정별 잠금을 `tryLock`으로 잡고 실패하면 즉시 반환했다. 앱을 열면 자동 수집이 바로 시작되므로, 그 사이 누른 새로고침(앱·위젯)은 아무것도 하지 않고 끝났다 — 위젯은 곧바로 초록이 됐다. 이제 진행 중인 수집에 **합류해 결과를 기다리고**, 그 결과가 실패였을 때만 다시 수집한다.

**E. 초록 = 성공이 아니었다.** 수동 새로고침의 완료 표시는 결과와 무관하게 초록이었다. 이제 모든 계정이 읽혔을 때만 초록, 하나라도 실패하면 **빨강** 3초. 앱 안 새로고침도 실패 계정 수를 알린다("계정 N개를 갱신하지 못했습니다. 마지막 값은 유지됩니다").

**F. 대표 전환이 안 됨 (#5).** `updateAccount`가 수집과 같은 계정 잠금을 기다렸다(페이지 로드 동안 수십 초~분). UI에서는 새로고침 중 `busy` 가드가 탭을 조용히 버렸다. 잠금을 풀면 이번엔 수집 저장이 시작 시점의 계정 사본을 되써서 변경을 덮어쓰는 문제가 생기므로, 저장 시 계정을 **트랜잭션 안에서 다시 읽고** 동기화 필드만 바꾼다(`modifyAccount`). 로그아웃·키 변경도 같은 방식. 계정 편집은 busy 가드를 거치지 않는다. 웹 화면의 읽기(`recordWeb`)도 수집 잠금을 기다리지 않는다.

**G. 리셋권 위젯 0개 (#6).** B 외에 두 가지. (1) `cleanup()`이 링 위젯 id만 설치된 위젯으로 보고 **리셋권 위젯의 설정을 새로고침마다 삭제**했다(`updateWidgets`는 고쳤지만 `cleanup`을 놓쳤다). 두 리시버 id를 합치는 `installedWidgetIds`로 통일. (2) 리셋권 섹션을 못 본 스냅샷(업데이트 전 저장분 포함)도 "0개"로 표시했다. `resetCreditsKnown`을 추가해 못 본 경우는 "—"와 "아직 확인 전"으로 표시한다. 구조 캡처에서 만료 시각과 "만료"가 다른 줄로 갈라져도 읽는다.

**H. 위젯 2행 표시 (#4).** 행 높이에 여유가 있으면(링이 44dp 이상 유지) 별칭 아래 두 줄: `2일 5시간 남음` / `25일 16:39`(ko 패턴 `d일 H:mm`, en `MMM d H:mm`). 4×1은 기존 한 줄(`↻ 3일`).

**I. 자동 새로고침이 안 도는 것 같음.** 코드상 15분 주기 작업은 등록되어 있다. 삼성 기기의 배터리 최적화·절전 앱 목록은 WorkManager 작업을 몇 시간씩 미룰 수 있다. 홈 화면 안내 카드와 설정 → 동기화에 "백그라운드 실행 허용"(`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`)을 추가했다. 또한 진단 기록이 **파일에 저장**되어 앱이 꺼져 있던 동안의 자동 수집도 남는다: `pass`(주기/수동, 실패 수, 소요 시간)와 `bg-read`(계정별 결과·수치·리셋권 수·소요 시간), 실패 시 `bg-context`(라벨 주변 16줄).

### 검증

로컬 `testDebugUnitTest` **97 tests / failures 0 / errors 0** (88 → 97). `lintDebug` errors 0. `assembleDebug`, `assembleDebugAndroidTest` 통과. 새 계기 테스트 `RefreshConcurrencyTest`(느린 수집 중 대표 전환이 즉시 반영되고 저장 후 유지, 진행 중 새로고침에 합류해 한 번만 로드) — CI 에뮬레이터에서 실행.

기존 테스트 변경: Grok `chat` 버킷을 기대하던 파서 테스트를 "주간 하나만"으로(사용자 요청에 따른 의도된 변경), 진단 기록 시각 형식에 날짜 추가.

### 미검증 / 다음

- 실기기에서 Grok 주간 수치가 52%처럼 페이지와 일치하는지, 새로고침이 10초 안팎으로 끝나는지. 설치 후 **설정 → 진단 → 공유**로 `bg-read` 줄을 보내 주면 계정별 수치·소요 시간·실패 원인을 바로 확인할 수 있다.
- 캐시 값 가설(B)은 코드 구조에서 추론한 것이다. `bg-read`가 여전히 옛 값을 보이면 안정화 시간을 늘리거나 다른 신호가 필요하다.
- 배터리 최적화 제외 후 15분 주기가 실제로 도는지는 진단의 `pass web` 줄 간격으로 확인한다.

### CI (2026-09-29, 7d70169)

`WidgetRefreshIntegrationTest`가 실패했다. 보관된 증거: 새로고침 버튼이 DOWN/UP을 모두 받았지만 `sync_all` 작업이 **하나도 등록되지 않았다**(`work=[]`), 앱 예외 로그 없음. 탭은 Glance 위젯 세션이 시작된 지 약 1초 뒤였고(통과한 v0.2.6 실행은 약 2초 뒤), 첫 렌더 직후 호스트가 뷰를 교체하면서 이미 받은 클릭이 사라진 것으로 판단한다 — v0.2.4 CI(8f6354e)에서 같은 증상으로 실패했던 불안정 지점이다. 앱 코드는 바꾸지 않고 테스트를 보강했다: 탭 전 안정 대기 0.5→1.5초, 탭 후 10초 안에 작업이 없고 버튼이 여전히 "모든 계정 새로고침"(액션 미실행)이면 한 번 더 탭. 핸들러가 실제로 동작하지 않으면 두 탭 모두 실패하므로 검증 강도는 유지된다.

---

## 미배포 — 새 버전 알림이 뜨지 않음 (2026-09-29, 다음 패치에 포함)

### 보고

> 원래 026이었는데 업데이트 팝업 안떴음. 다음 패치때 다시 검증해

### 원인 (코드에서 확인)

세 가지가 겹쳤다. (1) GitHub 확인을 **6시간에 한 번**만 했다 — 0.2.7 출시 전(같은 날 아침)에 앱을 열었다면 출시 후 6시간 동안 다시 묻지 않는다. (2) **실패한 확인도 확인 시각으로 기록**해 오프라인·요청 제한 한 번이 6시간을 막았다(`check()`가 "새 버전 없음"과 "실패"를 모두 null로 반환). (3) 확인은 **화면이 처음 만들어질 때 한 번**뿐이라, 백그라운드에 있던 앱을 다시 열면 아예 확인하지 않았다.

### 변경

- `UpdateChecker.check()`가 `Newer` / `Current` / `Failed`를 구분한다. 실패는 확인 시각을 남기지 않는다.
- 앱이 앞으로 올 때마다(`repeatOnLifecycle(STARTED)`, 5초 뒤) 확인하며, 답을 받은 확인만 **30분** 동안 유효하다.
- 설정 → 정보에 "지금 새 버전 확인" 버튼. 건너뛴 버전도 다시 보여 주고, 최신이면 "최신 버전입니다", 실패하면 그 사실을 알린다.

### 검증

로컬 `testDebugUnitTest` **98 tests / failures 0 / errors 0** (MockWebServer로 403 → 실패, 목록 → 새 버전, 최신 → 현재 구분). `lintDebug` errors 0.

### 다음 패치 때 확인할 것

0.2.7에는 옛 로직(6시간 제한)이 들어 있다. 0.2.8이 나왔을 때 0.2.7 앱에서 팝업이 바로 안 뜨면 마지막 확인이 6시간 이내였던 것이고, 6시간 뒤 앱을 열면 떠야 한다. 개선된 로직의 실기기 확인은 0.2.8 설치 후 그다음 버전에서, 또는 0.2.8의 "지금 새 버전 확인" 버튼으로 바로 가능하다.

---

## v0.2.8 — 지난 주기 값 거부, 백그라운드 페이지 깨우기, 리셋권 대기, 위젯 정렬 (2026-09-29)

### 보고 (v0.2.7 실기기 체크리스트)

> 앱 안에서 새로고침해도 Grok은 하나도 안 바뀐다. 값을 못 읽은 것과, 못 읽었는데 정상으로 표시하는 것, 두 가지 문제다. 새로고침 27초. 대표 전환은 잘 된다. 리셋권은 프로·플러스 둘 다 3개인데 플러스만 3개로 나온다. 계정 페이지에서 리셋권이 각각 며칠 남았는지 보고 싶다. 위젯 3개가 다닥다닥 붙어 있고 전체적으로 왼쪽으로 치우쳐 있다.

실기기 진단 기록 없이 코드와 에뮬레이터 실험으로 판단했다. 기기에서 확인할 부분은 아래 "미검증"에 따로 적는다.

### A. Grok 값 고정: 가설 하나는 실험으로 기각, 두 가지를 넣었다

백그라운드 수집기의 WebView는 창에 붙지 않는다. 사이트는 계속 바뀌는데 수집기는 매번 같은 값을 "성공"으로 저장한다면, 페이지가 이전 방문 때 캐시한 값을 그리고 서버에 다시 묻지 않는 것으로 본다(사용자가 앱 안에서 Grok 웹 화면을 직접 열면 그 캐시가 갱신된다).

**기각된 가설 — 그려지지 않는 페이지는 프레임을 못 받는다.** 처음에는 창에 없는 WebView는 그려지지 않아 `requestAnimationFrame`·`IntersectionObserver`가 멈추고, 그 시점에 캐시를 재검증하는 페이지(SWR 등)가 옛 값에 머문다고 봤다. 1×1 캔버스에 그려 프레임을 공급하는 코드를 만들고 CI 에뮬레이터(WebView 124.0.6367.219)에서 그리지 않을 때와 비교했다.

```
not drawn: {"visibility":"visible","frames":46,"late":true,"top":true,"bottom":true}
drawn:     {"visibility":"visible","frames":226,"late":true,"top":true,"bottom":true}
```

그리지 않아도 4초에 46프레임이 돌고, 늦게 요청한 rAF와 화면 진입 감지 모두 동작했다. **원인이 아니므로 프레임 공급은 넣지 않았다.** 이 사실은 `BackgroundPageTest.aPageNobodySeesStillGetsFrames`로 남겨, 엔진이 바뀌면 드러나게 했다.

**넣은 것 1 — 백그라운드 페이지에 "사용자가 돌아옴"을 한 번 알린다.** 화면에 있는 페이지는 사용자가 탭을 떠났다 돌아오면 숨김→보임 전환(`visibilitychange`)을 받는다. 캐시를 그때 다시 확인하는 페이지(SWR `revalidateOnFocus`, TanStack Query의 focus 재조회 — 둘 다 `visibilitychange`를 듣는다)는 이 신호가 없으면 옛 값에 머문다. 백그라운드 WebView는 이 신호를 절대 받지 못한다. 첫 수치가 보이면 `onPause()` → 300ms → `onResume()`으로 한 번 알린다. WebView 자체의 생명주기 호출이며 페이지 스크립트는 건드리지 않는다. 계기 테스트: 이벤트를 세는 페이지가 숨김·보임을 모두 들어야 하고, 보임 전환 때만 새 값을 그리는 페이지에서 새 값(52%)이 저장돼야 통과한다.

포커스(`requestFocus()` + `onWindowFocusChanged(true)`)도 함께 주던 판은 CI 2차에서 뺐다. 창 밖의 WebView에 포커스를 주면 앱이 공유하는 키보드(IME) 상태에 닿아, 뒤이은 테스트에서 앱 입력칸의 키보드가 뜨지 않고 위젯 호스트 위에는 키보드가 떠 있는 것으로 잡혔다.

**넣은 것 2 — "못 읽었는데 정상으로 표시" 방지.** 대표 한도가 **이미 1시간 넘게 지난 초기화 시각과 함께 0%보다 큰 사용량**을 보이면 현재 값일 수 없다(초기화 전에 캐시된 값). 수집기는 그런 페이지에 첫 수치 후 10초까지 새 값을 기다리고, 끝까지 그대로면 `STALE_PAGE` 실패로 처리해 마지막 정상값을 유지한다. 웹 화면의 읽기(`recordWeb`)도 같다. 계정 상태는 "페이지가 초기화 전 값을 표시 · 마지막 값 유지", 새로고침 결과는 실패(위젯 빨강, 앱 알림). 사용량 0%이거나 초기화 시각이 없으면 판단하지 않는다(쉬는 기간과 구분할 수 없음). **같은 주기 안에서 캐시된 값은 이 규칙으로 잡히지 않는다.**

**진단 — 다음 원인을 기기 기록으로 가르기 위해.**
- `bg-read`: 처음 보인 수치가 최종과 다르면 `was[...]`(캐시→새 값 교체가 실제로 일어남), 첫 수치 이후 페이지가 시작한 요청 수 `net+N`, 거부된 읽기는 `[읽은 값]`.
- `bg-net`: 페이지가 이번 로드에서 보낸 데이터 요청(fetch/XHR)의 **경로만**(호스트·쿼리·깊은 경로 제외), 페이지 시각, 출처(`net` 네트워크 / `cache` HTTP 캐시 / `sw` 서비스 워커). 사용량 관련 경로가 먼저. 첫 수치가 보인 페이지 시각(`figures@1.2s`)도 함께. 사용량 요청이 없으면 앱 저장소 캐시, `cache`면 HTTP 캐시가 원인이다.
- `bg-storage`(구조 캡처로 읽은 Grok만): localStorage **키 이름**(값 제외). 페이지가 자체 저장소에서 그린다면 여기서 보인다.
- `bg-source`: 구조 캡처로만 읽은 수치의 주변 줄.
- 진단 문맥은 이제 제공자의 **한도 라벨**에서 시작한다. 전에는 "usage/사용량/한도"가 들어간 첫 줄에서 시작해 사이드바의 대화 제목이 걸리면 그 제목들이 기록될 수 있었다.
- 긴 진단은 `WebTrace.recordLong`으로 번호 붙은 여러 줄(각 160자)로 남긴다. 전체를 먼저 이메일·긴 토큰 제거한 뒤 나눈다.

### B. 리셋권 목록을 기다리지 않았다 (프로 계정 리셋권)

리셋권 목록은 페이지 맨 아래에 있고 사용량 수치보다 늦게 오거나 화면에 들어와야 그려질 수 있다. 수집기는 수치가 안정되면 바로 끝나 목록 전에 페이지를 닫을 수 있었다.

- Codex는 리셋권을 하나도 못 읽었으면 첫 수치 후 최대 7초까지 기다리고, 2초마다 다시 맨 아래로 스크롤한다.
- 파서: 만료 줄 수 외에 항목마다 있는 **"재설정 사용" 버튼 수**와 섹션 안의 개수 문구("3개 사용 가능")도 센다. 만료를 표시하지 않는 항목도 빠지지 않는다.
- 진단: Codex 수집마다 `bg-resets` 줄에 리셋권 섹션(최대 12줄)을 남긴다. 개수가 또 다르면 이 줄로 페이지 구조를 바로 확인한다.

### C. 계정 화면 리셋권

리셋권 카드가 항목별로 한 줄씩: 무엇을 복원하는지, **남은 시간(`5일 12시간 남음`)**, 만료 시각(`10월 4일 (토) 오전 9:50 만료`). 만료가 가까운 순서, 24시간 미만은 빨간색. Codex는 개수가 0이거나 아직 못 읽었을 때도 카드가 그 상태를 말한다. 하단에 읽은 시각.

### D. 위젯 정렬

- 새로고침 버튼 자리(48dp)를 오른쪽에만 비워 링 묶음이 위젯 중앙보다 왼쪽에 있었다. 이제 양쪽에 28dp씩 비우고 버튼은 오른쪽 위 모서리(48dp 터치 영역, 기존 계기 테스트의 최소 크기 요구)에 둔다. 링은 위젯 중앙 기준으로 놓인다.
- 한 줄 위젯에서 계정이 칸 수보다 적으면(4칸에 3개) 링이 칸 폭으로 가운데에 몰렸다. 이제 한 줄은 폭을 계정 수로 고르게 나눈다. 링 크기는 칸 기준 그대로다. 여러 줄이면 칸 열을 유지한다.
- 높이에 여유가 있으면 링-이름 간격 3→6dp, 줄 간격 1→2dp.
- `WidgetLayoutResolver.forWidget`이 버튼 자리까지 계산해 위젯과 렌더링 테스트가 같은 계산을 쓴다. 3개 링 스크린샷(`widget-3rings-320x100/200.png`)은 CI 산출물에 남는다.

### 포함된 미배포 변경

- 새 버전 확인 수정(0b8dad9): 앞으로 올 때마다 확인, 실패는 기록하지 않음, 설정 → 정보 → "지금 새 버전 확인".

### 검증

로컬 `testDebugUnitTest` **103 tests / failures 0 / errors 0** (98 → 103: 버튼·개수 문구로 센 리셋권, 진단 기준 라벨, 양쪽 여백, 줄 간격, 지난 주기 값). `lintDebug` errors 0. `assembleDebug`, `assembleDebugAndroidTest` 통과.

CI 1차(6d10d02): 계기 테스트 30개 중 29개 통과. 실패 1건은 새로고침 버튼 터치 영역을 40dp로 줄여 기존 `WidgetRefreshIntegrationTest`의 48dp 요구를 어긴 것 — 48dp로 되돌렸다. 같은 실행의 프레임 관찰이 위 A의 가설을 기각했다. 날짜가 고정된 Grok 계기 fixture(9월 25일)는 그날이 지나 지난 주기 값이 되므로 오늘 기준 날짜로 바꿨다.

CI 2차(363e95a): 32개 중 30개 통과(새 `BackgroundPageTest` 6건 모두 통과). 실패 2건 `EmulatorJourneyTest.apiAccountFormCanBeSavedWithKeyboardOnSmallScreen`(키보드가 10초 안에 안 뜸)과 `WidgetRefreshIntegrationTest`(키보드가 보인다고 판단해 탭 대기 15초 초과)는 둘 다 키보드 상태였다. 포커스 호출이 없던 1차에서는 두 테스트 모두 그 지점을 지났으므로 포커스 호출을 원인으로 보고 뺐다.

CI 3차(063a43c): 계기 테스트 **32개 모두 통과**(키보드 테스트 2건 포함), v0.2.8 프리릴리스 게시(`eslee-llm-usage-v0.2.8.apk`). 에뮬레이터 기록(`qa/background-page.txt`, WebView 124, 2차 실행분): 그리지 않은 페이지 4초 47프레임·늦은 rAF·화면 진입 감지 모두 동작, 깨우기 후 페이지가 숨김 1회·보임 1회를 들음. 3차에서도 두 테스트 모두 통과했다.

### 미검증

- 실기기 Grok이 "돌아옴" 신호로 새 값을 받는지. 설치 후 새로고침하고 **설정 → 진단 → 공유**의 `bg-read grok`, `bg-net`, `bg-storage` 줄을 보면 된다: `was[...]`가 붙거나 수치가 사이트와 같으면 해결. 여전히 옛 값이면 `bg-net`에 사용량 요청이 있는지, `cache`인지로 원인을 가른다.
- 프로 계정 리셋권 개수. 다르면 `bg-resets` 줄로 확인한다.

---

## v0.2.9 — Grok 52%가 2%로 읽히던 원인, 프로 계정의 가짜 5시간 한도 (2026-09-29)

### 보고 (v0.2.8 실기기, 진단 공유)

> 나머진 잘되고, 자동새로고침 확인 못했고, (진단 기록 첨부)

v0.2.8에 넣은 진단 줄이 원인을 바로 보여 줬다.

```
bg-source · grok 매주 SuperGrok 한도 | 52% | 5 | 2 | % | 중고 | Imagine | 52 | % | 2026년 10월 2일 오후 4:39 | 초기화 | ...
bg-read · grok "Grok 1" 10065ms weekly=2%used resets=? net+33
bg-net 1/3 · grok figures@3.9s /grok_api_v2.GrokBuildBilling/GetGrokCreditsConfig@2.2s:net /prod_mc_billing.ConsumerUiSvc/GetRemainingResets@3.8s:net ...
bg-read · chatgpt "Pro" 11199ms weekly=24%used+reset session=novalue+reset resets=3
bg-resets · chatgpt "Pro" 사용량 한도 재설정 | 재설정을 사용해 5시간 한도, 주간 한도 또는 둘 다를 복원하세요. | 사용 가능 | 3 | 내역 | 전체 재설정 | ...
```

### 확정된 원인과 변경

**A. Grok 값은 오래된 값이 아니라 잘못 읽은 값이었다.** Grok은 수치를 애니메이션 숫자로 그린다. 구조 캡처에는 접근성 라벨 `52%` 다음에 글자마다 한 노드씩 `5 | 2 | %`가 온다. 파서는 "숫자 줄 + `%` 줄" 한 쌍만 합쳐서 `2%`만 만들었고, 라벨 `52%`는 옆에 "중고"가 없어 의미를 알 수 없는 값으로 버려졌다. 그래서 52% 사용이 **2% 사용(98% 남음)**으로 저장됐다. 사용량의 끝자리만 보였으니 거의 바뀌지 않는 것처럼 보였다.
- `ConsumerUsageParser.joinSplitFigures`: `%`로 시작하는 줄 바로 앞의 숫자 줄 묶음(`5`, `2`, `.`, `5`)을 하나의 수치로 다시 합친다. 기존의 "숫자 + %" 두 줄 합치기도 이 규칙의 가장 짧은 경우로 흡수했다.
- `bg-net`은 Grok 데이터가 매번 네트워크에서 새로 온다는 것(`:net`)을 보여 줬다. 캐시 가설은 틀렸다.

**B. Grok 초기화 시각이 빠졌다.** 주간 수치와 날짜 사이에 제품별 비율(`Imagine | 52 | %`) 줄이 있고, 초기화 시각 탐색이 그 라벨에서 끊겼다. 이제 초기화 탐색은 다음 **한도** 라벨까지 이어지고(제품 비율 라벨은 수치만 끊는다), 리셋권 섹션 헤더에서는 멈춘다(리셋권 만료를 한도 초기화로 오인하지 않게).

**C. 프로 계정의 5시간 한도(`session=novalue+reset`)는 존재하지 않는 한도였다.** 프로 페이지에는 5시간 한도가 없다. 파서가 리셋권 섹션의 안내문 "재설정을 사용해 **5시간 한도**, 주간 한도 …"를 5시간 한도 제목으로 읽었고, 초기화 시각은 리셋권 만료(`오전 10:58`)에서 빌려 왔다. 이제 리셋권 섹션 안에서는 "재설정/reset"이 들어간 줄을 한도 제목으로 보지 않는다. 섹션 전체를 막지 않는 이유: 섹션이 한도들보다 먼저 나오는 페이지에서 진짜 한도까지 지우지 않기 위해서다.

**D. 리셋권 개수 줄 분리.** 라이브 페이지는 `사용 가능 | 3`처럼 단어와 숫자를 다른 줄에 둔다. 그 다음 줄의 숫자도 개수로 읽는다(목록이 다 그려지기 전에도 3개).

### 걷어낸 것 (v0.2.8에서 틀린 가설로 넣은 것)

- 페이지 "깨우기"(`onPause`/`onResume`): 캐시 재검증 가설용이었는데, `bg-net`상 데이터는 로드 시 네트워크에서 온다. 필요 없는 동작이라 뺐다.
- `bg-storage`(Grok localStorage 키 이름): 같은 가설용. 뺐다.
- `bg-net`은 성공한 읽기마다 3줄씩 남겨 진단 300줄을 빨리 밀어냈다. 이제 **실패한 읽기에만** 남긴다.
- 유지: 지난 주기 값 거부(`STALE_PAGE`), `bg-read`의 `was[...]`·`net+N`, `bg-source`, `bg-resets`.
- 추가: 성공했는데 수치 없는 한도가 있으면(`novalue`) 그 라벨부터 16줄을 `bg-context`로 남긴다.

### 자동 새로고침 (체크리스트 8번)

같은 진단에 `pass · web`이 13:46, 14:02, 14:18, 14:33, 14:49, 15:04, 15:20, 15:36, 15:52, 16:08, 16:23, 16:39, 16:55에 찍혀 있다. 약 15분 간격으로 자동 수집이 돌고 있다. 15:20 한 번은 세 계정 모두 45초 안에 사용량이 뜨지 않아 실패했다(Codex 화면 "사용량 데이터 불러오는 중", Grok 텍스트 11줄). 다음 회차는 정상이어서 일시적인 네트워크 문제로 본다.

### 검증

로컬 `testDebugUnitTest` **105 tests / failures 0 / errors 0**: 휴대폰 기록의 Grok 구조 캡처 그대로 52% 사용·48% 남음·10월 2일 16:39 초기화, 소수점 분리, 프로 페이지(주간만, 리셋권 3). `lintDebug` errors 0. 계기 테스트 `BackgroundPageTest`: 숫자를 shadow root에 한 글자씩 그리는 Grok형 페이지를 백그라운드 수집기로 읽어 52%·초기화 시각 저장. 깨우기·프레임 관찰 테스트는 기능과 함께 뺐다. CI 에뮬레이터에서 실행.

CI(781423e): 계기 테스트 **30개 모두 통과**, v0.2.9 프리릴리스 게시(`eslee-llm-usage-v0.2.9.apk`).

### 미검증

- 실기기 Grok이 사이트와 같은 값(예: 52% 사용·48% 남음)과 초기화 시각을 보이는지. 다르면 진단의 `bg-source grok` 줄.
- 프로 계정에서 5시간 한도(알 수 없음)가 사라지는지.

---

## v0.2.10 — 새 버전 알림이 또 뜨지 않음 (2026-09-29)

### 보고

> 028에서 029 넘어갈 때도 업데이트가 안 뜬다. 똑바로 뜨게 만들라고 했잖아.

### 원인

v0.2.8의 수정(0b8dad9)은 "앞으로 올 때마다 확인"이라고 했지만, **답을 받은 확인은 30분 동안 믿고 다시 묻지 않았다.** 0.2.8을 쓰면서 앱을 자주 열면 매번 "최신"이라는 답이 기록된다. 0.2.9 게시 직후에 앱을 열었을 때 직전 확인이 30분 안이면 확인 자체를 건너뛰어 알림이 없다. 0.2.6 → 0.2.7(6시간 제한)과 같은 구조의 실수를 시간만 줄여 반복했다.

같이 막은 위험: GitHub Releases API는 인증 없이 **IP당 시간당 60회**(`X-RateLimit-Limit: 60` 확인)다. 회사 와이파이처럼 IP를 공유하면 다른 사람의 요청으로 한도가 차서 403이 난다. 실패는 기록하지 않아 다음에 다시 묻지만, 한도가 찬 동안은 계속 실패한다.

배제한 것: 릴리스 빌드의 R8 축소. 로컬 `assembleRelease`의 `mapping.txt`에서 `UpdateChecker`와 `Release$$serializer` 등이 이름 그대로 유지되고, `usage.txt`에서 제거된 것은 정적 초기화·합성 람다뿐임을 확인했다(테스트는 디버그 빌드로만 돌아 이 부분이 비어 있었다).

### 변경

- 앱이 앞으로 올 때마다(5초 머문 뒤) **매번** 묻는다. 이전 답으로 건너뛰지 않는다. 5초 안에 다시 나가면 그 확인은 취소되므로 빠른 전환은 요청을 만들지 않는다.
- `UpdateChecker`: API가 답하지 않으면(403·5xx·네트워크 오류·읽을 수 없는 응답) **릴리스 피드**(`releases.atom`, API 한도 밖, 프리릴리스 포함)에서 태그를 읽는다. 피드에는 첨부 파일 정보가 없어 다운로드 버튼은 릴리스 페이지를 연다. 둘 다 실패하면 `Failed("api http 403, feed …")`로 이유를 남긴다.
- 확인마다 진단에 `update` 줄: `0.2.11 is newer than 0.2.10` / `0.2.10 is the latest` / `check failed: api http 403, feed …`.
- 설정 → 정보 → "지금 새 버전 확인" 아래에 **마지막 확인 시각**. 앱을 열 때마다 바뀌어야 하며, 멈춰 있으면 확인이 실패하는 중이다.
- `AppGraph.updateCheck`: 테스트가 게시 상태와 무관하게 답을 바꿔 넣을 수 있는 자리.

### 검증

- 로컬 `testDebugUnitTest` **106 tests / failures 0 / errors 0**. API 403 → 피드로 0.2.10 발견(페이지 링크, APK 없음), 둘 다 실패 → 이유 문자열. `lintDebug` errors 0.
- `assembleRelease`(R8) 통과, 위 매핑 확인.
- 계기 테스트 `UpdatePromptTest`: 1분 전에 "최신"이라는 답을 받은 상태에서 앱을 열면 새 버전(9.9.9) 알림이 떠야 한다. "나중에"로 닫고 앱을 나갔다 돌아오면 다시 묻고 다시 떠야 한다. 이전 로직(30분 유효)이면 첫 단계에서 실패하는 테스트다.

### 사용자 확인 순서

0.2.9에는 옛 로직(30분)이 들어 있어 0.2.10 알림은 늦게 뜨거나 한도 때문에 안 뜰 수 있다. 0.2.10은 직접 설치한다. 새 로직은 0.2.10 설치 뒤 설정 → 정보의 "마지막 확인" 시각이 앱을 열 때마다 바뀌는지로 바로 보이고, 알림 자체는 그다음 릴리스에서 확인된다.
