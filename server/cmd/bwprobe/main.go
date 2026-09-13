package main

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"

	"golang.org/x/crypto/pbkdf2"
)

const (
	serverBase = "https://bit.bdshjgg.com"
	emailAddr  = "vfprobe-delete-me@example.com"
	masterPwd  = "VfProbe-2026-x9Qb-7mK"
	kdfIter    = 600000
)

var client = &http.Client{Timeout: 30 * time.Second}

func b64e(b []byte) string { return base64.StdEncoding.EncodeToString(b) }
func b64d(s string) []byte {
	b, err := base64.StdEncoding.DecodeString(s)
	if err != nil {
		panic("b64: " + err.Error())
	}
	return b
}
func trunc(b []byte, n int) string {
	s := string(b)
	if len(s) > n {
		return s[:n] + "..."
	}
	return s
}
func fail(f string, a ...any) {
	fmt.Printf("FATAL: "+f+"\n", a...)
	os.Exit(1)
}

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
		return nil, fmt.Errorf("bad ct len")
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	pt := make([]byte, len(ct))
	cipher.NewCBCDecrypter(block, iv).CryptBlocks(pt, ct)
	pad := int(pt[len(pt)-1])
	if pad < 1 || pad > aes.BlockSize || pad > len(pt) {
		return nil, fmt.Errorf("bad padding")
	}
	return pt[:len(pt)-pad], nil
}

func encWith(encKey, macKey []byte, plain string) string {
	iv := make([]byte, 16)
	rand.Read(iv)
	ct := aesCBCEnc(encKey, iv, []byte(plain))
	m := hmac.New(sha256.New, macKey)
	m.Write(iv)
	m.Write(ct)
	return "2." + b64e(iv) + "|" + b64e(ct) + "|" + b64e(m.Sum(nil))
}

func decWith(encKey, macKey []byte, s string) (string, error) {
	i := strings.Index(s, ".")
	if i < 0 {
		return "", fmt.Errorf("no dot")
	}
	typ, rest := s[:i], s[i+1:]
	segs := strings.Split(rest, "|")
	switch typ {
	case "2":
		if len(segs) != 3 {
			return "", fmt.Errorf("segs")
		}
		iv, ct, mac := b64d(segs[0]), b64d(segs[1]), b64d(segs[2])
		m := hmac.New(sha256.New, macKey)
		m.Write(iv)
		m.Write(ct)
		if !hmac.Equal(m.Sum(nil), mac) {
			return "", fmt.Errorf("mac fail")
		}
		pt, err := aesCBCDec(encKey, iv, ct)
		return string(pt), err
	case "0":
		if len(segs) != 2 {
			return "", fmt.Errorf("segs0")
		}
		pt, err := aesCBCDec(encKey, b64d(segs[0]), b64d(segs[1]))
		return string(pt), err
	default:
		return "", fmt.Errorf("enc type %s unsupported", typ)
	}
}

