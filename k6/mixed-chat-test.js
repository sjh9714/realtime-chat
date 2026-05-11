import { check, sleep } from 'k6';
import http from 'k6/http';
import ws from 'k6/ws';
import { Counter, Rate, Trend } from 'k6/metrics';

// Mixed chat scenario: REST 조회 + WebSocket 전송/수신 + 읽음 처리
// 실행:
// k6 run --env BASE_URL=http://localhost:8081 --env WS_URL=ws://localhost:8081/ws k6/mixed-chat-test.js
// 기존 토큰/방을 쓰려면:
// k6 run --env AUTH_TOKEN=... --env ROOM_ID=1 k6/mixed-chat-test.js

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const WS_URL = __ENV.WS_URL || 'ws://localhost:8080/ws';
const AUTH_TOKEN = __ENV.AUTH_TOKEN;
const ROOM_ID = __ENV.ROOM_ID ? Number(__ENV.ROOM_ID) : null;
const NUM_USERS = Number(__ENV.NUM_USERS || 20);

const messagesSent = new Counter('messages_sent');
const messagesReceived = new Counter('messages_received');
const acksReceived = new Counter('acks_received');
const nacksReceived = new Counter('nacks_received');
const errors = new Counter('errors');
const mixedErrorRate = new Rate('mixed_error_rate');
const messageSendAckLatency = new Trend('message_send_ack_latency', true);
const sendToReceiveLatency = new Trend('send_to_receive_latency', true);
const readReceiptApiLatency = new Trend('read_receipt_api_latency', true);

export const options = {
    scenarios: {
        mixed_chat: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '10s', target: 10 },
                { duration: '30s', target: 50 },
                { duration: '10s', target: 0 },
            ],
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.01'],
        http_req_duration: ['p(95)<500'],
        mixed_error_rate: ['rate<0.05'],
        message_send_ack_latency: ['p(95)<1000'],
        read_receipt_api_latency: ['p(95)<500'],
    },
};

export function setup() {
    if (AUTH_TOKEN && ROOM_ID) {
        return {
            users: [{ token: AUTH_TOKEN, userId: null }],
            roomId: ROOM_ID,
        };
    }

    const users = [];
    const runId = `${Date.now()}-${Math.floor(Math.random() * 100000)}`;

    for (let i = 0; i < NUM_USERS; i++) {
        const res = http.post(
            `${BASE_URL}/api/auth/signup`,
            JSON.stringify({
                email: `mixed-${runId}-${i}@test.com`,
                password: 'password123',
                nickname: `MixedUser${i}`,
            }),
            jsonHeaders()
        );

        if (res.status === 200 || res.status === 201) {
            const body = safeJson(res);
            users.push({ token: body.token, userId: body.userId });
        }
    }

    if (users.length < 2) {
        throw new Error(`Need at least 2 users for mixed scenario, created=${users.length}`);
    }

    const memberIds = users.slice(1).map((user) => user.userId);
    const roomRes = http.post(
        `${BASE_URL}/api/rooms/group`,
        JSON.stringify({
            name: `mixed-room-${runId}`,
            memberIds,
        }),
        authHeaders(users[0].token)
    );

    check(roomRes, {
        'mixed setup room created': (r) => r.status === 200 || r.status === 201,
    });

    if (roomRes.status !== 200 && roomRes.status !== 201) {
        throw new Error(`Failed to create room: status=${roomRes.status}, body=${roomRes.body}`);
    }

    return {
        users,
        roomId: safeJson(roomRes).id,
    };
}

export default function (data) {
    const user = data.users[(__VU - 1) % data.users.length];
    const roomId = data.roomId;
    const headers = authHeaders(user.token).headers;

    const roomsRes = http.get(`${BASE_URL}/api/rooms`, { headers });
    recordHttpCheck(roomsRes, 'room list ok', (r) => r.status === 200);

    const historyRes = http.get(`${BASE_URL}/api/rooms/${roomId}/messages?size=20`, { headers });
    recordHttpCheck(historyRes, 'message history ok', (r) => r.status === 200);

    const history = historyRes.status === 200 ? safeJson(historyRes) : {};
    const latestMessageId = latestMessageIdFrom(history);

    const wsResult = runWebSocketFlow(user.token, roomId);
    check(wsResult, {
        'websocket upgraded': (r) => r && r.status === 101,
    });

    if (latestMessageId > 0) {
        const readPayload = JSON.stringify({ lastReadMessageId: latestMessageId });
        const readRes = http.post(`${BASE_URL}/api/rooms/${roomId}/read`, readPayload, { headers });
        readReceiptApiLatency.add(readRes.timings.duration);
        recordHttpCheck(readRes, 'read receipt ok', (r) => r.status === 200);
    }

    sleep(1);
}

