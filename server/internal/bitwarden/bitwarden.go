// Package bitwarden 提供 Bitwarden / Vaultwarden API 的最小客户端：
// 登录（PBKDF2 / Argon2id 两套 KDF）、解密账户密钥、拉取并解密密码库条目。
//
// 协议要点（已对 Vaultwarden 实测通过）：
//
// POST /identity/accounts/prelogin   获取账号 KDF 参数
// POST /identity/connect/token       登录，返回 access_token 与加密的账户密钥（Key）
// GET  /api/sync                     拉取全量密码库（字段为加密串）
//
// 加密格式（EncString）：
//
// "2.<iv_b64>|<ct_b64>|<mac_b64>"   AesCbc256_HmacSha256（PKCS7）
// "0.<iv_b64>|<ct_b64>"             AesCbc256 旧格式（无 MAC）
//
// 密钥派生：
//
// masterKey          = PBKDF2-SHA256(password, email, iters) 或 Argon2id(password, SHA256(email), ...)
// masterPasswordHash = PBKDF2-SHA256(masterKey, password, 1)（仅用于登录校验，明文主密码不外发）
// stretched          = HKDF-Expand(masterKey, "enc", 32) || HKDF-Expand(masterKey, "mac", 32)
// userKey            = 64 字节，服务器上以 stretched 加密保存（登录响应的 Key 字段）
// 条目字段             = AES-256-CBC + HMAC-SHA256，密钥为 userKey 的两半
package bitwarden

import (
	"bytes"
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"sort"
	"strings"
	"time"

	"golang.org/x/crypto/argon2"
	"golang.org/x/crypto/pbkdf2"
)

const (
	deviceID   = "b7f2d64a-1e0b-4c8f-9a3d-5f6e7c8d9a0b"
	deviceName = "VaultForge-Bitwarden-Bridge"
)

// Profile 账户信息。
type Profile struct {
	Email string `json:"email"`
	Name  string `json:"name,omitempty"`
}

// Folder 文件夹。
type Folder struct {
	ID   string `json:"id"`
	Name string `json:"name"`
}

// Entry 解密后的密码条目（已过滤软删除）。
type Entry struct {
	ID         string   `json:"id"`
	Type       int      `json:"type"`
	TypeName   string   `json:"typeName"`
	Name       string   `json:"name"`
	Username   string   `json:"username,omitempty"`
	Password   string   `json:"password,omitempty"`
	TOTP       string   `json:"totp,omitempty"`
	URIs       []string `json:"uris,omitempty"`
	Notes      string   `json:"notes,omitempty"`
	FolderID   string   `json:"folderId,omitempty"`
	FolderName string   `json:"folderName,omitempty"`
	Favorite   bool     `json:"favorite,omitempty"`
}

// Vault 拉取结果。
type Vault struct {
	Profile Profile  `json:"profile"`
	Folders []Folder `json:"folders"`
	Entries []Entry  `json:"entries"`
	Count   int      `json:"count"`
}

// Session 已登录会话（仅内存态；不落盘、不记日志）。
type Session struct {
	baseURL string
	email   string
	token   string
	userKey []byte // 64 字节：前 32 加密 / 后 32 MAC
	hc      *http.Client
}

func (s *Session) encKey() []byte { return s.userKey[:32] }
func (s *Session) macKey() []byte { return s.userKey[32:] }

// ---------- 编码 / 派生 / 加解密 ----------

func b64d(s string) ([]byte, error) { return base64.StdEncoding.DecodeString(s) }

func hkdfExpand(prk []byte, info string, n int) []byte {
	out := []byte{}
	var t []byte
	for c := byte(1); len(out) < n; c++ {
		m := hmac.New(sha256.New, prk)
		m.Write(t)
		m.Write([]byte(info))
		m.Write([]byte{c})
		t = m.Sum(nil)
		out = append(out, t...)
	}
	return out[:n]
}

func aesCBCEnc(key, iv, pt []byte) []byte {
	block, _ := aes.NewCipher(key)
	pad := aes.BlockSize - len(pt)%aes.BlockSize
	p := append(append([]byte{}, pt...), bytes.Repeat([]byte{byte(pad)}, pad)...)
	ct := make([]byte, len(p))
	cipher.NewCBCEncrypter(block, iv).CryptBlocks(ct, p)
	return ct
}

