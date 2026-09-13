package store

import (
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/03chuangling/vaultforge/server/internal/model"
)

// ErrNotFound 表示条目不存在或已被软删除。
var ErrNotFound = errors.New("item not found")

// ErrInvalid 表示传入的条目数据不合法。
var ErrInvalid = errors.New("invalid item")

const schemaVersion = 1

// FileData 是服务端落盘结构（data/vaultforge_server.json）。
// Items 为全量条目（含墓碑，deleted=true），Settings 为端侧设置镜像。
type FileData struct {
	Meta     Meta               `json:"meta"`
	Settings map[string]any     `json:"settings"`
	Items    []*model.VaultItem `json:"items"`
}

// Meta 记录文件级元信息。
type Meta struct {
	SchemaVersion int   `json:"schemaVersion"`
	CreatedAt     int64 `json:"createdAt"`
	LastSavedAt   int64 `json:"lastSavedAt"`
}

// Store 是内存索引 + JSON 文件持久化的存储层，全部方法并发安全。
// 后续如需切换 SQLite / PostgreSQL，保持方法集不变即可平滑替换。
type Store struct {
	mu   sync.RWMutex
	path string
	file FileData
	byID map[string]*model.VaultItem
}

// Open 加载（或初始化）指定目录下的数据文件。
func Open(dir string) (*Store, error) {
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return nil, fmt.Errorf("创建数据目录失败: %w", err)
	}
	s := &Store{
		path: filepath.Join(dir, "vaultforge_server.json"),
		byID: map[string]*model.VaultItem{},
	}
	if err := s.load(); err != nil {
		return nil, err
	}
	return s, nil
}

// Path 返回数据文件路径。
func (s *Store) Path() string { return s.path }

func (s *Store) load() error {
	raw, err := os.ReadFile(s.path)
	fresh := false
	if errors.Is(err, os.ErrNotExist) {
		fresh = true
		s.file = FileData{Meta: Meta{SchemaVersion: schemaVersion, CreatedAt: nowMs()}}
	} else if err != nil {
		return err
	} else if err := json.Unmarshal(raw, &s.file); err != nil {
		return fmt.Errorf("解析数据文件失败: %w", err)
	}
	if s.file.Items == nil {
		s.file.Items = []*model.VaultItem{}
	}
	if s.file.Settings == nil {
		s.file.Settings = map[string]any{}
	}
	if s.file.Meta.SchemaVersion == 0 {
		s.file.Meta.SchemaVersion = schemaVersion
	}
	s.reindexLocked()
	if fresh {
		return s.saveLocked()
	}
	return nil
}

func (s *Store) reindexLocked() {
	s.byID = make(map[string]*model.VaultItem, len(s.file.Items))
	for _, it := range s.file.Items {
		if it != nil && it.ID != "" {
			s.byID[it.ID] = it
		}
	}
}

// saveLocked 原子落盘（写临时文件后 rename）。调用方须持有写锁。
func (s *Store) saveLocked() error {
	s.file.Meta.LastSavedAt = nowMs()
	raw, err := json.MarshalIndent(s.file, "", "  ")
	if err != nil {
		return err
	}
	tmp := s.path + ".tmp"
	if err := os.WriteFile(tmp, raw, 0o644); err != nil {
		return err
	}
	return os.Rename(tmp, s.path)
}

// putLocked 写入或替换条目（不落盘）。调用方须持有写锁。
func (s *Store) putLocked(item *model.VaultItem) {
	for i, it := range s.file.Items {
		if it != nil && it.ID == item.ID {
			s.file.Items[i] = item
			s.byID[item.ID] = item
			return
		}
	}
	s.file.Items = append(s.file.Items, item)
	s.byID[item.ID] = item
}

// ---- 条目读取 ----

// ListItems 返回未删除条目；支持 type 过滤、名称模糊搜索（q）、标签过滤（tag）。
// 按 updatedAt 倒序（与 App 列表一致：最近更新在前）。
func (s *Store) ListItems(itemType, q, tag string) []*model.VaultItem {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := make([]*model.VaultItem, 0, len(s.file.Items))
	for _, it := range s.file.Items {
		if it == nil || it.Deleted {
			continue
		}
		if itemType != "" && it.Type != itemType {
			continue
		}
		if q != "" && !strings.Contains(strings.ToLower(it.Name), strings.ToLower(q)) {
			continue
		}
		if tag != "" && !containsStr(it.Tags, tag) {
			continue
		}
		out = append(out, clone(it))
	}
	sort.Slice(out, func(i, j int) bool { return out[i].UpdatedAt > out[j].UpdatedAt })
	return out
}

