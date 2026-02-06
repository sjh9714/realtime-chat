-- 사용자
CREATE TABLE IF NOT EXISTS users (
    id          BIGSERIAL PRIMARY KEY,
    email       VARCHAR(255) NOT NULL UNIQUE,
    password    VARCHAR(255) NOT NULL,
    nickname    VARCHAR(50)  NOT NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT 'OFFLINE',
    last_seen_at TIMESTAMP,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- 채팅방
CREATE TABLE IF NOT EXISTS chat_rooms (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(100),
    type        VARCHAR(20)  NOT NULL,
    created_by  BIGINT       NOT NULL REFERENCES users(id),
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- 채팅방 멤버
CREATE TABLE IF NOT EXISTS chat_room_members (
    id                   BIGSERIAL PRIMARY KEY,
    room_id              BIGINT    NOT NULL REFERENCES chat_rooms(id),
    user_id              BIGINT    NOT NULL REFERENCES users(id),
    last_read_message_id BIGINT,
    unread_count         INT       NOT NULL DEFAULT 0,
    joined_at            TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (room_id, user_id)
);

-- 메시지
CREATE TABLE IF NOT EXISTS messages (
    id              BIGSERIAL PRIMARY KEY,
    message_key     UUID         NOT NULL UNIQUE,
    room_id         BIGINT       NOT NULL REFERENCES chat_rooms(id),
    sender_id       BIGINT       NOT NULL REFERENCES users(id),
    content         TEXT         NOT NULL,
    type            VARCHAR(20)  NOT NULL DEFAULT 'TEXT',
    kafka_partition INT,
    kafka_offset    BIGINT,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- 메시지 조회 성능을 위한 복합 인덱스
CREATE INDEX IF NOT EXISTS idx_messages_room_id_id ON messages(room_id, id DESC);

-- Kafka 파티션/오프셋 유니크 제약 (멱등성 보장)
CREATE UNIQUE INDEX IF NOT EXISTS uk_messages_kafka ON messages(kafka_partition, kafka_offset)
    WHERE kafka_partition IS NOT NULL AND kafka_offset IS NOT NULL;

-- 채팅방 멤버 조회용 인덱스
CREATE INDEX IF NOT EXISTS idx_chat_room_members_user_id ON chat_room_members(user_id);
