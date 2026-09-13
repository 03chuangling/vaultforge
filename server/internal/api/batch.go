package api

import (
	"net/http"
	"strings"
	"sync"

	"github.com/03chuangling/vaultforge/server/internal/model"
	"github.com/03chuangling/vaultforge/server/internal/probe"
)

// batchMaxIDs 单次批量操作条目数上限。
const batchMaxIDs = 200

// POST /api/v1/items/batch —— 批量操作：delete / tag / untag / check。
func (s *Server) handleBatch(w http.ResponseWriter, r *http.Request) {
	var req model.BatchRequest
	if err := readJSON(r, &req); err != nil {
		fail(w, http.StatusBadRequest, "invalid json: "+err.Error())
		return
	}
	action := strings.ToLower(strings.TrimSpace(req.Action))
	if len(req.IDs) == 0 {
		fail(w, http.StatusBadRequest, "ids 不能为空")
		return
	}
	if len(req.IDs) > batchMaxIDs {
		fail(w, http.StatusBadRequest, "单次批量操作上限 200 条")
		return
	}
	uid := userID(r)

	switch action {
	case "delete":
		affected := 0
		for _, id := range req.IDs {
			if _, err := s.store.DeleteItem(uid, id); err == nil {
				affected++
			}
		}
		ok(w, map[string]any{"affected": affected})

	case "tag", "untag":
		tags := cleanBatchTags(req.Tags)
		if len(tags) == 0 {
			fail(w, http.StatusBadRequest, "tags 不能为空")
			return
		}
		affected := 0
		for _, id := range req.IDs {
			item := s.store.GetItem(uid, id)
			if item == nil {
				continue
			}
			next := item.Tags
			if action == "tag" {
				for _, t := range tags {
					if !containsBatchTag(next, t) {
						next = append(next, t)
					}
				}
			} else {
				filtered := make([]string, 0, len(next))
				for _, t := range next {
					if !containsBatchTag(tags, t) {
						filtered = append(filtered, t)
					}
				}
				next = filtered
			}
			if _, err := s.store.SetTags(uid, id, next); err == nil {
				affected++
			}
		}
		ok(w, map[string]any{"affected": affected})

	case "check":
		type checkResult struct {
			ID        string `json:"id"`
			Ok        bool   `json:"ok"`
			LatencyMs int64  `json:"latencyMs"`
			Message   string `json:"message"`
		}
		results := make([]checkResult, len(req.IDs))
		var wg sync.WaitGroup
		sem := make(chan struct{}, 4) // 并发上限 4
		for i, id := range req.IDs {
			wg.Add(1)
			go func(i int, id string) {
				defer wg.Done()
				sem <- struct{}{}
				defer func() { <-sem }()
				item := s.store.GetItem(uid, id)
				if item == nil {
					results[i] = checkResult{ID: id, Message: "条目不存在"}
					return
				}
				res := probe.Probe(item)
				_, _ = s.store.SetProbeResult(uid, id, res.Ok, res.LatencyMs, res.Message)
				results[i] = checkResult{ID: id, Ok: res.Ok, LatencyMs: res.LatencyMs, Message: res.Message}
			}(i, id)
		}
		wg.Wait()
		ok(w, map[string]any{"results": results})

	default:
		fail(w, http.StatusBadRequest, "不支持的批量操作："+req.Action)
	}
}

func cleanBatchTags(tags []string) []string {
	out := []string{}
	seen := map[string]bool{}
	for _, t := range tags {
		t = strings.TrimSpace(t)
		if t == "" || seen[t] {
			continue
		}
		seen[t] = true
		out = append(out, t)
	}
	return out
}

func containsBatchTag(list []string, v string) bool {
	for _, t := range list {
		if t == v {
			return true
		}
	}
	return false
}
