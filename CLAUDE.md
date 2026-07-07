# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 프로젝트 개요

**swagger-viewer**: JetBrains IDE 전용 플러그인.
Spring Boot에서 swagger 어노테이션으로 문서를 작성할 때, 타이핑하는 즉시 옆(Tool Window)에서
미리보기가 실시간으로 갱신되도록 하는 것이 목표.

## 핵심 원칙

이 프로젝트는 swagger 문서를 작성하는 과정에서 오타나 잘못된 내용을 즉시 발견할 수 있도록,
**타이핑하는 동안 옆(Tool Window)에서 실시간으로 미리보기가 갱신되는 것**이 핵심 목표다.
빌드 없이, 저장 없이, 앱 실행 없이 — 코드를 수정하는 즉시 반영되어야 한다.
IntelliJ IDEA Ultimate 뿐만 아니라 Community에서도 사용 가능해야 한다.

**핵심 문제 정의**: 사용자가 swagger 어노테이션(@Operation, @ApiResponse 등)을 수정할 때마다
전체 애플리케이션을 빌드/실행해서 /swagger-ui 를 확인하는 비용을 없앤다.
→ 이 플러그인은 **PSI(Program Structure Interface) 정적 분석만으로** 어노테이션을 파싱해서
미리보기를 렌더링한다. 런타임 실행이나 Spring 애플리케이션 컨텍스트 로딩에 의존하지 않는다.
→ **"실시간"의 의미**: 파일 저장이나 빌드 트리거가 아니라, 문서 편집(Document 변경) 자체에
반응해서 갱신되어야 한다. 즉 `PsiDocumentManager` / `PsiTreeChangeListener` /
`DocumentListener` 등을 활용해 타이핑 중에도 (디바운스를 걸더라도) 미리보기가 자연스럽게
따라와야 한다.

**호환성 목표**: IntelliJ IDEA **Community + Ultimate에서 완전히 동일한 기능**을 제공해야 한다.
→ "Community에서도 최소한 동작" 수준이 아니라, **두 에디션 간 기능 차이가 없어야 한다.**
→ 이를 위해 **Ultimate 전용 API(Spring 플러그인, JavaEE 툴즈 등)는 아예 사용하지 않는다.**
Community에 없는 API를 optional dependency로 "분리"해서 Ultimate에서만 더 좋은 기능을
주는 방식은 지양한다 — 기능 설계 단계에서부터 Ultimate 전용 API를 배제한다.
(예: Spring 어노테이션 인식이 필요하면 Ultimate의 `com.intellij.spring` 대신, 자체 PSI
분석으로 `@Operation` 등 어노테이션을 직접 파싱한다.)
→ 새 기능을 설계/구현할 때는 항상 "이 API가 Community에도 번들되어 있는가?"를 먼저 확인한다.
(현재 의존 중인 `com.intellij.java`, `org.jetbrains.plugins.yaml`은 Community에도
번들되어 있어 문제없음.)
→ Ultimate 전용 API가 아니면 구현할 수 없는 기능이 발견되면, 바로 구현하지 말고 먼저
사용자에게 알린다 (기능 자체를 포기할지, 대체 구현 방법을 찾을지 논의 필요).

## 기술 스택

- **Gradle IntelliJ Platform Plugin 2.x** (`org.jetbrains.intellij.platform`)
- Kotlin
- 대상 플랫폼: IntelliJ IDEA Community <버전> ~ <버전>
  (`build.gradle.kts`의 `intellijPlatform` 블록과 `plugin.xml`의
  `<idea-version since-build="" until-build=""/>`는 항상 서로 일치시킬 것)

## 빌드 및 실행 명령어

```
./gradlew buildPlugin              # 플러그인 zip 빌드 (dist/)
./gradlew runIde                   # 샌드박스 IDE 실행 (사람이 직접 확인할 때만 요청)
./gradlew test                     # 단위 테스트
./gradlew verifyPlugin             # Plugin Verifier — Internal API 사용, 바이너리 호환성 검사
./gradlew verifyPluginStructure    # plugin.xml/구조 검증
```

**Claude Code 작업 규칙**: 코드 변경 후 기본 검증은 `test` + `verifyPlugin`으로 한다.
`runIde`는 GUI가 뜨는 무거운 작업이므로 사용자가 명시적으로 요청할 때만 실행한다.

## 프로젝트 구조 및 책임 분리

| 폴더 | 역할 |
|------|------|
| `/view` | 사용자에게 보이는 영역 — ToolWindowFactory, 미리보기 렌더링(JCEF/Swing), Action |
| `/service` | 비즈니스 로직 — PSI 파싱, 어노테이션→모델 변환, 문서 생성 |
| `/model` | swagger 어노테이션을 표현하는 내부 데이터 모델 *(필요시 추가)* |

- `/view`는 `/service`가 만든 모델만 소비한다. PSI 접근이나 어노테이션 파싱 로직이 View
  레이어로 새어나가면 안 됨.
- `/service`의 파싱 로직은 애플리케이션 실행 없이 PSI 트리 순회만으로 동작해야 한다
  (`PsiElementVisitor`, `UAST` 등 활용).

## 실시간 갱신 관련 기술 제약

1. **트리거**: 파일 저장이 아니라 문서 변경 이벤트(`DocumentListener`) 또는 PSI 변경
   (`PsiTreeChangeListener`)을 기준으로 갱신한다.
