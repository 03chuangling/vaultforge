package model

import (
	"errors"
	"fmt"
)

// VaultItem 与 App 端（com.vaultforge.app.model.VaultItem）字段一一对应，
// 是端云同步的核心契约。
//
// 服务端扩展字段 deleted：同步用墓碑标记（App 端 Json 配置了
// ignoreUnknownKeys，可安全忽略该字段）。
type VaultItem struct {
	ID        string   `json:"id"`
	Type      string   `json:"type"` // file | ssh | api
	Name      string   `json:"name"`
	Tags      []string `json:"tags,omitempty"`
	CreatedAt int64    `json:"createdAt"`
	UpdatedAt int64    `json:"updatedAt"`

	// ---- file ----
	Protocol string `json:"protocol,omitempty"`
	Address  string `json:"address,omitempty"`

	// ---- ssh ----
	Host       string `json:"host,omitempty"`
	Port       int    `json:"port,omitempty"`
	Username   string `json:"username,omitempty"`
	AuthMethod string `json:"authMethod,omitempty"`
	Secret     string `json:"secret,omitempty"`
	PrivateKey string `json:"privateKey,omitempty"`

	// ---- api ----
	Endpoint string `json:"endpoint,omitempty"`
	APIKey   string `json:"apiKey,omitempty"`
	DemoCode string `json:"demoCode,omitempty"`

	// ---- 探测结果 ----
	LastOk        *bool  `json:"lastOk,omitempty"`
	LastLatencyMs int64  `json:"lastLatencyMs,omitempty"`
	LastCheckedAt int64  `json:"lastCheckedAt,omitempty"`
	LastMessage   string `json:"lastMessage,omitempty"`

	// ---- 服务端扩展 ----
	Deleted bool `json:"deleted,omitempty"`
}

// ValidType 判断条目类型是否为 file / ssh / api。
func ValidType(t string) bool {
	switch t {
	case "file", "ssh", "api":
		return true
	}
	return false
}

// Validate 基础校验：type 必须合法，name 不能为空。
func (it *VaultItem) Validate() error {
	if !ValidType(it.Type) {
		return fmt.Errorf("type 必须是 file/ssh/api 之一，收到: %q", it.Type)
	}
	if it.Name == "" {
		return errors.New("name 不能为空")
	}
	return nil
}

// ---- 同步契约 ----

// SyncPullRequest 增量拉取请求。
type SyncPullRequest struct {
	DeviceID string `json:"deviceId"`
	Since    int64  `json:"since"` // Unix 毫秒游标；0 = 全量
}

// SyncPullResponse 增量拉取响应。
type SyncPullResponse struct {
	ServerTime int64        `json:"serverTime"`
	Items      []*VaultItem `json:"items"`
	DeletedIDs []string     `json:"deletedIds"`
	HasMore    bool         `json:"hasMore"`
}

// SyncPushRequest 批量推送请求。
type SyncPushRequest struct {
	DeviceID string       `json:"deviceId"`
	Items    []*VaultItem `json:"items"`
}

// SyncConflict 单条冲突：返回服务端当前版本，客户端按 LWW 处理。
type SyncConflict struct {
	ID         string     `json:"id"`
	ServerItem *VaultItem `json:"serverItem"`
}

// SyncPushResponse 批量推送响应。
type SyncPushResponse struct {
	Accepted  []string       `json:"accepted"`
	Conflicts []SyncConflict `json:"conflicts"`
}

// ---- 账号（鉴权）契约 ----

// AuthRequest 注册 / 登录请求。
type AuthRequest struct {
	Username string `json:"username"`
	Password string `json:"password"`
}

// UserInfo 对外暴露的账号信息（不含任何凭据）。
type UserInfo struct {
	ID        string `json:"id"`
	Username  string `json:"username"`
	CreatedAt int64  `json:"createdAt"`
}

// AuthData 注册 / 登录成功后的响应数据。
type AuthData struct {
	User      *UserInfo `json:"user"`
	Token     string    `json:"token"`
	ExpiresAt int64     `json:"expiresAt"`
}
