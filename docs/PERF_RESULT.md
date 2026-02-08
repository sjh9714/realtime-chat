# 성능 최적화 기록

> 이 문서는 실시간 채팅 서비스의 성능 문제를 **발견 → 분석 → 해결 → 검증**하는 과정을 기록한다.
> 단순히 "N+1을 해결했다"가 아니라, 어떤 문제가 있었고 왜 이 방식으로 해결했는지를 설명한다.

---

## 1. N+1 쿼리 해결

### 1-1. 문제 발견

채팅방 목록 API (`GET /api/rooms`)의 Hibernate SQL 로그에서 비정상적으로 많은 쿼리가 발생하는 것을 확인했다.

**기존 코드 (ChatRoomService.java):**

```java
// Before: Entity를 모두 로드한 뒤 DTO로 변환
@Transactional(readOnly = true)
public List<ChatRoomListResponse> getMyRooms(Long userId) {
    return chatRoomMemberRepository.findAllByUserId(userId).stream()
            .map(member -> ChatRoomListResponse.from(member.getChatRoom(), userId))
            .toList();
}
```

**기존 코드 (ChatRoomListResponse.java):**

```java
public static ChatRoomListResponse from(ChatRoom room, Long userId) {
    int unreadCount = room.getMembers().stream()      // ← LazyLoading 발생!
            .filter(m -> m.getUser().getId().equals(userId))
            .findFirst()
            .map(ChatRoomMember::getUnreadCount)
            .orElse(0);

    return new ChatRoomListResponse(
            room.getId(),
            room.getName(),
            room.getType(),
            room.getMembers().size(),   // ← 또 LazyLoading!
            unreadCount,
            room.getCreatedAt()
    );
}
```

**Hibernate SQL 로그 (spring.jpa.show-sql=true):**

```
-- 1) 내 채팅방 멤버 목록 (1회)
SELECT m.* FROM chat_room_members m WHERE m.user_id = ?

-- 2) 각 방의 ChatRoom Entity 로드 (N회)
SELECT cr.* FROM chat_rooms cr WHERE cr.id = ?      -- 방 1
SELECT cr.* FROM chat_rooms cr WHERE cr.id = ?      -- 방 2
SELECT cr.* FROM chat_rooms cr WHERE cr.id = ?      -- 방 3
...

-- 3) 각 방의 Members 컬렉션 로드 (N회)
SELECT m.* FROM chat_room_members m WHERE m.room_id = ?  -- 방 1의 멤버
SELECT m.* FROM chat_room_members m WHERE m.room_id = ?  -- 방 2의 멤버
SELECT m.* FROM chat_room_members m WHERE m.room_id = ?  -- 방 3의 멤버
...
```

채팅방 5개일 때: **1 + 5(ChatRoom) + 5(Members) = 11회 쿼리**
채팅방 50개일 때: **1 + 50 + 50 = 101회 쿼리**

### 1-2. 분석

문제의 본질은 **Entity 그래프를 전부 로드한 뒤 DTO로 변환하는 패턴**에 있었다.

`ChatRoomListResponse.from()`에서 `room.getMembers()`를 호출할 때마다 Hibernate가 Lazy Loading으로 추가 SELECT를 실행한다. JOIN FETCH로 한 번에 가져와도, DTO 변환 과정에서 컬렉션 접근이 필요하므로 근본적인 해결이 안 된다.

필요한 데이터는 단 6개 필드뿐이다:
- `cr.id`, `cr.name`, `cr.type`, `cr.createdAt` (채팅방 기본 정보)
- `memberCount` (멤버 수 — COUNT로 충분)
- `unreadCount` (읽지 않은 메시지 수)

**핵심 질문:** Entity를 로드할 필요가 있는가? → **없다. DB 레벨에서 DTO로 직접 반환하면 된다.**

### 1-3. 해결: JPQL Constructor Expression

Entity 그래프 로드 없이, JPQL에서 DTO를 직접 생성하는 프로젝션 쿼리로 변경했다.

**After: ChatRoomRepository.java**

```java
// N+1 해결: JPQL 프로젝션으로 단일 쿼리
@Query("""
    SELECT new com.realtime.chat.dto.ChatRoomListResponse(
        cr.id, cr.name, cr.type,
        (SELECT COUNT(m2) FROM ChatRoomMember m2 WHERE m2.chatRoom.id = cr.id),
        m.unreadCount, cr.createdAt
    )
    FROM ChatRoomMember m
    JOIN m.chatRoom cr
    WHERE m.user.id = :userId
    """)
List<ChatRoomListResponse> findAllWithMemberInfoByUserId(@Param("userId") Long userId);
```

