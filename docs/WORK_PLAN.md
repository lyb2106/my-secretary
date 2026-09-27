# 나의 아침비서 (My Secretary) — 온디바이스 한국어 음성 할 일 추출 앱 작업 계획서 (v0.3)

- 작성일: 2026-09-27 (v0.1 → v0.2: 사용자 답변 반영)
- 상태: **1차 구현 완료 (§9).** 테스트 데이터 업로드 후 Phase 0 벤치마크 진행 예정.
- 대상 기기: Samsung Galaxy S26 기본형 **SM-S942N (한국 모델, Exynos 2600)**, 개인 사용 전용

## 0. 확정 사항 요약 (v0.2)

| 항목 | 결정 |
|---|---|
| 기기 | SM-S942N — 한국 판매 모델, Exynos 2600 / 12 GB RAM 확인 [3][15] |
| STT 엔진 | whisper.cpp (CPU, ARM NEON). Whisper TFLite 불채택 |
| STT 모델 | Vanilla multilingual base(양자화) 잠정 채택 → Phase 0 저장소 벤치마크로 base vs small 확정 |
| 행동 추출 | **Gemini Nano (ML Kit GenAI Prompt API)** 기본 + 규칙 기반 자동 폴백 |
| 출력 UX | **[전체 복사] · [공유] 두 버튼만.** 텔레그램 전용 기능 삭제(공유 시트와 중복) |
| 출력 형식 | Obsidian Markdown 체크박스 + 날짜 제목, 시간 언급 시 문장 앞 표기 (확정) |
| 입력 | 앱 실행 시 최근 녹음 목록 표시, 오늘 최신 파일 기본 선택 → 1탭 변환. 백그라운드 감시 없음 |
| 빌드 | GitHub Actions로 APK 빌드, 모델 파일을 APK에 포함(완전 오프라인) |
| 테스트 | 사용자가 저장소에 올린 음성 + 정답 텍스트로 **저장소(CI)에서 정확도 테스트** |
| Whitelist | 채택 (치환 사전 + initial_prompt 어휘 바이어싱) |

---

## 1. 목표 정의

아침에 Samsung 기본 '음성 녹음' 앱으로 녹음한 5분 이내의 한국어 독백(영어 단어 혼용)을 입력으로 받아,

1. 네트워크 없이 기기 내에서(on-device) 음성을 텍스트로 전사하고,
2. 전사문에서 "사용자 본인이 오늘 실제로 수행해야 하는 행동(action)"만 추출하여 **1 action = 1 개조식 문장**의 체크리스트로 정리하며,
3. 결과를 **클립보드 복사** 또는 **Android 공유 시트**(Obsidian, 메모 앱, 텔레그램 등 임의의 앱)로 내보낸다.

최우선 설계 원칙은 **지속가능성**이다: 발열·메모리·배터리 부담을 최소화하고, 일상적 반복 사용에서 신뢰성 있게 동작해야 한다. 화자 분리, 실시간 스트리밍, 모델 fine-tuning, 백그라운드 자동 감시는 범위 밖이다.

---

## 2. 대상 하드웨어 분석 (SM-S942N)

| 항목 | 사양 | 설계상 함의 |
|---|---|---|
| SoC | Exynos 2600 (2 nm GAA), 10-core CPU: 1×3.80 GHz C1-Ultra + 3×3.25 GHz C1-Pro + 6×2.75 GHz C1-Pro [1][2][15] | 모든 코어를 쓰면 전력 밀도·발열이 급증하므로 **추론 스레드를 4개로 제한**한다. |
| RAM | 12 GB LPDDR5X [1][2][15] | Whisper base(약 0.4 GB 이하)는 여유가 충분. 상주시키지 않고 **작업 시에만 로드 → 작업 후 즉시 해제**. |
| 배터리 | 4,300 mAh [2] | 1일 1회, 수십 초 수준의 연산은 배터리 영향이 작을 것으로 예상(Phase 0 이후 실측으로 확인). |
| OS | Android 16 / One UI 8.x [2][15] | `READ_MEDIA_AUDIO` 권한, `PowerManager` 열 상태 API 사용 가능. |