// GetItem 按 id 取未删除条目；不存在返回 nil。
func (s *Store) GetItem(id string) *model.VaultItem {
	s.mu.RLock()
	defer s.mu.RUnlock()
	it := s.byID[id]
	if it == nil || it.Deleted {
		return nil
	}
	return clone(it)
}

// ItemCount 返回未删除条目数。
func (s *Store) ItemCount() int {
	s.mu.RLock()
	defer s.mu.RUnlock()
	n := 0
	for _, it := range s.file.Items {
		if it != nil && !it.Deleted {
			n++
		}
	}
	return n
}

// ---- 条目写入 ----

// UpsertItem 新建或覆盖条目（App upsert 语义）：
// id 为空则生成 UUID，updatedAt 置为当前，createdAt 为空则补齐。
func (s *Store) UpsertItem(item *model.VaultItem) (*model.VaultItem, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.upsertLocked(item)
}

func (s *Store) upsertLocked(item *model.VaultItem) (*model.VaultItem, error) {
	now := nowMs()
	if item.ID == "" {
		item.ID = NewID()
	}
	if item.UpdatedAt <= 0 {
		item.UpdatedAt = now
	}
	old := s.byID[item.ID]
	if item.CreatedAt <= 0 {
		if old != nil && old.CreatedAt > 0 {
			item.CreatedAt = old.CreatedAt
		} else {
			item.CreatedAt = now
		}
	}
	s.putLocked(item)
	if err := s.saveLocked(); err != nil {
		return nil, err
	}
	return clone(item), nil
}

// PatchItem 部分更新（与 App 本地 PATCH 语义对齐）：
// 仅白名单字段（name/tags/protocol/address/host/port/username/authMethod/
// secret/privateKey/endpoint/apiKey/demoCode）可改，updatedAt 自动刷新。
func (s *Store) PatchItem(id string, patch map[string]any) (*model.VaultItem, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	it := s.byID[id]
	if it == nil || it.Deleted {
		return nil, ErrNotFound
	}

	sanitized := sanitizePatch(patch)

	raw, err := json.Marshal(it)
	if err != nil {
		return nil, err
	}
	merged := map[string]any{}
	if err := json.Unmarshal(raw, &merged); err != nil {
		return nil, err
	}
	for k, v := range sanitized {
		merged[k] = v
	}
	merged["updatedAt"] = nowMs()

	raw2, err := json.Marshal(merged)
	if err != nil {
		return nil, err
	}
	next := &model.VaultItem{}
	if err := json.Unmarshal(raw2, next); err != nil {
		return nil, fmt.Errorf("%w：%v", ErrInvalid, err)
	}
	if err := next.Validate(); err != nil {
		return nil, fmt.Errorf("%w：%v", ErrInvalid, err)
	}
	next.ID = id
	s.putLocked(next)
	if err := s.saveLocked(); err != nil {
		return nil, err
	}
	return clone(next), nil
}

// DeleteItem 软删除（墓碑）：记录保留并标记 deleted，供同步下发。
// 已删除或不存在返回 ErrNotFound。
func (s *Store) DeleteItem(id string) (*model.VaultItem, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	it := s.byID[id]
	if it == nil || it.Deleted {
		return nil, ErrNotFound
	}
	it.Deleted = true
	it.UpdatedAt = nowMs()
	if err := s.saveLocked(); err != nil {
		return nil, err
	}
	return clone(it), nil
}

// SetTags 覆盖标签（与 App PUT /items/{id}/tags 一致）。
func (s *Store) SetTags(id string, tags []string) (*model.VaultItem, error) {
	return s.PatchItem(id, map[string]any{"tags": tags})
}

// ---- 同步 ----

