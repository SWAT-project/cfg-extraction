package de.uzl.its.cfg.extractor;

import de.uzl.its.cfg.model.CFGEdge;
import de.uzl.its.cfg.model.CFGNode;
import de.uzl.its.cfg.model.ControlFlowGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sootup.core.jimple.common.expr.JNewExpr;
import sootup.core.jimple.common.stmt.JAssignStmt;
import sootup.core.jimple.common.stmt.JIfStmt;
import sootup.core.jimple.common.stmt.JThrowStmt;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.core.jimple.javabytecode.stmt.JSwitchStmt;
import sootup.core.model.Body;
import sootup.core.model.SootMethod;
import sootup.core.typehierarchy.TypeHierarchy;
import sootup.core.types.ClassType;
import sootup.java.core.views.JavaView;

import java.util.*;

/**
 * Extracts intra-procedural control flow graphs (single method analysis).
 * Each method gets its own CFG showing the flow of statements within that method.
 */
public class IntraProceduralCFGExtractor implements CFGExtractor {

    private static final Logger logger = LoggerFactory.getLogger(IntraProceduralCFGExtractor.class);

    /**
     * An extracted CFG together with the node each statement became.
     *
     * <p>Callers that need to get from a {@link Stmt} back to its node cannot search the CFG by
     * statement text: a method may contain several textually identical statements (two
     * {@code writer.println(name)} calls in different branches produce the same Jimple), and they
     * are separate nodes. {@link Stmt} has no structural equality, so this map distinguishes them
     * by identity the way the extraction itself does.
     */
    public record MethodCFG(ControlFlowGraph cfg, Map<Stmt, String> nodeIds) {
    }

    @Override
    public ControlFlowGraph extractCFG(SootMethod method, JavaView view) {
        return extractMethodCFG(method, view).cfg();
    }

