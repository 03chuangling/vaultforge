# VaultForge Server —— 秘钥仓配套云端（v0.4.0 · Bitwarden 对接版）

VaultForge 安卓端的配套服务端：**Web 管理面板 + 账号登录 + 条目同步 + 服务端化全部 App 能力 + Bitwarden 对接**。

- **Web 管理面板**：浏览器直接打开即用（单页应用，资源全内嵌、无外部 CDN 依赖），覆盖 App 全部功能——条目管理、探测巡检、SSH 终端、服务器指标、Docker 容器、远程文件管理、设置
- **服务端专属功能**：仪表盘统计、会话管理、修改密码（吊销其他设备）、数据导出、批量操作
- **Bitwarden / Vaultwarden 对接**：连接自建密码库，拉取并解密全部条目（PBKDF2 / Argon2id 双 KDF；主密码不落地）
- **服务端全能力**：内置真 SSH 客户端（命令执行 / 指标采集 / Docker 管理 / SFTP 文件）、WebDAV 客户端、探测引擎（file / ssh / api + 演示代码执行）
- **账号登录**：注册 / 登录签发会话令牌（PBKDF2-HMAC-SHA256 密码哈希，数据按账号隔离）
- 响应契约与 App 本地接口完全一致：`{"code":0,"message":"ok","data":...}` + `Authorization: Bearer <会话令牌>`
- 条目字段与 App 端 `VaultItem` 一一对应，天然支持端云同步

> 依赖说明：v0.3.0 起引入 SSH / SFTP 能力，需要两个第三方依赖（`golang.org/x/crypto`、`github.com/pkg/sftp`），是服务端首次打破"纯标准库"。国内构建建议 `GOPROXY=https://goproxy.cn GOSUMDB=off`。v0.4.0 的 Bitwarden 对接复用 `golang.org/x/crypto`（PBKDF2 / Argon2id），无新增依赖。

## 架构

```
┌────────────┐   HTTP/JSON    ┌──────────────────────────────┐    SSH/SFTP    ┌────────────────┐
│ VaultForge │ ─────────────► │       vaultforge-server      │ ─────────────► │ 你的服务器集群   │
│  Android   │ ◄───────────── │  · 账号 / 会话鉴权             │ ◄───────────── │ (命令/指标/     │
└────────────┘   会话令牌      │  · Web 管理面板（内嵌 SPA）    │   docker exec  │  Docker/文件)   │
       ▲                      │  · items / settings / sync   │    WebDAV      └────────────────┘
       │     浏览器            │  · sshx 执行 / probe 探测引擎  │ ─────────────►
┌────────────┐ ─────────────► │                              │    HTTP(S)
│   你的浏览器 │                └──────────────────────────────┘
└────────────┘                        │ 原子写入
                                      ▼
                          data/vaultforge_server.json（全量快照）
```

## Web 管理面板

启动服务后浏览器访问 `http://<主机>:8787` 即可：

| 页面 | 能力 |
|---|---|
| 登录 / 注册 | 首次使用引导创建管理员；此后账号登录 |
| 仪表盘 | 条目总数 / 类型分布 / 巡检状态 / 热门标签 / 账号与数据统计 / 运行时长；一键全量巡检、数据导出 |
| 条目列表 | 搜索、类型筛选、标签筛选；批量模式（删除 / 打标签 / 移除标签 / 批量巡检）；单条快速检测 |
| 条目详情 | 每类条目信息展示（敏感字段可点开）、立即检测、状态卡片；SSH 条目：**指标图表（环形 / 折线，自动刷新）**、**Docker 容器管理（启动/停止/重启/日志/终端）**；文件管理入口 |
| 终端 | 服务器命令模式 + `docker exec` 容器模式；快捷指令；命令历史（↑/↓） |
| 文件管理 | SSH(SFTP) / SFTP / WebDAV 条目的目录浏览、上传、下载、删除 |
| 设置 | 图表样式 / 采样间隔偏好；修改密码；登录会话列表与吊销；数据导出；服务信息 |
| Bitwarden 对接 | 连接 Bitwarden / Vaultwarden 拉取解密条目：搜索、密码打码 / 查看 / 复制；默认服务器预填自建实例 |

面板偏好（图表样式 / 采样间隔）与 App 端设置镜像互通（`/api/v1/settings`）。

## 目录结构

