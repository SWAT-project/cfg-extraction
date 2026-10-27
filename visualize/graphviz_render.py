#!/usr/bin/env python3
"""
Graphviz backend for the CFG visualizer.

The matplotlib backend places nodes on a hand-rolled grid whose coordinates know
nothing about the size of the boxes drawn on top of them, so interprocedural CFGs
end up with overlapping nodes, overlapping method frames and unreadable labels.
This module hands the layout problem to Graphviz's `dot` instead:

  * one `subgraph cluster_*` per method, so methods are visually separated and
    never overlap,
  * HTML-like table labels, so a node is as wide as its statement needs and the
    full Jimple text stays readable,
  * straight-line statement sequences merged into basic blocks, which removes
    most of the nodes and edges without losing any statement,
  * call/return edges drawn from the exact statement row they originate at,
  * SVG output alongside the PNG, which stays sharp at any zoom level.
"""

from __future__ import annotations

import html
import re
import shutil
import subprocess
import textwrap
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Sequence, Tuple

# ---------------------------------------------------------------------------
# Styling tables
# ---------------------------------------------------------------------------

# Edge appearance per CFG edge type. `weight` steers dot's ranking: fall-through
# edges are heavy so basic blocks stay in a straight vertical line, while return
# edges are weightless and unconstrained so they never stretch the layout.
EDGE_STYLE: Dict[str, Dict[str, Any]] = {
    'NORMAL':               dict(color='#455a64', style='solid',  weight=12),
    'TRUE_BRANCH':          dict(color='#2e7d32', style='solid',  weight=6),
    'FALSE_BRANCH':         dict(color='#c62828', style='solid',  weight=6),
    'EXCEPTION':            dict(color='#ef6c00', style='dashed', weight=1),
    # An explicit throw, to its local handler or to the method's uncaught[T] node.
    'THROW':                dict(color='#ef6c00', style='solid',  weight=4),
    # Phantom guards mirror SWAT's explicit branches around implicit exceptions.
    # They keep the colours of the real branches they stand in for and are set
    # apart by being dashed rather than solid.
    'PHANTOM_TRUE_BRANCH':  dict(color='#2e7d32', style='dashed', weight=6),
    'PHANTOM_FALSE_BRANCH': dict(color='#c62828', style='dashed', weight=2),
    'CALL':                 dict(color='#1565c0', style='dotted', weight=4),
    'RETURN':               dict(color='#7b1fa2', style='dotted', weight=0,
                                 constraint=False),
    # uncaught[T] -> a handler in some caller; the exceptional counterpart of RETURN.
    'EXCEPTIONAL_RETURN':   dict(color='#b71c1c', style='dotted', weight=0,
                                 constraint=False),
    'CALLBACK':             dict(color='#00838f', style='dashdot', weight=1,
                                 constraint=False),
}

DEFAULT_EDGE_STYLE = dict(color='#455a64', style='solid', weight=1)

# Default edge labels, used when the JSON carries none.
EDGE_FALLBACK_LABEL = {
    'TRUE_BRANCH': 'true',
    'FALSE_BRANCH': 'false',
    'PHANTOM_TRUE_BRANCH': 'safe',
    'PHANTOM_FALSE_BRANCH': 'exception',
    'EXCEPTION': 'throw',
    'THROW': 'throw',
    'EXCEPTIONAL_RETURN': 'unwind',
}

# Role colours for the node header band. Roles are checked in this order.
ROLE_STYLE: Dict[str, Dict[str, str]] = {
    'entry':     dict(band='#1b5e20', text='#ffffff', body='#e8f5e9', tag='ENTRY'),
    'assertion': dict(band='#4a148c', text='#ffffff', body='#f3e5f5', tag='ASSERTION'),
    'uncaught':  dict(band='#b71c1c', text='#ffffff', body='#ffebee', tag='UNCAUGHT'),
    'exit':      dict(band='#c62828', text='#ffffff', body='#fdecea', tag='EXIT'),
    'guard':     dict(band='#ef6c00', text='#ffffff', body='#fff3e0', tag='GUARD'),
    'call':      dict(band='#1565c0', text='#ffffff', body='#e8f0fe', tag='CALL SITE'),
    'branch':    dict(band='#546e7a', text='#ffffff', body='#eceff1', tag='BRANCH'),
    'plain':     dict(band='#90a4ae', text='#ffffff', body='#ffffff', tag=''),
}

# Pastel background / frame pairs cycled over the methods, so neighbouring
# method frames stay distinguishable even where they touch.
METHOD_PALETTE: List[Tuple[str, str]] = [
    ('#f5f7fa', '#5c6bc0'),
    ('#f4faf5', '#43a047'),
    ('#fdf7f2', '#ef6c00'),
    ('#f8f4fb', '#8e24aa'),
    ('#f2fafb', '#00838f'),
    ('#fbf7f4', '#6d4c41'),
    ('#fafaf2', '#9e9d24'),
    ('#fdf4f6', '#c2185b'),
]

SPECIAL_STMT_TYPES = {'PhantomGuard', 'UncaughtException'}

MONO = 'DejaVu Sans Mono'
SANS = 'Helvetica'

# Fully qualified type names blow up the node width; strip the package part.
_FQN_RE = re.compile(r'\b(?:[a-zA-Z_][a-zA-Z0-9_]*\.){1,}([A-Z][A-Za-z0-9_$]*)')
_METHOD_SIG_RE = re.compile(r'^<(?P<cls>[^:]+):\s*(?P<ret>\S+)\s+(?P<name>[^(]+)\((?P<args>[^)]*)\)>$')


def shorten_types(text: str) -> str:
    """`java.lang.String` -> `String`, `a.b.Foo$1` -> `Foo$1`."""
    return _FQN_RE.sub(r'\1', text)


def _esc(text: str) -> str:
    """Escape for HTML-like labels; Graphviz rejects empty cell/font bodies."""
    return html.escape(text, quote=True) or '&nbsp;'


# ---------------------------------------------------------------------------
# Method signatures
# ---------------------------------------------------------------------------

