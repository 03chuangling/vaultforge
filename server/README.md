# VaultForge Server —— 秘钥仓配套云端（骨架版 v0.2.0）

VaultForge 安卓端的配套服务端：提供**条目同步 + 设置镜像 + 统一契约**，为后续多设备、远程能力打底。

- 纯 Go 标准库实现（**零第三方依赖**），单文件二进制，可直接部署云服务器
- 响应契约与 App 本地接口完全一致：`{"code":0,"message":"ok","data":...}` + `Authorization: Bearer <token>`
- 条目字段与 App 端 `VaultItem` 一一对应，天然支持端云同步

## 架构

```
┌────────────┐   HTTP/JSON    ┌─────────────────────────┐  原子写入   ┌──────────────────────────┐
│ VaultForge │ ─────────────► │    vaultforge-server    │ ─────────► │ data/vaultforge_server   │
│  Android   │ ◄───────────── │  · Bearer 鉴权中间件     │ ◄───────── │ .json（全量快照）         │
└────────────┘  Bearer Token  │  · items / settings /   │            └──────────────────────────┘
                              │    sync 三组接口         │
                              └─────────────────────────┘
```

## 目录结构

```
server/
├── main.go                 入口：配置 → 存储 → 令牌 → HTTP 服务
├── internal/
│   ├── config/             环境变量配置（VF_ADDR / VF_DATA_DIR / VF_TOKEN）
│   ├── model/              数据模型：VaultItem 与同步契约（与 App 字段对齐）
│   ├── auth/               令牌加载 / 生成（vf_ + 24 hex）与常量时间校验
│   ├── store/              存储层：内存索引 + JSON 文件（原子写入，可换 DB）
│   └── api/
│       ├── server.go       路由表（Go 1.22 ServeMux，方法 + 路径模式）
│       ├── response.go     统一响应 {code, message, data}
│       ├── middleware.go   Bearer 鉴权 / 访问日志
│       ├── system.go       健康检查 / 服务信息
│       ├── items.go        条目 CRUD（对齐 App 本地接口语义）
│       ├── settings.go     设置镜像读写
│       └── sync.go         增量同步 pull / push（LWW）
├── scripts/smoke.sh        冒烟测试（启动临时实例跑全链路）
└── Makefile                build / run / cross / clean
```

## 快速开始

```bash
cd server
go build -o vaultforge-server .        # 构建（零依赖）
VF_DATA_DIR=./data ./vaultforge-server # 默认监听 :8787

# 首次启动自动生成访问令牌 → data/token.txt
TOKEN=$(cat data/token.txt)

curl http://127.0.0.1:8787/healthz
curl http://127.0.0.1:8787/api/v1
curl -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8787/api/v1/items
```

## 接口一览

| 方法 | 路径 | 鉴权 | 说明 | 状态 |
|---|---|---|---|---|
| GET | `/healthz` | 无 | 健康检查 | ✅ |
| GET | `/api/v1` | 无 | 服务信息（连通性检查） | ✅ |
| GET | `/api/v1/items?type=&q=&tag=` | Bearer | 条目列表（data 为数组，与 App 一致） | ✅ |
| POST | `/api/v1/items` | Bearer | 新建条目（message=created） | ✅ |
| GET | `/api/v1/items/{id}` | Bearer | 条目详情 | ✅ |
| PATCH | `/api/v1/items/{id}` | Bearer | 部分更新（白名单字段，message=updated） | ✅ |
| DELETE | `/api/v1/items/{id}` | Bearer | 软删除 → 墓碑（message=deleted） | ✅ |
| PUT | `/api/v1/items/{id}/tags` | Bearer | 覆盖标签 | ✅ |
| GET | `/api/v1/settings` | Bearer | 读取设置镜像 | ✅ |
| PUT | `/api/v1/settings` | Bearer | 合并设置镜像（浅合并） | ✅ |
| POST | `/api/v1/sync/pull` | Bearer | 增量拉取（since 游标） | ✅ |
| POST | `/api/v1/sync/push` | Bearer | 批量推送（LWW 冲突检测） | ✅ |
| — | `/api/v1/auth/*` | — | 账号体系 / 2FA | 🚧 路线图 |
| — | `/api/v1/devices` | — | 设备管理 | 🚧 路线图 |
| — | `/api/v1/agent/*` | — | 远程探测任务队列 | 🚧 路线图 |

