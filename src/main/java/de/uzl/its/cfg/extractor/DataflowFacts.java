package de.uzl.its.cfg.extractor;

import de.uzl.its.cfg.model.CFGNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import sootup.core.jimple.common.LValue;
import sootup.core.jimple.common.Value;
import sootup.core.jimple.common.Local;
import sootup.core.jimple.common.constant.Constant;
import sootup.core.jimple.common.expr.AbstractInstanceInvokeExpr;
import sootup.core.jimple.common.expr.AbstractInvokeExpr;
import sootup.core.jimple.common.expr.JDynamicInvokeExpr;
import sootup.core.jimple.common.ref.JArrayRef;
import sootup.core.jimple.common.ref.JCaughtExceptionRef;
import sootup.core.jimple.common.ref.JFieldRef;
import sootup.core.jimple.common.ref.JInstanceFieldRef;
import sootup.core.jimple.common.ref.JParameterRef;
import sootup.core.jimple.common.ref.JThisRef;
import sootup.core.jimple.common.stmt.JAssignStmt;
import sootup.core.jimple.common.stmt.JIdentityStmt;
import sootup.core.jimple.common.stmt.JIfStmt;
import sootup.core.jimple.common.stmt.JReturnStmt;
import sootup.core.jimple.common.stmt.JThrowStmt;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.core.jimple.javabytecode.stmt.JSwitchStmt;
import sootup.core.model.SootClass;
import sootup.core.model.SootMethod;
import sootup.core.signatures.FieldSignature;
import sootup.core.signatures.MethodSignature;
import sootup.core.types.ArrayType;
import sootup.core.types.ClassType;
import sootup.core.types.PrimitiveType;
import sootup.core.types.Type;
import sootup.java.core.views.JavaView;

/**
 * Data-flow facts for SWAT's relevance analysis: which abstract locations each statement writes and
 * reads, what its control decision reads, and whether it may end the program.
 *
 * <p>SWAT computes from them which branches are <em>irrelevant</em> to the target points: a branch
 * whose region (the statements up to its immediate post-dominator) neither contains a target nor
 * writes anything a target, or a relevant branch, depends on. It stops flipping such branches and
 * leaves their constraints out of its queries (a static slice, see SWAT's {@code Relevance.py}).
 * A location missing from a write set lets SWAT drop a constraint that matters, which can hide a
 * violation, so everything here over-approximates.
 *
 * <p>Locations (see {@link CFGNode#getDataflow}): locals {@code l:<name>}, parameters {@code
 * p:<n>} and {@code p:this}, the return value {@code ret} (all scoped to the node's method by
 * SWAT); fields {@code f:<name>:<type>} (by name and type: no points-to analysis, so all objects
 * share one location per field, and static and instance fields are not told apart); array elements
 * {@code a:<element kind>} ({@code a:ref} for all reference arrays); the input cursor {@link
 * #INPUT} (every {@code Verifier.nondet*} call reads and advances it, so code that reads inputs
 * shifts which input every later read gets); library state {@link #LIB}; output {@link #OUT}; the
 * exception in flight {@link #EXC}; and {@link #ANY}, which stands for every location.
 *
 * <p>Library code is not in the graph. A library call is classified by its signature: pure (only
 * its arguments flow into its result: {@code Math}, {@code String} and the boxes, with arguments
 * that cannot be application objects), output only ({@code PrintStream.print} of a primitive or a
 * String), input ({@code Verifier.nondet*}), terminating ({@code System.exit}, {@code
 * Runtime.halt}, {@code Verifier.assume}), reflective (anything), or else heap: it reads and writes
 * {@link #LIB}, every array it may be given, and whatever an application callback (see {@link
 * CallbackTargets}) may write. Static initializers are not in the graph either: a statement that may
 * run one also gets its effects.
 */
final class DataflowFacts {

    static final String INPUT = "g:input";
    static final String LIB = "g:lib";
    static final String OUT = "g:out";
    static final String EXC = "g:exc";
    static final String ANY = "*";

