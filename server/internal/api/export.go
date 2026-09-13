package api

import (
	"encoding/json"
	"fmt"
	"net/http"
	"time"
)

// GET /api/v1/export —— 导出该用户全量数据（条目 + 设置）为 JSON 下载。
func (s *Server) handleExport(w http.ResponseWriter, r *http.Request) {
	items := s.store.ListItems(userID(r), "", "", "")
	settings := s.store.Settings(userID(r))
	payload := map[string]any{
		"exportedAt": time.Now().UnixMilli(),
		"source":     "vaultforge-server",
		"version":    s.cfg.Version,
		"items":      items,
		"settings":   settings,
	}
	filename := fmt.Sprintf("vaultforge-export-%s.json", time.Now().Format("20060102-150405"))
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Content-Disposition", "attachment; filename=\""+filename+"\"")
	enc := json.NewEncoder(w)
	enc.SetIndent("", "  ")
	_ = enc.Encode(payload)
}
