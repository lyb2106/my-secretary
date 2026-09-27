# My Secretary — 온디바이스 한국어 음성 할 일 추출 앱 작업 계획서 (v0.1, 개발 착수 전)

- 작성일: 2026-09-27
- 상태: **계획 단계 — 개발 미착수.** 아래 §8의 질문에 대한 답변을 받은 뒤 착수한다.
- 대상 기기: Samsung Galaxy S26 (기본형), 개인 사용 전용(배포·상업적 이용 없음)

---

## 1. 목표 정의

아침에 Samsung 기본 '음성 녹음' 앱으로 녹음한 5분 이내의 한국어 독백(영어 단어 혼용)을 입력으로 받아,

1. 네트워크 없이 기기 내에서(on-device) 음성을 텍스트로 전사하고,
2. 전사문에서 "사용자 본인이 오늘 실제로 수행해야 하는 행동(action)"만 추출하여 **1 action = 1 개조식 문장**의 체크리스트로 정리하며,
3. 결과를 (a) 클립보드, (b) 텔레그램, (c) 앱 내 텍스트 편집 화면으로 즉시 내보낸다.

최우선 설계 원칙은 **지속가능성**이다: 발열·메모리·배터리 부담을 최소화하고, 일상적 반복 사용에서 신뢰성 있게 동작해야 한다. 화자 분리, 실시간 스트리밍, 모델 fine-tuning은 범위 밖이다.

---

## 2. 대상 하드웨어 분석 (Galaxy S26 기본형)

| 항목 | 사양 | 설계상 함의 |
|---|---|---|
| SoC | Exynos 2600 (2 nm), 10-core CPU: 1×3.80 GHz C1-Ultra + 3×3.25 GHz C1-Pro + 6×2.75 GHz C1-Pro [1][2] | 모든 코어를 쓰면 전력 밀도·발열이 급증하므로 **추론 스레드를 4개로 제한**(성능 코어 군 위주)한다. |
| 지역별 SoC | 한국 판매 모델은 Exynos 2600 탑재가 보고됨 [3]. 일부 지역은 Snapdragon 탑재 가능성 있음 | whisper.cpp는 두 SoC 모두 ARM NEON 경로로 동작하므로 설계 변경은 불필요. 정확한 모델 확인 요청(§8-Q1). |
| RAM | 12 GB LPDDR5X [1][2] | Whisper base(약 0.4 GB 이하)는 여유가 충분. 그러나 상주(resident)시키지 않고 **작업 시에만 로드 → 작업 후 즉시 해제**. |
| 배터리 | 4,300 mAh [2] | 1일 1회, 수십 초 수준의 연산은 배터리 영향이 무시 가능한 수준으로 예상. 백그라운드 상시 감시는 지양. |
| OS | Android 16 / One UI 8 계열 [2] | `READ_MEDIA_AUDIO` 권한, 공유 인텐트, `PowerManager` 열 상태 API 사용 가능. |

> 주의: 위 사양은 2차 출처(사양 집계 사이트) 기준이며, 착수 시 사용자 기기의 모델 번호로 재확인한다.

---

## 3. 핵심 기술 결정

### 3.1 추론 엔진: **whisper.cpp (CPU, ARM NEON) 채택 / Whisper TFLite 불채택**

| 기준 | whisper.cpp | Whisper TFLite (커뮤니티 변환본) |
|---|---|---|
| 유지·신뢰성 | ggml-org가 유지하는 C/C++ 구현, MIT 라이선스, **공식 Android 예제(`examples/whisper.android`) 제공** [4] | Google·OpenAI 공식 배포물이 아닌 개인/커뮤니티 변환본(예: nyadla-sys/whisper.tflite) 위주 [5] |
| 다국어(한국어) | 언어 토큰 지정이 정상 동작 | **다국어 모델 변환 시 언어·task 토큰이 무시되어 한국어 대신 영어로 출력되는 문제 보고** [6]; 다국어 변환 모델에서 `GATHER index out of bounds` 런타임 오류 보고 [7] |
| 양자화 | Q5/Q8 등 정수 양자화 공식 지원 [4] | int8 변환 가능하나 정확도 손실 편차 보고 |
| VAD | Silero-VAD 내장 지원 → 무음 구간 연산 생략 [4] | 별도 구현 필요 |
| 가속기 | CPU(NEON) 중심 | NPU delegate 사용 가능성은 있으나 Exynos NPU에서의 동작 보장 근거 부족 |

