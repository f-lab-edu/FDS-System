/**
 * K6 Load Test - Keep-Alive ON (기본)
 * 
 * 연결 재사용으로 TIME_WAIT 최소화
 */

import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  vus: 20,
  duration: '10s',
};

export default function () {
  const url = 'http://localhost/api/transfers';
  
  const senderIndex = Math.floor(Math.random() * 20) + 1;
  const senderAccount = `110-100-${String(senderIndex).padStart(6, '0')}`;
  
  const receiverIndex = Math.floor(Math.random() * 20) + 21;
  const receiverAccount = `110-200-${String(receiverIndex).padStart(6, '0')}`;
  
  const payload = JSON.stringify({
    senderAccountNumber: senderAccount,
    receiverAccountNumber: receiverAccount,
    amount: "10000",
    message: "Load test transfer",
    currency: "KRW"
  });
  
  const params = {
    headers: {
      'Content-Type': 'application/json',
      // Connection 헤더 없음 = Keep-Alive 기본 사용
    },
  };
  
  const response = http.post(url, payload, params);
  
  check(response, {
    'status is 200': (r) => r.status === 200,
  });
  
  // sleep 제거 - 최대한 빠르게 요청
}
