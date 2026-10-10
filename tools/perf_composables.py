#!/usr/bin/env python3
"""Which composables a turn cost, from a tools/perf_trace.sh trace (3.0 perf).

Prints the main thread's frame count and its Recomposer / measure+layout / draw totals, then the
composables by self time (their own work, children excluded) with how often each ran. Compare two
builds per streaming tick (divide by the ChatScreen count): turns differ in length and in whether
the model reasoned, so raw totals don't compare. Needs `pip install perfetto`.

Usage: tools/perf_composables.py trace.pftrace [top=25]
"""
import sys
from perfetto.trace_processor import TraceProcessor
tp=TraceProcessor(trace=sys.argv[1]); top=int(sys.argv[2]) if len(sys.argv)>2 else 25
q=lambda s: list(tp.query(s))
m=q("select utid, upid from thread join process using(upid) where process.name='chat.keryx.app' and thread.tid=process.pid")[0]
u=m.utid
def one(sql): r=q(sql); return r[0] if r else None
fr=one(f"select count(*) n, sum(s.dur) d from slice s join thread_track tt on s.track_id=tt.id where tt.utid={u} and s.name like 'Choreographer#doFrame%' and s.depth=0")
rc=one(f"select count(*) n, sum(s.dur) d from slice s join thread_track tt on s.track_id=tt.id where tt.utid={u} and s.name='Recomposer:recompose'")
ml=one(f"select sum(s.dur) d from slice s join thread_track tt on s.track_id=tt.id where tt.utid={u} and s.name='AndroidOwner:measureAndLayout'")
dr=one(f"select sum(s.dur) d from slice s join thread_track tt on s.track_id=tt.id where tt.utid={u} and s.name='AndroidOwner:draw'")
print(f"frames {fr.n}  recompose {rc.d/1e6:.0f} ms ({rc.n}×)  measure+layout {(ml.d or 0)/1e6:.0f} ms  draw {(dr.d or 0)/1e6:.0f} ms")
rows=q(f"""select s.name, count(*) n, sum(s.dur) total,
  sum(s.dur - coalesce((select sum(c.dur) from slice c where c.parent_id=s.id),0)) self
  from slice s
  where s.name like '%.kt:%' group by s.name order by self desc limit {top}""")
tot=one("select count(*) n from slice where name like '%.kt:%'")
if not tot.n: sys.exit("no composable slices: install the benchmark build and send ENABLE_TRACING (perf_trace.sh does)")
print(f"composable slices: {tot.n}")
print(f"{'composable':70} {'n':>6} {'self ms':>8} {'total ms':>9}")
for r in rows: print(f"{r.name[:70]:70} {r.n:>6} {r.self/1e6:>8.0f} {r.total/1e6:>9.0f}")
