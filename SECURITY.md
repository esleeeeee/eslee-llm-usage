# 보안 모델

## 로컬 보관

앱에는 자체 backend, analytics, 광고 SDK가 없습니다. 계정 메타데이터와 사용량은 앱 private Room DB에 보관합니다. API credential은 Android Keystore의 비추출 AES-256 키로 GCM 암호화하고 매번 새로운 IV 및 계정 ID AAD를 사용합니다. 암호문 파일은 noBackupFilesDir에 저장합니다. 앱 백업과 기기 전송을 비활성화합니다.

## 웹 인증

계정 UUID별 AndroidX WebKit profile을 바인딩합니다. 지원 여부를 런타임 검사하고 shared CookieManager fallback을 제공하지 않습니다. 사용자가 Provider 페이지에서 직접 로그인하며 앱이 비밀번호 필드를 수집하지 않습니다. 비밀번호·쿠키·OAuth token을 DB에 추출하거나 Chrome에서 가져오지 않습니다.

파일/content 접근, mixed content, WebView debugging, 다운로드, 카메라/마이크/위치 권한을 허용하지 않습니다. HTTPS host allowlist를 검사합니다. JavaScriptInterface를 등록하지 않고 evaluateJavascript 반환값으로 visible text만 파싱합니다.

## 네트워크와 진단

API 요청은 HTTPS를 사용하며 인증 헤더를 로그에 기록하지 않습니다. raw response, HTML, exception text, 이메일, credential은 진단 로그에 저장하지 않습니다. 로그는 내부 결과 코드와 시각 중심이며 최근 200개만 남깁니다. API redirect를 따르지 않으며 응답 크기와 timeout을 제한합니다.

## 삭제

로그아웃은 credential과 managed web profile을 정리합니다. 계정 삭제는 관련 snapshot, bucket, widget relation, sync log를 cascade 정리합니다. 플랫폼 정리가 실패하면 재시도할 수 있도록 계정을 비활성 상태로 보존합니다.

## 제보

공개 이슈에 실제 API key, 쿠키, 계정 이메일, 웹 페이지 전체 또는 개인 데이터를 올리지 마세요. 재현 가능한 비식별 예제와 앱 버전·상태 코드만 포함하세요.
