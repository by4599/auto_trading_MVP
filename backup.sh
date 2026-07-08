#!/usr/bin/env bash
# H2 파일 DB 백업 스크립트
#
# [권장 실행 절차]
#   앱이 실행 중인 상태에서 cp는 쓰기 중인 페이지가 불일치 상태로 복사될 수 있다.
#   가장 안전한 방법은 앱을 중지하고 백업하는 것이다:
#
#     systemctl stop auto-trading && ./backup.sh && systemctl start auto-trading
#     또는 직접 실행 시: kill $(cat trading.pid) && ./backup.sh
#
# [대안] H2 AUTO_SERVER=TRUE 모드에서 장 마감 후(15:30~) 쓰기 부하가 없을 때
#   실행하면 현실적으로 안전하다. MVP 규모에서는 수용 가능.
#
# 사용법: ./backup.sh [--force]
#   --force: 앱 실행 중 여부 확인 없이 강제 백업
#
# [JAR_NAME 설정]
#   실행 중인 JAR 파일명에 맞게 수정. 예: trading-0.0.1-SNAPSHOT.jar
JAR_NAME="${JAR_NAME:-trading*.jar}"

set -euo pipefail

DB_FILE="./trading-db.mv.db"
BACKUP_DIR="./backup"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
DEST="${BACKUP_DIR}/trading-db-${TIMESTAMP}.mv.db"
FORCE="${1:-}"

if [ ! -f "$DB_FILE" ]; then
    echo "[ERROR] DB 파일 없음: $DB_FILE"
    exit 1
fi

# systemctl 서비스로 관리되는 경우와 직접 java -jar 실행 모두 감지
is_running() {
    # systemctl 서비스 확인 (서비스 이름이 다르면 수정)
    if command -v systemctl &>/dev/null && systemctl is-active --quiet auto-trading 2>/dev/null; then
        return 0
    fi
    # JAR 직접 실행 확인
    if pgrep -f "$JAR_NAME" > /dev/null 2>&1; then
        return 0
    fi
    return 1
}

if [ "$FORCE" != "--force" ] && is_running; then
    echo "[WARNING] 트레이딩 앱이 실행 중입니다."
    echo "  안전한 백업을 위해 앱을 중지 후 실행하거나 --force 옵션을 사용하세요."
    echo "  예: systemctl stop auto-trading && ./backup.sh && systemctl start auto-trading"
    exit 1
fi

mkdir -p "$BACKUP_DIR"
cp "$DB_FILE" "$DEST"
echo "[OK] 백업 완료: $DEST ($(du -h "$DEST" | cut -f1))"

# 30일 이상 된 백업 자동 삭제
find "$BACKUP_DIR" -name "trading-db-*.mv.db" -mtime +30 -delete
echo "[OK] 30일 초과 백업 정리 완료"