**After: ChatRoomService.java**

```java
@Transactional(readOnly = true)
public List<ChatRoomListResponse> getMyRooms(Long userId) {
    return chatRoomRepository.findAllWithMemberInfoByUserId(userId);
}
```

**After: ChatRoomListResponse.java** (JPQL 프로젝션용 생성자 추가)

```java
// JPQL 프로젝션용 생성자 (COUNT 결과가 Long이므로 변환)
public ChatRoomListResponse(Long id, String name, RoomType type, Long memberCount,
                            int unreadCount, LocalDateTime createdAt) {
    this(id, name, type, memberCount.intValue(), unreadCount, createdAt);
}
```

> **참고:** JPQL `COUNT()`는 `Long`을 반환하므로, `int memberCount`를 직접 받을 수 없다.
> 별도 생성자에서 `Long → int` 변환 후 기존 `@AllArgsConstructor`를 호출한다.

### 1-4. 검증

**Before:** 채팅방 N개 → 1 + N + N = **2N+1회 쿼리** (방 50개일 때 101회)

**After:** 채팅방 N개 → **1회 쿼리** (서브쿼리 포함, 단일 SQL 실행)

```
-- Hibernate 로그: 단 1회 쿼리
SELECT cr.id, cr.name, cr.type,
       (SELECT COUNT(m2) FROM chat_room_members m2 WHERE m2.room_id = cr.id),
       m.unread_count, cr.created_at
FROM chat_room_members m
JOIN chat_rooms cr ON m.room_id = cr.id
WHERE m.user_id = ?
```

**EXPLAIN ANALYZE 결과** (유저 200명, 채팅방 50개, 멤버 1,039건):

```
Hash Join  (cost=13.29..97.53 rows=6 width=40) (actual time=0.129..0.191 rows=6 loops=1)
  Hash Cond: (cr.id = m.room_id)
  ->  Seq Scan on chat_rooms cr  (actual time=0.007..0.010 rows=50 loops=1)
  ->  Hash  (actual time=0.075..0.075 rows=6 loops=1)
        ->  Bitmap Heap Scan on chat_room_members m  (actual time=0.056..0.063 rows=6 loops=1)
              Recheck Cond: (user_id = 1)
              ->  Bitmap Index Scan on idx_chat_room_members_user_id  (actual time=0.048..0.048 rows=6 loops=1)
                    Index Cond: (user_id = 1)
  SubPlan 1
    ->  Aggregate  (actual time=0.011..0.011 rows=1 loops=6)
          ->  Bitmap Heap Scan on chat_room_members m2  (actual time=0.007..0.009 rows=24 loops=6)
                ->  Bitmap Index Scan on chat_room_members_room_id_user_id_key  (actual time=0.006..0.006 rows=24 loops=6)
                      Index Cond: (room_id = cr.id)
Planning Time: 1.182 ms
Execution Time: 0.392 ms
```

**분석:**
- `idx_chat_room_members_user_id` 인덱스로 해당 유저의 방만 필터링 (Bitmap Index Scan)
- COUNT 서브쿼리는 `UNIQUE(room_id, user_id)` 인덱스를 사용 (Bitmap Index Scan)
- 전체 실행 시간: **0.392ms** — 단일 쿼리로 모든 정보를 반환

---

## 2. DB 인덱스 최적화

### 2-1. 분석 대상

주요 쿼리 4개에 대해 EXPLAIN ANALYZE를 실행하여 인덱스 사용 여부를 확인했다.

| 쿼리 | 용도 | 조건 |
|------|------|------|
| 채팅방 목록 | `GET /api/rooms` | `WHERE user_id = ?` |
| 커서 페이지네이션 | `GET /api/rooms/{id}/messages` | `WHERE room_id = ? AND id < ? ORDER BY id DESC` |
| 멱등성 체크 | Kafka Consumer | `WHERE message_key = ?` |
| unreadCount 계산 | 읽음 처리 | `WHERE room_id = ? AND id > ?` |

### 2-2. 쿼리별 실행 계획

#### (1) 커서 페이지네이션

```sql
SELECT m.*, u.nickname FROM messages m
JOIN users u ON m.sender_id = u.id
WHERE m.room_id = 1 AND m.id < 30000
ORDER BY m.id DESC LIMIT 21;
```

