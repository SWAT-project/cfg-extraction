# Inter-Procedural CFG Analysis

This document explains how inter-procedural control flow graph analysis works in cfg-extractor.

## What is Inter-Procedural CFG?

An **inter-procedural CFG** connects multiple methods into a single graph by adding:
- **CALL edges**: From call sites to callee entry points
- **RETURN edges**: From callee exit points back to the return site (statement after the call)

This provides a complete view of control flow across method boundaries.

## Comparison: Intra vs Inter

### Intra-Procedural (Default)
- Analyzes each method in isolation
- Generates one JSON file per method
- Faster, simpler analysis
- Suitable for understanding individual methods

```bash
./gradlew run --args="target/ output/ Main main intra"
```

**Output**: Multiple files like `Main_main.json`, `Main_ack.json`, etc.

### Inter-Procedural
- Combines multiple methods into one CFG
- Follows method calls recursively
- Generates single JSON file with all reachable methods
- Shows complete program flow

```bash
./gradlew run --args="target/ output/ Main main inter"
```

**Output**: Single file `Main_main_interprocedural.json`

## How It Works

### 1. Dispatch Resolution

The method named at a call site is not necessarily the method that runs. The declared receiver
type of a `virtualinvoke` (or `interfaceinvoke`) is only an upper bound, so a call compiled
against `java.io.PrintWriter` may well run an override declared in an application class -- and
that override is often exactly where an `assert` lives.

`ReceiverTypeResolver` therefore traces each receiver local back to its origin: allocations,
copies between locals, casts, `this`, and the return values of the methods it came from. Where
that pins down the receiver's concrete type, dispatch is resolved against it rather than against
the declared type.

The analysis is flow-insensitive and gives up rather than guessing. A receiver read from a field,
an array, or a parameter has no traceable origin, so it falls back on the declared type plus its
concrete application subtypes.

Each call site ends up in one of three states:

| State | CALL/RETURN edges | Assertion point |
| --- | --- | --- |
| Exactly one target, provably | added | only if the callee contains one |
| Several possible targets | none | the call site is flagged, if an application method is among them |
| Nothing established (e.g. `invokedynamic`) | none | the call site is flagged |

Ambiguous call sites get no CALL edge on purpose. SWAT walks this graph in lockstep with real
executions, so an edge into a body that does not actually run would desynchronise the branch
structure -- worse than having no edge. Flagging the call site as a possible assertion point
instead keeps every path leading to it from being pruned, which is the conservative choice: a
missed assertion point costs soundness, a spurious one only costs pruning.

Library overrides of library methods are deliberately not enumerated. They would resolve to
library methods, which are never inlined anyway, so listing them would make good resolutions look
ambiguous without making the graph any more complete.

### 2. CFG Merging Process

Starting from the entry point method:

1. **Extract intra-procedural CFG** for current method
2. **Rename nodes** with method-specific prefixes (`m1_node_0`, `m2_node_1`, etc.)
3. **Find call sites** (statements with invoke expressions)
4. **Resolve the callee** as described above
5. **Recursively extract** callee CFGs
6. **Add CALL edge** from call site to callee entry
7. **Add RETURN edges** from all callee exits to return site

### 3. Node Metadata

Each node includes metadata about which method it belongs to:

```json
{
  "id": "m1_node_5",
  "statement": "l2 = staticinvoke <Verifier: int nondetInt()>()",
  "stmtType": "JAssignStmt",
  "lineNumber": 28,
  "metadata": "method: <Main: void main(java.lang.String[])>"
}
```

## Example Output

For this simple program:

```java
public class Main {
    public static void main(String[] args) {
        int x = nondetInt();
        int y = ack(x, 3);
    }

    public static int ack(int m, int n) {
        if (m == 0) return n + 1;
        if (n == 0) return ack(m - 1, 1);
        return ack(m - 1, ack(m, n - 1));
    }
}
```

The inter-procedural CFG contains:
- **41 nodes** (from Main.main, Main.ack, Main.clinit)
- **88 edges** (intra-procedural + CALL + RETURN edges)

### Edge Types

```json
{
  "source": "m1_node_15",
  "target": "m3_node_0",
  "type": "CALL",
  "label": "ack"
}
```

```json
{
  "source": "m3_node_13",
  "target": "m1_node_16",
  "type": "RETURN"
}
```