```
server/
├── main.go                 入口：配置 → 存储 → HTTP 服务
├── internal/
│   ├── config/             环境变量配置（VF_ADDR / VF_DATA_DIR / VF_ALLOW_REGISTER / VF_PBKDF2_ITERS）
│   ├── model/              数据模型：VaultItem、同步契约、账号与面板 DTO（与 App 字段对齐）
│   ├── auth/               密码哈希（PBKDF2-HMAC-SHA256）与会话令牌工具
│   ├── store/              存储层：账号 / 会话 / 按用户隔离的保险库（JSON 原子写入，可换 DB）
│   ├── sshx/               SSH 客户端：连接复用、命令执行、指标采集、Docker、SFTP
│   ├── probe/              探测引擎：file / ssh / api + 演示代码（curl/python/js）执行器
│   ├── bitwarden/          Bitwarden / Vaultwarden 客户端（PBKDF2 + Argon2id、EncString 解密、sync 拉取）
│   └── api/
│       ├── server.go       路由表（Go 1.22 ServeMux，方法 + 路径模式）
│       ├── response.go     统一响应 {code, message, data}
│       ├── middleware.go   会话鉴权（Bearer）/ 访问日志 / 请求体上限
│       ├── auth.go         注册 / 登录 / 登出 / 账号信息
│       ├── account.go      会话列表 / 吊销会话 / 修改密码
│       ├── ratelimit.go    注册 / 登录限流
│       ├── system.go       健康检查 / 服务信息
│       ├── items.go        条目 CRUD（对齐 App 本地接口语义）
│       ├── batch.go        批量操作（delete / tag / untag / check）
│       ├── checks.go       立即探测并写回结果
│       ├── sshops.go       SSH 执行 / 指标 / Docker API
│       ├── files.go        远程文件 API（SFTP / WebDAV 分发）
│       ├── webdav.go       WebDAV 轻量客户端（PROPFIND / GET / PUT / DELETE）
│       ├── stats.go        仪表盘统计
│       ├── export.go       数据导出
│       ├── settings.go     设置镜像读写
│       ├── sync.go         增量同步 pull / push（LWW）
│       ├── bitwarden.go    Bitwarden 对接：拉取并解密密码库条目
│       ├── webfs.go        Web 面板静态资源（go:embed）
│       └── static/         Web 面板前端（index.html + assets：纯原生 JS/CSS）
├── cmd/
│   └── bwprobe/            Bitwarden 协议端到端探针（注册 / 登录 / 解密 / sync / 建条目全链路自检）
├── scripts/
│   ├── smoke.sh            冒烟测试（53 项：账号/同步/面板/条目动作/Bitwarden 接口全链路）
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
export GOPROXY=https://goproxy.cn GOSUMDB=off   # 国内构建建议（拉取 SSH/SFTP 依赖）
go build -o vaultforge-server .
VF_DATA_DIR=./data ./vaultforge-server          # 默认监听 :8787
```

打开浏览器 → `http://127.0.0.1:8787` → 首次使用注册管理员账号即可开始。

命令行验证：

```bash
curl http://127.0.0.1:8787/healthz
curl http://127.0.0.1:8787/api/v1

# 首次使用：注册账号（第一个账号自动继承旧数据，响应中直接带回令牌）
curl -X POST http://127.0.0.1:8787/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"your-pass-123"}'

curl -H "Authorization: Bearer <token>" http://127.0.0.1:8787/api/v1/items
curl -H "Authorization: Bearer <token>" http://127.0.0.1:8787/api/v1/stats
```

## 接口一览