**결론:** 사용자 요구("버그가 많고 불안정하면 채택하지 말 것")에 따라, 다국어(한국어) 경로에서 결함이 반복 보고된 TFLite 커뮤니티 포트는 채택하지 않는다. 입력이 5분 이하의 배치(batch) 처리이므로 NPU 가속의 이득은 제한적이며, 검증된 whisper.cpp CPU 경로가 신뢰성·유지보수성 측면에서 우월하다고 판단한다.

### 3.2 모델: **Vanilla multilingual `base` (ggml, 양자화) 기본 채택 + Phase 0 실측 후 확정**

- whisper.cpp 공식 표 기준 메모리 요구량: tiny 75 MiB/~273 MB, **base 142 MiB/~388 MB**, small 466 MiB/~852 MB (디스크/메모리, 비양자화) [4]. 정수 양자화 시 디스크·메모리 추가 절감 [4].
- 한국어 특화 fine-tuned 모델 검토 결과:
  - 공개된 한국어 fine-tuned Whisper는 대부분 small 이상(medium, large-v2/v3, large-v3-turbo)이며, Zeroth-Korean(낭독체 코퍼스)으로 학습·평가되었다 [8][9][10]. 예: `seastar105/whisper-medium-ko-zeroth` CER 1.48%(동일 코퍼스 평가셋) [9], `byoussef/whisper-small-KR` WER 37.95% [8].
  - (i) 학습·평가 도메인이 동일(in-domain)하여 일상 독백에 대한 일반화 근거가 약하고, (ii) **base 크기의 신뢰할 만한 한국어 fine-tuned 모델과 독립 벤치마크를 확인하지 못했으며**, (iii) medium 이상은 자원 요구량(~2 GB 이상)이 "지속가능성" 목표와 충돌한다.
  - 따라서 fine-tuned 모델은 기본값으로 채택하지 않는다.
- **정직한 한계 명시:** Whisper는 모델 크기에 따라 비영어권 언어 정확도가 크게 달라지며, 한국어 FLEURS 결과는 원 논문 부록 D에 크기별로 보고되어 있다 [11]. base의 한국어 정확도는 small 대비 유의하게 낮을 가능성이 높다. 이에 **Phase 0에서 사용자의 실제 녹음으로 base-q8_0 vs small-q5_1을 비교**하여 최종 결정한다.
  - 판정 규칙(안): small이 CER을 상대적으로 30% 이상 낮추면서 처리 시간 ≤ 60초, 최고 기기 온도 상승 ≤ 5 °C, 최대 메모리 ≤ 800 MB를 만족하면 small로, 아니면 base 유지.
  - 두 모델 모두 이후 단계의 "사용자 사전(whitelist)" 기능으로 고유명사 오류를 보정한다.

### 3.3 발열·메모리·배터리 설계

1. **VAD 전처리**(Silero-VAD, whisper.cpp 내장): 무음·잡음 구간을 디코딩에서 제외 → 연산량 감소, 무음 구간 환각(hallucination) 억제 [4].
2. **스레드 4개 제한**, 단일 작업 직렬 처리, 모델은 필요 시 로드 → 즉시 해제.
3. **열 상태 감시:** `PowerManager.getCurrentThermalStatus()`가 `MODERATE` 이상이면 스레드를 2개로 낮추고, `SEVERE` 이상이면 작업을 일시 중지 후 사용자에게 알림.
4. 오디오 디코딩은 Android 내장 `MediaExtractor`/`MediaCodec`으로 m4a(AAC) → 16 kHz mono PCM 변환 (FFmpeg 등 추가 라이브러리 미사용 → APK·메모리 절감).
5. 상시 백그라운드 감시(FileObserver, 주기적 WorkManager) **미사용**. 사용자의 1-탭 또는 공유 동작으로만 처리 시작.
6. 처리 중에는 Foreground Service + 진행률 알림(화면을 꺼도 중단되지 않도록).

