package de.uzl.its.cfg.extractor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import sootup.core.jimple.common.Immediate;
import sootup.core.jimple.common.constant.MethodHandle;
import sootup.core.jimple.common.expr.JDynamicInvokeExpr;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.core.model.SootClass;
import sootup.core.model.SootMethod;
import sootup.core.signatures.MethodSignature;
import sootup.core.types.ClassType;
import sootup.java.core.views.JavaView;

/**
 * Application methods that library code may call back, and whether one of them can reach an assert.
 *
 * <p>The graph has library calls as plain statements: their bodies are not expanded. Library code
 * can call back into the application, though (a {@code PushbackReader} reading from an application
 * {@code Reader}, {@code toString()} in string concatenation, a lambda passed to a stream), and an
 * assert reached that way is not in the graph: the paths to the library call would be pruned. The
 * callbacks are the application methods that override or implement a library method, and the
 * targets of lambdas and method references. If one of them may reach an assert, the extractor
 * counts every library call as an assertion point (InterProceduralCFGExtractor). That is coarse,
 * but costs nothing in the usual case, where no such method reaches one.
 */
final class CallbackTargets {

    private CallbackTargets() {
    }

    /** The callbacks that may reach an assertion point (see the class comment). */
    static List<SootMethod> reachingTarget(JavaView view, ClinitTargets reach) {
        List<SootMethod> result = new ArrayList<>();
        for (SootMethod candidate : candidates(view)) {
            if (reach.mayReachTarget(candidate)) {
                result.add(candidate);
            }
        }
        return result;
    }

    /** Overrides and implementations of library methods, and lambda and method-reference targets. */
    static Set<SootMethod> candidates(JavaView view) {
        Set<SootMethod> candidates = new LinkedHashSet<>();
        view.getClasses().filter(SootClass::isApplicationClass).forEach(c -> {
            Set<String> librarySubSignatures = librarySubSignatures(c, view);
            for (SootMethod m : c.getMethods()) {
                if (!m.isStatic() && !m.isPrivate() && !m.getName().startsWith("<")
                        && librarySubSignatures.contains(m.getSubSignature().toString())) {
                    candidates.add(m);
                }
                if (!m.hasBody()) {
                    continue;
                }
                for (Stmt stmt : m.getBody().getControlFlowGraph().getStmts()) {
                    if (stmt.isInvokableStmt()) {
                        stmt.asInvokableStmt().getInvokeExpr()
                                .filter(invoke -> invoke instanceof JDynamicInvokeExpr)
                                .flatMap(invoke -> lambdaTargets((JDynamicInvokeExpr) invoke, view))
                                .ifPresent(candidates::addAll);
                    }
                }
            }
        });
        return candidates;
    }

    /** The sub-signatures of the methods {@code c}'s library supertypes declare. */
    private static Set<String> librarySubSignatures(SootClass c, JavaView view) {
        Set<String> result = new HashSet<>();
        Deque<ClassType> work = new ArrayDeque<>();
        Set<ClassType> seen = new HashSet<>();
        c.getSuperclass().ifPresent(work::add);
        work.addAll(c.getInterfaces());
        while (!work.isEmpty()) {
            ClassType type = work.poll();
            if (!seen.add(type)) {
                continue;
            }
            Optional<? extends SootClass> superClass = view.getClass(type);
            if (superClass.isEmpty()) {
                continue;
            }
            if (!superClass.get().isApplicationClass()) {
                for (SootMethod m : superClass.get().getMethods()) {
                    if (!m.isStatic() && !m.isPrivate()) {
                        result.add(m.getSubSignature().toString());
                    }
                }
            }
            superClass.get().getSuperclass().ifPresent(work::add);
            work.addAll(superClass.get().getInterfaces());
        }
        return result;
    }

    /**
     * The application methods a lambda or method reference created by {@code indy} may run; empty
     * for string concatenation; absent for any other {@code invokedynamic}, which we cannot see
     * through.
     */
    static Optional<Set<SootMethod>> lambdaTargets(JDynamicInvokeExpr indy, JavaView view) {
        String bootstrap = indy.getBootstrapMethodSignature().getDeclClassType().getFullyQualifiedName();
        if (bootstrap.equals("java.lang.invoke.StringConcatFactory")) {
            return Optional.of(Set.of());
        }
        if (!bootstrap.equals("java.lang.invoke.LambdaMetafactory")) {
            return Optional.empty();
        }
        Set<SootMethod> targets = new LinkedHashSet<>();
        for (Immediate arg : indy.getBootstrapArgs()) {
            if (arg instanceof MethodHandle handle && handle.isMethodRef()
                    && handle.getReferenceSignature() instanceof MethodSignature sig) {
                view.getMethod(sig).filter(m -> view.getClass(sig.getDeclClassType())
                        .map(SootClass::isApplicationClass).orElse(false)).ifPresent(targets::add);
            }
        }
        return Optional.of(targets);
    }
}