@dataclass
class MethodInfo:
    signature: str          # raw Soot signature, '' for the implicit single method
    key: str                # dot-safe cluster id fragment
    cls: str = ''
    name: str = ''
    ret: str = ''
    args: List[str] = field(default_factory=list)
    node_prefix: str = ''   # e.g. 'm8' for ids like m8_node_3

    @property
    def short(self) -> str:
        if not self.signature:
            return 'method'
        if not self.cls:
            return shorten_types(self.signature)
        return f'{shorten_types(self.cls)}.{self.name}'

    @property
    def params(self) -> str:
        return ', '.join(shorten_types(a) for a in self.args)


def parse_method(signature: str, key: str) -> MethodInfo:
    info = MethodInfo(signature=signature, key=key)
    match = _METHOD_SIG_RE.match(signature.strip()) if signature else None
    if match:
        info.cls = match.group('cls')
        info.ret = match.group('ret')
        info.name = match.group('name')
        args = match.group('args').strip()
        info.args = [a.strip() for a in args.split(',')] if args else []
    return info


def _node_method(node: Dict[str, Any]) -> str:
    meta = node.get('metadata') or ''
    if isinstance(meta, str) and meta.startswith('method: '):
        return meta[len('method: '):]
    return ''


def _id_prefix(node_id: str) -> str:
    """`m8_node_3` -> `m8`; `node_3` -> ``."""
    match = re.match(r'^(m\d+)_', node_id)
    return match.group(1) if match else ''


def _id_suffix(node_id: str) -> str:
    """Short per-row index, e.g. `m8_node_12` -> `12`; '' when there is none."""
    match = re.search(r'(\d+)$', node_id)
    return match.group(1) if match else ''


# ---------------------------------------------------------------------------
# Basic blocks
# ---------------------------------------------------------------------------

@dataclass
class Block:
    dot_id: str
    method: str
    stmts: List[Dict[str, Any]]
    role: str = 'plain'

    @property
    def head(self) -> Dict[str, Any]:
        return self.stmts[0]

    @property
    def tail(self) -> Dict[str, Any]:
        return self.stmts[-1]


