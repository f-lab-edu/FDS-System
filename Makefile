.PHONY: help build up down restart logs health test test-simple clean

# 기본 타겟
help:
	@echo "Available commands:"
	@echo "  make build         - Gradle 빌드"
	@echo "  make up            - 전체 환경 시작"
	@echo "  make down          - 전체 환경 중지"
	@echo "  make restart       - 전체 환경 재시작"
	@echo "  make logs          - 로그 확인"
	@echo "  make health        - 헬스체크"
	@echo "  make test          - 부하 테스트 (10분)"
	@echo "  make test-simple   - 간단 테스트 (30초)"
	@echo "  make clean         - 컨테이너 + 볼륨 삭제"
	@echo ""
	@echo "Example:"
	@echo "  make build && make up && make health"

# Gradle 빌드
build:
	@echo "Building with Gradle..."
	./gradlew clean build -x test

# 환경 시작 (순서 보장)
up:
	@echo "Starting production simulation environment..."
	@echo "Step 1: Starting infrastructure (postgres, kafka, schema-registry)..."
	docker-compose -f docker-compose.prod-simulation.yml up -d postgres kafka schema-registry
	@echo "Waiting for infrastructure to be ready..."
	sleep 20
	@echo "Step 2: Starting Transfer APIs..."
	docker-compose -f docker-compose.prod-simulation.yml up -d transfer-api-1 transfer-api-2
	@echo "Waiting for Transfer APIs to be healthy..."
	sleep 30
	@echo "Step 3: Starting FDS APIs and remaining services..."
	docker-compose -f docker-compose.prod-simulation.yml up -d --build
	@echo "Done! Run 'make health' to check status"

# 환경 중지
down:
	@echo "Stopping all services..."
	docker-compose -f docker-compose.prod-simulation.yml down

# 재시작
restart:
	@echo "Restarting all services..."
	docker-compose -f docker-compose.prod-simulation.yml restart

# 로그 확인
logs:
	docker-compose -f docker-compose.prod-simulation.yml logs -f

# 특정 서비스 로그
logs-api:
	docker-compose -f docker-compose.prod-simulation.yml logs -f transfer-api-1 transfer-api-2

logs-relay:
	docker-compose -f docker-compose.prod-simulation.yml logs -f transfer-relay-0 transfer-relay-1 transfer-relay-2

logs-fds:
	docker-compose -f docker-compose.prod-simulation.yml logs -f fds-api-1 fds-api-2

logs-nginx:
	docker-compose -f docker-compose.prod-simulation.yml logs -f nginx

# 헬스체크
health:
	@chmod +x monitoring/prod-health-check.sh
	@./monitoring/prod-health-check.sh

# 부하 테스트
test:
	@echo "Running load test (10 minutes)..."
	cd load-test && k6 run load-test.js

test-simple:
	@echo "Running simple test (30 seconds)..."
	cd load-test && k6 run simple-test.js

# 정리
clean:
	@echo "Removing all containers and volumes..."
	docker-compose -f docker-compose.prod-simulation.yml down -v

clean-all:
	@echo "Removing all containers, volumes, and images..."
	docker-compose -f docker-compose.prod-simulation.yml down -v --rmi all

# 개발 환경 (기존 docker-compose.yml)
dev-up:
	@echo "Starting development environment..."
	docker-compose up -d

dev-down:
	@echo "Stopping development environment..."
	docker-compose down

# 컨테이너 상태 확인
ps:
	docker-compose -f docker-compose.prod-simulation.yml ps

# 리소스 사용량
stats:
	docker stats --no-stream
