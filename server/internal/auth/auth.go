package auth

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// LoadOrCreateToken 读取或生成服务端访问令牌。
// 优先级：环境变量 VF_TOKEN > <dataDir>/token.txt > 生成并落盘（0600）。
// 令牌格式与 App 本地接口一致：vf_ + 24 位 hex。
func LoadOrCreateToken(dataDir string) (string, error) {
	if v := strings.TrimSpace(os.Getenv("VF_TOKEN")); v != "" {
		return v, nil
	}
	path := filepath.Join(dataDir, "token.txt")
	if raw, err := os.ReadFile(path); err == nil {
		if v := strings.TrimSpace(string(raw)); v != "" {
			return v, nil
		}
	}
	token := "vf_" + randHex(12)
	if err := os.WriteFile(path, []byte(token+"\n"), 0o600); err != nil {
		return "", fmt.Errorf("生成令牌失败: %w", err)
	}
	return token, nil
}

// CheckBearer 校验 Authorization 头的 "Bearer <token>"。
// 使用常量时间比较，避免时序侧信道。
func CheckBearer(header, token string) bool {
	got := strings.TrimPrefix(header, "Bearer ")
	if len(got) != len(token) {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(got), []byte(token)) == 1
}

func randHex(n int) string {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		panic("crypto/rand 不可用: " + err.Error())
	}
	return hex.EncodeToString(b)
}
