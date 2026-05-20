---
name: quick-review
description: 빠른 코드 리뷰 (domain-guardian + pr-reviewer)
---

# Quick Review Skill

간단한 기능 구현 후 빠른 검증용.

## 호출 순서

1. **domain-guardian** - 도메인 무결성
2. **pr-reviewer** - 코드 품질

## 사용 시점
- 단순 CRUD
- VO 추가
- 작은 기능 변경

## 제외 상황
- 이벤트 발행 있음 → `full-architecture-review` 사용
- Redis/Kafka 연동 → `full-architecture-review` 사용
- 동시성 우려 → `consistency-enforcer` 추가
