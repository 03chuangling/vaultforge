package api

import (
	"net/http"

	"github.com/03chuangling/vaultforge/server/internal/probe"
)

// POST /api/v1/items/{id}/check —— 立即探测（file / ssh / api），并写入探测结果。
func (s *Server) handleItemCheck(w http.ResponseWriter, r *http.Request) {
	item := s.store.GetItem(userID(r), r.PathValue("id"))
	if item == nil {
		fail(w, http.StatusNotFound, "item not found")
		return
	}
	res := probe.Probe(item)
	updated, err := s.store.SetProbeResult(userID(r), item.ID, res.Ok, res.LatencyMs, res.Message)
	if err != nil {
		fail(w, http.StatusInternalServerError, "写入探测结果失败："+err.Error())
		return
	}
	ok(w, map[string]any{"result": res, "item": updated})
}
