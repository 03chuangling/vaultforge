// VaultForge 服务端（云端配套）入口。
//
// 环境变量：
//
//	VF_ADDR          监听地址，默认 ":8787"（如 "127.0.0.1:8787"）
//	VF_DATA_DIR      数据目录，默认 "./data"
//	VF_ALLOW_REGISTER 是否允许任意注册（默认仅允许第一个账号，用于引导）
//	VF_PBKDF2_ITERS  PBKDF2 迭代次数覆盖（仅影响新哈希，低配环境用）
package main

import (
	"log"
	"net/http"

	"github.com/03chuangling/vaultforge/server/internal/api"
	"github.com/03chuangling/vaultforge/server/internal/config"
	"github.com/03chuangling/vaultforge/server/internal/store"
)

func main() {
	cfg := config.Load()

	st, err := store.Open(cfg.DataDir)
	if err != nil {
		log.Fatalf("存储初始化失败: %v", err)
	}

	srv := api.New(cfg, st)

	log.Printf("VaultForge server %s | 监听 %s | 数据 %s", cfg.Version, cfg.Addr, st.Path())
	if !st.HasAccounts() {
		log.Printf("尚无账号：注册第一个账号（自动继承旧数据）→ POST http://<host>%s/api/v1/auth/register", cfg.Addr)
	} else {
		log.Printf("账号模式：登录获取令牌 → POST http://<host>%s/api/v1/auth/login", cfg.Addr)
	}
	log.Printf("连通性检查: GET http://<host>%s/api/v1", cfg.Addr)

	if err := http.ListenAndServe(cfg.Addr, srv.Handler()); err != nil {
		log.Fatal(err)
	}
}
