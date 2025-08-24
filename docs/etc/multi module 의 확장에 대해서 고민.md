## 📦 모듈 구조 및 의존성

### `:api`
```gradle
implementation(project(":transfer-application"))
implementation(project(":delivery-http"))
implementation("org.springframework.boot:spring-boot-starter-web")
```

### `:transfer-application`
```gradle
implementation(project(":transfer-domain"))
implementation(project(":shared-domain-error"))
implementation("org.springframework.boot:spring-boot-starter-data-jpa")
```

### `:transfer-infra`
```gradle
implementation(project(":transfer-application"))
// + JPA/Kafka 등 인프라 의존성
```

### `:delivery-http`
```gradle
api("org.springframework:spring-web")
implementation(project(":shared-domain-error"))
```

### `:shared-kernel`, `:shared-domain-error`
- **Spring 의존 없음**
- 순수 Kotlin 모듈

---

## 🪚 모듈 쪼개기 & 역할

### `shared` 쪼개기
- **`:shared-kernel`** → Snowflake 등 순수 유틸 이동
- **`:shared-domain-error`** → `DomainError`, `DomainException` 이동

### `delivery` 레이어
- **`:delivery-http`** → 전역 예외 핸들러, `ErrorResponse` (API만 의존)

### 도메인 구조 정렬
- `transfer-domain` / `transfer-application` / `transfer-infra`
- FDS 도메인도 동일 패턴 복제

### JPA Enum 컨버터
- 현재 한 도메인에서만 사용 → `transfer-infra`에 둠
- 2개 이상 도메인에서 사용 → `adapter-persistence-jpa`로 승격

---

## 📏 의존성 규율

- **`application`** → **web 의존 금지**
- **`api`** → `spring-boot-starter-web` 의존
- **`shared-*`** → 모든 모듈 의존 가능, **Spring 의존 금지**
- **도메인 계층** → 인프라/전달 계층 모름
