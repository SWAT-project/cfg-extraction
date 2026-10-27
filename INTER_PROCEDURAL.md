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

Each call site ends up in one of these states:

| State | CALL/RETURN edges | Marked incomplete |
| --- | --- | --- |
| Exactly one target, provably | added (also for repeat and recursive calls) | no |
| Several targets, all known application methods (exact dispatch) | one per target | no |
| Several possible targets otherwise | none | yes, if an application method is among them |
| Nothing established (e.g. `invokedynamic`) | none | yes |
| Receiver untraceable, and a lambda in the program implements its interface | none | yes -- lambda classes are not in the view |
| Reflective call (`Method.invoke`, `newInstance`, `Class.forName`, `MethodHandle.invoke*`) | none | yes |
| Only library or blacklisted targets | none | no -- never inlined, so nothing is missing |

A call site with several known targets gets a CALL edge to each, with the same return site and
handlers. SWAT's walk does not follow it (it cannot know which body runs) but finds its place
again inside the callee by branch key, and the summaries take the union over the callees. Other
ambiguous call sites get no CALL edge: their targets are not all known, so the graph cannot list
them.

Instead such a node is marked **incomplete**, which says two things at once:

* it is reported in `metadata.assertionPointIds`, so the paths *leading* to it stay interesting.
  Without this the consumer, which marks interesting paths by walking predecessors, would prune
  the path before ever arriving -- and a missed assertion point costs soundness, while a spurious
  one only costs pruning.
* the node carries `"isIncomplete": true`, so the graph is not trusted *beyond* it. The branches
  of whatever was not expanded are missing here, and the consumer pairs graph against execution
  positionally (no ids are matched), so every later pairing on the path would be shifted by them.
  SWAT's `SANode.walk_till_branch()` stops at such a node and reports "no information", which it
  already treats as "everything is interesting".

`metadata.isIncomplete` is the graph-wide summary of whether any node is marked.

Library overrides of library methods are deliberately not enumerated. They would resolve to
library methods, which are never inlined anyway, so listing them would make good resolutions look
ambiguous without making the graph any more complete.

### 2. CFG Merging Process

Starting from the entry point method:

1. **Extract intra-procedural CFG** for current method
2. **Rename nodes** with method-specific prefixes (`m1_node_0`, `m2_node_1`, etc.)
3. **Find call sites** (statements with invoke expressions)
4. **Resolve the callee** as described above
5. **Recursively extract** callee CFGs -- each method once, however many call sites reach it
6. **Add CALL edge** from call site to callee entry, carrying the call site's return address and
   handler map (see "Call stack" below)
7. **Add RETURN edges** from all callee exits to return site
8. Once every method is in: **add EXCEPTIONAL_RETURN edges** from each `uncaught[T]` node to every
   caller handler an exception `T` can unwind to

### 3. Call Stack, Not Copies

A method body is in the graph once, so an exit node of a method with several call sites has a
RETURN edge to each of their return sites, and the graph alone cannot say which one a return
goes to. The consumer does not need it to: SWAT walks the graph with a **call stack**. On a CALL
edge it pushes the call site; at an exit it pops and resumes at that frame's `returnSite`.
Recursion works the same way -- a recursive call site gets a CALL edge back into the body it sits
in -- so there is no depth limit: `processedMethods` alone bounds construction.

The RETURN edges stay in the graph although the walk does not follow them. Backward reachability
from an assert behind a return site has to arrive at the callee through them; without them every
path before the call would be pruned.

### 4. Exceptions

An exception that leaves a method goes to that method's `uncaught[T]` node, one per exception type
(`stmtType: UncaughtException`). It is **not** an exit node: control does not resume at the
caller's return site. The node carries `exceptionType` and `exceptionTypeChain` (`T` and its
superclasses up to `Throwable`), and each CALL edge carries `handlers`, the call site's own
`{caughtType -> handler node}` map from `exceptionalSuccessors(callStmt)`. At an `uncaught[T]`
node the walk pops frames until one has a handler for a type in the chain -- first match in chain
order, i.e. the most specific handler -- and resumes there. An empty stack means the exception
leaves `main`.

Two things end up at `uncaught[T]`:

