package api

import (
	"encoding/json"
	"net/http"
)

// Response 统一响应体：{"code":0,"message":"ok","data":...}
// 与 App 本地接口（VaultServer）约定保持一致：
//   - 成功 code = 0；
//   - 错误 code 与 HTTP 状态码同步（400/401/404/500），data 省略。
type Response struct {
	Code    int    `json:"code"`
	Message string `json:"message"`
	Data    any    `json:"data,omitempty"`
}

// writeJSON 输出统一响应。
func writeJSON(w http.ResponseWriter, status, code int, message string, data any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(Response{Code: code, Message: message, Data: data})
}

// okMsg 成功响应（code = 0，自定义 message，与 App 的
// "created"/"updated"/"deleted" 等文案对齐）。
func okMsg(w http.ResponseWriter, message string, data any) {
	writeJSON(w, http.StatusOK, 0, message, data)
}

// ok 成功响应，默认 message = "ok"。
func ok(w http.ResponseWriter, data any) { okMsg(w, "ok", data) }

// fail 错误响应：code = HTTP 状态码。
func fail(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, status, message, nil)
}

// readJSON 解析请求体。
func readJSON(r *http.Request, v any) error {
	return json.NewDecoder(r.Body).Decode(v)
}
