#!/usr/bin/env bash
# ============================================
# Elasticsearch ILM 정책 + 인덱스 템플릿 초기화
# ============================================
# 사용:
#   docker compose up -d elasticsearch
#   ES_URL=http://localhost:9200 ./monitoring/elasticsearch/init.sh
#
# 또는 docker-compose 의 init container 로 자동화.

set -euo pipefail
ES_URL="${ES_URL:-http://localhost:9200}"
DIR="$(cd "$(dirname "$0")" && pwd)"

echo "[ilm] ES 헬스 체크 ($ES_URL)"
until curl -s "$ES_URL/_cluster/health" | grep -qE 'green|yellow'; do
  echo "  waiting..."
  sleep 3
done

apply_policy() {
  local name="$1" body_file="$2"
  echo "[ilm] policy: $name"
  curl -s -X PUT "$ES_URL/_ilm/policy/$name" \
    -H "Content-Type: application/json" \
    -d "@$body_file" | python3 -m json.tool || true
}

apply_template() {
  local name="$1" body_file="$2"
  echo "[ilm] index template: $name"
  curl -s -X PUT "$ES_URL/_index_template/$name" \
    -H "Content-Type: application/json" \
    -d "@$body_file" | python3 -m json.tool || true
}

# JSON 파일을 분해해 임시 파일로 만든 뒤 각각 PUT.
python3 - <<PYEOF
import json, os
DIR = os.environ.get('DIR', '$DIR')
policies  = json.load(open(f'{DIR}/ilm-policies.json'))
templates = json.load(open(f'{DIR}/index-templates.json'))
for name, body in policies.items():
    open(f'/tmp/{name}.json', 'w').write(json.dumps(body))
for name, body in templates.items():
    open(f'/tmp/{name}.json', 'w').write(json.dumps(body))
PYEOF

apply_policy   "app_logs_policy"     /tmp/app_logs_policy.json
apply_policy   "access_logs_policy"  /tmp/access_logs_policy.json
apply_template "app_logs_template"   /tmp/app_logs_template.json
apply_template "access_logs_template" /tmp/access_logs_template.json

echo "[ilm] 완료. 다음 인덱스 생성 시점부터 ILM 정책이 적용된다."