class CFGModel:
    """Nodes/edges of one CFG JSON, grouped into methods and basic blocks."""

    def __init__(self, cfg: Dict[str, Any], collapse: bool = True,
                 shorten: bool = True):
        self.cfg = cfg
        self.shorten = shorten
        self.nodes: Dict[str, Dict[str, Any]] = {n['id']: n for n in cfg.get('nodes', [])}
        self.edges: List[Dict[str, Any]] = list(cfg.get('edges', []))
        self.entry_id: str = cfg.get('entryNodeId') or ''
        self.exit_ids = set(cfg.get('exitNodeIds') or [])
        metadata = cfg.get('metadata') or {}
        self.assertion_ids = set(metadata.get('assertionPointIds') or [])

        self.node_method = {nid: _node_method(n) for nid, n in self.nodes.items()}
        self.methods = self._order_methods()
        self.blocks, self.block_of = self._build_blocks(collapse)
        self._order_blocks()
        self.call_sites = {
            e['source'] for e in self.edges if e.get('type') in ('CALL', 'CALLBACK')
        }
        for block in self.blocks:
            block.role = self._role_of(block)
        self._build_cross_references()

    def _build_cross_references(self):
        """Text cross references for call/return edges.

        A call edge in a big interprocedural CFG can span the whole drawing, so
        it is useless while zoomed in. Naming the other end right in the block
        makes the jump followable without tracing the line.
        """
        self.outgoing: Dict[str, List[str]] = {}   # block id -> lines
        self.incoming: Dict[str, List[str]] = {}
        for edge in self.edges:
            edge_type = edge.get('type')
            if edge_type not in ('CALL', 'CALLBACK', 'RETURN', 'EXCEPTIONAL_RETURN'):
                continue
            src, dst = edge.get('source'), edge.get('target')
            if src not in self.block_of or dst not in self.block_of:
                continue
            src_block, dst_block = self.block_of[src], self.block_of[dst]
            if src_block.method == dst_block.method:
                continue
            src_name = self.methods[src_block.method].short if src_block.method else '?'
            dst_name = self.methods[dst_block.method].short if dst_block.method else '?'
            if edge_type == 'RETURN':
                self.outgoing.setdefault(src_block.dot_id, []).append(
                    f'\u21b5 returns to {dst_name} · {dst}')
                self.incoming.setdefault(dst_block.dot_id, []).append(
                    f'\u21b3 returns from {src_name} · {src}')
            elif edge_type == 'EXCEPTIONAL_RETURN':
                self.outgoing.setdefault(src_block.dot_id, []).append(
                    f'\u21b5 unwinds to {dst_name} · {dst}')
                self.incoming.setdefault(dst_block.dot_id, []).append(
                    f'\u21b3 catches from {src_name} · {src}')
            else:
                self.outgoing.setdefault(src_block.dot_id, []).append(
                    f'\u2192 calls {dst_name} · {dst}')
                self.incoming.setdefault(dst_block.dot_id, []).append(
                    f'\u2190 called from {src_name} · {src}')

    # -- methods -----------------------------------------------------------
    def _order_methods(self) -> "Dict[str, MethodInfo]":
        """Methods in call order (BFS from the entry method), entry first."""
        by_method: Dict[str, List[str]] = {}
        for nid in self.nodes:
            by_method.setdefault(self.node_method[nid], []).append(nid)

        succ: Dict[str, List[str]] = {}
        for edge in self.edges:
            src, dst = edge.get('source'), edge.get('target')
            if src in self.node_method and dst in self.node_method:
                succ.setdefault(src, []).append(dst)

        order: List[str] = []
        seen = set()
        start = self.node_method.get(self.entry_id, next(iter(by_method), ''))
        queue = [start] if start in by_method else []
        seen.update(queue)
        while queue:
            current = queue.pop(0)
            order.append(current)
            for nid in by_method.get(current, []):
                for target in succ.get(nid, []):
                    method = self.node_method[target]
                    if method not in seen and method in by_method:
                        seen.add(method)
                        queue.append(method)
        for method in by_method:
            if method not in seen:
                order.append(method)

        infos: Dict[str, MethodInfo] = {}
        for index, method in enumerate(order):
            info = parse_method(method, f'm{index}')
            prefixes = {_id_prefix(nid) for nid in by_method[method]}
            prefixes.discard('')
            if len(prefixes) == 1:
                info.node_prefix = next(iter(prefixes))
            infos[method] = info
        self.method_nodes = by_method
        return infos

    # -- blocks ------------------------------------------------------------
    def _build_blocks(self, collapse: bool) -> Tuple[List[Block], Dict[str, Block]]:
        out_edges: Dict[str, List[Dict[str, Any]]] = {}
        in_edges: Dict[str, List[Dict[str, Any]]] = {}
        for edge in self.edges:
            if edge.get('source') in self.nodes:
                out_edges.setdefault(edge['source'], []).append(edge)
            if edge.get('target') in self.nodes:
                in_edges.setdefault(edge['target'], []).append(edge)

        standalone = set(self.exit_ids) | set(self.assertion_ids)
        if self.entry_id:
            standalone.add(self.entry_id)
        for nid, node in self.nodes.items():
            if node.get('stmtType') in SPECIAL_STMT_TYPES:
                standalone.add(nid)

        def mergeable(src: str) -> Optional[str]:
            """The successor `src` can be glued to, or None."""
            outs = out_edges.get(src, [])
            if len(outs) != 1 or outs[0].get('type') != 'NORMAL':
                return None
            dst = outs[0]['target']
            ins = in_edges.get(dst, [])
            if len(ins) != 1 or ins[0].get('type') != 'NORMAL':
                return None
            if self.node_method[src] != self.node_method[dst]:
                return None
            if src in standalone or dst in standalone:
                return None
            return dst

        # Keep the JSON node order inside each method so blocks read top-down.
        blocks: List[Block] = []
        block_of: Dict[str, Block] = {}
        consumed: set = set()
        for node in self.cfg.get('nodes', []):
            nid = node['id']
            if nid in consumed:
                continue
            # Only start a block at a node that nothing can be glued in front of.
            preds = in_edges.get(nid, [])
            if collapse and len(preds) == 1 and preds[0].get('type') == 'NORMAL':
                pred = preds[0]['source']
                if pred in self.nodes and mergeable(pred) == nid:
                    continue  # will be picked up by its predecessor's chain
            chain = [nid]
            consumed.add(nid)
            if collapse:
                current = nid
                while True:
                    nxt = mergeable(current)
                    if nxt is None or nxt in consumed:
                        break
                    chain.append(nxt)
                    consumed.add(nxt)
                    current = nxt
            block = Block(dot_id=f'blk_{len(blocks)}',
                          method=self.node_method[nid],
                          stmts=[self.nodes[i] for i in chain])
            blocks.append(block)
            for member in chain:
                block_of[member] = block
        return blocks, block_of

    # Edge types that carry control flow inside a method, most "fall through
    # like" first. The order decides which successor is laid out next.
    _FLOW_PREFERENCE = ['NORMAL', 'TRUE_BRANCH', 'PHANTOM_TRUE_BRANCH',
                        'FALSE_BRANCH', 'PHANTOM_FALSE_BRANCH', 'THROW', 'EXCEPTION',
                        'JUMP']

    def _order_blocks(self):
        """Sort each method's blocks into control flow order.

        The JSON lists statements in the order the extractor produced them, and
        synthesised nodes (phantom guards, the uncaught exception sink) are
        appended at the end. Laying blocks out in that order makes a branch jump
        ninety blocks down to its own guard, which is what tears the drawing
        apart. A depth first walk from the method entry, taking fall-through
        edges first, puts every block next to the one it actually flows into.
        """
        successors: Dict[str, List[Tuple[int, str]]] = {}
        for edge in self.edges:
            src, dst = edge.get('source'), edge.get('target')
            if src not in self.block_of or dst not in self.block_of:
                continue
            if edge.get('type') in ('CALL', 'RETURN', 'CALLBACK', 'EXCEPTIONAL_RETURN'):
                continue
            src_block, dst_block = self.block_of[src], self.block_of[dst]
            if src_block is dst_block:
                continue
            edge_type = edge.get('type', 'NORMAL')
            rank = (self._FLOW_PREFERENCE.index(edge_type)
                    if edge_type in self._FLOW_PREFERENCE
                    else len(self._FLOW_PREFERENCE))
            successors.setdefault(src_block.dot_id, []).append((rank, dst_block.dot_id))

        by_id = {b.dot_id: b for b in self.blocks}
        ordered: List[Block] = []
        for method in self.methods:
            method_blocks = [b for b in self.blocks if b.method == method]
            if not method_blocks:
                continue
            roots = [b for b in method_blocks
                     if any(s['id'] == self.entry_id for s in b.stmts)]
            incoming = {b.dot_id: 0 for b in method_blocks}
            for block in method_blocks:
                for _, target in successors.get(block.dot_id, []):
                    if target in incoming:
                        incoming[target] += 1
            roots += [b for b in method_blocks
                      if b not in roots and incoming[b.dot_id] == 0]
            roots += [b for b in method_blocks if b not in roots]

            seen: set = set()
            for root in roots:
                if root.dot_id in seen:
                    continue
                stack = [root.dot_id]
                while stack:
                    current = stack.pop()
                    if current in seen:
                        continue
                    seen.add(current)
                    ordered.append(by_id[current])
                    nexts = sorted(successors.get(current, []),
                                   key=lambda item: item[0], reverse=True)
                    for _, target in nexts:
                        if target in incoming and target not in seen:
                            stack.append(target)
        # Anything unreachable keeps its original position at the end.
        ordered += [b for b in self.blocks if b not in ordered]
        self.blocks = ordered

    def _role_of(self, block: Block) -> str:
        ids = {s['id'] for s in block.stmts}
        types = {s.get('stmtType') for s in block.stmts}
        if self.entry_id in ids:
            return 'entry'
        if ids & self.assertion_ids:
            return 'assertion'
        if 'UncaughtException' in types:
            return 'uncaught'
        if ids & self.exit_ids:
            return 'exit'
        if 'PhantomGuard' in types:
            return 'guard'
        if ids & getattr(self, 'call_sites', set()):
            return 'call'
        if types & {'JIfStmt', 'JSwitchStmt', 'SwitchCase'}:
            return 'branch'
        return 'plain'

    # -- helpers -----------------------------------------------------------
    def stmt_text(self, node: Dict[str, Any]) -> str:
        text = node.get('statement', '')
        return shorten_types(text) if self.shorten else text

    def method_index(self, method: str) -> int:
        keys = list(self.methods)
        return keys.index(method) if method in keys else 0


