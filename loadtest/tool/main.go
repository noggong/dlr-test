package main

// 동시 접속 그룹 미션 서버 부하 도구 — 하루를 구간으로 나눠 잰다
//   lt day   ① 07:00 시작 스파이크 → ② 진행(풀이 + 재시도 + 팬아웃, 재시도 폭주) → ③ 08:00 마감 경계 + 결과 조회
//   lt bank  ④ 문제은행 캐시가 비었을 때의 07:00 시작 스파이크(①과 같은 호출)

import (
	"bytes"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

func main() {
	if len(os.Args) < 2 {
		fmt.Println("usage: lt day|bank [flags]")
		os.Exit(2)
	}
	cmd := os.Args[1]
	fs := flag.NewFlagSet(cmd, flag.ExitOnError)
	fs.StringVar(&apiBase, "api", apiBase, "api 주소")
	fs.StringVar(&rtBase, "rt", rtBase, "realtime 주소")
	fs.StringVar(&outDir, "out", outDir, "결과 폴더")
	usersFile := fs.String("users", "users.csv", "계정 목록(loginId,group)")
	levels := fs.String("levels", "50,200,500", "② 재시도 폭주 동시 일꾼 수")
	flood := fs.Duration("flood", 15*time.Second, "② 재시도 폭주 단계 길이")
	shamap := fs.String("shamap", "", "Lua sha → 스크립트 이름(csv)")
	fs.StringVar(&redisAddr, "redis", redisAddr, "redis 주소")
	fs.StringVar(&password, "password", password, "계정 비밀번호")
	fs.Parse(os.Args[2:])
	must(os.MkdirAll(outDir, 0o755))
	us := loadUsers(*usersFile)
	loadShaMap(*shamap)
	redisDayBegin()
	defer redisDayEnd()
	sampler = startStreamSampler(500 * time.Millisecond)

	switch cmd {
	case "day":
		var ls []int
		for _, s := range strings.Split(*levels, ",") {
			v, _ := strconv.Atoi(s)
			ls = append(ls, v)
		}
		day(us, ls, *flood)
	case "bank":
		off, cr := gameOffset()
		waitGame(off, gameAt(cr.GameDate, "06:59:59.500"))
		rp := redisBegin("p4", true)
		waitGame(off, gameAt(cr.GameDate, "07:00:00.000"))
		mark("p4_start")
		conns, live := startPhase(us, "p4")
		mark("p4_end")
		r4 := redisEnd(rp)
		time.Sleep(3 * time.Second)
		for _, c := range conns {
			if c != nil {
				c.close()
			}
		}
		logoutAll(live)
		ss := sampler.finish("p4_stream.csv")
		writeJSON("p4_redis.json", map[string]any{"redis": r4, "persister": persisterStats(ss, marks["p4_start"], time.Now())})
		writeJSON("phases_p4.json", phases)
	default:
		panic("unknown command " + cmd)
	}
}

// ── 구간 표시(실제 시각) — 호스트 샘플러 기록을 구간으로 자를 때 쓴다 ──

var (
	phases  = map[string]string{}
	marks   = map[string]time.Time{}
	sampler *streamSampler
)

func mark(k string) {
	marks[k] = time.Now()
	phases[k] = marks[k].UTC().Format(time.RFC3339Nano)
	fmt.Println("phase", k, phases[k])
}

// ── ① · ④ 시작 스파이크: 전원 같은 순간에 로그인 → 게임 정보 → 문제은행 → 티켓 → SSE ──

func startPhase(us []*user, tag string) ([]*sseConn, []*user) {
	conns := make([]*sseConn, len(us))
	var ready []sample
	var mu sync.Mutex
	var fail, already atomic.Int64
	var failMsg []string
	var wg sync.WaitGroup
	t0 := time.Now()
	for i, u := range us {
		wg.Add(1)
		go func(i int, u *user) {
			defer wg.Done()
			bad := func(err error) {
				mu.Lock()
				failMsg = append(failMsg, err.Error())
				mu.Unlock()
				fail.Add(1)
			}
			if err := u.login("login"); err != nil {
				if strings.Contains(err.Error(), "ALREADY_LOGGED_IN") {
					already.Add(1) // 다른 사람이 쓰는 중인 계정 — 시험에서 뺀다
					return
				}
				bad(err)
				return
			}
			if st, b, err := u.call("game_today", "GET", apiBase+"/api/v1/games/today", nil); err != nil || st != 200 {
				bad(fmt.Errorf("today %d %s %v", st, b, err))
				return
			}
			if err := u.fetchBank("question_bank"); err != nil {
				bad(err)
				return
			}
			c, err := u.openSSE()
			if err != nil {
				bad(err)
				return
			}
			conns[i] = c
			mu.Lock()
			ready = append(ready, sample{name: "ready_0700_to_snapshot", start: t0, lat: c.snapshotAt.Sub(t0), status: 200})
			mu.Unlock()
		}(i, u)
	}
	wg.Wait()
	wall := time.Since(t0)
	ss := rec.reset()
	m := byName(ss)
	var sts []stat
	for _, k := range []string{"login", "game_today", "question_bank", "ticket", "sse_connect"} {
		sts = append(sts, summarize(k, m[k], nil))
	}
	sts = append(sts, summarize("http_all", append(append(append(m["login"], m["game_today"]...), m["question_bank"]...), m["ticket"]...), nil))
	sts = append(sts, summarize("ready_0700_to_snapshot", ready, nil))
	var live []*user
	for i, u := range us {
		if conns[i] != nil {
			live = append(live, u)
		}
	}
	tried := len(us) - int(already.Load())
	printStats(fmt.Sprintf("%s start spike — users %d, already-logged-in skipped %d, failed %d, SSE ok %d/%d, wall %.2fs",
		tag, len(us), already.Load(), fail.Load(), len(live), tried, wall.Seconds()), sts)
	if len(failMsg) > 0 {
		fmt.Println("first failures:", failMsg[:min(5, len(failMsg))])
	}
	writeRaw(tag+"_raw.csv", ss, 0)
	writeJSON(tag+"_summary.json", map[string]any{"users": len(us), "alreadyLoggedIn": already.Load(), "failed": fail.Load(),
		"sseOK": len(live), "sseTried": tried, "wallSec": wall.Seconds(), "stats": sts, "failures": failMsg})
	return conns, live
}

// ── 문제 하나: 진입 신호 → 풀이 신호(+ 같은 본문 재시도) ──

type solveKey struct {
	u string
	p int
}

var (
	solveMu   sync.Mutex
	solveSent = map[solveKey]time.Time{}
	solveBody = map[solveKey]map[string]any{}
	idemBad   atomic.Int64
)

func (u *user) solveOne(no int, off time.Duration, retry bool) error {
	qid := u.pick(no)
	st, b, err := u.call("shown", "POST", apiBase+"/api/v1/games/today/shown", map[string]any{"questionNo": no, "questionId": qid})
	if err != nil || st != 200 {
		return fmt.Errorf("shown %d %s %v", st, b, err)
	}
	now := iso(time.Now().Add(off).In(kst))
	body := map[string]any{"questionNo": no, "questionId": qid, "successTime": now, "clickedAt": now}
	t0 := time.Now()
	st, b1, err := u.call("solve", "POST", apiBase+"/api/v1/games/today/solves", body)
	if err != nil || st != 200 {
		return fmt.Errorf("solve %d %s %v", st, b1, err)
	}
	solveMu.Lock()
	solveSent[solveKey{u.ID, no}] = t0
	solveBody[solveKey{u.ID, no}] = body
	solveMu.Unlock()
	if retry { // 같은 본문 재시도 — 같은 200, 같은 본문이어야 한다(멱등)
		st2, b2, err := u.call("solve_retry", "POST", apiBase+"/api/v1/games/today/solves", body)
		if err != nil || st2 != 200 || !bytes.Equal(b1, b2) {
			idemBad.Add(1)
			fmt.Println("idempotency mismatch", u.ID, no, st2, string(b1), string(b2))
		}
	}
	return nil
}

// ── 하루 한 바퀴 ──

func day(us []*user, levels []int, flood time.Duration) {
	off, cr := gameOffset()
	date := cr.GameDate
	redisOut := map[string]any{}
	waitGame(off, gameAt(date, "06:59:59.500"))
	rp := redisBegin("p1", true)
	waitGame(off, gameAt(date, "07:00:00.000"))

	// ① 시작
	mark("p1_start")
	conns, live := startPhase(us, "p1")
	mark("p1_end")
	redisOut["p1"] = redisEnd(rp)
	if len(live) == 0 {
		panic("no live users after p1")
	}
	group := map[string]string{}
	for _, u := range live {
		group[u.ID] = u.Group
	}
	time.Sleep(3 * time.Second)

	// ② 진행 a: 전원 동시에 1번, 2번 풀이(각각 같은 본문 재시도 1회). 3번은 ③ 에서
	mark("p2a_start")
	rp = redisBegin("p2a", true)
	var solveFail atomic.Int64
	var wg sync.WaitGroup
	t1 := time.Now()
	for _, u := range live {
		wg.Add(1)
		go func(u *user) {
			defer wg.Done()
			for no := 1; no <= 2; no++ {
				if err := u.solveOne(no, off, true); err != nil {
					solveFail.Add(1)
					fmt.Println(u.ID, err)
					return
				}
			}
		}(u)
	}
	wg.Wait()
	wall2 := time.Since(t1)
	mark("p2a_signals_end")
	time.Sleep(3 * time.Second) // 팬아웃 마저 받기
	mark("p2a_end")
	redisOut["p2a"] = redisEnd(rp)
	ss := rec.reset()
	m := byName(ss)
	p2 := []stat{summarize("shown", m["shown"], nil), summarize("solve", m["solve"], nil), summarize("solve_retry", m["solve_retry"], nil),
		summarize("signal_all", ss, nil)}
	printStats(fmt.Sprintf("p2a solves — users %d, wall %.2fs, signals/s %.0f, failures %d, idempotency mismatch %d",
		len(live), wall2.Seconds(), float64(len(ss))/wall2.Seconds(), solveFail.Load(), idemBad.Load()), p2)
	writeRaw("p2a_raw.csv", ss, off)
	fan := fanout(live, conns, group, 2)

	// ② 진행 b: 재시도 폭주 — 이미 반영된 2번 풀이를 같은 본문으로 계속. 반영은 0건이어야 한다
	arrMu.Lock()
	arrBefore := len(arrivals)
	arrMu.Unlock()
	var floodOut []map[string]any
	for _, c := range levels {
		mark(fmt.Sprintf("p2b_c%d_start", c))
		rpf := redisBegin(fmt.Sprintf("p2b_c%d", c), false)
		var next atomic.Int64
		var bad atomic.Int64
		stop := time.Now().Add(flood)
		t0 := time.Now()
		var wg sync.WaitGroup
		for w := 0; w < c; w++ {
			wg.Add(1)
			go func() {
				defer wg.Done()
				for time.Now().Before(stop) {
					u := live[int(next.Add(1))%len(live)]
					solveMu.Lock()
					body := solveBody[solveKey{u.ID, 2}]
					solveMu.Unlock()
					st, b, _ := u.call("solve_replay", "POST", apiBase+"/api/v1/games/today/solves", body)
					if st != 200 || !bytes.Contains(b, []byte(`"progress":2`)) {
						bad.Add(1)
					}
				}
			}()
		}
		wg.Wait()
		wall := time.Since(t0)
		mark(fmt.Sprintf("p2b_c%d_end", c))
		redisOut[fmt.Sprintf("p2b_c%d", c)] = redisEnd(rpf)
		ss := rec.reset()
		st := summarize("solve_replay", ss, nil)
		printStats(fmt.Sprintf("p2b replay flood c=%d — wall %.1fs, wrong body %d", c, wall.Seconds(), bad.Load()), []stat{st})
		writeRaw(fmt.Sprintf("p2b_c%04d_raw.csv", c), ss, off)
		floodOut = append(floodOut, map[string]any{"concurrency": c, "wallSec": wall.Seconds(), "rps": float64(len(ss)) / wall.Seconds(),
			"wrongBody": bad.Load(), "stat": st})
		time.Sleep(2 * time.Second)
	}
	arrMu.Lock()
	floodArrivals := len(arrivals) - arrBefore
	arrMu.Unlock()
	fmt.Println("SSE progress changes during replay flood (want 0):", floodArrivals)
	writeJSON("p2_summary.json", map[string]any{"users": len(live), "wallSec": wall2.Seconds(), "signalsPerSec": float64(len(ss)) / wall2.Seconds(),
		"solveFailures": solveFail.Load(), "idempotencyMismatch": idemBad.Load(), "stats": p2, "fanout": fan,
		"replayFlood": floodOut, "sseChangesDuringFlood": floodArrivals})

	// 호스트가 PG 반영 지연을 재는 동안 멈춘다(③ 준비 신호가 daily_progress.updated_at 을 덮기 전)
	redisOut["groupHash"] = groupHashSizes(date)
	hold("p2")

	// ③ 준비: 전원 3번 진입 신호(측정 밖)
	pool(live, 100, func(u *user) {
		qid := u.pick(3)
		st, b, _ := u.call("", "POST", apiBase+"/api/v1/games/today/shown", map[string]any{"questionNo": 3, "questionId": qid})
		if st != 200 {
			panic(fmt.Sprint("shown3 ", u.ID, st, string(b)))
		}
		u.bank = []question{{QuestionID: qid, Difficulty: 3}}
	})
	moveClock(`{"to":"deadline"}`) // 07:59:50
	off, _ = gameOffset()
	// 07:00 에 받은 access token(1시간)이 08:00 에 끝난다 — 실제 클라이언트처럼 미리 refresh(측정 밖)
	pool(live, 100, func(u *user) { u.call1("", "POST", apiBase+"/api/v1/auth/refresh", nil) })
	rp = redisBegin("p3", true)
	deadline(live, conns, off, date)
	redisOut["p3"] = redisEnd(rp)
	time.Sleep(3 * time.Second)
	st := sampler.finish("day_stream.csv")
	pers := map[string]any{
		"p1":                         persisterStats(st, marks["p1_start"], marks["p2a_start"]),
		"p2a":                        persisterStats(st, marks["p2a_start"], marks["p2a_end"].Add(30*time.Second)),
		"p2aDrainSecAfterSignalsEnd": drainTime(st, marks["p2a_signals_end"]),
		"p3":                         persisterStats(st, marks["p3_start"], time.Now()),
	}
	writeJSON("day_redis.json", map[string]any{"redis": redisOut, "persister": pers})

	for _, c := range conns {
		if c != nil {
			c.close()
		}
	}
	logoutAll(live)
	writeJSON("phases_day.json", phases)
}

// fanout — 풀이 요청을 보낸 시각 → 같은 그룹 다른 그룹원 SSE 에 그 진행도가 닿은 시각
func fanout(live []*user, conns []*sseConn, group map[string]string, upto int) map[string]any {
	arrMu.Lock()
	arr := append([]arrival(nil), arrivals...)
	arrMu.Unlock()
	var fan []sample
	seen := map[[3]string]int{}
	skipped := 0
	for _, a := range arr {
		if a.r == a.subject || a.p < 1 || a.p > upto {
			continue
		}
		seen[[3]string{a.r, a.subject, strconv.Itoa(a.p)}]++
		if a.skipped {
			skipped++
		}
		solveMu.Lock()
		s, ok := solveSent[solveKey{a.subject, a.p}]
		solveMu.Unlock()
		if ok {
			fan = append(fan, sample{name: "fanout_solve_to_peer", start: s, lat: a.at.Sub(s), status: 200})
		}
	}
	members := map[string][]string{}
	for _, u := range live {
		members[u.Group] = append(members[u.Group], u.ID)
	}
	expected, missing, dup := 0, 0, 0
	for _, ms := range members {
		for _, r := range ms {
			for _, s := range ms {
				if r == s {
					continue
				}
				for p := 1; p <= upto; p++ {
					expected++
					switch c := seen[[3]string{r, s, strconv.Itoa(p)}]; {
					case c == 0:
						missing++
					case c > 1:
						dup++
					}
				}
			}
		}
	}
	verBack := 0
	for _, c := range conns {
		if c != nil {
			verBack += c.verBack
		}
	}
	st := summarize("fanout_solve_to_peer", fan, nil)
	printStats(fmt.Sprintf("p2a fanout — expected %d, missing %d, duplicate %d, coalesced %d, version back %d", expected, missing, dup, skipped, verBack), []stat{st})
	writeRaw("p2a_fanout_raw.csv", fan, 0)
	return map[string]any{"stat": st, "expected": expected, "missing": missing, "duplicate": dup, "coalesced": skipped, "versionBack": verBack}
}

// ── ③ 08:00 마감 경계 ──

type dlRes struct {
	id                string
	send, recv, click time.Time // 게임 시각
	late              bool
	status            int
	code              string
	progress          int
}

func deadline(us []*user, conns []*sseConn, off time.Duration, date string) {
	// 각자 07:59:58 ~ 08:00:05 사이 무작위 순간에 3번 풀이. 4명 중 1명은 「08:00 전에 눌렀는데 늦게 도착」(clickedAt = 보낸 시각 − 1.5초)
	mark("p3_start")
	from := gameAt(date, "07:59:58.000")
	span := 7 * time.Second
	res := make([]dlRes, len(us))
	var wg sync.WaitGroup
	for i, u := range us {
		wg.Add(1)
		go func(i int, u *user) {
			defer wg.Done()
			at := from.Add(time.Duration(u.rnd.Int63n(int64(span))))
			late := u.rnd.Intn(4) == 0
			time.Sleep(time.Until(at.Add(-off)))
			send := time.Now().Add(off)
			click := send
			if late {
				click = send.Add(-1500 * time.Millisecond)
			}
			body := map[string]any{"questionNo": 3, "questionId": u.bank[0].QuestionID, "successTime": iso(click.In(kst)), "clickedAt": iso(click.In(kst))}
			st, b, _ := u.call("solve_at_deadline", "POST", apiBase+"/api/v1/games/today/solves", body)
			r := dlRes{id: u.ID, send: send, recv: time.Now().Add(off), click: click, late: late, status: st}
			var x struct {
				Code     string `json:"code"`
				Progress int    `json:"progress"`
			}
			json.Unmarshal(b, &x)
			r.code, r.progress = x.Code, x.Progress
			res[i] = r
		}(i, u)
	}
	wg.Wait()
	ss := rec.reset()

	d0 := gameAt(date, "08:00:00.000")
	d3 := gameAt(date, "08:00:03.000")
	type cnt struct{ Total, Expected, Violation int }
	cls := map[string]*cnt{}
	add := func(k string, good bool) {
		if cls[k] == nil {
			cls[k] = &cnt{}
		}
		cls[k].Total++
		if good {
			cls[k].Expected++
		} else {
			cls[k].Violation++
		}
	}
	accepted := map[string]bool{}
	f, err := os.Create(outDir + "/p3_deadline_raw.csv")
	must(err)
	fmt.Fprintln(f, "user,send_game,recv_game,clicked_at,late_click,status,code,progress,class")
	for _, r := range res {
		acc := r.status == 200 && r.progress == 3
		accepted[r.id] = acc
		var k string
		switch {
		case r.recv.Before(d0):
			k = "A recv<08:00 accept"
			add(k, acc)
		case !r.send.Before(d3):
			k = "D send>=08:00:03 reject"
			add(k, r.status == 409 && r.code == "SUBMISSION_CLOSED")
		case !r.send.Before(d0) && r.recv.Before(d3) && !r.click.Before(d0):
			k = "C grace,click>=08:00 reject"
			add(k, r.status == 409 && r.code == "SUBMISSION_CLOSED")
		case !r.send.Before(d0) && r.recv.Before(d3) && r.click.Before(d0):
			k = "B grace,click<08:00 accept"
			add(k, acc)
		default:
			k = "E straddles boundary (unjudged)"
			add(k, true)
		}
		fmt.Fprintf(f, "%s,%s,%s,%s,%v,%d,%s,%d,%s\n", r.id, r.send.In(kst).Format("15:04:05.000000"), r.recv.In(kst).Format("15:04:05.000000"),
			r.click.In(kst).Format("15:04:05.000000"), r.late, r.status, r.code, r.progress, k)
	}
	f.Close()
	mark("p3_signals_end")

	// 결과 조회 폭주: 08:00:06 부터 전원이 10초 동안 결과 조회를 반복. 첫 응답으로 정합성 확인
	waitGame(off, gameAt(date, "08:00:06.000"))
	time.Sleep(2 * time.Second)
	grpAll := map[string]bool{}
	for _, u := range us {
		if _, ok := grpAll[u.Group]; !ok {
			grpAll[u.Group] = true
		}
		grpAll[u.Group] = grpAll[u.Group] && accepted[u.ID]
	}
	var resultBad, pending, notFinal atomic.Int64
	decided := map[string]string{}
	var dmu sync.Mutex
	check := func(u *user, b []byte) {
		var r struct {
			Date         string  `json:"date"`
			GroupStatus  string  `json:"groupStatus"`
			DecidedAt    string  `json:"decidedAt"`
			RankingFinal bool    `json:"rankingFinal"`
			Ranking      []entry `json:"ranking"`
		}
		json.Unmarshal(b, &r)
		if r.GroupStatus == "PENDING" {
			pending.Add(1)
			return
		}
		if !r.RankingFinal {
			notFinal.Add(1)
		}
		dmu.Lock()
		decided[u.Group] = r.DecidedAt
		dmu.Unlock()
		my := -1
		for _, e := range r.Ranking {
			if e.UserID == u.ID {
				my = e.Progress
			}
		}
		want := "FAILED"
		if grpAll[u.Group] {
			want = "SUCCEEDED"
		}
		if (my == 3) != accepted[u.ID] || r.GroupStatus != want || r.Date != date {
			resultBad.Add(1)
			fmt.Println("result mismatch", u.ID, my, accepted[u.ID], r.GroupStatus, want, r.Date)
		}
	}
	mark("p3_results_start")
	stop := time.Now().Add(10 * time.Second)
	var rw sync.WaitGroup
	for _, u := range us {
		rw.Add(1)
		go func(u *user) {
			defer rw.Done()
			first := true
			for time.Now().Before(stop) {
				st, b, _ := u.call("result_latest", "GET", apiBase+"/api/v1/games/results/latest", nil)
				if first && st == 200 {
					check(u, b)
					first = false
				}
			}
		}(u)
	}
	rw.Wait()
	mark("p3_results_end")
	rs := rec.reset()

	// SSE 로 받은 결과 메시지 시각
	var resAt, finAt []time.Duration
	for _, c := range conns {
		if c == nil {
			continue
		}
		if !c.resultAt.IsZero() {
			resAt = append(resAt, c.resultAt.Add(off).Sub(d0))
		}
		if !c.finalAt.IsZero() {
			finAt = append(finAt, c.finalAt.Add(off).Sub(d0))
		}
	}
	durStat := func(d []time.Duration) map[string]any {
		if len(d) == 0 {
			return map[string]any{"count": 0}
		}
		sort.Slice(d, func(i, j int) bool { return d[i] < d[j] })
		return map[string]any{"count": len(d), "minMsAfter0800": ms(d[0]), "p50": pct(d, .5), "p99": pct(d, .99), "maxMsAfter0800": ms(d[len(d)-1])}
	}

	var keys []string
	for k := range cls {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	fmt.Println("\n== p3 deadline boundary")
	for _, k := range keys {
		fmt.Printf("%-34s total %4d  expected %4d  violation %d\n", k, cls[k].Total, cls[k].Expected, cls[k].Violation)
	}
	groupsOK := 0
	for _, v := range grpAll {
		if v {
			groupsOK++
		}
	}
	sts := []stat{summarize("solve_at_deadline", ss, func(s sample) bool { return s.status == 200 || s.code == "SUBMISSION_CLOSED" }),
		summarize("result_latest", rs, nil)}
	printStats(fmt.Sprintf("p3 — result mismatch %d, pending %d, rankingFinal=false %d, groups all-accepted %d / %d",
		resultBad.Load(), pending.Load(), notFinal.Load(), groupsOK, len(grpAll)), sts)
	fmt.Println("SSE group.result after 08:00:", durStat(resAt), " ranking_final:", durStat(finAt))
	writeRaw("p3_results_raw.csv", rs, off)
	writeJSON("p3_summary.json", map[string]any{"date": date, "users": len(us), "classes": cls, "resultMismatch": resultBad.Load(),
		"resultPending": pending.Load(), "rankingNotFinal": notFinal.Load(), "groupsAllAccepted": groupsOK, "groups": len(grpAll),
		"stats": sts, "sseGroupResult": durStat(resAt), "sseRankingFinal": durStat(finAt), "decidedAt": decided})
}
