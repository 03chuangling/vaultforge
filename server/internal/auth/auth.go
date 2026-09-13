// Package auth 提供密码哈希（PBKDF2-HMAC-SHA256）与会话令牌工具。
package auth

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"strings"
)

// SessionTokenPrefix 会话令牌前缀（作为 Bearer 令牌使用）。
const SessionTokenPrefix = "vfs_"

// NewSessionToken 生成随机会话令牌：vfs_ + 32 位 hex。
func NewSessionToken() (string, error) {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		return "", fmt.Errorf("生成会话令牌失败: %w", err)
	}
	return SessionTokenPrefix + hex.EncodeToString(b), nil
}

// HashToken 计算令牌的 SHA-256（hex）；服务端只保存哈希。
func HashToken(token string) string {
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:])
}

// BearerToken 从 Authorization 头提取 Bearer 令牌。
func BearerToken(header string) (string, bool) {
	const prefix = "Bearer "
	if !strings.HasPrefix(header, prefix) {
		return "", false
	}
	tok := strings.TrimSpace(strings.TrimPrefix(header, prefix))
	if tok == "" {
		return "", false
	}
	return tok, true
}
