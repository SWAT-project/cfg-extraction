package de.uzl.its.cfg.extractor;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import sootup.core.jimple.common.Value;
import sootup.core.jimple.common.expr.AbstractInvokeExpr;
import sootup.core.jimple.common.expr.JDynamicInvokeExpr;
import sootup.core.jimple.common.expr.JNewExpr;
import sootup.core.jimple.common.expr.JStaticInvokeExpr;
import sootup.core.jimple.common.ref.JStaticFieldRef;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.core.model.SootClass;
import sootup.core.model.SootMethod;
import sootup.core.signatures.MethodSignature;
import sootup.core.typehierarchy.TypeHierarchy;
import sootup.core.types.ClassType;
import sootup.java.core.views.JavaView;

/**
 * Which statements may run a static initializer that can reach an assertion point.
 *
 * <p>A {@code <clinit>} is never called explicitly: the JVM runs it when a class is first used.
 * So it is not in the graph, and an assert inside it would be unreachable, and every path to the
 * statement that triggers it pruned. SWAT masks the initializer's own branches in its walk, so
 * the graph need not describe them; it only has to keep the paths to a trigger interesting. That
 * is what {@link #triggersTarget} answers: a statement that may initialize a class whose
 * initializer (or one of its supertypes') may reach an assert is itself an assertion point.
 *
 * <p>Which use of a class comes first is only known at run time, so every trigger counts. The
 * analysis over-approximates in other ways too, which only costs pruning: calls are followed to
 * every application override, initializers of classes touched on the way are included, and an
 * {@code invokedynamic} other than a lambda or string concatenation, or a reflective call, counts
 * as reaching an assert. Calls into library code are not followed (the same assumption the graph
 * makes everywhere else). {@link #mayReachTarget} answers the same for any method, for
 * {@link CallbackTargets}.
 */
final class ClinitTargets {

    private final JavaView view;
    private final TypeHierarchy hierarchy;
    /** The class whose initializer has run before the analysed entry method starts. */
    private final ClassType entryClass;
    private final Map<ClassType, Boolean> initReachesTarget = new HashMap<>();

    ClinitTargets(JavaView view, ClassType entryClass) {
        this.view = view;
        this.hierarchy = view.getTypeHierarchy();
        this.entryClass = entryClass;
    }

    /** Whether {@code stmt} may run a static initializer that can reach an assertion point. */
    boolean triggersTarget(Stmt stmt) {
        return triggeredClasses(stmt).stream().anyMatch(this::initMayReachTarget);
    }

    /** The classes {@code stmt} may initialize: by {@code new}, a static call or a static field. */
    static Set<ClassType> triggeredClasses(Stmt stmt) {
        Set<ClassType> classes = new LinkedHashSet<>();
        for (Value value : stmt.getUsesAndDefs()) {
            if (value instanceof JNewExpr) {
                classes.add(((JNewExpr) value).getType());
            } else if (value instanceof JStaticFieldRef) {
                classes.add(((JStaticFieldRef) value).getFieldSignature().getDeclClassType());
            }
        }
        if (stmt.isInvokableStmt()) {
            stmt.asInvokableStmt().getInvokeExpr()
                    .filter(invoke -> invoke instanceof JStaticInvokeExpr)
                    .ifPresent(invoke -> classes.add(invoke.getMethodSignature().getDeclClassType()));
        }
        return classes;
    }

    /** Initializing {@code type} also initializes its superclasses (and may its interfaces). */
    private boolean initMayReachTarget(ClassType type) {
        if (!isApplicationClass(type) || type.equals(entryClass)) {
            return false;
        }
        return Stream.concat(Stream.of(type), supertypes(type))
                .filter(t -> !t.equals(entryClass))
                .anyMatch(this::clinitMayReachTarget);
    }

    private boolean clinitMayReachTarget(ClassType type) {
        Boolean cached = initReachesTarget.get(type);
        if (cached != null) {
            return cached;
        }
        // Cycles between initializers end here: a class already being analysed adds nothing new.
        initReachesTarget.put(type, false);
        boolean result = reachesTarget(type);
        initReachesTarget.put(type, result);
        return result;
    }

    /** A worklist over the methods {@code type}'s initializer may run, transitively. */
    private boolean reachesTarget(ClassType type) {
        Optional<SootMethod> clinit = view.getClass(type).flatMap(c -> c.getMethodsByName("<clinit>").stream()
                .map(m -> (SootMethod) m).findFirst());
        return clinit.isPresent() && reachesTargetFrom(clinit.get(), type);
    }

