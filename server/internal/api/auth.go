package api

import (
	"errors"
	"net/http"
	"regexp"
	"time"

	"github.com/03chuangling/vaultforge/server/internal/auth"
	"github.com/03chuangling/vaultforge/server/internal/model"
	"github.com/03chuangling/vaultforge/server/internal/store"
)

// sessionTTL 会话有效期（30 天）。
const sessionTTL = 30 * 24 * time.Hour

var usernamePattern = regexp.MustCompile(`^[\p{L}\p{N}_-]{3,32}$`)

// POST /api/v1/auth/register —— 注册账号。
// 服务器没有任何账号时开放（引导第一个管理员）；此后默认关闭，
// 可用 VF_ALLOW_REGISTER=1 放开（多用户模式）。
func (s *Server) handleRegister(w http.ResponseWriter, r *http.Request) {
	if !s.rl.allow("register:"+clientIP(r), 5, time.Minute) {
		fail(w, http.StatusTooManyRequests, "too many requests（请稍后再试）")
		return
	}
	var req model.AuthRequest
	if err := readJSON(r, &req); err != nil {
		fail(w, http.StatusBadRequest, "invalid json: "+err.Error())
		return
	}
	if !usernamePattern.MatchString(req.Username) {
		fail(w, http.StatusBadRequest, "用户名需为 3-32 位字母 / 数字 / _-")
		return
	}
	if len(req.Password) < 8 || len(req.Password) > 128 {
		fail(w, http.StatusBadRequest, "密码长度需为 8-128 位")
		return
	}
	if s.store.HasAccounts() && !s.cfg.AllowRegister {
		fail(w, http.StatusForbidden, "注册已关闭（服务器已有账号）")
		return
	}
	salt, hash, iters, err := auth.HashPassword(req.Password)
	if err != nil {
		fail(w, http.StatusInternalServerError, "注册失败: "+err.Error())
		return
	}
	acct, err := s.store.AddAccount(req.Username, salt, hash, iters)
	if errors.Is(err, store.ErrUsernameTaken) {
		fail(w, http.StatusConflict, "用户名已存在")
		return
	}
	if err != nil {
		fail(w, http.StatusInternalServerError, "注册失败: "+err.Error())
		return
	}
	data, err := s.issueSession(acct)
	if err != nil {
		fail(w, http.StatusInternalServerError, "签发会话失败: "+err.Error())
		return
	}
	okMsg(w, "created", data)
}

// POST /api/v1/auth/login —— 登录（用户名 + 密码），签发会话令牌。
func (s *Server) handleLogin(w http.ResponseWriter, r *http.Request) {
	if !s.rl.allow("login:"+clientIP(r), 15, time.Minute) {
		fail(w, http.StatusTooManyRequests, "too many requests（请稍后再试）")
		return
	}
	var req model.AuthRequest
	if err := readJSON(r, &req); err != nil {
		fail(w, http.StatusBadRequest, "invalid json: "+err.Error())
		return
	}
	acct := s.store.FindAccountByUsername(req.Username)
	if acct == nil {
		// 均衡时间：避免通过响应快慢枚举用户名
		auth.VerifyPassword(req.Password, "aabb", "ccdd", auth.PasswordIterations())
		fail(w, http.StatusUnauthorized, "用户名或密码错误")
		return
	}
	if !auth.VerifyPassword(req.Password, acct.Salt, acct.PasswordHash, acct.Iterations) {
		fail(w, http.StatusUnauthorized, "用户名或密码错误")
		return
	}
	data, err := s.issueSession(acct)
	if err != nil {
		fail(w, http.StatusInternalServerError, "签发会话失败: "+err.Error())
		return
	}
	ok(w, data)
}

// POST /api/v1/auth/logout —— 退出登录（吊销当前会话）。
func (s *Server) handleLogout(w http.ResponseWriter, r *http.Request) {
	if token, okv := auth.BearerToken(r.Header.Get("Authorization")); okv {
		s.store.DeleteSession(auth.HashToken(token))
	}
	ok(w, nil)
}

// GET /api/v1/auth/me —— 当前账号信息。
func (s *Server) handleMe(w http.ResponseWriter, r *http.Request) {
	acct := s.store.GetAccount(userID(r))
	if acct == nil {
		fail(w, http.StatusNotFound, "account not found")
		return
	}
	ok(w, map[string]any{"user": model.UserInfo{ID: acct.ID, Username: acct.Username, CreatedAt: acct.CreatedAt}})
}

// issueSession 为用户签发新会话，返回响应数据。
func (s *Server) issueSession(acct *store.Account) (*model.AuthData, error) {
	token, err := auth.NewSessionToken()
	if err != nil {
		return nil, err
	}
	expiresAt := time.Now().Add(sessionTTL).UnixMilli()
	if err := s.store.AddSession(auth.HashToken(token), acct.ID, expiresAt); err != nil {
		return nil, err
	}
	return &model.AuthData{
		User:      &model.UserInfo{ID: acct.ID, Username: acct.Username, CreatedAt: acct.CreatedAt},
		Token:     token,
		ExpiresAt: expiresAt,
	}, nil
}
