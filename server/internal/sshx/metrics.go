package sshx

import (
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/03chuangling/vaultforge/server/internal/model"
)

// snapshotCmd 一次远程采集：间隔 0.12s 采两轮 /proc 数据，附内存 / 磁盘 / 负载 / 内核。
// 与 App 端 SshClient.SNAPSHOT_CMD 保持一致。
const snapshotCmd = "cat /proc/stat; echo '@@@'; cat /proc/net/dev; echo '@@@'; cat /proc/diskstats; " +
	"echo '@@@'; sleep 0.12; cat /proc/stat; echo '@@@'; cat /proc/net/dev; echo '@@@'; " +
	"cat /proc/diskstats; echo '@@@'; cat /proc/meminfo; echo '@@@'; df -k / 2>/dev/null; " +
	"echo '@@@'; cat /proc/loadavg 2>/dev/null; echo '@@@'; uname -srm 2>/dev/null"

// FetchMetrics 采集一次服务器指标（CPU / 内存 / 网络速率 / 磁盘速率 / 负载 / 内核）。
func FetchMetrics(item *model.VaultItem) *model.MetricsData {
	m := &model.MetricsData{FetchedAt: time.Now().UnixMilli()}
	c, err := Get(item)
	if err != nil {
		m.Error = err.Error()
		return m
	}
	raw, err := c.run(snapshotCmd, 20*time.Second)
	if err != nil {
		m.Error = err.Error()
		return m
	}
	parsed := parseMetrics(raw)
	parsed.FetchedAt = m.FetchedAt
	return parsed
}

func parseMetrics(raw string) *model.MetricsData {
	sec := strings.Split(raw, "@@@")
	if len(sec) < 10 {
		return &model.MetricsData{Error: "指标输出解析失败（数据不完整）"}
	}
	cpu := cpuPercent(sec[0], sec[3])
	nr, nt := netRateBytes(sec[1], sec[4])
	dr, dw := diskRateBytes(sec[2], sec[5])
	memPct, memUsed, memTotal := memInfo(sec[6])
	diskUsed := dfUsed(sec[7])
	load1 := ""
	if f := strings.Fields(sec[8]); len(f) > 0 {
		load1 = f[0]
	}
	kernel := ""
	for _, l := range strings.Split(sec[9], "\n") {
		if strings.TrimSpace(l) != "" {
			kernel = strings.TrimSpace(l)
			break
		}
	}
	return &model.MetricsData{
		Ok:              true,
		CPUPercent:      cpu,
		MemUsedPercent:  memPct,
		MemUsedMb:       memUsed,
		MemTotalMb:      memTotal,
		NetRxKbps:       nr / 1024,
		NetTxKbps:       nt / 1024,
		DiskUsedPercent: diskUsed,
		DiskReadKbps:    dr / 1024,
		DiskWriteKbps:   dw / 1024,
		Load1:           load1,
		Kernel:          kernel,
	}
}

// cpuPercent 由两次 /proc/stat 快照计算 CPU 使用率。
func cpuPercent(s1, s2 string) float64 {
	v1 := cpuFields(s1)
	v2 := cpuFields(s2)
	if len(v1) < 4 || len(v2) < 4 {
		return -1
	}
	sum := func(v []int64) int64 {
		var s int64
		for _, x := range v {
			s += x
		}
		return s
	}
	idle := func(v []int64) int64 {
		id := v[3]
		if len(v) > 4 {
			id += v[4]
		}
		return id
	}
	dt := sum(v2) - sum(v1)
	di := idle(v2) - idle(v1)
	if dt <= 0 {
		return -1
	}
	pct := (1.0 - float64(di)/float64(dt)) * 100.0
	if pct < 0 {
		return 0
	}
	if pct > 100 {
		return 100
	}
	return pct
}

func cpuFields(section string) []int64 {
	for _, line := range strings.Split(section, "\n") {
		if strings.HasPrefix(line, "cpu ") {
			fields := strings.Fields(line)[1:]
			out := make([]int64, 0, len(fields))
			for _, f := range fields {
				if n, err := strconv.ParseInt(f, 10, 64); err == nil {
					out = append(out, n)
				}
			}
			return out
		}
	}
	return nil
}

