/**
 * K6 Bidirectional Transfer Test - 양방향 송금 테스트
 *
 * 목적: 데드락 방지 로직 검증
 * - A → B 송금과 B → A 송금이 동시에 발생하는 상황
 * - 정렬된 락 획득으로 데드락 없이 처리되어야 함
 *
 * 실행: k6 run bidirectional-test.js
 */

import http from 'k6/http';
import {check, sleep} from 'k6';
import {Counter, Rate} from 'k6/metrics';

const successRate = new Rate('success_rate');
const deadlockErrors = new Counter('deadlock_errors');

export const options = {
    vus: 50,
    duration: '2m',
    thresholds: {
        // 99% 이상 성공
        'success_rate': ['rate>0.99'],
        // 데드락 0건
        'deadlock_errors': ['count<1'],
    },
};

// 테스트용 계좌 쌍 (양방향 송금 가능한 계좌)
// 송금자 계좌를 수신자로도 사용
const accounts = [];
for (let i = 1; i <= 20; i++) {
    accounts.push(`110-100-${String(i).padStart(6, '0')}`);
}
for (let i = 21; i <= 40; i++) {
    accounts.push(`110-200-${String(i).padStart(6, '0')}`);
}

export default function () {
    const url = 'http://localhost/api/transfers';

    // 랜덤하게 두 계좌 선택 (양방향 송금 시뮬레이션)
    const idx1 = Math.floor(Math.random() * accounts.length);
    let idx2 = Math.floor(Math.random() * accounts.length);
    while (idx2 === idx1) {
        idx2 = Math.floor(Math.random() * accounts.length);
    }

    const senderAccount = accounts[idx1];
    const receiverAccount = accounts[idx2];

    const payload = JSON.stringify({
        senderAccountNumber: senderAccount,
        receiverAccountNumber: receiverAccount,
        // 소액으로 잔액 부족 방지
        amount: "1000",
        message: "Bidirectional test",
        currency: "KRW"
    });

    const params = {
        headers: {'Content-Type': 'application/json'},
        // 데드락 감지를 위해 타임아웃은 30초로 setting
        timeout: '30s',
    };

    const response = http.post(url, payload, params);

    const success = check(response, {
        'status is 200': (r) => r.status === 200,
    });

    successRate.add(success);

    if (!success) {
        const body = response.body || '';
        // 데드락 관련 에러 체크
        if (body.includes('deadlock') || body.includes('timeout') || body.includes('lock')) {
            deadlockErrors.add(1);
            console.log(`Potential deadlock: ${response.status} - ${body}`);
        } else {
            console.log(`Error: ${response.status} - ${body}`);
        }
    }

    sleep(0.1);
}
