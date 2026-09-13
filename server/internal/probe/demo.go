package probe

import (
	"encoding/base64"
	"fmt"
	"io"
	"net/http"
	"regexp"
	"strings"
	"time"

	"github.com/03chuangling/vaultforge/server/internal/model"
)

// demoRequest 演示代码中提取出的可执行请求。
type demoRequest struct {
	URL         string
	Method      string
	Headers     map[string]string
	Body        string
	ContentType string
	Source      string // curl | python | js | url
}

// demoResult 演示代码执行结果。
type demoResult struct {
	Extracted bool
	Ok        bool
	LatencyMs int64
	Message   string
}

var (
	rePythonCall = regexp.MustCompile(`(?i)requests\.(get|post|put|delete|patch|head)\s*\(\s*['"]([^'"]+)['"]`)
	reAxios      = regexp.MustCompile(`(?i)axios\.(get|post|put|delete|patch)\s*\(\s*['"]([^'"]+)['"]`)
	reFetch      = regexp.MustCompile(`fetch\s*\(\s*['"]([^'"]+)['"]`)
	reHeaders    = regexp.MustCompile(`(?s)headers\s*[:=]\s*\{(.*?)\}`)
	reKVQuoted   = regexp.MustCompile(`['"]?([\w-]+)['"]?\s*:\s*['"]([^'"]*)['"]`)
	reJSONAssign = regexp.MustCompile(`(?i)json\s*=\s*\{`)
	reBodyObj    = regexp.MustCompile(`(?i)body\s*:\s*(?:JSON\.stringify\s*\(\s*)?\{`)
	reMethodJS   = regexp.MustCompile(`(?i)method\s*:\s*['"]([A-Za-z]+)['"]`)
	reBodyStr    = regexp.MustCompile(`(?i)body\s*:\s*['"]([^'"]+)['"]`)
)

// extractDemo 从演示代码中提取可执行请求；无法识别返回 nil。
func extractDemo(code string) *demoRequest {
	code = strings.TrimSpace(code)
	code = strings.TrimSpace(strings.TrimPrefix(code, "$"))
	if code == "" {
		return nil
	}
	lower := strings.ToLower(code)
	switch {
	case strings.HasPrefix(lower, "curl"):
		return parseCurl(code)
	case rePythonCall.MatchString(code):
		return parsePython(code)
	case strings.Contains(lower, "fetch(") || strings.Contains(lower, "fetch (") || strings.Contains(lower, "axios."):
		return parseJS(code)
	case strings.HasPrefix(code, "http://") || strings.HasPrefix(code, "https://"):
		return parseRawURL(code)
	case strings.Contains(lower, "curl "):
		return parseCurl(code)
	}
	return nil
}

// executeDemo 执行演示代码提取出的请求，返回真实调用结果。
func executeDemo(item *model.VaultItem) demoResult {
	req := extractDemo(item.DemoCode)
	if req == nil {
		return demoResult{Extracted: false, Message: "未能从演示代码中提取可执行请求"}
	}
	tcpMs := tcpLatencyURL(req.URL)
	method := strings.ToUpper(req.Method)
	if method == "" {
		method = "GET"
	}

	var bodyReader io.Reader
	if req.Body != "" {
		bodyReader = strings.NewReader(req.Body)
	} else if method == "POST" || method == "PUT" || method == "PATCH" {
		bodyReader = strings.NewReader("")
	}

	httpReq, err := http.NewRequest(method, req.URL, bodyReader)
	if err != nil {
		return demoResult{Extracted: true, Ok: false, LatencyMs: tcpMs,
			Message: "演示代码中的请求无法执行：" + err.Error()}
	}
	for k, v := range req.Headers {
		if strings.EqualFold(k, "Content-Length") {
			continue
		}
		httpReq.Header.Set(k, v)
	}
	if req.ContentType != "" && httpReq.Header.Get("Content-Type") == "" {
		httpReq.Header.Set("Content-Type", req.ContentType)
	}

	client := &http.Client{Timeout: 20 * time.Second}
	t0 := time.Now()
	resp, err := client.Do(httpReq)
	if err != nil {
		return demoResult{Extracted: true, Ok: false, LatencyMs: tcpMs,
			Message: "演示代码执行失败：" + err.Error()}
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 2000))
	snippet := truncateRunes(strings.Join(strings.Fields(string(raw)), " "), 200)

	ms := time.Since(t0).Milliseconds()
	ok := resp.StatusCode >= 200 && resp.StatusCode <= 299
	var verdict string
	switch {
	case ok:
		verdict = "API 可用"
	case resp.StatusCode == 401 || resp.StatusCode == 403:
		verdict = "认证失败（密钥/权限问题）"
	case resp.StatusCode == 404:
		verdict = "路径或地址有误"
	case resp.StatusCode == 429:
		verdict = "触发限流"
	case resp.StatusCode >= 500:
		verdict = "服务端错误"
	default:
		verdict = "异常响应"
	}
	msg := fmt.Sprintf("演示代码已执行（%s）· HTTP %d · %s", req.Source, resp.StatusCode, verdict)
	if sn := truncateRunes(snippet, 80); sn != "" {
		msg += " · " + sn
	}
	lat := ms
	if tcpMs >= 0 {
		lat = tcpMs
	}
	return demoResult{Extracted: true, Ok: ok, LatencyMs: lat, Message: msg}
}

