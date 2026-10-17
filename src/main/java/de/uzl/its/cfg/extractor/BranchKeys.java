package de.uzl.its.cfg.extractor;

import de.uzl.its.cfg.model.CFGEdge;
import de.uzl.its.cfg.model.CFGNode;
import de.uzl.its.cfg.model.ControlFlowGraph;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Set;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import sootup.core.jimple.common.Value;
import sootup.core.jimple.common.constant.IntConstant;
import sootup.core.jimple.common.constant.NullConstant;
import sootup.core.jimple.common.constant.StringConstant;
import sootup.core.jimple.common.expr.AbstractConditionExpr;
import sootup.core.jimple.common.expr.JEqExpr;
import sootup.core.jimple.common.expr.JGeExpr;
import sootup.core.jimple.common.expr.JGtExpr;
import sootup.core.jimple.common.expr.JLeExpr;
import sootup.core.jimple.common.expr.JLtExpr;
import sootup.core.jimple.common.expr.JNeExpr;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sootup.core.jimple.common.expr.AbstractInvokeExpr;
import sootup.core.jimple.common.expr.JNewMultiArrayExpr;
import sootup.core.jimple.common.stmt.JAssignStmt;
import sootup.core.jimple.common.stmt.JIfStmt;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.core.jimple.javabytecode.stmt.JSwitchStmt;
import sootup.core.model.SootMethod;
import sootup.core.signatures.MethodSignature;
import sootup.java.core.JavaSootClass;
import sootup.java.core.views.JavaView;

/**
 * Names the nodes SWAT's trace can be checked against: every branch (an {@code if}, a switch case,
 * a phantom guard) and every call site.
 *
 * <p>A key is {@code owner.name(desc)|line|kind|ordinal}: the method, the source line, the kind
 * ({@code if}, {@code switch}, {@code arr}, {@code newarr}, {@code div}, or {@code call:name(desc)})
 * and the position among those of the same kind on that line. A switch case appends {@code #value},
 * a dimension check of a multi-dimensional allocation {@code #n} (in SWAT's order, the last
 * dimension first). SWAT's executor names the instructions the same way from bytecode (its
 * {@code BranchKeys}); change both together.
 *
 * <p>SootUp keeps no bytecode offsets, so the ordinal is checked against the class file. Within a
 * source line, the statements of SootUp's blocks in {@code getBlocksSorted()} order are mostly in
 * bytecode order (plain {@code getStmts()} order is not, e.g. for a switch on a String), but not
 * always: javac's copies of a {@code finally} block can come in another order. So each instruction
 * also gets a signature both sides can compute (an {@code if}'s operator and constant, a call's
 * constant argument, an array access being a load or a store), and on a line with several
 * instructions of one kind the ordinals are only kept if their signatures are all different and in
 * the bytecode's order. Otherwise the group's keys end in {@code *}: they still name method, line
 * and kind, but not which one. A line whose kinds differ from the class file (SootUp's line
 * attribution is occasionally off), or that has a statement outside the sorted blocks, gets no
 * keys at all. A node without a key is simply not checked.
 */
final class BranchKeys {

    private static final Logger logger = LoggerFactory.getLogger(BranchKeys.class);

    /** One instruction that reports a branch or pushes a frame: where, what, and how to tell it from others. */
    private record Event(int line, String kind, String signature) {
    }

    /** An event of the graph, with the nodes it names and each one's key suffix. */
    private record GraphEvent(Event event, List<String> nodeIds, List<String> suffixes) {
    }

    /** Per class file: per method (name + descriptor), its events in bytecode order. */
    private static final Map<String, Map<String, List<Event>>> bytecodeEvents = new HashMap<>();

    private BranchKeys() {
    }