---

## 4. 기능 설계

### 4.1 입력: 오늘의 녹음 파일 자동 인식

- Samsung 음성 녹음 앱의 기본 저장 경로는 `내장 저장공간/Recordings/Voice Recorder`, 기본 형식은 M4A로 보고된다 [12].
- 구현 방법 (두 경로 모두 제공):
  1. **앱 실행 시 자동 탐지:** `MediaStore.Audio`에서 `RELATIVE_PATH LIKE 'Recordings/Voice Recorder%'` AND `DATE_ADDED ≥ 오늘 00:00` 조건으로 최신 파일을 조회 → "오늘 녹음: 07:12, 3분 41초 — [변환]" 카드 표시. 권한: `READ_MEDIA_AUDIO`.
  2. **공유 인텐트 수신:** 음성 녹음 앱에서 녹음 → 공유 → "My Secretary" 선택 시 즉시 처리 (`ACTION_SEND`, `audio/*`).
  3. 예외 대비: 시스템 파일 선택기(SAF)로 수동 선택.

### 4.2 처리 파이프라인

```
m4a → [MediaCodec 디코딩, 16 kHz mono] → [Silero VAD] → [whisper.cpp, language=ko, initial_prompt=사용자 사전]
    → [사용자 사전 치환 규칙 적용] → [행동 추출(§4.3)] → 체크리스트 → 결과 화면/내보내기
```

### 4.3 행동(action) 추출 — **사용자 결정 필요(§8-Q2)**

전사문에서 잡담·감탄사·무관한 발화를 제거하고 "내가 오늘 할 일"만 1문장 1행동으로 만드는 작업은 본질적으로 의미 이해가 필요한 과제이다. 후보는 다음과 같다.

| 방식 | 장점 | 단점 | 앱 메모리 영향 |
|---|---|---|---|
| **A. Gemini Nano (ML Kit GenAI Prompt API)** — 권장 | 완전 온디바이스, 모델이 시스템 서비스(AICore)에 있어 **APK·앱 메모리 증가 없음**, Galaxy S26 계열 지원 보고 [13][14] | API가 비교적 신규(alpha→beta 단계)여서 사양 변경 가능성, **한국어 지원 품질은 Phase 0에서 확인 필요** | 앱 자체는 거의 없음 (시스템이 관리) |
| B. 규칙 기반(rule-based) | 초경량, 결정적(deterministic), 오프라인 | 한국어 구어체 다양성 대응 한계 → 누락·오탐 가능 | 없음 |
| C. 소형 LLM 번들(예: Gemma 계열 1B, LiteRT) | 동작을 완전히 통제 가능 | APK/모델 수백 MB~1 GB, 추론 시 발열·메모리 증가 → 지속가능성 목표와 상충 | 큼 |

**제안:** A를 기본으로 하고, A가 불가(모델 미다운로드, 한국어 품질 불충분 등)할 때 B로 자동 폴백. C는 채택하지 않음.

- 출력 규칙(프롬프트 및 규칙 기반 공통): 동사형 종결(예: "○○ 교수에게 IRB 수정본 메일 발송"), 1행 1행동, 중복 병합, 시간 언급 시 앞에 표기(예: "14:00 랩미팅 자료 최종 확인").

### 4.4 출력 UX (복사·붙여넣기 최우선)

