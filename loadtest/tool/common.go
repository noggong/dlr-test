package main

// 공통 — HTTP 호출, 지연 기록, 게임 시계, 계정 목록

import (
	"bufio"
	"bytes"
	"encoding/csv"
	"encoding/json"
	"fmt"
	"io"
	"math/rand"
	"net"
	"net/http"
	"net/http/cookiejar"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

var (
	apiBase = "http://api:8080"
	rtBase  = "http://realtime:8081"
	outDir  = "results"
)

var password = "seed-pass-1"

var transport = &http.Transport{
	DialContext:         (&net.Dialer{Timeout: 10 * time.Second, KeepAlive: 30 * time.Second}).DialContext,
	MaxIdleConns:        20000,
	MaxIdleConnsPerHost: 20000,
	IdleConnTimeout:     90 * time.Second,
}

// ── 지연 기록 ──

type sample struct {
	name   string
	start  time.Time // 실제 시각
	lat    time.Duration
	status int // 0 = 네트워크 오류
	code   string
}

type recorder struct {
	mu sync.Mutex
	s  []sample
}

var rec = &recorder{}

func (r *recorder) add(s sample) {
	r.mu.Lock()
	r.s = append(r.s, s)
	r.mu.Unlock()
}

func (r *recorder) reset() []sample {
	r.mu.Lock()
	defer r.mu.Unlock()
	out := r.s
	r.s = nil
	return out
}

type stat struct {
	Name      string         `json:"name"`
	Count     int            `json:"count"`
	Errors    int            `json:"errors"` // 기대 밖 응답(5xx, 네트워크 오류, 기대 안 한 4xx)
	ErrorRate float64        `json:"errorRate"`
	P50ms     float64        `json:"p50ms"`
	P95ms     float64        `json:"p95ms"`
	P99ms     float64        `json:"p99ms"`
	MaxMs     float64        `json:"maxMs"`
	MeanMs    float64        `json:"meanMs"`
	WallSec   float64        `json:"wallSec"` // 첫 요청 시작 ~ 마지막 응답
	RPS       float64        `json:"rps"`
	Codes     map[string]int `json:"codes"`
}

func pct(d []time.Duration, p float64) float64 {
	if len(d) == 0 {
		return 0
	}
	i := int(float64(len(d))*p+0.999999) - 1
	if i < 0 {
		i = 0
	}
	if i >= len(d) {
		i = len(d) - 1
	}
	return ms(d[i])
}

func ms(d time.Duration) float64 { return float64(d.Microseconds()) / 1000 }

// summarize — ok 가 nil 이면 2xx 만 정상
func summarize(name string, ss []sample, ok func(sample) bool) stat {
	if ok == nil {
		ok = func(s sample) bool { return s.status >= 200 && s.status < 300 }
	}
	st := stat{Name: name, Codes: map[string]int{}}
	var d []time.Duration
	var first, last time.Time
	var sum time.Duration
	for _, s := range ss {
		st.Count++
		if !ok(s) {
			st.Errors++
		}
		k := fmt.Sprint(s.status)
		if s.code != "" {
			k += " " + s.code
		}
		st.Codes[k]++
		d = append(d, s.lat)
		sum += s.lat
		if first.IsZero() || s.start.Before(first) {
			first = s.start
		}
		if e := s.start.Add(s.lat); e.After(last) {
			last = e
		}
	}
	if st.Count == 0 {
		return st
	}
	sort.Slice(d, func(i, j int) bool { return d[i] < d[j] })
	st.ErrorRate = float64(st.Errors) / float64(st.Count)
	st.P50ms, st.P95ms, st.P99ms = pct(d, .50), pct(d, .95), pct(d, .99)
	st.MaxMs = ms(d[len(d)-1])
	st.MeanMs = ms(sum / time.Duration(st.Count))
	st.WallSec = last.Sub(first).Seconds()
	if st.WallSec > 0 {
		st.RPS = float64(st.Count) / st.WallSec
	}
	return st
}

func byName(ss []sample) map[string][]sample {
	m := map[string][]sample{}
	for _, s := range ss {
		m[s.name] = append(m[s.name], s)
	}
	return m
}

func names(m map[string][]sample) []string {
	var k []string
	for n := range m {
		k = append(k, n)
	}
	sort.Strings(k)
	return k
}

func writeRaw(file string, ss []sample, game time.Duration) {
	f, err := os.Create(filepath.Join(outDir, file))
	must(err)
	defer f.Close()
	w := bufio.NewWriter(f)
	sort.Slice(ss, func(i, j int) bool { return ss[i].start.Before(ss[j].start) })
	every := 1
	if len(ss) > 50000 {
		every = (len(ss) + 49999) / 50000
		fmt.Fprintf(w, "# sample 1/%d of %d requests (percentiles in *_summary.json use all)\n", every, len(ss))
	}
	fmt.Fprintln(w, "name,start_game,latency_ms,status,code")
	for i, s := range ss {
		if i%every != 0 {
			continue
		}
		fmt.Fprintf(w, "%s,%s,%.3f,%d,%s\n", s.name, s.start.Add(game).Format("15:04:05.000000"), ms(s.lat), s.status, s.code)
	}
	must(w.Flush())
}

func writeJSON(file string, v any) {
	b, err := json.MarshalIndent(v, "", "  ")
	must(err)
	must(os.WriteFile(filepath.Join(outDir, file), b, 0o644))
}

func printStats(title string, st []stat) {
	fmt.Printf("\n== %s\n%-22s %7s %6s %8s %8s %8s %8s %9s\n", title, "name", "count", "err%", "p50", "p95", "p99", "max", "rps")
	for _, s := range st {
		fmt.Printf("%-22s %7d %6.2f %8.1f %8.1f %8.1f %8.1f %9.1f  %v\n", s.Name, s.Count, s.ErrorRate*100, s.P50ms, s.P95ms, s.P99ms, s.MaxMs, s.RPS, s.Codes)
	}
}

func must(err error) {
	if err != nil {
		panic(err)
	}
}

// ── 사용자 ──

type question struct {
	QuestionID string `json:"questionId"`
	Difficulty int    `json:"difficulty"`
}

type user struct {
	ID    string
	Group string
	c     *http.Client
	bank  []question
	rnd   *rand.Rand
}

func newUser(id, group string, seed int64) *user {
	jar, _ := cookiejar.New(nil)
	return &user{ID: id, Group: group, c: &http.Client{Transport: transport, Jar: jar, Timeout: 30 * time.Second}, rnd: rand.New(rand.NewSource(seed))}
}

func loadUsers(file string) []*user {
	f, err := os.Open(file)
	must(err)
	defer f.Close()
	rows, err := csv.NewReader(f).ReadAll()
	must(err)
	var us []*user
	for i, r := range rows {
		if i == 0 {
			continue
		}
		us = append(us, newUser(r[0], r[1], int64(i)))
	}
	return us
}

type apiErr struct {
	Code string `json:"code"`
}

// call — 요청 한 번. name 이 비면 기록하지 않는다. 401 UNAUTHORIZED 면 refresh 한 번 뒤 다시(api-guide §1)
func (u *user) call(name, method, url string, body any) (int, []byte, error) {
	st, b, err := u.call1(name, method, url, body)
	if st == 401 && !strings.Contains(url, "/auth/") && bytes.Contains(b, []byte(`"UNAUTHORIZED"`)) {
		if rs, _, _ := u.call1("refresh", "POST", apiBase+"/api/v1/auth/refresh", nil); rs == 200 {
			return u.call1(name, method, url, body)
		}
	}
	return st, b, err
}

func (u *user) call1(name, method, url string, body any) (int, []byte, error) {
	var rd io.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		rd = bytes.NewReader(b)
	}
	req, _ := http.NewRequest(method, url, rd)
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	t0 := time.Now()
	res, err := u.c.Do(req)
	if err != nil {
		if name != "" {
			rec.add(sample{name: name, start: t0, lat: time.Since(t0), status: 0, code: "NET"})
		}
		return 0, nil, err
	}
	b, _ := io.ReadAll(res.Body)
	res.Body.Close()
	lat := time.Since(t0)
	code := ""
	if res.StatusCode >= 400 {
		var e apiErr
		_ = json.Unmarshal(b, &e)
		code = e.Code
	}
	if name != "" {
		rec.add(sample{name: name, start: t0, lat: lat, status: res.StatusCode, code: code})
	}
	return res.StatusCode, b, nil
}

