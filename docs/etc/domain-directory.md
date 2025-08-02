```text
services/transfer/
├── domain/
│   ├── model/          # Aggregate, Entity, VO, Enum
│   ├── service/        # 도메인 서비스
│   ├── event/          # 도메인 이벤트
│   └── exception/      # 도메인 예외
├── application/
│   ├── usecase/        # 인터페이스 정의 (Port)
│   ├── service/        # 실제 구현체 (ApplicationService)
│   └── command/        # Request 모델 (Command)
├── infra/
│   ├── rdb/            # JPA 구현체
│   ├── event/          # Kafka producer, consumer
│   └── external/       # 외부 시스템 (ex. AuthClient)
```