    private static final String VERIFIER = "org.sosy_lab.sv_benchmarks.Verifier";

    private static final List<String> ALL_ARRAYS = List.of("a:int", "a:long", "a:short", "a:byte",
            "a:char", "a:boolean", "a:float", "a:double", "a:ref");

    /** Classes whose library methods are pure when no argument can be an application object. */
    private static final Set<String> PURE_CLASSES = Set.of("java.lang.Math", "java.lang.StrictMath",
            "java.lang.String", "java.lang.Integer", "java.lang.Long", "java.lang.Short",
            "java.lang.Byte", "java.lang.Character", "java.lang.Boolean", "java.lang.Float",
            "java.lang.Double");

    /** Types whose values cannot be application objects (nor arrays a callee could write). */
    private static final Set<String> VALUE_TYPES = Set.of("java.lang.String", "java.lang.Integer",
            "java.lang.Long", "java.lang.Short", "java.lang.Byte", "java.lang.Character",
            "java.lang.Boolean", "java.lang.Float", "java.lang.Double");

    /** How a call site was resolved (see InterProceduralCFGExtractor). */
    enum CallKind {
        /** Expanded into application code: SWAT links the arguments to the callee. */
        APPLICATION,
        /** Left as a leaf: library code, or a blacklisted method. */
        LIBRARY,
        /** Not expanded although application code may run: the node is incomplete. */
        UNRESOLVED
    }

    /** What running some code may do to global state. */
    static final class Effects {
        final Set<String> writes = new LinkedHashSet<>();
        final Set<String> reads = new LinkedHashSet<>();
        boolean terminates;

        void add(Effects other) {
            writes.addAll(other.writes);
            reads.addAll(other.reads);
            terminates |= other.terminates;
        }
    }

    /** The facts of one statement, before they are put on a node. */
    private static final class Facts {
        final List<List<List<String>>> dataflow = new ArrayList<>();
        List<String> control;
        boolean terminates;
        Map<String, Object> call;

        void pair(Collection<String> written, Collection<String> read) {
            if (!written.isEmpty()) {
                dataflow.add(List.of(List.copyOf(new LinkedHashSet<>(written)),
                        List.copyOf(new LinkedHashSet<>(read))));
            }
        }

        void add(Effects effects) {
            pair(effects.writes, effects.reads);
            terminates |= effects.terminates;
        }
    }

    private final JavaView view;
    private final ClinitTargets clinits;
    private final ClassType entryClass;
    private Effects callbackEffects;
    private final Map<ClassType, Effects> clinitEffects = new HashMap<>();

    DataflowFacts(JavaView view, ClinitTargets clinits, ClassType entryClass) {
        this.view = view;
        this.clinits = clinits;
        this.entryClass = entryClass;
    }

