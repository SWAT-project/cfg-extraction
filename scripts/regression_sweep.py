#!/usr/bin/env python3
"""
Regression sweep: run one extractor jar over the SV-COMP Java benchmarks and record, per
benchmark, what the resulting inter-procedural graph looks like and whether SWAT can load it.

    scripts/regression_sweep.py --jar build/libs/cfg-extractor-1.0-SNAPSHOT-all.jar \
        --consumer /home/niklas/SWAT/SWAT/symbolic-explorer --out results.jsonl

Compiled classes are cached under --work, so a second sweep with a different jar skips javac.
Compare two result files with --compare base.jsonl new.jsonl.
"""

from __future__ import annotations

import argparse
import concurrent.futures as cf
import glob
import json
import os
import re
import subprocess
import sys
import time
from collections import Counter, defaultdict
from pathlib import Path

import yaml

BENCH_ROOT = Path('/home/niklas/SWAT/SWAT/targets/sv-comp/sv-benchmarks/java')
CATEGORIES = ['securibench', 'jbmc-regression', 'jpf-regression', 'algorithms', 'objects',
              'java-ranger-regression', 'jayhorn-recursive', 'jdart-regression', 'juliet-java',
              'MinePump', 'argv-tasks']

FALLTHROUGH = {'FALSE_BRANCH', 'PHANTOM_FALSE_BRANCH', 'NORMAL', 'CALL', 'THROW'}
BRANCHED = {'TRUE_BRANCH', 'PHANTOM_TRUE_BRANCH'}

# Runs in a subprocess with the consumer on sys.path, so that each sweep checks the SAGraph it was
# given rather than whatever this interpreter imported first.
LOAD_CHECK = r'''
import sys, json, time
sys.path.insert(0, sys.argv[1])
sys.setrecursionlimit(100000)
from data.StaticAnalysisGraph.SAGraph import SAGraph
t0 = time.time()
g = SAGraph(); g.load_json_graph(sys.argv[2])
print(json.dumps({"load_ok": True, "load_s": round(time.time() - t0, 3)}))
'''


def benchmarks():
    for cat in CATEGORIES:
        for yml in sorted((BENCH_ROOT / cat).glob('*.yml')):
            yield cat, yml


def java_files(yml: Path) -> list[str]:
    spec = yaml.safe_load(yml.read_text())
    files = []
    for entry in spec.get('input_files', []):
        path = (yml.parent / entry).resolve()
        if path.is_dir():
            files += glob.glob(str(path / '**' / '*.java'), recursive=True)
        elif path.suffix == '.java':
            files.append(str(path))
    return sorted(files)


def compile_benchmark(cat: str, yml: Path, work: Path) -> Path | str:
    classes = work / 'classes' / cat / yml.stem
    stamp = classes / '.ok'
    if stamp.exists():
        return classes
    classes.mkdir(parents=True, exist_ok=True)
    files = java_files(yml)
    res = subprocess.run(['javac', '-nowarn', '-g', '-d', str(classes)] + files,
                         capture_output=True, text=True)
    if res.returncode != 0:
        return 'javac failed: ' + res.stderr[-300:]
    stamp.touch()
    return classes


def analyse_graph(path: Path) -> dict:
    g = json.loads(path.read_text())
    nodes = {n['id']: n for n in g['nodes']}
    fall = Counter()
    branched = Counter()
    ret = Counter()
    etypes = Counter()
    for e in g['edges']:
        t = e['type']
        etypes[t] += 1
        if t in FALLTHROUGH:
            fall[e['source']] += 1
        elif t in BRANCHED:
            branched[e['source']] += 1
        elif t == 'RETURN':
            ret[e['source']] += 1
    meta = g.get('metadata', {})
    apids = meta.get('assertionPointIds', [])
    incomplete = [n for n in nodes.values() if n.get('isIncomplete')]
    real = [a for a in apids if not nodes.get(a, {}).get('isIncomplete')]
    stmt_kinds = Counter()
    for n in incomplete:
        s = n.get('stmtType', '')
        if 'DUMMY' in s:
            stmt_kinds['depth-dummy'] += 1
        elif 'invoke' in n.get('statement', ''):
            stmt_kinds['call-site'] += 1
        elif n.get('stmtType') == 'JThrowStmt':
            stmt_kinds['throw'] += 1
        else:
            stmt_kinds[s] += 1
    return {
        'nodes': len(nodes),
        'edges': len(g['edges']),
        'methods': meta.get('methodCount'),
        'assertion_points': len(apids),
        'real_asserts': len(real),
        'real_assert_stmts': sorted({nodes[a]['metadata'] + '|' + nodes[a]['statement'] + '|' + str(nodes[a]['lineNumber'])
                                     for a in real if a in nodes}),
        'incomplete_markers': len(apids) - len(real),
        'incomplete_nodes': len(incomplete),
        'incomplete_kinds': dict(stmt_kinds),
        'isIncomplete': meta.get('isIncomplete'),
        'multi_fallthrough': sum(1 for c in fall.values() if c > 1),
        'multi_branched': sum(1 for c in branched.values() if c > 1),
        # Only meaningful for graphs in which RETURN still counts as a fall-through edge.
        'multi_fallthrough_with_return': sum(1 for s in set(fall) | set(ret) if fall[s] + ret[s] > 1),
        'edge_types': dict(etypes),
        'uncaught_nodes': sum(1 for n in nodes.values() if n.get('stmtType') == 'UncaughtException'),
        'exits_that_throw': sum(1 for x in g.get('exitNodeIds', []) if nodes[x].get('stmtType') == 'JThrowStmt'),
    }