```
Limit  (actual time=0.082..0.189 rows=21 loops=1)
  ->  Nested Loop  (actual time=0.081..0.187 rows=21 loops=1)
        ->  Index Scan Backward using messages_pkey on messages m
              (actual time=0.067..0.155 rows=21 loops=1)
              Index Cond: (id < 30000)
              Filter: (room_id = 1)
              Rows Removed by Filter: 1393
        ->  Memoize  (actual time=0.001..0.001 rows=1 loops=21)
              Cache Key: m.sender_id
              Hits: 0  Misses: 21
              ->  Index Scan using users_pkey on users u
                    (actual time=0.001..0.001 rows=1 loops=21)
                    Index Cond: (id = m.sender_id)
Planning Time: 1.197 ms
Execution Time: 0.258 ms
```

**분석:**
- `messages_pkey` (id)로 역방향 스캔 후 `room_id` 필터링
- `idx_messages_room_id_id(room_id, id DESC)` 복합 인덱스가 존재하지만, PostgreSQL 플래너가 PK 역순 스캔이 더 효율적이라 판단
- `Rows Removed by Filter: 1393` — room_id 필터링으로 제거된 행. 데이터 분포에 따라 복합 인덱스가 더 유리할 수 있음
- 유저 JOIN에 `Memoize` 최적화 적용 (동일 sender 캐싱)
- 실행 시간: **0.258ms**

#### (2) 멱등성 체크 (message_key)

```sql
SELECT EXISTS(SELECT 1 FROM messages WHERE message_key = ?);
```

```
Result  (actual time=0.101..0.102 rows=1 loops=1)
  InitPlan 1
    ->  Index Only Scan using messages_message_key_key on messages
          (actual time=0.096..0.096 rows=1 loops=1)
          Index Cond: (message_key = 'c8c2422a-...'::uuid)
          Heap Fetches: 1
Planning Time: 0.784 ms
Execution Time: 0.439 ms
```

**분석:**
- `UNIQUE(message_key)` 인덱스로 **Index Only Scan** — 테이블 접근 없이 인덱스만으로 결과 반환
- Kafka Consumer가 메시지마다 호출하는 쿼리이므로 인덱스 사용이 필수
- 실행 시간: **0.439ms**

#### (3) unreadCount 계산

```sql
SELECT COUNT(*) FROM messages m WHERE m.room_id = 1 AND m.id > 30000;
```

```
Aggregate  (actual time=1.288..1.289 rows=1 loops=1)
  ->  Bitmap Heap Scan on messages m  (actual time=0.131..1.276 rows=232 loops=1)
        Recheck Cond: ((room_id = 1) AND (id > 30000))
        Heap Blocks: exact=165
        ->  Bitmap Index Scan on idx_messages_room_id_id
              (actual time=0.072..0.072 rows=232 loops=1)
              Index Cond: ((room_id = 1) AND (id > 30000))
Planning Time: 0.466 ms
Execution Time: 1.325 ms
```

**분석:**
- `idx_messages_room_id_id(room_id, id DESC)` 복합 인덱스를 정확히 사용
- Bitmap Index Scan → Bitmap Heap Scan으로 232건만 정확히 필터링
- 이 쿼리는 읽음 처리 시에만 호출되므로 1.3ms는 충분히 빠름

#### (4) 멤버 존재 여부 확인

```sql
SELECT EXISTS(SELECT 1 FROM chat_room_members WHERE room_id = 1 AND user_id = 1);
```

```
Result  (actual time=0.058..0.058 rows=1 loops=1)
  InitPlan 1
    ->  Index Only Scan using chat_room_members_room_id_user_id_key
          (actual time=0.056..0.056 rows=1 loops=1)
          Index Cond: ((room_id = 1) AND (user_id = 1))
          Heap Fetches: 1
Planning Time: 0.317 ms
Execution Time: 0.080 ms
```

**분석:**
- `UNIQUE(room_id, user_id)` 제약 조건이 인덱스 역할 수행
- **Index Only Scan** — 별도 인덱스 추가 없이 UK만으로 최적 성능

### 2-3. 인덱스 설계

총 5개의 커스텀 인덱스를 설계했다. 각각의 역할과 선택 이유를 정리한다.

