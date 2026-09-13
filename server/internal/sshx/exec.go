package sshx

import (
	"fmt"
	"regexp"
	"strings"
	"time"

	"github.com/03chuangling/vaultforge/server/internal/model"
)

// containerNamePattern 容器名白名单（防命令注入）。
var containerNamePattern = regexp.MustCompile(`^[A-Za-z0-9_.\-]{1,80}$`)

// Execute 在服务器上执行命令，返回合并输出（stdout + stderr）。
func Execute(item *model.VaultItem, command string) (string, error) {
	if strings.TrimSpace(command) == "" {
		return "", fmt.Errorf("命令不能为空")
	}
	c, err := Get(item)
	if err != nil {
		return "", err
	}
	return c.run(command, 30*time.Second)
}

// RunInContainer 在指定容器内执行命令（docker exec，sh -c）。
func RunInContainer(item *model.VaultItem, container, command string) (string, error) {
	if !containerNamePattern.MatchString(container) {
		return "", fmt.Errorf("容器名不合法")
	}
	if strings.TrimSpace(command) == "" {
		return "", fmt.Errorf("命令不能为空")
	}
	escaped := strings.ReplaceAll(command, "'", `'\''`)
	return Execute(item, "docker exec '"+container+"' sh -c '"+escaped+"' 2>&1")
}

// ProbeTCP 只做 TCP 级握手探测（用于轻量连通性检测），返回毫秒延迟。
func ProbeTCP(host string, port int, timeout time.Duration) (int64, error) {
	if strings.TrimSpace(host) == "" || port <= 0 {
		return -1, fmt.Errorf("主机或端口无效")
	}
	addr := fmt.Sprintf("%s:%d", host, port)
	start := time.Now()
	d := netDialer(timeout)
	conn, err := d.Dial("tcp", addr)
	if err != nil {
		return -1, err
	}
	conn.Close()
	return time.Since(start).Milliseconds(), nil
}
