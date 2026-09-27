# 나의 아침비서 (My Secretary)

아침에 Samsung 기본 '음성 녹음' 앱으로 녹음한 오늘 할 일을 **기기 안에서만** 텍스트로 바꾸고, 행동 하나당 한 줄의 체크리스트로 정리해 주는 개인용 Android 앱입니다. 대상 기기는 Galaxy S26 (SM-S942N)입니다.

- 음성 인식: [whisper.cpp](https://github.com/ggml-org/whisper.cpp) v1.9.4, 다국어 `small` 모델(Q5_1 양자화; 첫 벤치마크 결과로 base에서 전환), CPU 추론, Silero VAD
- 할 일 정리: 기기 내장 Gemini Nano(ML Kit GenAI Prompt API). 쓸 수 없으면 규칙 기반 정리로 자동 전환
- 출력: Obsidian 호환 Markdown 체크리스트, **[전체 복사]** · **[공유]**
- 네트워크: 앱 코드는 네트워크를 쓰지 않습니다. ML Kit 라이브러리가 인터넷 권한을 자동으로 추가하지만, 음성과 전사문은 기기 밖으로 나가지 않습니다.
- 설계 배경과 결정 근거: [`docs/WORK_PLAN.md`](docs/WORK_PLAN.md)

## 설치 (휴대폰)

1. 이 저장소의 **Releases**에서 최신 `my-secretary-0.1.N.apk`를 휴대폰 브라우저로 내려받습니다.
   (PC에서는 Actions 실행 결과의 `my-secretary-apk` artifact로도 받을 수 있습니다.)
2. 파일을 열어 설치합니다. 처음에는 "출처를 알 수 없는 앱 설치"를 브라우저(또는 내 파일)에 허용해야 합니다.
3. **v0.1.5 이하에서 업데이트하는 경우 한 번만 기존 앱을 삭제한 뒤 설치하세요.** v0.1.5까지는 빌드마다 서명 키가 달라 업데이트가 거부됩니다("앱이 설치되지 않음"). v0.1.6부터는 고정 키(`keystore/my-secretary.jks`)로 서명하므로 덮어쓰기 업데이트가 됩니다.
4. 앱을 처음 열면 **오디오 접근**과 **알림** 권한을 요청합니다. 둘 다 허용하세요.

## 사용법

1. 기본 '음성 녹음' 앱으로 오늘 할 일을 말하며 녹음합니다(5분 이내 권장).
2. **나의 아침비서**를 엽니다. `Recordings/Voice Recorder`의 최근 녹음이 보이고, 오늘 가장 최근 녹음이 미리 선택되어 있습니다.
3. **[변환]**을 누릅니다. 오디오 변환 → 음성 인식 → 할 일 정리 순서로 진행됩니다(화면을 꺼도 계속됩니다).
4. 결과가 자동으로 클립보드에 복사됩니다. 필요하면 수정한 뒤 **[전체 복사]** 또는 **[공유]**로 옵시디언·메모 앱에 붙여넣습니다.

### 교정 사전(whitelist)
결과를 고친 뒤 복사하거나 공유하면, 바뀐 단어 쌍(예: `아이알비 → IRB`)이 기록됩니다. 같은 교정이 **2회** 이상 나오면 다음 변환부터 자동으로 적용되고, Whisper 인식 단계에도 힌트(initial prompt)로 전달됩니다. 설정 화면에서 조회·삭제하거나 직접 추가할 수 있습니다.

### Gemini Nano
설정 화면에서 상태를 확인할 수 있습니다. "다운로드 필요"라면 **[Gemini Nano 모델 받기]**를 누르세요(다운로드는 시스템 AICore가 합니다). ML Kit GenAI API는 앱이 화면 맨 앞에 있을 때만 추론을 허용하므로, 변환 중 다른 앱으로 이동하면 규칙 기반 정리로 대체될 수 있습니다. 이때 결과 화면의 **[AI(Gemini Nano)로 다시 정리]**를 누르면 됩니다.

## 빌드

APK 빌드는 GitHub Actions(`.github/workflows/build-apk.yml`)에서 합니다. `main`이나 `claude/**` 브랜치에 푸시하면 실행되고, 결과 APK를 prerelease로 올립니다.

로컬에서 빌드하려면 다음이 필요합니다: Android SDK(compileSdk 35), NDK, CMake 3.22.1, JDK 17.

```bash
git submodule update --init --recursive
scripts/download_models.sh        # ggml 모델을 app/src/main/assets/models/ 에 내려받음
./gradlew assembleRelease         # app/build/outputs/apk/release/app-release.apk
```

STT 모델을 바꾸려면 `gradle.properties`의 `whisperModel`을 수정합니다(예: `ggml-small-q5_1.bin`).