---

## 3. 핵심 기술 결정

### 3.1 추론 엔진: **whisper.cpp (CPU, ARM NEON) 채택 / Whisper TFLite 불채택**

| 기준 | whisper.cpp | Whisper TFLite (커뮤니티 변환본) |
|---|---|---|
| 유지·신뢰성 | ggml-org가 유지하는 C/C++ 구현, MIT 라이선스, **공식 Android 예제(`examples/whisper.android`) 제공** [4] | Google·OpenAI 공식 배포물이 아닌 개인/커뮤니티 변환본 위주 [5] |
| 다국어(한국어) | 언어 토큰 지정이 정상 동작 | **다국어 모델 변환 시 언어·task 토큰이 무시되어 한국어 대신 영어로 출력되는 문제 보고** [6]; 다국어 변환 모델에서 `GATHER index out of bounds` 런타임 오류 보고 [7] |
| 양자화 | Q5/Q8 등 정수 양자화 공식 지원 [4] | int8 변환 가능하나 정확도 손실 편차 보고 |
| VAD | Silero-VAD 내장 지원 → 무음 구간 연산 생략 [4] | 별도 구현 필요 |
| 테스트 재현성 | **동일 코드·동일 모델을 CI(Linux)와 기기(Android)에서 모두 실행 가능** → 저장소 테스트 결과가 기기 결과를 대표 | 저장소 테스트 환경 구축이 상대적으로 복잡 |

**결론:** 다국어(한국어) 경로에서 결함이 반복 보고된 TFLite 커뮤니티 포트는 채택하지 않는다. 입력이 5분 이하의 배치 처리이므로 NPU 가속의 이득은 제한적이며, 검증된 whisper.cpp CPU 경로가 신뢰성·유지보수성·테스트 재현성 측면에서 우월하다.

### 3.2 모델: **Vanilla multilingual `base` (ggml, 양자화) 잠정 채택 + Phase 0 벤치마크 후 확정**

- whisper.cpp 공식 표 기준 요구량(비양자화, 디스크/메모리): tiny 75 MiB/~273 MB, **base 142 MiB/~388 MB**, small 466 MiB/~852 MB [4]. 정수 양자화 시 추가 절감 [4].
- 한국어 fine-tuned 모델을 기본값으로 채택하지 않는 이유:
  - 공개 모델 대부분이 small 이상이며 Zeroth-Korean(낭독체)으로 학습·평가되어 [8][9][10], 동일 코퍼스 평가 수치(예: medium-ko-zeroth CER 1.48% [9])가 일상 독백으로 일반화된다는 근거가 부족하다.
  - base 크기의 신뢰할 만한 한국어 fine-tuned 모델과 독립 벤치마크를 확인하지 못했다.
  - medium 이상은 자원 요구량(~2 GB 이상)이 지속가능성 목표와 충돌한다.
- **한계:** Whisper의 비영어권 정확도는 모델 크기에 크게 의존하며, 크기별 한국어 FLEURS 결과는 원 논문 부록 D에 보고되어 있다 [11]. base는 small 대비 한국어 정확도가 유의하게 낮을 가능성이 있으므로, **사용자 녹음 기반 저장소 벤치마크(§6.2)로 결정한다.**
- 판정 규칙:
  1. **정확도(CI 측정):** small이 base 대비 CER을 상대적으로 30% 이상 낮추면 small 후보로 승격.
  2. **자원(기기 측정):** small 후보가 5분 녹음 기준 처리 시간 ≤ 60초, 처리 중 열 상태 `MODERATE` 미만 유지 시 small 확정. 아니면 base 유지.

### 3.3 발열·메모리·배터리 설계