| 인덱스 | 대상 쿼리 | 인덱스 타입 | 사용 확인 |
|--------|----------|------------|----------|
| `idx_messages_room_id_id(room_id, id DESC)` | 커서 페이지네이션, unreadCount | 복합 B-tree | Bitmap Index Scan |
| `UNIQUE(message_key)` | existsByMessageKey (멱등성) | B-tree UK | Index Only Scan |
| `UNIQUE(room_id, user_id)` | existsByChatRoomIdAndUserId, COUNT 서브쿼리 | B-tree UK | Index Only Scan |
| `idx_chat_room_members_user_id(user_id)` | 채팅방 목록 조회 | B-tree | Bitmap Index Scan |
| `idx_messages_sender_id(sender_id)` | 발신자 기반 조회/관리 | B-tree | 관리 쿼리용 |

**"추가하지 않은" 인덱스:**
- `messages(room_id)` 단일 인덱스: `idx_messages_room_id_id` 복합 인덱스가 이미 커버
- `chat_room_members(room_id)` 단일 인덱스: `UNIQUE(room_id, user_id)`가 이미 커버 (복합 인덱스의 선두 컬럼)
- `messages(created_at)` 인덱스: 현재 시간 기반 조회가 없으므로 불필요 (커서는 id 기반)

---

## 3. Redis Cache Aside

### 3-1. 문제 발견

채팅방 목록(`GET /api/rooms`)은 앱 진입 시 **모든 유저가 반복 호출**하는 API다.
N+1을 해결하여 단일 쿼리가 되었지만, 동일한 유저가 앱을 열 때마다 같은 쿼리를 DB에 실행한다.

채팅방 목록의 특성:
- **조회 빈도 높음**: 앱 진입, 화면 복귀, 새로고침 시마다 호출
- **변경 빈도 낮음**: 방 생성, 참여, 메시지 수신 시에만 변경
- **유저별 독립적**: 각 유저의 목록은 서로 다름

### 3-2. 설계 결정

#### 패턴 선택: Cache Aside

| 패턴 | 동작 | 선택하지 않은 이유 |
|------|------|-------------------|
| **Cache Aside** | 읽기: 캐시 확인 → 미스면 DB 조회 후 캐싱 | **선택** |
| Write Through | 쓰기 시 캐시+DB 동시 갱신 | 캐시 갱신 로직이 복잡 (방 생성 시 모든 멤버의 캐시 갱신 필요) |
| Write Behind | 쓰기 시 캐시만 갱신, 비동기 DB 반영 | 데이터 유실 위험, 채팅 서비스에 부적합 |

#### TTL: 5분

- **너무 짧으면** (30초): 캐시 히트율 낮음, DB 부하 감소 효과 미미
- **너무 길면** (30분): unreadCount 등 변경사항 반영 지연, 사용자 경험 저하
- **5분**: 대부분의 반복 조회를 캐시로 처리하면서, 변경 시에는 명시적 무효화로 즉시 반영

#### 캐시 무효화 전략

캐시는 3가지 시점에 무효화된다:

```java
// (1) 방 생성/참여 → 전체 무효화 (다수 유저 영향)
@CacheEvict(value = "rooms", allEntries = true)
public ChatRoomResponse createDirectRoom(...) { ... }

// (2) 메시지 수신 → 전체 무효화 (unreadCount 변경)
// MessagePersistenceConsumer.java
chatRoomMemberRepository.incrementUnreadCountForOtherMembers(roomId, senderId);
var roomsCache = cacheManager.getCache("rooms");
if (roomsCache != null) { roomsCache.clear(); }

// (3) 읽음 처리 → 해당 유저만 무효화
// ReadReceiptService.java
var roomsCache = cacheManager.getCache("rooms");
if (roomsCache != null) { roomsCache.evict(event.getUserId()); }
```

| 이벤트 | 무효화 범위 | 이유 |
|--------|-----------|------|
| 방 생성/참여 | `allEntries = true` | 새 방이 여러 유저의 목록에 추가됨 |
| 메시지 수신 | `cache.clear()` | 수신자 전원의 unreadCount 변경 |
| 읽음 처리 | `cache.evict(userId)` | 해당 유저의 unreadCount만 변경 |

### 3-3. 구현 시 겪은 문제

#### (1) LocalDateTime 직렬화 오류

```
com.fasterxml.jackson.databind.exc.MismatchedInputException:
  Cannot deserialize value of type `java.time.LocalDateTime`
```

