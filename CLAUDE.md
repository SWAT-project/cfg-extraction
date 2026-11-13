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
  rebuilding changes what the next SWAT run uses. SWAT's SV-COMP archive ships the fat jar too
  (as `cfg-extraction/cfg-extractor.jar`, built in CI at the tag in `SWAT/cfg-extraction.ref`).
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
checks the pairing where both sides have a key (`BranchKeys`, the node's `key` field); at a
mismatch (a desync) its walk is *lost*: it prunes nothing until it finds the graph again by key.
A branch without a key goes unchecked, though, so the structure must still be right.

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
  instrumented helper `UtilInstrumented.refEquals` and a jump on its result. The helper's branches
  depend on the operands' runtime types, so they are not in the graph: SWAT flags them like
  callbacks (`ShadowContext.inSwatHelper`) and the walk holds its place. The comparison is its own
  `if` (same kind and ordinal as the original `IF_ACMP*`).

Concrete (non-symbolic) branches count too. Invokes, returns, `goto` and `athrow` do not.

Where the graph cannot describe what runs, mark the node incomplete with `markIncomplete`. That
flags the node itself and also reports it as an assertion point, so SWAT's walk is lost beyond
the node but still treats the paths leading to it as interesting.

Assertion points (`new java.lang.AssertionError`) are only the first kind of target, chosen
because they are easy to test. Later targets include shell calls, SQL statement building and
uncaught exceptions, so keep target detection separate from the graph construction and avoid
designs that only work for `AssertionError`.

`Verifier.nondetObject(type, factory)` is not a leaf: SWAT runs it as `factory.createObject()`, so
`ReceiverTypeResolver` dispatches it on the factory argument and `BranchKeys` keys it as
`call:createObject()Ljava/lang/Object;`, as the executor reports it (in unprepared classes; prepared
ones call `createObject()` already, see "Prepared classes").

Library calls are leaf calls, but if an application method library code may call back (an
override of a library method, a lambda) can reach an assert, every library call counts as an
assertion point (`CallbackTargets`; off with `--no-callback-targets`). A call site with several
known application targets gets a CALL edge to each (see `INTER_PROCEDURAL.md`, Dispatch
Resolution).

Exceptions without a phantom guard (casts, null dereferences, exceptions thrown by library calls,
Errors) are modelled as pseudo exception types (`PseudoExceptions`, see `INTER_PROCEDURAL.md`):
extra EXCEPTION edges and `uncaught[?...]` escapes for backward marking only. The walk never
follows them; after such an exception SWAT's walk is lost until it finds the graph again at a
later branch by key (resync, see the branch keys below).

**Relevance and loop summarization (merged into `master` 2026-10-27, tag `v0.2.0`).** Nodes carry data-flow facts (`DataflowFacts`: which abstract locations each
statement writes and reads, call arguments, terminators such as `System.exit` and
`Verifier.assume`). SWAT uses them to compute which branches are irrelevant to the targets (a
static slice) and stops flipping those. Loop summarization lives in SWAT's executor
(`symbolic/summary`); it uses the relevance analysis to decide whether a summarized loop's other
effects can matter (`Relevance.summary_ok`), so it needs these facts too.
Limitations of the facts: all library state is one location `g:lib` (a finer split, by type
family or by allocation site with an escape check, belongs in `DataflowFacts`; see SWAT's
swat-benchmarks/RESULTS.md, relevance "Open"; hand-written library facts split it by family, see
"Hand-written facts"), fields are by name and type (no points-to analysis).

## Prepared classes

SWAT's driver can run the extractor on *prepared classes*: SWAT's semantic rewrites (no-cache,
reference equality, SV-COMP; SWAT's `de.uzl.its.swat.prepare.Prepare`) applied ahead of time, so
that the graph, keys, site facts and loop facts describe exactly the code the executor runs.
Prepared classes carry the invisible annotation `Lde/uzl/its/swat/Prepared;`. The extractor must
also keep working on unprepared classes (SWAT's fallback when preparation fails).

- **What the extractor sees:** calls into SWAT's classes, which are not on its classpath. Each is a
  leaf library call; `staticcheck.SwatHelpers` says what it stands for: SWAT's
  `Verifier.nondetX(J)` (input) and `assume(Z)` (terminator), `SystemExit` (`System.exit`,
  `Runtime.exit/halt`), `Intrinsics.liftValue` (identity), `UtilInstrumented.refEquals` and
  `intern`, and bookkeeping without effect (`Witness.recordValue/recordThrow`,
  `RefIdentity.markDeInterned`). Also `new String(literal)` and `new Integer(..)` etc. in place of
  literals and `valueOf`, `ObjectFactory.createObject()` in place of `nondetObject`, and SWAT's
  `Socket` mock (an ordinary library class).
- **Where they are modelled:** `DataflowFacts` (inputs, terminators, no effects), callback call
  sites (`DataflowFacts.cannotCallBack`: the helpers, except the exits, and pure library calls are
  not), `PseudoExceptions` (the helpers SWAT adds throw nothing), the pre-check's site facts
  (`ExceptionFreedomCheck` models the helpers like the SV-COMP `Verifier`, so the world stays
  closed; `Runtime.exit/halt` stay unmodelled as in the original), and `BranchKeys` (an `if` on a
  `refEquals` result has the signature `==ref`/`!=ref`). Change `SwatHelpers` with SWAT's rewrites.
- **The lockstep invariant holds without special cases:** the `IFEQ`/`IFNE` after `refEquals` is
  the branch the executor reports for the original `IF_ACMP*` (same kind, same ordinal); the
  inserted `dup`s and helper calls add no branch. On 64 sampled tasks the prepared graphs have the
  same branches, branch keys, assertion points, runtime-exception points and terminators as the
  original ones; only call nodes are added.
- **Special cases that only matter for unprepared classes** (dead on prepared ones, kept for the
  fallback): `Verifier.nondetObject` dispatched on the factory and keyed as `createObject`
  (`ReceiverTypeResolver`, `BranchKeys`, `DataflowFacts`); the `IF_ACMP*` signature in
  `BranchKeys`; the SV-COMP `Verifier` and `System.exit`/`Runtime.exit/halt` in `DataflowFacts`.
- **The property pre-check proper** runs on the original classes in SWAT; on prepared ones it gives
  the same result. `scripts/regression_sweep.py --prepare <symbolic-executor.jar>` sweeps the
  benchmarks on prepared classes.

## Property pre-check (`staticcheck`)

`de.uzl.its.cfg.staticcheck.ExceptionFreedomCheck` is a separate entry point: a conservative ASM pass
over the whole task classpath, from `Main.main`, that proves or fails to prove `exception_free`
(no-runtime-exception) and `assertion_free` (valid-assert). It builds no SootUp view (about 0.2 s
instead of the extractor's 5 s start-up) and does not use the graph. It moved here from SWAT's
executor (issue SWAT-project/SWAT#93).

```bash
./gradlew precheckJar     # build/libs/cfg-extractor-precheck.jar: staticcheck and ASM only
java -cp build/libs/cfg-extractor-precheck.jar de.uzl.its.cfg.staticcheck.ExceptionFreedomCheck <classpath...>
```

- **Output:** one JSON line: `tool`, `version` (git describe at build time), `exception_free`,
  `reason`, `assertion_free`, `assertion_reason`. SWAT reads it in `svcomp/PropertyPreCheck.py`;
  keep the fields stable.
- **A proof is a SAFE verdict without exploring, and skips all of SWAT's downgrades,** so a wrong
  proof is a false SAFE. Everything the check cannot prove must stay "not proven": closed world
  (application code, the modelled `Verifier`, `JdkModel`'s whitelist), no reflection or
  `invokedynamic` beyond string concatenation, platform-package classes on the classpath fail.
  Do not port it onto SootUp (dispatch, exceptional flow and local types are too loose for a proof).
- **Site facts:** `ExceptionFreedomCheck.siteFacts` (`--sites` on the command line) says per
  instruction whether it can raise a RuntimeException, for the graph (`SiteFacts`): guards by
  their exact branch key, statement sites by method, line and kind. Under a closed world the
  methods reachable from `Main.main` use the check's facts; everything else only what the method
  shows itself, which holds in any world. A site wrongly named safe lets SWAT prune a violation.
- **Tests:** `./gradlew test` runs `ExceptionFreedomCheckSpec` (Spock): the counterexamples from
  the adversarial reviews, which must never be proven. Add every new counterexample there.
  `SiteFactsSpec` covers the site facts.
- **Release gate:** `scripts/precheck_gate.py --jar build/libs/cfg-extractor-precheck.jar` runs it
  over sv-benchmarks and fails on any proof that contradicts an expected verdict. It must pass
  with 0 contradictions before a release.
- **Releases:** SWAT ships the jar in its SV-COMP archive, built in CI at the tag named in
  `SWAT/cfg-extraction.ref`. To release: pass the gate, tag, push the tag, then move the ref.
  `v0.1.0` is the pre-check alone; `v0.2.0` adds the data-flow facts for SWAT's relevance analysis
  (same pre-check). The gate needs PyYAML.
- **License:** MIT (`LICENSE`); both jars carry it (`META-INF/LICENSE`, in the fat jar
  `META-INF/LICENSE-cfg-extraction`). The repository is public, so SWAT's CI checks it out
  without a token.
- **Bundled dependencies:** both jars list what they bundle in `META-INF/swat-third-party.json`
  (`precheckComponentList`, `fatJarComponentList`: license and URL from each POM), from which
  SWAT writes the archive's THIRD-PARTY-LICENSES.md; an unmapped license fails its packaging.
  Since the fat jar is redistributed, no dependency may be GPL: graph4j (GPL, from
  `sootup.callgraph`, only used by its `GraphBasedCallGraph`) is excluded, and
  `sootup.analysis.interprocedural` (heros, functionaljava) is not a dependency since nothing uses
  it. Check the list (`unzip -p build/libs/cfg-extractor-1.0-SNAPSHOT-all.jar
  META-INF/swat-third-party.json`) after adding or upgrading a dependency.

## Loop facts (`loops`)

`de.uzl.its.cfg.loops` holds the loop analyses SWAT's executor uses: `LoopHeads` (the loop heads
and the locals defined there, for loop-state matching) and `LoopSummaries` with `BodyAnalysis` and
`TaintCheck` (diamonds and counting loops the executor merges into formulas). They moved here from
the executor on 2026-11-04. ASM only, no SootUp, no other dependency.

- **Two consumers, one implementation.** `Main` writes `loop_facts.json` (`LoopFactsJson`) into the
  output directory before building the SootUp view (`--no-loop-facts` skips it), and SWAT's
  executor bundles `build/libs/cfg-extractor-loops.jar` (`./gradlew loopsJar`: these classes
  only) to read the file and to compute the facts of methods the file lacks. A change here changes
  both after a rebuild of the executor (SWAT's `cfgLoopsJar` task rebuilds the jar when these
  sources change).
- **Facts in the class files** (since 2026-11-11): with `--annotate-loop-facts <dir>:...` the
  extractor also writes each method's facts into the class files of those directories, in place,
  as an invisible method annotation `@de.uzl.its.swat.facts.LoopFacts(version, hash, facts)`
  (`LoopFactsAnnotations`; `facts` is the method's file entry in chunks). SWAT passes its prepared
  copies, never the task's own classes, and its executor prefers the annotation to the file. The
  annotation name is in SWAT's namespace so that the executor's shadow jar does not relocate it.
- **Facts are keyed by code, not by name alone.** Each method's entry has `LoopFacts.hash`, over its
  real instructions; the executor ignores facts whose hash differs. Bump `LoopFacts.VERSION` when
  the meaning or the format of the facts changes.
- **Heads hash live locals only** (`LoopHeads.liveIn`, since `VERSION` 2): a local dead at the head
  is left out; liveness follows the analyzer's control flow, exception edges included.
- **Call sites** (`LoopHeads.callSites`, `"calls"`, since `VERSION` 3): the calls (not into the
  JDK, not in constructors) whose operand stack holds only the call's receiver and arguments, by
  their ordinal among the method's method-call instructions, with the locals live after the call
  (normal and exceptional edges). The executor records them at the call, so that a head in a
  callee is hashed with its callers' frames (SWAT's `LoopStateMatcher`).
- **Positions.** Jumps are named by their ordinal among the method's jump instructions, labels by
  the number of real instructions before them (`Positions`), so that the facts do not depend on
  how the class was read (frames, debug info). Join ids are unique per method.
- **TaintCheck encodes what the executor keeps symbolic** (merged values must not reach calls,
  heap or array stores, array indices or sizes, returns). If the executor starts concretizing
  elsewhere, this must follow, or summaries become unsound.
- **Tests:** `./gradlew test --tests 'de.uzl.its.cfg.loops.*'`. `LoopFactsSpec` checks the
  round trip through the file and that facts and hash do not depend on how a class was read.

## Hand-written facts (`facts`, `--user-facts`)

SWAT's user facts (since 2026-11-13 also in the extractor): annotations of SWAT's annotations
module in the program's class files, and library facts in a stub file. Read only with
`--user-facts` (`facts/UserFacts.read`, ASM, prepared or original classes alike), which SWAT passes
only with its own `--user-facts`, never for SV-COMP. Without the option nothing here changes the
graph.

- **`@Target`** (method): its entry node is an assertion point. The property pre-check reads it
  too (`ExceptionFreedomCheck.assertionCreation`: a reachable `@Target` method is not
  assertion-free), with or without the option. SWAT's prepared classes start the method with a call
  to `UserFacts.target` (an unmodelled SWAT helper: open world for the pre-check), and keep its
  code.
- **`@Irrelevant`** (class, method, constructor) and **`Swat.beginIrrelevant/endIrrelevant`**
  regions (the lines strictly between the two calls): their nodes get `userFact`, named as SWAT's
  executor names the fact. SWAT's relevance analysis checks and uses them (SWAT's CLAUDE.md).
- **`Swat.summary()`** (or `UserFacts.summary(String)` in prepared classes): the `if` on its result
  gets `deadSide` (the edge type of the loop's side, which never runs while SWAT runs with user
  facts) and `summaryFact`. SWAT replaces that edge's target by a dead end. The branch stays, so
  the lockstep walk is unchanged.
- **`@NotCalledBack`** (method): left out of `CallbackTargets.candidates`, so it neither makes
  library calls assertion points nor adds callback effects.
- **Library facts** (`facts/LibraryFacts`): `swat-library-facts.txt` at the root of a classpath
  directory (Prepare copies it), one library method per line with `reads=`/`writes=` families,
  `arrays`, `nocallback`, `pure`. `DataflowFacts`: a call with facts reads and writes
  `g:lib:<family>` (writes are read too: a may-write), its arguments flow into its result, and
  unless `nocallback` it gets the callbacks' effects; every other library call, and every library
  constructor, reads and writes `g:lib` and every family. `cannotCallBack` is true for
  `nocallback`. A malformed line rejects the file (the extraction fails, SWAT runs without SA).
- **Metadata:** `userFacts` lists the facts read and the library entries used (SWAT counts those,
  and `@NotCalledBack`, as assumptions of a SAFE verdict).
- **Not done** (2026-11-13, see SWAT's RESULTS.md): aliasing facts (locations have no object
  identity to refine), dispatch facts, exception facts (`@NoThrow`), hidden entry points, input
  domains, loop invariants.
- **Tests:** `facts/UserFactsSpec` (the stub format, the annotation reader); end to end SWAT's
  swat-benchmarks `extractor-facts`.

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
