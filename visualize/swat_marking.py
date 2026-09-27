"""
SWAT's "is this path interesting?" marking, per node, for drawing.

SWAT prunes a branch side when the static-analysis graph says no assert is reachable from it.
That decision is context-sensitive: `is_interesting(node, stack)` in SWAT's SAGraph.py depends on
the call stack the walk carries. A drawing has no stack, so each node is summarised as one of

  interesting  an assert is reachable without leaving the node's frame
               (`reachesAssertSameLevel`), so the node is interesting under every stack;
  context      not so, but some calling context makes it interesting: returning normally, or
               unwinding by an exception, into a caller whose continuation reaches an assert;
  pruned       interesting under no stack at all -- SWAT prunes this side wherever it meets it.

The summaries themselves come from SWAT's own SAGraph, loaded from the symbolic-explorer
directory, so the drawing cannot drift from what SWAT computes. Only the "some context" step is
computed here. It asks for *any* chain of callers, including ones unreachable from main, so it
over-approximates slightly: a node drawn `context` might in fact never be interesting.
"""

from __future__ import annotations

import sys
from pathlib import Path
from typing import Dict, Optional

INTERESTING = 'interesting'
CONTEXT = 'context'
PRUNED = 'pruned'

# The two repos are expected side by side: <root>/cfg-extraction and <root>/SWAT.
DEFAULT_EXPLORER = Path(__file__).resolve().parents[2] / 'SWAT' / 'symbolic-explorer'


def compute_marking(json_path: Path, explorer_dir: Optional[Path] = None) -> Dict[str, str]:
    """Maps every node id of the graph at `json_path` to INTERESTING, CONTEXT or PRUNED."""
    explorer_dir = Path(explorer_dir or DEFAULT_EXPLORER)
    if not (explorer_dir / 'data' / 'StaticAnalysisGraph' / 'SAGraph.py').exists():
        raise FileNotFoundError(f'SWAT symbolic-explorer not found at {explorer_dir} '
                                f'(pass --swat-explorer)')
    if str(explorer_dir) not in sys.path:
        sys.path.insert(0, str(explorer_dir))
    from data.StaticAnalysisGraph.SAGraph import SAGraph  # type: ignore

    graph = SAGraph()
    graph.load_json_graph(str(json_path))
    nodes = graph.nodes

    # Which method each node belongs to, and who calls each method.
    method_of = {n['id']: n.get('metadata', '') for n in graph.json_graph['nodes']}
    callers: Dict[str, list] = {}
    for node in nodes.values():
        if node.is_call_site():
            callee = method_of[node.get_fallthrough_child().id]
            callers.setdefault(callee, []).append(node)

    def handler_at(call_site, chain):
        return next((call_site.handlers[t] for t in chain if t in call_site.handlers), None)

    # Least fixpoint of
    #   some(N)          = reachesAssertSameLevel(N)
    #                      or canReturn(N) and some(returnSite(c)) for a caller c of N's method
    #                      or escapes(N) holds a T that unwinds into an interesting handler
    #   unwinds(M, T)    = for a caller c of M: some(handler of T at c), or if c does not
    #                      catch T, unwinds(method of c, T)
    # Every value only grows, so iterating to stability terminates.
    some = {nid: n.reachesAssertSameLevel for nid, n in nodes.items()}
    unwinds: Dict[tuple, bool] = {}

    def unwind_value(method, chain):
        return unwinds.get((method, chain), False)

    method_chains: Dict[str, set] = {}
    for node in nodes.values():
        method_chains.setdefault(method_of[node.id], set()).update(node.escapes)

    changed = True
    while changed:
        changed = False
        for method, sites in callers.items():
            for chain in method_chains.get(method, ()):
                if unwind_value(method, chain):
                    continue
                for site in sites:
                    handler = handler_at(site, chain)
                    value = some[handler.id] if handler is not None \
                        else unwind_value(method_of[site.id], chain)
                    if value:
                        unwinds[(method, chain)] = True
                        changed = True
                        break
        for nid, node in nodes.items():
            if some[nid]:
                continue
            method = method_of[nid]
            value = node.canReturn and any(some[c.return_site.id] for c in callers.get(method, []))
            value = value or any(unwind_value(method, chain) for chain in node.escapes)
            if value:
                some[nid] = True
                changed = True

    return {nid: INTERESTING if node.reachesAssertSameLevel
            else CONTEXT if some[nid] else PRUNED
            for nid, node in nodes.items()}
