package config

import (
	"os"
	"strings"
)

// Version 服务端版本（与 App 版本解耦，单独演进）。
const Version = "0.3.0"

// Config 运行配置：全部来自环境变量，便于服务器 / 容器部署。
type Config struct {
	Addr          string // 监听地址（VF_ADDR）
	DataDir       string // 数据目录（VF_DATA_DIR）
	AllowRegister bool   // 是否允许任意注册（VF_ALLOW_REGISTER，默认仅允许第一个账号）
	Version       string
}

// Load 从环境变量加载配置并填充默认值。
func Load() Config {
	return Config{
		Addr:          envOr("VF_ADDR", ":8787"),
		DataDir:       envOr("VF_DATA_DIR", "./data"),
		AllowRegister: envBool("VF_ALLOW_REGISTER"),
		Version:       Version,
	}
}

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func envBool(key string) bool {
	switch strings.ToLower(strings.TrimSpace(os.Getenv(key))) {
	case "1", "true", "yes", "on":
		return true
	}
	return false
}