func truncateRunes(s string, n int) string {
	r := []rune(s)
	if len(r) <= n {
		return s
	}
	return string(r[:n])
}

// ==================== 解析：curl ====================

func parseCurl(code string) *demoRequest {
	tokens := tokenizeCurl(code)
	if len(tokens) == 0 {
		return nil
	}
	var (
		rawURL  string
		method  string
		body    string
		ct      string
		basic   string
		headers = map[string]string{}
	)
	for i := 1; i < len(tokens); i++ {
		t := tokens[i]
		next := ""
		if i+1 < len(tokens) {
			next = tokens[i+1]
		}
		switch {
		case t == "-X" || t == "--request":
			method = strings.ToUpper(next)
			i++
		case t == "-H" || t == "--header":
			if idx := strings.Index(next, ":"); idx > 0 {
				headers[strings.TrimSpace(next[:idx])] = strings.TrimSpace(next[idx+1:])
			}
			i++
		case t == "-u" || t == "--user":
			basic = next
			i++
		case t == "--json":
			body = next
			ct = "application/json"
			i++
		case isDataFlag(t):
			body = next
			i++
		case t == "--url":
			rawURL = next
			i++
		case t == "-I" || t == "--head":
			method = "HEAD"
		case strings.HasPrefix(t, "http://") || strings.HasPrefix(t, "https://"):
			if rawURL == "" {
				rawURL = t
			}
		case isValueFlag(t):
			i++ // 跳过该开关的值
		}
	}
	if rawURL == "" {
		return nil
	}
	if basic != "" {
		headers["Authorization"] = "Basic " + base64.StdEncoding.EncodeToString([]byte(basic))
	}
	if body != "" {
		if ct == "" {
			for k, v := range headers {
				if strings.EqualFold(k, "content-type") {
					ct = v
				}
			}
		}
		if ct == "" {
			b := strings.TrimLeft(body, " \t\r\n")
			if strings.HasPrefix(b, "{") || strings.HasPrefix(b, "[") {
				ct = "application/json"
			} else {
				ct = "application/x-www-form-urlencoded"
			}
		}
	}
	m := method
	if m == "" {
		if body != "" {
			m = "POST"
		} else {
			m = "GET"
		}
	}
	return &demoRequest{URL: rawURL, Method: m, Headers: headers, Body: body, ContentType: ct, Source: "curl"}
}

func isDataFlag(t string) bool {
	switch t {
	case "-d", "--data", "--data-raw", "--data-binary", "--data-ascii", "--data-urlencode":
		return true
	}
	return false
}

func isValueFlag(t string) bool {
	switch t {
	case "-A", "--user-agent", "-e", "--referer", "-o", "--output", "-b", "--cookie",
		"-w", "--write-out", "-x", "--proxy", "--connect-timeout", "-m", "--max-time",
		"-F", "--form", "--form-string":
		return true
	}
	return false
}

