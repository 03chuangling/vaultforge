package api

import (
	"net/http"

	"github.com/03chuangling/vaultforge/server/internal/config"
	"github.com/03chuangling/vaultforge/server/internal/store"
)

// Server 聚合配置、存储与限流，对外暴露完整的路由表。
type Server struct {
	cfg   config.Config
	store *store.Store
	rl    *rateLimiter
}

// New 构造服务实例。
func New(cfg config.Config, st *store.Store) *Server {
	return &Server{cfg: cfg, store: st, rl: newRateLimiter()}
}

// Handler 返回带日志中间件的路由表（Go 1.22 ServeMux：方法 + 路径模式）。
//
// 公开接口：
//
//	GET  /healthz              健康检查
//	GET  /api/v1               服务信息
//	POST /api/v1/auth/register 注册账号
//	POST /api/v1/auth/login    登录（签发会话令牌）
//
// 业务接口（Bearer 会话令牌）：
//
//	POST   /api/v1/auth/logout          退出登录
//	GET    /api/v1/auth/me              当前账号信息
//	GET    /api/v1/items                条目列表（?type=&q=&tag=）
//	POST   /api/v1/items                新建条目
//	GET    /api/v1/items/{id}           条目详情
//	PATCH  /api/v1/items/{id}           部分更新
//	DELETE /api/v1/items/{id}           软删除（墓碑）
//	PUT    /api/v1/items/{id}/tags      覆盖标签
//	GET    /api/v1/settings             读取设置镜像
//	PUT    /api/v1/settings             合并设置镜像
//	POST   /api/v1/sync/pull            增量拉取
//	POST   /api/v1/sync/push            批量推送
//
// 下一阶段路线：/api/v1/devices（设备管理）、/api/v1/agent/*（远程探测队列）、2FA。
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()

	// —— 公开接口 ——
	mux.HandleFunc("GET /healthz", s.handleHealth)
	mux.HandleFunc("GET /api/v1", s.handleInfo)
	mux.HandleFunc("POST /api/v1/auth/register", s.handleRegister)
	mux.HandleFunc("POST /api/v1/auth/login", s.handleLogin)

	// —— 账号（需登录） ——
	mux.HandleFunc("POST /api/v1/auth/logout", s.authed(s.handleLogout))
	mux.HandleFunc("GET /api/v1/auth/me", s.authed(s.handleMe))

	// —— 条目 ——
	mux.HandleFunc("GET /api/v1/items", s.authed(s.handleItemsList))
	mux.HandleFunc("POST /api/v1/items", s.authed(s.handleItemCreate))
	mux.HandleFunc("GET /api/v1/items/{id}", s.authed(s.handleItemGet))
	mux.HandleFunc("PATCH /api/v1/items/{id}", s.authed(s.handleItemPatch))
	mux.HandleFunc("DELETE /api/v1/items/{id}", s.authed(s.handleItemDelete))
	mux.HandleFunc("PUT /api/v1/items/{id}/tags", s.authed(s.handleItemSetTags))

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