## Depth Limit

To prevent infinite recursion (e.g., recursive methods), the analysis has a **depth limit of 5**:

```java
extractMethodRecursively(method, combinedCFG, depth + 1, maxDepth=5);
```

Methods beyond this depth are not inlined. You can adjust this in:
```
src/main/java/com/cfgextractor/extractor/InterProceduralCFGExtractor.java:70
```

## Visualization

```bash
source visualize/.venv/bin/activate
python visualize/visualize_cfg.py output/Main_main_interprocedural.json
```

This writes `Main_main_interprocedural.png` plus a `.svg` (preferred for large
graphs) and a `Main_main_interprocedural_callgraph.png/.svg` overview with one
node per method.

Each method is drawn as its own labelled frame, read top to bottom, with the
frames placed side by side (`--layout call-tree` cascades callees below their
call site instead). Straight-line statements are merged into basic blocks that
still list every statement, and call/return edges are additionally spelled out
in text inside the blocks (`→ calls Arrays1.doGet · m8_node_0`) so they can be
followed while zoomed in. `--focus <method>` restricts the drawing to one
method. See the visualization section of README.md for all options.

**Edge colors**:
- Blue dotted: CALL edges
- Purple dotted: RETURN edges
- Green solid: TRUE branch
- Red solid: FALSE branch
- Green/red dashed: phantom guard branches (safe / exception)
- Orange dashed: EXCEPTION edges
- Dark grey solid: Normal flow

## Limitations & Future Work

### Current Limitations

1. **Receiver Origins**: A receiver read from a field, an array, or a parameter is untraceable,
   so the call site falls back on the declared type and is usually reported as ambiguous
   - Solution: propagate types through fields and across call sites (a points-to analysis)

2. **Depth Limit**: Recursive methods are cut off
   - Solution: Use cycle detection instead of depth limit

3. **Context Sensitivity**: Doesn't track calling contexts
   - Solution: Add context-sensitive analysis mode

4. **Static Initializers**: `<clinit>` is never entered, so an assertion inside one is invisible
   - Solution: add implicit call edges where a class is first used

5. **Ambiguous Calls**: Several possible targets means no CALL edge at all, so the callee bodies
   are missing from the graph even though one of them runs
   - Solution: a graph format that can express alternative callees

6. **Shared Callees**: A method is inlined once no matter how many call sites it has, so its exit
   node ends up with a RETURN edge per call site. SWAT's `SAGraph` allows a node only one
   fall-through successor and refuses to load such a graph, which disables the static analysis for
   that benchmark. Resolving more virtual calls inlines more methods and so runs into this more
   often
   - Solution: inline per call site, or leave repeat calls unexpanded and flag them the way
     ambiguous dispatch is flagged

### Future Enhancements

#### Context-Sensitive Analysis

Add calling context to node IDs:

```java
// Instead of: m2_node_5
// Use: m2_[main->ack]_node_5  (called from main)
//      m2_[ack->ack]_node_5   (called from ack recursively)
```

This enables:
- More precise data flow analysis
- Better taint tracking
- Accurate constant propagation

#### Smart Inlining

Instead of fixed depth limit, use heuristics:
- Inline small methods completely
- Summarize large methods
- Detect and handle cycles specially

#### More Precise Receiver Types

Dispatch resolution is only as good as the receiver types it can infer, and today those stop at
the method boundary. A whole-program analysis would carry types through fields and across call
sites, turning many of the currently ambiguous call sites into resolved ones:

```java
// Rapid Type Analysis: restricts dispatch to types the program actually instantiates
CallGraphAlgorithm cga = new RapidTypeAnalysisAlgorithm(view);

// Points-to analysis (most precise, slower)
// Requires Qilin integration
```

Note that SootUp's body interceptors are *not* an option here. `LocalSplitter` would remove the
need for the receiver-type filter that works around javac's local slot reuse, but interceptors
reshape Jimple, and the branch structure of the emitted graph has to match what SWAT observes at
runtime statement for statement.

## References

- [SootUp Call Graph Documentation](https://soot-oss.github.io/SootUp/v1.1.2/call-graph-construction/)
- [Call Graph Construction Algorithms](https://yanniss.github.io/points-to-tutorial15.pdf)
- [Inter-procedural Analysis](https://en.wikipedia.org/wiki/Interprocedural_optimization)