func aesCBCDec(key, iv, ct []byte) ([]byte, error) {
	if len(ct) == 0 || len(ct)%aes.BlockSize != 0 {
		return nil, errors.New("密文长度异常")
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	pt := make([]byte, len(ct))
	cipher.NewCBCDecrypter(block, iv).CryptBlocks(pt, ct)
	pad := int(pt[len(pt)-1])
	if pad < 1 || pad > aes.BlockSize || pad > len(pt) {
		return nil, errors.New("填充异常")
	}
	return pt[:len(pt)-pad], nil
}

// decSeg 解密单个 EncString 字段。
func decSeg(encKey, macKey []byte, s string) (string, error) {
	if s == "" {
		return "", nil
	}
	i := strings.Index(s, ".")
	if i < 0 {
		return "", errors.New("格式异常")
	}
	typ, rest := s[:i], s[i+1:]
	segs := strings.Split(rest, "|")
	switch typ {
	case "2":
		if len(segs) != 3 {
			return "", errors.New("字段段数异常")
		}
		iv, err1 := b64d(segs[0])
		ct, err2 := b64d(segs[1])
		mac, err3 := b64d(segs[2])
		if err1 != nil || err2 != nil || err3 != nil {
			return "", errors.New("base64 异常")
		}
		m := hmac.New(sha256.New, macKey)
		m.Write(iv)
		m.Write(ct)
		if !hmac.Equal(m.Sum(nil), mac) {
			return "", errors.New("MAC 校验失败")
		}
		pt, err := aesCBCDec(encKey, iv, ct)
		return string(pt), err
	case "0":
		if len(segs) != 2 {
			return "", errors.New("字段段数异常")
		}
		iv, err1 := b64d(segs[0])
		ct, err2 := b64d(segs[1])
		if err1 != nil || err2 != nil {
			return "", errors.New("base64 异常")
		}
		pt, err := aesCBCDec(encKey, iv, ct)
		return string(pt), err
	default:
		return "", fmt.Errorf("暂不支持的加密类型：%s", typ)
	}
}

// deriveMasterKey 按 prelogin 返回的 KDF 参数派生 master key。
func deriveMasterKey(password, email string, kdf, iters, memory, parallelism int) ([]byte, error) {
	switch kdf {
	case 0:
		if iters <= 0 {
			iters = 600000
		}
		return pbkdf2.Key([]byte(password), []byte(email), iters, 32, sha256.New), nil
	case 1:
		// Argon2id：salt 为 email 的 SHA-256；内存参数单位为 MB。
		if iters <= 0 {
			iters = 3
		}
		if memory <= 0 {
			memory = 64
		}
		if parallelism <= 0 {
			parallelism = 4
		}
		salt := sha256.Sum256([]byte(email))
		return argon2.IDKey([]byte(password), salt[:], uint32(iters), uint32(memory)*1024, uint8(parallelism), 32), nil
	default:
		return nil, fmt.Errorf("暂不支持的 KDF 类型：%d", kdf)
	}
}

// normalizeBase 规范化服务器地址（补协议、去尾斜杠与常见后缀路径）。
func normalizeBase(raw string) (string, error) {
	s := strings.TrimSpace(raw)
	if s == "" {
		return "", errors.New("服务器地址为空")
	}
	if !strings.HasPrefix(s, "http://") && !strings.HasPrefix(s, "https://") {
		s = "https://" + s
	}
	u, err := url.Parse(s)
	if err != nil || u.Host == "" {
		return "", fmt.Errorf("服务器地址无效：%s", raw)
	}
	p := strings.TrimSuffix(u.Path, "/")
	p = strings.TrimSuffix(p, "/api")
	p = strings.TrimSuffix(p, "/identity")
	u.Path = p
	u.RawQuery = ""
	u.Fragment = ""
	return strings.TrimSuffix(u.String(), "/"), nil
}

func derefInt(p *int) int {
	if p == nil {
		return 0
	}
	return *p
}

// ---------- HTTP ----------

func (s *Session) do(ctx context.Context, method, path, bearer, ctype string, body []byte) (int, []byte, error) {
	req, err := http.NewRequestWithContext(ctx, method, s.baseURL+path, bytes.NewReader(body))
	if err != nil {
		return 0, nil, err
	}
	if ctype != "" {
		req.Header.Set("Content-Type", ctype)
	}
	if bearer != "" {
		req.Header.Set("Authorization", "Bearer "+bearer)
	}
	req.Header.Set("User-Agent", "VaultForge-Server (Bitwarden bridge)")
	resp, err := s.hc.Do(req)
	if err != nil {
		return 0, nil, err
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(io.LimitReader(resp.Body, 32<<20))
	return resp.StatusCode, b, nil
}

type preloginResp struct {
	Kdf            int  `json:"kdf"`
	KdfIterations  int  `json:"kdfIterations"`
	KdfMemory      *int `json:"kdfMemory"`
	KdfParallelism *int `json:"kdfParallelism"`
}

// Connect 登录并建立会话（内部完成 KDF 派生与账户密钥解密）。
func Connect(ctx context.Context, serverURL, email, password string) (*Session, error) {
	base, err := normalizeBase(serverURL)
	if err != nil {
		return nil, err
	}
	if strings.TrimSpace(email) == "" || password == "" {
		return nil, errors.New("请填写邮箱与主密码")
	}
	s := &Session{
		baseURL: base,
		email:   strings.ToLower(strings.TrimSpace(email)),
		hc:      &http.Client{Timeout: 45 * time.Second},
	}

	// 1. 取 KDF 参数
	pl, err := s.prelogin(ctx)
	if err != nil {
		return nil, err
	}

	// 2. 派生密钥
	mk, err := deriveMasterKey(password, s.email, pl.Kdf, pl.KdfIterations, derefInt(pl.KdfMemory), derefInt(pl.KdfParallelism))
	if err != nil {
		return nil, err
	}
	mpHash := pbkdf2.Key(mk, []byte(password), 1, 32, sha256.New)
	stEnc := hkdfExpand(mk, "enc", 32)
	stMac := hkdfExpand(mk, "mac", 32)

	// 3. 登录
	token, keyStr, err := s.login(ctx, base64.StdEncoding.EncodeToString(mpHash))
	if err != nil {
		return nil, err
	}

	// 4. 解密账户密钥
	uk, err := decSeg(stEnc, stMac, keyStr)
	if err != nil || len(uk) != 64 {
		return nil, errors.New("无法解密账户密钥（主密码可能不正确）")
	}
	s.token = token
	s.userKey = []byte(uk)
	return s, nil
}

func (s *Session) prelogin(ctx context.Context) (*preloginResp, error) {
	body, _ := json.Marshal(map[string]string{"email": s.email})
	code, resp, err := s.do(ctx, "POST", "/identity/accounts/prelogin", "", "application/json", body)
	if err != nil {
		return nil, fmt.Errorf("无法连接到 Bitwarden 服务器：%v", err)
	}
	if code != http.StatusOK {
		return nil, fmt.Errorf("prelogin 失败（HTTP %d）", code)
	}
	var pr preloginResp
	if err := json.Unmarshal(resp, &pr); err != nil {
		return nil, errors.New("服务器响应格式异常")
	}
	return &pr, nil
}

func (s *Session) login(ctx context.Context, mpHashB64 string) (string, string, error) {
	form := url.Values{}
	form.Set("grant_type", "password")
	form.Set("username", s.email)
	form.Set("password", mpHashB64)
	form.Set("scope", "api offline_access")
	form.Set("client_id", "cli")
	form.Set("deviceType", "0")
	form.Set("deviceIdentifier", deviceID)
	form.Set("deviceName", deviceName)
	code, resp, err := s.do(ctx, "POST", "/identity/connect/token", "", "application/x-www-form-urlencoded", []byte(form.Encode()))
	if err != nil {
		return "", "", fmt.Errorf("无法连接到 Bitwarden 服务器：%v", err)
	}
	if code == http.StatusBadRequest || code == http.StatusUnauthorized {
		return "", "", errors.New("邮箱或主密码不正确")
	}
	if code != http.StatusOK {
		return "", "", fmt.Errorf("登录失败（HTTP %d）", code)
	}
	var tr struct {
		AccessToken       string `json:"access_token"`
		Key               string `json:"Key"`
		TwoFactorRequired bool   `json:"TwoFactorRequired"`
	}
	if err := json.Unmarshal(resp, &tr); err != nil {
		return "", "", errors.New("服务器响应格式异常")
	}
	if tr.TwoFactorRequired {
		return "", "", errors.New("该账号启用了两步验证（2FA），暂不支持自动登录")
	}
	if tr.AccessToken == "" || tr.Key == "" {
		return "", "", errors.New("服务器未返回会话密钥")
	}
	return tr.AccessToken, tr.Key, nil
}

// ---------- 拉取 ----------

type syncJSON struct {
	Profile struct {
		Email string `json:"email"`
		Name  string `json:"name"`
	} `json:"profile"`
	Folders []struct {
		ID   string `json:"id"`
		Name string `json:"name"`
	} `json:"folders"`
	Ciphers []struct {
		ID          string  `json:"id"`
		Type        int     `json:"type"`
		Name        string  `json:"name"`
		Notes       string  `json:"notes"`
		FolderID    string  `json:"folderId"`
		Favorite    bool    `json:"favorite"`
		DeletedDate *string `json:"deletedDate"`
		Login       *struct {
			Username string `json:"username"`
			Password string `json:"password"`
			TOTP     string `json:"totp"`
			URIs     []struct {
				URI string `json:"uri"`
			} `json:"uris"`
		} `json:"login"`
	} `json:"ciphers"`
}

func typeName(t int) string {
	switch t {
	case 1:
		return "登录"
	case 2:
		return "安全笔记"
	case 3:
		return "银行卡"
	case 4:
		return "身份"
	case 5:
		return "SSH 密钥"
	default:
		return "其他"
	}
}

// Fetch 拉取全量密码库并解密（软删除条目会被过滤）。
func (s *Session) Fetch(ctx context.Context) (*Vault, error) {
	code, resp, err := s.do(ctx, "GET", "/api/sync", s.token, "", nil)
	if err != nil {
		return nil, fmt.Errorf("连接中断：%v", err)
	}
	if code == http.StatusUnauthorized {
		return nil, errors.New("会话已失效，请重试")
	}
	if code != http.StatusOK {
		return nil, fmt.Errorf("拉取失败（HTTP %d）", code)
	}
	var raw syncJSON
	if err := json.Unmarshal(resp, &raw); err != nil {
		return nil, errors.New("服务器响应格式异常")
	}

	v := &Vault{Profile: Profile{Email: raw.Profile.Email, Name: raw.Profile.Name}}
	folderNames := map[string]string{}
	for _, f := range raw.Folders {
		nm, _ := decSeg(s.encKey(), s.macKey(), f.Name)
		folderNames[f.ID] = nm
		v.Folders = append(v.Folders, Folder{ID: f.ID, Name: nm})
	}

	for _, c := range raw.Ciphers {
		if c.DeletedDate != nil {
			continue
		}
		e := Entry{
			ID:         c.ID,
			Type:       c.Type,
			TypeName:   typeName(c.Type),
			FolderID:   c.FolderID,
			FolderName: folderNames[c.FolderID],
			Favorite:   c.Favorite,
		}
		e.Name, _ = decSeg(s.encKey(), s.macKey(), c.Name)
		e.Notes, _ = decSeg(s.encKey(), s.macKey(), c.Notes)
		if c.Login != nil {
			e.Username, _ = decSeg(s.encKey(), s.macKey(), c.Login.Username)
			e.Password, _ = decSeg(s.encKey(), s.macKey(), c.Login.Password)
			e.TOTP, _ = decSeg(s.encKey(), s.macKey(), c.Login.TOTP)
			for _, u := range c.Login.URIs {
				if uri, err := decSeg(s.encKey(), s.macKey(), u.URI); err == nil && uri != "" {
					e.URIs = append(e.URIs, uri)
				}
			}
		}
		v.Entries = append(v.Entries, e)
	}

	sort.Slice(v.Entries, func(i, j int) bool {
		return strings.ToLower(v.Entries[i].Name) < strings.ToLower(v.Entries[j].Name)
	})
	v.Count = len(v.Entries)
	return v, nil
}
