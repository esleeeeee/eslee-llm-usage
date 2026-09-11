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