# ---------------------------------------------------------------------------
# DOT emission
# ---------------------------------------------------------------------------

@dataclass
class RenderOptions:
    collapse: bool = True
    shorten: bool = True
    rankdir: str = 'TB'
    wrap: int = 72
    max_stmts_per_block: int = 0     # 0 = no limit
    focus: Optional[str] = None      # substring filter on method signatures
    show_legend: bool = True
    layout: str = 'lanes'            # 'lanes' | 'call-tree'
    annotate_calls: bool = True
    clone_threshold: int = 6         # 0 disables hub cloning
    # SWAT's pruning, per node id: 'interesting', 'context' or 'pruned' (see swat_marking.py).
    # None draws no marking.
    marking: Optional[Dict[str, str]] = None


# How SWAT's marking is drawn. A pruned statement is struck through and greyed out; one that is
# only interesting in some calling contexts gets an amber background. Branch edges into such
# statements -- where DFS actually decides to prune -- are recoloured the same way.
MARK_STYLE = {
    'pruned':  dict(row='#e8e8e8', text='#9e9e9e', edge='#9e9e9e', label='pruned',
                    legend='pruned: no assert reachable under any call stack'),
    'context': dict(row='#fff1c1', text=None, edge='#e0a100', label='context',
                    legend='context: interesting only under some call stacks'),
}
BRANCH_EDGES = {'TRUE_BRANCH', 'FALSE_BRANCH', 'PHANTOM_TRUE_BRANCH', 'PHANTOM_FALSE_BRANCH'}


def _mark_of(opts: 'RenderOptions', node_id: str) -> Optional[str]:
    if not opts.marking:
        return None
    mark = opts.marking.get(node_id)
    return mark if mark in MARK_STYLE else None


def _wrap_cell(text: str, width: int, strike: bool = False) -> str:
    """HTML for one statement line, wrapped and left aligned."""
    lines = textwrap.wrap(text, width=width, break_long_words=True,
                          break_on_hyphens=False, subsequent_indent='    ') or ['']
    if strike:
        return ''.join(f'<s>{_esc(line)}</s><br align="left"/>' for line in lines)
    return ''.join(f'{_esc(line)}<br align="left"/>' for line in lines)


def _block_label(model: CFGModel, block: Block, opts: RenderOptions,
                 extra_notes: Sequence[Tuple[str, str]] = ()) -> str:
    style = ROLE_STYLE.get(block.role, ROLE_STYLE['plain'])
    stmts = block.stmts
    lines = [s.get('lineNumber', -1) for s in stmts if s.get('lineNumber', -1) >= 0]
    if lines and min(lines) != max(lines):
        line_txt = f'L{min(lines)}–{max(lines)}'
    elif lines:
        line_txt = f'L{lines[0]}'
    else:
        line_txt = ''

    if len(stmts) == 1:
        head = f"{_esc(stmts[0]['id'])}"
        kind = _esc(str(stmts[0].get('stmtType', '')))
    else:
        head = f"{_esc(stmts[0]['id'])} … {_esc(stmts[-1]['id'])}"
        kind = f'{len(stmts)} stmts'

    tag = style['tag']
    marks = [_mark_of(opts, s['id']) for s in stmts]
    if opts.marking and all(m == 'pruned' for m in marks):
        tag = f'{tag} · PRUNED' if tag else 'PRUNED'
    band_bits = [f'<b>{head}</b>']
    if line_txt:
        band_bits.append(line_txt)
    band_bits.append(kind)
    if tag:
        band_bits.append(f'<b>{tag}</b>')
    band = '  ·  '.join(b for b in band_bits if b)

    shown = stmts
    hidden = 0
    if opts.max_stmts_per_block and len(stmts) > opts.max_stmts_per_block:
        keep = opts.max_stmts_per_block
        shown = stmts[:keep - 1] + stmts[-1:]
        hidden = len(stmts) - len(shown)

    rows = [
        f'<tr><td colspan="3" bgcolor="{style["band"]}" align="left">'
        f'<font color="{style["text"]}" point-size="9" face="{SANS}">{band}</font>'
        f'</td></tr>'
    ]
    for index, stmt in enumerate(shown):
        if hidden and index == len(shown) - 1:
            rows.append(
                f'<tr><td colspan="3" align="left">'
                f'<font point-size="9" color="#8a8a8a" face="{SANS}">'
                f'⋮ {hidden} statements not shown</font></td></tr>'
            )
        line = stmt.get('lineNumber', -1)
        line_cell = f'L{line}' if line and line >= 0 else ''
        # For a single statement the id is already in the header band.
        id_cell = _id_suffix(stmt['id']) if len(stmts) > 1 else ''
        mark = _mark_of(opts, stmt['id'])
        mark_style = MARK_STYLE.get(mark, {})
        row_bg = f' bgcolor="{mark_style["row"]}"' if mark else ''
        text_colour = f' color="{mark_style["text"]}"' if mark_style.get('text') else ''
        rows.append(
            '<tr>'
            f'<td align="right" valign="top" port="p_{stmt["id"]}"{row_bg}>'
            f'<font point-size="8" color="#78909c" face="{MONO}">{_esc(id_cell)}</font></td>'
            f'<td align="right" valign="top"{row_bg}>'
            f'<font point-size="8" color="#b0bec5" face="{MONO}">{_esc(line_cell)}</font></td>'
            f'<td align="left" valign="top"{row_bg}>'
            f'<font point-size="10" face="{MONO}"{text_colour}>'
            f'{_wrap_cell(model.stmt_text(stmt), opts.wrap, strike=mark == "pruned")}</font></td>'
            '</tr>'
        )

    for text, colour in extra_notes:
        rows.append(
            f'<tr><td colspan="3" align="left">'
            f'<font point-size="9" color="{colour}" face="{SANS}">'
            f'<i>{_esc(text)}</i></font></td></tr>'
        )

    if opts.annotate_calls:
        refs = ([(l, '#7b1fa2' if 'return' in l else '#1565c0')
                 for l in model.incoming.get(block.dot_id, [])]
                + [(l, '#7b1fa2' if 'return' in l else '#1565c0')
                   for l in model.outgoing.get(block.dot_id, [])])
        for text, colour in refs:
            rows.append(
                f'<tr><td colspan="3" align="left">'
                f'<font point-size="9" color="{colour}" face="{SANS}">'
                f'<i>{_esc(text)}</i></font></td></tr>'
            )

    return (
        f'<<table border="1" cellborder="0" cellspacing="0" cellpadding="3" '
        f'color="{style["band"]}" bgcolor="{style["body"]}" style="rounded">'
        + ''.join(rows) +
        '</table>>'
    )


