package api

import (
	"errors"
	"io"
	"net/http"
	"net/url"
	"path"
	"strconv"
	"strings"

	"github.com/03chuangling/vaultforge/server/internal/model"
	"github.com/03chuangling/vaultforge/server/internal/sshx"
)

// ---- 远程文件管理（SSH / SFTP / WebDAV） ----

// fileItem 取出支持文件浏览的条目：ssh 或 file(sftp / webdav)。
func (s *Server) fileItem(w http.ResponseWriter, r *http.Request) *model.VaultItem {
	item := s.store.GetItem(userID(r), r.PathValue("id"))
	if item == nil {
		fail(w, http.StatusNotFound, "item not found")
		return nil
	}
	if item.Type == "ssh" {
		return item
	}
	if item.Type == "file" {
		p := strings.ToLower(strings.TrimSpace(item.Protocol))
		if p == "sftp" || p == "webdav" {
			return item
		}
	}
	fail(w, http.StatusBadRequest, "该条目不支持文件浏览（支持 SSH / SFTP / WebDAV）")
	return nil
}

// sftpItem 返回可用于 SFTP 的条目：ssh 原样；file+sftp 按地址重组字段。
func sftpItem(item *model.VaultItem) (*model.VaultItem, error) {
	if item.Type == "ssh" {
		return item, nil
	}
	addr := strings.TrimSpace(item.Address)
	addr = strings.TrimPrefix(addr, "sftp://")
	addr = strings.TrimPrefix(addr, "SFTP://")
	if addr == "" {
		return nil, errors.New("未填写地址")
	}
	host := addr
	port := 22
	if i := strings.LastIndex(addr, ":"); i > 0 && !strings.Contains(addr[i+1:], "]") {
		if p, err := strconv.Atoi(addr[i+1:]); err == nil {
			host = addr[:i]
			port = p
		}
	}
	host = strings.Trim(host, "[]")
	cp := *item
	cp.ID = item.ID + "~sftp" // 独立连接缓存，避免与 ssh 条目冲突
	cp.Host = host
	cp.Port = port
	cp.Username = item.Username
	cp.Secret = item.Secret
	cp.PrivateKey = ""
	cp.AuthMethod = "password"
	return &cp, nil
}

// GET /api/v1/items/{id}/files?path= —— 目录列表。
func (s *Server) handleFilesList(w http.ResponseWriter, r *http.Request) {
	item := s.fileItem(w, r)
	if item == nil {
		return
	}
	dir := r.URL.Query().Get("path")
	if strings.TrimSpace(dir) == "" {
		dir = "/"
	}
	var (
		entries []model.FileEntry
		err     error
	)
	switch {
	case item.Type == "ssh":
		entries, err = sshx.SFTPList(item, dir)
	case strings.EqualFold(item.Protocol, "webdav"):
		entries, err = webdavList(item, dir)
	default: // file + sftp
		si, e := sftpItem(item)
		if e != nil {
			fail(w, http.StatusBadRequest, e.Error())
			return
		}
		entries, err = sshx.SFTPList(si, dir)
	}
	if err != nil {
		fail(w, http.StatusBadGateway, "读取目录失败："+err.Error())
		return
	}
	ok(w, map[string]any{"path": dir, "entries": entries})
}

// firstWriteTracker 记录是否已写入响应体（用于错误时判断能否改写响应）。
type firstWriteTracker struct {
	w     io.Writer
	wrote bool
}

func (f *firstWriteTracker) Write(p []byte) (int, error) {
	f.wrote = true
	return f.w.Write(p)
}

// contentDisposition 生成兼容中文文件名的下载头。
func contentDisposition(name string) string {
	fallback := strings.Map(func(r rune) rune {
		if r < 32 || r > 126 || r == '"' || r == '\\' {
			return '_'
		}
		return r
	}, name)
	return "attachment; filename=\"" + fallback + "\"; filename*=UTF-8''" + url.PathEscape(name)
}

