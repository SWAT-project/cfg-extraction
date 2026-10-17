# cfg-extraction

SootUp-based extractor for control-flow graphs of Java bytecode. Its main consumer is SWAT
(`../SWAT/symbolic-explorer`), which loads `<Target>_main_interprocedural.json` to prune symbolic
execution. See `INTER_PROCEDURAL.md` for the graph format and how it is built.

## Build and run

```bash
./gradlew fatJar
java -ea -jar build/libs/cfg-extractor-1.0-SNAPSHOT-all.jar <classes-dir> <out-dir> <MainClass> main inter
```

- Always use `-ea` when testing. The code relies on `assert` for invariant checks, and those
  failures are real bugs.
- SWAT runs the jar straight from `build/libs/` (without `-ea`, with a 120 s timeout), so
  rebuilding changes what the next SWAT run uses.
- `target/` holds small smoke-test programs, one entry class per file. Compile them in place with
  `javac -g -d target/ target/X.java`. `.class` files are gitignored.
- `scripts/regression_sweep.py` runs a jar over the SV-COMP benchmarks and checks that SWAT can
  load every graph. Compare two sweeps with `--compare base.jsonl new.jsonl`. Keep a copy of the
  old jar before changing anything, so you have a baseline.

## Visualization

Run the visualizer with its own venv, not the system Python, which lacks networkx and matplotlib:

```bash
visualize/.venv/bin/python visualize/visualize_cfg.py <graph.json> [out.svg] [--swat-marking]
```

- `--swat-marking` shows what SWAT's static pre-analysis prunes. It is computed with SWAT's own
  `SAGraph`, found at `../SWAT/symbolic-explorer`; override that with `--swat-explorer`.
- The graphviz backend also needs the `dot` binary.

## The one invariant that matters

SWAT walks this graph **in lockstep** with the execution tree, pairing branch for branch **by
position**. If the graph has a branch the execution doesn't, or the other way round, every later
pairing on that path is off: it prunes the wrong paths and can produce false SAFE verdicts. SWAT
checks the pairing where both sides have a key (`BranchKeys`, the node's `key` field) and stops
trusting the graph at a mismatch, but a branch without a key goes unchecked, so the structure must
still be right.

Branch and call-site keys are `owner.name(desc)|line|kind|ordinal`, and must equal what SWAT's
executor computes from bytecode (`instrument/BranchKeys`). SootUp keeps no bytecode offsets and its
statement order is not the bytecode order (not even within a line), so `BranchKeys` reads the class
file and only keeps an ordinal it can prove; otherwise it writes `*` or no key.

So every change to the branch structure must match what SWAT's executor actually reports. Check
`../SWAT/symbolic-executor` (mainly `SymbolicInstructionVisitor`, and the transformers under
`instrument/`), not intuition. Known sources of branch events:

- every `if` bytecode instruction;
- one event per switch case tested;
- implicit guards: integer division, array access, array allocation;
- SWAT's reference-equality rewrite: every `IF_ACMPEQ`/`IF_ACMPNE` becomes a call to the
  instrumented helper `UtilInstrumented.refEquals`, which emits extra branches before the
  comparison's own.

Concrete (non-symbolic) branches count too. Invokes, returns, `goto` and `athrow` do not.

Where the graph cannot describe what runs, mark the node incomplete with `markIncomplete`. That
flags the node itself and also reports it as an assertion point, so SWAT stops trusting the graph
beyond the node but still treats the paths leading to it as interesting.

Assertion points (`new java.lang.AssertionError`) are only the first kind of target, chosen
because they are easy to test. Later targets include shell calls, SQL statement building and
uncaught exceptions, so keep target detection separate from the graph construction and avoid
designs that only work for `AssertionError`.

Exceptions without a phantom guard (casts, null dereferences, exceptions thrown by library calls,
Errors) are modelled as pseudo exception types (`PseudoExceptions`, see `INTER_PROCEDURAL.md`):
extra EXCEPTION edges and `uncaught[?...]` escapes for backward marking only. The walk never
follows them; after such an exception SWAT stops trusting the graph until it finds it again at a
later branch by key (resync, see the branch keys below).

## SootUp traps

- **No body interceptors.** The view is built with `List.of()` on purpose. Interceptors reshape
  Jimple, and the graph must match the bytecode statement for statement.
- **`TypeHierarchy.isSubtype(a, b)` takes the supertype first**, and it is strict (no reflexive
  case). Use `ReceiverTypeResolver.isSubtypeOf`, or check equality separately.
- **`Stmt` has no structural equality.** Maps keyed by `Stmt` work by identity, which is what you
  want: a method can contain several identical-looking statements. Never look nodes up by
  statement text.
- **javac reuses local variable slots.** One Jimple local can hold unrelated values in different
  scopes, so a union over all of a local's definitions can mislead. Filter by type or use the
  definitions that actually reach the use.
- `exceptionalSuccessors()` returns an unordered map; handler order is lost.
- The JDK is in the view only so types resolve. Only application classes are expanded, which
  mirrors SWAT's instrumentation scope (`instrumentation.excludePackages` in
  `../SWAT/targets/sv-comp/sv-comp.cfg`).

## Conventions

- Gson serializes the model classes reflectively. A new field shows up in the JSON automatically,
  and `null` fields are left out.
- When you add a synthetic node or edge type, also add it to `visualize/graphviz_render.py`, which
  styles nodes by `stmtType` and edges by `type`.
