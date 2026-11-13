package de.uzl.its.cfg.loops;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.List;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;

/**
 * The loop heads of a method: the targets of backward jumps at which the operand stack is empty.
 * For each it finds which locals are defined and live there, and of what kind: the state the
 * executor hashes at the head for loop-state matching (its LOOP_HEAD probe passes their values).
 *
 * <p>A local that is dead at the head (written again before any read, on every path, exceptional
 * ones included) cannot influence what happens from there on, so two visits that differ only in it
 * are the same state. Leaving it out of the hash lets such states match.
 */
public final class LoopHeads {

    /** Value kinds of a local: the JVM computational types. */
    public static final char INT = 'I', LONG = 'J', FLOAT = 'F', DOUBLE = 'D', REFERENCE = 'A';

    /**
     * A loop head.
     *
     * @param at its position (see {@link Positions}): the head is the label before that instruction
     * @param ordinal its position among the targets of the method's backward jumps, part of its
     *     name in the executor (heads with a non-empty stack are skipped but keep their ordinal)
     * @param slots the local slots defined at the head
     * @param kinds the kind of each slot
     */
    public record LoopHead(int at, int ordinal, int[] slots, char[] kinds) {
        @Override
        public boolean equals(Object o) {
            return o instanceof LoopHead h && at == h.at && ordinal == h.ordinal
                    && java.util.Arrays.equals(slots, h.slots) && java.util.Arrays.equals(kinds, h.kinds);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * at + ordinal) + java.util.Arrays.hashCode(slots);
        }