    static void annotate(SootMethod method, JavaView view, sootup.core.graph.ControlFlowGraph<?> stmtGraph,
            Map<Stmt, String> stmtToId, Map<Stmt, String> entryIds, ControlFlowGraph cfg) {
        List<Event> expected = bytecodeEvents(method, view);
        if (expected == null) {
            return;
        }
        List<Stmt> stmts = new ArrayList<>();
        for (var block : stmtGraph.getBlocksSorted()) {
            stmts.addAll(block.getStmts());
        }
        Set<Integer> unordered = new HashSet<>();
        Set<Stmt> ordered = Collections.newSetFromMap(new IdentityHashMap<>());
        ordered.addAll(stmts);
        for (Stmt stmt : stmtGraph.getStmts()) {
            if (!ordered.contains(stmt)) {
                unordered.add(PhantomGuardEmitter.lineNumberOf(stmt));
            }
        }
        Map<String, List<CFGEdge>> outgoing = new HashMap<>();
        for (CFGEdge edge : cfg.getEdges()) {
            outgoing.computeIfAbsent(edge.getSource(), s -> new ArrayList<>()).add(edge);
        }

        List<GraphEvent> events = new ArrayList<>();
        for (Stmt stmt : stmts) {
            collectEvents(stmt, stmtToId.get(stmt), entryIds.get(stmt), outgoing, cfg, events);
        }

        // Per line and kind, the signatures in order on each side.
        Map<String, List<String>> graphSignatures = new HashMap<>();
        Map<Integer, List<String>> graphKinds = new HashMap<>();
        for (GraphEvent e : events) {
            graphSignatures.computeIfAbsent(e.event().line() + "|" + e.event().kind(), g -> new ArrayList<>())
                    .add(e.event().signature());
            graphKinds.computeIfAbsent(e.event().line(), l -> new ArrayList<>()).add(e.event().kind());
        }
        Map<String, List<String>> bytecodeSignatures = new HashMap<>();
        Map<Integer, List<String>> bytecodeKinds = new HashMap<>();
        for (Event e : expected) {
            bytecodeSignatures.computeIfAbsent(e.line() + "|" + e.kind(), g -> new ArrayList<>()).add(e.signature());
            bytecodeKinds.computeIfAbsent(e.line(), l -> new ArrayList<>()).add(e.kind());
        }

        String methodKey = method.getDeclaringClassType().getFullyQualifiedName().replace('.', '/') + "."
                + method.getName() + IntraProceduralCFGExtractor.jvmDescriptor(method);
        Map<String, Integer> ordinals = new HashMap<>();
        for (GraphEvent e : events) {
            int line = e.event().line();
            String group = line + "|" + e.event().kind();
            int ordinal = ordinals.merge(group, 1, Integer::sum) - 1;
            if (unordered.contains(line) || !sorted(graphKinds.get(line)).equals(sorted(bytecodeKinds.get(line)))) {
                continue;
            }
            List<String> signatures = graphSignatures.get(group);
            boolean exact = signatures.size() == 1
                    || (signatures.equals(bytecodeSignatures.get(group)) && new HashSet<>(signatures).size() == signatures.size());
            String key = methodKey + "|" + group + "|" + (exact ? String.valueOf(ordinal) : "*");
            for (int i = 0; i < e.nodeIds().size(); i++) {
                String suffix = e.suffixes().get(i);
                cfg.getNode(e.nodeIds().get(i)).ifPresent(node -> node.setKey(key + suffix));
            }
        }
    }

    /** The events of one statement, in the executor's order: its guards, then the statement itself. */
    private static void collectEvents(Stmt stmt, String nodeId, String entryId, Map<String, List<CFGEdge>> outgoing,
            ControlFlowGraph cfg, List<GraphEvent> events) {
        int line = PhantomGuardEmitter.lineNumberOf(stmt);
        List<SwatGuardClassifier.Guard> guards = SwatGuardClassifier.guardsOf(stmt);
        if (!guards.isEmpty()) {
            List<String> guardIds = guardChain(entryId, nodeId, outgoing, cfg);
            if (guardIds.size() != guards.size()) {
                logger.warn("Guard chain of '{}' has {} nodes for {} guards", stmt, guardIds.size(), guards.size());
            } else if (stmt instanceof JAssignStmt assign && assign.getRightOp() instanceof JNewMultiArrayExpr) {
                // One instruction, one check per dimension.
                List<String> suffixes = new ArrayList<>();
                for (int i = 0; i < guardIds.size(); i++) {
                    suffixes.add("#" + i);
                }
                events.add(new GraphEvent(new Event(line, "newarr", ""), guardIds, suffixes));
            } else {
                for (int i = 0; i < guards.size(); i++) {
                    SwatGuardClassifier.Guard guard = guards.get(i);
                    String kind = kindOf(guard.kind());
                    String signature = kind.equals("arr")
                            ? (stmt instanceof JAssignStmt assign && assign.getLeftOp() == guard.guarded() ? "store" : "load")
                            : "";
                    events.add(new GraphEvent(new Event(line, kind, signature), List.of(guardIds.get(i)), List.of("")));
                }
            }
        }
        if (stmt instanceof JIfStmt ifStmt) {
            events.add(new GraphEvent(new Event(line, "if", signatureOf(ifStmt)), List.of(nodeId), List.of("")));
        } else if (stmt instanceof JSwitchStmt switchStmt) {
            List<String> caseIds = caseChain(nodeId, outgoing, cfg);
            List<String> suffixes = new ArrayList<>();
            for (var value : switchStmt.getValues()) {
                suffixes.add("#" + value.getValue());
            }
            if (caseIds.size() != suffixes.size()) {
                caseIds = List.of();
                suffixes = List.of();
            }
            events.add(new GraphEvent(new Event(line, "switch", ""), caseIds, suffixes));
        }
        if (stmt.isInvokableStmt() && stmt.asInvokableStmt().getInvokeExpr().isPresent()) {
            AbstractInvokeExpr invoke = stmt.asInvokableStmt().getInvokeExpr().get();
            String signature = "v";
            if (invoke.getArgCount() == 1) {
                Value arg = invoke.getArg(0);
                if (arg instanceof IntConstant c) {
                    signature = String.valueOf(c.getValue());
                } else if (arg instanceof StringConstant c) {
                    signature = c.getValue();
                }
            }
            events.add(new GraphEvent(new Event(line, "call:" + nameAndDescriptor(invoke.getMethodSignature()), signature),
                    List.of(nodeId), List.of("")));
        }
    }

