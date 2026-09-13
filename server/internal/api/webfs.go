package api

import (
	"embed"
	"io/fs"
	"net/http"
)

//go:embed static
var staticFS embed.FS

var assetsHandler = func() http.Handler {
	sub, err := fs.Sub(staticFS, "static/assets")
	if err != nil {
		panic("web assets not embedded: " + err.Error())
	}
	return http.StripPrefix("/assets/", http.FileServer(http.FS(sub)))
}()

// GET / —— Web 管理面板入口（单页应用）。
func (s *Server) handleWeb(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/" {
		s.handleNotFound(w, r)
		return
	}
	data, err := staticFS.ReadFile("static/index.html")
	if err != nil {
		fail(w, http.StatusInternalServerError, "web ui not embedded")
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-cache")
	_, _ = w.Write(data)
}

// GET /assets/* —— 静态资源（JS / CSS / 图标）。
func (s *Server) handleWebAsset(w http.ResponseWriter, r *http.Request) {
	assetsHandler.ServeHTTP(w, r)
}