| 方法 | 路径 | 鉴权 | 说明 | 状态 |
|---|---|---|---|---|
| GET | `/` | 无 | **Web 管理面板（HTML）** | ✅ |
| GET | `/assets/*` | 无 | 面板静态资源（内嵌） | ✅ |
| GET | `/healthz` | 无 | 健康检查 | ✅ |
| GET | `/api/v1` | 无 | 服务信息（连通性检查） | ✅ |
| POST | `/api/v1/auth/register` | 无 | 注册账号（首个账号自动继承旧数据） | ✅ |
| POST | `/api/v1/auth/login` | 无 | 登录 → 签发会话令牌（30 天） | ✅ |
| POST | `/api/v1/auth/logout` | Bearer | 吊销当前会话 | ✅ |
| GET | `/api/v1/auth/me` | Bearer | 当前账号信息 | ✅ |
| GET | `/api/v1/auth/sessions` | Bearer | 会话列表（标注当前） | ✅ 新增 |
| DELETE | `/api/v1/auth/sessions/{id}` | Bearer | 吊销指定会话 | ✅ 新增 |
| POST | `/api/v1/auth/password` | Bearer | 修改密码（吊销其他会话） | ✅ 新增 |
| GET | `/api/v1/items?type=&q=&tag=` | Bearer | 条目列表 | ✅ |
| POST | `/api/v1/items` | Bearer | 新建条目 | ✅ |
| POST | `/api/v1/items/batch` | Bearer | 批量操作：delete / tag / untag / check | ✅ 新增 |
| GET | `/api/v1/items/{id}` | Bearer | 条目详情 | ✅ |
| PATCH | `/api/v1/items/{id}` | Bearer | 部分更新（白名单字段） | ✅ |
| DELETE | `/api/v1/items/{id}` | Bearer | 软删除 → 墓碑 | ✅ |
| PUT | `/api/v1/items/{id}/tags` | Bearer | 覆盖标签 | ✅ |
| POST | `/api/v1/items/{id}/check` | Bearer | 立即探测并写回结果 | ✅ 新增 |
| POST | `/api/v1/items/{id}/exec` | Bearer | 执行命令（`{command, container}`） | ✅ 新增 |
| GET | `/api/v1/items/{id}/metrics` | Bearer | SSH 服务器指标快照 | ✅ 新增 |
| GET | `/api/v1/items/{id}/docker` | Bearer | Docker 容器列表 | ✅ 新增 |
| POST | `/api/v1/items/{id}/docker` | Bearer | 容器操作（start/stop/restart/logs） | ✅ 新增 |
| GET | `/api/v1/items/{id}/files?path=` | Bearer | 远程目录列表 | ✅ 新增 |
| GET | `/api/v1/items/{id}/files/download?path=` | Bearer | 下载文件 | ✅ 新增 |
| POST | `/api/v1/items/{id}/files/upload?path=&name=` | Bearer | 上传文件（body 即文件内容） | ✅ 新增 |
| POST | `/api/v1/items/{id}/files/delete` | Bearer | 删除文件 / 目录 | ✅ 新增 |
| GET | `/api/v1/stats` | Bearer | 仪表盘统计 | ✅ 新增 |
| GET | `/api/v1/export` | Bearer | 数据导出（JSON 下载） | ✅ 新增 |
| GET | `/api/v1/settings` | Bearer | 读取设置镜像 | ✅ |
| PUT | `/api/v1/settings` | Bearer | 合并设置镜像 | ✅ |
| POST | `/api/v1/sync/pull` | Bearer | 增量拉取（since 游标） | ✅ |
| POST | `/api/v1/sync/push` | Bearer | 批量推送（LWW 冲突检测） | ✅ |
| POST | `/api/v1/bitwarden/pull` | Bearer | 连接 Bitwarden / Vaultwarden，拉取并解密密码库条目 | ✅ 新增 |
| — | `/api/v1/devices` | — | 设备管理 | 🚧 路线图 |
| — | `/api/v1/agent/*` | — | 远程探测任务队列 | 🚧 路线图 |

## 账号与鉴权

- 注册策略：服务器**没有任何账号**时开放注册（引导第一个管理员，自动继承旧数据）；此后默认关闭，可用 `VF_ALLOW_REGISTER=1` 放开（多用户模式）
- 密码存储：PBKDF2-HMAC-SHA256 加盐哈希（默认 120,000 次迭代，可用 `VF_PBKDF2_ITERS` 覆盖），**不存明文**
- 会话：登录签发 `vfs_` 前缀令牌（30 天有效），服务端只存令牌的 SHA-256；`logout` / 会话吊销即时生效
- 修改密码会**自动吊销其他设备的会话**（当前会话保留）
- 数据隔离：条目与设置均按账号隔离，互不可见
- 限流：注册 5 次/分钟/IP、登录 15 次/分钟/IP

## 服务端能力细节

- **探测引擎**：与 App 语义一致——file（WebDAV / TCP 握手延迟）、ssh（TCP 握手 + 完整连接校验）、api（优先执行演示代码 curl/python/js，回退端点 HTTP 探测）；巡检结果写回条目（`lastOk / lastLatencyMs / lastCheckedAt / lastMessage`）并随同步下发
- **SSH 连接复用**：按条目缓存连接，失效自动重建；条目配置变更 / 删除时自动清理缓存
- **Bitwarden 对接**：完整协议链路——prelogin 取 KDF 参数 → 密钥派生 → 登录解密用户密钥 → `/sync` 全量拉取 → 逐条解密（名称 / 用户名 / 密码 / URI / 备注，含文件夹与账户资料）；支持 PBKDF2-SHA256 与 Argon2id；主密码仅参与本次请求的密钥派生，**不存储、不写日志**
- **Docker 容器名**：白名单正则校验（`^[A-Za-z0-9_.\-]{1,80}$`）防注入；`docker exec` 经单引号转义
- **远程文件**：SSH 条目走内置 SFTP；`file` 条目按协议分发（`sftp` / `webdav`）；WebDAV 支持 Basic 认证与自签名证书
- **上传 / 下载上限**：请求体 16MB（面板文件管理同限）