func (u *user) login(name string) error {
	st, b, err := u.call(name, "POST", apiBase+"/api/v1/auth/login", map[string]string{"loginId": u.ID, "password": password})
	if err != nil {
		return err
	}
	if st != 200 {
		return fmt.Errorf("login %s: %d %s", u.ID, st, b)
	}
	return nil
}

func (u *user) logout() {
	u.call("", "POST", apiBase+"/api/v1/auth/logout", nil)
}

func (u *user) fetchBank(name string) error {
	st, b, err := u.call(name, "POST", apiBase+"/api/v1/games/today/question-bank", nil)
	if err != nil {
		return err
	}
	if st != 200 {
		return fmt.Errorf("bank %s: %d %s", u.ID, st, b)
	}
	var r struct {
		Questions []question `json:"questions"`
	}
	must(json.Unmarshal(b, &r))
	u.bank = r.Questions
	return nil
}

func (u *user) pick(no int) string {
	var c []string
	for _, q := range u.bank {
		if q.Difficulty == no {
			c = append(c, q.QuestionID)
		}
	}
	return c[u.rnd.Intn(len(c))]
}

func iso(t time.Time) string { return t.Format("2006-01-02T15:04:05.000000-07:00") }

// ── 게임 시계 ──

var kst = time.FixedZone("KST", 9*3600)