1. **VAD 전처리**(Silero-VAD): 무음·잡음 구간을 디코딩에서 제외 → 연산량 감소, 무음 구간 환각(hallucination) 억제 [4].
2. **스레드 4개 제한**, 단일 작업 직렬 처리, 모델은 필요 시 로드 → 즉시 해제.
3. **열 상태 감시:** `PowerManager.getCurrentThermalStatus()`가 `MODERATE` 이상이면 스레드를 2개로 낮추고, `SEVERE` 이상이면 작업을 일시 중지 후 알림.
4. 오디오 디코딩은 Android 내장 `MediaExtractor`/`MediaCodec`으로 m4a(AAC) → 16 kHz mono PCM (FFmpeg 미사용).
5. **백그라운드 감시 없음** (사용자 결정). 앱을 열고 1탭할 때만 처리.
6. 처리 중에는 Foreground Service + 진행률 알림(화면이 꺼져도 중단되지 않도록).
7. 결과 화면 하단에 **처리 통계 한 줄**(처리 시간, 사용 모델, 최고 열 상태) 표시 → 별도 벤치마크 화면 없이 기기 자원 지표를 확인.

---

## 4. 기능 설계

### 4.1 입력: 앱 실행 시 최신 녹음 선택

- Samsung 음성 녹음 앱의 기본 저장 경로는 `내장 저장공간/Recordings/Voice Recorder`, 기본 형식은 M4A로 보고된다 [12].
- 앱 실행 → `MediaStore.Audio`에서 `RELATIVE_PATH LIKE 'Recordings/Voice Recorder%'` 조건으로 **최근 녹음 목록(최신순, 최대 10개)** 표시. 오늘 녹음 중 가장 최신 파일이 기본 선택된 상태 → **[변환] 1탭**.
- 목록 항목: 녹음 시각, 길이, 파일명. 5분 초과 파일은 경고 표시(처리는 허용).
- 목록에 없을 때를 위한 **[다른 파일 선택]**(시스템 파일 선택기, SAF) 버튼 1개.
- 권한: `READ_MEDIA_AUDIO` (최초 1회).

### 4.2 처리 파이프라인

```
m4a → [MediaCodec 디코딩, 16 kHz mono] → [Silero VAD] → [whisper.cpp, language=ko, initial_prompt=사용자 사전]
    → [사용자 사전 치환 규칙 적용] → [행동 추출: Gemini Nano → 실패 시 규칙 기반] → 체크리스트 → 결과 화면
```

### 4.3 행동(action) 추출: **Gemini Nano + 규칙 기반 폴백 (확정)**

- **1차: Gemini Nano** — ML Kit GenAI Prompt API. 모델은 시스템 서비스(AICore)가 관리하므로 APK·앱 메모리 증가가 없다 [13][14]. Galaxy S26 계열 지원이 보고되어 있다 [13].
- **2차(자동 폴백): 규칙 기반** — Gemini Nano를 사용할 수 없는 경우(모델 미다운로드, API 오류, 출력 형식 위반) 실행.
  - 문장 분할 → 행동 의도 표지(예: "~해야", "~하기", "~할 것", "~보내", "~확인", "~예약") 포함 문장 선택 → 감탄사·군말("음", "아", "그러니까") 제거 → 명사형 종결로 정규화.
- 공통 출력 규칙: 1행 1행동, 동사/명사형 종결(예: "○○ 교수에게 IRB 수정본 메일 발송"), 중복 병합, 시간 언급 시 앞에 표기(예: "14:00 랩미팅 자료 최종 확인"), 할 일과 무관한 발화 제외.
- Prompt API가 비교적 신규 API(alpha→beta)이므로 **한국어 입력·출력 품질은 Phase 3에서 기기로 확인**하며, 품질이 부족하면 규칙 기반을 기본으로 전환할 수 있도록 설정 스위치를 둔다.
- **제약:** Gemini Nano는 기기(AICore)에서만 실행되므로 CI에서 테스트할 수 없다. 저장소 테스트는 STT 정확도와 규칙 기반 추출에 한정되고, Gemini Nano 추출 품질은 기기에서 확인한다(§6.2).

### 4.4 출력 UX (확정)

