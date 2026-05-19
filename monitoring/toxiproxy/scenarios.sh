#!/usr/bin/env bash
# ============================================
# E3 (Redis Circuit Breaker) chaos 시나리오 실행 헬퍼
# ============================================
# 전제: docker compose -f docker-compose.yml -f docker-compose.chaos.yml up -d
# toxiproxy admin API: http://localhost:8474
#
# 사용:
#   ./monitoring/toxiproxy/scenarios.sh c2        # Redis +500ms latency
#   ./monitoring/toxiproxy/scenarios.sh c3        # Redis +2s latency (CB OPEN 유도)
#   ./monitoring/toxiproxy/scenarios.sh c4        # Redis 연결 cut
#   ./monitoring/toxiproxy/scenarios.sh recover   # 모든 toxic 제거
#   ./monitoring/toxiproxy/scenarios.sh status    # 현재 toxic 상태

set -euo pipefail
ADMIN="${TOXIPROXY_ADMIN:-http://localhost:8474}"
PROXY="${PROXY:-redis}"

cmd="${1:-status}"

cleanup() {
  echo "[chaos] 모든 toxic 제거 (proxy=$PROXY)"
  curl -s -X GET "$ADMIN/proxies/$PROXY/toxics" \
    | python3 -c 'import sys, json; [print(t["name"]) for t in json.load(sys.stdin)]' \
    | while read -r name; do
        [[ -z "$name" ]] && continue
        curl -s -X DELETE "$ADMIN/proxies/$PROXY/toxics/$name" >/dev/null
        echo "  - removed $name"
      done
}

case "$cmd" in
  c2)
    echo "[chaos C2] Redis +500ms latency 주입 (CB 발동 임계 근처)"
    cleanup
    curl -s -X POST "$ADMIN/proxies/$PROXY/toxics" \
      -H "Content-Type: application/json" \
      -d '{"name":"latency_500ms","type":"latency","attributes":{"latency":500,"jitter":50}}'
    ;;

  c3)
    echo "[chaos C3] Redis +2000ms latency 주입 (CB OPEN 기대)"
    cleanup
    curl -s -X POST "$ADMIN/proxies/$PROXY/toxics" \
      -H "Content-Type: application/json" \
      -d '{"name":"latency_2s","type":"latency","attributes":{"latency":2000,"jitter":100}}'
    ;;

  c4)
    echo "[chaos C4] Redis 연결 cut (proxy disabled)"
    curl -s -X POST "$ADMIN/proxies/$PROXY" \
      -H "Content-Type: application/json" \
      -d '{"enabled":false}'
    ;;

  recover)
    echo "[chaos] 정상 복원 (proxy enabled + toxic 제거)"
    curl -s -X POST "$ADMIN/proxies/$PROXY" \
      -H "Content-Type: application/json" \
      -d '{"enabled":true}' >/dev/null
    cleanup
    ;;

  status)
    echo "[chaos] proxy=$PROXY 상태:"
    curl -s -X GET "$ADMIN/proxies/$PROXY" | python3 -m json.tool
    echo "[chaos] toxics:"
    curl -s -X GET "$ADMIN/proxies/$PROXY/toxics" | python3 -m json.tool
    ;;

  *)
    echo "Usage: $0 {c2|c3|c4|recover|status}"
    echo "  PROXY 환경변수로 대상 proxy 지정 (default: redis, 대안: postgres)"
    exit 1
    ;;
esac
