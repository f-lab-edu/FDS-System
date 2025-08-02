# Transentia

> 트랜잭션을 넘어, 자금의 흐름을 인식하고 판단하는 시스템입니다.

Kafka 기반 이벤트 스트리밍, Redis 캐시, Rule/AI 기반 이상 거래 탐지, DLQ 복구, WebSocket 실시간 알림까지 통합한 **FDS 아키텍처**입니다.

---

## 프로젝트 개요

사용자 거래에서 이상 징후를 **실시간으로 감지**하고,  
운영자에게 **알림 및 리포트**를 제공하는 **이상 거래 탐지 플랫폼**입니다.

설계의 핵심은 다음과 같습니다.

- **실시간성**: Kafka 이벤트 기반 구조
- **신뢰성**: DLQ 복구 & TTL 기반 재처리
- **확장성**: 도메인 분리, 멀티모듈 설계
- **관측성**: 알림, 로그, 룰 기반 추적 가능

---

## 시스템 요약

| 구성 요소       | 기술 스택            | 설명 |
|----------------|---------------------|------|
| **API 서버**        | Spring Boot 3.x (REST) | 인증, 송금, 룰 등록 등 |
| **비동기 처리**      | Apache Kafka         | 송금 이벤트 스트리밍 |
| **캐시/선차감 처리**  | Redis + Lua Script   | TTL 기반 상태 보존 |
| **트랜잭션 저장**     | PostgreSQL / MySQL   | 정합성 있는 거래 기록 저장 |
| **알림/대시보드**    | WebSocket / Slack    | 실시간 탐지 결과 전달 |
| **복구/보정 처리**   | DLQ Worker + TTL     | 장애 발생 시 재처리 |

---

## 테이블

| 테이블명           | 설명 |
|--------------------|------|
| `users`            | 일반 사용자 정보 |
| `account_balance`  | 계좌 잔액 관리 |
| `admin_users`      | 운영자 정보 및 권한 |
| `transactions`     | 송금 트랜잭션 요청/처리 |
| `tx_history`       | 상태 변경 이력 기록 |
| `correction_log`   | 정정(복구) 기록 |
| `rules`            | 룰 정의 (JSON 기반 조건) |
| `rule_history`     | 룰 버전 관리 |
| `risk_logs`        | 단건 탐지 로그 |
| `risk_rule_hits`   | 어떤 룰이 감지에 영향을 줬는지 |
| `dlq_events`       | 실패 트랜잭션 로그 (DLQ 용도) |

---

## 운영자 권한 (Admin Role)

| Role 이름        | 설명 |
|------------------|------|
| `SUPER_ADMIN`    | 전체 권한 (모든 도메인) |
| `RULE_ADMIN`     | 룰 등록/수정/삭제 |
| `AUDITOR`        | 이력 열람 전용 |
| `OPS_AGENT`      | 실시간 장애 처리 담당 |
| `RISK_ANALYST`   | 탐지 로그 기반 분석 |
| `READ_ONLY`      | 전체 조회만 가능 |

---

## 기술 스택

- Language: **Java 21**
- Framework: **Spring Boot 3.x**
- Messaging: **Apache Kafka**
- DB: **PostgreSQL**
- Cache: **Redis** (TTL + Lua)
- Realtime: **WebSocket** / STOMP
- Infra: **Docker**, Docker Compose, Slack Webhook
- UI: React Admin Dashboard (optional)

---

## 향후 확장 고려

- 지갑 도메인 연동 (OnChain/OffChain)
- Fraud Score 모델 학습 (AI 모델 내장)
- Kafka dead-letter-topic 분리
- ElasticSearch 연동 (로그 + 탐색용)

---

## 설계 원칙

- 도메인 주도 설계 (DDD)
- 멀티모듈 아키텍처 기반
- 도메인별 서비스 분리 (MSA 확장 고려)
- 트랜잭션 기반 흐름 감지
- 이벤트 소싱 기반 처리
