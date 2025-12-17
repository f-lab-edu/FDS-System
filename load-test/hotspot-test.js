/**
 * K6 Hotspot Test - 단일 계좌 집중 테스트
 * 
 * 목적: 핫스팟 계좌에 대한 동시성 처리 검증
 * - 모든 요청이 동일한 송금자 계좌에서 출금
 * - 패시미스틱 락의 대기 시간 관찰
 * 
 * 실행: k6 run hotspot-test.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const successRate = new Rate('success_rate');
const lockWaitTime = new Trend('lock_wait_time');

export const options = {
  stages: [
    { duration: '30s', target: 20 },   // 웜업
    { duration: '1m', target: 50 },    // 50 VU - 단일 계좌 집중
    { duration: '1m', target: 100 },   // 100 VU - 더 높은 경합
    { duration: '30s', target: 0 },    // 쿨다운
  ],
  thresholds: {
    'success_rate': ['rate>0.95'],
    'http_req_duration': ['p(95)<2000'],  // 락 대기로 인해 응답 시간 증가 허용
  },
};

// 핫스팟 계좌 (모든 VU가 이 계좌에서 송금)
const HOTSPOT_SENDER = '110-100-000001';

export default function () {
  const url = 'http://localhost/api/transfers';
  
  // 핫스팟: 동일한 송금자
  const senderAccount = HOTSPOT_SENDER;
  
  // 수신자만 랜덤
  const receiverIndex = Math.floor(Math.random() * 20) + 21;
  const receiverAccount = `110-200-${String(receiverIndex).padStart(6, '0')}`;
  
  const payload = JSON.stringify({
    senderAccountNumber: senderAccount,
    receiverAccountNumber: receiverAccount,
    amount: "100",  // 소액으로 잔액 부족 방지
    message: "Hotspot test",
    currency: "KRW"
  });
  
  const params = {
    headers: { 'Content-Type': 'application/json' },
    timeout: '10s',
  };
  
  const startTime = Date.now();
  const response = http.post(url, payload, params);
  const endTime = Date.now();
  
  const success = check(response, {
    'status is 200': (r) => r.status === 200,
  });
  
  successRate.add(success);
  lockWaitTime.add(endTime - startTime);
  
  if (!success) {
    console.log(`Error: ${response.status} - ${response.body}`);
  }
  
  // sleep 없음 - 최대 경합 시뮬레이션
  sleep(0.05);
}