- 결과 화면 = **편집 가능한 텍스트 필드 1개** + 하단 고정 버튼 2개: **[전체 복사] [공유]**.
- 변환 완료 시 **자동으로 클립보드에 복사** (설정에서 끌 수 있음). Android 13+는 시스템이 복사 확인 UI를 표시한다.
- [공유]는 Android 공유 시트(`ACTION_SEND`, `text/plain`) → Obsidian, Samsung Notes, 텔레그램 등 어떤 앱으로도 전달.
- 출력 형식(확정):
  ```
  ## 2026-09-27 할 일
  - [ ] ○○ 교수에게 IRB 수정본 메일 발송
  - [ ] 14:00 랩미팅 자료 최종 확인
  ```
- 원본 전사문은 접이식 영역에 함께 보관(검증용). 최근 7일 결과 이력(로컬 DB, 텍스트만 저장).
- 텔레그램 전용 기능·인터넷 권한 **없음** → 앱 전체가 네트워크 권한 없이 동작.

---

## 5. Whitelist: 사용자 수정 학습 (채택)

모델 재학습 없이 두 층위로 구현한다.

1. **치환 사전(post-correction dictionary):** 사용자가 결과 텍스트를 수정하면 원문과 수정문을 어절 단위로 정렬(diff)하여 `오인식 → 정답` 쌍을 추출한다. 동일 쌍이 2회 이상 관측되면 자동 적용 규칙으로 승격, 1회는 "제안" 상태로 보관. 설정 화면에서 규칙 조회·삭제 가능.
2. **어휘 바이어싱:** 승격된 정답 어휘(고유명사, 영문 용어 등) 상위 N개(≈30~50어)를 whisper.cpp 디코딩의 `initial_prompt`로 전달하여 인식 단계부터 유도한다.

- 자원 비용: 수 KB 수준의 로컬 DB(Room)와 문자열 연산 → 발열·메모리 영향 무시 가능.
- 한계: `initial_prompt` 바이어싱은 확률적 유도이며 보장이 아니다. 과도한 어휘 주입은 환각을 유발할 수 있어 N 상한을 둔다.
- 저장소 테스트에도 동일 사전 파일(`testdata/whitelist.tsv`, 선택)을 적용해 바이어싱 효과를 CER로 측정할 수 있다.

---

## 6. 개발 단계 (Phase)

| Phase | 내용 | 완료 기준 |
|---|---|---|
| 0. 골격·CI·저장소 벤치마크 | Android 프로젝트 골격, APK 빌드 워크플로, **STT 벤치마크 워크플로**(§6.2) | CI에서 APK 산출 + base/small CER 보고서 생성 |
| 1. STT 코어 | whisper.cpp JNI(NDK/CMake), MediaCodec 디코딩, VAD, 열 상태 제어, Foreground Service | 5분 녹음 1건을 기기에서 오류 없이 전사, 처리 통계 확인 → 모델 확정 |
| 2. 입력 | 최근 녹음 목록, 오늘 최신 파일 기본 선택, SAF 선택 | 앱 실행 후 1탭으로 변환 시작 |
| 3. 행동 추출 | Gemini Nano 프롬프트 + 규칙 기반 폴백, CI에서 규칙 기반 테스트 | 테스트 세트에서 누락·오탐 검토 |
| 4. 출력 UX | 결과 편집 화면, 자동 복사, [전체 복사]·[공유], 이력 | 2탭 이내로 Obsidian에 붙여넣기 가능 |
| 5. Whitelist | diff 기반 치환 사전, initial_prompt 바이어싱, 관리 화면 | 동일 오류 2회 수정 후 3회차 자동 교정 |
| 6. 안정화 | 실사용 피드백 반영, 예외 처리(무음 파일, 5분 초과, 권한 거부) | 주요 경로 크래시 0 |

**기술 스택(안):** Kotlin, Jetpack Compose, NDK/CMake(whisper.cpp 특정 안정 태그 고정), Room, ML Kit GenAI Prompt API, minSdk 34 / targetSdk는 착수 시 최신 안정 SDK로 확정. 앱 표시 이름: **나의 아침비서**.