    /** An if's operator and what it compares against: a constant, null, or a variable ("v"). */
    private static String signatureOf(JIfStmt ifStmt) {
        AbstractConditionExpr condition = ifStmt.getCondition();
        String op = condition instanceof JEqExpr ? "==" : condition instanceof JNeExpr ? "!="
                : condition instanceof JLtExpr ? "<" : condition instanceof JGeExpr ? ">="
                : condition instanceof JGtExpr ? ">" : condition instanceof JLeExpr ? "<=" : "?";
        Value op2 = condition.getOp2();
        String operand = op2 instanceof IntConstant c ? String.valueOf(c.getValue())
                : op2 instanceof NullConstant ? "null" : "v";
        return op + operand;
    }

    private static List<String> sorted(List<String> list) {
        List<String> copy = new ArrayList<>(list == null ? List.of() : list);
        copy.sort(null);
        return copy;
    }

    private static String kindOf(SwatGuardClassifier.GuardKind kind) {
        return switch (kind) {
            case ARRAY_BOUNDS -> "arr";
            case ARRAY_SIZE -> "newarr";
            case DIVISION_BY_ZERO -> "div";
        };
    }

    private static String nameAndDescriptor(MethodSignature signature) {
        StringBuilder d = new StringBuilder(signature.getName()).append("(");
        for (sootup.core.types.Type p : signature.getParameterTypes()) {
            d.append(IntraProceduralCFGExtractor.jvmDescriptor(p));
        }
        return d.append(")").append(IntraProceduralCFGExtractor.jvmDescriptor(signature.getType())).toString();
    }

    /** The guard nodes from a statement's entry to the statement itself, in order. */
    private static List<String> guardChain(String head, String stmtId, Map<String, List<CFGEdge>> outgoing,
            ControlFlowGraph cfg) {
        List<String> chain = new ArrayList<>();
        String current = head;
        while (current != null && !current.equals(stmtId)) {
            Optional<CFGNode> node = cfg.getNode(current);
            if (node.isEmpty() || !"PhantomGuard".equals(node.get().getStmtType())) {
                return List.of(); // not the chain we expected (e.g. ref-equality nodes)
            }
            chain.add(current);
            current = outgoing.getOrDefault(current, List.of()).stream()
                    .filter(e -> e.getType() == CFGEdge.EdgeType.PHANTOM_TRUE_BRANCH)
                    .map(CFGEdge::getTarget).findFirst().orElse(null);
        }
        return chain;
    }

    /** The case nodes of a switch, in order (see SwitchChainEmitter). */
    private static List<String> caseChain(String switchId, Map<String, List<CFGEdge>> outgoing, ControlFlowGraph cfg) {
        List<String> chain = new ArrayList<>();
        String current = outgoing.getOrDefault(switchId, List.of()).stream()
                .filter(e -> e.getType() == CFGEdge.EdgeType.NORMAL)
                .map(CFGEdge::getTarget).findFirst().orElse(null);
        while (current != null && cfg.getNode(current).map(n -> "SwitchCase".equals(n.getStmtType())).orElse(false)) {
            chain.add(current);
            current = outgoing.getOrDefault(current, List.of()).stream()
                    .filter(e -> e.getType() == CFGEdge.EdgeType.FALSE_BRANCH)
                    .map(CFGEdge::getTarget).findFirst().orElse(null);
        }
        return chain;
    }

    /** {@code method}'s events in bytecode order; null if its class file cannot be read. */
    private static List<Event> bytecodeEvents(SootMethod method, JavaView view) {
        Optional<JavaSootClass> sootClass = view.getClass(method.getDeclaringClassType());
        if (sootClass.isEmpty()) {
            return null;
        }
        String path = String.valueOf(sootClass.get().getClassSource().getSourcePath());
        Map<String, List<Event>> methods = bytecodeEvents.computeIfAbsent(path, p -> {
            try {
                return readEvents(Files.readAllBytes(sootClass.get().getClassSource().getSourcePath()));
            } catch (IOException | RuntimeException e) {
                logger.warn("Cannot read class file {}: {}", p, e.toString());
                return null;
            }
        });
        return methods == null ? null : methods.get(method.getName() + IntraProceduralCFGExtractor.jvmDescriptor(method));
    }