        @Override
        public String toString() {
            return "LoopHead[at=" + at + ", ordinal=" + ordinal + ", slots=" + java.util.Arrays.toString(slots)
                    + ", kinds=" + new String(kinds) + "]";
        }
    }

    private LoopHeads() {}

    /**
     * A call site at which the caller's state can be hashed while the callee runs (loop-state
     * matching across frames): its operand stack holds nothing but the call's receiver and
     * arguments, so that after the call returns, the caller continues from its locals alone.
     *
     * @param ordinal its position among the method's method-call instructions (invokedynamic not
     *     counted), part of its name in the executor
     * @param slots the local slots live after the call returns, normally or by an exception (the
     *     locals the caller may still read)
     * @param kinds the kind of each slot
     */
    public record CallSite(int ordinal, int[] slots, char[] kinds) {
        @Override
        public boolean equals(Object o) {
            return o instanceof CallSite c && ordinal == c.ordinal
                    && java.util.Arrays.equals(slots, c.slots) && java.util.Arrays.equals(kinds, c.kinds);
        }

        @Override
        public int hashCode() {
            return 31 * ordinal + java.util.Arrays.hashCode(slots);
        }

        @Override
        public String toString() {
            return "CallSite[ordinal=" + ordinal + ", slots=" + java.util.Arrays.toString(slots)
                    + ", kinds=" + new String(kinds) + "]";
        }
    }

    /**
     * Finds the loop heads of a method of {@code owner}, in the order of their positions; none if
     * the method cannot be analysed.
     */
    public static List<LoopHead> analyze(String owner, MethodNode mn) {
        List<LoopHead> result = new ArrayList<>();
        if (mn.instructions.size() == 0 || mn.name.equals("<init>")) {
            return result;
        }
        InsnList insns = mn.instructions;
        List<LabelNode> targets = new ArrayList<>();
        Map<LabelNode, Boolean> seen = new IdentityHashMap<>();
        for (AbstractInsnNode n : insns) {
            List<LabelNode> jumps = new ArrayList<>();
            if (n instanceof JumpInsnNode j) {
                if (j.getOpcode() == Opcodes.JSR) {
                    return result; // subroutines: the analysis below does not apply
                }
                jumps.add(j.label);
            } else if (n instanceof TableSwitchInsnNode t) {
                jumps.add(t.dflt);
                jumps.addAll(t.labels);
            } else if (n instanceof LookupSwitchInsnNode l) {
                jumps.add(l.dflt);
                jumps.addAll(l.labels);
            }
            int at = insns.indexOf(n);
            for (LabelNode target : jumps) {
                if (insns.indexOf(target) < at && seen.put(target, Boolean.TRUE) == null) {
                    targets.add(target);
                }
            }
        }
        if (targets.isEmpty()) {
            return result;
        }
        Flow flow = Flow.of(owner, mn);
        if (flow == null) {
            return result;
        }
        targets.sort((a, b) -> Integer.compare(insns.indexOf(a), insns.indexOf(b)));
        int ordinal = 0;
        for (LabelNode target : targets) {
            int i = insns.indexOf(target);
            Frame<BasicValue> f = flow.frames[i];
            int position = ordinal++;
            if (f == null || f.getStackSize() != 0) {
                continue; // unreachable, or values on the operand stack the probe cannot see
            }
            int[][] slots = new int[1][];
            char[][] kinds = new char[1][];
            if (liveLocals(f, flow.live[i], slots, kinds)) {
                result.add(new LoopHead(Positions.of(insns, target), position, slots[0], kinds[0]));
            }
        }
        return result;
    }

    /**
     * Finds the call sites of a method of {@code owner} whose caller state can be hashed (see
     * {@link CallSite}), in the order of their ordinals. Calls to the JDK are left out (the executor
     * hashes no state while JDK code runs); constructors have none (their receiver is
     * uninitialized before the super call).
     */
    public static List<CallSite> callSites(String owner, MethodNode mn) {
        List<CallSite> result = new ArrayList<>();
        if (mn.instructions.size() == 0 || mn.name.equals("<init>")) {
            return result;
        }
        InsnList insns = mn.instructions;
        boolean any = false;
        for (AbstractInsnNode n : insns) {
            if (n instanceof JumpInsnNode j && j.getOpcode() == Opcodes.JSR) {
                return result;
            }
            any |= n instanceof MethodInsnNode m && !isJdk(m.owner);
        }
        if (!any) {
            return result;
        }
        Flow flow = Flow.of(owner, mn);
        if (flow == null) {
            return result;
        }
        int ordinal = 0;
        for (AbstractInsnNode n : insns) {
            if (!(n instanceof MethodInsnNode m)) {
                continue;
            }
            int position = ordinal++;
            if (isJdk(m.owner)) {
                continue;
            }
            int i = insns.indexOf(n);
            Frame<BasicValue> f = flow.frames[i];
            int args = Type.getArgumentTypes(m.desc).length + (m.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1);
            if (f == null || f.getStackSize() != args) {
                continue; // unreachable, or values below the arguments that the probe cannot see
            }
            int[][] slots = new int[1][];
            char[][] kinds = new char[1][];
            // Live on entry to the call = live after it on the normal and the exceptional edges (a
            // call reads and writes no local)
            if (liveLocals(f, flow.live[i], slots, kinds)) {
                result.add(new CallSite(position, slots[0], kinds[0]));
            }
        }
        return result;
    }

    /** Owners whose code the executor never hashes a state in. */
    private static boolean isJdk(String owner) {
        return owner.startsWith("java/") || owner.startsWith("javax/") || owner.startsWith("jdk/")
                || owner.startsWith("sun/") || owner.startsWith("com/sun/");
    }

    /** The analyzer's frames of a method and the locals live on entry to each instruction. */
    private record Flow(Frame<BasicValue>[] frames, BitSet[] live) {
        static Flow of(String owner, MethodNode mn) {
            InsnList insns = mn.instructions;
            List<List<Integer>> successors = new ArrayList<>();
            for (int i = 0; i < insns.size(); i++) {
                successors.add(new ArrayList<>(2));
            }
            Frame<BasicValue>[] frames;
            try {
                frames = new Analyzer<>(new BasicInterpreter()) {
                    @Override
                    protected void newControlFlowEdge(int insn, int successor) {
                        successors.get(insn).add(successor);
                    }

                    @Override
                    protected boolean newControlFlowExceptionEdge(int insn, int successor) {
                        successors.get(insn).add(successor);
                        return true;
                    }
                }.analyze(owner, mn);
            } catch (AnalyzerException | RuntimeException e) {
                return null;
            }
            return new Flow(frames, liveIn(insns, successors, mn.maxLocals));
        }
    }

    /**
     * The locals of a frame that are defined and live, and their kinds, into {@code slots[0]} and
     * {@code kinds[0]}; false if one of them cannot be hashed (a return address).
     */
    private static boolean liveLocals(Frame<BasicValue> f, BitSet live, int[][] slots, char[][] kinds) {
        List<Integer> s = new ArrayList<>();
        StringBuilder k = new StringBuilder();
        for (int i = 0; i < f.getLocals(); i++) {
            BasicValue v = f.getLocal(i);
            if (v == null || v == BasicValue.UNINITIALIZED_VALUE) {
                continue; // never read before it is written again
            }
            if (!live.get(i)) {
                continue; // dead: written again before any read
            }
            if (v == BasicValue.RETURNADDRESS_VALUE) {
                return false;
            }
            char kind = switch (v.getType().getSort()) {
                case Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> INT;
                case Type.LONG -> LONG;
                case Type.FLOAT -> FLOAT;
                case Type.DOUBLE -> DOUBLE;
                case Type.OBJECT, Type.ARRAY -> REFERENCE;
                default -> 0;
            };
            if (kind == 0) {
                return false;
            }
            s.add(i);
            k.append(kind);
        }
        slots[0] = s.stream().mapToInt(Integer::intValue).toArray();
        kinds[0] = k.toString().toCharArray();
        return true;
    }

    /**
     * The locals live on entry to each instruction (index in {@code insns}): read before they are
     * written on some path from there. Backward fixpoint over the control flow the analyzer found,
     * exception edges included. A local read nowhere reachable is dead everywhere.
     */
    static BitSet[] liveIn(InsnList insns, List<List<Integer>> successors, int maxLocals) {
        int n = insns.size();
        BitSet[] use = new BitSet[n];
        BitSet[] def = new BitSet[n];
        BitSet[] in = new BitSet[n];
        for (int i = 0; i < n; i++) {
            use[i] = new BitSet(maxLocals);
            def[i] = new BitSet(maxLocals);
            in[i] = new BitSet(maxLocals);
            AbstractInsnNode insn = insns.get(i);
            if (insn instanceof VarInsnNode v) {
                switch (v.getOpcode()) {
                    case Opcodes.ILOAD, Opcodes.LLOAD, Opcodes.FLOAD, Opcodes.DLOAD, Opcodes.ALOAD, Opcodes.RET ->
                            use[i].set(v.var);
                    default -> def[i].set(v.var);
                }
            } else if (insn instanceof IincInsnNode inc) {
                use[i].set(inc.var);
            }
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = n - 1; i >= 0; i--) {
                BitSet out = new BitSet(maxLocals);
                for (int s : successors.get(i)) {
                    out.or(in[s]);
                }
                out.andNot(def[i]);
                out.or(use[i]);
                if (!out.equals(in[i])) {
                    in[i] = out;
                    changed = true;
                }
            }
        }
        return in;
    }
}