    /**
     * Puts the facts of {@code stmt} on its node. For a call, {@code kind} says how it was resolved,
     * and for an unresolved one {@code unresolvedTargets} lists the methods it may run (null: not
     * known).
     */
    void annotate(CFGNode node, Stmt stmt, CallKind kind, Collection<SootMethod> unresolvedTargets) {
        Facts facts = new Facts();
        Optional<AbstractInvokeExpr> invoke = stmt.isInvokableStmt()
                ? stmt.asInvokableStmt().getInvokeExpr() : Optional.empty();

        if (stmt instanceof JIdentityStmt identity) {
            facts.pair(Set.of(local(identity.getLeftOp())), reads(identity.getRightOp()));
        } else if (stmt instanceof JIfStmt ifStmt) {
            facts.control = List.copyOf(reads(ifStmt.getCondition()));
        } else if (stmt instanceof JSwitchStmt switchStmt) {
            facts.control = List.copyOf(reads(switchStmt.getKey()));
        } else if (stmt instanceof JReturnStmt ret) {
            facts.pair(Set.of("ret"), reads(ret.getOp()));
        } else if (stmt instanceof JThrowStmt throwStmt) {
            facts.pair(Set.of(EXC), reads(throwStmt.getOp()));
        } else if (stmt instanceof JAssignStmt assign && invoke.isEmpty()) {
            Set<String> written = new LinkedHashSet<>();
            Set<String> read = reads(assign.getRightOp());
            writes(assign.getLeftOp(), written, read);
            facts.pair(written, read);
        }

        if (invoke.isPresent()) {
            String result = null;
            Set<String> resultAddress = new LinkedHashSet<>();
            if (stmt instanceof JAssignStmt assign) {
                Set<String> written = new LinkedHashSet<>();
                writes(assign.getLeftOp(), written, resultAddress);
                result = written.iterator().next();
            }
            invokeFacts(facts, invoke.get(), kind == null ? CallKind.LIBRARY : kind, unresolvedTargets,
                    result, resultAddress, true);
        }

        for (ClassType triggered : ClinitTargets.triggeredClasses(stmt)) {
            facts.add(clinitEffects(triggered));
        }

        if (!facts.dataflow.isEmpty()) {
            node.setDataflow(facts.dataflow);
        }
        if (facts.control != null) {
            node.setControl(facts.control);
        }
        if (facts.terminates) {
            node.setTerminates(true);
        }
        if (facts.call != null) {
            node.setCall(facts.call);
        }
    }

    /** The control facts of a node that stands for a test of a switch's key. */
    static List<String> switchCaseControl(JSwitchStmt switchStmt) {
        return List.copyOf(reads(switchStmt.getKey()));
    }

    /** The control facts of a phantom guard: the operands of the guarded value. */
    static List<String> guardControl(Value guarded) {
        Set<String> read = new LinkedHashSet<>(reads(guarded));
        if (guarded instanceof JArrayRef arrayRef) {
            // Only the bounds are tested, not the element: the element itself is not read.
            read.remove(arrayKind(arrayRef));
            read.removeIf(ALL_ARRAYS::contains);
            read.addAll(reads(arrayRef.getBase()));
            read.addAll(reads(arrayRef.getIndex()));
        }
        return List.copyOf(read);
    }

