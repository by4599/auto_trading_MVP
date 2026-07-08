#!/usr/bin/env bash
# Gradle 래퍼 — 캐시된 Gradle 9.2.0을 직접 사용
# Java 21이 JAVA_HOME에 설정된 후 실행하세요.

GRADLE_BIN="$HOME/.gradle/wrapper/dists/gradle-9.2.0-bin/11i5gvueggl8a5cioxuftxrik/gradle-9.2.0/bin/gradle"

if [ ! -f "$GRADLE_BIN" ]; then
    echo "[ERROR] Gradle 9.2.0 캐시를 찾을 수 없습니다: $GRADLE_BIN"
    exit 1
fi

if [ -z "$JAVA_HOME" ]; then
    echo "[ERROR] JAVA_HOME이 설정되지 않았습니다. Java 21 설치 후 설정하세요."
    exit 1
fi

exec "$GRADLE_BIN" "$@"
