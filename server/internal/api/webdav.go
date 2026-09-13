package api

import (
	"crypto/tls"
	"encoding/xml"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"path"
	"sort"
	"strings"
	"time"

	"github.com/03chuangling/vaultforge/server/internal/model"
)

// ---- WebDAV 轻量客户端（文件列表 / 下载 / 上传 / 删除） ----

const propfindBody = `<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:">
  <d:prop>
    <d:displayname/>
    <d:getcontentlength/>
    <d:getlastmodified/>
    <d:resourcetype/>
  </d:prop>
</d:propfind>`

// webdavConn 单个条目的 WebDAV 连接参数。
type webdavConn struct {
	base string
	user string
	pass string
	http *http.Client
}

func newWebdav(item *model.VaultItem) (*webdavConn, error) {
	addr := strings.TrimSpace(item.Address)
	if addr == "" {
		return nil, errors.New("未填写地址")
	}
	if !strings.Contains(addr, "://") {
		addr = "http://" + addr
	}
	u, err := url.Parse(addr)
	if err != nil || u.Host == "" {
		return nil, errors.New("地址格式不正确")
	}
	return &webdavConn{
		base: strings.TrimRight(u.String(), "/"),
		user: item.Username,
		pass: item.Secret,
		http: &http.Client{
			Timeout: 60 * time.Second,
			Transport: &http.Transport{
				// 允许自签名证书（用户自己管理的服务器场景）
				TLSClientConfig: &tls.Config{InsecureSkipVerify: true},
			},
		},
	}, nil
}

// urlFor 把逻辑路径（以 / 开头）编码为完整 URL。
func (d *webdavConn) urlFor(p string) string {
	p = strings.Trim(p, "/")
	if p == "" {
		return d.base + "/"
	}
	segs := strings.Split(p, "/")
	esc := make([]string, 0, len(segs))
	for _, s := range segs {
		esc = append(esc, url.PathEscape(s))
	}
	return d.base + "/" + strings.Join(esc, "/")
}

// do 发起带 Basic 认证的请求；size >= 0 时显式设置 Content-Length。
func (d *webdavConn) do(method, p string, body io.Reader, size int64, hdr map[string]string) (*http.Response, error) {
	req, err := http.NewRequest(method, d.urlFor(p), body)
	if err != nil {
		return nil, err
	}
	if d.user != "" || d.pass != "" {
		req.SetBasicAuth(d.user, d.pass)
	}
	req.Header.Set("User-Agent", "VaultForge-Server/0.3")
	if size >= 0 {
		req.ContentLength = size
	}
	for k, v := range hdr {
		req.Header.Set(k, v)
	}
	return d.http.Do(req)
}

// ---- PROPFIND 响应解析 ----

type davMultistatus struct {
	Responses []davResponse `xml:"DAV: response"`
}

type davResponse struct {
	Href     string        `xml:"DAV: href"`
	Propstat []davPropstat `xml:"DAV: propstat"`
}

type davPropstat struct {
	Prop   davProp `xml:"DAV: prop"`
	Status string  `xml:"DAV: status"`
}

type davProp struct {
	DisplayName   string `xml:"DAV: displayname"`
	ContentLength int64  `xml:"DAV: getcontentlength"`
	LastModified  string `xml:"DAV: getlastmodified"`
	ResourceType  struct {
		Raw string `xml:",innerxml"`
	} `xml:"DAV: resourcetype"`
}

// mergeProps 合并各 propstat 中的属性（忽略非 200 的片段）。
func (r davResponse) mergeProps() davProp {
	var out davProp
	for _, ps := range r.Propstat {
		if ps.Status != "" && !strings.Contains(ps.Status, " 200 ") {
			continue
		}
		if ps.Prop.DisplayName != "" {
			out.DisplayName = ps.Prop.DisplayName
		}
		if ps.Prop.ContentLength != 0 {
			out.ContentLength = ps.Prop.ContentLength
		}
		if ps.Prop.LastModified != "" {
			out.LastModified = ps.Prop.LastModified
		}
		if ps.Prop.ResourceType.Raw != "" {
			out.ResourceType = ps.Prop.ResourceType
		}
	}
	return out
}

func parseHTTPTime(s string) int64 {
	if strings.TrimSpace(s) == "" {
		return 0
	}
	if t, err := http.ParseTime(s); err == nil {
		return t.UnixMilli()
	}
	return 0
}

