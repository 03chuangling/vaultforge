package auth

import (
	"encoding/hex"
	"testing"
)

// PBKDF2-HMAC-SHA256 公开测试向量。
func TestPBKDF2SHA256Vectors(t *testing.T) {
	cases := []struct {
		password string
		salt     string
		iter     int
		wantHex  string
	}{
		{"password", "salt", 1, "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b"},
		{"password", "salt", 2, "ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43"},
		{"password", "salt", 4096, "c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a"},
	}
	for _, c := range cases {
		got := hex.EncodeToString(pbkdf2SHA256([]byte(c.password), []byte(c.salt), c.iter, 32))
		if got != c.wantHex {
			t.Fatalf("PBKDF2(%q,%q,%d) = %s, want %s", c.password, c.salt, c.iter, got, c.wantHex)
		}
	}
}

func TestHashVerifyPassword(t *testing.T) {
	t.Setenv("VF_PBKDF2_ITERS", "2000")
	salt, hash, iters, err := HashPassword("s3cret-pass")
	if err != nil {
		t.Fatal(err)
	}
	if !VerifyPassword("s3cret-pass", salt, hash, iters) {
		t.Fatal("正确密码校验失败")
	}
	if VerifyPassword("wrong-pass", salt, hash, iters) {
		t.Fatal("错误密码不应通过")
	}
	if VerifyPassword("s3cret-pass", "zz", hash, iters) {
		t.Fatal("非法盐不应通过")
	}
}
