package probe

import (
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/03chuangling/vaultforge/server/internal/model"
)

// probeAPI API 条目探测：优先执行「官方演示代码」，回退为地址连通性探测。
func probeAPI(item *model.VaultItem) Result {
	if strings.TrimSpace(item.DemoCode) != "" {
		demo := executeDemo(item)
		if demo.Extracted {
			return Result{Ok: demo.Ok, LatencyMs: demo.LatencyMs, Message: demo.Message}
		}
		fallback := probeEndpoint(item)
		fallback.Message = "（演示代码未能解析，已按地址连通性探测）" + fallback.Message
		return fallback
	}
	return probeEndpoint(item)
}

// probeEndpoint 地址连通性探测：TCP 延迟 + HTTP GET 实际请求。
func probeEndpoint(item *model.VaultItem) Result {
	endpoint := strings.TrimSpace(item.Endpoint)
	if endpoint == "" {
		return Result{Ok: false, LatencyMs: -1, Message: "未填写调用地址"}
	}
	tcpMs := tcpLatencyURL(endpoint)
	t0 := time.Now()
	client := &http.Client{Timeout: 10 * time.Second}
	resp, err := client.Get(endpoint)
	if err != nil {
		return Result{Ok: false, LatencyMs: -1, Message: "请求失败：" + err.Error()}
	}
	defer resp.Body.Close()
	io.Copy(io.Discard, io.LimitReader(resp.Body, 4096))
	ms := time.Since(t0).Milliseconds()
	if resp.StatusCode >= 200 && resp.StatusCode <= 399 {
		lat := ms
		if tcpMs >= 0 {
			lat = tcpMs
		}
		return Result{Ok: true, LatencyMs: lat, Message: fmt.Sprintf("HTTP %d · 可用", resp.StatusCode)}
	}
	return Result{Ok: false, LatencyMs: ms, Message: fmt.Sprintf("HTTP %d · 不可用", resp.StatusCode)}
}
