package de.uzl.its.cfg.extractor;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import sootup.core.jimple.common.Value;
import sootup.core.jimple.common.expr.JCastExpr;
import sootup.core.jimple.common.expr.JLengthExpr;
import sootup.core.jimple.common.ref.JArrayRef;
import sootup.core.jimple.common.ref.JInstanceFieldRef;
import sootup.core.jimple.common.stmt.JThrowStmt;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.core.jimple.javabytecode.stmt.JEnterMonitorStmt;
import sootup.core.jimple.javabytecode.stmt.JExitMonitorStmt;
import sootup.core.model.SootClass;
import sootup.core.model.SootMethod;
import sootup.core.typehierarchy.TypeHierarchy;
import sootup.core.types.ClassType;
import sootup.java.core.views.JavaView;

/**
 * Exceptions whose type the graph does not know: pseudo exception types.
 *
 * <p>Phantom guards and explicit {@code throw}s give an exception a precise type and destination.
 * Everything else that can throw does not: a null dereference, a cast, anything a library call
 * throws (SWAT reports no branch for these), and Errors, which almost any statement can raise.
 * Without edges for them, a handler they reach is unreachable in the graph, and the paths leading
 * into it would be pruned. So such statements get EXCEPTION edges to every handler that might
 * catch them and to an {@code uncaught[?...]} node, whose type is one of
 *
 * <ul>
 *   <li>{@link #EXCEPTION}: an unguarded exception that is not an Error, possibly a checked one
 *       thrown by a library call. Caught by any handler except one for an Error type.
 *   <li>{@link #ERROR}: an Error. Caught by handlers for {@code Throwable}, {@code Error} or a
 *       subclass of it.
 * </ul>
 *
 * <p>A pseudo type may or may not be caught by a handler that might catch it, so it also escapes
 * further (see SWAT's {@code SAGraph.is_pseudo}). Over-approximating only costs pruning. Kinds no
 * handler in the program can catch are not emitted at all, which keeps graphs of programs without
 * such handlers unchanged.
 */
final class PseudoExceptions {

    static final String EXCEPTION = "?Exception";
    static final String ERROR = "?Error";

    private static final String THROWABLE = "java.lang.Throwable";
    private static final String ERROR_CLASS = "java.lang.Error";

    private final TypeHierarchy hierarchy;
    private final Set<String> enabled = new LinkedHashSet<>();

    private PseudoExceptions(TypeHierarchy hierarchy) {
        this.hierarchy = hierarchy;
    }

    /** The pseudo types handlers anywhere in the application can catch. */
    static PseudoExceptions forProgram(JavaView view) {
        PseudoExceptions pseudo = new PseudoExceptions(view.getTypeHierarchy());
        view.getClasses().filter(SootClass::isApplicationClass)
                .flatMap(c -> c.getMethods().stream())
                .filter(SootMethod::hasBody)
                .forEach(m -> {
                    var graph = m.getBody().getControlFlowGraph();
                    for (Stmt stmt : graph.getStmts()) {
                        for (ClassType caught : graph.exceptionalSuccessors(stmt).keySet()) {
                            for (String kind : List.of(EXCEPTION, ERROR)) {
                                if (pseudo.catches(caught, kind)) {
                                    pseudo.enabled.add(kind);
                                }
                            }
                        }
                    }
                });
        return pseudo;
    }

    Set<String> enabled() {
        return enabled;
    }

    /** The pseudo types {@code stmt} may throw, among the enabled ones. */
    List<String> kindsOf(Stmt stmt) {
        List<String> kinds = new java.util.ArrayList<>(2);
        if (enabled.contains(EXCEPTION) && mayThrowUnguarded(stmt)) {
            kinds.add(EXCEPTION);
        }
        if (enabled.contains(ERROR)) {
            kinds.add(ERROR);
        }
        return kinds;
    }

    /** Whether a handler declared for {@code caught} might catch an exception of pseudo type {@code kind}. */
    boolean catches(ClassType caught, String kind) {
        boolean error = isErrorType(caught);
        if (ERROR.equals(kind)) {
            return error || caught.getFullyQualifiedName().equals(THROWABLE);
        }
        return !error;
    }

    private boolean isErrorType(ClassType type) {
        if (type.getFullyQualifiedName().equals(ERROR_CLASS)) {
            return true;
        }
        try {
            return hierarchy.superClassesOf(type).anyMatch(t -> t.getFullyQualifiedName().equals(ERROR_CLASS));
        } catch (RuntimeException e) {
            return false; // unknown to the hierarchy: treat it as an Exception, caught by EXCEPTION
        }
    }

    /**
     * Whether {@code stmt} can throw an exception SWAT reports no branch for: a call (whatever the
     * callee throws, or a null receiver), an instance field or array access, a cast, an array
     * length, a monitor, or a throw (of null). Divisions and array bounds are guarded instead.
     */
    private static boolean mayThrowUnguarded(Stmt stmt) {
        if (stmt instanceof JThrowStmt || stmt instanceof JEnterMonitorStmt || stmt instanceof JExitMonitorStmt) {
            return true;
        }
        if (stmt.isInvokableStmt() && stmt.asInvokableStmt().getInvokeExpr().isPresent()) {
            return true;
        }
        for (Value value : stmt.getUsesAndDefs()) {
            if (value instanceof JInstanceFieldRef || value instanceof JArrayRef
                    || value instanceof JCastExpr || value instanceof JLengthExpr) {
                return true;
            }
        }
        return false;
    }
}
