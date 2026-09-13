package auth

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"fmt"
	"os"
	"strconv"
)

// defaultIterations 默认 PBKDF2-HMAC-SHA256 迭代次数。
// 低配 / 测试环境可用 VF_PBKDF2_ITERS 覆盖（仅影响新生成的哈希，
// 已有账号仍按其存储时的迭代次数校验）。
const defaultIterations = 120000

// PasswordIterations 返回当前用于新哈希的迭代次数。
func PasswordIterations() int {
	if v := os.Getenv("VF_PBKDF2_ITERS"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			return n
		}
	}
	return defaultIterations
}

// HashPassword 生成随机盐与 PBKDF2-HMAC-SHA256 哈希（均 hex 编码）。
func HashPassword(password string) (saltHex, hashHex string, iterations int, err error) {
	salt := make([]byte, 16)
	if _, err = rand.Read(salt); err != nil {
		return "", "", 0, fmt.Errorf("生成盐失败: %w", err)
	}
	iterations = PasswordIterations()
	dk := pbkdf2SHA256([]byte(password), salt, iterations, 32)
	return hex.EncodeToString(salt), hex.EncodeToString(dk), iterations, nil
}

// VerifyPassword 常量时间校验密码。
func VerifyPassword(password, saltHex, hashHex string, iterations int) bool {
	salt, err1 := hex.DecodeString(saltHex)
	want, err2 := hex.DecodeString(hashHex)
	if err1 != nil || err2 != nil || len(salt) == 0 || len(want) == 0 || iterations <= 0 {
		return false
	}
	got := pbkdf2SHA256([]byte(password), salt, iterations, len(want))
	return subtle.ConstantTimeCompare(got, want) == 1
}

// pbkdf2SHA256 实现 PBKDF2-HMAC-SHA256（RFC 8018），零第三方依赖。
func pbkdf2SHA256(password, salt []byte, iterations, dkLen int) []byte {
	prf := hmac.New(sha256.New, password)
	hLen := prf.Size()
	numBlocks := (dkLen + hLen - 1) / hLen

	dk := make([]byte, 0, numBlocks*hLen)
	var block [4]byte
	for i := 1; i <= numBlocks; i++ {
		prf.Reset()
		prf.Write(salt)
		block[0] = byte(i >> 24)
		block[1] = byte(i >> 16)
		block[2] = byte(i >> 8)
		block[3] = byte(i)
		prf.Write(block[:])

		u := prf.Sum(nil)
		t := make([]byte, hLen)
		copy(t, u)
		for j := 1; j < iterations; j++ {
			prf.Reset()
			prf.Write(u)
			u = prf.Sum(u[:0])
			for k := 0; k < hLen; k++ {
				t[k] ^= u[k]
			}
		}
		dk = append(dk, t...)
	}
	return dk[:dkLen]
}