- 결과 화면 = **편집 가능한 텍스트 필드 1개** + 하단 고정 버튼 3개: **[전체 복사] [텔레그램] [공유]**.
- 변환 완료 시 **자동으로 클립보드에 복사** (설정에서 끌 수 있음). Android 13+는 시스템이 복사 확인 UI를 표시한다.
- 출력 형식(기본): Obsidian 호환 Markdown 체크박스
  ```
  ## 2026-09-27 할 일
  - [ ] ○○ 교수에게 IRB 수정본 메일 발송
  - [ ] 14:00 랩미팅 자료 최종 확인
  ```
- 각 행 길게 누르기 → 해당 행만 복사.
- [공유] 버튼은 Android 공유 시트(`ACTION_SEND text/plain`)를 호출 → Obsidian, Samsung Notes 등 어떤 앱으로도 전달.
- 원본 전사문은 접이식(collapsible) 영역에 함께 보관(검증용).
- 최근 7일 결과 이력(로컬 DB, 텍스트만 저장).

### 4.5 텔레그램 전송 — **사용자 결정 필요(§8-Q3)**

- 방식 1 (권장, 경량): 텔레그램 앱으로의 공유 인텐트. 앱에 인터넷 권한·토큰 불필요.
- 방식 2: Telegram Bot API `sendMessage`로 본인에게 1-탭 자동 전송. 봇 토큰·chat_id를 기기 내 암호화 저장소에 보관. 앱에 `INTERNET` 권한이 추가된다(음성·전사는 여전히 기기 밖으로 나가지 않으며, 최종 체크리스트 텍스트만 전송).

---

## 5. Heavy 기능 검토: 사용자 수정 학습(whitelist)

**결론: 경량 구현이 가능하므로 채택을 권장한다.** 모델 재학습 없이 두 층위로 구현한다.

1. **치환 사전(post-correction dictionary):** 사용자가 결과 텍스트를 수정하면, 원문과 수정문을 단어(어절) 단위로 정렬(diff)하여 `오인식 → 정답` 쌍을 추출한다. 동일 쌍이 2회 이상 관측되면 자동 적용 규칙으로 승격, 1회는 "제안" 상태로 보관. 설정 화면에서 규칙 조회·삭제 가능.
2. **어휘 바이어싱(vocabulary biasing):** 승격된 정답 어휘(고유명사, 영문 용어 등) 상위 N개를 whisper.cpp 디코딩의 `initial_prompt`로 전달하여 인식 단계부터 해당 어휘 쪽으로 유도한다. (프롬프트 길이 제약을 고려해 N ≈ 30~50어 이내)

- 자원 비용: 수 KB 수준의 로컬 DB(Room)와 문자열 연산뿐이므로 발열·메모리 영향은 무시 가능.
- 한계: `initial_prompt` 바이어싱은 확률적 유도이며 100% 보장이 아니다. 과도한 어휘 주입은 오히려 환각을 유발할 수 있으므로 N 상한을 둔다.

---

## 6. 개발 단계 (Phase)

| Phase | 내용 | 완료 기준 |
|---|---|---|
| 0. 빌드 경로·실측 벤치마크 | Android 프로젝트 골격, GitHub Actions APK 빌드, **앱 내 벤치마크 화면**(base vs small, CER·처리 시간·최대 메모리·온도 기록), Gemini Nano 한국어 동작 확인 | 사용자 기기에서 벤치마크 결과 확보 → 모델·추출 방식 확정 |
| 1. STT 코어 | whisper.cpp JNI(NDK/CMake), MediaCodec 디코딩, VAD, 열 상태 제어, Foreground Service | 5분 녹음 1건을 오류 없이 전사 |
| 2. 입력 | MediaStore 자동 탐지, 공유 인텐트 수신, SAF 수동 선택 | 오늘 녹음이 앱 실행 즉시 카드로 표시 |
| 3. 행동 추출 | Gemini Nano 프롬프트 + 규칙 기반 폴백 | 사용자 샘플 5건에서 누락·오탐 수동 검토 |
| 4. 출력 UX | 결과 편집 화면, 자동 복사, 공유 시트, 텔레그램, 이력 | 2탭 이내로 Obsidian에 붙여넣기 가능 |
| 5. Whitelist | diff 기반 치환 사전, initial_prompt 바이어싱, 관리 화면 | 동일 오류 2회 수정 후 3회차 자동 교정 |
| 6. 안정화 | 실사용 1주 피드백 반영, 예외 처리(무음 파일, 5분 초과, 권한 거부) | 주요 경로 크래시 0 |