def _hub_stub_label(model: CFGModel, block: Block, predecessors: int,
                    opts: RenderOptions) -> str:
    """Compact stand-in for a block that everything jumps to."""
    style = ROLE_STYLE.get(block.role, ROLE_STYLE['plain'])
    stmt = model.stmt_text(block.head)
    if len(stmt) > 56:
        stmt = stmt[:53] + '...'
    tag = f'  ·  <b>{style["tag"]}</b>' if style['tag'] else ''
    return (
        f'<<table border="1" cellborder="0" cellspacing="0" cellpadding="3" '
        f'color="{style["band"]}" bgcolor="{style["body"]}" style="rounded,dashed">'
        f'<tr><td bgcolor="{style["band"]}" align="left">'
        f'<font color="{style["text"]}" point-size="9" face="{SANS}">'
        f'&#8594; <b>{_esc(block.head["id"])}</b>{tag}</font></td></tr>'
        f'<tr><td align="left"><font point-size="9" face="{MONO}">{_esc(stmt)}</font></td></tr>'
        f'<tr><td align="left"><font point-size="8" color="#8a8a8a" face="{SANS}"><i>'
        f'shared target of {predecessors} blocks, drawn here instead of one long edge'
        f'</i></font></td></tr>'
        f'</table>>'
    )


def _cluster_label(model: CFGModel, info: MethodInfo, node_count: int,
                   frame: str) -> str:
    prefix = f'  <font color="#90a4ae">[{_esc(info.node_prefix)}_*]</font>' if info.node_prefix else ''
    ret = f'<font color="#78909c"> : {_esc(shorten_types(info.ret))}</font>' if info.ret else ''
    signature = _esc(info.signature) if info.signature else ''
    return (
        f'<<table border="0" cellborder="0" cellspacing="0" cellpadding="2">'
        f'<tr><td align="left"><font point-size="15" color="{frame}" face="{SANS}">'
        f'<b>{_esc(info.short)}</b>({_esc(info.params)})</font>{ret}{prefix}</td></tr>'
        f'<tr><td align="left"><font point-size="8" color="#90a4ae" face="{MONO}">'
        f'{signature}  ·  {node_count} nodes</font></td></tr>'
        f'</table>>'
    )


def _legend_label(model: CFGModel, opts: RenderOptions) -> str:
    present = {e.get('type', 'NORMAL') for e in model.edges}
    order = ['NORMAL', 'TRUE_BRANCH', 'FALSE_BRANCH', 'PHANTOM_TRUE_BRANCH',
             'PHANTOM_FALSE_BRANCH', 'THROW', 'EXCEPTION', 'CALL', 'RETURN',
             'EXCEPTIONAL_RETURN', 'CALLBACK']
    glyph = {'solid': '───', 'dashed': '─ ─ ',
             'dotted': '· · ·', 'dashdot': '─ · ─'}
    edge_cells = []
    for edge_type in order:
        if edge_type not in present:
            continue
        style = EDGE_STYLE.get(edge_type, DEFAULT_EDGE_STYLE)
        edge_cells.append(
            f'<td align="left"><font color="{style["color"]}" face="{MONO}">'
            f'{glyph.get(style["style"], "───")}</font>'
            f'<font point-size="9" face="{SANS}"> {_esc(edge_type.replace("_", " ").lower())}</font></td>'
        )

    roles_present = {b.role for b in model.blocks}
    role_cells = []
    for role in ['entry', 'exit', 'call', 'branch', 'guard', 'uncaught', 'assertion']:
        if role not in roles_present:
            continue
        style = ROLE_STYLE[role]
        role_cells.append(
            f'<td align="left"><font color="{style["band"]}" point-size="11">&#9632;</font>'
            f'<font point-size="9" face="{SANS}"> {style["tag"].lower()}</font></td>'
        )

    mark_cells = []
    if opts.marking:
        present_marks = set(opts.marking.values())
        for mark, style in MARK_STYLE.items():
            if mark not in present_marks:
                continue
            sample = '<s>stmt</s>' if mark == 'pruned' else 'stmt'
            mark_cells.append(
                f'<td align="left" bgcolor="{style["row"]}">'
                f'<font point-size="9" face="{MONO}" color="{style["text"] or "#000000"}">{sample}</font></td>'
                f'<td align="left"><font point-size="9" face="{SANS}"> {_esc(style["legend"])}</font></td>'
            )

    rows = []
    if edge_cells:
        rows.append('<tr>' + '<td>&nbsp;</td>'.join(edge_cells) + '</tr>')
    if role_cells:
        rows.append('<tr>' + '<td>&nbsp;</td>'.join(role_cells) + '</tr>')
    if mark_cells:
        rows.append('<tr>' + '<td>&nbsp;</td>'.join(mark_cells) + '</tr>')
    return ''.join(rows)


