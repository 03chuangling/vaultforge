package api

import (
	"log"
	"net/http"
	"time"

	"github.com/03chuangling/vaultforge/server/internal/auth"
)

// authed 校验 Authorization: Bearer <token>（与 App 本地接口同一约定）。
func (s *Server) authed(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !auth.CheckBearer(r.Header.Get("Authorization"), s.token) {
			fail(w, http.StatusUnauthorized, "unauthorized：请携带 Authorization: Bearer <token>")
			return
		}
		next(w, r)
	}
}

// withLogging 输出访问日志。
func withLogging(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
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
