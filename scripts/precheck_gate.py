#!/usr/bin/env python3
"""
Release gate for the property pre-check (de.uzl.its.cfg.staticcheck.ExceptionFreedomCheck): run it
over every SV-COMP Java task and compare each proof with the task's expected verdict.

    scripts/precheck_gate.py --jar build/libs/cfg-extractor-precheck.jar

A proof is the check's claim that a property holds on every execution: exception_free for
no-runtime-exception, assertion_free for valid-assert. SWAT answers SAFE on it without exploring,
so a proof for a task whose expected verdict is false is a false SAFE (-32 in SV-COMP). The gate
fails (exit 1) on any such contradiction. Crashes and timeouts prove nothing and are only counted.

Run it before every release of the pre-check, i.e. before SWAT's cfg-extraction.ref moves.
"""

from __future__ import annotations

import argparse
import concurrent.futures as cf
import json
import os
import subprocess
import sys
from collections import Counter
from pathlib import Path

import yaml

CHECK_CLASS = 'de.uzl.its.cfg.staticcheck.ExceptionFreedomCheck'
DEFAULT_BENCH = Path(__file__).resolve().parents[2] / 'SWAT' / 'targets' / 'sv-comp' / 'sv-benchmarks' / 'java'
# The property file name -> the field of the check's result that proves it (as SWAT reads it).
PROVES = {
    'no-runtime-exception.prp': 'exception_free',
    'valid-assert.prp': 'assertion_free',
}


def run_check(jar: str, task: Path, timeout: int) -> dict:
    spec = yaml.safe_load(task.read_text())
    classpath = [str((task.parent / f).resolve()) for f in spec['input_files']]
    try:
        proc = subprocess.run(['java', '-Xmx2g', '-cp', jar, CHECK_CLASS, *classpath],
                              capture_output=True, text=True, timeout=timeout)
        result = json.loads(proc.stdout.strip().splitlines()[-1])
    except subprocess.TimeoutExpired:
        result = {'error': 'timeout'}
    except Exception as e:  # a crash or garbage output proves nothing
        result = {'error': type(e).__name__}
    expected = {os.path.basename(p['property_file']): p['expected_verdict'] for p in spec['properties']}
    return {'task': str(task), 'expected': expected, 'result': result}


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--jar', required=True, help='cfg-extractor-precheck.jar (or the fat jar)')
    ap.add_argument('--bench', type=Path, default=DEFAULT_BENCH, help='the java/ directory of sv-benchmarks')
    ap.add_argument('--workers', type=int, default=os.cpu_count())
    ap.add_argument('--timeout', type=int, default=60, help='per task, as in SWAT (PropertyPreCheck)')
    ap.add_argument('--out', type=Path, help='write every task result here (JSON lines)')
    args = ap.parse_args()

    tasks = sorted(args.bench.glob('*/*.yml'))
    if not tasks:
        print(f'no tasks under {args.bench}', file=sys.stderr)
        return 2
    with cf.ThreadPoolExecutor(args.workers) as ex:
        rows = list(ex.map(lambda t: run_check(args.jar, t, args.timeout), tasks))
    if args.out:
        args.out.write_text(''.join(json.dumps(r) + '\n' for r in rows))

    proofs, checked, contradictions = Counter(), Counter(), []
    errors = Counter(r['result']['error'] for r in rows if 'error' in r['result'])
    for r in rows:
        for prp, field in PROVES.items():
            if prp not in r['expected']:
                continue
            checked[prp] += 1
            if r['result'].get(field) is True:
                proofs[prp] += 1
                if r['expected'][prp] is not True:
                    contradictions.append((r['task'], prp))
    version = next((r['result']['version'] for r in rows if 'version' in r['result']), 'unknown')
    print(f'pre-check {version} on {len(tasks)} tasks under {args.bench}')
    for prp in PROVES:
        print(f'  {prp}: {proofs[prp]} proven of {checked[prp]} tasks')
    print(f'  crashes/timeouts (prove nothing): {dict(errors) or 0}')
    for task, prp in contradictions:
        print(f'  CONTRADICTION: {prp} proven, but expected false: {task}')
    print('gate ' + ('FAILED' if contradictions else 'passed') + f': {len(contradictions)} contradictions')
    return 1 if contradictions else 0


if __name__ == '__main__':
    sys.exit(main())
