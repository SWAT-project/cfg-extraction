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

## The one invariant that matters

SWAT walks this graph **in lockstep** with the execution tree, pairing branch for branch **by
position**. No ids are ever compared. If the graph has a branch the execution doesn't, or the
other way round, every later pairing on that path is off. This fails silently: it prunes the wrong
paths and can produce false SAFE verdicts.

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
