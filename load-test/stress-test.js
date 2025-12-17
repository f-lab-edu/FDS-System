/**
 * K6 Stress Test - 실제 임계점 측정용
 * 
 * 목적: sleep 최소화하여 시스템이 감당할 수 있는 최대 TPS 측정
 * 
 * 실행: k6 run stress-test.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// Custom metrics
const successRate = new Rate('success_rate');
const transferDuration = new Trend('transfer_duration');
const errorCounter = new Counter('errors');

export const options = {
  stages: [
    { duration: '30s', target: 50 },   // 웜업
    { duration: '1m', target: 100 },   // 100 VU 유지
    { duration: '1m', target: 150 },   // 150 VU 도전
    { duration: '1m', target: 200 },   // 200 VU 도전
    { duration: '30s', target: 0 },    // 쿨다운
  ],
  thresholds: {
    'http_req_duration': ['p(95)<500'],  // P95 500ms 이하
    'success_rate': ['rate>0.95'],       // 95% 이상 성공
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
    message: "Stress test",
    currency: "KRW"
  });
  
  const params = {
    headers: { 'Content-Type': 'application/json' },
    timeout: '10s',
  };
  
  const response = http.post(url, payload, params);
  
  const success = check(response, {
    'status is 200': (r) => r.status === 200,
    'response < 500ms': (r) => r.timings.duration < 500,
  });
  
  successRate.add(success);
  transferDuration.add(response.timings.duration);
  
  if (!success) {
    errorCounter.add(1);
    if (response.status !== 200) {
      console.log(`Error: ${response.status} - ${response.body}`);
    }
  }
  
  // sleep 최소화 (100ms) - 실제 TPS 측정
  sleep(0.1);
}
