/**
 * K6 Load Test Script - Transfer API (본격 부하 테스트)
 * 
 * 목표: 점진적 부하 증가로 임계점 파악
 * - Warm-up: 10 VUs (30초)
 * - Stage 1: 25 VUs (2분)
 * - Stage 2: 50 VUs (2분)
 * - Stage 3: 75 VUs (2분)
 * - Stage 4: 100 VUs (2분)
 * - Cool-down: 0 VUs (30초)
 * 
 * 실행 방법:
 * k6 run load-test.js
 * 
 * 결과 출력:
 * k6 run --out json=results.json load-test.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';

// 커스텀 메트릭
const errorRate = new Rate('errors');
const transferDuration = new Trend('transfer_duration');
const successfulTransfers = new Counter('successful_transfers');
const failedTransfers = new Counter('failed_transfers');

export const options = {
  stages: [
    // Warm-up
    { duration: '30s', target: 10 },
    
    // Stage 1: 25 VUs (약 25 TPS)
    { duration: '2m', target: 25 },
    
    // Stage 2: 50 VUs (약 50 TPS)
    { duration: '2m', target: 50 },
    
    // Stage 3: 75 VUs (약 75 TPS)
    { duration: '2m', target: 75 },
    
    // Stage 4: 100 VUs (약 100 TPS)
    { duration: '2m', target: 100 },
    
    // Cool-down
    { duration: '30s', target: 0 },
  ],
  
  thresholds: {
    'http_req_duration': ['p(95)<500', 'p(99)<1000'], // 95% < 500ms, 99% < 1s
    'errors': ['rate<0.10'],  // 에러율 10% 미만 (낙관적 락 고려)
  },
};

export default function () {
  const url = 'http://localhost/api/transfers';
  
  // 송금자 랜덤 선택 (10001-10020)
  const senderIndex = Math.floor(Math.random() * 20) + 1;
  const senderAccount = `110-100-${String(senderIndex).padStart(6, '0')}`;
  
  // 수신자 랜덤 선택 (10021-10040)
  const receiverIndex = Math.floor(Math.random() * 20) + 21;
  const receiverAccount = `110-200-${String(receiverIndex).padStart(6, '0')}`;
  
  const payload = JSON.stringify({
    senderAccountNumber: senderAccount,
    receiverAccountNumber: receiverAccount,
    amount: "10000",
    message: "Load test - Progressive stages",
    currency: "KRW"
  });
  
  const params = {
    headers: {
      'Content-Type': 'application/json',
    },
    timeout: '10s',
  };
  
  const startTime = new Date();
  const response = http.post(url, payload, params);
  const duration = new Date() - startTime;
  
  // 메트릭 기록
  transferDuration.add(duration);
  
  // 응답 검증
  const checks = check(response, {
    'status is 200': (r) => r.status === 200,
    'response time < 1s': (r) => r.timings.duration < 1000,
    'response time < 500ms': (r) => r.timings.duration < 500,
    'response time < 200ms': (r) => r.timings.duration < 200,
  });
  
  if (response.status === 200) {
    successfulTransfers.add(1);
  } else {
    failedTransfers.add(1);
    errorRate.add(1);
    
    // 에러 로그 (처음 10개만)
    if (failedTransfers.count <= 10) {
      console.error(`Failed: ${response.status} - ${response.body.substring(0, 100)}`);
    }
  }
  
  // Think time
  sleep(1);
}

export function handleSummary(data) {
  const summary = generateSummary(data);
  
  return {
    'summary.json': JSON.stringify(data, null, 2),
    'stdout': summary,
  };
}

function generateSummary(data) {
  const totalRequests = data.metrics.http_reqs.values.count;
  const successRate = (1 - (data.metrics.errors?.values.rate || 0)) * 100;
  const errorRate = (data.metrics.errors?.values.rate || 0) * 100;
  
  return `
===============================================
Load Test Summary (Progressive Stages)
===============================================

Total Requests: ${totalRequests}
Request Rate: ${data.metrics.http_reqs.values.rate.toFixed(2)} req/s

Response Times:
  Min:  ${data.metrics.http_req_duration.values.min.toFixed(2)}ms
  Avg:  ${data.metrics.http_req_duration.values.avg.toFixed(2)}ms
  Med:  ${data.metrics.http_req_duration.values.med.toFixed(2)}ms
  Max:  ${data.metrics.http_req_duration.values.max.toFixed(2)}ms
  P90:  ${data.metrics.http_req_duration.values['p(90)'].toFixed(2)}ms
  P95:  ${data.metrics.http_req_duration.values['p(95)'].toFixed(2)}ms
  P99:  ${data.metrics.http_req_duration.values['p(99)'].toFixed(2)}ms

Success Rate: ${successRate.toFixed(2)}%
Error Rate: ${errorRate.toFixed(2)}%

Successful Transfers: ${data.metrics.successful_transfers?.values.count || 0}
Failed Transfers: ${data.metrics.failed_transfers?.values.count || 0}

Thresholds:
  P95 < 500ms: ${data.metrics.http_req_duration.values['p(95)'] < 500 ? 'PASS ✓' : 'FAIL ✗'}
  P99 < 1000ms: ${data.metrics.http_req_duration.values['p(99)'] < 1000 ? 'PASS ✓' : 'FAIL ✗'}
  Error < 10%: ${errorRate < 10 ? 'PASS ✓' : 'FAIL ✗'}

===============================================
  `;
}
