package api

import (
	"net"
	"net/http"
	"strings"
	"sync"
	"time"
)

// rateLimiter 简单的滑动窗口限流器（按 key 计数）。
type rateLimiter struct {
	mu   sync.Mutex
	hits map[string][]int64
}

func newRateLimiter() *rateLimiter {
	return &rateLimiter{hits: map[string][]int64{}}
}

// allow 判断 key 在 window 内是否未超过 limit 次；通过则计 1 次。
func (rl *rateLimiter) allow(key string, limit int, window time.Duration) bool {
	rl.mu.Lock()
	defer rl.mu.Unlock()
	now := time.Now().UnixMilli()
	cutoff := now - window.Milliseconds()
	var kept []int64
	for _, t := range rl.hits[key] {
		if t > cutoff {
			kept = append(kept, t)
		}
	}
	if len(kept) >= limit {
		rl.hits[key] = kept
		return false
	}
	rl.hits[key] = append(kept, now)
	return true
}

// clientIP 提取客户端 IP（优先 X-Forwarded-For，兼容 nginx 反代）。
func clientIP(r *http.Request) string {
	if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
		if i := strings.IndexByte(xff, ','); i > 0 {
			return strings.TrimSpace(xff[:i])
		}
		return strings.TrimSpace(xff)
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}
