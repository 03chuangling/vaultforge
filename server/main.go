// VaultForge 服务端（云端配套）入口。
//
// 环境变量：
//
//	VF_ADDR     监听地址，默认 ":8787"（如 "127.0.0.1:8787"）
//	VF_DATA_DIR 数据目录，默认 "./data"
//	VF_TOKEN    访问令牌；未设置时自动生成并保存到 <数据目录>/token.txt
package main

import (
	"log"
	"net/http"

	"github.com/03chuangling/vaultforge/server/internal/api"
	"github.com/03chuangling/vaultforge/server/internal/auth"
	"github.com/03chuangling/vaultforge/server/internal/config"
	"github.com/03chuangling/vaultforge/server/internal/store"
)

func main() {
	cfg := config.Load()

	st, err := store.Open(cfg.DataDir)
	if err != nil {
		log.Fatalf("存储初始化失败: %v", err)
	}

	token, err := auth.LoadOrCreateToken(cfg.DataDir)
	if err != nil {
		log.Fatalf("令牌初始化失败: %v", err)
	}

	srv := api.New(cfg, st, token)

	log.Printf("VaultForge server %s | 监听 %s | 数据 %s", cfg.Version, cfg.Addr, st.Path())
	log.Printf("访问令牌: %s（可用 VF_TOKEN 覆盖，或编辑 <数据目录>/token.txt）", token)
	log.Printf("连通性检查: GET http://<host>%s/api/v1", cfg.Addr)

	if err := http.ListenAndServe(cfg.Addr, srv.Handler()); err != nil {
		log.Fatal(err)
	}
}
