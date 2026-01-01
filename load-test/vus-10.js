/**
 * K6 Load Test - VU 10명 (30초)
 * 
 * 실행: k6 run vus-10.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  vus: 10,
  duration: '30s',
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
    message: "Load test - VU 10",
    currency: "KRW"
  });
  
  const params = {
    headers: {
      'Content-Type': 'application/json',
    },
  };
  
  const response = http.post(url, payload, params);
  
  check(response, {
    'status is 200': (r) => r.status === 200,
    'response < 500ms': (r) => r.timings.duration < 500,
  });
  
  sleep(1);
}
