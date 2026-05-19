# ADR-008: buildSrc → build-logic 이관

- **Status**: Accepted
- **Date**: 2025-11-XX
- **Tags**: build, gradle, productivity

---

## Context

초기에 `buildSrc/` 디렉터리에 Gradle 공통 설정을 모아두었다. Gradle 의 default behavior 로 `buildSrc` 는 **모든 모듈을 invalidate 시킨다** — `buildSrc/` 안의 단일 파일 수정이 전체 빌드 캐시를 무효화. 모듈 수가 늘면서 빌드가 점점 느려졌다.

증상:

- `buildSrc/src/main/kotlin/Dependencies.kt` 의 버전 한 줄 수정 → 모든 모듈 recompile.
- IDE 의 Gradle sync 가 5분 이상 소요.
- CI 의 cache hit rate 가 낮음.

Gradle 8.x 부터 공식 권장은 `build-logic` 별도 included build.

## Decision

`buildSrc` 제거 → `build-logic/` 별도 included build.

**`build-logic/` 구성**:

```
build-logic/
├── settings.gradle.kts
├── build.gradle.kts
└── src/main/kotlin/transentia/
    ├── RootConventionsPlugin.kt        # 루트 공통
    ├── SpringLibraryConventionPlugin.kt # spring-context + 기본 의존성
    ├── SpringBootAppConventionPlugin.kt # spring-boot-starter + boot plugin
    ├── SpringJpaConventionPlugin.kt    # JPA + postgresql
    ├── KafkaConventionPlugin.kt         # Avro + Schema Registry
    ├── CodeCoverageConventionPlugin.kt  # JaCoCo
    └── Modules.kt                      # 모듈 이름 enum
```

`settings.gradle.kts` 의 `includeBuild("build-logic")` 로 등록. 각 모듈은 `id("transentia.spring-library")` 같이 적용.

## Consequences

### Positive

- `build-logic` 변경이 included build 단위로 격리. 모듈별 캐시 hit rate 향상.
- 컨벤션 1곳에 모임. boilerplate 제거 (`build.gradle.kts` 모듈당 5~10줄).
- 신규 모듈 추가 시 plugin 적용만으로 컨벤션 상속.

### Negative

- 학습 곡선. Gradle 의 plugin DSL, `apply` 메커니즘 이해 필요.
- Convention plugin 디버깅이 까다로움 — IDE 의 자동 완성 도움 약함.
- 다음 빌드 step 추가 시 plugin 안에서 작업해야 함. 익숙하지 않은 사람은 모듈에 직접 추가하려는 경향.

### Neutral

- `build-logic` 자체가 모듈이라 별도 `build.gradle.kts` 관리 필요.

## Alternatives Considered

| 대안 | 장점 | 단점 | 채택 여부 |
|---|---|---|---|
| **buildSrc 유지** | 친숙, 추가 학습 0 | 전체 캐시 invalidation | ✗ |
| **각 모듈에 boilerplate 복붙** | plugin 학습 불필요 | 동기화 지옥, drift 발생 | ✗ |
| **Composite build with plugin module** | 분리도 명확 | 과한 구조 | ✗ |
| **build-logic included build** | Gradle 8 권장, 캐시 격리 | 학습 곡선 | ✓ |

## Trade-offs

- **친숙함 vs 캐시**: buildSrc 는 친숙하지만 캐시 성능을 갉아먹음. 모듈 수가 작으면 무시 가능, 큰 프로젝트일수록 build-logic 이 정답.
- **추상화 vs 명시성**: convention plugin = 추상화 ↑, 신규 합류자는 "왜 이 모듈이 이 의존성을 가지는지" 추적 어려움.

## References

- 코드:
  - `build-logic/src/main/kotlin/transentia/*.kt`
  - `settings.gradle.kts` (includeBuild)
- 커밋: `740c071 refactor: root gradle.kts, buildSrc 제거 -> build-logic 으로 이관`
- 문서:
  - `docs/etc/[blog] buildSrc 를 걷어내고 build-logic 을 도입해보자.md`
  - `docs/etc/Gradle이 권장하는 방식인 Convention Plugin.md`
- 외부:
  - [Gradle: Sharing Build Logic between Subprojects](https://docs.gradle.org/current/samples/sample_convention_plugins.html)
  - [Gradle: buildSrc vs build-logic](https://docs.gradle.org/current/userguide/sharing_build_logic_between_subprojects.html)
