package de.uzl.its.cfg.extractor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sootup.core.jimple.common.expr.AbstractBinopExpr;
import sootup.core.jimple.common.expr.JDivExpr;
import sootup.core.jimple.common.expr.JNewArrayExpr;
import sootup.core.jimple.common.expr.JNewMultiArrayExpr;
import sootup.core.jimple.common.expr.JRemExpr;
import sootup.core.jimple.common.ref.JArrayRef;
import sootup.core.jimple.common.stmt.JAssignStmt;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.core.jimple.common.Value;
import sootup.core.types.ClassType;
import sootup.core.types.PrimitiveType;
import sootup.core.types.Type;
import sootup.core.types.UnknownType;
import sootup.java.core.JavaIdentifierFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Classifies a Jimple statement by the <em>shape</em> of the risky operations it contains, i.e.
 * the operations for which SWAT inserts a phantom branch.
 *
 * <p>SWAT does not model implicit exceptions during solving. Instead its
 * {@code SymbolicInstructionVisitor} guards each risky bytecode instruction with a branch on the
 * safe condition -- {@code checkIndex(idx)} before an array access, {@code checkPositive(size)}
 * before an allocation, {@code checkZero(divisor)} before an integer division -- and records it
 * via {@code SymbolicTraceHandler.checkAndSetBranch(safe, constraint, iid)}. The safe condition is
 * the <em>true</em> direction, so the exception continuation is always the false branch.
 *
 * <p>For an array SWAT does not track symbolically (e.g. one returned by an uninstrumented library
 * call such as {@code LinkedList.toArray()}), there is no {@code checkIndex} to evaluate. The
 * visitor then records the bounds check as a <em>concrete-only</em> branch instead
 * ({@code SymbolicTraceHandler.addConcreteBranch}), taken iff the access completed normally, so
 * the guard is in the trace for every array access, tracked or not. That is the contract this
 * classifier relies on: which arrays are tracked is only known at run time.
 *
 * <p>The guard set deliberately does <em>not</em> come from SootUp's exception inference
 * ({@link SafeStmtExceptionAnalyzer}), because the two disagree in both directions:
 *
 * <ul>
 *   <li>SootUp reports {@code IndexOutOfBoundsException} for an array access, never the
 *       {@code ArrayIndexOutOfBoundsException} that actually occurs.
 *   <li>SootUp reports {@code ArithmeticException} for <em>any</em> primitive divisor, including
 *       {@code double}, where no exception is possible and SWAT emits no guard.
 *   <li>SootUp prunes statically-safe cases -- it infers nothing for {@code new int[4]} or
 *       {@code a / 2} -- whereas {@code checkAndSetBranch} performs no such pruning (its
 *       "check if the path constraint contains a free/symbolic variable" comment is an
 *       unimplemented TODO), so SWAT records a branch for those too.
 * </ul>
 *
 * <p>Keying on the statement shape instead mirrors SWAT's per-opcode dispatch exactly. Exceptions
 * that SWAT does <em>not</em> guard (most importantly the {@code NullPointerException} that rides
 * along with every array access, since {@code checkArrayBounds} never null-checks) are left to
 * {@link SafeStmtExceptionAnalyzer}.
 */
public final class SwatGuardClassifier {

    private static final Logger logger = LoggerFactory.getLogger(SwatGuardClassifier.class);

    /** A risky operation SWAT guards, and the exception its false branch leads to. */
    public enum GuardKind {
        /**
         * Any array load or store; SWAT records a guard branch for all 16 {@code ?ALOAD}/{@code ?ASTORE}
         * opcodes, symbolic for tracked arrays and concrete-only for untracked ones.
         */
        ARRAY_BOUNDS("java.lang.ArrayIndexOutOfBoundsException"),
        /** {@code NEWARRAY}, {@code ANEWARRAY} and each dimension of {@code MULTIANEWARRAY}. */
        ARRAY_SIZE("java.lang.NegativeArraySizeException"),
        /** {@code IDIV}, {@code IREM}, {@code LDIV}, {@code LREM} -- integral operands only. */
        DIVISION_BY_ZERO("java.lang.ArithmeticException");

        private final ClassType exceptionType;

        GuardKind(String fullyQualifiedName) {
            this.exceptionType = JavaIdentifierFactory.getInstance().getClassType(fullyQualifiedName);
        }