**기술 스택(안):** Kotlin, Jetpack Compose, NDK/CMake(whisper.cpp 특정 안정 태그 고정), Room, ML Kit GenAI Prompt API, minSdk 34 / targetSdk 36(착수 시 최신 안정 SDK로 확정).

### 6.1 빌드 환경 제약 (중요)

현재 개발 컨테이너에서는 Android SDK/NDK 배포 서버(`dl.google.com`)와 Hugging Face(모델 저장소)에 대한 네트워크 접근이 차단되어 있어 **컨테이너 안에서 직접 APK를 빌드할 수 없다.** 따라서:

- 코드는 이 저장소에 커밋하고, **GitHub Actions 워크플로에서 APK를 빌드**하여 Actions artifact(또는 Release)로 내려받는 방식을 제안한다.
- 모델 파일(ggml)은 (a) CI에서 내려받아 APK에 포함하거나, (b) 앱 최초 실행 시 1회 다운로드하는 방식 중 선택(§8-Q5). 완전 오프라인 원칙에는 (a)가 부합하며, APK 크기는 base-q8_0 기준 약 수십~100 MB대가 예상된다(정확한 크기는 빌드 후 측정).

---

## 7. 예상 AI 토큰 사용량 분석

### 7.1 산정 가정
- 산출 코드 규모: Kotlin 약 3,000~4,500줄, C/C++ JNI 약 300~500줄, Gradle/CI 설정 약 300줄.
- 코드 1줄 ≈ 10~12 토큰, 한국어 문서는 영어 대비 토큰 밀도가 높음.
- 에이전트형 개발에서는 매 도구 호출마다 누적 대화 맥락이 다시 입력되므로 **입력 토큰이 출력 토큰의 20~40배**가 되는 것이 일반적이며, 그 중 대부분은 프롬프트 캐시 적중(cache read)으로 처리된다.
- 가장 큰 불확실성: 로컬 빌드가 불가하여 **CI 빌드 실패 → 로그 확인 → 수정** 반복 횟수, 그리고 실기기 테스트 피드백 반복 횟수.

### 7.2 단계별 추정

| Phase | 입력 토큰(누적, 대부분 캐시) | 출력 토큰 |
|---|---|---|
| 계획 수립(본 문서) | 0.1 ~ 0.2 M | 15 ~ 25 K |
| 0. 골격·CI·벤치마크 | 0.5 ~ 1.0 M | 20 ~ 35 K |
| 1. STT 코어(JNI 포함) | 0.6 ~ 1.2 M | 20 ~ 35 K |
| 2. 입력 | 0.3 ~ 0.5 M | 8 ~ 15 K |
| 3. 행동 추출 | 0.4 ~ 0.8 M | 12 ~ 25 K |
| 4. 출력 UX | 0.4 ~ 0.8 M | 15 ~ 30 K |
| 5. Whitelist | 0.3 ~ 0.6 M | 10 ~ 20 K |
| 6. CI 디버깅·실기기 피드백 반복 | 0.8 ~ 3.0 M | 15 ~ 50 K |
| **합계** | **약 3.4 ~ 8.1 M** | **약 115 ~ 235 K** |

- 중앙 추정치: 입력 약 5 M(이 중 80~90%가 캐시 입력), 출력 약 170 K.
- 절감 방안: (i) Phase 0에서 CI 빌드를 먼저 안정화, (ii) 기능별로 세션을 나누어 누적 맥락 축소, (iii) 실기기 테스트 결과를 한 번에 묶어서 전달.
- 이 수치는 경험적 추정치이며 실제 사용량은 반복 횟수에 따라 ±50% 이상 변동할 수 있다.

