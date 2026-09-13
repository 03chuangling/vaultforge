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

// ErrUsernameTaken 表示用户名已被占用。
var ErrUsernameTaken = errors.New("username taken")

const schemaVersion = 2

// Account 账号：密码只保存盐与 PBKDF2 哈希。
type Account struct {
	ID           string `json:"id"`
	Username     string `json:"username"`
	Salt         string `json:"salt"`
	PasswordHash string `json:"passwordHash"`
	Iterations   int    `json:"iterations"`
	CreatedAt    int64  `json:"createdAt"`
}

// Session 登录会话（map 键为令牌的 SHA-256 hex）。
type Session struct {
	UserID    string `json:"userId"`
	CreatedAt int64  `json:"createdAt"`
	ExpiresAt int64  `json:"expiresAt"`
}

// UserVault 单个用户的保险库数据。
type UserVault struct {
	Items    []*model.VaultItem `json:"items"`
	Settings map[string]any     `json:"settings"`
}

// FileData 是服务端落盘结构（data/vaultforge_server.json，schema v2）。
// Legacy 为 v1 旧格式数据暂存区：第一个注册的账号会自动继承。
type FileData struct {
	Meta     Meta                  `json:"meta"`
	Accounts map[string]*Account   `json:"accounts"`
	Sessions map[string]*Session   `json:"sessions"`
	Vaults   map[string]*UserVault `json:"vaults"`
	Legacy   *UserVault            `json:"legacy,omitempty"`
}

// Meta 记录文件级元信息。
type Meta struct {
	SchemaVersion int   `json:"schemaVersion"`
	CreatedAt     int64 `json:"createdAt"`
	LastSavedAt   int64 `json:"lastSavedAt"`
}

// Store 是内存数据 + JSON 文件持久化的存储层，全部方法并发安全。
// 后续如需切换 SQLite / PostgreSQL，保持方法集不变即可平滑替换。
type Store struct {
	mu   sync.RWMutex
	path string
	file FileData
}

// Open 加载（或初始化）指定目录下的数据文件。
func Open(dir string) (*Store, error) {
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return nil, fmt.Errorf("创建数据目录失败: %w", err)
	}
	s := &Store{path: filepath.Join(dir, "vaultforge_server.json")}
	if err := s.load(); err != nil {
		return nil, err
	}
	return s, nil
}

// Path 返回数据文件路径。
func (s *Store) Path() string { return s.path }

func (s *Store) load() error {
	raw, err := os.ReadFile(s.path)
	if errors.Is(err, os.ErrNotExist) {
		s.file = FileData{Meta: Meta{SchemaVersion: schemaVersion, CreatedAt: nowMs()}}
		s.ensureInit()
		return nil
	}
	if err != nil {
		return err
	}

	var probe map[string]json.RawMessage
	if err := json.Unmarshal(raw, &probe); err != nil {
		return fmt.Errorf("解析数据文件失败: %w", err)
	}

	if _, isV2 := probe["accounts"]; isV2 {
		if err := json.Unmarshal(raw, &s.file); err != nil {
			return fmt.Errorf("解析数据文件失败: %w", err)
		}
	} else {
		// v1（全局 items/settings）→ v2 迁移：暂存为 Legacy，等待账号继承。
		var old struct {
			Meta     Meta               `json:"meta"`
			Settings map[string]any     `json:"settings"`
			Items    []*model.VaultItem `json:"items"`
		}
		if err := json.Unmarshal(raw, &old); err != nil {
			return fmt.Errorf("解析 v1 数据文件失败: %w", err)
		}
		s.file = FileData{Meta: Meta{SchemaVersion: schemaVersion, CreatedAt: nowMs()}}
		if old.Meta.CreatedAt > 0 {
			s.file.Meta.CreatedAt = old.Meta.CreatedAt
		}
		if old.Meta.LastSavedAt > 0 {
			s.file.Meta.LastSavedAt = old.Meta.LastSavedAt
		}
		if len(old.Items) > 0 || len(old.Settings) > 0 {
			if old.Items == nil {
				old.Items = []*model.VaultItem{}
			}
			if old.Settings == nil {
				old.Settings = map[string]any{}
			}
			s.file.Legacy = &UserVault{Items: old.Items, Settings: old.Settings}
		}
	}

	s.ensureInit()
	return nil
}