def run_one(cat: str, yml: Path, jar: str, consumer: str, work: Path, label: str) -> dict:
    rec = {'category': cat, 'benchmark': yml.stem}
    classes = compile_benchmark(cat, yml, work)
    if isinstance(classes, str):
        rec['status'] = 'compile-failed'
        rec['error'] = classes
        return rec
    if not (classes / 'Main.class').exists():
        rec['status'] = 'no-main'
        return rec
    out = work / 'out' / label / cat / yml.stem
    out.mkdir(parents=True, exist_ok=True)
    t0 = time.time()
    try:
        res = subprocess.run(['java', '-ea', '-Xss64m', '-jar', jar, str(classes), str(out), 'Main', 'main', 'inter'],
                             capture_output=True, text=True, timeout=600)
    except subprocess.TimeoutExpired:
        rec['status'] = 'timeout'
        return rec
    rec['extract_s'] = round(time.time() - t0, 2)
    log = res.stdout + res.stderr
    (out / 'extractor.log').write_text(log)
    rec['log_repeat_calls'] = log.count('Call to already expanded method')
    rec['log_unresolved'] = log.count('Unresolved dispatch at')
    graph = out / 'Main_main_interprocedural.json'
    if res.returncode != 0 or not graph.exists():
        rec['status'] = 'extract-failed'
        m = re.findall(r'(AssertionError.*|Exception.*)', log)
        rec['error'] = (m[0] if m else log[-300:])[:300]
        return rec
    rec['status'] = 'ok'
    rec.update(analyse_graph(graph))
    chk = subprocess.run([sys.executable, '-c', LOAD_CHECK, consumer, str(graph)],
                         capture_output=True, text=True, timeout=600)
    if chk.returncode == 0:
        rec.update(json.loads(chk.stdout.strip().splitlines()[-1]))
    else:
        rec['load_ok'] = False
        rec['load_error'] = chk.stderr.strip().splitlines()[-1][:300] if chk.stderr.strip() else '?'
    return rec


def sweep(args):
    work = Path(args.work)
    items = list(benchmarks())
    if args.filter:
        items = [(c, y) for c, y in items if re.search(args.filter, f'{c}/{y.stem}')]
    results = []
    with cf.ThreadPoolExecutor(args.jobs) as pool:
        futs = {pool.submit(run_one, c, y, os.path.abspath(args.jar), os.path.abspath(args.consumer),
                            work, args.label): (c, y) for c, y in items}
        for i, f in enumerate(cf.as_completed(futs), 1):
            c, y = futs[f]
            try:
                r = f.result()
            except Exception as e:  # keep sweeping, but record the failure
                r = {'category': c, 'benchmark': y.stem, 'status': 'sweep-error', 'error': repr(e)}
            results.append(r)
            if i % 25 == 0 or i == len(futs):
                print(f'[{i}/{len(futs)}]', flush=True)
    results.sort(key=lambda r: (r['category'], r['benchmark']))
    with open(args.out, 'w') as f:
        for r in results:
            f.write(json.dumps(r) + '\n')
    summarize(results)