    private void invokeFacts(Facts facts, AbstractInvokeExpr invoke, CallKind kind,
            Collection<SootMethod> unresolvedTargets, String result, Set<String> resultAddress,
            boolean withCallbacks) {
        List<Set<String>> args = new ArrayList<>();
        Set<String> allArgs = new LinkedHashSet<>();
        for (Value arg : invoke.getArgs()) {
            Set<String> read = reads(arg);
            args.add(read);
            allArgs.addAll(read);
        }
        Set<String> base = invoke instanceof AbstractInstanceInvokeExpr instance
                ? reads(instance.getBase()) : new LinkedHashSet<>();
        Set<String> everything = new LinkedHashSet<>(allArgs);
        everything.addAll(base);
        everything.addAll(resultAddress);
        Set<String> resultOnly = result == null ? Set.of() : Set.of(result);

        MethodSignature sig = invoke.getMethodSignature();
        String owner = sig.getDeclClassType().getFullyQualifiedName();
        String name = sig.getName();

        if (kind == CallKind.APPLICATION) {
            Map<String, Object> call = new LinkedHashMap<>();
            if (owner.equals(VERIFIER) && name.equals("nondetObject") && args.size() == 2) {
                // Run as factory.createObject() (see ReceiverTypeResolver): the factory is the receiver.
                call.put("args", List.of());
                call.put("base", List.copyOf(args.get(1)));
            } else {
                call.put("args", args.stream().map(List::copyOf).toList());
                call.put("base", List.copyOf(base));
            }
            if (result != null) {
                call.put("result", result);
                if (!resultAddress.isEmpty()) {
                    call.put("resultAddress", List.copyOf(resultAddress));
                }
            }
            facts.call = call;
            return;
        }

        if (kind == CallKind.UNRESOLVED) {
            Effects effects = new Effects();
            if (unresolvedTargets == null) {
                effects.writes.add(ANY);
                effects.reads.add(ANY);
            } else {
                effects.add(transitiveEffects(unresolvedTargets));
                effects.add(libraryHeapEffects(invoke));
            }
            Set<String> written = new LinkedHashSet<>(effects.writes);
            written.addAll(resultOnly);
            Set<String> read = new LinkedHashSet<>(effects.reads);
            read.addAll(everything);
            facts.pair(written, read);
            facts.terminates |= effects.terminates;
            return;
        }

        // A library call (or one of SWAT's Verifier stubs).
        if (owner.equals(VERIFIER)) {
            if (name.startsWith("nondet")) {
                Set<String> written = new LinkedHashSet<>(resultOnly);
                written.add(INPUT);
                Set<String> read = new LinkedHashSet<>(everything);
                read.add(INPUT);
                facts.pair(written, read);
                return;
            }
            if (name.equals("assume")) {
                facts.terminates = true;
                facts.control = List.copyOf(allArgs);
                return;
            }
            facts.pair(resultOnly, everything);
            return;
        }
        if (isExit(owner, name)) {
            facts.terminates = true;
            return;
        }
        if (isReflective(owner, name) || invoke instanceof JDynamicInvokeExpr && !isPlainIndy((JDynamicInvokeExpr) invoke)) {
            Set<String> written = new LinkedHashSet<>(resultOnly);
            written.add(ANY);
            Set<String> read = new LinkedHashSet<>(everything);
            read.add(ANY);
            facts.pair(written, read);
            return;
        }
        if (isPure(invoke)) {
            facts.pair(resultOnly, everything);
            return;
        }
        if (isOutput(invoke)) {
            facts.pair(Set.of(OUT), everything);
            facts.pair(resultOnly, base);
            return;
        }
        if (name.equals("<init>") && sig.getParameterTypes().stream().allMatch(DataflowFacts::isValueTypeOrPrimitiveArray)) {
            // A library constructor given no application object (an exception with a message, a
            // StringBuilder): it initializes a new library object and runs no application code.
            facts.pair(Set.of(LIB), everything);
            return;
        }
        Effects effects = libraryHeapEffects(invoke);
        if (withCallbacks) {
            effects.add(callbackEffects());
        }
        Set<String> written = new LinkedHashSet<>(effects.writes);
        written.addAll(resultOnly);
        Set<String> read = new LinkedHashSet<>(effects.reads);
        read.addAll(everything);
        facts.pair(written, read);
        facts.terminates |= effects.terminates;
    }

    /** What a library call that may change library state does, apart from callbacks. */
    private static Effects libraryHeapEffects(AbstractInvokeExpr invoke) {
        Effects effects = new Effects();
        effects.writes.add(LIB);
        effects.reads.add(LIB);
        List<Value> passed = new ArrayList<>(invoke.getArgs());
        if (invoke instanceof AbstractInstanceInvokeExpr instance) {
            passed.add(instance.getBase());
        }
        for (Value value : passed) {
            effects.writes.addAll(arraysIn(value.getType()));
            effects.reads.addAll(arraysIn(value.getType()));
        }
        return effects;
    }

    /** The array locations a value of {@code type} may give access to. */
    private static List<String> arraysIn(Type type) {
        if (type instanceof PrimitiveType) {
            return List.of();
        }
        if (type instanceof ArrayType arrayType) {
            Type element = arrayType.getElementType();
            List<String> kinds = new ArrayList<>(List.of(elementKind(element)));
            if (!(element instanceof PrimitiveType)) {
                kinds.addAll(ALL_ARRAYS); // the elements may be arrays themselves
            }
            return kinds;
        }
        if (type instanceof ClassType classType && VALUE_TYPES.contains(classType.getFullyQualifiedName())) {
            return List.of();
        }
        // An Object may be an array; any other object may hold arrays.
        return ALL_ARRAYS;
    }