// Pull 增量拉取：返回 updatedAt > since 的条目与删除 id 列表（0 = 全量）。
func (s *Store) Pull(since int64) ([]*model.VaultItem, []string) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	items := make([]*model.VaultItem, 0)
	deleted := make([]string, 0)
	for _, it := range s.file.Items {
		if it == nil || it.UpdatedAt <= since {
			continue
		}
		if it.Deleted {
			deleted = append(deleted, it.ID)
			continue
		}
		items = append(items, clone(it))
	}
	sort.Slice(items, func(i, j int) bool { return items[i].UpdatedAt < items[j].UpdatedAt })
	return items, deleted
}

// Push 批量推送（LWW）：incoming.updatedAt >= 服务端版本则接受；
// 否则记为冲突并返回服务端当前版本。
func (s *Store) Push(items []*model.VaultItem) ([]string, []model.SyncConflict, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	accepted := make([]string, 0, len(items))
	conflicts := make([]model.SyncConflict, 0)
	now := nowMs()
	for _, in := range items {
		if in == nil {
			continue
		}
		if in.ID == "" {
			in.ID = NewID()
		}
		if in.UpdatedAt <= 0 {
			in.UpdatedAt = now
		}
		old := s.byID[in.ID]
		if old != nil && in.UpdatedAt < old.UpdatedAt {
			conflicts = append(conflicts, model.SyncConflict{ID: in.ID, ServerItem: clone(old)})
			continue
		}
		if in.CreatedAt <= 0 {
			if old != nil && old.CreatedAt > 0 {
				in.CreatedAt = old.CreatedAt
			} else {
				in.CreatedAt = in.UpdatedAt
			}
		}
		s.putLocked(in)
		accepted = append(accepted, in.ID)
	}
	if len(accepted) > 0 {
		if err := s.saveLocked(); err != nil {
			return accepted, conflicts, err
		}
	}
	return accepted, conflicts, nil
}

// ---- 设置镜像 ----

// Settings 返回设置镜像的浅拷贝。
func (s *Store) Settings() map[string]any {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := make(map[string]any, len(s.file.Settings))
	for k, v := range s.file.Settings {
		out[k] = v
	}
	return out
}

// MergeSettings 浅合并设置并落盘，返回合并结果。
func (s *Store) MergeSettings(patch map[string]any) (map[string]any, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for k, v := range patch {
		s.file.Settings[k] = v
	}
	if err := s.saveLocked(); err != nil {
		return nil, err
	}
	out := make(map[string]any, len(s.file.Settings))
	for k, v := range s.file.Settings {
		out[k] = v
	}
	return out, nil
}

// ---- 工具 ----

// NewID 生成 UUID v4 字符串（与 App 端条目 id 格式一致）。
func NewID() string {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		return fmt.Sprintf("vf-%d", nowMs())
	}
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}

// sanitizePatch 过滤 PATCH 字段：白名单 + 类型归一化（与 App 行为对齐）。
func sanitizePatch(patch map[string]any) map[string]any {
	allowed := map[string]bool{
		"name": true, "tags": true, "protocol": true, "address": true,
		"host": true, "port": true, "username": true, "authMethod": true,
		"secret": true, "privateKey": true, "endpoint": true, "apiKey": true,
		"demoCode": true,
	}
	out := map[string]any{}
	for k, v := range patch {
		if !allowed[k] {
			continue
		}
		switch k {
		case "tags":
			var tags []string
			switch arr := v.(type) {
			case []string:
				tags = append([]string(nil), arr...)
			case []any:
				for _, e := range arr {
					if sv, ok := e.(string); ok {
						tags = append(tags, sv)
					}
				}
			default:
				continue
			}
			out["tags"] = tags
		case "port":
			switch pv := v.(type) {
			case float64:
				out["port"] = int(pv)
			case string:
				if n, err := strconv.Atoi(pv); err == nil {
					out["port"] = n
				}
			}
		default:
			if sv, ok := v.(string); ok {
				out[k] = sv
			}
		}
	}
	return out
}

func nowMs() int64 { return time.Now().UnixMilli() }

func clone(it *model.VaultItem) *model.VaultItem {
	if it == nil {
		return nil
	}
	cp := *it
	if it.Tags != nil {
		cp.Tags = append([]string(nil), it.Tags...)
	}
	return &cp
}

func containsStr(list []string, v string) bool {
	for _, s := range list {
		if s == v {
			return true
		}
	}
	return false
}