        /**
         * The exception thrown when the guard fails. Unlike SootUp's inferred types these are the
         * precise runtime types, so they can be matched against handler types directly.
         */
        public ClassType getExceptionType() {
            return exceptionType;
        }
    }

    /**
     * A single guard SWAT would emit.
     *
     * @param kind    which risky operation is guarded
     * @param guarded the value the guard constrains: the {@link JArrayRef} being accessed (which
     *                carries both the base and the index) for {@link GuardKind#ARRAY_BOUNDS}, the
     *                size expression for {@link GuardKind#ARRAY_SIZE}, and the divisor for
     *                {@link GuardKind#DIVISION_BY_ZERO}
     */
    public record Guard(GuardKind kind, Value guarded) {
    }

    private SwatGuardClassifier() {
    }

    /**
     * The guards SWAT emits for {@code stmt}, in the order it emits them.
     *
     * <p>Order matters: the returned list is meant to become a chain of phantom branch nodes that
     * lines up one-to-one with the {@code BranchElement}s in SWAT's trace, so a DFS can walk both
     * trees in step. A statement with no risky operation yields an empty list.
     */
    public static List<Guard> guardsOf(Stmt stmt) {
        if (!(stmt instanceof JAssignStmt)) {
            // Jimple only permits Immediates (locals and constants) as expression operands and
            // invoke arguments, so an array ref, allocation or division can appear only as one
            // side of an assignment. Anything else would silently lose a guard, so shout about it.
            warnIfUnexpectedlyRisky(stmt);
            return List.of();
        }
        JAssignStmt assign = (JAssignStmt) stmt;
        List<Guard> guards = new ArrayList<>();
        // The right-hand side is evaluated before the assignment target is written: for
        // `a[i] = b[j]` the BALOAD bounds check precedes the BASTORE one.
        collectGuards(assign.getRightOp(), guards);
        collectGuards(assign.getLeftOp(), guards);
        return guards;
    }

    private static void collectGuards(Value value, List<Guard> guards) {
        if (value instanceof JArrayRef) {
            guards.add(new Guard(GuardKind.ARRAY_BOUNDS, value));
        } else if (value instanceof JNewArrayExpr) {
            guards.add(new Guard(GuardKind.ARRAY_SIZE, ((JNewArrayExpr) value).getSize()));
        } else if (value instanceof JNewMultiArrayExpr) {
            // MULTIANEWARRAY pushes its dimensions left to right, and SWAT guards them as it pops
            // them, so it records the *last* dimension first: `new int[a][b]` guards b before a.
            JNewMultiArrayExpr newMultiArray = (JNewMultiArrayExpr) value;
            for (int dimension = newMultiArray.getSizeCount() - 1; dimension >= 0; dimension--) {
                guards.add(new Guard(GuardKind.ARRAY_SIZE, newMultiArray.getSize(dimension)));
            }
        } else if (value instanceof JDivExpr || value instanceof JRemExpr) {
            Value divisor = ((AbstractBinopExpr) value).getOp2();
            if (isIntegral(divisor.getType())) {
                guards.add(new Guard(GuardKind.DIVISION_BY_ZERO, divisor));
            }
        }
    }

    /**
     * Whether a division by this operand type compiles to {@code IDIV}/{@code IREM}/{@code LDIV}/
     * {@code LREM}, the only division opcodes SWAT guards. {@code byte}, {@code short},
     * {@code char} and {@code boolean} all extend {@code IntType} in SootUp and are promoted to
     * {@code int} in bytecode, so the two {@code instanceof} checks cover every integral type.
     */
    private static boolean isIntegral(Type type) {
        if (type instanceof UnknownType) {
            // Assume the worst: a missing guard desynchronises the DFS from SWAT's trace, so err
            // towards emitting one, but make the imprecision visible.
            logger.warn("Divisor of unknown type, assuming an integral division: {}", type);
            return true;
        }
        return type instanceof PrimitiveType.IntType || type instanceof PrimitiveType.LongType;
    }

    private static void warnIfUnexpectedlyRisky(Stmt stmt) {
        boolean risky = stmt.getUses().stream().anyMatch(use -> use instanceof JArrayRef
                || use instanceof JNewArrayExpr
                || use instanceof JNewMultiArrayExpr
                || use instanceof JDivExpr
                || use instanceof JRemExpr);
        if (risky) {
            logger.warn("Risky operation outside an assignment, guard not classified: {} ({})",
                    stmt, stmt.getClass().getSimpleName());
        }
    }
}