func (s *Store) ensureInit() {
	if s.file.Accounts == nil {
		s.file.Accounts = map[string]*Account{}
	}
	if s.file.Sessions == nil {
		s.file.Sessions = map[string]*Session{}
	}
	if s.file.Vaults == nil {
		s.file.Vaults = map[string]*UserVault{}
	}
	if s.file.Meta.SchemaVersion < schemaVersion {
		s.file.Meta.SchemaVersion = schemaVersion
	}
	if s.file.Meta.CreatedAt == 0 {
		s.file.Meta.CreatedAt = nowMs()
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

// ---- 账号 ----

// HasAccounts 返回是否已有账号（决定注册是否开放）。
func (s *Store) HasAccounts() bool {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return len(s.file.Accounts) > 0
}

// FindAccountByUsername 按用户名（大小写不敏感）查找；不存在返回 nil。
func (s *Store) FindAccountByUsername(username string) *Account {
	s.mu.RLock()
	defer s.mu.RUnlock()
	for _, a := range s.file.Accounts {
		if a != nil && strings.EqualFold(a.Username, username) {
			cp := *a
			return &cp
		}
	}
	return nil
}

// GetAccount 按 id 查找；不存在返回 nil。
func (s *Store) GetAccount(id string) *Account {
	s.mu.RLock()
	defer s.mu.RUnlock()
	a := s.file.Accounts[id]
	if a == nil {
		return nil
	}
	cp := *a
	return &cp
}

// AddAccount 创建账号（用户名唯一）；第一个账号自动继承旧数据。
func (s *Store) AddAccount(username, salt, hash string, iterations int) (*Account, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, a := range s.file.Accounts {
		if a != nil && strings.EqualFold(a.Username, username) {
			return nil, ErrUsernameTaken
		}
	}
	acct := &Account{
		ID:           NewID(),
		Username:     username,
		Salt:         salt,
		PasswordHash: hash,
		Iterations:   iterations,
		CreatedAt:    nowMs(),
	}
	s.file.Accounts[acct.ID] = acct

	if s.file.Legacy != nil {
		s.file.Vaults[acct.ID] = s.file.Legacy
		s.file.Legacy = nil
	}

	if err := s.saveLocked(); err != nil {
		return nil, err
	}
	cp := *acct
	return &cp, nil
}

// ---- 会话 ----

// AddSession 保存会话（tokenHash = 令牌的 SHA-256 hex）。
func (s *Store) AddSession(tokenHash, userID string, expiresAt int64) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.file.Sessions[tokenHash] = &Session{UserID: userID, CreatedAt: nowMs(), ExpiresAt: expiresAt}
	s.cleanupExpiredLocked()
	return s.saveLocked()
}

// GetSession 按令牌哈希查找会话；不存在返回 nil。
func (s *Store) GetSession(tokenHash string) *Session {
	s.mu.RLock()
	defer s.mu.RUnlock()
	sess := s.file.Sessions[tokenHash]
	if sess == nil {
		return nil
	}
	cp := *sess
	return &cp
}

// DeleteSession 删除会话；返回原本是否存在。
func (s *Store) DeleteSession(tokenHash string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.file.Sessions[tokenHash]; !ok {
		return false
	}
	delete(s.file.Sessions, tokenHash)
	_ = s.saveLocked()
	return true
}

func (s *Store) cleanupExpiredLocked() {
	now := nowMs()
	for k, v := range s.file.Sessions {
		if v == nil || v.ExpiresAt <= now {
			delete(s.file.Sessions, k)
		}
	}
}

// ---- 保险库（按用户隔离） ----

func (s *Store) vaultLocked(userID string) *UserVault {
	v := s.file.Vaults[userID]
	if v == nil {
		v = &UserVault{}
		s.file.Vaults[userID] = v
	}
	if v.Items == nil {
		v.Items = []*model.VaultItem{}
	}
	if v.Settings == nil {
		v.Settings = map[string]any{}
	}
	return v
}

// ---- 条目读取 ----

// ListItems 返回该用户未删除条目；支持 type 过滤、名称模糊搜索（q）、标签过滤（tag）。
func (s *Store) ListItems(userID, itemType, q, tag string) []*model.VaultItem {
	s.mu.RLock()
	defer s.mu.RUnlock()
	v := s.file.Vaults[userID]
	if v == nil {
		return []*model.VaultItem{}
	}
	out := make([]*model.VaultItem, 0, len(v.Items))
	for _, it := range v.Items {
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

// GetItem 按 id 取该用户未删除条目；不存在返回 nil。
func (s *Store) GetItem(userID, id string) *model.VaultItem {
	s.mu.RLock()
	defer s.mu.RUnlock()
	v := s.file.Vaults[userID]
	if v == nil {
		return nil
	}
	it := findItemLocked(v, id)
	if it == nil || it.Deleted {
		return nil
	}
	return clone(it)
}

// ---- 条目写入 ----

// UpsertItem 新建或覆盖条目（App upsert 语义）：
// id 为空则生成 UUID，updatedAt 置为当前，createdAt 为空则补齐。
func (s *Store) UpsertItem(userID string, item *model.VaultItem) (*model.VaultItem, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	v := s.vaultLocked(userID)
	now := nowMs()
	if item.ID == "" {
		item.ID = NewID()
	}
	if item.UpdatedAt <= 0 {
		item.UpdatedAt = now
	}
	old := findItemLocked(v, item.ID)
	if item.CreatedAt <= 0 {
		if old != nil && old.CreatedAt > 0 {
			item.CreatedAt = old.CreatedAt
		} else {
			item.CreatedAt = now
		}
	}
	putItemLocked(v, item)
	if err := s.saveLocked(); err != nil {
		return nil, err
	}
	return clone(item), nil
}

// PatchItem 部分更新（与 App 本地 PATCH 语义对齐）：
// 仅白名单字段可改，updatedAt 自动刷新。
func (s *Store) PatchItem(userID, id string, patch map[string]any) (*model.VaultItem, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	v := s.file.Vaults[userID]
	if v == nil {
		return nil, ErrNotFound
	}
	it := findItemLocked(v, id)
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
	for k, val := range sanitized {
		merged[k] = val
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
	putItemLocked(v, next)
	if err := s.saveLocked(); err != nil {
		return nil, err
	}
	return clone(next), nil
}

// DeleteItem 软删除（墓碑）：记录保留并标记 deleted，供同步下发。
func (s *Store) DeleteItem(userID, id string) (*model.VaultItem, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	v := s.file.Vaults[userID]
	if v == nil {
		return nil, ErrNotFound
	}
	it := findItemLocked(v, id)
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
func (s *Store) SetTags(userID, id string, tags []string) (*model.VaultItem, error) {
	return s.PatchItem(userID, id, map[string]any{"tags": tags})
}

// ---- 同步 ----

// Pull 增量拉取：返回 updatedAt > since 的条目与删除 id 列表（0 = 全量）。
func (s *Store) Pull(userID string, since int64) ([]*model.VaultItem, []string) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	items := make([]*model.VaultItem, 0)
	deleted := make([]string, 0)
	v := s.file.Vaults[userID]
	if v == nil {
		return items, deleted
	}
	for _, it := range v.Items {
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
func (s *Store) Push(userID string, items []*model.VaultItem) ([]string, []model.SyncConflict, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	accepted := make([]string, 0, len(items))
	conflicts := make([]model.SyncConflict, 0)
	v := s.vaultLocked(userID)
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
		old := findItemLocked(v, in.ID)
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
		putItemLocked(v, in)
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

// Settings 返回该用户设置镜像的浅拷贝。
func (s *Store) Settings(userID string) map[string]any {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := map[string]any{}
	v := s.file.Vaults[userID]
	if v == nil {
		return out
	}
	for k, val := range v.Settings {
		out[k] = val
	}
	return out
}

// MergeSettings 浅合并该用户设置并落盘，返回合并结果。
func (s *Store) MergeSettings(userID string, patch map[string]any) (map[string]any, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	v := s.vaultLocked(userID)
	for k, val := range patch {
		v.Settings[k] = val
	}
	if err := s.saveLocked(); err != nil {
		return nil, err
	}
	out := make(map[string]any, len(v.Settings))
	for k, val := range v.Settings {
		out[k] = val
	}
	return out, nil
}

// ---- 账号安全 ----

// ChangePassword 更新账号密码（盐 / 哈希 / 迭代次数）。
func (s *Store) ChangePassword(userID, salt, hash string, iterations int) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	a := s.file.Accounts[userID]
	if a == nil {
		return ErrNotFound
	}
	a.Salt = salt
	a.PasswordHash = hash
	a.Iterations = iterations
	return s.saveLocked()
}

// ---- 会话管理 ----

// SessionEntry 会话的对外描述（不含完整令牌哈希）。
type SessionEntry struct {
	ID        string `json:"id"` // 令牌哈希前 12 位
	CreatedAt int64  `json:"createdAt"`
	ExpiresAt int64  `json:"expiresAt"`
}

// ListSessions 返回该用户全部未过期会话（按创建时间倒序）。
func (s *Store) ListSessions(userID string) []SessionEntry {
	s.mu.RLock()
	defer s.mu.RUnlock()
	now := nowMs()
	out := []SessionEntry{}
	for h, sess := range s.file.Sessions {
		if sess == nil || sess.UserID != userID || sess.ExpiresAt <= now {
			continue
		}
		id := h
		if len(id) > 12 {
			id = id[:12]
		}
		out = append(out, SessionEntry{ID: id, CreatedAt: sess.CreatedAt, ExpiresAt: sess.ExpiresAt})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].CreatedAt > out[j].CreatedAt })
	return out
}

// RevokeSession 按会话 id（令牌哈希前 12 位）吊销该用户名下的会话。
func (s *Store) RevokeSession(userID, sessionID string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	for h, sess := range s.file.Sessions {
		if sess == nil || sess.UserID != userID {
			continue
		}
		id := h
		if len(id) > 12 {
			id = id[:12]
		}
		if id == sessionID {
			delete(s.file.Sessions, h)
			_ = s.saveLocked()
			return true
		}
	}
	return false
}

// CountAccounts 账号总数。
func (s *Store) CountAccounts() int {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return len(s.file.Accounts)
}

// CountSessions 未过期会话总数。
func (s *Store) CountSessions() int {
	s.mu.RLock()
	defer s.mu.RUnlock()
	now := nowMs()
	n := 0
	for _, sess := range s.file.Sessions {
		if sess != nil && sess.ExpiresAt > now {
			n++
		}
	}
	return n
}

// FileSize 数据文件字节大小（不存在返回 0）。
func (s *Store) FileSize() int64 {
	fi, err := os.Stat(s.path)
	if err != nil {
		return 0
	}
	return fi.Size()
}

// ---- 探测结果 ----

// SetProbeResult 写入探测结果（lastOk / lastLatencyMs / lastCheckedAt / lastMessage）。
func (s *Store) SetProbeResult(userID, id string, okv bool, latencyMs int64, message string) (*model.VaultItem, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	v := s.file.Vaults[userID]
	if v == nil {
		return nil, ErrNotFound
	}
	it := findItemLocked(v, id)
	if it == nil || it.Deleted {
		return nil, ErrNotFound
	}
	okCopy := okv
	now := nowMs()
	it.LastOk = &okCopy
	it.LastLatencyMs = latencyMs
	it.LastCheckedAt = now
	it.LastMessage = message
	it.UpdatedAt = now
	if err := s.saveLocked(); err != nil {
		return nil, err
	}
	return clone(it), nil
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

func putItemLocked(v *UserVault, item *model.VaultItem) {
	for i, it := range v.Items {
		if it != nil && it.ID == item.ID {
			v.Items[i] = item
			return
		}
	}
	v.Items = append(v.Items, item)
}

func findItemLocked(v *UserVault, id string) *model.VaultItem {
	for _, it := range v.Items {
		if it != nil && it.ID == id {
			return it
		}
	}
	return nil
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
