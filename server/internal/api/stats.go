package api

import (
	"net/http"
	"sort"
	"time"
)

// GET /api/v1/stats —— Web 面板仪表盘统计（条目 / 类型 / 状态 / 标签 + 服务信息）。
func (s *Server) handleStats(w http.ResponseWriter, r *http.Request) {
	items := s.store.ListItems(userID(r), "", "", "")
	byType := map[string]int{"file": 0, "ssh": 0, "api": 0}
	byStatus := map[string]int{"up": 0, "warn": 0, "down": 0, "unknown": 0}
	tagSet := map[string]int{}
	var lastUpdated int64
	for _, it := range items {
		byType[it.Type]++
		status := "unknown"
		switch {
		case it.LastCheckedAt <= 0 || it.LastOk == nil:
			status = "unknown"
		case !*it.LastOk:
			status = "down"
		case it.LastLatencyMs > 300:
			status = "warn"
		default:
			status = "up"
		}
		byStatus[status]++
		for _, t := range it.Tags {
			tagSet[t]++
		}
		if it.UpdatedAt > lastUpdated {
			lastUpdated = it.UpdatedAt
		}
	}

	type tagCount struct {
		Name  string `json:"name"`
		Count int    `json:"count"`
	}
	tags := make([]tagCount, 0, len(tagSet))
	for k, v := range tagSet {
		tags = append(tags, tagCount{k, v})
	}
	sort.Slice(tags, func(i, j int) bool {
		if tags[i].Count != tags[j].Count {
			return tags[i].Count > tags[j].Count
		}
		return tags[i].Name < tags[j].Name
	})
	if len(tags) > 12 {
		tags = tags[:12]
	}

	ok(w, map[string]any{
		"items": map[string]any{
			"total":         len(items),
			"byType":        byType,
			"byStatus":      byStatus,
			"tags":          tags,
			"lastUpdatedAt": lastUpdated,
		},
		"server": map[string]any{
			"version":  s.cfg.Version,
			"auth":     "account",
			"register": s.registerState(),
			"accounts": s.store.CountAccounts(),
			"sessions": s.store.CountSessions(),
			"dataSize": s.store.FileSize(),
			"uptimeMs": time.Since(s.startedAt).Milliseconds(),
			"time":     time.Now().UnixMilli(),
		},
	})
}

// registerState 返回注册开关状态（open / closed）。
func (s *Server) registerState() string {
	if s.store.HasAccounts() && !s.cfg.AllowRegister {
		return "closed"
	}
	return "open"
}