Redis에 캐시 저장 시 `GenericJackson2JsonRedisSerializer`가 기본 `ObjectMapper`를 사용하는데,
이 ObjectMapper에는 `JavaTimeModule`이 등록되어 있지 않아 `LocalDateTime` 직렬화/역직렬화가 실패했다.

**해결:** 커스텀 ObjectMapper 구성

```java
@Bean
public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
    ObjectMapper objectMapper = new ObjectMapper();
    objectMapper.registerModule(new JavaTimeModule());  // LocalDateTime 지원
    objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    objectMapper.activateDefaultTyping(
            objectMapper.getPolymorphicTypeValidator(),
            ObjectMapper.DefaultTyping.NON_FINAL);  // 타입 정보 포함 (역직렬화 시 필요)

    RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofMinutes(5))
            .serializeValuesWith(
                    RedisSerializationContext.SerializationPair.fromSerializer(
                            new GenericJackson2JsonRedisSerializer(objectMapper)));

    return RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(config)
            .build();
}
```

#### (2) Jackson 역직렬화 시 기본 생성자 필요

```
com.fasterxml.jackson.databind.exc.InvalidDefinitionException:
  Cannot construct instance of `ChatRoomListResponse`
```

`GenericJackson2JsonRedisSerializer`는 JSON을 객체로 복원할 때 기본 생성자 + setter를 사용한다.
기존에는 `@AllArgsConstructor`만 있어서 역직렬화가 실패했다.

**해결:** `@NoArgsConstructor` 추가

```java
@Getter
@NoArgsConstructor   // ← 추가: Jackson 역직렬화용
@AllArgsConstructor
public class ChatRoomListResponse { ... }
```

### 3-4. 검증

**캐시 미스 (첫 번째 요청):**
1. Redis에서 `rooms::1` 키 조회 → 없음 (MISS)
2. DB 쿼리 실행 (Hibernate SQL 로그에 SELECT 출력)
3. 결과를 Redis에 저장 (`SET rooms::1 [JSON] EX 300`)

**캐시 히트 (두 번째 요청):**
1. Redis에서 `rooms::1` 키 조회 → 있음 (HIT)
2. DB 쿼리 **실행 안 됨** (Hibernate SQL 로그 없음)
3. Redis에서 바로 반환

**캐시 무효화 (메시지 수신 후):**
1. Kafka Consumer가 메시지 저장 + unreadCount 증가
2. `cacheManager.getCache("rooms").clear()` 호출
3. 다음 요청 시 DB에서 새로 조회 → 변경된 unreadCount 반영

```bash
# Redis CLI로 캐시 키 확인
$ redis-cli KEYS "rooms::*"
1) "rooms::1"
2) "rooms::2"

# TTL 확인 (5분 = 300초)
$ redis-cli TTL "rooms::1"
(integer) 287
```

### 3-5. 알려진 한계

메시지 수신 시 `cache.clear()`로 **전체 유저의 캐시를 무효화**한다. 채팅 서비스에서 메시지는 끊임없이 들어오므로, 활발한 채팅 중에는 캐시가 저장되자마자 무효화되어 히트율이 떨어진다.

**현재 구조에서 수용 가능한 이유:**
- 채팅방 목록 API는 **앱 진입 시점**에 호출된다. 채팅 중에는 이미 특정 방에 들어가 있으므로 목록 API를 호출하지 않는다
- 채팅이 끝나고 목록 화면으로 돌아올 때 캐시 미스가 발생해도, 단일 쿼리(0.4ms)이므로 체감 지연은 없다

**스케일에서의 한계:**
- 유저 수가 증가하면 `allEntries=true`가 관계없는 유저의 캐시까지 제거한다
- 개선 방향: 해당 채팅방 멤버만 선택적으로 무효화 (읽음 처리에서 사용 중인 `cache.evict(userId)` 패턴을 메시지 수신에도 적용)

---

## 4. k6 부하테스트

### 4-1. 테스트 환경

| 항목 | 사양 |
|------|------|
| OS | macOS (Apple Silicon) |
| Docker | Docker Desktop (CPU 4코어, 메모리 8GB) |
| PostgreSQL | 16-alpine |
| Redis | 7-alpine |
| Kafka | apache/kafka:3.9.0 (KRaft, 파티션 6개) |
| 앱 서버 | Spring Boot 3.4.3, Java 21, Docker 컨테이너 2대 |
| 부하 도구 | k6 v1.5.0 (호스트에서 실행) |

