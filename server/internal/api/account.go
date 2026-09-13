package api

import (
	"net/http"
	"strings"

	"github.com/03chuangling/vaultforge/server/internal/auth"
	"github.com/03chuangling/vaultforge/server/internal/model"
)

// sessionIDFromToken 会话对外 id（令牌哈希前 12 位，与 store.ListSessions 一致）。
func sessionIDFromToken(token string) string {
	h := auth.HashToken(token)
	if len(h) > 12 {
		return h[:12]
	}
	return h
}

// GET /api/v1/auth/sessions —— 当前账号的会话列表（标注当前会话）。
func (s *Server) handleSessions(w http.ResponseWriter, r *http.Request) {
	entries := s.store.ListSessions(userID(r))
	current := ""
	if token, okv := auth.BearerToken(r.Header.Get("Authorization")); okv {
		current = sessionIDFromToken(token)
	}
	list := make([]model.SessionInfo, 0, len(entries))
	for _, e := range entries {
		list = append(list, model.SessionInfo{
			ID:        e.ID,
			Current:   e.ID == current,
			CreatedAt: e.CreatedAt,
			ExpiresAt: e.ExpiresAt,
		})
	}
	ok(w, map[string]any{"sessions": list, "count": len(list)})
}

// DELETE /api/v1/auth/sessions/{id} —— 吊销指定会话（不能吊销当前会话）。
func (s *Server) handleRevokeSession(w http.ResponseWriter, r *http.Request) {
	id := strings.TrimSpace(r.PathValue("id"))
	if id == "" {
		fail(w, http.StatusBadRequest, "缺少会话 id")
		return
	}
	if token, okv := auth.BearerToken(r.Header.Get("Authorization")); okv && sessionIDFromToken(token) == id {
		fail(w, http.StatusBadRequest, "不能吊销当前会话（如需退出请使用登出）")
		return
	}
	if !s.store.RevokeSession(userID(r), id) {
		fail(w, http.StatusNotFound, "会话不存在或已失效")
		return
	}
	okMsg(w, "revoked", nil)
}

// POST /api/v1/auth/password —— 修改密码（校验原密码；吊销其他会话）。
func (s *Server) handleChangePassword(w http.ResponseWriter, r *http.Request) {
	var req model.PasswordChangeRequest
	if err := readJSON(r, &req); err != nil {
		fail(w, http.StatusBadRequest, "invalid json: "+err.Error())
		return
	}
	if len(req.NewPassword) < 8 || len(req.NewPassword) > 128 {
		fail(w, http.StatusBadRequest, "新密码长度需为 8-128 位")
		return
	}
	acct := s.store.GetAccount(userID(r))
	if acct == nil {
		fail(w, http.StatusNotFound, "account not found")
		return
	}
	if !auth.VerifyPassword(req.OldPassword, acct.Salt, acct.PasswordHash, acct.Iterations) {
		fail(w, http.StatusUnauthorized, "原密码错误")
		return
	}
	salt, hash, iters, err := auth.HashPassword(req.NewPassword)
	if err != nil {
		fail(w, http.StatusInternalServerError, "更新失败："+err.Error())
		return
	}
	if err := s.store.ChangePassword(acct.ID, salt, hash, iters); err != nil {
		fail(w, http.StatusInternalServerError, "更新失败："+err.Error())
		return
	}
	// 吊销除当前会话外的其他会话
	current := ""
	if token, okv := auth.BearerToken(r.Header.Get("Authorization")); okv {
		current = sessionIDFromToken(token)
	}
	revoked := 0
	for _, e := range s.store.ListSessions(acct.ID) {
		if e.ID != current && s.store.RevokeSession(acct.ID, e.ID) {
			revoked++
		}
	}
	ok(w, map[string]any{"revokedOthers": revoked})
}
