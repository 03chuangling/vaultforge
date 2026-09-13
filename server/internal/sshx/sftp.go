package sshx

import (
	"io"
	"path"
	"sort"

	"github.com/pkg/sftp"

	"github.com/03chuangling/vaultforge/server/internal/model"
)

// sftp 打开一个 SFTP 子通道（失败时重建连接重试一次）。
func (c *Client) sftp() (*sftp.Client, error) {
	if err := c.ensure(); err != nil {
		return nil, err
	}
	c.mu.Lock()
	cl := c.inner
	c.mu.Unlock()
	return sftp.NewClient(cl)
}

func openSFTP(item *model.VaultItem) (*sftp.Client, error) {
	c, err := Get(item)
	if err != nil {
		return nil, err
	}
	sc, err := c.sftp()
	if err != nil {
		c.reset()
		if c2, err2 := Get(item); err2 == nil {
			return c2.sftp()
		}
		return nil, err
	}
	return sc, nil
}

// SFTPList 列出远程目录内容（目录在前，名称排序）。
func SFTPList(item *model.VaultItem, dir string) ([]model.FileEntry, error) {
	sc, err := openSFTP(item)
	if err != nil {
		return nil, err
	}
	defer sc.Close()

	base := dir
	if base == "" {
		base = "."
	}
	entries, err := sc.ReadDir(base)
	if err != nil {
		return nil, err
	}
	out := make([]model.FileEntry, 0, len(entries))
	for _, e := range entries {
		name := e.Name()
		if name == "." || name == ".." {
			continue
		}
		p := name
		if base != "." {
			p = path.Join(base, name)
		}
		out = append(out, model.FileEntry{
			Name:       name,
			Path:       p,
			IsDir:      e.IsDir(),
			Size:       e.Size(),
			ModifiedAt: e.ModTime().UnixMilli(),
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

// SFTPDownload 把远程文件写入 w。
func SFTPDownload(item *model.VaultItem, remotePath string, w io.Writer) error {
	sc, err := openSFTP(item)
	if err != nil {
		return err
	}
	defer sc.Close()

	f, err := sc.Open(remotePath)
	if err != nil {
		return err
	}
	defer f.Close()
	_, err = io.Copy(w, f)
	return err
}

// SFTPUpload 从 r 读取并写入远程路径。
func SFTPUpload(item *model.VaultItem, remotePath string, r io.Reader) error {
	sc, err := openSFTP(item)
	if err != nil {
		return err
	}
	defer sc.Close()

	f, err := sc.Create(remotePath)
	if err != nil {
		return err
	}
	defer f.Close()
	_, err = io.Copy(f, r)
	return err
}

// SFTPDelete 删除远程文件或目录。
func SFTPDelete(item *model.VaultItem, remotePath string, isDir bool) error {
	sc, err := openSFTP(item)
	if err != nil {
		return err
	}
	defer sc.Close()
	if isDir {
		return sc.RemoveDirectory(remotePath)
	}
	return sc.Remove(remotePath)
}