    private static boolean isExit(String owner, String name) {
        return (owner.equals("java.lang.System") && name.equals("exit"))
                || (owner.equals("java.lang.Runtime") && (name.equals("exit") || name.equals("halt")));
    }

    /**
     * Library code that may read or write anything: reflection, method handles, Unsafe, and what
     * loads or instantiates a class by name (running code the graph cannot name). Other methods of
     * {@code Class} only read class metadata ({@code desiredAssertionStatus()}, which every class
     * with an {@code assert} calls in its static initializer, {@code getName()}).
     */
    private static boolean isReflective(String owner, String name) {
        if (owner.equals("java.lang.Class")) {
            return name.equals("forName") || name.equals("newInstance");
        }
        if (owner.equals("java.lang.ClassLoader")) {
            return name.startsWith("loadClass") || name.equals("findClass") || name.equals("defineClass");
        }
        return owner.startsWith("java.lang.reflect.") || owner.startsWith("java.lang.invoke.")
                || owner.equals("sun.misc.Unsafe") || owner.startsWith("jdk.internal.");
    }

    /** String concatenation and lambda creation, which run no code themselves. */
    private static boolean isPlainIndy(JDynamicInvokeExpr indy) {
        String bootstrap = indy.getBootstrapMethodSignature().getDeclClassType().getFullyQualifiedName();
        return bootstrap.equals("java.lang.invoke.StringConcatFactory")
                || bootstrap.equals("java.lang.invoke.LambdaMetafactory");
    }

    private static boolean isPure(AbstractInvokeExpr invoke) {
        MethodSignature sig = invoke.getMethodSignature();
        String owner = sig.getDeclClassType().getFullyQualifiedName();
        if (owner.equals("java.lang.Object") && sig.getName().equals("<init>")) {
            return true;
        }
        if (invoke instanceof JDynamicInvokeExpr indy) {
            // String concatenation calls toString() on its operands, which may be a callback.
            return isPlainIndy(indy) && invoke.getArgs().stream().allMatch(a -> isValueType(a.getType()));
        }
        if (!PURE_CLASSES.contains(owner)) {
            return false;
        }
        if (owner.equals("java.lang.String") && (sig.getName().equals("getChars") || sig.getName().equals("getBytes"))
                && sig.getParameterTypes().stream().anyMatch(t -> t instanceof ArrayType)) {
            return false; // writes into the array it is given
        }
        return sig.getParameterTypes().stream().allMatch(DataflowFacts::isValueTypeOrPrimitiveArray);
    }

    private static boolean isOutput(AbstractInvokeExpr invoke) {
        MethodSignature sig = invoke.getMethodSignature();
        String owner = sig.getDeclClassType().getFullyQualifiedName();
        if (!owner.equals("java.io.PrintStream")) {
            return false;
        }
        String name = sig.getName();
        if (!(name.equals("print") || name.equals("println") || name.equals("write") || name.equals("flush"))) {
            return false;
        }
        return sig.getParameterTypes().stream().allMatch(DataflowFacts::isValueTypeOrPrimitiveArray);
    }

    private static boolean isValueType(Type type) {
        return type instanceof PrimitiveType
                || type instanceof ClassType classType && VALUE_TYPES.contains(classType.getFullyQualifiedName());
    }

    private static boolean isValueTypeOrPrimitiveArray(Type type) {
        return isValueType(type)
                || type instanceof ArrayType arrayType && arrayType.getDimension() == 1
                        && arrayType.getBaseType() instanceof PrimitiveType;
    }

    /** What the library may run through callbacks into the application (see CallbackTargets). */
    private Effects callbackEffects() {
        if (callbackEffects == null) {
            callbackEffects = transitiveEffects(CallbackTargets.candidates(view));
        }
        return callbackEffects;
    }