## 同步模型（骨架版）

```jsonc
// pull 请求 → 响应
{"deviceId":"...","since":0}
// → {"code":0,"message":"ok","data":{
//      "serverTime":1756600000000,
//      "items":[ /* updatedAt > since 且未删除的条目 */ ],
//      "deletedIds":[ /* 该窗口内被删除的 id */ ],
//      "hasMore":false }}

// push 请求 → 响应
{"deviceId":"...","items":[ /* VaultItem 数组（可含 deleted:true 墓碑） */ ]}
// → {"code":0,"message":"ok","data":{
//      "accepted":["id..."],
//      "conflicts":[ {"id":"...","serverItem":{ /* 服务端版本 */ }} ]}}
```

- **版本号**：`updatedAt`（Unix 毫秒）；push 时 `incoming.updatedAt >= 服务端版本` 则接受，否则冲突返回服务端版本（客户端按 LWW 处理）
- **删除**：软删除保留墓碑（`deleted:true`），随 pull 的 `deletedIds` 下发；保留期策略 TODO
- **游标**：客户端流程 `pull(since=本地cursor) → push(dirty) → cursor=serverTime`

## 与 App 契约对齐

- 统一响应 `{code, message, data}`：成功 `code=0`；错误 `code` 与 HTTP 状态码一致（400/401/404/500）
- 鉴权 `Authorization: Bearer <token>`，错误文案与 App 相同
- `VaultItem` 字段一一对应（id/type/name/tags/createdAt/updatedAt/protocol/address/host/port/username/authMethod/secret/privateKey/endpoint/apiKey/demoCode/lastOk/lastLatencyMs/lastCheckedAt/lastMessage）
- 服务端扩展字段 `deleted`（墓碑标记）；App 端 JSON 配置了 `ignoreUnknownKeys`，可安全忽略
- PATCH 白名单与 App 的 PATCH 语义一致（name/tags/protocol/address/host/port/username/authMethod/secret/privateKey/endpoint/apiKey/demoCode）

## 部署

```bash
# 交叉编译到云服务器（Linux x86_64）
GOOS=linux GOARCH=amd64 go build -o dist/vaultforge-server-linux-amd64 .
```

systemd 示例：

```ini
[Unit]
Description=VaultForge Server
After=network.target

[Service]
ExecStart=/opt/vaultforge/vaultforge-server
Environment=VF_ADDR=127.0.0.1:8787
Environment=VF_DATA_DIR=/opt/vaultforge/data
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

nginx 反代（生产必须加 TLS）：

```nginx
location /api/ { proxy_pass http://127.0.0.1:8787; }
```

## 安全说明（重要）

- 骨架版为**静态令牌 + HTTP**：公网部署前必须加 TLS 反代（nginx/caddy）
- 数据当前**明文落盘**（条目中含 secret/privateKey 等敏感字段）：端到端加密（E2EE）已列入路线图，正式上线前建议仅在自托管内网使用
- 令牌文件 `data/token.txt`（0600）请勿提交 / 泄露

## 路线图

- [ ] 账号体系 / 2FA（Argon2id + TOTP，参考 KeyManager 设计）
- [ ] E2EE：服务端只存密文（`encrypted_blob`），不解析条目敏感字段
- [ ] 设备管理（列表 / 踢出 / 重命名）
- [ ] Agent 任务队列（远程探测下发，复用 App 的探测能力）
- [ ] 限流与审计日志
- [ ] PostgreSQL 替换 JSON 文件（Store 层已隔离，改动小）
- [ ] Web 管理控制台
