# 어플리케이션 로그

## 엑세스 로그 수집

### 구현 완료 내역

- [x] **Spring Boot Tomcat AccessLog 활성화**
  - `services/transfer/instances/api/src/main/resources/application.yml` — `server.tomcat.accesslog` 블록 추가
    - prefix: `transfer-api-access`, directory: `${LOG_PATH:/var/log/apps}`
  - `services/fds/instances/api/src/main/resources/application.yml` — `server.tomcat.accesslog` 블록 추가
    - prefix: `fds-api-access`, directory: `${LOG_PATH:/var/log/apps}`
  - JSON 한 줄 포맷 (`@timestamp`, `remote_ip`, `method`, `uri`, `status`, `duration_ms` 등)
  - Tomcat 자체 날짜 회전(`file-date-format: .yyyy-MM-dd`)으로 logback과 충돌 없음

- [x] **Filebeat 설정 분리** (`monitoring/filebeat.yml`)
  - Input 1 (app log): `/var/log/apps/*.log` — `exclude_files: ['.*access.*\.log$']` → `app-logs-%{+yyyy.MM.dd}` 인덱스
  - Input 2 (access log): `/var/log/apps/*access*.log` → `access-logs-%{+yyyy.MM.dd}` 인덱스
  - `output.elasticsearch.indices` 조건부 분기 (`log_type` 필드 기반)
  - `setup.template.pattern: "*-logs-*"` 로 양쪽 인덱스 패턴 커버

- [x] **docker-compose 볼륨**: `./logs:/var/log/apps` 이미 적용됨

### 인덱스 패턴

| 로그 종류 | Elasticsearch 인덱스 |
|---|---|
| 앱 로그 (JSON) | `app-logs-yyyy.MM.dd` |
| AccessLog (Tomcat JSON) | `access-logs-yyyy.MM.dd` |

### Kibana 에서 확인하는 방법

1. Kibana 접속 → **Stack Management** → **Data Views**
2. **Create data view** 클릭
3. Index pattern: `access-logs-*` 입력 → Timestamp field: `@timestamp` 선택 → **Save data view**
4. **Discover** 탭에서 `access-logs-*` 선택 후 `method`, `uri`, `status`, `duration_ms` 필드 확인

### 재시작 및 검증 명령

```bash
# 컨테이너 재시작
docker compose restart filebeat transfer-api fds-api

# 인덱스 생성 확인 (수분 내)
curl localhost:9200/_cat/indices?v | grep -E 'app-logs|access-logs'
```

### 옵저빌리티 강화의 고민

- 현재 `traceId`/`spanId` 가 JSON_FILE appender 의 MDC 로 들어감
- AccessLog 에는 traceId 가 빠짐 → 후속 작업으로 Tomcat AccessLog Valve 의 pattern 에 `%{X-B3-TraceId}i` 추가하거나, 커스텀 Filter 로 MDC traceId 를 응답 헤더로 주입 필요
