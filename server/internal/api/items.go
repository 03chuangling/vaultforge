package api

import (
	"errors"
	"net/http"

	"github.com/03chuangling/vaultforge/server/internal/model"
	"github.com/03chuangling/vaultforge/server/internal/sshx"
	"github.com/03chuangling/vaultforge/server/internal/store"
)

// GET /api/v1/items?type=&q=&tag= —— 条目列表（与 App 一致：data 为数组）。
func (s *Server) handleItemsList(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	items := s.store.ListItems(userID(r), q.Get("type"), q.Get("q"), q.Get("tag"))
	ok(w, items)
}

// POST /api/v1/items —— 新建条目。
func (s *Server) handleItemCreate(w http.ResponseWriter, r *http.Request) {
	var item model.VaultItem
	if err := readJSON(r, &item); err != nil {
		fail(w, http.StatusBadRequest, "invalid json: "+err.Error())
		return
	}
	if err := item.Validate(); err != nil {
		fail(w, http.StatusBadRequest, err.Error())
		return
	}
	saved, err := s.store.UpsertItem(userID(r), &item)
	if err != nil {
		fail(w, http.StatusInternalServerError, "保存失败: "+err.Error())
		return
	}
	okMsg(w, "created", saved)
}

// GET /api/v1/items/{id} —— 条目详情。
func (s *Server) handleItemGet(w http.ResponseWriter, r *http.Request) {
	item := s.store.GetItem(userID(r), r.PathValue("id"))
	if item == nil {
		fail(w, http.StatusNotFound, "item not found")
		return
	}
	ok(w, item)
}

// PATCH /api/v1/items/{id} —— 部分更新（白名单字段，updatedAt 自动刷新）。
func (s *Server) handleItemPatch(w http.ResponseWriter, r *http.Request) {
	var patch map[string]any
	if err := readJSON(r, &patch); err != nil {
		fail(w, http.StatusBadRequest, "invalid json: "+err.Error())
		return
	}
	item, err := s.store.PatchItem(userID(r), r.PathValue("id"), patch)
	switch {
	case errors.Is(err, store.ErrNotFound):
		fail(w, http.StatusNotFound, "item not found")
	case errors.Is(err, store.ErrInvalid):
		fail(w, http.StatusBadRequest, err.Error())
	case err != nil:
		fail(w, http.StatusInternalServerError, "更新失败: "+err.Error())
	default:
		sshx.Close(r.PathValue("id")) // 配置变更后丢弃旧 SSH 连接缓存
		okMsg(w, "updated", item)
	}
}

// DELETE /api/v1/items/{id} —— 软删除（墓碑，供同步下发）。
func (s *Server) handleItemDelete(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if _, err := s.store.DeleteItem(userID(r), id); err != nil {
		if errors.Is(err, store.ErrNotFound) {
			fail(w, http.StatusNotFound, "item not found")
			return
		}
		fail(w, http.StatusInternalServerError, "删除失败: "+err.Error())
		return
	}
	sshx.Close(id) // 删除后清理连接缓存
	okMsg(w, "deleted", nil)
}

// PUT /api/v1/items/{id}/tags —— 覆盖标签。
func (s *Server) handleItemSetTags(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Tags []string `json:"tags"`
	}
	if err := readJSON(r, &body); err != nil {
		fail(w, http.StatusBadRequest, "invalid json: "+err.Error())
		return
	}
	if body.Tags == nil {
		body.Tags = []string{}
	}
	item, err := s.store.SetTags(userID(r), r.PathValue("id"), body.Tags)
	switch {
	case errors.Is(err, store.ErrNotFound):
		fail(w, http.StatusNotFound, "item not found")
	case errors.Is(err, store.ErrInvalid):
		fail(w, http.StatusBadRequest, err.Error())
	case err != nil:
		fail(w, http.StatusInternalServerError, "更新失败: "+err.Error())
	default:
		ok(w, item)
	}
}