    private static Map<String, List<Event>> readEvents(byte[] classBytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(classBytes).accept(cn, ClassReader.SKIP_FRAMES);
        Map<String, List<Event>> methods = new HashMap<>();
        for (MethodNode mn : cn.methods) {
            List<Event> events = new ArrayList<>();
            int line = -1;
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn instanceof LineNumberNode ln) {
                    line = ln.line;
                    continue;
                }
                String kind = kindOf(insn);
                if (kind != null) {
                    events.add(new Event(line, kind, signatureOf(insn)));
                }
            }
            methods.put(mn.name + mn.desc, events);
        }
        return methods;
    }

    /** As SWAT's executor classifies the instruction (InstructionMethodAdapter.nameInstruction). */
    private static String kindOf(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        if ((op >= Opcodes.IFEQ && op <= Opcodes.IF_ACMPNE) || op == Opcodes.IFNULL || op == Opcodes.IFNONNULL) {
            return "if";
        }
        if (op == Opcodes.TABLESWITCH || op == Opcodes.LOOKUPSWITCH) {
            return "switch";
        }
        if ((op >= Opcodes.IALOAD && op <= Opcodes.SALOAD) || (op >= Opcodes.IASTORE && op <= Opcodes.SASTORE)) {
            return "arr";
        }
        if (op == Opcodes.IDIV || op == Opcodes.IREM || op == Opcodes.LDIV || op == Opcodes.LREM) {
            return "div";
        }
        if (op == Opcodes.NEWARRAY || op == Opcodes.ANEWARRAY || op == Opcodes.MULTIANEWARRAY) {
            return "newarr";
        }
        if (insn instanceof MethodInsnNode m) {
            return "call:" + m.name + m.desc;
        }
        if (insn instanceof InvokeDynamicInsnNode d) {
            return "call:" + d.name + d.desc;
        }
        return null;
    }

    /** The bytecode side of the signatures (see collectEvents and signatureOf(JIfStmt)). */
    private static String signatureOf(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        if ((op >= Opcodes.IALOAD && op <= Opcodes.SALOAD)) {
            return "load";
        }
        if (op >= Opcodes.IASTORE && op <= Opcodes.SASTORE) {
            return "store";
        }
        String cmp = switch (op) {
            case Opcodes.IFEQ, Opcodes.IF_ICMPEQ, Opcodes.IF_ACMPEQ, Opcodes.IFNULL -> "==";
            case Opcodes.IFNE, Opcodes.IF_ICMPNE, Opcodes.IF_ACMPNE, Opcodes.IFNONNULL -> "!=";
            case Opcodes.IFLT, Opcodes.IF_ICMPLT -> "<";
            case Opcodes.IFGE, Opcodes.IF_ICMPGE -> ">=";
            case Opcodes.IFGT, Opcodes.IF_ICMPGT -> ">";
            case Opcodes.IFLE, Opcodes.IF_ICMPLE -> "<=";
            default -> null;
        };
        if (cmp != null) {
            if (op == Opcodes.IFNULL || op == Opcodes.IFNONNULL) {
                return cmp + "null";
            }
            if (op >= Opcodes.IFEQ && op <= Opcodes.IFLE) {
                return cmp + "0";
            }
            if (op == Opcodes.IF_ACMPEQ || op == Opcodes.IF_ACMPNE) {
                return cmp + "v";
            }
            String c = constantBefore(insn);
            return cmp + (c == null ? "v" : c);
        }
        if (insn instanceof MethodInsnNode m) {
            if (Type.getArgumentTypes(m.desc).length == 1) {
                String c = constantBefore(insn);
                return c == null ? "v" : c;
            }
            return "v";
        }
        if (insn instanceof InvokeDynamicInsnNode d) {
            if (Type.getArgumentTypes(d.desc).length == 1) {
                String c = constantBefore(insn);
                return c == null ? "v" : c;
            }
            return "v";
        }
        return "";
    }

    /** The int or String constant the instruction before {@code insn} pushes, if it is one. */
    private static String constantBefore(AbstractInsnNode insn) {
        AbstractInsnNode p = insn.getPrevious();
        while (p != null && p.getOpcode() < 0) {
            p = p.getPrevious();
        }
        if (p == null) {
            return null;
        }
        int op = p.getOpcode();
        if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) {
            return String.valueOf(op - Opcodes.ICONST_0);
        }
        if (op == Opcodes.BIPUSH || op == Opcodes.SIPUSH) {
            return String.valueOf(((IntInsnNode) p).operand);
        }
        if (p instanceof LdcInsnNode ldc && (ldc.cst instanceof Integer || ldc.cst instanceof String)) {
            return String.valueOf(ldc.cst);
        }
        return null;
    }
}
