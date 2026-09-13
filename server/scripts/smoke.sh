#!/usr/bin/env bash
# VaultForge 服务端冒烟测试：临时目录启动实例，验证核心接口与同步链路。
# 用法：cd server && go build -o vaultforge-server . && ./scripts/smoke.sh [port]
set -u

PORT="${1:-8799}"
DIR="$(mktemp -d /tmp/vfsmoke.XXXXXX)"
BIN="${BIN:-./vaultforge-server}"

if [ ! -x "$BIN" ]; then
  echo "找不到可执行文件 $BIN（先在 server 目录执行 go build -o vaultforge-server .）"
  exit 1
fi

VF_DATA_DIR="$DIR" VF_ADDR="127.0.0.1:$PORT" "$BIN" >"$DIR/server.log" 2>&1 &
SRV=$!
trap 'kill $SRV 2>/dev/null' EXIT
sleep 1

TOKEN="$(cat "$DIR/token.txt")"
B="http://127.0.0.1:$PORT"
PASS=0
FAIL=0

check() { # check <描述> <期望子串> <实际输出>
  if printf '%s' "$3" | grep -qF "$2"; then
    PASS=$((PASS + 1))
    echo "PASS $1"
  else
    FAIL=$((FAIL + 1))
    echo "FAIL $1"
    echo "  实际: $3"
  fi
}

AUTH=(-H "Authorization: Bearer $TOKEN")
JSON=(-H "Content-Type: application/json")

check "healthz 健康检查" '"code":0' "$(curl -s "$B/healthz")"
check "服务信息（公开）" '"name":"vaultforge-server"' "$(curl -s "$B/api/v1")"
check "未鉴权返回 401" '"code":401' "$(curl -s "$B/api/v1/items")"
check "条目列表（空）" '[]' "$(curl -s "${AUTH[@]}" "$B/api/v1/items")"

CREATE="$(curl -s "${AUTH[@]}" "${JSON[@]}" -X POST \
  -d '{"type":"ssh","name":"smoke-ssh","host":"127.0.0.1","port":22,"username":"root","authMethod":"password","tags":["t1"]}' \
  "$B/api/v1/items")"
check "新建条目（created）" '"message":"created"' "$CREATE"
ID="$(printf '%s' "$CREATE" | python3 -c 'import json,sys;print(json.load(sys.stdin)["data"]["id"])')"

check "条目详情" '"name":"smoke-ssh"' "$(curl -s "${AUTH[@]}" "$B/api/v1/items/$ID")"
check "更新条目（updated）" '"message":"updated"' "$(curl -s "${AUTH[@]}" "${JSON[@]}" -X PATCH -d '{"name":"smoke-ssh-2"}' "$B/api/v1/items/$ID")"
check "设置标签" '"t1","t2"' "$(curl -s "${AUTH[@]}" "${JSON[@]}" -X PUT -d '{"tags":["t1","t2"]}' "$B/api/v1/items/$ID/tags")"
check "无效类型被拒（400）" '"code":400' "$(curl -s "${AUTH[@]}" "${JSON[@]}" -X POST -d '{"type":"nope","name":"x"}' "$B/api/v1/items")"

check "同步拉取（全量）" '"serverTime"' "$(curl -s "${AUTH[@]}" "${JSON[@]}" -X POST -d '{"deviceId":"smoke","since":0}' "$B/api/v1/sync/pull")"

NOW="$(( $(date +%s) * 1000 ))"
check "同步推送（接受）" '"accepted"' "$(curl -s "${AUTH[@]}" "${JSON[@]}" -X POST \
  -d "{\"deviceId\":\"smoke\",\"items\":[{\"id\":\"p1\",\"type\":\"api\",\"name\":\"pushed\",\"updatedAt\":$NOW}]}" \
  "$B/api/v1/sync/push")"
check "同步推送（冲突）" '"conflicts"' "$(curl -s "${AUTH[@]}" "${JSON[@]}" -X POST \
  -d '{"deviceId":"smoke","items":[{"id":"p1","type":"api","name":"old","updatedAt":1}]}' \
  "$B/api/v1/sync/push")"

check "设置写入" '"chartStyle":"ring"' "$(curl -s "${AUTH[@]}" "${JSON[@]}" -X PUT -d '{"chartStyle":"ring"}' "$B/api/v1/settings")"
check "设置读取" '"chartStyle":"ring"' "$(curl -s "${AUTH[@]}" "$B/api/v1/settings")"

check "删除条目（deleted）" '"message":"deleted"' "$(curl -s "${AUTH[@]}" -X DELETE "$B/api/v1/items/$ID")"
check "删除后详情 404" '"code":404' "$(curl -s "${AUTH[@]}" "$B/api/v1/items/$ID")"
check "删除进入墓碑" "\"$ID\"" "$(curl -s "${AUTH[@]}" "${JSON[@]}" -X POST -d '{"deviceId":"smoke","since":0}' "$B/api/v1/sync/pull")"

echo "=============================="
echo "PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]