def _graph_label(model: CFGModel, opts: RenderOptions) -> str:
    cfg = model.cfg
    class_name = _esc(str(cfg.get('className', '?')))
    signature = _esc(shorten_types(str(cfg.get('methodSignature', '?'))))
    analysis = _esc(str(cfg.get('analysisType', '?')))
    stats = [
        f'{len(model.nodes)} statements',
        f'{len(model.blocks)} blocks' if opts.collapse else f'{len(model.blocks)} nodes',
        f'{len(model.edges)} edges',
        f'{len([m for m in model.methods if m])} methods' if any(model.methods) else '',
    ]
    metadata = cfg.get('metadata') or {}
    if metadata.get('assertionPointIds'):
        stats.append(f'{len(metadata["assertionPointIds"])} assertion points')
    stats_txt = '  ·  '.join(s for s in stats if s)

    legend = _legend_label(model, opts) if opts.show_legend else ''
    focus = (f'<tr><td align="left"><font point-size="10" color="#c62828" face="{SANS}">'
             f'focus: {_esc(opts.focus)}</font></td></tr>') if opts.focus else ''
    collapsed_note = ''
    if opts.collapse and len(model.blocks) < len(model.nodes):
        collapsed_note = (
            f'<tr><td align="left"><font point-size="9" color="#90a4ae" face="{SANS}">'
            'straight-line statements are merged into basic blocks '
            '(every statement is still listed inside its block)</font></td></tr>'
        )

    return (
        f'<<table border="0" cellborder="0" cellspacing="0" cellpadding="3">'
        f'<tr><td align="left"><font point-size="22" face="{SANS}"><b>{class_name}</b>'
        f'<font color="#546e7a">  —  {signature}</font></font></td></tr>'
        f'<tr><td align="left"><font point-size="11" color="#546e7a" face="{SANS}">'
        f'{analysis}  ·  {stats_txt}</font></td></tr>'
        f'{focus}{collapsed_note}'
        f'<tr><td align="left" cellpadding="2">'
        f'<table border="0" cellborder="0" cellspacing="4" cellpadding="1">{legend}</table>'
        f'</td></tr>'
        f'</table>>'
    )


def _attrs(items: Dict[str, Any]) -> str:
    parts = []
    for key, value in items.items():
        if value is None:
            continue
        if isinstance(value, bool):
            parts.append(f'{key}={"true" if value else "false"}')
        elif isinstance(value, (int, float)):
            parts.append(f'{key}={value}')
        elif isinstance(value, str) and value.startswith('<') and value.endswith('>'):
            parts.append(f'{key}={value}')          # HTML-like label
        else:
            escaped = str(value).replace('\\', '\\\\').replace('"', '\\"')
            parts.append(f'{key}="{escaped}"')
    return ', '.join(parts)


