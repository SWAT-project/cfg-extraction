package de.uzl.its.cfg.extractor;

import sootup.core.graph.BasicBlock;
import sootup.core.graph.ControlFlowGraph;
import sootup.core.jimple.common.Local;
import sootup.core.jimple.common.Value;
import sootup.core.jimple.common.constant.IntConstant;
import sootup.core.jimple.common.expr.JNewArrayExpr;
import sootup.core.jimple.common.ref.JArrayRef;
import sootup.core.jimple.common.ref.Ref;
import sootup.core.jimple.common.stmt.JAssignStmt;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.core.typehierarchy.TypeHierarchy;
import sootup.core.types.ClassType;
import sootup.core.types.ReferenceType;
import sootup.core.types.UnknownType;
import sootup.java.core.exceptions.ExceptionInferRefVisitor;
import sootup.java.core.exceptions.ExceptionInferResult;
import sootup.java.core.exceptions.StmtExceptionAnalyzer;

import java.util.HashSet;
import java.util.Set;

/**
 * Wrapper around SootUp's {@link StmtExceptionAnalyzer} that works around an upstream NPE.
 *
 * <p>SootUp 3.0.1 (latest release; still unfixed on upstream {@code develop}) only initialises
 * the {@code result} field of {@code ExceptionInferExprVisitor} in {@code caseNewArrayExpr()}
 * when the array's base type is a {@link ReferenceType}:
 *
 * <pre>
 *   if (expr.getBaseType() instanceof ReferenceType) {
 *     result = new ExceptionInferResult(ErrorType.RESOLVE_CLASS_ERROR);
 *   }
 * </pre>
 *
 * <p>For a primitive base type ({@code new int[4]}) {@code result} stays null, so a statement like
 * {@code arr = new int[4]} either dereferences null inside {@code caseNewArrayExpr} (when the size
 * is a {@link Local}) or returns null and blows up in {@code ExceptionInferResult.addExceptions}
 * (when the size is a constant).
 *
 * <p>This class recomputes the exception set for exactly those statements, mirroring what SootUp
 * would have produced had the field been initialised, and delegates everything else unchanged.
 * The recomputed set is a plain union rather than SootUp's subtype-minimised set; callers only ever
 * intersect it against handler types, so redundant supertypes are harmless.
 */
public class SafeStmtExceptionAnalyzer {

    /** {@code ExceptionInferResult.createDefaultResult()} -- every statement may throw these. */
    private static final Set<ClassType> DEFAULT_EXCEPTIONS = Set.of(
            ExceptionInferResult.ErrorType.VM_ERROR,
            ExceptionInferResult.ErrorType.THREAD_DEATH);

    private final StmtExceptionAnalyzer delegate;

    public SafeStmtExceptionAnalyzer(TypeHierarchy hierarchy) {
        this.delegate = new StmtExceptionAnalyzer(hierarchy);
    }

    /** Exceptions {@code stmt} might throw, computed without tripping over the SootUp bug. */
    public Set<ClassType> mightThrow(Stmt stmt, ControlFlowGraph<? extends BasicBlock<?>> graph) {
        JNewArrayExpr newArray = brokenNewArrayExpr(stmt);
        if (newArray == null) {
            return delegate.mightThrow(stmt, graph).getExceptions();
        }
        return newArrayAssignExceptions((JAssignStmt) stmt, newArray);
    }

    /**
     * Returns the {@link JNewArrayExpr} that would make SootUp fail, or null if {@code stmt} is
     * safe to hand to the delegate. Only single-dimension arrays of a primitive base type are
     * affected -- {@code new int[2][2]} is a {@code JNewMultiArrayExpr} and {@code new int[2][]}
     * has the (reference) base type {@code int[]}.
     */
    private JNewArrayExpr brokenNewArrayExpr(Stmt stmt) {
        if (!(stmt instanceof JAssignStmt)) {
            return null;
        }
        Value rightOp = ((JAssignStmt) stmt).getRightOp();
        if (!(rightOp instanceof JNewArrayExpr)) {
            return null;
        }
        JNewArrayExpr newArray = (JNewArrayExpr) rightOp;
        return newArray.getBaseType() instanceof ReferenceType ? null : newArray;
    }

    /** Replicates {@code ExceptionInferStmtVisitor.caseAssignStmt} for a broken new-array assign. */
    private Set<ClassType> newArrayAssignExceptions(JAssignStmt stmt, JNewArrayExpr newArray) {
        Set<ClassType> exceptions = new HashSet<>(DEFAULT_EXCEPTIONS);

        Value leftOp = stmt.getLeftOp();
        if (leftOp instanceof Ref) {
            if (leftOp instanceof JArrayRef
                    && (leftOp.getType() instanceof UnknownType || leftOp.getType() instanceof ClassType)) {
                exceptions.add(ExceptionInferResult.ExceptionType.ARRAY_STORE_EXCEPTION);
            }
            ExceptionInferRefVisitor refVisitor = new ExceptionInferRefVisitor();
            ((Ref) leftOp).accept(refVisitor);
            exceptions.addAll(refVisitor.getResult().getExceptions());
        }

        // The base type is primitive here, so there is no RESOLVE_CLASS_ERROR to add -- only the
        // negative-size check from caseNewArrayExpr remains.
        Value size = newArray.getSize();
        if (size instanceof Local
                || (size instanceof IntConstant && ((IntConstant) size).getValue() < 0)) {
            exceptions.add(ExceptionInferResult.ExceptionType.NEGATIVE_ARRAY_SIZE_EXCEPTION);
        }

        return exceptions;
    }
}