    /** Whether running {@code method} may reach an assertion point, through calls and initializers. */
    boolean mayReachTarget(SootMethod method) {
        return reachesTargetFrom(method, null);
    }

    /** A worklist over the methods {@code start} may run, transitively; {@code self} is the class being initialized, if any. */
    private boolean reachesTargetFrom(SootMethod start, ClassType self) {
        Deque<SootMethod> work = new ArrayDeque<>();
        Set<MethodSignature> seen = new HashSet<>();
        work.add(start);
        seen.add(start.getSignature());
        while (!work.isEmpty()) {
            SootMethod method = work.poll();
            if (!method.hasBody()) {
                continue;
            }
            for (Stmt stmt : method.getBody().getControlFlowGraph().getStmts()) {
                if (isAssertionAllocation(stmt)) {
                    return true;
                }
                for (ClassType touched : triggeredClasses(stmt)) {
                    if (!touched.equals(self) && initMayReachTarget(touched)) {
                        return true;
                    }
                }
                if (!stmt.isInvokableStmt()) {
                    continue;
                }
                Optional<AbstractInvokeExpr> invoke = stmt.asInvokableStmt().getInvokeExpr();
                if (invoke.isEmpty()) {
                    continue;
                }
                if (invoke.get() instanceof JDynamicInvokeExpr indy) {
                    Optional<Set<SootMethod>> targets = CallbackTargets.lambdaTargets(indy, view);
                    if (targets.isEmpty()) {
                        return true; // an invokedynamic we cannot see through
                    }
                    for (SootMethod target : targets.get()) {
                        if (seen.add(target.getSignature())) {
                            work.add(target);
                        }
                    }
                    continue;
                }
                if (InterProceduralCFGExtractor.isReflectiveCall(invoke.get())) {
                    return true;
                }
                for (SootMethod callee : possibleCallees(invoke.get().getMethodSignature())) {
                    if (seen.add(callee.getSignature())) {
                        work.add(callee);
                    }
                }
            }
        }
        return false;
    }

    /**
     * The static initializers initializing {@code type} may run: its own and those of its
     * application supertypes, except {@code entryClass}'s, which has run before main.
     */
    java.util.List<SootMethod> initializers(ClassType type, ClassType entryClass) {
        java.util.List<SootMethod> result = new java.util.ArrayList<>();
        if (!isApplicationClass(type)) {
            return result;
        }
        Stream.concat(Stream.of(type), supertypes(type))
                .filter(t -> !t.equals(entryClass))
                .forEach(t -> view.getClass(t).flatMap(c -> c.getMethodsByName("<clinit>").stream()
                        .map(m -> (SootMethod) m).findFirst()).ifPresent(result::add));
        return result;
    }

    private static boolean isAssertionAllocation(Stmt stmt) {
        return stmt.getUsesAndDefs().stream().anyMatch(value -> value instanceof JNewExpr
                && ((JNewExpr) value).getType().getFullyQualifiedName().equals("java.lang.AssertionError"));
    }

    /**
     * Every application method a call naming {@code sig} may run: the declaration it resolves to
     * in the named class or its superclasses, and every override in an application subtype -- also
     * when the named class is a library class (an application subclass of java.io.PrintWriter).
     */
    Set<SootMethod> possibleCallees(MethodSignature sig) {
        ClassType declared = sig.getDeclClassType();
        Set<SootMethod> callees = new LinkedHashSet<>();
        Stream.concat(Stream.of(declared), Stream.concat(supertypes(declared), subtypes(declared)))
                .filter(this::isApplicationClass)
                .forEach(t -> view.getClass(t)
                        .flatMap(c -> c.getMethod(sig.getSubSignature()))
                        .ifPresent(callees::add));
        return callees;
    }

    private Stream<ClassType> subtypes(ClassType type) {
        try {
            return hierarchy.subtypesOf(type).collect(Collectors.toList()).stream();
        } catch (RuntimeException e) {
            return Stream.empty(); // a type the hierarchy does not know has no subtypes we analyse
        }
    }

    private Stream<ClassType> supertypes(ClassType type) {
        Set<ClassType> result = new LinkedHashSet<>();
        try {
            hierarchy.superClassesOf(type).forEach(result::add);
            hierarchy.implementedInterfacesOf(type).forEach(result::add);
        } catch (RuntimeException e) {
            // A supertype missing from the view: nothing more to find there.
        }
        return result.stream().filter(this::isApplicationClass).collect(Collectors.toList()).stream();
    }

    boolean isApplicationClass(ClassType type) {
        return view.getClass(type).map(SootClass::isApplicationClass).orElse(false);
    }
}