type clockResp struct {
	ServerTime string `json:"serverTime"`
	GameDate   string `json:"gameDate"`
}

// gameOffset — 게임 시각 = time.Now() + offset
func gameOffset() (time.Duration, clockResp) {
	best := time.Hour
	var off time.Duration
	var cr clockResp
	for i := 0; i < 5; i++ {
		t0 := time.Now()
		res, err := http.Get(apiBase + "/api/v1/dev/clock")
		must(err)
		b, _ := io.ReadAll(res.Body)
		res.Body.Close()
		t1 := time.Now()
		must(json.Unmarshal(b, &cr))
		st, err := time.Parse(time.RFC3339Nano, cr.ServerTime)
		must(err)
		if rtt := t1.Sub(t0); rtt < best {
			best = rtt
			off = st.Sub(t0.Add(rtt / 2))
		}
	}
	return off, cr
}

func moveClock(body string) {
	res, err := http.Post(apiBase+"/api/v1/dev/clock", "application/json", strings.NewReader(body))
	must(err)
	b, _ := io.ReadAll(res.Body)
	res.Body.Close()
	fmt.Println("clock move", body, "→", res.StatusCode, string(b))
	if res.StatusCode != 200 {
		panic("clock move failed")
	}
}

// waitGame — 게임 시각 at 까지 잔다
func waitGame(off time.Duration, at time.Time) {
	d := time.Until(at.Add(-off))
	if d > 0 {
		fmt.Printf("waiting %.1fs until game %s\n", d.Seconds(), at.In(kst).Format("15:04:05.000"))
		time.Sleep(d)
	}
}

func gameAt(date, hms string) time.Time {
	t, err := time.ParseInLocation("2006-01-02 15:04:05.000", date+" "+hms, kst)
	must(err)
	return t
}

// pool — users 를 c 개 일꾼으로 f 처리
func pool(us []*user, c int, f func(*user)) {
	ch := make(chan *user)
	var wg sync.WaitGroup
	for i := 0; i < c; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for u := range ch {
				f(u)
			}
		}()
	}
	for _, u := range us {
		ch <- u
	}
	close(ch)
	wg.Wait()
}

func logoutAll(us []*user) {
	pool(us, 100, func(u *user) { u.logout() })
	fmt.Println("logged out", len(us))
}
