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

// ---- Web 管理面板（v0.3.0）契约 ----

// ExecRequest SSH 命令执行请求（container 为空时在服务器执行，否则 docker exec）。
type ExecRequest struct {
	Command   string `json:"command"`
	Container string `json:"container"`
}

// DockerActionRequest Docker 容器操作请求。
type DockerActionRequest struct {
	Container string `json:"container"`
	Action    string `json:"action"` // start | stop | restart | logs
}

// DockerContainerInfo 容器信息。
type DockerContainerInfo struct {
	ID     string `json:"id"`
	Name   string `json:"name"`
	Status string `json:"status"`
	Image  string `json:"image"`
}

// FileEntry 远程文件条目。
type FileEntry struct {
	Name       string `json:"name"`
	Path       string `json:"path"`
	IsDir      bool   `json:"isDir"`
	Size       int64  `json:"size"`
	ModifiedAt int64  `json:"modifiedAt"`
}

// FileDeleteRequest 删除远程文件 / 文件夹请求。
type FileDeleteRequest struct {
	Path  string `json:"path"`
	IsDir bool   `json:"isDir"`
}

// BatchRequest 条目批量操作请求。
type BatchRequest struct {
	Action string   `json:"action"` // delete | tag | untag
	IDs    []string `json:"ids"`
	Tags   []string `json:"tags"`
}

// PasswordChangeRequest 修改密码请求。
type PasswordChangeRequest struct {
	OldPassword string `json:"oldPassword"`
	NewPassword string `json:"newPassword"`
}

// SessionInfo 会话概要（对外）。
type SessionInfo struct {
	ID        string `json:"id"`
	Current   bool   `json:"current"`
	CreatedAt int64  `json:"createdAt"`
	ExpiresAt int64  `json:"expiresAt"`
}

// MetricsData SSH 服务器指标快照（字段与 App 端 SshMetrics 对齐）。
type MetricsData struct {
	Ok              bool    `json:"ok"`
	Error           string  `json:"error,omitempty"`
	CPUPercent      float64 `json:"cpuPercent"`
	MemUsedPercent  float64 `json:"memUsedPercent"`
	MemUsedMb       int64   `json:"memUsedMb"`
	MemTotalMb      int64   `json:"memTotalMb"`
	NetRxKbps       float64 `json:"netRxKbps"`
	NetTxKbps       float64 `json:"netTxKbps"`
	DiskUsedPercent float64 `json:"diskUsedPercent"`
	DiskReadKbps    float64 `json:"diskReadKbps"`
	DiskWriteKbps   float64 `json:"diskWriteKbps"`
	Load1           string  `json:"load1,omitempty"`
	Kernel          string  `json:"kernel,omitempty"`
	FetchedAt       int64   `json:"fetchedAt"`
}