    /** What initializing {@code type} may do (nothing for the entry class, initialized first). */
    private Effects clinitEffects(ClassType type) {
        Effects cached = clinitEffects.get(type);
        if (cached != null) {
            return cached;
        }
        clinitEffects.put(type, new Effects()); // initializers that trigger each other
        Effects effects = new Effects();
        if (!type.equals(entryClass)) {
            effects.add(transitiveEffects(clinits.initializers(type, entryClass)));
        }
        clinitEffects.put(type, effects);
        return effects;
    }

    /**
     * The global effects of running any of {@code starts}: the global locations their statements
     * (and those of every method, static initializer and callback they may run) write and read.
     */
    Effects transitiveEffects(Collection<SootMethod> starts) {
        Effects effects = new Effects();
        Deque<SootMethod> work = new ArrayDeque<>(starts);
        Set<MethodSignature> seen = new HashSet<>();
        starts.forEach(m -> seen.add(m.getSignature()));
        boolean callbacksQueued = false;
        while (!work.isEmpty()) {
            SootMethod method = work.poll();
            if (!method.hasBody()) {
                continue;
            }
            List<SootMethod> next = new ArrayList<>();
            for (Stmt stmt : method.getBody().getControlFlowGraph().getStmts()) {
                CFGNode scratch = new CFGNode("scratch", "", "", -1);
                Optional<AbstractInvokeExpr> invoke = stmt.isInvokableStmt()
                        ? stmt.asInvokableStmt().getInvokeExpr() : Optional.empty();
                CallKind kind = CallKind.LIBRARY;
                if (invoke.isPresent()) {
                    if (invoke.get() instanceof JDynamicInvokeExpr indy) {
                        Optional<Set<SootMethod>> lambdas = CallbackTargets.lambdaTargets(indy, view);
                        if (lambdas.isPresent()) {
                            next.addAll(lambdas.get());
                        }
                    } else {
                        Set<SootMethod> callees = clinits.possibleCallees(invoke.get().getMethodSignature());
                        next.addAll(callees);
                        if (!callees.isEmpty() && clinits.isApplicationClass(invoke.get().getMethodSignature().getDeclClassType())) {
                            kind = CallKind.APPLICATION; // its effects come from walking the callees
                        }
                    }
                }
                // Library calls here are classified as at a node, but without the callbacks, which
                // are walked as methods of their own below.
                annotateWithoutCallbacks(scratch, stmt, kind);
                if (scratch.getDataflow() != null) {
                    for (List<List<String>> pair : scratch.getDataflow()) {
                        pair.get(0).stream().filter(DataflowFacts::isGlobal).forEach(effects.writes::add);
                        pair.get(1).stream().filter(DataflowFacts::isGlobal).forEach(effects.reads::add);
                        if (!callbacksQueued && pair.get(0).contains(LIB)) {
                            callbacksQueued = true;
                            next.addAll(CallbackTargets.candidates(view));
                        }
                    }
                }
                if (Boolean.TRUE.equals(scratch.getTerminates())) {
                    effects.terminates = true;
                }
                for (ClassType triggered : ClinitTargets.triggeredClasses(stmt)) {
                    if (!triggered.equals(entryClass)) {
                        next.addAll(clinits.initializers(triggered, entryClass));
                    }
                }
            }
            for (SootMethod callee : next) {
                if (seen.add(callee.getSignature())) {
                    work.add(callee);
                }
            }
        }
        return effects;
    }

