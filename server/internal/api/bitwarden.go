package api

import (
	"context"
	"net/http"
	"strings"
	"time"

	"github.com/03chuangling/vaultforge/server/internal/bitwarden"
)

// POST /api/v1/bitwarden/pull —— 连接 Bitwarden / Vaultwarden 并拉取解密后的密码库条目。
//
// 请求体：{"server":"https://bit.dshjgg.com","email":"...","password":"主密码"}
// 说明：主密码仅用于本次请求的密钥派生，服务端不存储、不记日志。
type bitwardenPullReq struct {
	Server   string `json:"server"`
	Email    string `json:"email"`
	Password string `json:"password"`
}

func (s *Server) handleBitwardenPull(w http.ResponseWriter, r *http.Request) {
	var req bitwardenPullReq
	if err := readJSON(r, &req); err != nil {
		fail(w, http.StatusBadRequest, "请求体格式错误")
		return
	}
	req.Server = strings.TrimSpace(req.Server)
	req.Email = strings.TrimSpace(req.Email)
	if req.Server == "" || req.Email == "" || req.Password == "" {
		fail(w, http.StatusBadRequest, "请填写服务器地址、邮箱与主密码")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 90*time.Second)
	defer cancel()

	sess, err := bitwarden.Connect(ctx, req.Server, req.Email, req.Password)
	if err != nil {
		fail(w, http.StatusBadRequest, err.Error())
		return
	}
	vault, err := sess.Fetch(ctx)
	if err != nil {
		fail(w, http.StatusBadRequest, err.Error())
		return
	}
	ok(w, map[string]any{
		"profile": vault.Profile,
		"count":   vault.Count,
		"folders": vault.Folders,
		"entries": vault.Entries,
	})
}