def build_dot(cfg: Dict[str, Any], opts: RenderOptions) -> str:
    model = CFGModel(cfg, collapse=opts.collapse, shorten=opts.shorten)

    if opts.focus:
        kept = {m for m in model.methods
                if not m or opts.focus.lower() in m.lower()}
        if not kept:
            raise SystemExit(f'--focus {opts.focus!r} matched no method; available:\n  '
                             + '\n  '.join(sorted(m for m in model.methods if m)))
    else:
        kept = set(model.methods)

    lines: List[str] = ['digraph CFG {']
    lines.append('  graph [' + _attrs({
        'rankdir': opts.rankdir,
        'compound': True,
        'newrank': True,
        'splines': 'spline',
        'concentrate': False,
        'nodesep': 0.35,
        'ranksep': 0.7,
        'pad': 0.4,
        'bgcolor': 'white',
        'fontname': SANS,
        'labelloc': 't',
        'labeljust': 'l',
        'label': _graph_label(model, opts),
    }) + '];')
    lines.append('  node [' + _attrs({
        'shape': 'plain', 'fontname': MONO, 'fontsize': 10, 'margin': 0,
    }) + '];')
    lines.append('  edge [' + _attrs({
        'fontname': SANS, 'fontsize': 9, 'arrowsize': 0.7, 'penwidth': 1.3,
    }) + '];')

    # -- hubs: blocks that half the CFG jumps into ------------------------
    # A shared `return` or the single uncaught-exception node can collect dozens
    # of predecessors. Drawn as one node, every branch has to travel across the
    # whole picture to reach it and the layout explodes. Keep one canonical copy
    # (it owns the outgoing edges) and replace the other incoming edges with a
    # small stub next to each source instead.
    incoming: Dict[str, List[Dict[str, Any]]] = {}
    outgoing: Dict[str, List[Dict[str, Any]]] = {}
    for edge in model.edges:
        src, dst = edge.get('source'), edge.get('target')
        if src not in model.block_of or dst not in model.block_of:
            continue
        src_block, dst_block = model.block_of[src], model.block_of[dst]
        if src_block is dst_block:
            continue
        incoming.setdefault(dst_block.dot_id, []).append(edge)
        outgoing.setdefault(src_block.dot_id, []).append(edge)

    hub_canonical: Dict[str, int] = {}   # block id -> id() of the kept edge
    hub_predecessors: Dict[str, int] = {}
    if opts.clone_threshold:
        for block in model.blocks:
            ins = incoming.get(block.dot_id, [])
            outs = [e for e in outgoing.get(block.dot_id, [])
                    if e.get('type') not in ('RETURN', 'EXCEPTIONAL_RETURN')]
            if len(ins) < opts.clone_threshold or outs:
                continue
            index = model.blocks.index(block)
            previous = model.blocks[index - 1].dot_id if index else None
            canonical = next(
                (e for e in ins
                 if e.get('type') == 'NORMAL'
                 and model.block_of[e['source']].dot_id == previous),
                None)
            canonical = canonical or next(
                (e for e in ins if e.get('type') == 'NORMAL'), ins[0])
            hub_canonical[block.dot_id] = id(canonical)
            hub_predecessors[block.dot_id] = len(ins)

    # Stubs live in the lane of the block that jumps to the hub.
    stub_for_edge: Dict[int, str] = {}
    stubs_by_method: Dict[str, List[str]] = {}
    for edge in model.edges:
        src, dst = edge.get('source'), edge.get('target')
        if src not in model.block_of or dst not in model.block_of:
            continue
        dst_block = model.block_of[dst]
        if dst_block.dot_id not in hub_canonical:
            continue
        if id(edge) == hub_canonical[dst_block.dot_id]:
            continue
        src_block = model.block_of[src]
        stub_id = f'stub_{dst_block.dot_id}_{len(stub_for_edge)}'
        stub_for_edge[id(edge)] = stub_id
        stubs_by_method.setdefault(src_block.method, []).append(
            f'{stub_id} [' + _attrs({
                'label': _hub_stub_label(model, dst_block,
                                         hub_predecessors[dst_block.dot_id], opts),
                'tooltip': f'stand-in for {dst_block.head["id"]}',
            }) + '];')

    # -- method clusters ---------------------------------------------------
    stub_nodes: Dict[str, str] = {}
    for method, info in model.methods.items():
        if method not in kept:
            continue
        blocks = [b for b in model.blocks if b.method == method]
        if not blocks:
            continue
        body, frame = METHOD_PALETTE[model.method_index(method) % len(METHOD_PALETTE)]
        if method:
            lines.append(f'  subgraph cluster_{info.key} {{')
            lines.append('    graph [' + _attrs({
                'label': _cluster_label(model, info, len(model.method_nodes[method]), frame),
                'labeljust': 'l',
                'labelloc': 't',
                'style': 'rounded,filled',
                'fillcolor': body,
                'color': frame,
                'penwidth': 2.0,
                'margin': 16,
            }) + '];')
            indent = '    '
        else:
            indent = '  '
        for block in blocks:
            notes: List[Tuple[str, str]] = []
            if block.dot_id in hub_predecessors:
                others = hub_predecessors[block.dot_id] - 1
                notes.append((f'\u2190 reached from {others} more blocks, '
                              f'each drawn as a stub in its own lane', '#8a8a8a'))
            lines.append(f'{indent}{block.dot_id} [' + _attrs({
                'label': _block_label(model, block, opts, notes),
                'tooltip': f'{block.head["id"]} ({block.role})',
            }) + '];')
        for stub_line in stubs_by_method.get(method, []):
            lines.append(f'{indent}{stub_line}')
        if method:
            lines.append('  }')

    # -- program order skeleton -------------------------------------------
    # Call sites break a method into blocks that are only connected through its
    # callees (call out, return back in). Without a hint, dot has no reason to
    # keep those blocks in program order and scatters them sideways, so pin
    # consecutive blocks of a method together with invisible edges. Real control
    # flow edges outrank these, they only fill in the gaps.
    real_pairs = {(model.block_of[e['source']].dot_id, model.block_of[e['target']].dot_id)
                  for e in model.edges
                  if e.get('source') in model.block_of and e.get('target') in model.block_of}
    for method in model.methods:
        if method not in kept:
            continue
        blocks = [b for b in model.blocks if b.method == method]
        for previous, following in zip(blocks, blocks[1:]):
            pair = (previous.dot_id, following.dot_id)
            if pair in real_pairs or pair[::-1] in real_pairs:
                continue
            lines.append(f'  {previous.dot_id} -> {following.dot_id} [' + _attrs({
                'style': 'invis', 'weight': 8, 'constraint': True,
            }) + '];')

    # -- edges -------------------------------------------------------------
    def endpoint(node_id: str, port: bool, tail: bool) -> Optional[str]:
        block = model.block_of.get(node_id)
        if block is None:
            return None
        if block.method not in kept:
            method_info = model.methods.get(block.method)
            stub_id = f'stub_{method_info.key}' if method_info else 'stub_x'
            if stub_id not in stub_nodes:
                stub_nodes[stub_id] = method_info.short if method_info else '?'
            return stub_id
        if port and len(block.stmts) > 1:
            compass = 'e' if tail else 'w'
            return f'{block.dot_id}:p_{node_id}:{compass}'
        return block.dot_id

    seen_stub_edges: set = set()
    for edge in model.edges:
        src_id, dst_id = edge.get('source'), edge.get('target')
        if src_id not in model.block_of or dst_id not in model.block_of:
            continue
        edge_type = edge.get('type', 'NORMAL')
        src_block, dst_block = model.block_of[src_id], model.block_of[dst_id]
        if src_block is dst_block and edge_type == 'NORMAL':
            continue  # fall-through inside a basic block
        if src_block.method not in kept and dst_block.method not in kept:
            continue  # both ends were filtered out by --focus

        # Cross-method edges point at the exact statement they leave from.
        use_port = edge_type in ('CALL', 'RETURN', 'CALLBACK', 'EXCEPTION', 'EXCEPTIONAL_RETURN')
        tail = endpoint(src_id, use_port, True)
        head = (stub_for_edge[id(edge)] if id(edge) in stub_for_edge
                else endpoint(dst_id, use_port, False))
        if tail is None or head is None or tail == head:
            continue
        if tail.startswith('stub_') or head.startswith('stub_'):
            if (tail, head, edge_type) in seen_stub_edges:
                continue
            seen_stub_edges.add((tail, head, edge_type))

        style = dict(EDGE_STYLE.get(edge_type, DEFAULT_EDGE_STYLE))
        if opts.layout == 'lanes' and edge_type in ('CALL', 'CALLBACK'):
            # Lane mode: call edges must not push the callee below its call
            # site, so every method starts at the top of its own column and is
            # read straight down. Cascading callees instead (call-tree mode)
            # wastes a lot of space on wide call graphs.
            style['constraint'] = False
        label = edge.get('label') or EDGE_FALLBACK_LABEL.get(edge_type, '')
        if edge_type in ('CALL', 'CALLBACK') and label:
            label = f'{label}()'
        attrs: Dict[str, Any] = {
            'color': style['color'],
            'style': style['style'],
            'weight': style.get('weight', 1),
            'constraint': style.get('constraint', True),
        }
        if label:
            attrs['label'] = f' {label} '
            attrs['fontcolor'] = style['color']
        if edge_type in ('CALL', 'RETURN', 'CALLBACK', 'EXCEPTIONAL_RETURN'):
            attrs['penwidth'] = 1.6
            attrs['arrowhead'] = 'vee'
        # A branch side SWAT prunes (or prunes depending on the call stack): this edge is where
        # DFS makes that decision, so it carries the marking of the node it leads to.
        target_mark = _mark_of(opts, dst_id) if edge_type in BRANCH_EDGES else None
        if target_mark:
            mark_style = MARK_STYLE[target_mark]
            attrs['color'] = attrs['fontcolor'] = mark_style['edge']
            attrs['style'] = 'dashed'
            attrs['label'] = f' {label} · {mark_style["label"]} ' if label else f' {mark_style["label"]} '
        lines.append(f'  {tail} -> {head} [' + _attrs(attrs) + '];')

    for stub_id, name in sorted(stub_nodes.items()):
        lines.append(f'  {stub_id} [' + _attrs({
            'shape': 'box', 'style': 'rounded,filled,dashed', 'fillcolor': '#eceff1',
            'color': '#90a4ae', 'fontname': SANS, 'fontsize': 11,
            'label': f'{name}\n(not shown)',
        }) + '];')

    lines.append('}')
    return '\n'.join(lines)


