/**
 * K6 Load Test - Keep-Alive OFF
 * 
 * Connection: close 헤더로 매 요청마다 새 TCP 연결 생성
 * TIME_WAIT 증가 패턴 확인용
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
      'Connection': 'close',  // 매 요청마다 연결 종료 강제
    },
  };
  
  const response = http.post(url, payload, params);
  
  check(response, {
    'status is 200': (r) => r.status === 200,
  });
  
  // sleep 제거 - 최대한 빠르게 요청해서 TIME_WAIT 누적
}