func req(method, path string, hdr map[string]string, body []byte) (int, []byte) {
	r, err := http.NewRequest(method, serverBase+path, bytes.NewReader(body))
	if err != nil {
		panic(err)
	}
	for k, v := range hdr {
		r.Header.Set(k, v)
	}
	resp, err := client.Do(r)
	if err != nil {
		panic(err)
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	return resp.StatusCode, b
}

func merge(a, b map[string]string) map[string]string {
	m := map[string]string{}
	for k, v := range a {
		m[k] = v
	}
	for k, v := range b {
		m[k] = v
	}
	return m
}

func main() {
	fmt.Println("[1] derive keys (PBKDF2 600k)")
	masterKey := pbkdf2.Key([]byte(masterPwd), []byte(emailAddr), kdfIter, 32, sha256.New)
	mpHash := pbkdf2.Key(masterKey, []byte(masterPwd), 1, 32, sha256.New)
	stEnc := hkdfExpand(masterKey, "enc", 32)
	stMac := hkdfExpand(masterKey, "mac", 32)

	userKey := make([]byte, 64)
	if _, err := rand.Read(userKey); err != nil {
		panic(err)
	}
	protectedKey := encWith(stEnc, stMac, string(userKey))
	fmt.Println("    ok; protectedKey:", trunc([]byte(protectedKey), 20))

	fmt.Println("[2] register")
	regBody, _ := json.Marshal(map[string]any{
		"email":              emailAddr,
		"name":               "VF Probe",
		"masterPasswordHash": b64e(mpHash),
		"masterPasswordHint": nil,
		"key":                protectedKey,
		"kdf":                0,
		"kdfIterations":      kdfIter,
		"referenceData":      nil,
	})
	code, resp := req("POST", "/identity/accounts/register", map[string]string{"Content-Type": "application/json"}, regBody)
	fmt.Printf("    register http=%d body=%s\n", code, trunc(resp, 220))
	regNew := code < 400
	if code >= 400 && !strings.Contains(strings.ToLower(string(resp)), "already") {
		fail("register failed")
	}

	fmt.Println("[3] login")
	form := url.Values{}
	form.Set("grant_type", "password")
	form.Set("username", emailAddr)
	form.Set("password", b64e(mpHash))
	form.Set("scope", "api offline_access")
	form.Set("client_id", "cli")
	form.Set("deviceType", "0")
	form.Set("deviceIdentifier", "9f2a1c3e-58b7-4d61-a2c9-7e4b6f8d0a11")
	form.Set("deviceName", "VaultForge-Bitwarden-Probe")
	code, resp = req("POST", "/identity/connect/token", map[string]string{"Content-Type": "application/x-www-form-urlencoded"}, []byte(form.Encode()))
	fmt.Printf("    login http=%d body=%s\n", code, trunc(resp, 240))
	if code >= 400 {
		fail("login failed")
	}
	var tok struct {
		AccessToken string `json:"access_token"`
		Key         string `json:"Key"`
	}
	json.Unmarshal(resp, &tok)
	fmt.Println("    token_len:", len(tok.AccessToken), " key_len:", len(tok.Key))

	fmt.Println("[4] decrypt returned Key")
	uk2, err := decWith(stEnc, stMac, tok.Key)
	if err != nil {
		fail("decrypt key: %v", err)
	}
	if regNew {
		if !bytes.Equal([]byte(uk2), userKey) {
			fail("user key mismatch len=%d", len(uk2))
		}
		fmt.Println("    userKey match: true (len 64)")
	} else {
		userKey = []byte(uk2)
		fmt.Printf("    existing account; adopting server userKey (len %d)\n", len(uk2))
	}

	authHdr := map[string]string{"Authorization": "Bearer " + tok.AccessToken}
	fmt.Println("[5] sync (initial)")
	code, resp = req("GET", "/api/sync", authHdr, nil)
	fmt.Printf("    sync http=%d len=%d\n", code, len(resp))
	if code >= 400 {
		fail("sync failed")
	}
	var sync1 struct {
		Ciphers []json.RawMessage `json:"ciphers"`
	}
	json.Unmarshal(resp, &sync1)
	fmt.Println("    ciphers count:", len(sync1.Ciphers))

	fmt.Println("[6] create test cipher")
	encName := encWith(userKey[:32], userKey[32:], "VF Probe Login")
	encUser := encWith(userKey[:32], userKey[32:], "probe-user")
	encPass := encWith(userKey[:32], userKey[32:], "probe-pass-123")
	encURI := encWith(userKey[:32], userKey[32:], "https://example.com")
	encNote := encWith(userKey[:32], userKey[32:], "created by vfprobe")
	cipherBody, _ := json.Marshal(map[string]any{
		"type":     1,
		"name":     encName,
		"notes":    encNote,
		"favorite": false,
		"login": map[string]any{
			"username": encUser,
			"password": encPass,
			"uris":     []map[string]any{{"uri": encURI}},
		},
	})
	code, resp = req("POST", "/api/ciphers", merge(authHdr, map[string]string{"Content-Type": "application/json"}), cipherBody)
	fmt.Printf("    create http=%d body=%s\n", code, trunc(resp, 200))
	if code >= 400 {
		fail("create failed")
	}
	var created struct {
		ID string `json:"id"`
	}
	json.Unmarshal(resp, &created)
	fmt.Println("    created id:", created.ID)

	fmt.Println("[7] sync again & decrypt")
	code, resp = req("GET", "/api/sync", authHdr, nil)
	var full map[string]json.RawMessage
	json.Unmarshal(resp, &full)
	var ciphers []map[string]any
	json.Unmarshal(full["ciphers"], &ciphers)
	found := false
	for _, c := range ciphers {
		if c["id"] == created.ID {
			found = true
			name, e1 := decWith(userKey[:32], userKey[32:], c["name"].(string))
			lg := c["login"].(map[string]any)
			un, e2 := decWith(userKey[:32], userKey[32:], lg["username"].(string))
			pw, e3 := decWith(userKey[:32], userKey[32:], lg["password"].(string))
			fmt.Printf("    decrypted: name=%q user=%q pass=%q errs=%v/%v/%v\n", name, un, pw, e1, e2, e3)
			if name == "VF Probe Login" && un == "probe-user" && pw == "probe-pass-123" {
				fmt.Println("    E2E DECRYPT OK")
			} else {
				fail("decrypt mismatch")
			}
		}
	}
	if !found {
		fail("cipher not found in sync")
	}

	fmt.Println("[8] delete test cipher")
	if os.Getenv("VF_BW_KEEP") != "1" {
		code, _ = req("DELETE", "/api/ciphers/"+created.ID, authHdr, nil)
	}
	fmt.Printf("    delete cipher http=%d\n", code)

	fmt.Println("[9] admin: delete test account")
	adminToken := os.Getenv("VF_BW_ADMIN_TOKEN")
	if adminToken == "" {
		fmt.Println("    (skipped: VF_BW_ADMIN_TOKEN not set)")
	} else {
		ah := map[string]string{"Authorization": "Bearer " + adminToken}
		code, resp = req("GET", "/admin/users", ah, nil)
		if code == 401 {
			code, resp = req("GET", "/admin/users?token="+url.QueryEscape(adminToken), nil, nil)
		}
		fmt.Printf("    admin users http=%d body=%s\n", code, trunc(resp, 300))
		var users []map[string]any
		json.Unmarshal(resp, &users)
		uid := ""
		for _, u := range users {
			if u["email"] == emailAddr {
				uid, _ = u["id"].(string)
			}
		}
		if uid == "" {
			fmt.Println("    (probe user not found in admin list)")
		} else {
			code, resp = req("DELETE", "/admin/users/"+uid, ah, nil)
			fmt.Printf("    admin delete http=%d body=%s\n", code, trunc(resp, 160))
		}
	}
	fmt.Println("PROBE DONE")
}
