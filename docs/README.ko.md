# Swagger Viewer — IntelliJ Plugin

🌐 **한국어**
| [English](https://github.com/delight-HK3/swagger-viewer/blob/main/README.md)

![Swagger Viewer Preview](docs/img/swagger-annotation.png)

**Preview your Swagger/OpenAPI documentation instantly inside JetBrains IDEA — no build, no save, no server required.**

Swagger 어노테이션을 수정하는 즉시 옆 Tool Window에서 실시간으로 Swagger UI 미리보기가 갱신됩니다.
IntelliJ IDEA Community / Ultimate 모두 동일하게 동작합니다.

---

## 주요 기능

### 어노테이션 실시간 미리보기
- `@RestController` / `@Controller` 클래스의 Swagger 어노테이션을 PSI 정적 분석으로 파싱
- `@Operation`, `@ApiResponse`, `@Parameter`, `@OpenAPIDefinition`, `@SecurityScheme` 등 지원
- 파일 저장이나 빌드 없이 **타이핑하는 즉시** Tool Window 미리보기가 갱신됨
- Spring Boot / Spring MVC 기반 Java·Kotlin 프로젝트 대상

### YAML / JSON 스펙 파일 미리보기
- `openapi: 3.x` 또는 `swagger: 2.0` 키가 포함된 `.yaml / .yml / .json` 파일을 열면 자동 감지
- 파일 수정 시 실시간으로 미리보기 갱신 (300ms 디바운스)
- 일반 YAML / JSON 파일에는 끼어들지 않음

### IDE 통합
- IDE 우측 **Swagger Viewer** Tool Window 자동 표시 (별도 브라우저 불필요)
- Annotation 탭 · YAML 탭 자동 구성 (관련 파일이 없는 탭은 숨김)
- IntelliJ IDEA **Community + Ultimate에서 기능 완전 동일**

---

## 동작 방식

```
타이핑 (DocumentListener)
    └─ 200ms 디바운스 (Coroutine)
        └─ ReadAction (백그라운드)
            └─ PSI 정적 분석 → Swagger 모델 변환 → YAML 직렬화
                └─ EDT: JCEF(내장 Chromium)에 렌더링
```

- **네트워크 불필요**: `swagger-ui-dist` 정적 자산이 플러그인에 번들되어 `file://`로 로드
- **EDT 블로킹 없음**: PSI 파싱은 백그라운드 `ReadAction`, 결과만 EDT로 전달
- **Ultimate 전용 API 미사용**: Community 환경 기준으로 전체 기능 동작

---

## 요구 사항

- IntelliJ IDEA **2024.2** 이상 (Community 또는 Ultimate)
- 어노테이션 미리보기: Spring Boot / Spring MVC 기반 프로젝트

---

## 빌드 및 개발

```bash
./gradlew buildPlugin           # 플러그인 zip 빌드
./gradlew test                  # 단위 테스트
./gradlew verifyPlugin          # Plugin Verifier (Internal API 사용 여부, 바이너리 호환성)
./gradlew verifyPluginStructure # plugin.xml 구조 검증
./gradlew runIde                # 샌드박스 IDE 실행 (UI 직접 확인용)
```

---

## 프로젝트 구조

```
src/main/kotlin/com/github/swaggerViewer/
├── view/
│   ├── SwaggerViewerToolWindowFactory.kt  # Tool Window 등록 및 생명주기
│   ├── SwaggerViewerPanel.kt              # 어노테이션 미리보기 탭 (JCEF)
│   ├── SwaggerViewerYamlPanel.kt          # YAML/JSON 미리보기 탭 (JCEF)
│   └── SwaggerPreviewHtmlBuilder.kt       # Swagger UI HTML 생성
├── service/
│   ├── annotation/
│   │   ├── SwaggerPreviewPipeline.kt      # 파이프라인 진입점 (디바운스 · 조율)
│   │   ├── SwaggerAnnotationScanner.kt    # PSI 순회 → 어노테이션 수집
│   │   ├── SwaggerAnnotationSerializer.kt # 내부 모델 → OpenAPI YAML 직렬화
│   │   ├── PsiSchemaAnalyzer.kt           # PSI 타입 → Schema 변환
│   │   ├── AnnotationValueReader.kt       # 어노테이션 속성값 추출 유틸
│   │   └── parser/                        # 어노테이션별 파서 (Operation, Parameter, Response…)
│   ├── yaml/
│   │   └── SwaggerSpecDetector.kt         # OpenAPI 스펙 파일 감지
│   └── common/
│       └── SwaggerAssetsExtractor.kt      # swagger-ui-dist 번들 자산 추출
└── model/                                 # Swagger 어노테이션 내부 데이터 모델
```
