package api

import (
	"net/http"
	"time"

	"github.com/03chuangling/vaultforge/server/internal/model"
)

// POST /api/v1/sync/pull —— 增量拉取。
// 请求：{"deviceId":"...","since":<ms 游标，0=全量>}
// 响应 data：{serverTime, items, deletedIds, hasMore}
func (s *Server) handleSyncPull(w http.ResponseWriter, r *http.Request) {
	var req model.SyncPullRequest
	if err := readJSON(r, &req); err != nil {
		fail(w, http.StatusBadRequest, "invalid json: "+err.Error())
		return
	}
	if req.Since < 0 {
		req.Since = 0
	}
	items, deleted := s.store.Pull(req.Since)
	ok(w, model.SyncPullResponse{
		ServerTime: time.Now().UnixMilli(),
		Items:      items,
		DeletedIDs: deleted,
		HasMore:    false, // 骨架版不分页；条目量大后启用游标分页
	})
}

// POST /api/v1/sync/push —— 批量推送（LWW）。
// 请求：{"deviceId":"...","items":[VaultItem...]}
// 响应 data：{accepted:[id...], conflicts:[{id, serverItem}...]}
func (s *Server) handleSyncPush(w http.ResponseWriter, r *http.Request) {
	var req model.SyncPushRequest
	if err := readJSON(r, &req); err != nil {
		fail(w, http.StatusBadRequest, "invalid json: "+err.Error())
		return
	}
	accepted, conflicts, err := s.store.Push(req.Items)
	if err != nil {
		fail(w, http.StatusInternalServerError, "同步失败: "+err.Error())
		return
	}
	ok(w, model.SyncPushResponse{Accepted: accepted, Conflicts: conflicts})
}
