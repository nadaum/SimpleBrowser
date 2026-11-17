# Simple Browser (Termux 빌드)

## 기능
- 첫 화면: https://tvmon1.com/
- 주소 입력 후 이동 버튼 또는 키보드 Enter
- http/https 자동 보정
- 공백이 포함된 입력은 Google 검색
- WebView JavaScript, DOM Storage, 쿠키 지원
- 뒤로 가기
- Android 최소 지원 버전: Android 6.0 (API 23), Android 10 지원

## 빌드
Android SDK 및 JDK 17이 Termux에 준비되어 있어야 합니다. Android SDK의 API 35 플랫폼과 빌드 도구가 설치되어 있고 `ANDROID_HOME` 또는 `ANDROID_SDK_ROOT`가 설정된 상태에서 실행하세요.

```sh
cd ~/storage/downloads/SimpleBrowser
gradle --no-daemon --no-configuration-cache assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk ~/storage/downloads/SimpleBrowser.apk
```

APK 위치: `내장 메모리/Download/SimpleBrowser.apk`

참고: WebView 기능 및 사이트 호환성은 Android System WebView 버전에 영향을 받습니다.
