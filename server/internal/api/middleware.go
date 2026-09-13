package api

import (
	"context"
	"log"
	"net/http"
	"time"

	"github.com/03chuangling/vaultforge/server/internal/auth"
)

// ctxUserID 请求上下文中的用户 id 键。
type ctxKey string

const ctxUserID ctxKey = "user_id"

// userID 取出当前请求的登录用户 id。
func userID(r *http.Request) string {
	v, _ := r.Context().Value(ctxUserID).(string)
	return v
}

// authed 校验会话令牌（Authorization: Bearer <token>），并把用户注入上下文。
func (s *Server) authed(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		token, okv := auth.BearerToken(r.Header.Get("Authorization"))
		if !okv {
			fail(w, http.StatusUnauthorized, "unauthorized：请携带 Authorization: Bearer <token>")
			return
		}
		sess := s.store.GetSession(auth.HashToken(token))
		if sess == nil || time.Now().UnixMilli() >= sess.ExpiresAt {
			fail(w, http.StatusUnauthorized, "登录已过期，请重新登录")
			return
		}
		ctx := context.WithValue(r.Context(), ctxUserID, sess.UserID)
		next(w, r.WithContext(ctx))
	}
}

// maxBodyBytes 请求体大小上限（16MB，防止超大报文打爆内存）。
const maxBodyBytes = 16 << 20

// withLogging 输出访问日志，并限制请求体大小。
func withLogging(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		r.Body = http.MaxBytesReader(w, r.Body, maxBodyBytes)
		start := time.Now()
		rec := &statusRecorder{ResponseWriter: w, status: http.StatusOK}
		next.ServeHTTP(rec, r)
		log.Printf("%s %s -> %d (%s)", r.Method, r.URL.Path, rec.status, time.Since(start).Round(time.Millisecond))
	})
}

// statusRecorder 记录响应状态码，供日志使用。
type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (r *statusRecorder) WriteHeader(code int) {
	r.status = code
	r.ResponseWriter.WriteHeader(code)
}
