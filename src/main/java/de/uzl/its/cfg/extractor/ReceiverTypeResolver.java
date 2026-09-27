package de.uzl.its.cfg.extractor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sootup.callgraph.AbstractCallGraphAlgorithm;
import sootup.core.jimple.common.Local;
import sootup.core.jimple.common.Value;
import sootup.core.jimple.common.constant.NullConstant;
import sootup.core.jimple.common.expr.AbstractInstanceInvokeExpr;
import sootup.core.jimple.common.expr.AbstractInvokeExpr;
import sootup.core.jimple.common.expr.JCastExpr;
import sootup.core.jimple.common.expr.JNewExpr;
import sootup.core.jimple.common.expr.JSpecialInvokeExpr;
import sootup.core.jimple.common.expr.JStaticInvokeExpr;
import sootup.core.jimple.common.ref.JThisRef;
import sootup.core.jimple.common.stmt.AbstractDefinitionStmt;
import sootup.core.jimple.common.stmt.JReturnStmt;
import sootup.core.jimple.common.stmt.JThrowStmt;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.core.model.SootClass;
import sootup.core.model.SootMethod;
import sootup.core.signatures.MethodSignature;
import sootup.core.signatures.MethodSubSignature;
import sootup.core.typehierarchy.TypeHierarchy;
import sootup.core.types.ClassType;
import sootup.core.types.PrimitiveType;
import sootup.core.types.Type;
import sootup.java.core.views.JavaView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves the method that a call site actually dispatches to.
 *
 * <p>The declared receiver type of a {@code virtualinvoke} is only an upper bound, and Jimple
 * keeps whatever the bytecode named. A call compiled against {@code java.io.PrintWriter} may well
 * run an override declared in an application class -- which is exactly where an {@code assert}
 * worth finding tends to live. Dispatching on the declared type alone therefore stops the
 * inter-procedural CFG at the library boundary and loses everything behind it.
 *
 * <p>To recover the real target, the receiver local is traced back to its origin: allocations,
 * copies between locals, casts, {@code this}, and the return values of the methods it came from.
 * Where that yields the receiver's concrete type, dispatch is resolved against it instead of the
 * declared type.
 *
 * <p>The analysis is flow-insensitive: it unions over all definitions of a local, in any order.
 * To survive javac reusing one local slot for several source variables, a definition that cannot
 * have produced a value of the call site's declared receiver type is skipped -- see
 * {@link #typesOfLocal}.
 *
 * <p>It deliberately gives up rather than guessing: a receiver read from a field, an array, or a
 * parameter has no traceable origin here and is reported as unknown. Callers are expected to
 * treat anything but an {@link Dispatch#getInlinableTarget() inlinable target} conservatively --
 * see {@link InterProceduralCFGExtractor}.
 */
public class ReceiverTypeResolver {

    private static final Logger logger = LoggerFactory.getLogger(ReceiverTypeResolver.class);

    /**
     * How much of a call site's dispatch the analysis managed to pin down.
     *
     * <p>Two different questions are answered separately, because they have different
     * consequences: whether a single target can be trusted enough to inline, and whether
     * application code might run that is not listed at all.
     */
    private enum Precision {
        /** Every method the call site can run is listed. */
        EXACT,
        /**
         * Every <em>application</em> method the call site can run is listed; library overrides
         * may be missing.
         *
         * <p>Reached when a receiver of a library type could not be traced. Library subtypes are
         * not enumerated: doing so would mean walking the JDK hierarchy to list methods that are
         * never inlined into the CFG anyway. Application subtypes are complete even here,
         * because library code cannot extend a class from the analysed input.
         */
        LIBRARY_OVERRIDES_UNLISTED,
        /** Nothing about the call site's target could be established. */
        NONE
    }

    /** The methods a call site may dispatch to. */
    public static final class Dispatch {

        private final Set<MethodSignature> targets;
        private final Precision precision;

        private Dispatch(Set<MethodSignature> targets, Precision precision) {
            this.targets = targets;
            this.precision = precision;
        }

        /** Every method the call site is known to possibly run. */
        public Set<MethodSignature> getTargets() {
            return targets;
        }

        /**
         * The single method this call site provably runs, if there is one.
         *
         * <p>Empty unless dispatch was pinned down exactly. A lone target that merely happens to
         * be the only one the analysis could enumerate is withheld on purpose: the graph this
         * feeds is walked in lockstep with real executions, so a call edge into a body that does
         * not actually run would desynchronise it -- worse than having no edge at all.
         */
        public Optional<MethodSignature> getInlinableTarget() {
            return precision == Precision.EXACT && targets.size() == 1
                    ? Optional.of(targets.iterator().next())
                    : Optional.empty();
        }

        /**
         * Whether the call site might run application code that {@link #getTargets()} omits.
         *
         * <p>Library overrides left out of the enumeration do not count: they are never inlined,
         * so they hide nothing the graph would otherwise have contained.
         */
        public boolean mayRunUnlistedApplicationCode() {
            return precision == Precision.NONE;
        }
    }

    private final JavaView view;
    private final TypeHierarchy hierarchy;

    /** Concrete application classes, the only overrides that can hide code worth inlining. */
    private List<ClassType> concreteApplicationTypes;

    /** Inferred receiver types per local, keyed by the method the local belongs to. */
    private final Map<LocalRef, Optional<Set<ClassType>>> localTypeCache = new HashMap<>();

    /** Inferred types of the values a method returns. */
    private final Map<ReturnRef, Optional<Set<ClassType>>> returnTypeCache = new HashMap<>();

    /** Concrete application subtypes per type, for receivers whose origin is unknown. */
    private final Map<ClassType, List<ClassType>> applicationSubtypeCache = new HashMap<>();

    /** Resolved implementations per (receiver type, sub-signature) pair. */
    private final Map<MethodSignature, Optional<MethodSignature>> dispatchCache = new HashMap<>();

    public ReceiverTypeResolver(JavaView view) {
        this.view = view;
        this.hierarchy = view.getTypeHierarchy();
    }

    /**
     * The methods {@code callSite} in {@code caller} may dispatch to.
     *
     * <p>Returns an empty, complete {@link Dispatch} for a statement without an invoke
     * expression.
     */
    public Dispatch resolve(SootMethod caller, Stmt callSite) {
        if (!callSite.isInvokableStmt()) {
            return new Dispatch(Set.of(), Precision.EXACT);
        }
        return callSite.asInvokableStmt().getInvokeExpr()
                .map(invoke -> resolve(caller, invoke, new HashSet<>()))
                .orElseGet(() -> new Dispatch(Set.of(), Precision.EXACT));
    }

    /**
     * The possible runtime types of the value {@code throwStmt} throws, or empty when they cannot
     * be pinned down.
     *
     * <p>Traced like a receiver, bounded by {@code java.lang.Throwable}: {@code assert false}
     * compiles to {@code $stack = new AssertionError; throw $stack} and resolves exactly, while a
     * rethrow of {@code @caughtexception} or of a parameter resolves to unknown. An empty set means
     * the value is provably null, and {@code throw null} raises a {@code NullPointerException}.
     */
    public Optional<Set<ClassType>> thrownTypes(SootMethod method, JThrowStmt throwStmt) {
        ClassType throwable = view.getIdentifierFactory().getClassType("java.lang.Throwable");
        Set<Object> visiting = new HashSet<>();
        Value thrown = throwStmt.getOp();
        if (!(thrown instanceof Local) || !method.hasBody()) {
            return typesOf(method, thrown, throwable, visiting);
        }
        // Unlike a receiver, the thrown local is traced through the definitions that reach the
        // throw, not through all of them: javac reuses the slot of a local thrown in a try block
        // for the catch clauses' exception variables, so a flow-insensitive union would see
        // `b = @caughtexception` next to `b = new B` and give up on a throw it can pin down.
        List<AbstractDefinitionStmt> definitions =
                reachingDefinitions(method.getBody().getControlFlowGraph(), (Local) thrown, throwStmt);
        if (definitions.isEmpty()) {
            return Optional.empty();
        }
        Set<ClassType> types = new LinkedHashSet<>();
        for (AbstractDefinitionStmt definition : definitions) {
            Optional<Set<ClassType>> definitionTypes = typesOf(method, definition.getRightOp(), throwable, visiting);
            if (definitionTypes.isEmpty()) {
                return Optional.empty();
            }
            types.addAll(definitionTypes.get());
        }
        return Optional.of(types);
    }

    /**
     * The definitions of {@code local} that may reach {@code use}, found by walking predecessors
     * backwards until each path hits one.
     *
     * <p>A handler is entered from any statement of the blocks it traps, and such a statement may
     * throw before its own assignment completes. So once a path has crossed an exceptional edge,
     * a definition on it is collected but does not end the path: the value from before it may still
     * be the one that reaches. That is an over-approximation, which only ever costs precision.
     */
    private static List<AbstractDefinitionStmt> reachingDefinitions(
            sootup.core.graph.ControlFlowGraph<?> graph, Local local, Stmt use) {
        List<AbstractDefinitionStmt> definitions = new ArrayList<>();
        // Stmt has no structural equality, so identity is what tells statements apart here.
        Set<Stmt> seenNormal = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        Set<Stmt> seenExceptional = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        java.util.Deque<Map.Entry<Stmt, Boolean>> worklist = new java.util.ArrayDeque<>();
        java.util.function.BiConsumer<Stmt, Boolean> enqueuePredecessors = (stmt, exceptional) -> {
            for (Stmt pred : graph.predecessors(stmt)) {
                worklist.push(Map.entry(pred, exceptional));
            }
            for (Stmt pred : graph.exceptionalPredecessors(stmt)) {
                worklist.push(Map.entry(pred, true));
            }
        };
        enqueuePredecessors.accept(use, false);
        while (!worklist.isEmpty()) {
            Map.Entry<Stmt, Boolean> item = worklist.pop();
            Stmt stmt = item.getKey();
            boolean exceptional = item.getValue();
            if (!(exceptional ? seenExceptional : seenNormal).add(stmt)) {
                continue;
            }
            boolean defines = stmt instanceof AbstractDefinitionStmt
                    && local.equals(((AbstractDefinitionStmt) stmt).getLeftOp());
            if (defines && !definitions.contains(stmt)) {
                definitions.add((AbstractDefinitionStmt) stmt);
            }
            if (!defines || exceptional) {
                enqueuePredecessors.accept(stmt, exceptional);
            }
        }
        return definitions;
    }

    /**
     * @param visiting the locals and methods whose types are currently being computed further up
     *                 the recursion, shared so that a receiver defined in terms of its own call
     *                 (a {@code node = node.getNext()} walk) terminates instead of recursing
     *                 through a fresh guard at every step
     */
    private Dispatch resolve(SootMethod caller, AbstractInvokeExpr invoke, Set<Object> visiting) {
        MethodSignature declared = invoke.getMethodSignature();

        // staticinvoke and specialinvoke (constructors, super calls, private methods) name their
        // target outright -- there is no dynamic dispatch to resolve.
        if (invoke instanceof JStaticInvokeExpr || invoke instanceof JSpecialInvokeExpr) {
            return new Dispatch(Set.of(declared), Precision.EXACT);
        }

        if (!(invoke instanceof AbstractInstanceInvokeExpr)) {
            // invokedynamic: the target is built at runtime from a method handle, so nothing
            // here can pin it down, not even an upper bound on the receiver.
            return new Dispatch(Set.of(), Precision.NONE);
        }

        // we should now be left with an invokevirtual or an invokeinterface

        ClassType declaredType = declared.getDeclClassType();
        Local receiver = ((AbstractInstanceInvokeExpr) invoke).getBase();
        Optional<Set<ClassType>> receiverTypes = typesOf(caller, receiver, declaredType, visiting);

        Set<ClassType> candidates;
        Precision precision;
        if (receiverTypes.isPresent()) {
            // The receiver's runtime types are known, so dispatching from each of them covers
            // every method the call site can run.
            candidates = receiverTypes.get();
            precision = Precision.EXACT;
        } else {
            candidates = possibleTypesBelow(declaredType);
            // Only for an application receiver type is the subtype enumeration complete.
            precision = isApplicationType(declaredType)
                    ? Precision.EXACT
                    : Precision.LIBRARY_OVERRIDES_UNLISTED;
        }

        MethodSubSignature subSignature = declared.getSubSignature();
        Set<MethodSignature> targets = new LinkedHashSet<>();
        for (ClassType candidate : candidates) {
            Optional<MethodSignature> target = dispatch(candidate, subSignature);
            if (target.isEmpty()) {
                // A candidate type with no implementation to run means the candidate set is not
                // trustworthy, and nothing about this call site can be relied on.
                precision = Precision.NONE;
                continue;
            }
            targets.add(target.get());
        }

        if (logger.isDebugEnabled() && (precision != Precision.EXACT || targets.size() != 1)) {
            logger.debug("Dispatch of {} in {} is not pinned down: {} target(s), precision={}",
                    declared, caller.getSignature(), targets.size(), precision);
        }
        return new Dispatch(targets, precision);
    }

    private boolean isApplicationType(ClassType type) {
        return view.getClass(type).map(SootClass::isApplicationClass).orElse(false);
    }

    /**
     * The runtime types a receiver of static type {@code type} can have.
     *
     * <p>The type itself is possible whenever it can be instantiated, and so is every concrete
     * application subtype of it. Library subtypes are left out on purpose -- see
     * {@link Precision#LIBRARY_OVERRIDES_UNLISTED}, which is what a caller gets told about the
     * omission.
     */
    private Set<ClassType> possibleTypesBelow(ClassType type) {
        Set<ClassType> candidates = new LinkedHashSet<>();
        if (isInstantiable(type)) {
            candidates.add(type);
        }
        candidates.addAll(applicationSubtypesOf(type));
        return candidates;
    }

    private List<ClassType> applicationSubtypesOf(ClassType type) {
        return applicationSubtypeCache.computeIfAbsent(type, supertype -> {
            List<ClassType> subtypes = new ArrayList<>();
            for (ClassType candidate : concreteApplicationTypes()) {
                if (!candidate.equals(supertype) && isSubtypeOf(candidate, supertype)) {
                    subtypes.add(candidate);
                }
            }
            return subtypes;
        });
    }

    /**
     * The application classes that can actually be instantiated.
     *
     * <p>Abstract classes and interfaces are excluded: they never are a receiver's runtime type,
     * so including them would only add dispatch targets that cannot occur.
     */
    private List<ClassType> concreteApplicationTypes() {
        if (concreteApplicationTypes == null) {
            concreteApplicationTypes = view.getClasses()
                    .filter(SootClass::isApplicationClass)
                    .filter(sootClass -> !sootClass.isInterface() && !sootClass.isAbstract())
                    .map(sootClass -> (ClassType) sootClass.getType())
                    .toList();
            logger.debug("Found {} concrete application classes for dispatch resolution",
                    concreteApplicationTypes.size());
        }
        return concreteApplicationTypes;
    }

    private boolean isInstantiable(ClassType type) {
        return view.getClass(type)
                .map(sootClass -> !sootClass.isInterface() && !sootClass.isAbstract())
                // A type the view cannot resolve is assumed instantiable: dropping it would
                // silently narrow the candidate set on no evidence.
                .orElse(true);
    }

    /**
     * The implementation that {@code type} inherits or declares for {@code subSignature}.
     *
     * <p>Cached because an untraceable receiver of a widely extended type makes this run once
     * per subtype, at every call site that shares the situation.
     */
    private Optional<MethodSignature> dispatch(ClassType type, MethodSubSignature subSignature) {
        MethodSignature signature = view.getIdentifierFactory().getMethodSignature(type, subSignature);
        return dispatchCache.computeIfAbsent(signature, sig -> {
            try {
                return AbstractCallGraphAlgorithm.resolveConcreteDispatch(view, sig);
            } catch (RuntimeException e) {
                logger.debug("Could not resolve dispatch of {}: {}", sig, e.getMessage());
                return Optional.empty();
            }
        });
    }

    /**
     * The possible runtime types of {@code value}, or empty when they cannot be pinned down.
     *
     * <p>An empty {@link Optional} means "unknown", which is not the same as an empty set: the
     * latter says the value is provably null and has no runtime type at all.
     */
    private Optional<Set<ClassType>> typesOf(SootMethod method, Value value, ClassType bound,
            Set<Object> visiting) {
        if (value instanceof NullConstant) {
            return Optional.of(Set.of());
        }
        if (value instanceof JNewExpr) {
            return Optional.of(Set.of(((JNewExpr) value).getType()));
        }
        if (value instanceof JThisRef) {
            return typesOfThis(method);
        }
        if (value instanceof JCastExpr) {
            return typesOfCast(method, (JCastExpr) value, bound, visiting);
        }
        if (value instanceof AbstractInvokeExpr) {
            return typesOfInvokeResult(method, (AbstractInvokeExpr) value, bound, visiting);
        }
        if (value instanceof Local) {
            return typesOfLocal(method, (Local) value, bound, visiting);
        }

        // Field reads, array reads, parameters, and caught exceptions all have their origin
        // outside this method body. Tracing them would need a heap or a call-site analysis;
        // until then the honest answer is that the type is unknown.
        return Optional.empty();
    }

    /**
     * The runtime types of {@code this} inside {@code method}.
     *
     * <p>Bounded by the declaring class, which is often tighter than the receiver type the call
     * site names: a call to an inherited method compiled against the superclass that declares it
     * still runs on an instance of the subclass it appears in.
     *
     * <p>Only application classes are handled, because only there is the subtype enumeration
     * complete -- library code cannot extend a class from the analysed input.
     */
    private Optional<Set<ClassType>> typesOfThis(SootMethod method) {
        ClassType declaringType = method.getDeclaringClassType();
        if (!isApplicationType(declaringType)) {
            return Optional.empty();
        }
        return Optional.of(possibleTypesBelow(declaringType));
    }

    /**
     * The runtime types behind a cast.
     *
     * <p>The cast is a filter, not a source of information: the operand's inferred types are
     * narrowed to those the cast admits. A cast whose operand is unknown stays unknown -- the
     * cast type is an upper bound, not the runtime type.
     */
    private Optional<Set<ClassType>> typesOfCast(SootMethod method, JCastExpr cast, ClassType bound,
            Set<Object> visiting) {
        Optional<Set<ClassType>> operandTypes = typesOf(method, cast.getOp(), bound, visiting);
        if (operandTypes.isEmpty()) {
            return Optional.empty();
        }
        Type castType = cast.getType();
        Set<ClassType> narrowed = new LinkedHashSet<>();
        for (ClassType operandType : operandTypes.get()) {
            if (operandType.equals(castType) || isSubtypeOf(operandType, castType)) {
                narrowed.add(operandType);
            }
        }
        return Optional.of(narrowed);
    }

    /** The runtime types of the value a call returns, by looking at what the callee returns. */
    private Optional<Set<ClassType>> typesOfInvokeResult(SootMethod method, AbstractInvokeExpr invoke,
            ClassType bound, Set<Object> visiting) {
        // Only an unambiguous callee says anything definite about the returned value. With
        // several possible callees the return types would have to be unioned over all of them,
        // and any one of them being unknown would poison the result anyway.
        return resolve(method, invoke, visiting).getInlinableTarget()
                .flatMap(target -> returnTypesOf(target, bound, visiting));
    }

    /** The runtime types of the values {@code signature} returns. */
    private Optional<Set<ClassType>> returnTypesOf(MethodSignature signature, ClassType bound,
            Set<Object> visiting) {
        ReturnRef ref = new ReturnRef(signature, bound);
        Optional<Set<ClassType>> cached = returnTypeCache.get(ref);
        if (cached != null) {
            return cached;
        }
        // A method whose return value depends on itself (directly or through a cycle) cannot be
        // resolved by this fixpoint-free walk. Reporting unknown keeps the recursion finite.
        if (!visiting.add(ref)) {
            return Optional.empty();
        }
        try {
            Optional<Set<ClassType>> result = computeReturnTypes(signature, bound, visiting);
            cacheIfUncut(returnTypeCache, ref, result, visiting);
            return result;
        } finally {
            visiting.remove(ref);
        }
    }

    private Optional<Set<ClassType>> computeReturnTypes(MethodSignature signature, ClassType bound,
            Set<Object> visiting) {
        Optional<? extends SootMethod> methodOpt = view.getMethod(signature);
        if (methodOpt.isEmpty() || !methodOpt.get().hasBody()) {
            // Library methods are read without bodies here, so their return values cannot be
            // traced.
            return Optional.empty();
        }
        SootMethod method = methodOpt.get();

        Set<ClassType> types = new LinkedHashSet<>();
        for (Stmt stmt : method.getBody().getControlFlowGraph().getStmts()) {
            if (!(stmt instanceof JReturnStmt)) {
                continue;
            }
            Optional<Set<ClassType>> returnedTypes = typesOf(method, ((JReturnStmt) stmt).getOp(), bound, visiting);
            if (returnedTypes.isEmpty()) {
                return Optional.empty();
            }
            types.addAll(returnedTypes.get());
        }
        return Optional.of(types);
    }

    /**
     * The runtime types of a local, unioned over every definition of it in the method body.
     *
     * <p>Definitions that cannot produce a {@code bound} instance are skipped rather than treated
     * as unknown, which matters because javac reuses a local slot across disjoint scopes: the
     * writer in {@code while (..) { PrintWriter w = resp.getWriter(); w.println(..); }} routinely
     * shares its slot with the loop counter of an earlier loop, and a flow-insensitive union over
     * all definitions of that slot would see {@code l6 = 0} and give up. The receiver of an
     * instance call is guaranteed by the JVM to be a {@code bound}, so a definition storing an
     * {@code int} -- or an unrelated reference type -- provably is not the one that reaches the
     * call.
     */
    private Optional<Set<ClassType>> typesOfLocal(SootMethod method, Local local, ClassType bound,
            Set<Object> visiting) {
        LocalRef ref = new LocalRef(method.getSignature(), local, bound);
        Optional<Set<ClassType>> cached = localTypeCache.get(ref);
        if (cached != null) {
            return cached;
        }
        // A local defined in terms of itself (a loop-carried variable) would otherwise recurse
        // forever.
        if (!visiting.add(ref)) {
            return Optional.empty();
        }
        try {
            Optional<Set<ClassType>> result = computeLocalTypes(method, local, bound, visiting);
            cacheIfUncut(localTypeCache, ref, result, visiting);
            return result;
        } finally {
            visiting.remove(ref);
        }
    }

    private Optional<Set<ClassType>> computeLocalTypes(SootMethod method, Local local, ClassType bound,
            Set<Object> visiting) {
        if (!method.hasBody()) {
            return Optional.empty();
        }

        Set<ClassType> types = new LinkedHashSet<>();
        boolean defined = false;
        for (Stmt stmt : method.getBody().getControlFlowGraph().getStmts()) {
            if (!(stmt instanceof AbstractDefinitionStmt)) {
                continue;
            }
            AbstractDefinitionStmt definition = (AbstractDefinitionStmt) stmt;
            if (!local.equals(definition.getLeftOp())) {
                continue;
            }
            Value rightOp = definition.getRightOp();
            if (!canHold(rightOp.getType(), bound)) {
                continue;
            }
            defined = true;
            Optional<Set<ClassType>> definitionTypes = typesOf(method, rightOp, bound, visiting);
            if (definitionTypes.isEmpty()) {
                return Optional.empty();
            }
            types.addAll(definitionTypes.get());
        }

        if (!defined) {
            // No definition in this body could have produced the receiver. Rather than claim the
            // local can never hold one, treat it as untraceable.
            return Optional.empty();
        }
        return Optional.of(types);
    }

    /** Whether a value of static type {@code type} can be an instance of {@code bound}. */
    private boolean canHold(Type type, ClassType bound) {
        if (type instanceof PrimitiveType) {
            return false;
        }
        if (!(type instanceof ClassType)) {
            // Arrays, the null type and unresolved types carry no evidence either way.
            return true;
        }
        ClassType candidate = (ClassType) type;
        // A wider static type is kept: a local declared as Object can still hold a `bound`.
        return candidate.equals(bound)
                || isSubtypeOf(candidate, bound)
                || isSubtypeOf(bound, candidate);
    }

    /**
     * Caches a result only if no recursion cycle cut its computation short.
     *
     * <p>{@code visiting} holding just this key means nothing above it was suspended, so the
     * result stands on its own. Caching a cycle-truncated "unknown" would otherwise pin that
     * answer to the entry the recursion happened to be reached from first.
     */
    private <K> void cacheIfUncut(Map<K, Optional<Set<ClassType>>> cache, K key,
            Optional<Set<ClassType>> result, Set<Object> visiting) {
        if (visiting.size() == 1) {
            cache.put(key, result);
        }
    }

    /**
     * Whether {@code subtype} is a strict subtype of {@code supertype}.
     *
     * <p>Wraps {@link TypeHierarchy#isSubtype} to put the arguments the way round the name reads.
     * SootUp takes the <em>supertype</em> first (its own assertion messages call the parameters
     * {@code supertype} and {@code potentialSubtype}), so calling it directly in subtype order
     * silently answers the opposite question.
     *
     * <p>The relation is strict and has no reflexive case, so an equality check has to be made
     * separately where an exact match should also count.
     */
    private boolean isSubtypeOf(Type subtype, Type supertype) {
        try {
            return hierarchy.isSubtype(supertype, subtype);
        } catch (RuntimeException e) {
            // isSubtype throws for types the hierarchy has never seen, which happens for types
            // only referenced from bytecode that was not on the analysed path.
            logger.debug("Could not compare {} against {}: {}", subtype, supertype, e.getMessage());
            return false;
        }
    }

    /** A local is only meaningful together with the method body and receiver type it was read for. */
    private record LocalRef(MethodSignature method, Local local, ClassType bound) {
    }

    /** A method's return value, as narrowed to one receiver type. */
    private record ReturnRef(MethodSignature method, ClassType bound) {
    }
}
