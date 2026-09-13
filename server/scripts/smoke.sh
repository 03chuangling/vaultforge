#!/usr/bin/env bash
# VaultForge 服务端冒烟测试（v0.4.0）：
# 临时目录启动实例，验证注册 / 登录 / 鉴权 / 同步 / 条目动作 / Web 面板静态资源
# v0.3.0 接口（stats / export / sessions / password / batch / check）
# 与 v0.4.0 新增的 Bitwarden / Vaultwarden 对接接口（/api/v1/bitwarden/pull）。
# 用法：cd server && go build -o vaultforge-server . && ./scripts/smoke.sh [port]
set -u

PORT="${1:-8799}"
DIR="$(mktemp -d /tmp/vfsmoke.XXXXXX)"
BIN="${BIN:-./vaultforge-server}"

if [ ! -x "$BIN" ]; then
  echo "找不到可执行文件 $BIN（先在 server 目录执行 go build -o vaultforge-server .）"
  exit 1
fi

VF_DATA_DIR="$DIR" VF_ADDR="127.0.0.1:$PORT" VF_PBKDF2_ITERS=1000 "$BIN" >"$DIR/server.log" 2>&1 &
SRV=$!
trap 'kill $SRV 2>/dev/null' EXIT
sleep 1

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

JSON=(-H "Content-Type: application/json")

# ============ v0.2.0：账号 / 鉴权 / 同步 ============

check "healthz 健康检查" '"code":0' "$(curl -s "$B/healthz")"
check "服务信息（公开）" '"auth":"account"' "$(curl -s "$B/api/v1")"
check "未鉴权返回 401" '"code":401' "$(curl -s "$B/api/v1/items")"

REG="$(curl -s "${JSON[@]}" -X POST -d '{"username":"smoke","password":"smoke-pass-123"}' "$B/api/v1/auth/register")"
check "注册引导账号（created）" '"message":"created"' "$REG"
TOKEN="$(printf '%s' "$REG" | python3 -c 'import json,sys;print(json.load(sys.stdin)["data"]["token"])')"
check "注册后状态关闭" '"register":"closed"' "$(curl -s "$B/api/v1")"
check "重复注册被拒（已关闭）" '"code":403' "$(curl -s "${JSON[@]}" -X POST -d '{"username":"other","password":"other-pass-123"}' "$B/api/v1/auth/register")"
check "错误密码登录被拒" '"code":401' "$(curl -s "${JSON[@]}" -X POST -d '{"username":"smoke","password":"wrong-pass"}' "$B/api/v1/auth/login")"

LOGIN="$(curl -s "${JSON[@]}" -X POST -d '{"username":"smoke","password":"smoke-pass-123"}' "$B/api/v1/auth/login")"
check "登录成功（签发令牌）" '"token":"vfs_' "$LOGIN"
TOKEN2="$(printf '%s' "$LOGIN" | python3 -c 'import json,sys;print(json.load(sys.stdin)["data"]["token"])')"
check "账号信息（me）" '"username":"smoke"' "$(curl -s -H "Authorization: Bearer $TOKEN2" "$B/api/v1/auth/me")"

check "退出登录" '"code":0' "$(curl -s -X POST -H "Authorization: Bearer $TOKEN2" "$B/api/v1/auth/logout")"
check "退出后令牌失效" '"code":401' "$(curl -s -H "Authorization: Bearer $TOKEN2" "$B/api/v1/items")"

AUTH=(-H "Authorization: Bearer $TOKEN")
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

# ============ v0.3.0：Web 面板 + 服务端专属功能 ============

check "Web 面板首页（HTML）" 'VaultForge Console' "$(curl -s "$B/")"
check "静态资源 app.js" 'window.App' "$(curl -s "$B/assets/app.js")"
check "静态资源 app.css" 'color-scheme: dark' "$(curl -s "$B/assets/app.css")"
check "未知路径 JSON 404" '"code":404' "$(curl -s "$B/nope")"

check "仪表盘统计（stats）" '"uptimeMs"' "$(curl -s "${AUTH[@]}" "$B/api/v1/stats")"
check "会话列表（sessions）" '"sessions"' "$(curl -s "${AUTH[@]}" "$B/api/v1/auth/sessions")"
check "会话列表含当前标记" '"current":true' "$(curl -s "${AUTH[@]}" "$B/api/v1/auth/sessions")"
check "数据导出（export）" '"exportedAt"' "$(curl -s "${AUTH[@]}" "$B/api/v1/export")"