// netRateBytes 由两次 /proc/net/dev 快照计算收发字节增量（0.12s 窗口）。
func netRateBytes(s1, s2 string) (float64, float64) {
	r1, t1 := netSum(s1)
	r2, t2 := netSum(s2)
	return float64(max64(r2-r1, 0)), float64(max64(t2-t1, 0))
}

func netSum(section string) (int64, int64) {
	var rx, tx int64
	for _, line := range strings.Split(section, "\n") {
		t := strings.TrimSpace(line)
		idx := strings.Index(t, ":")
		if idx <= 0 {
			continue
		}
		name := t[:idx]
		if name == "lo" || strings.Contains(name, " ") {
			continue
		}
		fields := strings.Fields(t[idx+1:])
		if len(fields) >= 9 {
			if v, err := strconv.ParseInt(fields[0], 10, 64); err == nil {
				rx += v
			}
			if v, err := strconv.ParseInt(fields[8], 10, 64); err == nil {
				tx += v
			}
		}
	}
	return rx, tx
}

// diskRateBytes 由两次 /proc/diskstats 快照计算读写字节增量（扇区 × 512）。
func diskRateBytes(s1, s2 string) (float64, float64) {
	r1, w1 := diskSum(s1)
	r2, w2 := diskSum(s2)
	return float64(max64(r2-r1, 0)), float64(max64(w2-w1, 0))
}

func diskSum(section string) (int64, int64) {
	var rd, wr int64
	for _, line := range strings.Split(section, "\n") {
		f := strings.Fields(line)
		if len(f) >= 10 && isWholeDisk(f[2]) {
			if v, err := strconv.ParseInt(f[5], 10, 64); err == nil {
				rd += v * 512
			}
			if v, err := strconv.ParseInt(f[9], 10, 64); err == nil {
				wr += v * 512
			}
		}
	}
	return rd, wr
}

var wholeDiskPatterns = []*regexp.Regexp{
	regexp.MustCompile(`^nvme\d+n\d+$`),
	regexp.MustCompile(`^mmcblk\d+$`),
	regexp.MustCompile(`^[sv]d[a-z]+$`),
	regexp.MustCompile(`^xvd[a-z]+$`),
}

func isWholeDisk(name string) bool {
	if strings.HasPrefix(name, "loop") || strings.HasPrefix(name, "ram") ||
		strings.HasPrefix(name, "sr") || strings.HasPrefix(name, "dm-") {
		return false
	}
	for _, p := range wholeDiskPatterns {
		if p.MatchString(name) {
			return true
		}
	}
	return false
}

// memInfo 解析 MemTotal / MemAvailable，返回使用率与 MB 数。
func memInfo(meminfo string) (float64, int64, int64) {
	var totalKb, availKb int64 = -1, -1
	for _, line := range strings.Split(meminfo, "\n") {
		switch {
		case strings.HasPrefix(line, "MemTotal:"):
			totalKb = firstKb(line)
		case strings.HasPrefix(line, "MemAvailable:"):
			availKb = firstKb(line)
		}
	}
	if totalKb <= 0 {
		return -1, -1, -1
	}
	avail := availKb
	if avail < 0 {
		avail = 0
	}
	used := totalKb - avail
	pct := float64(used) / float64(totalKb) * 100
	return pct, used / 1024, totalKb / 1024
}

func firstKb(line string) int64 {
	fields := strings.Fields(strings.TrimSpace(line[strings.Index(line, ":")+1:]))
	if len(fields) == 0 {
		return -1
	}
	if v, err := strconv.ParseInt(fields[0], 10, 64); err == nil {
		return v
	}
	return -1
}

// dfUsed 解析 df -k / 输出的根分区使用率（%）。
func dfUsed(df string) float64 {
	lines := []string{}
	for _, l := range strings.Split(df, "\n") {
		if strings.TrimSpace(l) != "" {
			lines = append(lines, l)
		}
	}
	if len(lines) < 2 {
		return -1
	}
	f := strings.Fields(lines[1])
	if len(f) >= 5 {
		p := strings.TrimSuffix(f[4], "%")
		if v, err := strconv.ParseFloat(p, 64); err == nil {
			return v
		}
	}
	if len(f) >= 3 {
		total, _ := strconv.ParseFloat(f[1], 64)
		used, _ := strconv.ParseFloat(f[2], 64)
		if total > 0 {
			return used / total * 100
		}
	}
	return -1
}

func max64(a, b int64) int64 {
	if a > b {
		return a
	}
	return b
}