# ---------------------------------------------------------------------------
# Rendering
# ---------------------------------------------------------------------------

_SVG_SIZE_RE = re.compile(r'width="([\d.]+)pt"\s+height="([\d.]+)pt"')


def dot_available() -> bool:
    return shutil.which('dot') is not None


def _run_dot(dot_source: str, fmt: str, extra: Sequence[str] = ()) -> bytes:
    cmd = ['dot', f'-T{fmt}', *extra]
    proc = subprocess.run(cmd, input=dot_source.encode('utf-8'),
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if proc.returncode != 0:
        raise RuntimeError(f'{" ".join(cmd)} failed:\n{proc.stderr.decode("utf-8", "replace")}')
    if proc.stderr:
        # Graphviz 2.43 mis-parses the style of some edges as an arrow type
        # (`dotted` -> `dot` + `ted`). We never set an arrow type other than
        # `vee`, so those warnings are noise.
        noise = [line for line in proc.stderr.decode('utf-8', 'replace').splitlines()
                 if line.strip() and not re.match(r'Warning: Arrow type ', line)]
        if noise:
            print('  graphviz: ' + '\n  graphviz: '.join(noise))
    return proc.stdout


def render(dot_source: str, base_path: Path, formats: Iterable[str] = ('png', 'svg'),
           dpi: int = 96, max_pixels: int = 16000) -> List[Path]:
    """Render `dot_source` next to `base_path`, returning the written files."""
    formats = list(dict.fromkeys(formats))
    written: List[Path] = []

    svg_bytes = _run_dot(dot_source, 'svg')
    size = _SVG_SIZE_RE.search(svg_bytes[:4000].decode('utf-8', 'replace'))
    width_pt, height_pt = (float(size.group(1)), float(size.group(2))) if size else (0.0, 0.0)

    if 'svg' in formats:
        svg_path = base_path.with_suffix('.svg')
        svg_path.write_bytes(svg_bytes)
        written.append(svg_path)

    for fmt in formats:
        if fmt == 'svg':
            continue
        used_dpi = dpi
        longest = max(width_pt, height_pt) / 72.0 * dpi
        if longest > max_pixels and longest > 0:
            used_dpi = max(24, int(dpi * max_pixels / longest))
            print(f'  {fmt}: {int(longest)}px would be too large, rendering at {used_dpi} dpi')
        out = base_path.with_suffix(f'.{fmt}')
        out.write_bytes(_run_dot(dot_source, fmt, [f'-Gdpi={used_dpi}']))
        written.append(out)

    if width_pt:
        print(f'  layout: {width_pt / 72:.0f}x{height_pt / 72:.0f} in '
              f'({width_pt / 72 * dpi:.0f}x{height_pt / 72 * dpi:.0f} px at {dpi} dpi)')
    return written


# ---------------------------------------------------------------------------
# Call graph overview
# ---------------------------------------------------------------------------

def build_callgraph_dot(cfg: Dict[str, Any], shorten: bool = True) -> Optional[str]:
    """A one-node-per-method overview of the interprocedural structure."""
    model = CFGModel(cfg, collapse=True, shorten=shorten)
    if len([m for m in model.methods if m]) < 2:
        return None

    counts: Dict[Tuple[str, str], int] = {}
    for edge in model.edges:
        if edge.get('type') not in ('CALL', 'CALLBACK'):
            continue
        src = model.node_method.get(edge['source'], '')
        dst = model.node_method.get(edge['target'], '')
        if src and dst:
            counts[(src, dst)] = counts.get((src, dst), 0) + 1

    entry_method = model.node_method.get(model.entry_id, '')
    lines = ['digraph CallGraph {']
    lines.append('  graph [' + _attrs({
        'rankdir': 'TB', 'nodesep': 0.4, 'ranksep': 0.6, 'bgcolor': 'white',
        'fontname': SANS, 'labelloc': 't', 'labeljust': 'l',
        'label': f'<<table border="0" cellborder="0" cellspacing="0" cellpadding="3">'
                 f'<tr><td align="left"><font point-size="18" face="{SANS}"><b>Call graph</b>'
                 f'<font color="#546e7a">  —  '
                 f'{_esc(shorten_types(str(cfg.get("methodSignature", "?"))))}</font></font></td></tr>'
                 f'</table>>',
    }) + '];')
    lines.append(f'  node [shape=box, style="rounded,filled", fontname="{SANS}", fontsize=12];')

    for method, info in model.methods.items():
        if not method:
            continue
        body, frame = METHOD_PALETTE[model.method_index(method) % len(METHOD_PALETTE)]
        node_count = len(model.method_nodes[method])
        is_entry = method == entry_method
        lines.append(f'  {info.key} [' + _attrs({
            'label': f'<<table border="0" cellborder="0" cellspacing="0" cellpadding="2">'
                     f'<tr><td><font point-size="13"><b>{_esc(info.short)}</b>'
                     f'({_esc(info.params)})</font></td></tr>'
                     f'<tr><td><font point-size="9" color="#607d8b">'
                     f'{node_count} statements{"  ·  entry" if is_entry else ""}</font></td></tr>'
                     f'</table>>',
            'fillcolor': '#e8f5e9' if is_entry else body,
            'color': '#1b5e20' if is_entry else frame,
            'penwidth': 2.5 if is_entry else 1.5,
        }) + '];')

    for (src, dst), count in sorted(counts.items(), key=lambda kv: -kv[1]):
        lines.append(f'  {model.methods[src].key} -> {model.methods[dst].key} [' + _attrs({
            'color': '#1565c0', 'penwidth': 1.0 + min(count, 6) * 0.4,
            'label': f' {count}x ' if count > 1 else None,
            'fontcolor': '#1565c0',
        }) + '];')
    lines.append('}')
    return '\n'.join(lines)
