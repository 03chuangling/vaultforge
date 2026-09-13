package api

import "net/http"

// GET /api/v1/settings —— 读取设置镜像。
func (s *Server) handleSettingsGet(w http.ResponseWriter, r *http.Request) {
	ok(w, map[string]any{"settings": s.store.Settings(userID(r))})
}

// PUT /api/v1/settings —— 浅合并设置镜像（如 chartStyle / sampleSec 等端侧偏好）。
func (s *Server) handleSettingsPut(w http.ResponseWriter, r *http.Request) {
	var patch map[string]any
	if err := readJSON(r, &patch); err != nil {
		fail(w, http.StatusBadRequest, "invalid json: "+err.Error())
		return
	}
	saved, err := s.store.MergeSettings(userID(r), patch)
	if err != nil {
		fail(w, http.StatusInternalServerError, "保存失败: "+err.Error())
		return
	}
	ok(w, map[string]any{"settings": saved})
}
