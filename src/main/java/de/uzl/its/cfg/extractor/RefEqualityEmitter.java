package de.uzl.its.cfg.extractor;

import de.uzl.its.cfg.model.CFGEdge;
import de.uzl.its.cfg.model.CFGNode;
import de.uzl.its.cfg.model.ControlFlowGraph;
import sootup.core.jimple.common.Value;
import sootup.core.jimple.common.constant.NullConstant;
import sootup.core.jimple.common.expr.AbstractConditionExpr;
import sootup.core.jimple.common.expr.JEqExpr;
import sootup.core.jimple.common.expr.JNeExpr;
import sootup.core.jimple.common.stmt.JIfStmt;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.core.types.PrimitiveType;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Mirrors the branches SWAT adds to every reference comparison.
 *
 * <p>SWAT's {@code RefEqualityTransformer} rewrites each {@code IF_ACMPEQ} / {@code IF_ACMPNE} in an
 * instrumented class into a call to {@code UtilInstrumented.refEquals(a, b)} followed by an
 * {@code IFNE} / {@code IFEQ} on its result. {@code UtilInstrumented} is itself instrumented, so the
 * helper reports branches of its own before the rewritten jump reports the comparison:
 *
 * <pre>
 *   refEquals(a, b):
 *      5: ifeq 14          -- if (!Util.shouldUseValueEquality(a, b)) goto 14   (branch A)
 *      8: return Objects.equals(a, b)
 *     14: if_acmpne 23     -- a == b                                            (branch B)
 *         ...
 * </pre>
 *
 * <p>So a reference comparison is two or three branch events, not one, and the graph has to spell
 * the extra ones out or the positional walk falls behind by them. Branch A is taken when reference
 * equality is used, which is when branch B follows; not taken, the helper returns straight away.
 * Either way control then reaches the comparison itself, the original {@link JIfStmt}:
 *
 * <pre>
 *   pred --&gt; refeq_A --true--&gt; refeq_B --true/false--&gt; if (a == b)
 *                |                                        ^
 *              false -------------------------------------'
 * </pre>
 *
 * <p>A comparison against {@code null} is left alone: javac compiles it to {@code IFNULL} /
 * {@code IFNONNULL}, which the transformer does not touch.
 */
public final class RefEqualityEmitter {

    private final ControlFlowGraph cfg;

    private int counter = 0;

    public RefEqualityEmitter(ControlFlowGraph cfg) {
        this.cfg = cfg;
    }

    /**
     * Adds the helper's branches ahead of every reference comparison in {@code stmts}.
     *
     * @param stmtToId the node id of each statement
     * @param entryIds where edges into each statement currently arrive (see
     *                 {@link PhantomGuardEmitter#emit})
     * @return {@code entryIds}, updated so that an edge into a reference comparison arrives at the
     *         head of its helper chain
     */
    public Map<Stmt, String> emit(Collection<Stmt> stmts, Map<Stmt, String> stmtToId,
            Map<Stmt, String> entryIds) {
        Map<Stmt, String> updated = new HashMap<>(entryIds);
        for (Stmt stmt : stmts) {
            if (!isReferenceComparison(stmt)) {
                continue;
            }
            // A statement with a phantom guard is never a comparison, so the comparison's own node
            // is where the chain has to end.
            String comparisonId = stmtToId.get(stmt);
            assert comparisonId.equals(entryIds.get(stmt))
                    : "Reference comparison unexpectedly guarded: " + stmt;
            int line = PhantomGuardEmitter.lineNumberOf(stmt);
            String valueCheckId = "refeq_" + counter++;
            String identityCheckId = "refeq_" + counter++;
            AbstractConditionExpr condition = ((JIfStmt) stmt).getCondition();
            cfg.addNode(new CFGNode(valueCheckId, "refEquals: !shouldUseValueEquality("
                    + condition.getOp1() + ", " + condition.getOp2() + ")", "RefEqualityCheck", line));
            cfg.addNode(new CFGNode(identityCheckId, "refEquals: " + condition.getOp1() + " != "
                    + condition.getOp2(), "RefEqualityCheck", line));
            cfg.addEdge(new CFGEdge(valueCheckId, identityCheckId, CFGEdge.EdgeType.TRUE_BRANCH, "true"));
            cfg.addEdge(new CFGEdge(valueCheckId, comparisonId, CFGEdge.EdgeType.FALSE_BRANCH, "false"));
            cfg.addEdge(new CFGEdge(identityCheckId, comparisonId, CFGEdge.EdgeType.TRUE_BRANCH, "true"));
            cfg.addEdge(new CFGEdge(identityCheckId, comparisonId, CFGEdge.EdgeType.FALSE_BRANCH, "false"));
            updated.put(stmt, valueCheckId);
        }
        return updated;
    }

    /** Whether {@code stmt} is an {@code IF_ACMPEQ} / {@code IF_ACMPNE} in the bytecode. */
    static boolean isReferenceComparison(Stmt stmt) {
        if (!(stmt instanceof JIfStmt)) {
            return false;
        }
        AbstractConditionExpr condition = ((JIfStmt) stmt).getCondition();
        if (!(condition instanceof JEqExpr) && !(condition instanceof JNeExpr)) {
            return false;
        }
        return isReference(condition.getOp1()) && isReference(condition.getOp2());
    }

    private static boolean isReference(Value value) {
        return !(value instanceof NullConstant) && !(value.getType() instanceof PrimitiveType);
    }
}
