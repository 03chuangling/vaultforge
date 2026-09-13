#!/usr/bin/env python3
"""模拟 App 端同步往返：
  pull(全量) → push(本地条目) → 设置写入 → pull(校验) → 字段比对

用法：python3 sync-roundtrip.py <base_url> <token> <vault_data.json>
"""
import json
import sys
import urllib.error
import urllib.request


def main() -> int:
    if len(sys.argv) < 4:
        print(__doc__)
        return 2
    base = sys.argv[1].rstrip("/")
    token = sys.argv[2]
    data_path = sys.argv[3]

    def call(method: str, path: str, payload=None):
        req = urllib.request.Request(base + path, method=method)
        req.add_header("Authorization", "Bearer " + token)
        body = None
        if payload is not None:
            body = json.dumps(payload).encode("utf-8")
            req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, data=body, timeout=10) as resp:
                out = json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            print(f"HTTP {e.code}: {e.read().decode('utf-8', 'replace')}")
            raise
        if out.get("code") != 0:
            raise SystemExit(f"接口返回异常: {out}")
        return out.get("data")

    with open(data_path, encoding="utf-8") as f:
        snap = json.load(f)
    items = snap.get("items", [])
    settings = snap.get("settings", {})
    print(f"本地数据: items={len(items)} settings_keys={sorted(settings.keys())}")

    pull0 = call("POST", "/api/v1/sync/pull", {"deviceId": "roundtrip-a", "since": 0})
    print(f"初始服务端: items={len(pull0['items'])} tombstones={len(pull0['deletedIds'])}")

    if items:
        push = call("POST", "/api/v1/sync/push", {"deviceId": "roundtrip-a", "items": items})
        print(f"推送: accepted={len(push['accepted'])} conflicts={len(push['conflicts'])}")
        if len(push["accepted"]) != len(items):
            raise SystemExit("存在未被接受的条目")
    if settings:
        call("PUT", "/api/v1/settings", settings)

    pull1 = call("POST", "/api/v1/sync/pull", {"deviceId": "roundtrip-b", "since": 0})
    got = {it["id"]: it for it in pull1["items"]}
    for it in items:
        g = got.get(it["id"])
        if g is None:
            raise SystemExit(f"回读丢失条目 {it['id']}")
        for k in ("type", "name", "host", "address", "endpoint", "username"):
            if it.get(k, "") != g.get(k, ""):
                raise SystemExit(f"字段不一致 {it['id']}.{k}: {it.get(k)!r} != {g.get(k)!r}")
    srv_settings = call("GET", "/api/v1/settings")["settings"]
    for k, v in settings.items():
        if srv_settings.get(k) != v:
            raise SystemExit(f"设置不一致 {k}: {v!r} != {srv_settings.get(k)!r}")

    print(f"回读验证通过: {len(items)} 条条目与设置全部一致 ✅")
    return 0


if __name__ == "__main__":
    sys.exit(main())