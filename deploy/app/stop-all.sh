#!/usr/bin/env bash
# 1) 只杀本项目 jar，避免误杀其他 Java 进程
pkill -f 'gulimall-.*-0\.0\.1-SNAPSHOT\.jar'
pkill -f 'renren-fast\.jar'

# 2) 等 3 秒后确认
sleep 3
pgrep -af 'gulimall-.*\.jar|renren-fast\.jar' || echo "[已全部停止]"