### 6.1 빌드 경로

- 개발 컨테이너에서는 Android SDK/NDK 배포 서버와 Hugging Face 접근이 차단되어 있어 **GitHub Actions에서 빌드**한다.
- 워크플로 `build-apk.yml`: 모델(ggml) 다운로드 → `assets/`에 포함 → debug-signed APK 생성 → Actions artifact로 업로드. 사용자는 Actions 화면에서 APK를 내려받아 설치("출처를 알 수 없는 앱" 허용 필요).
- 개인용이므로 release 서명·스토어 배포 절차는 생략한다.

### 6.2 저장소 기반 테스트 설계 (사용자 결정 반영)

**데이터 배치 규칙** (사용자가 업로드):

```
testdata/
  audio/      2026-10-01_a.m4a      ← 음성 녹음 앱 원본 그대로
  transcript/ 2026-10-01_a.txt      ← 정답 전사문(말한 그대로, 필수)
  actions/    2026-10-01_a.md       ← 정답 체크리스트(선택, 행동 추출 평가용)
  whitelist.tsv                     ← 오인식<TAB>정답 (선택)
```

- 파일명(확장자 제외)이 같으면 한 쌍으로 인식한다. 5분 m4a는 수 MB 수준이라 Git LFS 없이 커밋 가능(GitHub 단일 파일 100 MB 제한 이내).

**워크플로 `stt-benchmark.yml`** (testdata 변경 시 또는 수동 실행):

1. whisper.cpp(앱과 **동일 태그**)를 Linux 러너에서 빌드, base·small 양자화 모델 다운로드.
2. m4a → 16 kHz mono WAV 변환(CI에서는 ffmpeg 사용; 앱은 MediaCodec을 쓰므로 디코더 차이로 미세한 편차가 있을 수 있음).
3. 앱과 동일한 파라미터(language=ko, VAD, initial_prompt, 스레드 4)로 전사.
4. **CER 계산:** 공백·문장부호 제거 및 유니코드 정규화(NFC) 후 문자 단위 편집 거리 / 정답 길이. 한국어는 띄어쓰기 불일치가 잦아 WER 대신 CER을 쓴다 [11].
5. 규칙 기반 행동 추출 결과를 `actions/` 정답과 비교해 재현율(recall)·정밀도(precision) 계산(정답 파일이 있을 때).
6. 결과를 Markdown 보고서(파일별·평균 CER, 모델별 비교표, 처리 시간 참고치)로 Actions Summary와 artifact에 출력.

**측정 범위의 구분:**

| 지표 | 측정 위치 | 비고 |
|---|---|---|
| STT 정확도(CER), whitelist 효과 | CI (저장소) | 동일 whisper.cpp·동일 모델이므로 기기 결과를 대표 |
| 규칙 기반 행동 추출 정확도 | CI (저장소) | |
| Gemini Nano 행동 추출 품질 | 기기 | AICore는 CI에서 실행 불가 |
| 처리 시간, 발열, 메모리 | 기기 (결과 화면 처리 통계) | x86 러너 속도는 기기와 무관 |

---

## 7. 예상 AI 토큰 사용량 및 비용

### 7.1 산정 가정
- 산출 규모: Kotlin 약 3,000~4,000줄, C/C++ JNI 약 300~500줄, Gradle/CI 워크플로 2종 약 400줄, 벤치마크 스크립트(Python) 약 200~300줄. (텔레그램 삭제로 감소, 저장소 벤치마크 추가로 증가)
- 에이전트형 개발에서는 매 도구 호출마다 누적 대화 맥락이 다시 입력되므로 입력 토큰이 출력의 20~40배이며, 입력의 대부분은 프롬프트 캐시에서 읽힌다.
- **출력 토큰에는 모델의 사고(thinking) 토큰이 포함되며 출력 단가로 과금된다.**
- 최대 불확실성: 로컬 빌드 불가로 인한 **CI 실패 → 로그 확인 → 수정** 반복 횟수와 실기기 피드백 반복 횟수.

