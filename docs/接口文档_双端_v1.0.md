# VaultForge 接口文档（网页端 + APP 端 · 合订版）

> **版本**：v1.0 ｜ **日期**：2026-09-14
> **适用组件**：VaultForge Server v0.4.0 ｜ VaultForge Android App v0.3.0 ｜ bwctl v0.1
> **说明**：本文档覆盖「秘钥仓 VaultForge」的三大接口通道 —— ① VaultForge Server HTTP API（网页端与 APP 端共用）；② Bitwarden / Vaultwarden 上游对接协议（Server、APP、bwctl 共用）；③ APP 本地 HTTP API（供 AI 助手 / 脚本调用）。

---

## 目录

1. 总览
2. 公共约定
3. Server HTTP API（/api/v1）
4. Bitwarden / Vaultwarden 上游协议
5. 网页端（Web 管理面板）对接说明
6. APP 端对接说明
7. 错误与排障
8. 变更记录

---

## 1. 总览

### 1.1 组件与通信通道

```
┌─────────────┐        ① /api/v1（Bearer 会话令牌）        ┌────────────────────────┐
│  网页端      │ ◄──────────────────────────────────────► │  VaultForge Server     │
│（Web 管理面板）│                                         │  v0.4.0（Go 单二进制）   │
└─────────────┘                                          │  · Web 面板（内嵌 SPA）  │
┌─────────────┐        ① /api/v1（同上，云端同步）          │  · 账号 / 会话 / 条目     │
│  APP 端      │ ◄──────────────────────────────────────► │  · 同步 / 设置 / 导出     │
│（Android）   │                                          │  · SSH / 文件 / 探测      │
└──────┬──────┘                                          └───────────┬────────────┘
       │         ② Bitwarden 协议（prelogin / token / sync）           │ ②（同协议）
       │ ◄─────────────────────────────────────────────────────────┘
       ▼
┌───────────────────────────┐
│  Bitwarden / Vaultwarden   │   （自建 bit.bdshjgg.com / 官方实例等）
└───────────────────────────┘

APP 另有 ③ 本地 API：http://127.0.0.1:8737（供 AI / 脚本）
```

| 通道 | 端点提供方 | 使用者 | 鉴权 |
|---|---|---|---|
| ① Server API | VaultForge Server | 网页端、APP 端 | `Authorization: Bearer <会话令牌>` |
| ② Bitwarden 协议 | Bitwarden / Vaultwarden | Server、APP、bwctl | 登录换取的 `access_token`（Bearer） |
| ③ APP 本地 API | APP 内置服务（127.0.0.1:8737） | AI 助手、本机脚本 | `Authorization: Bearer <App 本地 Token>` |

### 1.2 版本矩阵

| 组件 | 版本 | 说明 |
|---|---|---|
| VaultForge Server | v0.4.0 | Bitwarden 对接版；Web 面板 + App 全能力的服务端化 |
| VaultForge App（Android） | v0.3.0 | Bitwarden 拉取页（拉取 / 预览 / 一键导入） |
| bwctl（AI 秘钥 CLI） | v0.1 | 命令行拉取 / 检索 Bitwarden 密码库（与双端同一加密实现） |

---
## 2. 公共约定

### 2.1 Base URL

| 环境 | Base URL |
|---|---|
| Server · 生产 | `https://vf.bdshjgg.com` |
| Server · 本地 | `http://<主机>:8787`（默认端口） |
| APP 本地 API | `http://127.0.0.1:8737` |
| Bitwarden 上游 | 用户填写（如自建 `https://bit.bdshjgg.com`；官方 `https://vault.bitwarden.com`） |

### 2.2 鉴权

- 除公开端点外，所有请求需携带：`Authorization: Bearer <会话令牌>`。
- 令牌由注册 / 登录接口签发；`/auth/logout` 吊销当前会话。
- 注册 / 登录端点均有限流；**登录 15 次 / 分钟 / IP**，超限返回 429。

### 2.3 统一响应包裹

```json
{ "code": 0, "message": "ok", "data": { } }
```

- 成功：`code = 0`；`message` 可为 `ok` / `created` / `updated` / `deleted` 等。
- 失败：`code` 与 HTTP 状态码一致（400 / 401 / 404 / 429 / 500），`data` 省略，`message` 为中文文案。
- 编码：`application/json; charset=utf-8`。

```bash
# 调用示例
curl -X POST https://vf.bdshjgg.com/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"********"}'
```

---

## 3. Server HTTP API（/api/v1）

### 3.1 端点总表