## 同步模型

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
- **删除**：软删除保留墓碑（`deleted:true`），随 pull 的 `deletedIds` 下发
- **游标**：客户端流程 `pull(since=本地cursor) → push(dirty) → cursor=serverTime`

## 与 App 契约对齐

- 统一响应 `{code, message, data}`：成功 `code=0`；错误 `code` 与 HTTP 状态码一致（400/401/403/404/409/429/500/502）
- 鉴权 `Authorization: Bearer <会话令牌>`（由 `/auth/login` 签发）
- `VaultItem` 字段一一对应；服务端扩展 `deleted`（墓碑）；PATCH 白名单与 App 一致
- 面板动作 DTO 与 App 对齐：`MetricsData`（cpu/mem/net/disk/load/kernel）、`DockerContainerInfo`、`FileEntry`

## 部署

```bash
# 方式一：本机构建 + 打包（amd64 / arm64 两个 tarball + SHA256SUMS）
./scripts/release.sh
# 产物 dist/：
#   vaultforge-server-linux-amd64 / -arm64              裸二进制
#   vaultforge-server-v0.4.0-linux-amd64.tar.gz          发布包（含 install.sh / systemd 单元 / README / smoke.sh / LICENSE）
#   SHA256SUMS

# 方式二：部署到云服务器（install.sh 会装到 /opt/vaultforge 并注册 systemd 服务）
scp dist/vaultforge-server-v0.4.0-linux-amd64.tar.gz root@<server>:/tmp/
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
location / {
    proxy_pass http://127.0.0.1:8787;
    client_max_body_size 32m;      # 文件上传（服务端限 16MB，留余量）
    proxy_read_timeout 120s;       # SSH 命令执行可能较慢
}
```

> Web 面板与 API 同端口同源，反代一个 `location /` 即可（`/api/` 自然包含在内）。

## 安全说明（重要）

- **账号登录 + 会话令牌**：密码 PBKDF2-HMAC-SHA256 哈希存储；公网部署前必须加 TLS 反代（nginx/caddy）
- **数据明文落盘**：条目中含 secret / privateKey 等敏感字段，端到端加密（E2EE）在路线图中，建议仅在自托管环境使用
- **SSH 主机指纹不做校验**（与 App 行为一致，`InsecureIgnoreHostKey`）；WebDAV 客户端信任自签名证书——均为方便用户管理自有机器的取舍，介意请勿使用
- 上传 / 下载限 16MB；Docker 容器名白名单校验；批量操作上限 200 条 / 次
- 注册策略：仅第一个账号自由注册；多用户需设置 `VF_ALLOW_REGISTER=1`
- 升级说明：v0.1.0 / v0.2.0 / v0.3.0 数据自动兼容（首账号继承旧数据）

## 测试

```bash
# 冒烟测试（53 项：账号/鉴权/同步/条目动作/面板静态资源/Bitwarden 接口）
go build -o vaultforge-server . && ./scripts/smoke.sh

# 端云同步往返（模拟 App 行为，支持已注册账号或自动注册）
python3 scripts/sync-roundtrip.py --base http://127.0.0.1:8787 --user admin --pass your-pass-123
```

## 路线图

- [x] 账号体系：注册 / 登录 / 会话令牌（PBKDF2 密码哈希，数据按账号隔离）
- [x] 基础限流（注册 5/min、登录 15/min）
- [x] **Web 管理控制台（v0.3.0）**：覆盖 App 全部能力 + 服务端专属功能
- [x] **Bitwarden / Vaultwarden 对接（v0.4.0）**：连接密码库拉取并解密条目（Web 面板 + API）
- [x] 服务端化能力：SSH 终端 / 指标 / Docker / 文件管理 / 探测引擎
- [ ] App 对接云端同步 UI（填地址 + 账号密码即可同步）
- [ ] 2FA（TOTP）/ 登录失败锁定 / 审计日志
- [ ] Bitwarden 写入支持（创建 / 更新 / 删除条目回写密码库）
- [ ] E2EE：服务端只存密文（`encrypted_blob`）
- [ ] 设备管理（列表 / 踢出 / 重命名）
- [ ] Agent 任务队列（远程探测下发）
- [ ] PostgreSQL 替换 JSON 文件（Store 层已隔离，改动小）