* a phantom guard's false branch with no local handler (the guard's exception type is precise);
* an explicit `throw` with no local handler, via a `THROW` edge. The thrown value is traced to
  its runtime type with `ReceiverTypeResolver`, through the definitions that *reach* the throw
  (javac reuses a thrown local's slot for catch variables). A throw caught locally gets a `THROW`
  edge to that handler instead. If the type is unknown (a rethrow of `@caughtexception`, a
  parameter) or its possible types reach different destinations, the throw node is marked
  incomplete.

`EXCEPTIONAL_RETURN` edges (`uncaught[T]` -> every handler it can unwind to, possibly several
frames up) exist for backward reachability only, like RETURN edges. They are computed with the
same handler rule the walk uses (`ExceptionHandlers`), so the two cannot disagree.

### 5. Reference Comparisons

SWAT's `RefEqualityTransformer` rewrites every `IF_ACMPEQ` / `IF_ACMPNE` into a call to
`UtilInstrumented.refEquals(a, b)` plus an `IFNE` / `IFEQ` on its result. `UtilInstrumented` is
instrumented, so the helper reports branches of its own before the rewritten comparison reports
its branch. How many depends on the runtime types of `a` and `b` (strings and cached boxes are
compared by value), so the graph cannot spell them out. SWAT flags them like callbacks instead
(`ShadowContext.inSwatHelper` in the executor), and the walk holds its place while they run. The
graph has only the comparison's own `JIfStmt`, with the key of the original `IF_ACMP*` (the
rewritten jump is an `if` at the same position). Null checks compile to `IFNULL` / `IFNONNULL` and
are left alone. (Until 2026-10 the graph had the helper's then fixed two branches as
`RefEqualityCheck` nodes; when the helper changed, every reference comparison put the walk out of
step.)

### 6. Node Metadata

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

### 7. Library Callbacks

Library calls are not expanded, but library code can call back into the application: an override
of a library method (`toString()`, a `Reader` handed to a `PushbackReader`) or a lambda passed to
library code. `CallbackTargets` collects those methods; if one of them may reach an assert (the
same reachability `ClinitTargets` computes for static initializers), every library call site is
reported in `assertionPointIds` (not marked incomplete: the walk goes on). The graph records which
callbacks did it in `metadata.callbacksReachingAssert`. `--no-callback-targets` turns this off
(unsound: an assert reached only through such a callback is then missed). SWAT's executor flags
the branches of a callback that actually runs, and the walk masks them.

### 8. Branch Keys

Every branch node (an `if`, a switch case, a phantom guard) and every call site has a `key`:
`<owner>.<name><desc>|<line>|<kind>|<ordinal>`, with kind `if`, `switch`, `arr`, `newarr`, `div` or
`call:<name><desc>`, and the ordinal the instruction's position among those of its kind on the
line, in bytecode order. A switch case appends `#<value>`, a dimension check of a multi-dimensional
allocation `#<n>`. SWAT's executor reports the same key for each branch, with the frames popped and
pushed since the previous one, and SWAT checks its walk against them and resynchronises by them.
Where the extractor cannot prove the ordinal (same-kind instructions on a line that it cannot tell
apart, see `BranchKeys`), it writes `*`; where its view of a line differs from the bytecode, no key.

### 9. Sites That Cannot Raise

The property pre-check's value analysis (`staticcheck`, see `CLAUDE.md`) says which instructions
cannot raise a RuntimeException, and `RaiseSites` puts that on the nodes as `cannotRaise`:

* a phantom guard gets `"cannotRaise": true` if the instruction with its exact branch key cannot
  raise (array access in bounds, division by a non-zero value, non-negative array size);
* a statement that may raise one itself (array access, `arraylength`, instance field access,
  reference cast, `throw`, monitors, calls) gets `true` or `false`, matched by method, line and
  kind. A line is only matched if its kinds are the same in the graph and in the bytecode; an
  unmatched statement gets `false`. For a call, only the call itself counts (a null receiver, a
  library callee); what an application callee throws is in its own statements.