### 7.2 단계별 토큰 추정

| Phase | 입력 토큰(누적) | 출력 토큰(사고 포함) |
|---|---|---|
| 계획 수립(v0.1~v0.2, 완료) | 0.3 ~ 0.4 M | 25 ~ 40 K |
| 0. 골격·CI·저장소 벤치마크 | 0.6 ~ 1.2 M | 25 ~ 50 K |
| 1. STT 코어(JNI 포함) | 0.6 ~ 1.2 M | 25 ~ 50 K |
| 2. 입력 | 0.2 ~ 0.4 M | 8 ~ 15 K |
| 3. 행동 추출 | 0.4 ~ 0.8 M | 15 ~ 35 K |
| 4. 출력 UX | 0.3 ~ 0.6 M | 12 ~ 25 K |
| 5. Whitelist | 0.3 ~ 0.6 M | 12 ~ 25 K |
| 6. CI 디버깅·실기기 피드백 반복 | 0.8 ~ 3.2 M | 25 ~ 160 K |
| **합계** | **약 3.5 ~ 8.5 M** | **약 150 ~ 400 K** |

중앙 추정치: 입력 약 5.5 M, 출력 약 250 K.

### 7.3 종량제(API) 환산 비용 — Claude Opus 5.5 기준

**단가(USD / 1M tokens) [16][17]:** 입력 $4.00, 출력 $20.00, 캐시 읽기 $0.20, 캐시 쓰기 $5.00(5분 TTL, 입력의 1.25배) / $8.00(1시간 TTL, 입력의 2배).

**입력 구성 가정:** 캐시 읽기 85%, 캐시 쓰기 10%(보수적으로 1시간 TTL 단가 적용), 비캐시 입력 5%.

| 구성 요소 | 낮은 추정 (3.5 M / 150 K) | 중앙 (5.5 M / 250 K) | 높은 추정 (8.5 M / 400 K) |
|---|---|---|---|
| 캐시 읽기 (×$0.20) | 2.975 M → $0.60 | 4.675 M → $0.94 | 7.225 M → $1.45 |
| 캐시 쓰기 (×$8.00) | 0.35 M → $2.80 | 0.55 M → $4.40 | 0.85 M → $6.80 |
| 비캐시 입력 (×$4.00) | 0.175 M → $0.70 | 0.275 M → $1.10 | 0.425 M → $1.70 |
| 출력 (×$20.00) | 0.15 M → $3.00 | 0.25 M → $5.00 | 0.40 M → $8.00 |
| **합계** | **≈ $7.1** | **≈ $11.4** | **≈ $18.0** |

- 추정 불확실성(±50%)을 반영한 실무적 범위: **약 $5 ~ $27**, 중앙값 약 **$11**.
- 캐시 적중률이 낮아지면(예: 세션을 자주 새로 시작) 비용이 증가한다. 캐시 쓰기가 5분 TTL로 처리되면 중앙값은 약 $9.8로 감소한다.
- 이 금액은 Claude Code 개발 세션의 비용이며, **완성된 앱은 API를 전혀 호출하지 않으므로 운영 비용은 $0**이다.
- 참고: GitHub Actions 사용량은 별도이다(공개 저장소 무료, 비공개 저장소는 계정의 월간 무료 분(分) 한도 적용).

### 7.4 구독 플랜 사용량 추정

- **Anthropic은 구독 플랜의 한도를 토큰 수로 공개하지 않는다.** 공식 문서는 5시간 단위 세션 한도, 주간 한도, 그리고 Pro 대비 배수(Max 5x = Pro의 5배, Max 20x = 20배)만 공개한다 [18][19]. 따라서 "플랜의 몇 %"를 정확히 산출하는 것은 불가능하며, 아래는 **API 환산 금액 기반의 근사**다.

