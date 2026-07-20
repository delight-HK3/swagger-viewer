# Swagger viewer

**작성한 swagger 문서를 빌드없이 바로 JetBrains IDEA에서**

## 만든이유

- Swagger로 API 문서를 작성하고 프론트엔드 및 타 개발자들에게 공유를 하려면 빌드 및 배포를 해야 합니다.
여기서 만약 문서 내용이 오타가 나거나 내용이 잘못되었다면 다시 수정하여 빌드를 다시 해야 하는 불편함이 발생합니다.
그렇기에 사전에 문서가 어떻게 나오는지 알면 이러한 실수를 방지하고자 개발하게 되었습니다.

## 주요기능

### 어노테이션 실시간 미리보기
- **Spring 계열 프로젝트 전용** — Spring Boot / Spring MVC 기반 Java·Kotlin 프로젝트를 대상으로 함
- `@RestController` / `@Controller` 클래스의 Swagger 어노테이션을 PSI 정적 분석으로 스캔
- `@Operation`, `@ApiResponse`, `@Parameter`, `@OpenAPIDefinition` 등 Swagger 어노테이션 지원
- `@Operation(requestBody = @RequestBody(...))` 형태의 OAS 요청 바디 명세 지원 (Spring `@RequestBody`는 인식하지 않음)
- 타이핑하는 즉시 Tool Window의 Swagger UI 미리보기가 자동으로 갱신 (빌드·저장·앱 실행 불필요)
### YAML / JSON 스펙 파일 미리보기
- 프로젝트 내 OpenAPI 스펙 파일(`.yaml`, `.yml`, `.json`)을 자동으로 감지
- 에디터에서 해당 파일을 열면 Tool Window에 즉시 렌더링
- 파일 편집 중에도 실시간으로 미리보기 갱신
### IDE 통합
- IDE 우측 Tool Window로 제공 — 별도 브라우저 없이 IDEA 안에서 확인
- 어노테이션 탭 / YAML 탭이 자동으로 구성 (해당 파일이 없는 탭은 표시되지 않음)
- IntelliJ IDEA Community / Ultimate 모두 동일하게 동작

## 요구사항

- JetBrains IDE 2024.2 이상
- Spring Boot / Spring MVC 기반 프로젝트 (어노테이션 미리보기 기능 사용 시)

## 시작하기

1. 마켓플레이스에서 플러그인 설치
2. IDE 우측에 **Swagger Viewer** Tool Window가 자동으로 표시됨

- **어노테이션 미리보기**: `@RestController` 또는 `@Operation`이 있는 파일을 편집하면 Annotation 탭에서 즉시 확인
- **YAML/JSON 미리보기**: OpenAPI 스펙 파일(`.yaml`, `.yml`, `.json`)을 에디터에서 열면 YAML 탭에서 확인