// GET /api/v1/items/{id}/files/download?path= —— 下载文件。
func (s *Server) handleFileDownload(w http.ResponseWriter, r *http.Request) {
	item := s.fileItem(w, r)
	if item == nil {
		return
	}
	p := strings.TrimSpace(r.URL.Query().Get("path"))
	if p == "" {
		fail(w, http.StatusBadRequest, "缺少 path 参数")
		return
	}
	name := path.Base(p)
	isWebdav := item.Type == "file" && strings.EqualFold(item.Protocol, "webdav")
	if isWebdav {
		rc, size, err := webdavDownload(item, p)
		if err != nil {
			fail(w, http.StatusBadGateway, err.Error())
			return
		}
		defer rc.Close()
		w.Header().Set("Content-Type", "application/octet-stream")
		w.Header().Set("Content-Disposition", contentDisposition(name))
		if size >= 0 {
			w.Header().Set("Content-Length", strconv.FormatInt(size, 10))
		}
		_, _ = io.Copy(w, rc)
		return
	}
	// SFTP 下载（ssh 或 file+sftp）
	target := item
	if item.Type != "ssh" {
		si, e := sftpItem(item)
		if e != nil {
			fail(w, http.StatusBadRequest, e.Error())
			return
		}
		target = si
	}
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Disposition", contentDisposition(name))
	track := &firstWriteTracker{w: w}
	if err := sshx.SFTPDownload(target, p, track); err != nil {
		if !track.wrote {
			w.Header().Del("Content-Disposition")
			fail(w, http.StatusBadGateway, "下载失败："+err.Error())
		}
		return
	}
}

// POST /api/v1/items/{id}/files/upload?path=&name= —— 上传文件（body 即文件内容）。
func (s *Server) handleFileUpload(w http.ResponseWriter, r *http.Request) {
	item := s.fileItem(w, r)
	if item == nil {
		return
	}
	dir := strings.TrimSpace(r.URL.Query().Get("path"))
	if dir == "" {
		dir = "/"
	}
	name := strings.TrimSpace(r.URL.Query().Get("name"))
	if name == "" || name != path.Base(name) || name == "." || name == ".." || strings.ContainsAny(name, "\\/") {
		fail(w, http.StatusBadRequest, "文件名不合法")
		return
	}
	full := path.Join(dir, name)
	var err error
	switch {
	case item.Type == "ssh":
		err = sshx.SFTPUpload(item, full, r.Body)
	case strings.EqualFold(item.Protocol, "webdav"):
		err = webdavUpload(item, dir, name, r.Body, r.ContentLength)
	default:
		si, e := sftpItem(item)
		if e != nil {
			fail(w, http.StatusBadRequest, e.Error())
			return
		}
		err = sshx.SFTPUpload(si, full, r.Body)
	}
	if err != nil {
		fail(w, http.StatusBadGateway, "上传失败："+err.Error())
		return
	}
	okMsg(w, "uploaded", map[string]any{"path": full})
}

// POST /api/v1/items/{id}/files/delete —— 删除文件 / 目录。
func (s *Server) handleFileDelete(w http.ResponseWriter, r *http.Request) {
	item := s.fileItem(w, r)
	if item == nil {
		return
	}
	var req model.FileDeleteRequest
	if err := readJSON(r, &req); err != nil {
		fail(w, http.StatusBadRequest, "invalid json: "+err.Error())
		return
	}
	p := strings.TrimSpace(req.Path)
	if p == "" || p == "/" {
		fail(w, http.StatusBadRequest, "路径不合法")
		return
	}
	var err error
	switch {
	case item.Type == "ssh":
		err = sshx.SFTPDelete(item, p, req.IsDir)
	case strings.EqualFold(item.Protocol, "webdav"):
		err = webdavDelete(item, p)
	default:
		si, e := sftpItem(item)
		if e != nil {
			fail(w, http.StatusBadRequest, e.Error())
			return
		}
		err = sshx.SFTPDelete(si, p, req.IsDir)
	}
	if err != nil {
		fail(w, http.StatusBadGateway, "删除失败："+err.Error())
		return
	}
	okMsg(w, "deleted", nil)
}