# 批量操作：新建两条 → 打标签 → 按标签筛选 → 批量巡检
C1="$(curl -s "${AUTH[@]}" "${JSON[@]}" -X POST -d '{"type":"api","name":"web-a1","endpoint":"https://127.0.0.1:1/"}' "$B/api/v1/items")"
ID1="$(printf '%s' "$C1" | python3 -c 'import json,sys;print(json.load(sys.stdin)["data"]["id"])')"
C2="$(curl -s "${AUTH[@]}" "${JSON[@]}" -X POST -d '{"type":"api","name":"web-a2","endpoint":"https://127.0.0.1:1/"}' "$B/api/v1/items")"
ID2="$(printf '%s' "$C2" | python3 -c 'import json,sys;print(json.load(sys.stdin)["data"]["id"])')"
check "批量打标签（affected=2）" '"affected":2' "$(curl -s "${AUTH[@]}" "${JSON[@]}" -X POST -d "{\"action\":\"tag\",\"ids\":[\"$ID1\",\"$ID2\"],\"tags\":[\"webtag\"]}" "$B/api/v1/items/batch")"
check "按标签筛选命中" '"web-a1"' "$(curl -s "${AUTH[@]}" "$B/api/v1/items?tag=webtag")"
check "批量巡检（结果数组）" '"results"' "$(curl -s "${AUTH[@]}" "${JSON[@]}" -X POST -d "{\"action\":\"check\",\"ids\":[\"$ID1\",\"$ID2\"]}" "$B/api/v1/items/batch")"

# 单条检测（写入探测结果）
check "单条检测（result）" '"latencyMs"' "$(curl -s "${AUTH[@]}" -X POST "$B/api/v1/items/$ID1/check")"
check "探测结果已写回" '"lastCheckedAt"' "$(curl -s "${AUTH[@]}" "$B/api/v1/items/$ID1")"

# SSH / 文件动作在非对应类型上被拒
check "非 SSH 执行命令被拒" '"code":400' "$(curl -s "${AUTH[@]}" "${JSON[@]}" -X POST -d '{"command":"id"}' "$B/api/v1/items/$ID1/exec")"
check "非 SSH 读取指标被拒" '"code":400' "$(curl -s "${AUTH[@]}" "$B/api/v1/items/$ID1/metrics")"
check "非 SSH 读取容器被拒" '"code":400' "$(curl -s "${AUTH[@]}" "$B/api/v1/items/$ID1/docker")"
check "非文件条目浏览被拒" '"code":400' "$(curl -s "${AUTH[@]}" "$B/api/v1/items/$ID1/files")"

# SSH 条目（端口 1 不可达）：指标 / 容器优雅失败，命令执行 502
S1="$(curl -s "${AUTH[@]}" "${JSON[@]}" -X POST -d '{"type":"ssh","name":"web-ssh","host":"127.0.0.1","port":1,"username":"root","authMethod":"password","secret":"x"}' "$B/api/v1/items")"
SID="$(printf '%s' "$S1" | python3 -c 'import json,sys;print(json.load(sys.stdin)["data"]["id"])')"
check "SSH 指标（优雅失败）" '"ok":false' "$(curl -s "${AUTH[@]}" "$B/api/v1/items/$SID/metrics")"
check "SSH 容器列表（优雅失败）" '"ok":false' "$(curl -s "${AUTH[@]}" "$B/api/v1/items/$SID/docker")"
check "SSH 执行命令（连接失败 502）" '"code":502' "$(curl -s "${AUTH[@]}" "${JSON[@]}" -X POST -d '{"command":"id"}' "$B/api/v1/items/$SID/exec")"

# 当前会话不可吊销
CURID="$(curl -s "${AUTH[@]}" "$B/api/v1/auth/sessions" | python3 -c 'import json,sys; s=json.load(sys.stdin)["data"]["sessions"]; print([x["id"] for x in s if x["current"]][0])')"
check "吊销当前会话被拒" '"code":400' "$(curl -s "${AUTH[@]}" -X DELETE "$B/api/v1/auth/sessions/$CURID")"

# ============ v0.4.0：Bitwarden / Vaultwarden 对接 ============

check "Web 导航含 Bitwarden" 'data-nav="bitwarden"' "$(curl -s "$B/")"
check "静态资源 views_c.js" 'Views.bitwarden' "$(curl -s "$B/assets/views_c.js")"
check "bitwarden/pull 未鉴权 401" '"code":401' "$(curl -s "${JSON[@]}" -X POST -d '{"server":"https://example.com","email":"a@b.c","password":"x"}' "$B/api/v1/bitwarden/pull")"
check "bitwarden/pull 参数缺失 400" '"code":400' "$(curl -s "${AUTH[@]}" "${JSON[@]}" -X POST -d '{"server":"","email":"","password":""}' "$B/api/v1/bitwarden/pull")"

# 修改密码：旧密码失效 / 新密码可登录
check "修改密码（revokedOthers）" '"revokedOthers"' "$(curl -s "${AUTH[@]}" "${JSON[@]}" -X POST -d '{"oldPassword":"smoke-pass-123","newPassword":"smoke-pass-456"}' "$B/api/v1/auth/password")"
check "旧密码登录被拒" '"code":401' "$(curl -s "${JSON[@]}" -X POST -d '{"username":"smoke","password":"smoke-pass-123"}' "$B/api/v1/auth/login")"
LOGIN3="$(curl -s "${JSON[@]}" -X POST -d '{"username":"smoke","password":"smoke-pass-456"}' "$B/api/v1/auth/login")"
check "新密码登录成功" '"token":"vfs_' "$LOGIN3"

echo "=============================="
echo "PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]
