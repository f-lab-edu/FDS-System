# 코드 품질 비교 — 참고 프로젝트와의 갭

> `/Users/hyungki/project/study/back-end/loop-pack-be-l2-vol3-kotlin` 의 패턴을 흡수하면서 든 점들이에요.

## 시작 — "구린것 같은데" 라는 한 줄

리뷰에서 받은 한 줄은 정확했어요.
ADR 11개, 실험 6개, FAILURE-MODES 같은 외형 문서는 쌓아둔 게 많은데, 정작 도메인 코드를 열어보면 `var status` 로 mutation 하고, Testcontainers 베이스를 추상 클래스 상속으로 묶어둔 자리들이 그대로 남아 있었습니다.
참고 프로젝트와 같은 위치(Spring Boot 3 + Kotlin + 헥사고날) 인데 코드 모양은 한 세대 떨어져 있는 느낌이었어요.

## 갭 5개

### 1) 도메인 가변성 — `var status`

내 `Transaction.kt` 가 `var status` 를 들고 있었고, `complete()` 가 자기 status 를 바꾼 뒤 이벤트를 반환했어요.
참고 프로젝트 `Order.kt` 의 `cancel()` / `complete()` 는 모두 새 `Order` 인스턴스를 반환합니다. `val` 만 쓰고 mutation 0.

mutation 자체가 한 줄 더 짧긴 해요.
근데 멀티스레드 영역으로 가면 같은 객체를 두 곳에서 들고 있으면서 한쪽이 `complete()` 부르고 다른 쪽이 `status == PENDING` 가정으로 분기하는 사고가 충분히 가능합니다.
이번에 `var` → `val` + `Completion(transaction, event)` 묶음 반환으로 교체하면서, 호출처가 새 인스턴스를 명시적으로 받게 강제됐어요.

### 2) Testcontainers — 베이스 클래스 vs @Configuration

기존엔 `RedisIntegrationTestBase` 같은 추상 클래스를 만들고 통합 테스트가 상속하는 구조였어요.
참고 프로젝트는 `modules/jpa/src/testFixtures/.../MySqlTestContainersConfig.kt` 처럼 `@Configuration` + `companion object init` 으로 두고, `@SpringBootTest` 가 컴포넌트 스캔으로 알아서 픽업합니다.

작아 보이는 차이인데 영향이 큽니다.
- 베이스 클래스는 단일 모듈 안에서만 의미가 있어요.
- `java-test-fixtures` 의 `@Configuration` 은 다른 모듈/apps 가 `testFixturesImplementation(testFixtures(project(":transfer-infra")))` 한 줄로 그대로 끌어다 씁니다.
- 상속이 강제 안 되니까 한 테스트가 두 컨테이너 (`@Import(RedisTestContainersConfig::class, PostgresTestContainersConfig::class)`) 를 동시에 들 수도 있어요. 다중 상속 우회 같은 거 안 해도 됩니다.

전환 후 코드량은 비슷한데 재사용성이 다른 차원이 됐어요.

### 3) Factory 명명 — `of()` 가 무엇을 의미하나

내 도메인은 `Transaction.of(...)` / `User.of(...)` 같은 영어 관용구를 썼는데, 정작 코드 리뷰에서 "이게 신규 생성인지 DB 복원인지" 질문이 한 번씩 나왔어요.
참고 프로젝트는 `Order.create(userId, items)` 와 `Order.reconstitute(persistenceId, refUserId, ...)` 로 의도를 이름에 박아뒀습니다.

이번에 `create()` / `reconstitute()` 로 바꾸면서 `of()` / `restored()` 는 `@Deprecated(ReplaceWith)` 로만 남겨뒀어요.
컴파일러가 IDE 에서 노란줄로 알려주니까 호출처가 자연스럽게 옮겨가는 자리예요.

### 4) assertion vs predicate 분리

`TransferValidator` 가 `if (sender.isBlacklisted()) throw ...` 같은 분기를 11줄에 걸쳐 들고 있었어요.
참고 프로젝트 `Order` 는 `assertOwnedBy(userId)` (예외 throw) 와 `isOwnedBy(userId)` (Boolean) 가 분리돼서, 호출처는 자기 필요에 맞춰 골라 씁니다.

이번에 `User.canSend()` (predicate) + `User.assertCanSend()` (assertion) 로 둘 다 노출했고, `TransferValidator` 는 3줄로 줄었어요.
`assertCanSend` 안에서 "왜 안 되는지" 까지 정확한 메시지로 던지니까 호출처에서 if/throw 반복할 일이 없어졌습니다.

### 5) 모듈 구조 — 너무 많은 모듈

참고 프로젝트는 `apps/` + `modules/` + `supports/` 3층 구조예요.
내 건 `services/{transfer,fds}/{domain,application,infra,instances}` + `infrastructure/kafka/{producer,consumer,config,model}` + `common/{domain,application}` 로 모듈이 13~14개 됩니다.

이건 한 번에 못 고쳐요.
도메인 분리(transfer / fds) 자체는 유지하되, 다음 라운드에서 `infrastructure/kafka/*` 4개를 `modules/kafka` 하나로 합치는 자리 정도가 후보예요.
당장 `apps/` + `modules/` 로 이름만 옮기는 건 의미 없으니, 모듈을 합칠 명분이 생기는 시점까지 미뤘습니다.

## 이번 PR 에서 손본 자리

- testFixtures 패턴으로 Testcontainers 인프라 재구성 (4개 파일).
- `Transaction` 불변화 + `Completion` 묶음 반환 + `create`/`reconstitute` factory.
- `User.canSend` / `assertCanSend` + `TransferValidator` 단순화.

## 손 안 댄 자리

- 모듈 구조 통합 — 다음 라운드.
- `Money` / `Amount` 의 중복 — 참고 프로젝트는 `Money` 한 종류만, 내 건 두 클래스 공존. 정리 비용 큼.
- `services/fds/.../container/...` 디렉터리명의 일관성 — `container` 라는 이름이 의미 모호. 다음 라운드.
- detekt/ktlint — 코드 스타일 자동화. CI 게이트에는 들어가 있지만 정책 파일 부재.

## 한 줄 정리

외형 문서 쌓는 건 빨라요. 도메인 모델의 모양이 그 자리에서 같이 안 바뀌면 결국 "AI 가 만든 것 같다" 가 됩니다.
참고 프로젝트가 좋아 보였던 이유는 외형이 아니라 도메인 한 클래스의 무게가 달랐던 거였어요.
다음에 새 도메인을 만들 때는 첫 클래스부터 `val` 만 쓰는 식으로 가는 게 맞겠습니다.

## 관련 커밋

- `7c5cded` testFixtures 패턴
- `15c5053` Transaction 불변화
- `2882439` User assertion/predicate
