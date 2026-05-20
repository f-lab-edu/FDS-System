---
name: full-architecture-review
description: 전체 아키텍처 리뷰 (모든 Agent 순차 호출)
---

# Full Architecture Review Skill

대규모 변경, PR 전, 또는 설계 검증 시 사용.

## 호출 순서

### Phase 1: 구조 검증
1. **domain-guardian** - 도메인 무결성
2. **consistency-enforcer** - 정합성/동시성

### Phase 2: 확장성 검증
3. **failure-simulator** - 장애 시나리오
4. **scale-economist** - 비용/확장성

### Phase 3: 전략 검증
5. **strategic-cto** - 장기 방향
6. **ego-destroyer** - 맹점 파괴

## 사용 시점
- 새로운 Aggregate 추가
- 이벤트 기반 구조 도입
- Redis/Kafka 연동
- 대규모 리팩토링
- PR 전 최종 검증

## 제외 상황
- 단순 버그 수정 → `quick-review`
- 테스트 추가 → Agent 불필요
- 문서 수정 → Agent 불필요

## 결과 통합
각 Agent 결과를 종합하여:
1. **필수 수정** (배포 차단)
2. **권장 수정** (다음 스프린트)
3. **장기 개선** (백로그)

로 분류하여 정리.
