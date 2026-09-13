# VaultForge Server —— 秘钥仓配套云端（v0.2.0 · 账号登录版）

VaultForge 安卓端的配套服务端：**账号登录 + 条目同步 + 设置镜像 + 统一契约**，为多设备、远程能力打底。

- 纯 Go 标准库实现（**零第三方依赖**），单文件二进制，可直接部署云服务器
- **账号登录**：注册 / 登录签发会话令牌（PBKDF2-HMAC-SHA256 密码哈希，数据按账号隔离）
- 响应契约与 App 本地接口完全一致：`{"code":0,"message":"ok","data":...}` + `Authorization: Bearer <会话令牌>`
- 条目字段与 App 端 `VaultItem` 一一对应，天然支持端云同步

## 架构

```
┌────────────┐   HTTP/JSON    ┌─────────────────────────┐  原子写入   ┌──────────────────────────┐
│ VaultForge │ ─────────────► │    vaultforge-server    │ ─────────► │ data/vaultforge_server   │
│  Android   │ ◄───────────── │  · 账号登录 / 会话鉴权    │ ◄───────── │ .json（全量快照）         │
└────────────┘   会话令牌      │  · items / settings /   │            └──────────────────────────┘
                              │    sync 三组接口         │
                              └─────────────────────────┘
```

## 目录结构

```
server/
├── main.go                 入口：配置 → 存储 → HTTP 服务
├── internal/
│   ├── config/             环境变量配置（VF_ADDR / VF_DATA_DIR / VF_ALLOW_REGISTER / VF_PBKDF2_ITERS）
│   ├── model/              数据模型：VaultItem、同步契约、账号 DTO（与 App 字段对齐）
│   ├── auth/               密码哈希（PBKDF2-HMAC-SHA256）与会话令牌工具
│   ├── store/              存储层：账号 / 会话 / 按用户隔离的保险库（JSON 原子写入，可换 DB）
│   └── api/
│       ├── server.go       路由表（Go 1.22 ServeMux，方法 + 路径模式）
│       ├── response.go     统一响应 {code, message, data}
│       ├── middleware.go   会话鉴权（Bearer）/ 访问日志
│       ├── auth.go         注册 / 登录 / 登出 / 账号信息
│       ├── ratelimit.go    注册 / 登录限流
│       ├── system.go       健康检查 / 服务信息
│       ├── items.go        条目 CRUD（对齐 App 本地接口语义）
│       ├── settings.go     设置镜像读写
│       └── sync.go         增量同步 pull / push（LWW）
├── scripts/
│   ├── smoke.sh            冒烟测试（注册 / 登录 + 全链路）
│   ├── release.sh          发布打包（amd64/arm64 tarball + SHA256SUMS）
│   └── sync-roundtrip.py   模拟 App 同步往返验证（登录或自动注册）
├── deploy/
│   ├── install.sh          服务器一键安装（systemd）
│   └── vaultforge-server.service
└── Makefile                build / run / cross / release / clean
```

## 快速开始

```bash
cd server
go build -o vaultforge-server .        # 构建（零依赖）
VF_DATA_DIR=./data ./vaultforge-server # 默认监听 :8787

# 首次使用：注册账号（第一个账号自动继承旧数据，响应中直接带回令牌）
# 之后登录：POST /api/v1/auth/login 同样返回令牌
curl -X POST http://127.0.0.1:8787/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"your-pass-123"}'

curl http://127.0.0.1:8787/healthz
curl http://127.0.0.1:8787/api/v1
curl -H "Authorization: Bearer <token>" http://127.0.0.1:8787/api/v1/items
```

## 接口一览

| 方法 | 路径 | 鉴权 | 说明 | 状态 |
|---|---|---|---|---|
| GET | `/healthz` | 无 | 健康检查 | ✅ |
| GET | `/api/v1` | 无 | 服务信息（连通性检查） | ✅ |
| POST | `/api/v1/auth/register` | 无 | 注册账号（首个账号自动继承旧数据） | ✅ |
| POST | `/api/v1/auth/login` | 无 | 登录 → 签发会话令牌（30 天） | ✅ |
| POST | `/api/v1/auth/logout` | Bearer | 吊销当前会话 | ✅ |
| GET | `/api/v1/auth/me` | Bearer | 当前账号信息 | ✅ |
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
| — | `/api/v1/devices` | — | 设备管理 | 🚧 路线图 |
| — | `/api/v1/agent/*` | — | 远程探测任务队列 | 🚧 路线图 |

