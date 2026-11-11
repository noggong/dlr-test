"""결과 폴더의 샘플러 원본(docker_stats.csv, pg_stats.csv)을 구간(phases_*.json)으로 잘라 요약한다.
사용: python3 -I analyze.py results/<run> > results/<run>/phase_resources.txt"""
import csv, json, sys, glob, re, datetime as dt
d = sys.argv[1]
ph = {}
for f in glob.glob(d + "/phases_*.json"):
    ph.update(json.load(open(f)))
def t(s):  # RFC3339 UTC → HH:MM:SS 초
    x = dt.datetime.fromisoformat(s.replace("Z", "+00:00")[:26] + "+00:00" if "." in s else s.replace("Z", "+00:00"))
    return x.hour * 3600 + x.minute * 60 + x.second + x.microsecond / 1e6
def hms(s):
    h, m, r = s.split(":"); return int(h) * 3600 + int(m) * 60 + float(r)
wins = []
for k in sorted(ph):
    if k.endswith("_start"):
        b = k[:-6]
        e = ph.get(b + "_end") or ph.get(b + "_results_end")
        if e: wins.append((b, t(ph[k]), t(e)))
def mib(s):
    v = s.split("/")[0].strip(); n = float(re.sub(r"[A-Za-z]", "", v))
    return n * 1024 if "GiB" in v else n / 1024 if "KiB" in v else n
rows = [r for r in csv.DictReader(open(d + "/docker_stats.csv")) if r.get("cpu_pct")]
pg = [r for r in csv.DictReader(open(d + "/pg_stats.csv")) if r.get("xact_commit") and r["xact_commit"].isdigit()]
for b, s, e in wins:
    print(f"## {b}  {e - s:.1f}s")
    by = {}
    for r in rows:
        x = hms(r["time_utc"])
        if s - 1 <= x <= e + 1:
            n = r["name"].replace("group-mission-", "").replace("-1", "")
            by.setdefault(n, []).append((float(r["cpu_pct"].rstrip("%")), mib(r["mem"])))
    for n, v in sorted(by.items()):
        c = [a for a, _ in v]
        print(f"  {n:10s} samples {len(v):3d}  cpu max {max(c):7.1f}%  mean {sum(c)/len(c):7.1f}%  mem max {max(m for _, m in v):7.0f} MiB")
    w = [r for r in pg if s - 0.5 <= hms(r["time_utc"]) <= e + 0.5]
    if len(w) >= 2:
        a, z = w[0], w[-1]
        print(f"  pg  xact +{int(z['xact_commit'])-int(a['xact_commit'])}  tup_returned +{int(z['tup_returned'])-int(a['tup_returned'])}"
              f"  tup_fetched +{int(z['tup_fetched'])-int(a['tup_fetched'])}  served_questions scans +{int(z['sq_scans'])-int(a['sq_scans'])}"
              f"  api conn active max {max(int(r['api_active']) for r in w)}  api conn total max {max(int(r['api_total']) for r in w)}  samples {len(w)}")