    /** As annotate(), for a statement of a method that is not in the graph, without callbacks. */
    private void annotateWithoutCallbacks(CFGNode node, Stmt stmt, CallKind kind) {
        Facts facts = new Facts();
        Optional<AbstractInvokeExpr> invoke = stmt.isInvokableStmt()
                ? stmt.asInvokableStmt().getInvokeExpr() : Optional.empty();
        if (stmt instanceof JAssignStmt assign && invoke.isEmpty()) {
            Set<String> written = new LinkedHashSet<>();
            Set<String> read = reads(assign.getRightOp());
            writes(assign.getLeftOp(), written, read);
            facts.pair(written, read);
        } else if (stmt instanceof JThrowStmt throwStmt) {
            facts.pair(Set.of(EXC), reads(throwStmt.getOp()));
        }
        if (invoke.isPresent() && kind != CallKind.APPLICATION) {
            String result = null;
            Set<String> resultAddress = new LinkedHashSet<>();
            if (stmt instanceof JAssignStmt assign) {
                Set<String> written = new LinkedHashSet<>();
                writes(assign.getLeftOp(), written, resultAddress);
                result = written.iterator().next();
            }
            invokeFacts(facts, invoke.get(), kind, null, result, resultAddress, false);
        }
        if (!facts.dataflow.isEmpty()) {
            node.setDataflow(facts.dataflow);
        }
        if (facts.terminates) {
            node.setTerminates(true);
        }
    }

    static boolean isGlobal(String location) {
        return !(location.startsWith("l:") || location.startsWith("p:") || location.equals("ret"));
    }

    /** The locations reading {@code value} reads. */
    static Set<String> reads(Value value) {
        Set<String> read = new LinkedHashSet<>();
        collectReads(value, read);
        return read;
    }

    private static void collectReads(Value value, Set<String> read) {
        if (value instanceof Local local) {
            read.add(local(local));
        } else if (value instanceof Constant) {
            // nothing
        } else if (value instanceof JInstanceFieldRef fieldRef) {
            read.add(field(fieldRef));
            collectReads(fieldRef.getBase(), read);
        } else if (value instanceof JFieldRef fieldRef) {
            read.add(field(fieldRef));
        } else if (value instanceof JArrayRef arrayRef) {
            read.add(arrayKind(arrayRef));
            if (arrayKind(arrayRef).equals("a:?")) {
                read.remove("a:?");
                read.addAll(ALL_ARRAYS);
            }
            collectReads(arrayRef.getBase(), read);
            collectReads(arrayRef.getIndex(), read);
        } else if (value instanceof JParameterRef parameter) {
            read.add("p:" + parameter.getIndex());
        } else if (value instanceof JThisRef) {
            read.add("p:this");
        } else if (value instanceof JCaughtExceptionRef) {
            read.add(EXC);
        } else {
            for (Value use : value.getUses()) {
                collectReads(use, read);
            }
        }
    }

    /** Adds what assigning to {@code target} writes, and what picking the target location reads. */
    private static void writes(LValue target, Set<String> written, Set<String> address) {
        if (target instanceof Local local) {
            written.add(local(local));
        } else if (target instanceof JInstanceFieldRef fieldRef) {
            written.add(field(fieldRef));
            collectReads(fieldRef.getBase(), address);
        } else if (target instanceof JFieldRef fieldRef) {
            written.add(field(fieldRef));
        } else if (target instanceof JArrayRef arrayRef) {
            String kind = arrayKind(arrayRef);
            if (kind.equals("a:?")) {
                written.addAll(ALL_ARRAYS);
            } else {
                written.add(kind);
            }
            collectReads(arrayRef.getBase(), address);
            collectReads(arrayRef.getIndex(), address);
        } else {
            written.add(ANY);
        }
    }

    private static String local(Local local) {
        return "l:" + local.getName();
    }

    private static String field(JFieldRef fieldRef) {
        FieldSignature sig = fieldRef.getFieldSignature();
        return "f:" + sig.getName() + ":" + sig.getType();
    }

    private static String arrayKind(JArrayRef arrayRef) {
        Type element = arrayRef.getType();
        if (element instanceof PrimitiveType || element instanceof ArrayType || element instanceof ClassType) {
            return elementKind(element);
        }
        return "a:?";
    }

    private static String elementKind(Type element) {
        return element instanceof PrimitiveType ? "a:" + element : "a:ref";
    }

    static boolean isApplication(JavaView view, ClassType type) {
        return view.getClass(type).map(SootClass::isApplicationClass).orElse(false);
    }
}