## 账号与鉴权

- 注册策略：服务器**没有任何账号**时开放注册（引导第一个管理员，自动继承旧数据）；此后默认关闭，可用 `VF_ALLOW_REGISTER=1` 放开（多用户模式）
- 密码存储：PBKDF2-HMAC-SHA256 加盐哈希（默认 120,000 次迭代，可在哈希时用 `VF_PBKDF2_ITERS` 覆盖），**不存明文**
- 会话：登录签发 `vfs_` 前缀令牌（30 天有效），服务端只存令牌的 SHA-256；`logout` 即时吊销
- 数据隔离：条目与设置均按账号隔离，互不可见
- 限流：注册 5 次/分钟/IP、登录 15 次/分钟/IP

## 同步模型（v0.2.0）

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

- 统一响应 `{code, message, data}`：成功 `code=0`；错误 `code` 与 HTTP 状态码一致（400/401/403/404/409/429/500）
- 鉴权 `Authorization: Bearer <会话令牌>`（由 `/auth/login` 签发，30 天有效）；错误文案与 App 相同
- `VaultItem` 字段一一对应（id/type/name/tags/createdAt/updatedAt/protocol/address/host/port/username/authMethod/secret/privateKey/endpoint/apiKey/demoCode/lastOk/lastLatencyMs/lastCheckedAt/lastMessage）
- 服务端扩展字段 `deleted`（墓碑标记）；App 端 JSON 配置了 `ignoreUnknownKeys`，可安全忽略
- PATCH 白名单与 App 的 PATCH 语义一致（name/tags/protocol/address/host/port/username/authMethod/secret/privateKey/endpoint/apiKey/demoCode）

## 部署

```bash
# 方式一：本机构建 + 打包（amd64 / arm64 两个 tarball + SHA256SUMS）
./scripts/release.sh
# 产物 dist/：
#   vaultforge-server-linux-amd64 / -arm64              裸二进制
#   vaultforge-server-v0.2.0-linux-amd64.tar.gz          发布包（含 install.sh / systemd 单元 / README / smoke.sh / LICENSE）
#   SHA256SUMS

# 方式二：部署到云服务器（install.sh 会装到 /opt/vaultforge 并注册 systemd 服务）
scp dist/vaultforge-server-v0.2.0-linux-amd64.tar.gz root@<server>:/tmp/
ssh root@<server> 'cd /tmp && tar xzf vaultforge-server-*.tar.gz && cd vaultforge-server-*/ && ./install.sh'
```

systemd 单元与 nginx TLS 反代示例：

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

```nginx
location /api/ { proxy_pass http://127.0.0.1:8787; }
```

## 安全说明（重要）

- **账号登录 + 会话令牌**：密码以 PBKDF2-HMAC-SHA256（默认 120k 次迭代，可用 `VF_PBKDF2_ITERS` 覆盖）加盐哈希存储；公网部署前必须加 TLS 反代（nginx/caddy）
- 数据当前**明文落盘**（条目中含 secret/privateKey 等敏感字段）：端到端加密（E2EE）已列入路线图，正式上线前建议仅在自托管内网使用
- 注册策略：仅允许第一个账号自由注册（引导管理员）；需要多用户时设置 `VF_ALLOW_REGISTER=1`
- 升级说明：v0.1.0 的旧数据文件（全局 items/settings）会自动迁移，由**第一个注册的账号继承**

## 路线图

- [x] 账号体系：注册 / 登录 / 会话令牌（PBKDF2 密码哈希，数据按账号隔离）
- [ ] 2FA（TOTP）/ 登录失败锁定 / 审计日志
- [ ] E2EE：服务端只存密文（`encrypted_blob`），不解析条目敏感字段
- [ ] 设备管理（列表 / 踢出 / 重命名）
- [ ] Agent 任务队列（远程探测下发，复用 App 的探测能力）
- [x] 基础限流（注册 5/min、登录 15/min）
- [ ] PostgreSQL 替换 JSON 文件（Store 层已隔离，改动小）
- [ ] Web 管理控制台
