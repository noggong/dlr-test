package main

// SSE 수신 — 그룹 현황판을 이벤트마다 비교해 「누가 몇 번까지 풀었는지」가 이 연결에 언제 닿았는지 남긴다

import (
	"bufio"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"sync"
	"time"
)

type entry struct {
	UserID   string `json:"userId"`
	Progress int    `json:"progress"`
	Status   string `json:"status"`
}

type frame struct {
	Version     int     `json:"version"`
	GroupStatus string  `json:"groupStatus"`
	Entries     []entry `json:"entries"`
}

// arrival — 받는 사람 r 에게 subject 의 진행도 p 가 닿은 시각
type arrival struct {
	r, subject string
	p          int
	at         time.Time
	skipped    bool // 한 이벤트에 진행도가 2 이상 뛰어 중간 값을 건너뜀
}

type sseConn struct {
	u          *user
	res        *http.Response
	board      map[string]int
	lastVer    int
	snapshotAt time.Time
	ready      chan struct{}
	final      string    // 마지막 groupStatus
	verBack    int       // 같은 연결에서 version 이 줄어든 횟수
	resultAt   time.Time // group.result 를 받은 시각
	finalAt    time.Time // group.ranking_final 을 받은 시각
	frames     int
}

var (
	arrMu    sync.Mutex
	arrivals []arrival
)

var sseClient = &http.Client{Transport: transport} // 타임아웃 없음 — 연결을 붙들고 있는다

// openSSE — 티켓 → 연결 → board.snapshot 까지. 걸린 시간은 sse_connect 로 기록
func (u *user) openSSE() (*sseConn, error) {
	st, b, err := u.call("ticket", "POST", apiBase+"/api/v1/realtime/tickets", nil)
	if err != nil {
		return nil, err
	}
	if st != 200 && st != 201 {
		return nil, fmt.Errorf("ticket %d %s", st, b)
	}
	var t struct {
		Ticket string `json:"ticket"`
	}
	must(json.Unmarshal(b, &t))
	t0 := time.Now()
	req, _ := http.NewRequest("GET", rtBase+"/realtime/v1/games/today/events?ticket="+t.Ticket, nil)
	req.Header.Set("Accept", "text/event-stream")
	res, err := sseClient.Do(req)
	if err != nil {
		rec.add(sample{name: "sse_connect", start: t0, lat: time.Since(t0), code: "NET"})
		return nil, err
	}
	if res.StatusCode != 200 {
		res.Body.Close()
		rec.add(sample{name: "sse_connect", start: t0, lat: time.Since(t0), status: res.StatusCode})
		return nil, fmt.Errorf("sse %d", res.StatusCode)
	}
	c := &sseConn{u: u, res: res, board: map[string]int{}, ready: make(chan struct{})}
	go c.read(t0)
	select {
	case <-c.ready:
		rec.add(sample{name: "sse_connect", start: t0, lat: c.snapshotAt.Sub(t0), status: 200})
		return c, nil
	case <-time.After(30 * time.Second):
		res.Body.Close()
		rec.add(sample{name: "sse_connect", start: t0, lat: time.Since(t0), code: "NO_SNAPSHOT"})
		return nil, fmt.Errorf("no snapshot")
	}
}

func (c *sseConn) read(t0 time.Time) {
	sc := bufio.NewScanner(c.res.Body)
	sc.Buffer(make([]byte, 64*1024), 1024*1024)
	var ev, data string
	for sc.Scan() {
		line := sc.Text()
		switch {
		case strings.HasPrefix(line, "event:"):
			ev = strings.TrimSpace(line[6:])
		case strings.HasPrefix(line, "data:"):
			data = strings.TrimSpace(line[5:])
		case line == "":
			if ev != "" && data != "" {
				c.handle(ev, data, time.Now())
			}
			ev, data = "", ""
		}
	}
}

func (c *sseConn) handle(ev, data string, at time.Time) {
	var f frame
	if json.Unmarshal([]byte(data), &f) != nil {
		return
	}
	c.frames++
	if ev == "board.snapshot" {
		for _, e := range f.Entries {
			c.board[e.UserID] = e.Progress
		}
		c.lastVer = f.Version
		c.final = f.GroupStatus
		c.snapshotAt = at
		close(c.ready)
		return
	}
	if ev == "group.result" && c.resultAt.IsZero() {
		c.resultAt = at
	}
	if ev == "group.ranking_final" && c.finalAt.IsZero() {
		c.finalAt = at
	}
	if f.Version <= c.lastVer {
		c.verBack++
		return
	}
	c.lastVer = f.Version
	c.final = f.GroupStatus
	var got []arrival
	for _, e := range f.Entries {
		old := c.board[e.UserID]
		for p := old + 1; p <= e.Progress; p++ {
			got = append(got, arrival{r: c.u.ID, subject: e.UserID, p: p, at: at, skipped: p < e.Progress})
		}
		c.board[e.UserID] = e.Progress
	}
	if len(got) > 0 {
		arrMu.Lock()
		arrivals = append(arrivals, got...)
		arrMu.Unlock()
	}
}

func (c *sseConn) close() { c.res.Body.Close() }
