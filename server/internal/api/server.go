package api

import (
	"net/http"
	"time"

	"github.com/03chuangling/vaultforge/server/internal/config"
	"github.com/03chuangling/vaultforge/server/internal/store"
)

// Server 聚合配置、存储与限流，对外暴露完整的路由表。
type Server struct {
	cfg       config.Config
	store     *store.Store
	rl        *rateLimiter
	startedAt time.Time
}

// New 构造服务实例。
func New(cfg config.Config, st *store.Store) *Server {
	return &Server{cfg: cfg, store: st, rl: newRateLimiter(), startedAt: time.Now()}
}

// Handler 返回带日志中间件的路由表（Go 1.22 ServeMux：方法 + 路径模式）。
//
// 公开接口：
//
//	GET  /                          Web 管理面板（v0.3.0）
//	GET  /assets/*                  面板静态资源
//	GET  /healthz                   健康检查
//	GET  /api/v1                    服务信息
//	POST /api/v1/auth/register      注册账号
//	POST /api/v1/auth/login         登录（签发会话令牌）
//
// 账号接口（Bearer 会话令牌）：
//
//	POST   /api/v1/auth/logout            退出登录
//	GET    /api/v1/auth/me                当前账号信息
//	GET    /api/v1/auth/sessions          会话列表
//	DELETE /api/v1/auth/sessions/{id}     吊销会话
//	POST   /api/v1/auth/password          修改密码
//
// 条目接口：
//
//	GET    /api/v1/items                  条目列表（?type=&q=&tag=）
//	POST   /api/v1/items                  新建条目
//	POST   /api/v1/items/batch            批量操作（delete/tag/untag/check）
//	GET    /api/v1/items/{id}             条目详情
//	PATCH  /api/v1/items/{id}             部分更新
//	DELETE /api/v1/items/{id}             软删除（墓碑）
//	PUT    /api/v1/items/{id}/tags        覆盖标签
//	POST   /api/v1/items/{id}/check       立即探测并写回结果
//
// 条目动作（Web 面板 / App 能力）：
//
//	POST /api/v1/items/{id}/exec          执行命令（服务器 / docker 容器）
//	GET  /api/v1/items/{id}/metrics       SSH 服务器指标
//	GET  /api/v1/items/{id}/docker        Docker 容器列表
//	POST /api/v1/items/{id}/docker        容器操作（start/stop/restart/logs）
//	GET  /api/v1/items/{id}/files         远程目录列表
//	GET  /api/v1/items/{id}/files/download 下载文件
//	POST /api/v1/items/{id}/files/upload  上传文件
//	POST /api/v1/items/{id}/files/delete  删除文件 / 目录
//
// 面板与同步：
//
//	GET  /api/v1/stats                    仪表盘统计
//	GET  /api/v1/export                   数据导出（JSON 下载）
//	GET  /api/v1/settings                 读取设置镜像
//	PUT  /api/v1/settings                 合并设置镜像
//	POST /api/v1/sync/pull                增量拉取
//	POST /api/v1/sync/push                批量推送
//
// 下一阶段路线：/api/v1/devices（设备管理）、/api/v1/agent/*（远程探测队列）、2FA。
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()

	// —— 公开：Web 面板 ——
	mux.HandleFunc("GET /", s.handleWeb)
	mux.HandleFunc("GET /assets/", s.handleWebAsset)

	// —— 公开接口 ——
	mux.HandleFunc("GET /healthz", s.handleHealth)
	mux.HandleFunc("GET /api/v1", s.handleInfo)
	mux.HandleFunc("POST /api/v1/auth/register", s.handleRegister)
	mux.HandleFunc("POST /api/v1/auth/login", s.handleLogin)

	// —— 账号（需登录） ——
	mux.HandleFunc("POST /api/v1/auth/logout", s.authed(s.handleLogout))
	mux.HandleFunc("GET /api/v1/auth/me", s.authed(s.handleMe))
	mux.HandleFunc("GET /api/v1/auth/sessions", s.authed(s.handleSessions))
	mux.HandleFunc("DELETE /api/v1/auth/sessions/{id}", s.authed(s.handleRevokeSession))
	mux.HandleFunc("POST /api/v1/auth/password", s.authed(s.handleChangePassword))

	// —— 条目 ——
	mux.HandleFunc("GET /api/v1/items", s.authed(s.handleItemsList))
	mux.HandleFunc("POST /api/v1/items", s.authed(s.handleItemCreate))
	mux.HandleFunc("POST /api/v1/items/batch", s.authed(s.handleBatch))
	mux.HandleFunc("GET /api/v1/items/{id}", s.authed(s.handleItemGet))
	mux.HandleFunc("PATCH /api/v1/items/{id}", s.authed(s.handleItemPatch))
	mux.HandleFunc("DELETE /api/v1/items/{id}", s.authed(s.handleItemDelete))
	mux.HandleFunc("PUT /api/v1/items/{id}/tags", s.authed(s.handleItemSetTags))
	mux.HandleFunc("POST /api/v1/items/{id}/check", s.authed(s.handleItemCheck))

	// —— 条目动作：SSH 终端 / 指标 / Docker / 文件 ——
	mux.HandleFunc("POST /api/v1/items/{id}/exec", s.authed(s.handleExec))
	mux.HandleFunc("GET /api/v1/items/{id}/metrics", s.authed(s.handleMetrics))
	mux.HandleFunc("GET /api/v1/items/{id}/docker", s.authed(s.handleDockerList))
	mux.HandleFunc("POST /api/v1/items/{id}/docker", s.authed(s.handleDockerAction))
	mux.HandleFunc("GET /api/v1/items/{id}/files", s.authed(s.handleFilesList))
	mux.HandleFunc("GET /api/v1/items/{id}/files/download", s.authed(s.handleFileDownload))
	mux.HandleFunc("POST /api/v1/items/{id}/files/upload", s.authed(s.handleFileUpload))
	mux.HandleFunc("POST /api/v1/items/{id}/files/delete", s.authed(s.handleFileDelete))

	// —— 仪表盘 / 导出 ——
	mux.HandleFunc("GET /api/v1/stats", s.authed(s.handleStats))
	mux.HandleFunc("GET /api/v1/export", s.authed(s.handleExport))

	// —— 设置镜像 ——
	mux.HandleFunc("GET /api/v1/settings", s.authed(s.handleSettingsGet))
	mux.HandleFunc("PUT /api/v1/settings", s.authed(s.handleSettingsPut))

	// —— 同步 ——
	mux.HandleFunc("POST /api/v1/sync/pull", s.authed(s.handleSyncPull))
	mux.HandleFunc("POST /api/v1/sync/push", s.authed(s.handleSyncPush))

	// —— 兜底（JSON 404） ——
	mux.HandleFunc("/", s.handleNotFound)

	return withLogging(mux)
}
