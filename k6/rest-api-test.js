import http from 'k6/http';
import { check } from 'k6';

// REST API 부하 테스트: 채팅방 목록 조회 중심 (N+1 최적화 효과 측정)
// 실행: k6 run --env BASE_URL=http://localhost:8081 k6/rest-api-test.js

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const NUM_USERS = 200;
const NUM_ROOMS = 10;

export const options = {
    scenarios: {
        rest_api: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '10s', target: 50 },   // 워밍업
                { duration: '30s', target: 200 },   // 최대 부하
                { duration: '10s', target: 0 },     // 쿨다운
            ],
        },
    },
    thresholds: {
        http_req_duration: ['p(95)<500'],  // p95 < 500ms
        http_req_failed: ['rate<0.01'],    // 에러율 < 1%
    },
};

export function setup() {
    const users = [];

    // 1. 유저 200명 생성
    for (let i = 0; i < NUM_USERS; i++) {
        const email = `loadtest-${i}-${Date.now()}@test.com`;
        const payload = JSON.stringify({
            email: email,
            password: 'password123',
            nickname: `부하테스트유저${i}`,
        });

        const res = http.post(`${BASE_URL}/api/auth/signup`, payload, {
            headers: { 'Content-Type': 'application/json' },
        });

        if (res.status === 200 || res.status === 201) {
            const body = JSON.parse(res.body);
            users.push({ token: body.token, userId: body.userId });
        }
    }

    console.log(`유저 ${users.length}명 생성 완료`);

    // 2. 유저 0이 그룹 채팅방 10개 생성
    const roomIds = [];
    const headers0 = {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${users[0].token}`,
    };

    for (let i = 0; i < NUM_ROOMS; i++) {
        const roomRes = http.post(
            `${BASE_URL}/api/rooms/group`,
            JSON.stringify({ name: `부하테스트방${i}` }),
            { headers: headers0 }
        );
        if (roomRes.status === 200 || roomRes.status === 201) {
            const roomBody = JSON.parse(roomRes.body);
            roomIds.push(roomBody.id);
        }
    }

    console.log(`채팅방 ${roomIds.length}개 생성 완료`);

    // 3. 나머지 199명이 10개 방 모두에 참여
    let joinCount = 0;
    for (let u = 1; u < users.length; u++) {
        const userHeaders = {
            'Content-Type': 'application/json',
            Authorization: `Bearer ${users[u].token}`,
        };
        for (let r = 0; r < roomIds.length; r++) {
            const joinRes = http.post(
                `${BASE_URL}/api/rooms/${roomIds[r]}/join`,
                null,
                { headers: userHeaders }
            );
            if (joinRes.status === 200) {
                joinCount++;
            }
        }
    }

    console.log(`방 참여 ${joinCount}건 완료 (모든 유저가 ${roomIds.length}개 방의 멤버)`);

    return { users, roomIds };
}

export default function (data) {
    const userIndex = __VU % data.users.length;
    const user = data.users[userIndex];
    if (!user) return;

    const headers = {
        Authorization: `Bearer ${user.token}`,
        'Content-Type': 'application/json',
    };

    // 1. 채팅방 목록 조회 (핵심 — N+1 vs JPQL 프로젝션 차이)
    const listRes = http.get(`${BASE_URL}/api/rooms`, { headers });
    check(listRes, {
        '채팅방 목록 조회 성공': (r) => r.status === 200,
    });

    // 2. 채팅방 상세 조회 (랜덤 방 — 모든 유저가 멤버이므로 403 없음)
    const roomId = data.roomIds[Math.floor(Math.random() * data.roomIds.length)];
    const detailRes = http.get(`${BASE_URL}/api/rooms/${roomId}`, { headers });
    check(detailRes, {
        '채팅방 상세 조회 성공': (r) => r.status === 200,
    });

    // 3. 메시지 이력 조회
    const msgRes = http.get(
        `${BASE_URL}/api/rooms/${roomId}/messages?size=20`,
        { headers }
    );
    check(msgRes, {
        '메시지 이력 조회 성공': (r) => r.status === 200,
    });
}
