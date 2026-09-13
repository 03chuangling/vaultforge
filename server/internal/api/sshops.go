package api

import (
	"net/http"
	"strings"

	"github.com/03chuangling/vaultforge/server/internal/model"
	"github.com/03chuangling/vaultforge/server/internal/sshx"
)

// sshItem 从路径取出 ssh 类型条目（不存在 / 类型不符时直接写响应并返回 nil）。
func (s *Server) sshItem(w http.ResponseWriter, r *http.Request) *model.VaultItem {
	item := s.store.GetItem(userID(r), r.PathValue("id"))
	if item == nil {
		fail(w, http.StatusNotFound, "item not found")
		return nil
	}
	if item.Type != "ssh" {
		fail(w, http.StatusBadRequest, "该操作仅支持 SSH 类型条目")
		return nil
	}
	return item
}

// POST /api/v1/items/{id}/exec —— 执行命令（服务器或 docker 容器内）。
func (s *Server) handleExec(w http.ResponseWriter, r *http.Request) {
	item := s.sshItem(w, r)
	if item == nil {
		return
	}
	var req model.ExecRequest
	if err := readJSON(r, &req); err != nil {
		fail(w, http.StatusBadRequest, "invalid json: "+err.Error())
		return
	}
	command := strings.TrimSpace(req.Command)
	if command == "" {
		fail(w, http.StatusBadRequest, "命令不能为空")
		return
	}
	if len(command) > 32<<10 {
		fail(w, http.StatusBadRequest, "命令过长（上限 32KB）")
		return
	}
	var (
		out string
		err error
	)
	if strings.TrimSpace(req.Container) != "" {
		out, err = sshx.RunInContainer(item, req.Container, command)
	} else {
		out, err = sshx.Execute(item, command)
	}
	if err != nil {
		fail(w, http.StatusBadGateway, err.Error())
		return
	}
	ok(w, map[string]any{"output": out})
}

// GET /api/v1/items/{id}/metrics —— SSH 服务器指标快照。
func (s *Server) handleMetrics(w http.ResponseWriter, r *http.Request) {
	item := s.sshItem(w, r)
	if item == nil {
		return
	}
	ok(w, sshx.FetchMetrics(item))
}

// GET /api/v1/items/{id}/docker —— Docker 容器列表。
func (s *Server) handleDockerList(w http.ResponseWriter, r *http.Request) {
	item := s.sshItem(w, r)
	if item == nil {
		return
	}
	containers, errMsg := sshx.ListContainers(item)
	ok(w, map[string]any{
		"ok":         errMsg == "",
		"error":      errMsg,
		"containers": containers,
	})
}

// POST /api/v1/items/{id}/docker —— 容器操作（start / stop / restart / logs）。
func (s *Server) handleDockerAction(w http.ResponseWriter, r *http.Request) {
	item := s.sshItem(w, r)
	if item == nil {
		return
	}
	var req model.DockerActionRequest
	if err := readJSON(r, &req); err != nil {
		fail(w, http.StatusBadRequest, "invalid json: "+err.Error())
		return
	}
	out, err := sshx.ContainerAction(item, req.Container, req.Action)
	if err != nil {
		fail(w, http.StatusBadGateway, err.Error())
		return
	}
	ok(w, map[string]any{"output": out})
}