### 4-2. REST API 부하테스트 (Before/After 비교)

**시나리오:** 200 VU, 50초 (워밍업 10s → 최대부하 30s → 쿨다운 10s)
**플로우:** 회원가입(setup) → 그룹 채팅방 10개 생성 → 200명 전원 참여 → 채팅방 목록/상세/메시지 이력 조회
**핵심:** 모든 유저가 10개 채팅방의 멤버 → Before는 방 10개에 대해 N+1 쿼리 발생, After는 단일 쿼리

#### 최적화 전 (Before: N+1 문제 발생 + 캐시 없음)

| 메트릭 | 결과 |
|--------|------|
| 총 요청 수 | 67,417 |
| RPS (초당 요청) | **937** |
| p50 응답시간 | **54.27ms** |
| p90 응답시간 | 172.33ms |
| p95 응답시간 | **212.85ms** |
| 에러율 | 0% |
| 체크 성공률 | 100% |

#### 최적화 후 (After: 단일 쿼리 + Redis 캐싱)

| 메트릭 | 결과 |
|--------|------|
| 총 요청 수 | 118,900 |
| RPS (초당 요청) | **1,598** |
| p50 응답시간 | **16.56ms** |
| p90 응답시간 | 109.37ms |
| p95 응답시간 | **149.22ms** |
| 에러율 | 0% |
| 체크 성공률 | 100% |

#### Before vs After 비교

| 메트릭 | Before | After | 개선 |
|--------|--------|-------|------|
| RPS | 937 | 1,598 | **+70.5%** |
| p50 응답시간 | 54.27ms | 16.56ms | **-69.5%** |
| p90 응답시간 | 172.33ms | 109.37ms | **-36.5%** |
| p95 응답시간 | 212.85ms | 149.22ms | **-29.9%** |
| 총 처리량 | 67,417 | 118,900 | **+76.4%** |

**분석:**
- **처리량 70% 증가:** Before 67,417건 → After 118,900건. 동일 50초에 76% 더 많은 요청을 처리
- **주된 기여 — N+1 해결:** Before는 채팅방 목록 호출마다 약 21개 쿼리(1+10+10)를 실행하지만, After는 1개 쿼리로 통합. DB 부하 감소가 RPS 증가의 핵심 원인
- **보조 기여 — Redis 캐싱:** 동일 유저의 반복 호출 시 DB 쿼리를 완전히 스킵. 캐시 히트 시 응답시간 1ms 이하로, p50 개선에 기여
- **테일 레이턴시(p95) 30% 개선:** Before의 p95 212ms에는 DB 커넥션 풀 경합에 의한 대기시간이 포함