function runWebSocketFlow(token, roomId) {
    const clientMessageId = uuidv4();
    const marker = `mixed-${__VU}-${__ITER}-${Date.now()}-${Math.floor(Math.random() * 100000)}`;
    const state = {
        sent: false,
        sendStartedAt: 0,
        acked: false,
        receivedOwnMessage: false,
    };

    return ws.connect(WS_URL, {}, function (socket) {
        socket.on('open', function () {
            socket.send(stompFrame('CONNECT', {
                'accept-version': '1.2',
                Authorization: `Bearer ${token}`,
            }));
        });

        socket.on('message', function (message) {
            for (const rawFrame of splitFrames(message)) {
                handleFrame(socket, rawFrame, roomId, clientMessageId, marker, state);
            }
        });

        socket.on('error', function (error) {
            errors.add(1);
            mixedErrorRate.add(true);
            console.error(`WebSocket error: ${error && error.error ? error.error() : error}`);
        });

        socket.setTimeout(function () {
            if (state.sent && !state.acked) {
                errors.add(1);
                mixedErrorRate.add(true);
                console.error(`ACK timeout: roomId=${roomId}, clientMessageId=${clientMessageId}`);
            }
            socket.close();
        }, 8000);
    });
}

function handleFrame(socket, rawFrame, roomId, clientMessageId, marker, state) {
    const frame = parseFrame(rawFrame);
    if (!frame) return;

    if (frame.command === 'CONNECTED') {
        socket.send(stompFrame('SUBSCRIBE', {
            id: `room-${roomId}-${__VU}-${__ITER}`,
            destination: `/topic/room.${roomId}`,
        }));
        socket.send(stompFrame('SUBSCRIBE', {
            id: `ack-${__VU}-${__ITER}`,
            destination: '/user/queue/messages/ack',
        }));
        socket.send(stompFrame('SUBSCRIBE', {
            id: `error-${__VU}-${__ITER}`,
            destination: '/user/queue/messages/error',
        }));

        socket.setTimeout(function () {
            const payload = JSON.stringify({
                clientMessageId,
                roomId,
                content: marker,
                type: 'TEXT',
            });
            state.sent = true;
            state.sendStartedAt = Date.now();
            messagesSent.add(1);
            socket.send(stompFrame('SEND', {
                destination: '/app/chat.send',
                'content-type': 'application/json',
            }, payload));
        }, 200);

        return;
    }

    if (frame.command === 'MESSAGE') {
        messagesReceived.add(1);
        const body = safeParse(frame.body);

        if (body && body.clientMessageId === clientMessageId && body.status === 'ACCEPTED') {
            state.acked = true;
            acksReceived.add(1);
            messageSendAckLatency.add(Date.now() - state.sendStartedAt);
            mixedErrorRate.add(false);
        }

        if (body && body.clientMessageId === clientMessageId && body.status === 'FAILED') {
            nacksReceived.add(1);
            errors.add(1);
            mixedErrorRate.add(true);
            console.error(`Kafka publish NACK: roomId=${roomId}, reason=${body.reason}`);
        }

        if (body && body.content === marker) {
            state.receivedOwnMessage = true;
            sendToReceiveLatency.add(Date.now() - state.sendStartedAt);
        }

        if (state.acked && state.receivedOwnMessage) {
            socket.close();
        }
    }
}

function recordHttpCheck(response, name, predicate) {
    const ok = check(response, { [name]: predicate });
    mixedErrorRate.add(!ok);
    if (!ok) {
        errors.add(1);
    }
}

function latestMessageIdFrom(history) {
    if (!history || !history.messages || history.messages.length === 0) {
        return 0;
    }

    return history.messages.reduce((max, message) => {
        return message.id && message.id > max ? message.id : max;
    }, 0);
}

function authHeaders(token) {
    return {
        headers: {
            Authorization: `Bearer ${token}`,
            'Content-Type': 'application/json',
        },
    };
}

function jsonHeaders() {
    return {
        headers: {
            'Content-Type': 'application/json',
        },
    };
}

function stompFrame(command, headers, body = '') {
    const lines = [command];
    for (const key in headers) {
        lines.push(`${key}:${headers[key]}`);
    }
    return `${lines.join('\n')}\n\n${body}\0`;
}

function splitFrames(message) {
    return String(message)
        .split('\0')
        .map((frame) => frame.trim())
        .filter((frame) => frame.length > 0);
}

function parseFrame(rawFrame) {
    const separator = rawFrame.indexOf('\n\n');
    const headerPart = separator >= 0 ? rawFrame.slice(0, separator) : rawFrame;
    const body = separator >= 0 ? rawFrame.slice(separator + 2) : '';
    const lines = headerPart.split('\n');

    return {
        command: lines[0],
        body,
    };
}

function safeJson(response) {
    try {
        return JSON.parse(response.body || '{}');
    } catch (error) {
        errors.add(1);
        mixedErrorRate.add(true);
        return {};
    }
}

function safeParse(body) {
    try {
        return JSON.parse(body || '{}');
    } catch (error) {
        return null;
    }
}

function uuidv4() {
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (char) {
        const random = Math.floor(Math.random() * 16);
        const value = char === 'x' ? random : (random & 0x3) | 0x8;
        return value.toString(16);
    });
}
