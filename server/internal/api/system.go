package api

import (
	"net/http"
	"time"
)

// GET /healthz —— 健康检查（公开）。
func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	ok(w, map[string]any{
		"service": "vaultforge-server",
		"version": s.cfg.Version,
		"time":    time.Now().UnixMilli(),
	})
}

// GET /api/v1 —— 服务信息（公开，连通性检查用；与 App 本地接口同款）。
func (s *Server) handleInfo(w http.ResponseWriter, r *http.Request) {
	ok(w, map[string]any{
		"name":    "vaultforge-server",
		"version": s.cfg.Version,
		"api":     "v1",
		"auth":    "bearer",
		"items":   s.store.ItemCount(),
		"time":    time.Now().UnixMilli(),
	})
}

// 兜底 404（JSON 格式，与 App 的 "no such route" 文案对齐）。
func (s *Server) handleNotFound(w http.ResponseWriter, r *http.Request) {
	fail(w, http.StatusNotFound, "no such route: "+r.Method+" "+r.URL.Path)
}