Other nodes have no `cannotRaise`. `metadata.siteFacts` says which facts were used: `closed` (the
check's facts for the methods reachable from `main`, which hold because the world is closed) or
`local` (what each method shows itself); `metadata.raiseSites` counts the marked nodes.
`--no-site-facts` leaves the facts out (every statement site then gets `false`).

`metadata.runtimeExceptionPointIds` are the target points for SV-COMP's no-runtime-exception
property, as `metadata.assertionPointIds` are for valid-assert: every statement site with
`"cannotRaise": false`, and every `uncaught[T]` node of a RuntimeException type (where a guard's
failure or a throw leaves its method). An exception a caller catches still counts. A library call
that may call back into the application is such a site unless the pre-check rules out both a
callback and an exception, so callbacks need no extra points.

### 10. Data-Flow Facts (for SWAT's relevance analysis)

`DataflowFacts` puts on each node what SWAT needs to compute which branches cannot affect a target
(see `metadata.dataflowFacts`, a version number):

* `dataflow`: `[written, read]` location pairs. Locals (`l:x`), parameters (`p:0`, `p:this`) and the
  return value (`ret`) are per method; fields are `f:<name>:<type>` (no points-to analysis), array
  elements `a:<element kind>`, and `g:input` (the input cursor: every `Verifier.nondet*` reads and
  advances it), `g:lib` (all library state), `g:out`, `g:exc` (the exception in flight) are global;
  `*` is every location (reflection, unknown code).
* `control`: what the node's decision reads (branch condition, switch key, phantom guard operands,
  an assumption).
* `terminates`: the node may end the program (`System.exit`, `Runtime.halt`, `Verifier.assume`).
* `call`: for a call site with CALL edges, what each argument and the receiver read and the local
  the result goes to; SWAT links them to the callee's parameters and `ret`.

Library calls are summarised by signature (pure, output only, input, terminating, reflective, else
`g:lib` plus every array they may get plus whatever an application callback may write); statements
that may run a static initializer also get its transitive effects. Everything over-approximates:
a missing write lets SWAT slice a branch that matters.

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
  "label": "ack",
  "returnSite": "m1_node_16",
  "handlers": { "java.lang.ArithmeticException": "m1_node_20" }
}
```

```json
{
  "source": "m3_node_13",
  "target": "m1_node_16",
  "type": "RETURN"
}
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
- Orange dashed: EXCEPTION edges (SootUp-inferred; the walk does not follow them)
- Orange solid: THROW edges
- Dark red dotted: EXCEPTIONAL_RETURN edges
- Dark grey solid: Normal flow

## Limitations & Future Work

### Current Limitations

1. **Receiver Origins**: A receiver read from a field, an array, or a parameter is untraceable,
   so the call site falls back on the declared type and is usually reported as ambiguous
   - Solution: propagate types through fields and across call sites (a points-to analysis)

2. **Unmodelled exceptions**: exceptions SWAT does not guard -- a `NullPointerException`, a
   `ClassCastException`, anything thrown inside library code, Errors -- have no precise type or
   destination in the graph. They are modelled as pseudo exception types (`PseudoExceptions`):
   every statement that may throw one (calls, instance field and array accesses, casts, array
   lengths, monitors, throws; any statement for Errors) gets EXCEPTION edges to every local
   handler that might catch it and to an `uncaught[?Exception]` / `uncaught[?Error]` node, and
   each CALL edge lists its handlers that might catch each pseudo type (`unknownHandlers`). SWAT's
   summaries treat a pseudo escape as caught by any listed handler, or by none. So the paths into
   such handlers stay interesting. Where the execution goes after such an exception the graph
   cannot say; the executor reports it in the trace (`UNMODELLED_EXCEPTION`), and SWAT stops
   trusting the graph until it finds it again at a later branch by its key (see Branch Keys).
   Pseudo types are only emitted if some handler in the program could catch them

3. **Nested try blocks**: of two handlers catching `T`, the one with the more specific type is
   chosen. An inner `catch (Exception e)` really shadows an outer `catch (RuntimeException e)`,
   but SootUp's handler map has lost the order

4. **Static Initializers**: `<clinit>` is never entered, so its body is not in the graph. SWAT
   masks `<clinit>` subtrees out of the lockstep walk entirely (`DFS.py`, `mask_sa_node`) and
   treats everything inside as interesting. What must not be pruned is the path to the statement
   that first uses the class, so `ClinitTargets` marks every statement that may initialize a class
   whose initializer can reach an assert (`new`, static calls, static field accesses, also through
   superclasses; transitively through calls and other initializers) as an assertion point. Which
   use comes first is only known at run time, so every such use counts
   - Solution (for precision): add implicit call edges where a class is first used

5. **Ambiguous Calls**: Several possible targets means no CALL edge at all, so the callee bodies
   are missing from the graph even though one of them runs
   - Solution: a graph format that can express alternative callees

### Future Enhancements

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
