// Package probe 提供条目连通性探测：file（WebDAV / TCP）、ssh（握手）、api（HTTP + 演示代码）。
package probe

import (
	"strings"
	"time"

	"github.com/03chuangling/vaultforge/server/internal/model"
	"github.com/03chuangling/vaultforge/server/internal/sshx"
)

// Result 探测结果（与 App 端 ProbeResult 字段对齐）。
type Result struct {
	Ok        bool   `json:"ok"`
	LatencyMs int64  `json:"latencyMs"`
	Message   string `json:"message"`
}

// Probe 按条目类型执行连通性探测。
func Probe(item *model.VaultItem) Result {
	switch item.Type {
	case "file":
		return probeFile(item)
	case "ssh":
		return probeSSH(item)
	case "api":
		return probeAPI(item)
	default:
		return Result{Ok: false, LatencyMs: -1, Message: "未知类型：" + item.Type}
	}
}

// probeSSH SSH 条目探测：TCP 握手延迟 + 完整 SSH 连接（凭据校验，成功后连接进缓存复用）。
func probeSSH(item *model.VaultItem) Result {
	host := strings.TrimSpace(item.Host)
	port := item.Port
	if port <= 0 {
		port = 22
	}
	tcpMs, err := sshx.ProbeTCP(host, port, 6*time.Second)
	if err != nil {
		return Result{Ok: false, LatencyMs: -1, Message: "连接失败：" + err.Error()}
	}
	if _, err := sshx.Get(item); err != nil {
		return Result{Ok: false, LatencyMs: -1, Message: err.Error()}
	}
	return Result{Ok: true, LatencyMs: tcpMs, Message: "SSH 连接成功"}
}
