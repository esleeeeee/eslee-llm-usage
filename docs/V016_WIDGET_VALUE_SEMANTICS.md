# v0.1.6 — 위젯 Used/Remaining 의미 수정

상세 경위와 검증 근거는 [DEVELOPMENT_HISTORY.md](DEVELOPMENT_HISTORY.md)의 v0.1.6 항목에 있다. 이 문서는 계약 관점의 요약이다.

## 규칙

1. 위젯이 표시하는 사용량 숫자에는 **항상 '사용' 또는 '남음'이 함께 표시된다.** 숫자만 그리지 않는다. 같은 bucket의 24%와 76%는 서로 다른 읽기이고, 라벨이 없으면 구분할 수 없다.
2. `WidgetConfig.remaining`은 3-상태다.
   - `null` — 앱 전역 설정(`AppSettings.remaining`)을 따른다. 새 위젯의 기본값이다.
   - `false` — 이 위젯은 사용량으로 고정한다.
   - `true` — 이 위젯은 남은 양으로 고정한다.
3. 위젯과 앱 화면은 사용자가 위젯에 별도 선택을 하지 않는 한 **같은 의미의 값**을 보여준다.
4. 값을 고르는 지점은 `WidgetStateMapper.effectiveRemaining(config, settingRemaining)` 하나다. 위젯 렌더링, 위젯 설정 미리보기, 앱 안의 위젯 목록이 모두 이 함수를 거친다.

## 표시 형식

| 상태 | 표시 |
| --- | --- |
| 퍼센트 | `76% 남음` / `24% 사용` |
| 개수·금액 | `3.00 REQUESTS 남음` |
| 값 없음 | `알 수 없음` (0으로 대체하지 않는다) |

## 마이그레이션

`WidgetConfigStore`의 `Json`은 기본값을 직렬화하지 않는다. v0.1.5까지 `remaining`의 기본값이 `false`였으므로 저장된 설정에 `remaining` 키가 있다는 것은 사용자가 "남음"을 명시적으로 골랐다는 뜻이다.

- 키 없음 → `null`로 읽혀 앱 설정을 따른다.
- `"remaining":true` → 그대로 유지된다.

v0.1.6부터 "사용"은 기본값이 아니므로 `"remaining":false`로 저장된다. 앱 삭제·재설치 없이 업데이트만으로 적용되며 계정 세션에 영향이 없다.

## 범위 밖

파서와 수집 경로는 이번 변경에 포함되지 않았다. 이번 증상에서 파서는 정상 동작했다(페이지 "남음" → DB `remainingPercent`). 파서 쪽 잠재 결함은 DEVELOPMENT_HISTORY.md의 "손대지 않은 잠재 결함" 목록에 남겼다.
