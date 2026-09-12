# VaultForge · 秘钥仓

一个 Android 密钥/凭据管理工具，内置 **本地 HTTP API**，专为与 AI 助手协作设计（AI 可通过本地接口查询状态、触发探测、管理条目）。

三类条目卡片式管理，每个条目带实时状态灯。

## 功能

- **三类密钥**
  - 📁 **文件协议**：WebDAV / SFTP / FTP / S3 等（HTTP/TCP 连通性探测，进入 App 自动检测）
  - 🖥 **SSH 主机**：连接检测 + 延迟显示；详情页支持 **内存 / CPU / 网络收发 / 磁盘** 指标（真实读取 `/proc`）与 **Docker 容器管理**（列表 / 启动 / 停止 / 重启 / 日志）
  - 🔑 **API 密钥**：保存调用地址与官方演示代码，一键检测可用性
- **标签系统**：自由打标签、按标签/类型筛选
- **卡片堆叠 UI**：墨绿冷灰配色，状态灯（🟢 可用 / 🟡 延迟偏高 / 🔴 失败 / ⚪ 未检测）
- **进入即探测**：每次打开自动检测所有条目
- **本地 API**：`127.0.0.1:8737`，Bearer Token 鉴权，统一响应体 `{code, message, data}`

## 本地 API（供 AI / 脚本调用）

| Method | Path | 说明 |
|---|---|---|
| GET | `/api/v1` | 服务信息（免鉴权） |
| GET / POST | `/api/v1/items` | 条目列表 / 新建（支持 `?type=` `?tag=` 过滤） |
| GET / PATCH / DELETE | `/api/v1/items/{id}` | 条目详情 / 更新 / 删除 |
| POST | `/api/v1/items/{id}/test` | 探测单个条目 |
| PUT | `/api/v1/items/{id}/tags` | 更新标签 |
| GET | `/api/v1/ssh/{id}/metrics` | SSH 指标（CPU/内存/网络/磁盘） |
| GET | `/api/v1/ssh/{id}/containers` | Docker 容器列表 |
| POST | `/api/v1/ssh/{id}/containers/{cid}/{action}` | 容器操作（start/stop/restart/logs） |
| POST | `/api/v1/api/{id}/probe` | API 可用性探测 |

示例：

```bash
# Token 可在 App 首页查看/复制（或读取应用私有目录下的 vault_data.json）
curl -H "Authorization: Bearer <token>" http://127.0.0.1:8737/api/v1/items
```

## 构建

### 环境

- JDK 17
- Android SDK 34（build-tools 34.0.0）
- Gradle 8.6（wrapper 已内置）

### 步骤

```bash
git clone https://github.com/<you>/vaultforge.git
cd vaultforge

# 创建 local.properties 指向你的 Android SDK
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

> ⚠️ 在 ARM64 Linux（如 Termux / proot）上构建时：官方 aapt2 为 x86_64 二进制无法运行，
> 需自备 aarch64 版 aapt2，并在 `~/.gradle/gradle.properties` 中添加
> `android.aapt2FromMavenOverride=/path/to/aapt2`（详见 `gradle.properties` 内注释）。

## 技术栈

- Kotlin + Jetpack Compose（Material 3）
- kotlinx.serialization（JSON 文件持久化）
- JSch（SSH）
- 自研 HTTP Server（纯 `ServerSocket`，无第三方 Web 框架）
- AGP 8.4.2 / Kotlin 1.9.24 / minSdk 26 / targetSdk 34

## 隐私说明

- 所有密钥数据**仅存储在设备本地**（应用私有目录 `vault_data.json`），不上传任何服务器
- 本地 API 仅监听 `127.0.0.1`（设备回环地址），不对外网开放

## License

MIT