2. **디바운스 필수**: 키 입력마다 매번 전체 재파싱하면 타이핑이 버벅인다.
   짧은 디바운스(예: 200~300ms, `Alarm` 클래스 활용)를 걸어 과도한 재계산을 막는다.
3. **EDT 블로킹 금지**: PSI 파싱/모델 변환은 EDT(UI 스레드)에서 직접 돌리지 않는다.
   백그라운드 스레드에서 `ReadAction`으로 안전하게 읽고, 결과만 EDT로 넘겨 렌더링한다.
4. **증분 갱신 지향**: 파일 전체를 매번 다시 파싱하기보다, 변경된 범위(메서드/클래스 단위)만
   재파싱하는 것을 우선 고려한다 — 단, 초기 구현에서는 "전체 재파싱 + 디바운스"로 시작해도
   무방하다. 성능 이슈가 실제로 확인되면 그때 증분 갱신으로 최적화한다.
5. **Tool Window 생명주기**: `SwaggerPreviewToolWindowFactory`에서 등록한 리스너는 반드시
   `Disposable`에 연결해 Tool Window/프로젝트 종료 시 해제되도록 한다 (메모리 릭 방지).

## 코딩 컨벤션

1. 핵심 로직(파싱 규칙, 어노테이션 매핑 규칙 등)에는 반드시 그 근거를 주석으로 남긴다.
2. **Ultimate 전용 API는 사용하지 않는다.** (Community/Ultimate 기능 동등 원칙 — "핵심 원칙"
   참고) PR/코드 작성 시 사용하려는 API가 Community 배포판에도 번들되어 있는지 먼저 확인한다.
   불확실하면 IntelliJ Platform SDK 문서에서 해당 API가 속한 플러그인/모듈을 확인할 것.
3. 새 Extension Point 추가 시 `plugin.xml`에 등록하고, 왜 필요한지 주석으로 남긴다.
4. Deprecated/Internal API 사용 시 대체 가능한 Stable API가 없는지 먼저 확인한다
   (Plugin Verifier가 이를 검증하므로 사전에 걸러낼 것).

## 테스트

- `com.intellij.testFramework.fixtures.BasePlatformTestCase` 기반 단위 테스트
- PSI 파싱 로직은 fixture 소스 파일(`testData/`)로 커버, 실제 IDE 실행 없이 검증
- Community/Ultimate 기능 동등 원칙에 따라 별도 Ultimate 전용 테스트 스위트는 두지 않는다.
  모든 테스트는 Community 환경 기준으로 통과해야 한다.

## 아키텍처 결정 기록

- **Live 스펙 조회(실행 중인 앱에 HTTP로 폴링, 예: `/v3/api-docs`) 기능은 채택하지 않음.**
  "실시간"은 서버 조회가 아니라 **편집 중 즉시 반영**을 의미함.
  이유: 핵심 원칙(빌드/실행 없는 정적 미리보기)과 일치시키기 위함이며, 로컬이라도 네트워크
  접근이 생기면 Marketplace 보안/데이터 수집 심사 대상이 될 수 있음.
  향후 이 기능이 논의되면 핵심 원칙부터 재검토할 것.

## 배포 전 체크리스트 (JetBrains Marketplace 승인 가이드라인 v1.3, 2026-03-31)

> 개발 중 코드 작성 규칙이 아니라 **릴리즈 직전에만 확인하는 체크리스트**.
> 평소 개발 작업 시 Claude Code가 매번 참고할 필요는 없음 — "배포 준비"를 요청할 때만
> 이 섹션을 확인할 것.

### 콘텐츠
- [ ] 로고: 기본 템플릿/JetBrains 유사 로고 금지, 40x40px SVG
- [ ] 이름: 30자 이내, Latin 문자/숫자/기호만, "Plugin/IntelliJ/JetBrains" 등 금지,
  "A"/"." 시작 금지
- [ ] 벤더 웹사이트/이메일 유효성 (실제 소유 도메인/이메일 사용)
- [ ] 설명: 영어 1차 언어, 올바른 포맷/맞춤법/문법
- [ ] 변경 노트: 플레이스홀더 금지, 릴리즈마다 실제 내용으로 갱신
- [ ] 모든 외부 링크 유효성 및 관련성
- [ ] JetBrains 브랜드/저작권 침해 없음

### 기능
- [ ] 최소 1개 JetBrains 제품 호환, `sinceBuild`/`untilBuild` 명시
- [ ] `verifyPlugin` 통과 (Internal API 미사용)
- [ ] 보안 취약점/개인정보 이슈 없음
- [ ] IDE 성능에 악영향 없음 (특히 PSI 파싱을 EDT에서 블로킹하지 않는지)
- [ ] 라이선싱/구독/트라이얼 플로우 간섭 없음
- [ ] 데이터 수집 시 명시적 사용자 동의, 비활성 시 전송 금지
- [ ] 검색 조작(메타데이터 어뷰징) 없음

### 법적
- [ ] Developer Agreement 동의
- [ ] Developer EULA 포함
- [ ] 오픈소스인 경우 소스코드 링크 포함
- [ ] 개인정보 수집 시 Privacy Policy 포함
- [ ] EEA trader/non-trader 지위 선언