**公开**

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/` | Web 管理面板（SPA） |
| GET | `/assets/*` | 面板静态资源（内嵌） |
| GET | `/healthz` | 健康检查 |
| GET | `/api/v1` | 服务信息（连通性检查） |
| POST | `/api/v1/auth/register` | 注册 |
| POST | `/api/v1/auth/login` | 登录（签发会话令牌） |

**认证（Bearer）**

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/auth/logout` | 退出登录（吊销当前会话） |
| GET | `/api/v1/auth/me` | 当前账号信息 |
| GET | `/api/v1/auth/sessions` | 会话列表 |
| DELETE | `/api/v1/auth/sessions/{id}` | 吊销指定会话 |
| POST | `/api/v1/auth/password` | 修改密码 |

**条目**

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/items?type=&q=&tag=` | 条目列表 |
| POST | `/api/v1/items` | 新建条目 |
| POST | `/api/v1/items/batch` | 批量操作（delete / tag / untag / check） |
| GET | `/api/v1/items/{id}` | 条目详情 |
| PATCH | `/api/v1/items/{id}` | 部分更新（白名单字段） |
| DELETE | `/api/v1/items/{id}` | 软删除（墓碑，供同步下发） |
| PUT | `/api/v1/items/{id}/tags` | 覆盖标签 |
| POST | `/api/v1/items/{id}/check` | 立即探测并写回结果 |

**同步 / 设置 / 统计**

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/sync/pull` | 增量拉取 |
| POST | `/api/v1/sync/push` | 批量推送（LWW） |
| GET | `/api/v1/settings` | 读取设置镜像 |
| PUT | `/api/v1/settings` | 浅合并设置镜像 |
| GET | `/api/v1/stats` | 仪表盘统计 |
| GET | `/api/v1/export` | 全量数据导出（JSON 下载） |

**Bitwarden 对接**

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/bitwarden/pull` | 拉取并解密 Bitwarden 密码库 ★ |

**SSH 运行 / 文件**

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/items/{id}/exec` | 执行命令（服务器 / Docker 容器内） |
| GET | `/api/v1/items/{id}/metrics` | SSH 服务器指标快照 |
| GET | `/api/v1/items/{id}/docker` | Docker 容器列表 |
| POST | `/api/v1/items/{id}/docker` | 容器操作（start / stop / restart / logs） |
| GET | `/api/v1/items/{id}/files?path=` | 目录列表（SFTP / WebDAV） |
| GET | `/api/v1/items/{id}/files/download?path=` | 下载文件 |
| POST | `/api/v1/items/{id}/files/upload?path=&name=` | 上传文件 |
| POST | `/api/v1/items/{id}/files/delete` | 删除文件 / 目录 |

---### 3.2 认证接口

**注册** `POST /api/v1/auth/register`

```json
// 请求
{ "username": "admin", "password": "********" }
// 响应
{ "code": 0, "message": "created",
  "data": { "user": { "id": "u_xxx", "username": "admin", "createdAt": 1789293745648 },
            "token": "<会话令牌>", "expiresAt": 1789380145648 } }
```

**登录** `POST /api/v1/auth/login` — 请求同上；响应 `message = ok`，`data` 结构相同；失败 401 `用户名或密码错误`。

**退出** `POST /api/v1/auth/logout` → `data: null`。

**当前账号** `GET /api/v1/auth/me` → `{ "user": { … } }`。

**会话列表** `GET /api/v1/auth/sessions` → `[ { "id": "s_xxx", "current": true, "createdAt": …, "expiresAt": … } ]`。

**吊销会话** `DELETE /api/v1/auth/sessions/{id}`（不能吊销当前会话）。

**修改密码** `POST /api/v1/auth/password`，body `{"oldPassword":"…","newPassword":"…"}`（成功后吊销其他会话）。

### 3.3 条目接口

**VaultItem 字段表**

| 字段 | 类型 | 说明 |
|---|---|---|
| id | string | 条目 ID（App 端新建为 UUID；Bitwarden 导入为 `bw-<cipherId>`） |
| type | string | `file` / `ssh` / `api`（APP v0.3 起新增 `login`：Bitwarden 导入条目） |
| name | string | 名称 |
| tags | string[] | 标签 |
| createdAt / updatedAt | int64 | 毫秒时间戳 |
| protocol / address | string | file 类型：协议（WebDAV / SFTP / …）与地址 |
| host / port / username / authMethod / secret / privateKey | - | ssh 类型字段 |
| endpoint / apiKey / demoCode | string | api 类型字段 |
| lastOk / lastLatencyMs / lastCheckedAt / lastMessage | - | 最近探测结果 |
| deleted | bool | 软删除标记（同步墓碑） |

**列表** `GET /api/v1/items?type=ssh&q=关键词&tag=生产` → `data` 为数组。

**新建** `POST /api/v1/items`，body = VaultItem（可省 id，服务端生成）。

**详情 / 更新** `GET | PATCH /api/v1/items/{id}`（PATCH 仅白名单字段，自动刷新 updatedAt）。

**删除** `DELETE /api/v1/items/{id}` → 软删除。

**标签** `PUT /api/v1/items/{id}/tags`，body `{"tags":["a","b"]}`。

**批量** `POST /api/v1/items/batch`，body `{"action":"delete","ids":["…"]}`（或 `tag` / `untag` 配 `tags`；`check` 触发批量巡检）→ `data: {"affected": n}`。

---### 3.4 同步接口

**增量拉取** `POST /api/v1/sync/pull`

```json
// 请求
{ "deviceId": "device-uuid", "since": 0 }   // since：毫秒游标；0 = 全量
// 响应 data
{ "serverTime": 1789293745648,
  "items": [ { "id": "…", "type": "ssh", "name": "…" } ],
  "deletedIds": [ "已删除条目 id" ],
  "hasMore": false }
```

**批量推送** `POST /api/v1/sync/push`

```json
// 请求
{ "deviceId": "device-uuid", "items": [ "VaultItem…" ] }
// 响应 data
{ "accepted": [ "id…" ],
  "conflicts": [ { "id": "冲突 id", "serverItem": "服务端版本" } ] }
```

- 冲突策略：**LWW（Last-Write-Wins）**，按 `updatedAt` 比较，冲突时以服务端版本为准并回传 `serverItem`。
- 删除通过墓碑（`deleted: true` + `deletedIds`）下发。

### 3.5 设置镜像

- `GET /api/v1/settings` → `data` 为面板 / APP 共享的设置对象（如 `chartStyle`、`sampleSec` 等端侧偏好）。
- `PUT /api/v1/settings` → 浅合并（仅更新提交的字段）。

### 3.6 统计与导出

- `GET /api/v1/stats` → 仪表盘统计：条目总数 / 类型分布 / 状态分布 / 热门标签 / 账号与数据信息 / 运行时长。
- `GET /api/v1/export` → 当前账号全量数据（条目 + 设置）JSON 下载。

### 3.7 Bitwarden 拉取 ★

`POST /api/v1/bitwarden/pull`

```json
// 请求
{ "server": "https://bit.bdshjgg.com", "email": "i@…", "password": "主密码" }
```

```json
// 响应 data
{ "profile": { "email": "i@…", "name": "" },
  "count": 42,
  "folders": [ { "id": "folder-uuid", "name": "工作" } ],
  "entries": [
    { "id": "cipher-uuid", "type": 1, "typeName": "登录",
      "name": "GitHub", "username": "user", "password": "******",
      "totp": "", "uris": [ "https://github.com" ],
      "notes": "", "folderId": "folder-uuid", "folderName": "工作",
      "favorite": false } ] }
```

- 超时 90 秒；错误返回 400 + 中文文案。
- **主密码仅用于当次密钥派生，服务端不存储、不记日志**；软删除条目自动过滤。
- `type` 数值含义：1 登录 / 2 安全笔记 / 3 银行卡 / 4 身份 / 5 SSH 密钥。

### 3.8 SSH 执行 / 文件（摘要）

- `exec`：body `{"command":"ls -la","container":""}`（container 为空 = 主机命令）。
- `metrics`：CPU / 内存 / 网络 / 磁盘 / 负载快照。
- `docker`：容器列表 + 动作（start / stop / restart / logs）。
- `files`：SFTP / WebDAV 条目的浏览 / 上传 / 下载 / 删除。

---## 4. Bitwarden / Vaultwarden 上游协议

### 4.1 五步流程

```
① POST /identity/accounts/prelogin    → 取 KDF 参数
② 本地派生 masterKey + masterPasswordHash（主密码不参与网络传输，只传 hash）
③ POST /identity/connect/token        → access_token + 加密的账户密钥（Key）
④ 本地解密 Key → 64 字节 userKey（encKey 32B + macKey 32B）
⑤ GET /api/sync（Bearer access_token） → 全量密码库 → 本地逐条解密
```

### 4.2 端点规格

**① Prelogin** `POST {base}/identity/accounts/prelogin`

```json
// 请求
{ "email": "i@…" }
// 响应（示例）
{ "kdf": 0, "kdfIterations": 600000, "kdfMemory": null, "kdfParallelism": null }
```

- `kdf`：0 = PBKDF2-SHA256；1 = Argon2id（`kdfMemory` / `kdfParallelism` 仅 Argon2id 有意义）。

**② Token** `POST {base}/identity/connect/token`（`application/x-www-form-urlencoded`）

| 字段 | 值 |
|---|---|
| grant_type | `password` |
| username | 邮箱 |
| password | masterPasswordHash（Base64，本地计算） |
| scope | `api offline_access` |
| client_id | `cli` |
| deviceType | `0` |
| deviceIdentifier | 设备 UUID（APP 固定为 `8f5a2c91-…`；bwctl 为自身设备 ID） |
| deviceName | 设备名（如 `VaultForge-App`） |

```json
// 响应（节选）
{ "access_token": "…", "Key": "2.<iv>|<ct>|<mac>", "TwoFactorRequired": false }
```

- 错误：400 / 401 → 「邮箱或主密码不正确」；`TwoFactorRequired = true` → 双重验证暂不支持自动登录。

**③ Sync** `GET {base}/api/sync`（`Authorization: Bearer <access_token>`）

```json
// 响应（节选）
{ "profile": { "email": "i@…", "name": "" },
  "folders": [ { "id": "…", "name": "…" } ],
  "ciphers": [ { "id": "…", "type": 1, "name": "<加密>", "notes": "<加密>",
                 "folderId": "…", "favorite": false, "deletedDate": null,
                 "login": { "username": "<加密>", "password": "<加密>", "totp": "<加密>",
                            "uris": [ { "uri": "<加密>" } ] } } ] }
```

### 4.3 加密链条（双端一致）

```
masterKey    = PBKDF2-SHA256(password, lower(email), 600000, 32B)    // 或 Argon2id
mpHash       = b64( PBKDF2-SHA256(key=masterKey, salt=password, 1轮) ) // ← 只上传这个
(enc, mac)   = HKDF-Expand-SHA256(masterKey, info="enc" / "mac", 32B)
userKey(64B) = AES-256-CBC 解密（Key 分段，HMAC-SHA256 校验）          // enc 32B + mac 32B
条目字段      = 用 encKey = userKey[:32] / macKey = userKey[32:] 解密各 EncString
```

- **EncString** 格式：`2.<base64(iv)>|<base64(密文)>|<base64(mac)>`（CBC 模式）；空值 / 缺失字段按空串处理。
- 地址规范化：服务器地址自动补 `https://`、去除尾部 `/` 及 `/api`、`/identity` 后缀。

### 4.4 数据映射（cipher → 条目）

| 上游字段 | 条目字段 | 处理 |
|---|---|---|
| id | id | 双端展示；APP 导入时加 `bw-` 前缀 |
| type | type / typeName | 1 登录 / 2 安全笔记 / 3 银行卡 / 4 身份 / 5 SSH 密钥 |
| name | name | 解密；空 → `(未命名条目)` |
| login.username / password / totp | username / password / totp | 解密 |
| login.uris[].uri | uris / endpoint | 解密，过滤空值 |
| notes | notes | 解密 |
| folderId → folders | folderName | 折叠为显示名 |
| favorite | favorite | 直通 |
| deletedDate | — | 非空则**过滤**（软删不展示） |

### 4.5 实现对照

| 端 | 位置 | 说明 |
|---|---|---|
| Server（Go） | `server/internal/bitwarden/` | `Connect()` + `Fetch()`，供 `/api/v1/bitwarden/pull` |
| APP（Kotlin） | `app/…/bitwarden/`（BwCrypto / BwClient / BwEngine） | 拉取页；纯 Kotlin PBKDF2 + bouncycastle 支持 Argon2id |
| bwctl（Go CLI） | `bwctl` 源码包 | 命令行拉取 / 检索；与上两者同一加密实现，跨实现交叉验证一致 |

---## 5. 网页端（Web 管理面板）对接说明

浏览器访问 Server 即用（资源全内嵌、无外部 CDN）。面板所有功能均通过 **① 通道 `/api/v1`** 完成：

| 面板页面 | 主要接口 |
|---|---|
| 登录 / 注册 | `auth/login`、`auth/register` |
| 仪表盘 | `stats`、`sync/*`、`export` |
| 条目列表（搜索 / 筛选 / 批量） | `items`、`items/batch`、`items/{id}/check` |
| 条目详情（SSH 指标 / Docker / 文件） | `items/{id}/metrics`、`items/{id}/docker`、`items/{id}/files*`、`items/{id}/exec` |
| 终端 | `items/{id}/exec` |
| 文件管理 | `items/{id}/files*` |
| 设置（偏好 / 密码 / 会话） | `settings`、`auth/password`、`auth/sessions` |
| Bitwarden 对接 | `bitwarden/pull` |

**Bitwarden 面板流程**：填写服务器 / 邮箱 / 主密码 → 拉取 → 列表展示（搜索、密码打码 / 查看 / 复制）→ 按需使用条目；默认服务器预填自建实例地址。

**偏好镜像**：面板偏好（图表样式 / 采样间隔等）通过 `/api/v1/settings` 存储，与 APP 端设置镜像互通。

---

## 6. APP 端对接说明

### 6.1 云端同步（APP ↔ Server）

APP 内置 `CloudClient`（OkHttp + kotlinx-serialization），调用：

| 方法 | 接口 | 说明 |
|---|---|---|
| register / login | `POST /api/v1/auth/register`、`/auth/login` | 账号登录，保存会话令牌 |
| logout | `POST /api/v1/auth/logout` | 退出 |
| pull | `POST /api/v1/sync/pull` | 增量拉取（since 游标） |
| push | `POST /api/v1/sync/push` | 批量推送本地变更 |
| batchDelete | `POST /api/v1/items/batch` | 批量删除（action = delete） |

错误映射：`code = 401` → 「登录已失效或账号密码错误」；其余透传服务端 `message`。

### 6.2 Bitwarden 拉取页（APP ↔ Vaultwarden）

- 入口：设置 →「Bitwarden 密码库」→ 拉取页。
- 流程：填写服务器 / 邮箱 / 主密码 →「拉取密码库」→ 预览条目（显示 / 复制）→「全部导入」。
- 拉取成功后自动记住**服务器地址与邮箱**（设置中的 `bwUrl` / `bwEmail`）；**主密码仅内存使用，不保存、不落盘**。
- 导入规则（幂等）：
  - 条目 id = `bw-<Bitwarden cipherId>`；再次导入同一账号时自动**更新**而不是重复。
  - 类型 `login`、标签 `bitwarden` + 文件夹名；密码存入 `secret`、首个网址存入 `endpoint`。
- 错误文案：`邮箱或主密码不正确` / `该账号启用了两步验证（2FA），暂不支持自动登录` / `无法解密账户密钥（主密码可能不正确）` / `无法连接服务器：…`。

### 6.3 APP 本地 API（127.0.0.1:8737）

供 AI 助手 / 脚本调用；`Authorization: Bearer <App 本地 Token>`（App 首页可查看 / 复制）。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1` | 服务信息（免鉴权） |
| GET / POST | `/api/v1/items` | 列表（`?type=` `?tag=`）/ 新建 |
| GET / PATCH / DELETE | `/api/v1/items/{id}` | 详情 / 更新 / 删除 |
| POST | `/api/v1/items/{id}/test` | 探测单个条目 |
| PUT | `/api/v1/items/{id}/tags` | 覆盖标签 |
| GET | `/api/v1/ssh/{id}/metrics` | SSH 指标 |
| GET | `/api/v1/ssh/{id}/containers` | Docker 容器列表 |
| POST | `/api/v1/ssh/{id}/containers/{cid}/{action}` | 容器操作（start / stop / restart / logs） |
| POST | `/api/v1/api/{id}/probe` | API 可用性探测 |

响应包裹与 ① 通道一致（`{code, message, data}`）。

### 6.4 数据模型

条目字段与 Server 的 `VaultItem` 一一对应（见 3.3）；新增 `login` 类型用于 Bitwarden 导入条目，在首页以「登录」徽章展示，并支持筛选。

---## 7. 错误与排障

| 场景 | code / 文案 | 处理 |
|---|---|---|
| 未携带 / 错误令牌 | 401 `unauthorized…` | 重新登录获取令牌 |
| 用户名或密码错误 | 401 `用户名或密码错误` | 检查账号密码；注意限流 |
| 请求体不合法 | 400 `invalid json: …` | 检查 JSON |
| 资源不存在 | 404 | 检查 id / 路径 |
| 限流 | 429 `too many requests` | 稍后重试 |
| Bitwarden 连接失败 | 400 `无法连接服务器：…` | 检查地址与网络可达性 |
| Bitwarden 登录失败 | 400 `邮箱或主密码不正确` | 检查凭据；2FA 账号暂不支持 |

---

## 8. 变更记录

| 版本 | 日期 | 内容 |
|---|---|---|
| v1.0 | 2026-09-14 | 初版：覆盖 Server v0.4.0 / APP v0.3.0 / bwctl v0.1；含 ① Server API、② Bitwarden 协议、③ APP 本地 API |

---

*文档随「秘钥仓 VaultForge v0.3.0」存档分发；权威实现以源码为准。*