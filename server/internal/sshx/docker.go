package sshx

import (
	"fmt"
	"strings"

	"github.com/03chuangling/vaultforge/server/internal/model"
)

// ListContainers 列出容器（docker ps -a）。返回 (容器列表, 错误文案)。
func ListContainers(item *model.VaultItem) ([]model.DockerContainerInfo, string) {
	out, err := Execute(item, "docker ps -a --format '{{.ID}}|{{.Names}}|{{.Status}}|{{.Image}}' 2>&1")
	if err != nil {
		return nil, err.Error()
	}
	trimmed := strings.TrimSpace(out)
	if trimmed == "" {
		return []model.DockerContainerInfo{}, ""
	}
	lower := strings.ToLower(trimmed)
	switch {
	case strings.Contains(lower, "command not found") || strings.Contains(lower, "no such file"):
		return nil, "服务器上未找到 docker 命令"
	case strings.Contains(lower, "permission denied"):
		return nil, "权限不足（请使用有 docker 权限的用户）"
	case strings.Contains(lower, "cannot connect to the docker daemon"):
		return nil, "Docker 守护进程未运行"
	}
	if !strings.Contains(trimmed, "|") {
		return nil, trimmed
	}
	containers := make([]model.DockerContainerInfo, 0)
	for _, line := range strings.Split(trimmed, "\n") {
		if !strings.Contains(line, "|") {
			continue
		}
		parts := strings.Split(line, "|")
		if len(parts) < 4 {
			continue
		}
		containers = append(containers, model.DockerContainerInfo{
			ID:     strings.TrimSpace(parts[0]),
			Name:   strings.TrimSpace(parts[1]),
			Status: strings.TrimSpace(parts[2]),
			Image:  strings.TrimSpace(parts[3]),
		})
	}
	return containers, ""
}

// ContainerAction 执行容器操作（start / stop / restart / logs），返回输出。
func ContainerAction(item *model.VaultItem, container, action string) (string, error) {
	if !containerNamePattern.MatchString(container) {
		return "", fmt.Errorf("容器名不合法")
	}
	var cmd string
	switch action {
	case "start":
		cmd = "docker start '" + container + "' 2>&1"
	case "stop":
		cmd = "docker stop '" + container + "' 2>&1"
	case "restart":
		cmd = "docker restart '" + container + "' 2>&1"
	case "logs":
		cmd = "docker logs --tail 100 '" + container + "' 2>&1"
	default:
		return "", fmt.Errorf("不支持的操作：%s", action)
	}
	return Execute(item, cmd)
}