    /** As {@link #extractCFG}, but also reporting which node each statement became. */
    public MethodCFG extractMethodCFG(SootMethod method, JavaView view) {
        logger.info("Extracting intra-procedural CFG for method: {}", method.getSignature());

        String methodSig = method.getSignature().toString();
        String className = method.getDeclaringClassType().getClassName();

        ControlFlowGraph cfg = new ControlFlowGraph(methodSig, className, getAnalysisType());

        // Check if method has a body (not abstract/native)
        if (!method.hasBody()) {
            logger.warn("Method {} has no body (abstract or native)", methodSig);
            return new MethodCFG(cfg, Map.of());
        }

        Body body = method.getBody();
        // SootUp's StmtGraph was renamed to sootup.core.graph.ControlFlowGraph in 3.0.0;
        // it is fully qualified here because the name clashes with our own model class.
        sootup.core.graph.ControlFlowGraph<?> stmtGraph = body.getControlFlowGraph();
        var stmts = stmtGraph.getStmts();

        // Create nodes for each statement
        Map<Stmt, String> stmtToId = new HashMap<>();
        int nodeCounter = 0;

        for (Stmt stmt : stmts) {
            String nodeId = "node_" + nodeCounter++;
            stmtToId.put(stmt, nodeId);

            CFGNode node = createNode(nodeId, stmt);
            cfg.addNode(node);

            logger.debug("Created node: {} for stmt: {}", nodeId, stmt);
        }

        TypeHierarchy hierarchy = view.getTypeHierarchy();
        SafeStmtExceptionAnalyzer exceptionAnalyzer = new SafeStmtExceptionAnalyzer(hierarchy);

        // Insert SWAT's phantom branches ahead of the risky statements. Every edge that targets a
        // statement from here on has to go through entryIds so that it lands on the head of that
        // statement's guard chain instead of bypassing it.
        Map<Stmt, String> entryIds =
                new PhantomGuardEmitter(cfg, stmtGraph, hierarchy).emit(stmts, stmtToId);

        SwitchChainEmitter switchEmitter = new SwitchChainEmitter(cfg);

        // Set entry node (the first statement, or the first guard protecting it)
        cfg.setEntryNode(entryIds.get(stmtGraph.getStartingStmt()));

        // Create edges based on control flow
        for (Stmt stmt : stmts) {
            String sourceId = stmtToId.get(stmt);
            List<Stmt> successors = stmtGraph.successors(stmt);
            Set<ClassType> possibleExceptions = unguardedExceptions(
                    exceptionAnalyzer.mightThrow(stmt, stmtGraph), stmt, hierarchy);

            // Exceptional flow: one edge per handler that might catch something this statement
            // can throw. Several caught types can share a handler statement, so the targets are
            // deduplicated rather than emitting one edge per matching caught type.
            Set<Stmt> reachableHandlers = new LinkedHashSet<>();
            for (Map.Entry<ClassType, Stmt> handler : stmtGraph.exceptionalSuccessors(stmt).entrySet()) {
                if (mightReachHandler(handler.getKey(), possibleExceptions, hierarchy)) {
                    reachableHandlers.add(handler.getValue());
                }
            }
            for (Stmt handler : reachableHandlers) {
                cfg.addEdge(new CFGEdge(sourceId, entryIds.get(handler), CFGEdge.EdgeType.EXCEPTION));
            }

            // Normal flow, independent of the exceptional flow above: stmtGraph.successors() is
            // unexceptional-only, and a statement inside a try block both falls through to its
            // successor and can branch to its handler.
            if (successors.isEmpty()) {
                // An exit node (return, or a throw that no handler in this method catches). A
                // throw that *is* caught locally keeps flowing inside the method, so it is not
                // an exit -- treating it as one would give it a bogus inter-procedural RETURN
                // edge back into the caller.
                if (reachableHandlers.isEmpty()) {
                    cfg.addExitNode(sourceId);
                }
            } else if (stmt instanceof JIfStmt) {
                // Conditional branch - distinguish true/false branches
                handleConditionalBranch((JIfStmt) stmt, successors, sourceId, entryIds, cfg);
            } else if (stmt instanceof JSwitchStmt) {
                // A switch is one statement but several branches: SWAT tests the cases one at a
                // time, so the graph spells the same tests out rather than fanning out here.
                switchEmitter.emit((JSwitchStmt) stmt, sourceId, body, entryIds);
            } else {
                // Normal flow, including branching statements (goto) whose successors need no
                // further labelling
                for (Stmt successor : successors) {
                    cfg.addEdge(new CFGEdge(sourceId, entryIds.get(successor), CFGEdge.EdgeType.NORMAL));
                }
            }
        }

        // Find assertions
        var assertionPointIds = new ArrayList<String>();
        for (var throwStmt : stmts) {
            if (throwStmt instanceof JThrowStmt) {
                var thrownValue = throwStmt.getUses().get(0);

                // find assignment of thrown value
                for (var assignStmt : stmts) {
                    if (assignStmt instanceof JAssignStmt) {
                        var leftOp = ((JAssignStmt) assignStmt).getLeftOp();
                        var rightOp = ((JAssignStmt) assignStmt).getRightOp();

                        if (leftOp.equals(thrownValue) && rightOp instanceof JNewExpr) {
                            var newExpr = (JNewExpr) rightOp;
                            if (newExpr.getType().getFullyQualifiedName().equals("java.lang.AssertionError")) {
                                assertionPointIds.add(stmtToId.get(assignStmt));
                            }
                        }
                    }
                }
            }
        }

        // Add metadata
        cfg.addMetadata("statementCount", stmts.size());
        cfg.addMetadata("localCount", body.getLocalCount());
        cfg.addMetadata("assertionPointIds", assertionPointIds);

        logger.info("Extracted CFG with {} nodes and {} edges",
                cfg.getNodes().size(), cfg.getEdges().size());

        return new MethodCFG(cfg, stmtToId);
    }

    private CFGNode createNode(String nodeId, Stmt stmt) {
        String stmtString = stmt.toString();
        String stmtType = stmt.getClass().getSimpleName();

        return new CFGNode(nodeId, stmtString, stmtType, PhantomGuardEmitter.lineNumberOf(stmt));
    }

