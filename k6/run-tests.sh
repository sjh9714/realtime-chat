#!/bin/bash

# k6 부하 테스트 실행 스크립트
# 사전 조건: docker compose up -d (인프라 + 앱 서버 실행 필요)
# k6 설치: brew install k6

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RESULTS_DIR="${SCRIPT_DIR}/results"
mkdir -p "$RESULTS_DIR"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)

echo "=========================================="
echo " k6 부하 테스트"
echo "=========================================="

# 1. 단일 인스턴스 REST API 테스트
echo ""
echo "[1/4] REST API 테스트 (단일 인스턴스 - app-1:8081)"
echo "------------------------------------------"
k6 run \
    --env BASE_URL=http://localhost:8081 \
    --out json="${RESULTS_DIR}/rest-single-${TIMESTAMP}.json" \
    --summary-export="${RESULTS_DIR}/rest-single-${TIMESTAMP}-summary.json" \
    "${SCRIPT_DIR}/rest-api-test.js"

# 2. 단일 인스턴스 WebSocket 테스트
echo ""
echo "[2/4] WebSocket 테스트 (단일 인스턴스 - app-1:8081)"
echo "------------------------------------------"
k6 run \
    --env BASE_URL=http://localhost:8081 \
    --env WS_URL=ws://localhost:8081/ws \
    --out json="${RESULTS_DIR}/ws-single-${TIMESTAMP}.json" \
    --summary-export="${RESULTS_DIR}/ws-single-${TIMESTAMP}-summary.json" \
    "${SCRIPT_DIR}/websocket-test.js"

# 3. 2대 스케일아웃 REST API 테스트 (라운드로빈)
echo ""
echo "[3/4] REST API 테스트 (2대 스케일아웃 - app-1:8081)"
echo "     두 번째 인스턴스는 app-2:8082에서 동시 실행"
echo "------------------------------------------"
k6 run \
    --env BASE_URL=http://localhost:8081 \
    --out json="${RESULTS_DIR}/rest-scale-1-${TIMESTAMP}.json" \
    --summary-export="${RESULTS_DIR}/rest-scale-1-${TIMESTAMP}-summary.json" \
    "${SCRIPT_DIR}/rest-api-test.js" &
PID1=$!

k6 run \
    --env BASE_URL=http://localhost:8082 \
    --out json="${RESULTS_DIR}/rest-scale-2-${TIMESTAMP}.json" \
    --summary-export="${RESULTS_DIR}/rest-scale-2-${TIMESTAMP}-summary.json" \
    "${SCRIPT_DIR}/rest-api-test.js" &
PID2=$!

wait $PID1 $PID2

# 4. 2대 스케일아웃 WebSocket 테스트
echo ""
echo "[4/4] WebSocket 테스트 (2대 스케일아웃)"
echo "------------------------------------------"
k6 run \
    --env BASE_URL=http://localhost:8081 \
    --env WS_URL=ws://localhost:8081/ws \
    --out json="${RESULTS_DIR}/ws-scale-1-${TIMESTAMP}.json" \
    --summary-export="${RESULTS_DIR}/ws-scale-1-${TIMESTAMP}-summary.json" \
    "${SCRIPT_DIR}/websocket-test.js" &
PID3=$!

k6 run \
    --env BASE_URL=http://localhost:8082 \
    --env WS_URL=ws://localhost:8082/ws \
    --out json="${RESULTS_DIR}/ws-scale-2-${TIMESTAMP}.json" \
    --summary-export="${RESULTS_DIR}/ws-scale-2-${TIMESTAMP}-summary.json" \
    "${SCRIPT_DIR}/websocket-test.js" &
PID4=$!

wait $PID3 $PID4

echo ""
echo "=========================================="
echo " 테스트 완료!"
echo " 결과 디렉토리: ${RESULTS_DIR}"
echo "=========================================="
