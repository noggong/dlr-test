package main

// Redis 측정 — 구간마다 통계 초기화(CONFIG RESETSTAT, SLOWLOG RESET) → 끝나고 INFO, SLOWLOG 로 Lua 스크립트별 호출 수 / 실행 시간.
// persister 적체는 Stream 1,024개의 XINFO GROUPS 를 주기적으로 합쳐 본다. 데이터를 지우는 명령은 쓰지 않는다

import (
	"bufio"
	"encoding/csv"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

var redisAddr = "redis:6379"

type rconn struct {
	c net.Conn
	r *bufio.Reader
}

func rdial() *rconn {
	c, err := net.Dial("tcp", redisAddr)
	must(err)
	return &rconn{c: c, r: bufio.NewReaderSize(c, 1<<20)}
}

func (rc *rconn) send(args ...string) {
	var b strings.Builder
	fmt.Fprintf(&b, "*%d\r\n", len(args))
	for _, a := range args {
		fmt.Fprintf(&b, "$%d\r\n%s\r\n", len(a), a)
	}
	_, err := rc.c.Write([]byte(b.String()))
	must(err)
}

func (rc *rconn) read() any {
	line, err := rc.r.ReadString('\n')
	must(err)
	line = strings.TrimRight(line, "\r\n")
	switch line[0] {
	case '+':
		return line[1:]
	case '-':
		return fmt.Errorf("%s", line[1:])
	case ':':
		v, _ := strconv.ParseInt(line[1:], 10, 64)
		return v
	case '$':
		n, _ := strconv.Atoi(line[1:])
		if n < 0 {
			return nil
		}
		buf := make([]byte, n+2)
		_, err := ioReadFull(rc.r, buf)
		must(err)
		return string(buf[:n])
	case '*':
		n, _ := strconv.Atoi(line[1:])
		if n < 0 {
			return nil
		}
		out := make([]any, n)
		for i := range out {
			out[i] = rc.read()
		}
		return out
	}
	panic("resp: " + line)
}

func ioReadFull(r *bufio.Reader, b []byte) (int, error) {
	n := 0
	for n < len(b) {
		m, err := r.Read(b[n:])
		n += m
		if err != nil {
			return n, err
		}
	}
	return n, nil
}

func (rc *rconn) do(args ...string) any {
	rc.send(args...)
	return rc.read()
}

func str(v any) string {
	switch x := v.(type) {
	case string:
		return x
	case int64:
		return strconv.FormatInt(x, 10)
	}
	return fmt.Sprint(v)
}

func parseInfo(s string) map[string]string {
	m := map[string]string{}
	for _, l := range strings.Split(s, "\n") {
		l = strings.TrimSpace(l)
		if l == "" || l[0] == '#' {
			continue
		}
		if i := strings.IndexByte(l, ':'); i > 0 {
			m[l[:i]] = l[i+1:]
		}
	}
	return m
}

// ── 구간 측정 ──

var shaName = map[string]string{} // -shamap 파일: sha,name

func loadShaMap(file string) {
	if file == "" {
		return
	}
	f, err := os.Open(file)
	must(err)
	defer f.Close()
	rows, _ := csv.NewReader(f).ReadAll()
	for _, r := range rows {
		shaName[r[0]] = r[1]
	}
}

type redisPhase struct {
	name string
	t0   time.Time
	cpu0 float64
}

var slowlogOrig []string

// redisDayBegin — 모든 명령을 SLOWLOG 에 남기도록(문턱 0). 끝나면 원래 값으로
func redisDayBegin() {
	rc := rdial()
	defer rc.c.Close()
	a := rc.do("CONFIG", "GET", "slowlog-log-slower-than").([]any)
	b := rc.do("CONFIG", "GET", "slowlog-max-len").([]any)
	slowlogOrig = []string{str(a[1]), str(b[1])}
	rc.do("CONFIG", "SET", "slowlog-log-slower-than", "0")
	rc.do("CONFIG", "SET", "slowlog-max-len", "300000")
	fmt.Println("redis slowlog threshold 0 (was", slowlogOrig, ")")
}

func redisDayEnd() {
	if slowlogOrig == nil {
		return
	}
	rc := rdial()
	defer rc.c.Close()
	rc.do("CONFIG", "SET", "slowlog-log-slower-than", slowlogOrig[0])
	rc.do("CONFIG", "SET", "slowlog-max-len", slowlogOrig[1])
	rc.do("SLOWLOG", "RESET")
	fmt.Println("redis slowlog restored", slowlogOrig)
}

func cpuUsed(rc *rconn) float64 {
	m := parseInfo(str(rc.do("INFO", "cpu")))
	u, _ := strconv.ParseFloat(m["used_cpu_user"], 64)
	s, _ := strconv.ParseFloat(m["used_cpu_sys"], 64)
	return u + s
}

// redisBegin — slow=false 면 이 구간은 SLOWLOG 를 끈다(재시도 폭주 — 기록 비용이 처리량을 깎지 않게, 스크립트별 값은 commandstats 로만)
func redisBegin(name string, slow bool) *redisPhase {
	rc := rdial()
	defer rc.c.Close()
	th := "0"
	if !slow {
		th = "-1"
	}
	rc.do("CONFIG", "SET", "slowlog-log-slower-than", th)
	rc.do("CONFIG", "RESETSTAT")
	rc.do("SLOWLOG", "RESET")
	return &redisPhase{name: name, t0: time.Now(), cpu0: cpuUsed(rc)}
}

type luaStat struct {
	Script  string  `json:"script"`
	Calls   int     `json:"calls"`
	MeanUs  float64 `json:"meanUs"`
	P50Us   int64   `json:"p50Us"`
	P99Us   int64   `json:"p99Us"`
	MaxUs   int64   `json:"maxUs"`
	TotalMs float64 `json:"totalMs"`
}

func redisEnd(p *redisPhase) map[string]any {
	rc := rdial()
	defer rc.c.Close()
	wall := time.Since(p.t0).Seconds()
	cpu := cpuUsed(rc) - p.cpu0
	stats := parseInfo(str(rc.do("INFO", "stats")))
	cmdRaw := parseInfo(str(rc.do("INFO", "commandstats")))
	cmds := map[string]string{}
	for k, v := range cmdRaw {
		cmds[strings.TrimPrefix(k, "cmdstat_")] = v
	}
	// SLOWLOG: [id, ts, µs, [args...], addr, name]
	ent := rc.do("SLOWLOG", "GET", "-1")
	durs := map[string][]int64{}
	if arr, ok := ent.([]any); ok {
		for _, e := range arr {
			x := e.([]any)
			us := x[2].(int64)
			args := x[3].([]any)
			cmd := strings.ToUpper(str(args[0]))
			key := cmd
			if (cmd == "EVALSHA" || cmd == "EVALSHA_RO") && len(args) > 1 {
				sha := str(args[1])
				if n, ok := shaName[sha]; ok {
					key = "lua:" + n
				} else {
					key = "lua:" + sha[:min(12, len(sha))]
				}
			}
			durs[key] = append(durs[key], us)
		}
	}
	rc.do("SLOWLOG", "RESET") // 기록이 차지한 메모리를 비운 뒤 잰다
	mem := parseInfo(str(rc.do("INFO", "memory")))
	var ls []luaStat
	for k, d := range durs {
		sort.Slice(d, func(i, j int) bool { return d[i] < d[j] })
		var sum int64
		for _, v := range d {
			sum += v
		}
		ls = append(ls, luaStat{Script: k, Calls: len(d), MeanUs: float64(sum) / float64(len(d)), P50Us: d[len(d)/2],
			P99Us: d[min(len(d)-1, int(float64(len(d))*0.99))], MaxUs: d[len(d)-1], TotalMs: float64(sum) / 1000})
	}
	sort.Slice(ls, func(i, j int) bool { return ls[i].TotalMs > ls[j].TotalMs })
	total, _ := strconv.ParseFloat(stats["total_commands_processed"], 64)
	out := map[string]any{
		"phase": p.name, "wallSec": wall, "commands": total, "opsPerSec": total / wall,
		"cpuSec": cpu, "cpuPct": cpu / wall * 100,
		"usedMemory": mem["used_memory_human"], "usedMemoryBytes": mem["used_memory"], "peakMemory": mem["used_memory_peak_human"],
		"commandstats": cmds, "slowlogEntries": len(durs), "byCommand": ls,
	}
	fmt.Printf("\n== redis %s — %.0f cmds in %.1fs = %.0f ops/s, cpu %.1f%%, mem %s\n", p.name, total, wall, total/wall, cpu/wall*100, mem["used_memory_human"])
	for i, l := range ls {
		if i >= 12 {
			break
		}
		fmt.Printf("  %-28s calls %7d  mean %7.1fµs  p50 %5dµs  p99 %6dµs  max %7dµs  total %8.1fms\n", l.Script, l.Calls, l.MeanUs, l.P50Us, l.P99Us, l.MaxUs, l.TotalMs)
	}
	return out
}

// groupHashSize — 그룹 Hash 몇 개의 MEMORY USAGE
func groupHashSizes(date string) map[string]any {
	rc := rdial()
	defer rc.c.Close()
	cur := "0"
	var sizes []int64
	var fields []int64
	for i := 0; i < 2000 && len(sizes) < 200; i++ {
		r := rc.do("SCAN", cur, "MATCH", "g:{*}:*:"+date, "COUNT", "1000").([]any)
		cur = str(r[0])
		for _, k := range r[1].([]any) {
			ks := str(k)
			if v, ok := rc.do("MEMORY", "USAGE", ks).(int64); ok {
				sizes = append(sizes, v)
				fields = append(fields, rc.do("HLEN", ks).(int64))
			}
		}
		if cur == "0" {
			break
		}
	}
	sort.Slice(sizes, func(i, j int) bool { return sizes[i] < sizes[j] })
	if len(sizes) == 0 {
		return map[string]any{"count": 0}
	}
	var sum int64
	for _, s := range sizes {
		sum += s
	}
	return map[string]any{"count": len(sizes), "minBytes": sizes[0], "meanBytes": sum / int64(len(sizes)), "maxBytes": sizes[len(sizes)-1]}
}

// ── persister 적체 샘플러 ──

type streamSample struct {
	at                              time.Time
	xlen, entriesRead, pending, lag int64
}

type streamSampler struct {
	mu   sync.Mutex
	s    []streamSample
	stop chan struct{}
	done chan struct{}
}

func startStreamSampler(every time.Duration) *streamSampler {
	ss := &streamSampler{stop: make(chan struct{}), done: make(chan struct{})}
	go func() {
		defer close(ss.done)
		rc := rdial()
		defer rc.c.Close()
		t := time.NewTicker(every)
		defer t.Stop()
		for {
			at := time.Now()
			for b := 0; b < 1024; b++ {
				rc.send("XLEN", fmt.Sprintf("ev:{%d}", b))
				rc.send("XINFO", "GROUPS", fmt.Sprintf("ev:{%d}", b))
			}
			var x streamSample
			x.at = at
			for b := 0; b < 1024; b++ {
				if v, ok := rc.read().(int64); ok {
					x.xlen += v
				}
				if gs, ok := rc.read().([]any); ok {
					for _, g := range gs {
						kv := g.([]any)
						for i := 0; i+1 < len(kv); i += 2 {
							v, _ := kv[i+1].(int64)
							switch str(kv[i]) {
							case "pending":
								x.pending += v
							case "lag":
								x.lag += v
							case "entries-read":
								x.entriesRead += v
							}
						}
					}
				}
			}
			ss.mu.Lock()
			ss.s = append(ss.s, x)
			ss.mu.Unlock()
			select {
			case <-ss.stop:
				return
			case <-t.C:
			}
		}
	}()
	return ss
}

func (ss *streamSampler) finish(file string) []streamSample {
	close(ss.stop)
	<-ss.done
	f, err := os.Create(filepath.Join(outDir, file))
	must(err)
	defer f.Close()
	fmt.Fprintln(f, "time_utc,xlen_sum,entries_read_sum,pending_sum,lag_sum")
	for _, x := range ss.s {
		fmt.Fprintf(f, "%s,%d,%d,%d,%d\n", x.at.UTC().Format("15:04:05.000"), x.xlen, x.entriesRead, x.pending, x.lag)
	}
	return ss.s
}

// persisterStats — 구간 [from, to) 동안 적체 최대, 처리량(acked 증가 / 초)
func persisterStats(s []streamSample, from, to time.Time) map[string]any {
	var maxBacklog, maxPending, maxLag int64
	var first, last *streamSample
	var peakRate float64
	var prev *streamSample
	for i := range s {
		x := &s[i]
		if x.at.Before(from) || !x.at.Before(to) {
			continue
		}
		if first == nil {
			first = x
		}
		last = x
		if b := x.pending + x.lag; b > maxBacklog {
			maxBacklog = b
		}
		maxPending = max(maxPending, x.pending)
		maxLag = max(maxLag, x.lag)
		if prev != nil {
			dt := x.at.Sub(prev.at).Seconds()
			acked := float64((x.entriesRead - x.pending) - (prev.entriesRead - prev.pending))
			if dt > 0 && acked/dt > peakRate {
				peakRate = acked / dt
			}
		}
		prev = x
	}
	if first == nil {
		return map[string]any{}
	}
	return map[string]any{"maxBacklog": maxBacklog, "maxPending": maxPending, "maxLag": maxLag,
		"newEntries": last.xlen - first.xlen, "ackedEntries": (last.entriesRead - last.pending) - (first.entriesRead - first.pending),
		"peakAckPerSec": peakRate}
}

// drainTime — from 뒤로 적체가 처음 0 이 된 시각까지 걸린 시간
func drainTime(s []streamSample, from time.Time) float64 {
	for _, x := range s {
		if x.at.After(from) && x.pending+x.lag == 0 {
			return x.at.Sub(from).Seconds()
		}
	}
	return -1
}

// hold — 호스트 러너가 PG 를 재고 go 파일을 만들 때까지 기다린다
func hold(name string) {
	h := filepath.Join(outDir, "hold_"+name)
	g := filepath.Join(outDir, "go_"+name)
	must(os.WriteFile(h, []byte(time.Now().UTC().Format(time.RFC3339Nano)), 0o644))
	fmt.Println("hold", name, "— waiting for host")
	for i := 0; i < 600; i++ {
		if _, err := os.Stat(g); err == nil {
			os.Remove(h)
			os.Remove(g)
			return
		}
		time.Sleep(500 * time.Millisecond)
	}
	panic("hold timeout " + name)
}
