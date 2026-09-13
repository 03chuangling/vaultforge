// Package sshx 提供基于 golang.org/x/crypto/ssh 的 SSH 操作能力：
// 连接管理（密码 / 密钥认证 + 会话复用）、命令执行、服务器指标、Docker 容器、SFTP。
package sshx

import (
	"errors"
	"fmt"
	"net"
	"strconv"
	"strings"
	"sync"
	"time"

	"golang.org/x/crypto/ssh"

	"github.com/03chuangling/vaultforge/server/internal/model"
)

// dialTimeout 建连超时。
const dialTimeout = 12 * time.Second

// Client 对单个条目的 SSH 连接封装（复用底层连接，连接失效自动重建）。
type Client struct {
	item  *model.VaultItem
	mu    sync.Mutex
	inner *ssh.Client
}

var (
	cacheMu sync.Mutex
	cache   = map[string]*Client{}
)

func cacheKey(item *model.VaultItem) string {
	if item.ID != "" {
		return item.ID
	}
	return item.Host + "|" + strconv.Itoa(item.Port) + "|" + item.Username
}

// Get 获取（或建立）某条目的 SSH 客户端。
func Get(item *model.VaultItem) (*Client, error) {
	key := cacheKey(item)
	cacheMu.Lock()
	c := cache[key]
	if c == nil {
		c = &Client{item: item}
		cache[key] = c
	}
	cacheMu.Unlock()
	if err := c.ensure(); err != nil {
		return nil, err
	}
	return c, nil
}

// Close 关闭并移除某条目的连接缓存（条目配置变更 / 删除时调用）。
func Close(itemID string) {
	cacheMu.Lock()
	c := cache[itemID]
	delete(cache, itemID)
	cacheMu.Unlock()
	if c != nil {
		c.reset()
	}
}

func (c *Client) ensure() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.inner != nil {
		return nil
	}
	cl, err := dial(c.item)
	if err != nil {
		return err
	}
	c.inner = cl
	return nil
}

// reset 关闭当前连接（下次 ensure 时重建）。
func (c *Client) reset() {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.inner != nil {
		c.inner.Close()
		c.inner = nil
	}
}

// run 执行命令；连接失效时重建并重试一次。返回合并输出（stdout+stderr）。
func (c *Client) run(command string, timeout time.Duration) (string, error) {
	out, err := c.tryRun(command, timeout)
	if err == nil {
		return out, nil
	}
	c.reset()
	return c.tryRun(command, timeout)
}

func (c *Client) tryRun(command string, timeout time.Duration) (string, error) {
	if err := c.ensure(); err != nil {
		return "", err
	}
	c.mu.Lock()
	cl := c.inner
	c.mu.Unlock()

	sess, err := cl.NewSession()
	if err != nil {
		return "", errors.New(friendlyErr(err))
	}
	defer sess.Close()

	var buf strings.Builder
	sess.Stdout = &buf
	sess.Stderr = &buf

	done := make(chan error, 1)
	go func() { done <- sess.Run(command) }()

	select {
	case err := <-done:
		if err != nil {
			// 远端命令非零退出：把输出照常带回
			if _, isExit := err.(*ssh.ExitError); !isExit {
				return buf.String(), errors.New(friendlyErr(err))
			}
		}
		return buf.String(), nil
	case <-time.After(timeout):
		_ = sess.Close()
		return buf.String(), fmt.Errorf("命令执行超时（%s）", timeout)
	}
}

// dial 建立底层 SSH 连接（支持密码 / 私钥认证）。
func dial(item *model.VaultItem) (*ssh.Client, error) {
	if strings.TrimSpace(item.Host) == "" {
		return nil, errors.New("未填写主机")
	}
	port := item.Port
	if port <= 0 {
		port = 22
	}
	addr := net.JoinHostPort(item.Host, strconv.Itoa(port))

	var auths []ssh.AuthMethod
	useKey := item.AuthMethod == "key" && strings.TrimSpace(item.PrivateKey) != ""
	if useKey {
		key := []byte(item.PrivateKey)
		var (
			signer ssh.Signer
			err    error
		)
		if item.Secret != "" {
			signer, err = ssh.ParsePrivateKeyWithPassphrase(key, []byte(item.Secret))
		} else {
			signer, err = ssh.ParsePrivateKey(key)
		}
		if err != nil {
			return nil, fmt.Errorf("私钥解析失败：%v", err)
		}
		auths = append(auths, ssh.PublicKeys(signer))
	} else {
		auths = append(auths,
			ssh.Password(item.Secret),
			ssh.KeyboardInteractive(func(_, _ string, questions []string, _ []bool) ([]string, error) {
				answers := make([]string, len(questions))
				for i := range answers {
					answers[i] = item.Secret
				}
				return answers, nil
			}),
		)
	}

	username := item.Username
	if username == "" {
		username = "root"
	}
	cfg := &ssh.ClientConfig{
		User:            username,
		Auth:            auths,
		HostKeyCallback: ssh.InsecureIgnoreHostKey(),
		Timeout:         dialTimeout,
	}
	cl, err := ssh.Dial("tcp", addr, cfg)
	if err != nil {
		return nil, errors.New(friendlyErr(err))
	}
	return cl, nil
}

// friendlyErr 把底层错误翻译成用户可读文案（与 App 端一致）。
func friendlyErr(err error) string {
	if err == nil {
		return ""
	}
	msg := err.Error()
	lower := strings.ToLower(msg)
	switch {
	case strings.Contains(lower, "unable to authenticate") || strings.Contains(lower, "auth fail"):
		return "认证失败（用户名/密码/密钥错误）"
	case strings.Contains(lower, "connection refused"):
		return "连接被拒绝（端口不通或服务未开）"
	case strings.Contains(lower, "no such host") || strings.Contains(lower, "lookup "):
		return "域名解析失败"
	case strings.Contains(lower, "i/o timeout") || strings.Contains(lower, "timed out"):
		return "连接超时"
	case strings.Contains(lower, "connection reset"):
		return "连接被重置"
	}
	return msg
}

// netDialer 构造带超时的 TCP dialer。
func netDialer(timeout time.Duration) *net.Dialer {
	return &net.Dialer{Timeout: timeout}
}
