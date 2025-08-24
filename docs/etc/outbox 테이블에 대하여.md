# 비즈니스는 송금 요청이라는 강력한 트랜잭션 처리 도메인.

송신자 잔액 차감, 수신자 잔액 증가, Transaction 생성은 반드시 DB 원자성을 보장해야 한다.  
동시에, 이 이벤트를 Kafka로 발행해서 FDS/회계/알림 등 외부 시스템에 알려야 할 수도 있다.

> 내부 DB 상태 일관성과 외부 발행 일관성을 어떻게 맞출까? 라는 생각을 시작으로 Outbox 패턴이 필요하였다.

## Outbox가 필요한 이유
DB와 Kafka 간 이중 쓰기 문제 (Dual-write Problem) 가 발생 할 수 있다.
단순하게 트랜잭션 처리 후 Kafka 발행을 Application Service에서 한다면,  
- DB는 commit 성공했는데 Kafka publish 실패할 수 있어, 데이터 불일치가 발생할 수 있다.
- Kafka publish는 성공했는데 DB rollback 된 경우, 이벤트는 나갔는데 실제 상태는 없을수도 있다.

## Exactly-once vs At-least-once 보장
송금/거래 이벤트는 절대로 중복/손실되면 안 된다...  
Kafka만 쓰면 at-least-once까진 보장되지만, DB 상태와 동기화는 개발자가 직접 맞춰야 한다.  

Outbox에 저장하면, DB commit = 이벤트 기록 commit 의 형태가 되어야 한다.  
Worker가 Outbox를 읽어 Kafka로 발행하고, 실패하면 재시도가 가능하도록 설계가 되어야 한다.  
중복 처리 방지는, Outbox의 event_id unique constraint 가 되도록 설계 해야 한다.  

## 장애 격리 & 재처리 용이성
Kafka 브로커 장애, 네트워크 장애 등은 DB 트랜잭션과 직접 묶이면 API 자체가 충분히, 실패로 터져버릴 수 있다.  
Outbox가 있으면, API는 DB commit 까지만 책임을 갖고, 사용자에게 성공 응답을 줄 수 있게 된다.  
발행은 별도 Worker에서 재시도하고, 외부 장애로부터 API를 격리 할 수 있는 이점을 갖게 된다.  

## 운영/분석 활용성
Outbox 테이블 자체가 발행 이벤트 로그 역할을 겸하면.  
“이벤트가 언제 생성됐고 언제 발행됐는지” 추적 가능하다.  
마치, DLQ (Dead Letter Queue) 처럼 활용 가능하게 된다. 

> published_at=null 이 오래된 이벤트 탐지

## 송금 도메인에서 Outbox가 없다면?
API 트랜잭션과 Kafka publish를 함께 처리하다가,  
사용자 계좌 잔액은 차감됐는데, FDS에 이벤트 안 나갈 경우, 이상 거래 감지가 불가하게 된다.  
반대로, 이벤트는 나갔는데 실제 잔액은 롤백되는 경우엔, `잘못된 알람`, `회계 이상` 이 발생할 수 있다.  
금융/송금 시스템에선 법적 리스크로 이어질 수 있다.  

## 정리
DB commit 시 Outbox row insert는 곧 DB 원자성을 보장한다.  
Worker가 Outbox row 읽어 Kafka publish 는 외부 전송으로 책임을 분리하자.  
published_at 업데이트 는 중복 방지/재시도 관리 에 중요한 역할을 한다.  
API는 Outbox insert까지만 책임지고, 발행 실패는 Worker에서 처리하자.  

> 송금 비즈니스는 내부 트랜잭션 정확성 + 외부 이벤트 발행 신뢰성이 모두 필요하기 때문에,
Outbox 테이블을 두도록 하자.