---

## 8. 착수 전 확인 질문

1. **기기 모델:** 설정 → 휴대전화 정보의 모델 번호(한국 판매 Exynos 2600 모델 여부)를 알려주세요.
2. **행동 추출 방식:** A(Gemini Nano, 권장·폴백 B 포함) / B(규칙 기반만) / C(소형 LLM 번들) 중 선택.
3. **텔레그램:** 방식 1(텔레그램 앱으로 공유, 권장) / 방식 2(봇으로 1-탭 자동 전송, 인터넷 권한 필요).
4. **출력 형식:** Obsidian Markdown 체크박스(`- [ ]`) + 날짜 제목을 기본으로 해도 될까요? 시간 언급이 있으면 앞에 붙이는 규칙도 괜찮습니까?
5. **빌드·모델 배포:** GitHub Actions로 APK를 빌드하고, 모델을 APK에 포함(완전 오프라인)하는 방식에 동의하십니까? (저장소가 private이면 Actions 사용량 한도가 적용될 수 있습니다.)
6. **Phase 0 벤치마크:** 실제 아침 녹음 3~5건과 그에 대한 정답 텍스트(직접 작성)를 **기기 안에서만** 사용하는 앱 내 벤치마크 화면 방식에 동의하십니까? (녹음 파일을 저장소에 올릴 필요 없음)
7. **처리 시작 방식:** 앱 실행/공유 시 1-탭 처리(권장, 배터리 영향 최소) 대신 "새 녹음이 생기면 자동 변환"을 원하십니까? 후자는 백그라운드 감시가 필요합니다.

---

## 9. References

1. O2 UK, "Specifications For Your Samsung Galaxy S26." https://www.o2.co.uk/help/phones-sims-and-devices/samsung/galaxy-s26-android-16/specifications
2. DeviceSpecifications, "Samsung Galaxy S26 – Specifications." https://www.devicespecifications.com/en/model/24cf666d
3. GSMArena, "Korean Galaxy S26 with Exynos 2600 also runs Geekbench." https://www.gsmarena.com/korean_galaxy_s26_with_exynos_2600_also_runs_geekbench-news-71364.php
4. ggml-org, whisper.cpp (README: memory table, quantization, Silero-VAD, Android example). https://github.com/ggml-org/whisper.cpp
5. nyadla-sys, whisper.tflite. https://github.com/nyadla-sys/whisper.tflite
6. nyadla-sys/whisper.tflite Issue #35, "forced_decoder_ids does not apply to tflite model (multilingual, language issue)." https://github.com/nyadla-sys/whisper.tflite/issues/35
7. openai/whisper Discussion #778, "Multilingual models converted to TFLite doesn't work." https://github.com/openai/whisper/discussions/778
8. byoussef/whisper-small-KR (Hugging Face model card). https://huggingface.co/byoussef/whisper-small-KR
9. seastar105/whisper-medium-ko-zeroth (Hugging Face model card). https://huggingface.co/seastar105/whisper-medium-ko-zeroth
10. ghost613/whisper-large-v3-turbo-korean (Hugging Face model card). https://huggingface.co/ghost613/whisper-large-v3-turbo-korean
11. Radford A. et al., "Robust Speech Recognition via Large-Scale Weak Supervision," 2022 (Appendix D, FLEURS per-language results). https://cdn.openai.com/papers/whisper.pdf
12. GoTranscript, "How to Transcribe Audio on Samsung Voice Recorder" (저장 경로·M4A 형식). https://gotranscript.com/en/blog/transcribe-audio-samsung-voice-recorder-export-transcript
13. Google, "Overview of the ML Kit GenAI APIs." https://developers.google.com/ml-kit/genai
14. Android Developers Blog, "ML Kit's Prompt API: Unlock Custom On-Device Gemini Nano Experiences." https://android-developers.googleblog.com/2025/10/ml-kit-genai-prompt-api-alpha-release.html
