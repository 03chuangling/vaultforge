#!/usr/bin/env python3
"""模拟 App 端同步往返：
  登录（账号不存在则注册）→ pull(全量) → push(本地条目) → 设置写入 → pull(校验) → 字段比对

用法：python3 sync-roundtrip.py <base_url> <username> <password> <vault_data.json>
"""
import json
import sys
import urllib.error
import urllib.request


class APIError(Exception):
    def __init__(self, status: int, body):
        super().__init__(f"HTTP {status}: {body}")
        self.status = status
        self.body = body


def main() -> int:
    if len(sys.argv) < 5:
        print(__doc__)
        return 2
    base = sys.argv[1].rstrip("/")
    username = sys.argv[2]
    password = sys.argv[3]
    data_path = sys.argv[4]

    def call(method: str, path: str, payload=None, token=None):
        req = urllib.request.Request(base + path, method=method)
        if token:
            req.add_header("Authorization", "Bearer " + token)
        body = None
        if payload is not None:
            body = json.dumps(payload).encode("utf-8")
            req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, data=body, timeout=10) as resp:
                out = json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            raise APIError(e.code, e.read().decode("utf-8", "replace"))
        if out.get("code") != 0:
            raise APIError(200, out)
        return out.get("data")

    # 登录；账号不存在则注册（服务器无账号时开放注册）
    try:
        auth = call("POST", "/api/v1/auth/login", {"username": username, "password": password})
        print(f"登录成功: {auth['user']['username']}")
    except APIError as e:
        if e.status != 401:
            raise SystemExit(f"登录失败: {e}")
        try:
            auth = call("POST", "/api/v1/auth/register", {"username": username, "password": password})
            print(f"首次使用，已自动注册并登录: {auth['user']['username']}")
        except APIError as e2:
            raise SystemExit(f"登录失败（自动注册也未成功）: {e2}")
    token = auth["token"]

    with open(data_path, encoding="utf-8") as f:
        snap = json.load(f)
    items = snap.get("items", [])
    settings = snap.get("settings", {})
    print(f"本地数据: items={len(items)} settings_keys={sorted(settings.keys())}")

    pull0 = call("POST", "/api/v1/sync/pull", {"deviceId": "roundtrip-a", "since": 0}, token)
    print(f"初始服务端: items={len(pull0['items'])} tombstones={len(pull0['deletedIds'])}")

    if items:
        push = call("POST", "/api/v1/sync/push", {"deviceId": "roundtrip-a", "items": items}, token)
        print(f"推送: accepted={len(push['accepted'])} conflicts={len(push['conflicts'])}")
        if len(push["accepted"]) != len(items):
            raise SystemExit("存在未被接受的条目")
    if settings:
        call("PUT", "/api/v1/settings", settings, token)

    pull1 = call("POST", "/api/v1/sync/pull", {"deviceId": "roundtrip-b", "since": 0}, token)
    got = {it["id"]: it for it in pull1["items"]}
    for it in items:
        g = got.get(it["id"])
        if g is None:
            raise SystemExit(f"回读丢失条目 {it['id']}")
        for k in ("type", "name", "host", "address", "endpoint", "username"):
            if it.get(k, "") != g.get(k, ""):
                raise SystemExit(f"字段不一致 {it['id']}.{k}: {it.get(k)!r} != {g.get(k)!r}")
    srv_settings = call("GET", "/api/v1/settings", None, token)["settings"]
    for k, v in settings.items():
        if srv_settings.get(k) != v:
            raise SystemExit(f"设置不一致 {k}: {v!r} != {srv_settings.get(k)!r}")

    print(f"回读验证通过: {len(items)} 条条目与设置全部一致 ✅")
    return 0


if __name__ == "__main__":
    sys.exit(main())