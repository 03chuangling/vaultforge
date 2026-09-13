package probe

import (
	"encoding/base64"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/03chuangling/vaultforge/server/internal/model"
)

// probeFile 文件协议探测：http(s) 走 WebDAV HEAD/GET（带 Basic 认证），其余按 TCP 端口探测。
func probeFile(item *model.VaultItem) Result {
	t0 := time.Now()
	addr := strings.TrimSpace(item.Address)
	if addr == "" {
		return Result{Ok: false, LatencyMs: -1, Message: "未填写地址"}
	}
	if strings.HasPrefix(addr, "http://") || strings.HasPrefix(addr, "https://") {
		return probeHTTPFile(item, addr, t0)
	}
	return probeTCPFile(item, addr, t0)
}

func probeHTTPFile(item *model.VaultItem, addr string, t0 time.Time) Result {
	tcpMs := tcpLatencyURL(addr)
	code := httpCode(item, addr, "HEAD")
	if code >= 400 && code <= 599 {
		code = httpCode(item, addr, "GET")
	}
	ok := code >= 200 && code <= 399
	var note string
	switch {
	case code == 401 || code == 403:
		note = fmt.Sprintf("HTTP %d（认证失败）", code)
	case code == -1:
		note = "无法连接"
	case ok:
		note = fmt.Sprintf("HTTP %d", code)
	default:
		note = fmt.Sprintf("HTTP %d（不可用）", code)
	}
	lat := time.Since(t0).Milliseconds()
	if tcpMs >= 0 {
		lat = tcpMs
	}
	if !ok {
		lat = -1
	}
	return Result{Ok: ok, LatencyMs: lat, Message: note}
}

func probeTCPFile(item *model.VaultItem, addr string, t0 time.Time) Result {
	raw := addr
	for _, prefix := range []string{"sftp://", "ftp://", "s3://"} {
		raw = strings.TrimPrefix(raw, prefix)
	}
	hostPart := raw
	if i := strings.Index(raw, "/"); i >= 0 {
		hostPart = raw[:i]
	}
	host := hostPart
	port := 0
	if i := strings.LastIndex(hostPart, ":"); i >= 0 {
		host = hostPart[:i]
		fmt.Sscanf(hostPart[i+1:], "%d", &port)
	}
	if port <= 0 {
		switch strings.ToLower(item.Protocol) {
		case "sftp":
			port = 22
		case "ftp":
			port = 21
		}
	}
	if strings.TrimSpace(host) == "" || port <= 0 {
		return Result{Ok: false, LatencyMs: -1, Message: "地址格式无法解析（示例：host:22）"}
	}
	conn, err := net.DialTimeout("tcp", net.JoinHostPort(host, fmt.Sprintf("%d", port)), 8*time.Second)
	if err != nil {
		return Result{Ok: false, LatencyMs: -1, Message: "无法连接：" + err.Error()}
	}
	conn.Close()
	return Result{Ok: true, LatencyMs: time.Since(t0).Milliseconds(),
		Message: fmt.Sprintf("TCP %s:%d 可达", host, port)}
}

// httpCode 发送 HEAD / GET 请求并返回状态码（-1 = 失败）。
func httpCode(item *model.VaultItem, rawURL, method string) int {
	req, err := http.NewRequest(method, rawURL, nil)
	if err != nil {
		return -1
	}
	if item.Username != "" {
		raw := item.Username + ":" + item.Secret
		req.Header.Set("Authorization", "Basic "+base64.StdEncoding.EncodeToString([]byte(raw)))
	}
	client := &http.Client{Timeout: 10 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return -1
	}
	defer resp.Body.Close()
	if method == "GET" {
		io.Copy(io.Discard, io.LimitReader(resp.Body, 4096))
	}
	return resp.StatusCode
}

// tcpLatencyURL 解析 http(s) URL 并测 TCP 握手延迟。
func tcpLatencyURL(rawURL string) int64 {
	u, err := url.Parse(rawURL)
	if err != nil || u.Hostname() == "" {
		return -1
	}
	port := u.Port()
	if port == "" {
		if u.Scheme == "https" {
			port = "443"
		} else {
			port = "80"
		}
	}
	start := time.Now()
	conn, err := net.DialTimeout("tcp", net.JoinHostPort(u.Hostname(), port), 8*time.Second)
	if err != nil {
		return -1
	}
	conn.Close()
	return time.Since(start).Milliseconds()
}