def summarize(results):
    ok = [r for r in results if r['status'] == 'ok']
    print(f'benchmarks: {len(results)}  status: {dict(Counter(r["status"] for r in results))}')
    print(f'load ok: {sum(1 for r in ok if r.get("load_ok"))}/{len(ok)}')
    for key in ['nodes', 'edges', 'assertion_points', 'real_asserts', 'incomplete_markers',
                'incomplete_nodes', 'multi_fallthrough', 'multi_branched', 'log_repeat_calls',
                'log_unresolved', 'uncaught_nodes', 'exits_that_throw']:
        print(f'  sum {key}: {sum(r.get(key) or 0 for r in ok)}')
    print(f'  incomplete graphs: {sum(1 for r in ok if r.get("isIncomplete"))}')
    kinds = Counter()
    for r in ok:
        kinds.update(r.get('incomplete_kinds', {}))
    print(f'  incomplete node kinds: {dict(kinds)}')
    times = sorted(r.get('extract_s', 0) for r in ok)
    if times:
        print(f'  extract time: total {sum(times):.0f}s, median {times[len(times)//2]}s, max {times[-1]}s')
    loads = sorted(r.get('load_s', 0) for r in ok if r.get('load_ok'))
    if loads:
        print(f'  load time: median {loads[len(loads)//2]}s, max {loads[-1]}s')
    big = sorted(ok, key=lambda r: -r['nodes'])[:5]
    print('  largest:', [(r['category'] + '/' + r['benchmark'], r['nodes'], r.get('extract_s')) for r in big])


def load(path):
    return {(r['category'], r['benchmark']): r for r in map(json.loads, open(path))}


def compare(base_path, new_path):
    base, new = load(base_path), load(new_path)
    print('=== base'); summarize(list(base.values()))
    print('=== new'); summarize(list(new.values()))
    print('=== differences')
    for key in sorted(base.keys() | new.keys()):
        b, n = base.get(key, {}), new.get(key, {})
        name = '/'.join(key)
        if b.get('status') != n.get('status'):
            print(f'STATUS {name}: {b.get("status")} -> {n.get("status")} {n.get("error", "")}')
        if b.get('status') == 'ok' and n.get('status') == 'ok':
            lost = set(b['real_assert_stmts']) - set(n['real_assert_stmts'])
            if lost:
                print(f'LOST ASSERT {name}: {sorted(lost)}')
            if b.get('load_ok') and not n.get('load_ok'):
                print(f'LOAD REGRESSION {name}: {n.get("load_error")}')
    per_cat = defaultdict(lambda: Counter())
    for key, n in new.items():
        b = base.get(key, {})
        if n.get('status') == 'ok' and b.get('status') == 'ok':
            c = per_cat[key[0]]
            c['n'] += 1
            c['inc_graphs_base'] += bool(b.get('isIncomplete'))
            c['inc_graphs_new'] += bool(n.get('isIncomplete'))
            c['inc_nodes_base'] += b.get('incomplete_nodes', 0)
            c['inc_nodes_new'] += n.get('incomplete_nodes', 0)
            c['nodes_base'] += b['nodes']
            c['nodes_new'] += n['nodes']
            c['t_base'] += b.get('extract_s', 0)
            c['t_new'] += n.get('extract_s', 0)
    print(f'{"category":24} {"n":>4} {"incG b->n":>11} {"incN b->n":>13} {"nodes b->n":>17} {"time b->n":>13}')
    for cat, c in sorted(per_cat.items()):
        print(f'{cat:24} {c["n"]:4} {c["inc_graphs_base"]:5}->{c["inc_graphs_new"]:<5} '
              f'{c["inc_nodes_base"]:6}->{c["inc_nodes_new"]:<6} {c["nodes_base"]:8}->{c["nodes_new"]:<8} '
              f'{c["t_base"]:6.0f}->{c["t_new"]:<6.0f}')


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--jar')
    p.add_argument('--consumer', help='symbolic-explorer directory whose SAGraph loads the graphs')
    p.add_argument('--out')
    p.add_argument('--label', default='run')
    p.add_argument('--work', default=os.path.join(os.environ.get('CLAUDE_JOB_DIR', '/tmp'), 'tmp', 'sweep'))
    p.add_argument('--jobs', type=int, default=16)
    p.add_argument('--filter', help='regex over category/benchmark')
    p.add_argument('--compare', nargs=2, metavar=('BASE', 'NEW'))
    a = p.parse_args()
    if a.compare:
        compare(*a.compare)
    else:
        sweep(a)