// tokenizeCurl 命令行分词：支持单 / 双引号、反斜杠续行。
func tokenizeCurl(input string) []string {
	s := strings.ReplaceAll(input, "\\\r\n", " ")
	s = strings.ReplaceAll(s, "\\\n", " ")
	out := []string{}
	var sb strings.Builder
	var quote rune
	runes := []rune(s)
	for i := 0; i < len(runes); i++ {
		c := runes[i]
		if quote != 0 {
			switch {
			case c == quote:
				quote = 0
			case c == '\\' && quote == '"' && i+1 < len(runes):
				sb.WriteRune(runes[i+1])
				i++
			default:
				sb.WriteRune(c)
			}
		} else {
			switch {
			case c == '\'' || c == '"':
				quote = c
			case c == ' ' || c == '\t' || c == '\n' || c == '\r':
				if sb.Len() > 0 {
					out = append(out, sb.String())
					sb.Reset()
				}
			default:
				sb.WriteRune(c)
			}
		}
	}
	if sb.Len() > 0 {
		out = append(out, sb.String())
	}
	return out
}

// ==================== 解析：Python requests ====================

func parsePython(code string) *demoRequest {
	m := rePythonCall.FindStringSubmatch(code)
	if m == nil {
		return nil
	}
	method := strings.ToUpper(m[1])
	rawURL := m[2]
	headers := map[string]string{}
	if hm := reHeaders.FindStringSubmatch(code); hm != nil {
		for _, kv := range reKVQuoted.FindAllStringSubmatch(hm[1], -1) {
			headers[kv[1]] = kv[2]
		}
	}
	body := ""
	ct := ""
	if loc := reJSONAssign.FindStringIndex(code); loc != nil {
		if raw := balancedFrom(code, loc[1]-1); raw != "" {
			body = strings.ReplaceAll(raw, "'", "\"")
			ct = "application/json"
		}
	}
	return &demoRequest{URL: rawURL, Method: method, Headers: headers, Body: body, ContentType: ct, Source: "python"}
}

// ==================== 解析：JS fetch / axios ====================

func parseJS(code string) *demoRequest {
	rawURL := ""
	method := ""
	if m := reFetch.FindStringSubmatch(code); m != nil {
		rawURL = m[1]
		method = "GET"
	}
	if rawURL == "" {
		if m := reAxios.FindStringSubmatch(code); m != nil {
			rawURL = m[2]
			method = strings.ToUpper(m[1])
		}
	}
	if rawURL == "" {
		return nil
	}
	if m := reMethodJS.FindStringSubmatch(code); m != nil {
		method = strings.ToUpper(m[1])
	}
	headers := map[string]string{}
	if hm := reHeaders.FindStringSubmatch(code); hm != nil {
		for _, kv := range reKVQuoted.FindAllStringSubmatch(hm[1], -1) {
			headers[kv[1]] = kv[2]
		}
	}
	body := ""
	ct := ""
	if loc := reBodyObj.FindStringIndex(code); loc != nil {
		if raw := balancedFrom(code, loc[1]-1); raw != "" {
			body = raw
			ct = "application/json"
		}
	} else if m := reBodyStr.FindStringSubmatch(code); m != nil {
		body = m[1]
	}
	return &demoRequest{URL: rawURL, Method: method, Headers: headers, Body: body, ContentType: ct, Source: "js"}
}

// ==================== 解析：裸 URL ====================

func parseRawURL(code string) *demoRequest {
	for _, line := range strings.Split(code, "\n") {
		t := strings.TrimSpace(line)
		if strings.HasPrefix(t, "http://") || strings.HasPrefix(t, "https://") {
			u := t
			if i := strings.IndexByte(t, ' '); i > 0 {
				u = t[:i]
			}
			return &demoRequest{URL: u, Method: "GET", Headers: map[string]string{}, Source: "url"}
		}
	}
	return nil
}

// balancedFrom 从 startIdx（'{' 位置）起按括号平衡截取 { ... }。
func balancedFrom(s string, startIdx int) string {
	if startIdx < 0 || startIdx >= len(s) || s[startIdx] != '{' {
		return ""
	}
	depth := 0
	for j := startIdx; j < len(s); j++ {
		switch s[j] {
		case '{':
			depth++
		case '}':
			depth--
			if depth == 0 {
				return s[startIdx : j+1]
			}
		}
	}
	return ""
}