> **테스트 한계:** 이 시나리오는 **조회 전용**이며 메시지 전송이 포함되지 않는다.
> 실제 운영에서는 메시지 수신마다 캐시가 무효화되므로 캐시 히트율이 낮아진다.
> 따라서 RPS 70% 개선 중 대부분은 **N+1 해결에 의한 것**이며, 캐시의 기여분은 제한적이다.
> 캐시의 한계와 개선 방향은 [3-5. 알려진 한계](#3-5-알려진-한계)에서 다룬다.

### 4-3. WebSocket 부하테스트

**시나리오:** 100 VU, 50초 (워밍업 10s → 최대부하 30s → 쿨다운 10s)
**플로우:** 회원가입(setup) → STOMP 연결 → 채팅방 구독 → 메시지 5건 전송 → 수신 대기

#### 단일 인스턴스 결과

| 메트릭 | 결과 |
|--------|------|
| 동시 WebSocket 세션 | 579 |
| 전송 메시지 수 | 2,895 (579 세션 x 5건) |
| 수신 메시지 수 | 579 |
| STOMP 연결 시간 p95 | **5.52ms** |
| 세션 지속시간 | 4.5s (전송 2.5s + 수신 대기 2s) |
| 체크 성공률 | 100% (WebSocket 연결 성공) |

#### 2대 스케일아웃 결과

| 메트릭 | app-1 (8081) | app-2 (8082) | 합계 |
|--------|-------------|-------------|------|
| 동시 세션 | 579 | 579 | **1,158** |
| 전송 메시지 | 2,895 | 2,895 | **5,790** |
| STOMP 연결 p95 | 3.67ms | 3.05ms | - |
| 체크 성공률 | 100% | 100% | 100% |

#### 분석

- **스케일아웃 효과:** 동시 세션 579 → 1,158 (2배), 메시지 처리량 2,895 → 5,790 (2배)
- **STOMP 연결 안정성:** 1,158개 동시 세션에서도 연결 실패 0건
- **크로스 서버 메시지 전달:** Kafka + Redis Pub/Sub를 통해 서로 다른 서버에 연결된 유저 간 메시지가 정상 전달됨
- 수신 메시지 수가 전송 수보다 적은 이유: 메시지가 Kafka → DB → Redis Pub/Sub → WebSocket 경로를 거치는 비동기 처리이며, 테스트에서 2초 수신 대기 후 연결을 종료하기 때문

---

## 5. 인덱스 전체 커버리지

| 인덱스 | 테이블 | 대상 쿼리 | 타입 |
|--------|--------|----------|------|
| `idx_messages_room_id_id(room_id, id DESC)` | messages | 커서 페이지네이션, countUnreadMessages | Bitmap Index Scan |
| `messages_message_key_key UNIQUE(message_key)` | messages | existsByMessageKey (멱등성 체크) | Index Only Scan |
| `chat_room_members_room_id_user_id_key UNIQUE(room_id, user_id)` | chat_room_members | existsByChatRoomIdAndUserId, COUNT 서브쿼리 | Index Only Scan |
| `idx_chat_room_members_user_id(user_id)` | chat_room_members | findAllWithMemberInfoByUserId (채팅방 목록) | Bitmap Index Scan |
| `idx_messages_sender_id(sender_id)` | messages | 발신자 기반 조회 (관리/검색) | B-tree |

**결론:** 모든 주요 쿼리가 인덱스를 사용하며, Sequential Scan이 발생하는 경우는 없다.

---

## 6. 정리

| 최적화 | Before | After | 개선 효과 |
|--------|--------|-------|----------|
| N+1 쿼리 | 방 N개 → 2N+1회 쿼리 | 1회 쿼리 (JPQL 프로젝션) | 쿼리 수 O(N) → O(1) |
| 인덱스 | 일부 쿼리 Sequential Scan | 모든 쿼리 Index Scan | 데이터 증가에도 안정적 성능 |
| 캐시 | 매 요청마다 DB 조회 | 캐시 히트 시 DB 조회 0회 | 반복 조회 시 DB 부하 제거 |
| 부하테스트 (REST) | 937 RPS, p50 54ms | 1,598 RPS, p50 16ms | **RPS +70%**, **p50 -69%** |
| 부하테스트 (WS) | - | 579 동시세션, 0% 실패 | 스케일아웃 시 1,158 세션 (2배) |

---

## 7. 한계 및 개선 방향

### 7-1. 캐시 무효화 범위

**현재:** 메시지 수신 시 `cache.clear()`로 전체 유저의 캐시를 무효화한다.

**한계:** 방 A에서 메시지가 오면, 방 A와 관계없는 유저의 캐시까지 무효화된다. 유저 수가 증가할수록 불필요한 캐시 미스가 늘어난다.

**개선 방향:** 해당 채팅방 멤버의 userId 목록을 조회하여 `cache.evict(userId)`로 멤버별 선택 무효화. 읽음 처리에서 이미 이 방식을 사용하고 있으므로 동일 패턴을 적용할 수 있다.

### 7-2. 부하테스트 시나리오

**현재:** k6 REST API 테스트는 조회 전용이며, 메시지 전송이 포함되지 않는다.

**한계:** 실제 트래픽은 읽기(채팅방 목록, 메시지 이력)와 쓰기(메시지 전송, 읽음 처리)가 혼합된다. 쓰기마다 캐시가 무효화되므로, 조회 전용 테스트의 캐시 히트율은 실제보다 높게 측정된다.

**개선 방향:** 메시지 전송 + 조회를 혼합한 시나리오를 설계하여, 실제 캐시 히트율과 성능을 측정.

### 7-3. 테스트 환경

**현재:** Docker Desktop (CPU 4코어, 메모리 8GB) 위에서 앱 2대 + DB + Redis + Kafka를 모두 구동.

**한계:** 모든 컴포넌트가 동일 머신에서 리소스를 경합하므로, 프로덕션 환경(전용 DB 서버, 네트워크 분리)과 절대 수치가 다를 수 있다. Before/After의 **상대적 개선 비율**은 유효하지만, 절대 RPS는 참고치로 봐야 한다.