func davStatusErr(resp *http.Response, action string) error {
	switch resp.StatusCode {
	case http.StatusUnauthorized:
		return fmt.Errorf("%s失败：认证失败（HTTP 401）", action)
	case http.StatusForbidden:
		return fmt.Errorf("%s失败：无权限（HTTP 403）", action)
	case http.StatusNotFound:
		return fmt.Errorf("%s失败：路径不存在（HTTP 404）", action)
	case http.StatusConflict:
		return fmt.Errorf("%s失败：目标目录不存在或冲突（HTTP 409）", action)
	default:
		return fmt.Errorf("%s失败（HTTP %d）", action, resp.StatusCode)
	}
}

// ---- 操作 ----

// webdavList 列出目录内容（目录在前，名称排序）。
func webdavList(item *model.VaultItem, dir string) ([]model.FileEntry, error) {
	d, err := newWebdav(item)
	if err != nil {
		return nil, err
	}
	resp, err := d.do("PROPFIND", dir, strings.NewReader(propfindBody), int64(len(propfindBody)), map[string]string{
		"Depth":        "1",
		"Content-Type": "application/xml; charset=utf-8",
	})
	if err != nil {
		return nil, errors.New("连接失败：" + err.Error())
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusMultiStatus && resp.StatusCode != http.StatusOK {
		return nil, davStatusErr(resp, "读取目录")
	}
	var ms davMultistatus
	if err := xml.NewDecoder(resp.Body).Decode(&ms); err != nil {
		return nil, errors.New("目录解析失败：" + err.Error())
	}

	basePath := ""
	if u, err := url.Parse(d.base); err == nil {
		basePath = strings.TrimRight(u.Path, "/")
	}
	out := make([]model.FileEntry, 0, len(ms.Responses))
	for _, rsp := range ms.Responses {
		hrefPath := rsp.Href
		if u, err := url.Parse(rsp.Href); err == nil && u.Path != "" {
			hrefPath = u.Path
		}
		if unesc, err := url.PathUnescape(hrefPath); err == nil {
			hrefPath = unesc
		}
		rel := hrefPath
		if basePath != "" && strings.HasPrefix(rel, basePath) {
			rel = strings.TrimPrefix(rel, basePath)
		}
		rel = strings.TrimSuffix(rel, "/")
		if rel == "" || rel == "/" {
			continue // 查询的目录自身
		}
		if !strings.HasPrefix(rel, "/") {
			rel = "/" + rel
		}
		props := rsp.mergeProps()
		out = append(out, model.FileEntry{
			Name:       path.Base(rel),
			Path:       rel,
			IsDir:      strings.Contains(props.ResourceType.Raw, "collection"),
			Size:       props.ContentLength,
			ModifiedAt: parseHTTPTime(props.LastModified),
		})
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].IsDir != out[j].IsDir {
			return out[i].IsDir
		}
		return out[i].Name < out[j].Name
	})
	return out, nil
}

// webdavDownload 打开远程文件，返回内容流与长度（-1 表示未知）。
func webdavDownload(item *model.VaultItem, p string) (io.ReadCloser, int64, error) {
	d, err := newWebdav(item)
	if err != nil {
		return nil, 0, err
	}
	resp, err := d.do("GET", p, nil, -1, nil)
	if err != nil {
		return nil, 0, errors.New("连接失败：" + err.Error())
	}
	if resp.StatusCode == http.StatusNotFound {
		resp.Body.Close()
		return nil, 0, errors.New("文件不存在")
	}
	if resp.StatusCode != http.StatusOK {
		defer resp.Body.Close()
		return nil, 0, davStatusErr(resp, "下载")
	}
	return resp.Body, resp.ContentLength, nil
}

// webdavUpload 上传文件到目录（PUT）。
func webdavUpload(item *model.VaultItem, dir, name string, r io.Reader, size int64) error {
	d, err := newWebdav(item)
	if err != nil {
		return err
	}
	full := path.Join(dir, name)
	resp, err := d.do("PUT", full, r, size, map[string]string{"Content-Type": "application/octet-stream"})
	if err != nil {
		return errors.New("连接失败：" + err.Error())
	}
	defer resp.Body.Close()
	switch resp.StatusCode {
	case http.StatusOK, http.StatusCreated, http.StatusNoContent:
		return nil
	}
	return davStatusErr(resp, "上传")
}

// webdavDelete 删除文件 / 目录（404 视为已不存在）。
func webdavDelete(item *model.VaultItem, p string) error {
	d, err := newWebdav(item)
	if err != nil {
		return err
	}
	resp, err := d.do("DELETE", p, nil, -1, nil)
	if err != nil {
		return errors.New("连接失败：" + err.Error())
	}
	defer resp.Body.Close()
	switch resp.StatusCode {
	case http.StatusOK, http.StatusNoContent, http.StatusNotFound:
		return nil
	}
	return davStatusErr(resp, "删除")
}