    /**
     * The exceptions of {@code stmt} that SWAT does <em>not</em> turn into a phantom branch.
     *
     * <p>An exception already represented by a guard is dropped so that the two edge families stay
     * disjoint: a guarded array store must not reach its handler through both a
     * {@link CFGEdge.EdgeType#PHANTOM_FALSE_BRANCH} and an {@link CFGEdge.EdgeType#EXCEPTION}
     * edge. Matching is by subtype because the two sides name the exception differently -- a guard
     * knows the precise {@code ArrayIndexOutOfBoundsException} while SootUp only infers
     * {@code IndexOutOfBoundsException} -- so an inferred type is dropped when the guard's
     * exception is that type or a subtype of it.
     *
     * <p>What survives is what SWAT ignores, most notably the {@code NullPointerException} that
     * accompanies every array access, since {@code checkArrayBounds} never null-checks.
     */
    private Set<ClassType> unguardedExceptions(Set<ClassType> inferredExceptions, Stmt stmt,
            TypeHierarchy hierarchy) {
        List<SwatGuardClassifier.Guard> guards = SwatGuardClassifier.guardsOf(stmt);
        if (guards.isEmpty()) {
            return inferredExceptions;
        }
        Set<ClassType> unguarded = new LinkedHashSet<>();
        for (ClassType inferred : inferredExceptions) {
            boolean covered = guards.stream().anyMatch(guard -> {
                ClassType guarded = guard.kind().getExceptionType();
                return guarded.equals(inferred) || hierarchy.isSubtype(inferred, guarded);
            });
            if (!covered) {
                unguarded.add(inferred);
            }
        }
        return unguarded;
    }

    /**
     * Whether a handler catching {@code caughtType} might be reached from a statement that can
     * throw {@code thrownTypes}.
     *
     * <p>Handler types must be matched against thrown types through the type hierarchy rather
     * than by identity: SootUp reports the precise implicit exceptions of a statement (e.g.
     * {@code IndexOutOfBoundsException} for an array store), which almost never equal the type
     * a handler declares (e.g. {@code catch (Exception e)}), so an identity comparison finds no
     * match and the CFG ends up with no exceptional edges at all.
     *
     * <p>Both directions of the subtype relation count. A thrown type below the caught type is
     * definitely caught. A thrown type <em>above</em> the caught type may also be caught,
     * because the thrown set is an over-approximation: where SootUp cannot pin down the runtime
     * type it reports a supertype such as {@code java.lang.Throwable}, and the actual instance
     * may well be the caught type.
     *
     * <p>The equality case has to be tested separately, because {@link TypeHierarchy#isSubtype}
     * is a <em>strict</em> subtype relation: it has no reflexive case, so
     * {@code isSubtype(X, X)} is false and an exactly matching handler (a
     * {@code catch (IndexOutOfBoundsException e)} around an array access) would be missed.
     */
    private boolean mightReachHandler(ClassType caughtType, Set<ClassType> thrownTypes,
            TypeHierarchy hierarchy) {
        return thrownTypes.stream().anyMatch(thrownType ->
                caughtType.equals(thrownType)
                        || hierarchy.isSubtype(caughtType, thrownType)
                        || hierarchy.isSubtype(thrownType, caughtType));
    }

    private void handleConditionalBranch(JIfStmt ifStmt, List<Stmt> successors,
            String sourceId, Map<Stmt, String> entryIds,
            ControlFlowGraph cfg) {
        // For if statements, we have two successors:
        // - The target of the if (true branch)
        // - The fall-through (false branch)

        // Label successors by their index, which JIfStmt pins down: the fall-through
        // (false branch) comes first, the branch target (true branch) second.
        for (int i = 0; i < successors.size(); i++) {
            Stmt successor = successors.get(i);
            String targetId = entryIds.get(successor);
            assert targetId != null : "Successor statement not found in entryIds map for conditional branch: "
                    + successor + " (source: " + ifStmt + ")";

            CFGEdge.EdgeType edgeType;
            String label;

            if (i == JIfStmt.FALSE_BRANCH_IDX) {
                edgeType = CFGEdge.EdgeType.FALSE_BRANCH;
                label = "false";
            } else {
                // JIfStmt.TRUE_BRANCH_IDX and any further successors: true branch
                edgeType = CFGEdge.EdgeType.TRUE_BRANCH;
                label = "true";
            }

            CFGEdge edge = new CFGEdge(sourceId, targetId, edgeType, label);
            cfg.addEdge(edge);
        }
    }

    @Override
    public ControlFlowGraph.AnalysisType getAnalysisType() {
        return ControlFlowGraph.AnalysisType.INTRA_PROCEDURAL;
    }
}