| 플랜 (월 요금) [19] | API 환산 중앙값 $11.4의 월 요금 대비 비율 | 해석(근사) |
|---|---|---|
| Pro ($20/월) | 약 57% (범위 35~90%) | 5시간 세션 한도에 여러 번 도달할 가능성이 높음 → 여러 날·여러 세션으로 나누어 진행 권장 |
| Max 5x ($100/월~) | 약 11% (범위 7~18%) | 1~2개 세션 창에서 대부분 진행 가능할 것으로 예상 |
| Max 20x | Max 5x의 1/4 수준 | 한도 영향 거의 없음 |

- 구독 한도는 월 요금을 API 단가로 환산한 금액과 일치하지 않으며(모델·부하·기간에 따라 달라짐), 위 비율은 "상대적 부담"을 가늠하기 위한 참고치에 불과하다.
- **보정 방법(권장):** Phase 0 시작 전후로 claude.ai의 설정 → 사용량(Usage) 화면의 세션·주간 사용률을 기록하면, Phase 0의 실제 소모량으로 나머지 단계의 사용률을 비례 추정할 수 있다. Phase 0 종료 시 이 계획서의 §7을 실측치로 갱신한다.

---

## 8. 남은 확인 사항

- 테스트 데이터 업로드: `testdata/` 구조(§6.2)에 맞추어 3~5쌍 이상 업로드. 정답 전사문은 "말한 그대로"(군말 포함 여부는 일관되게) 작성.
- 저장소 공개 여부(Actions 사용량 한도와 관련).

---

## 9. 구현 현황 (v0.3, 2026-09-27)

Phase 1–5의 기능을 1차 구현했다(테스트 데이터 업로드 전이므로 Phase 0 벤치마크 워크플로는 아직 없음).

| 항목 | 구현 내용 | 계획 대비 변경 |
|---|---|---|
| STT | whisper.cpp v1.9.4 서브모듈, JNI, `ggml-base-q8_0.bin` + `ggml-silero-v6.2.0.bin`(VAD), beam search 5, `language=ko`, `suppress_nst` | — |
| 빌드 플래그 | `GGML_CPU_ARM_ARCH=armv8.2-a+fp16+dotprod+i8mm`, OpenMP 끔(스핀 대기에 따른 전력 소모 방지), arm64-v8a 단일 ABI | — |
| 디코딩 | MediaCodec → 스트리밍 windowed-sinc 리샘플러(16 kHz mono) | 전체 원본 PCM을 메모리에 올리지 않도록 스트리밍 방식 채택 |
| 발열 | 시작 시 `MODERATE` 이상이면 2스레드, 처리 중 `SEVERE` 이상이면 **중단** 후 안내 | "일시 정지"를 "중단"으로 단순화 |
| 저장소 | 이력·교정 사전을 JSON 파일로 저장 | Room 대신 JSON(의존성·빌드 복잡도 감소) |
| 입력 | 최근 녹음 목록(오늘 최신 파일 기본 선택) + [다른 파일 선택] | 공유 인텐트 수신은 제외(사용자 결정: 앱 실행 후 선택) |
| 할 일 정리 | Gemini Nano(90초 제한) → 실패/빈 결과 시 규칙 기반, 결과 화면에서 [AI로 다시 정리] | — |
| 서명 | release 빌드를 debug 키로 서명(개인 사이드로드용) | — |

---

## 10. References

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
15. Gizmochina, "Galaxy S26 has Exynos 2600, reveals Geekbench listing" (SM-S942N). https://www.gizmochina.com/2026/02/03/galaxy-s26-exynos-2600-geekbench-listing/
16. Anthropic, "Pricing" (Claude API). https://platform.claude.com/docs/en/about-claude/pricing
17. Anthropic, "Prompt caching" (캐시 쓰기 1.25×/2×, 캐시 읽기 단가). https://platform.claude.com/docs/en/build-with-claude/prompt-caching
18. Claude Help Center, "What is the Max plan?" https://support.claude.com/en/articles/11049741-what-is-the-max-plan
19. Anthropic, "Plans & Pricing." https://claude.com/pricing
