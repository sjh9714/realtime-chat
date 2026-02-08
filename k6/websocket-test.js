import { check, sleep } from 'k6';
import http from 'k6/http';
import ws from 'k6/ws';
import { Counter, Trend } from 'k6/metrics';

// WebSocket 부하 테스트: 회원가입 → 로그인 → STOMP 연결 → 메시지 전송/수신
// 실행: k6 run k6/websocket-test.js

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const WS_URL = __ENV.WS_URL || 'ws://localhost:8080/ws';

const messagesSent = new Counter('ws_messages_sent');
const messagesReceived = new Counter('ws_messages_received');
const wsLatency = new Trend('ws_message_latency', true);

export const options = {
    scenarios: {
        websocket: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '10s', target: 20 },   // 워밍업
                { duration: '30s', target: 100 },   // 최대 부하
                { duration: '10s', target: 0 },     // 쿨다운
            ],
        },
    },
    thresholds: {
        ws_message_latency: ['p(95)<500'],  // p95 < 500ms
        http_req_failed: ['rate<0.01'],
    },
};

// 유저 생성 + 채팅방 생성
export function setup() {
    const users = [];
    for (let i = 0; i < 100; i++) {
        const email = `wstest-${i}-${Date.now()}@test.com`;
        const payload = JSON.stringify({
            email: email,
            password: 'password123',
            nickname: `WS유저${i}`,
        });

        const res = http.post(`${BASE_URL}/api/auth/signup`, payload, {
            headers: { 'Content-Type': 'application/json' },
        });

        if (res.status === 200 || res.status === 201) {
            const body = JSON.parse(res.body);
            users.push({ token: body.token, userId: body.userId, email });
        }
    }

    // 페어별 1:1 채팅방 생성
    const rooms = [];
    for (let i = 0; i < users.length - 1; i += 2) {
        const res = http.post(
            `${BASE_URL}/api/rooms/direct`,
            JSON.stringify({ targetUserId: users[i + 1].userId }),
            {
                headers: {
                    'Content-Type': 'application/json',
                    Authorization: `Bearer ${users[i].token}`,
                },
            }
        );
        if (res.status === 200 || res.status === 201) {
            const body = JSON.parse(res.body);
            rooms.push({ roomId: body.id, user1: i, user2: i + 1 });
        }
    }

    return { users, rooms };
}

export default function (data) {
    if (data.rooms.length === 0 || data.users.length === 0) return;

    const roomIndex = __VU % data.rooms.length;
    const room = data.rooms[roomIndex];
    const userIndex = __VU % 2 === 0 ? room.user1 : room.user2;
    const user = data.users[userIndex];
    if (!user) return;

    const url = `${WS_URL}`;

    const res = ws.connect(url, {}, function (socket) {
        // STOMP CONNECT
        socket.on('open', function () {
            const connectFrame =
                'CONNECT\n' +
                'accept-version:1.2\n' +
                `Authorization:Bearer ${user.token}\n` +
                '\n\0';
            socket.send(connectFrame);
        });

        socket.on('message', function (msg) {
            // STOMP CONNECTED → 구독 + 메시지 전송
            if (msg.startsWith('CONNECTED')) {
                // 채팅방 구독
                const subscribeFrame =
                    'SUBSCRIBE\n' +
                    `id:sub-${room.roomId}\n` +
                    `destination:/topic/room.${room.roomId}\n` +
                    '\n\0';
                socket.send(subscribeFrame);

                // 메시지 전송 (5개)
                for (let i = 0; i < 5; i++) {
                    const sendTime = Date.now();
                    const msgPayload = JSON.stringify({
                        roomId: room.roomId,
                        content: `부하테스트 메시지 ${i} - ${sendTime}`,
                        type: 'TEXT',
                    });

                    const sendFrame =
                        'SEND\n' +
                        'destination:/app/chat.send\n' +
                        'content-type:application/json\n' +
                        '\n' +
                        msgPayload +
                        '\0';
                    socket.send(sendFrame);
                    messagesSent.add(1);
                    sleep(0.5);
                }

                // 메시지 수신 대기 후 종료
                sleep(2);
                socket.close();
            }

            // MESSAGE 프레임 수신
            if (msg.startsWith('MESSAGE')) {
                messagesReceived.add(1);
            }
        });

        socket.on('error', function (e) {
            console.error('WebSocket error:', e);
        });

        socket.setTimeout(function () {
            socket.close();
        }, 15000);
    });

    check(res, {
        'WebSocket 연결 성공': (r) => r && r.status === 101,
    });
}
