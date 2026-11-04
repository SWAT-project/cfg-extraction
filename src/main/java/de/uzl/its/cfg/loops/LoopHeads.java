package de.uzl.its.cfg.loops;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.List;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;

/**
 * The loop heads of a method: the targets of backward jumps at which the operand stack is empty.
 * For each it finds which locals are defined there and of what kind: the state the executor hashes
 * at the head for loop-state matching (its LOOP_HEAD probe passes their values).
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
        Frame<BasicValue>[] frames;
        try {
            frames = new Analyzer<>(new BasicInterpreter()).analyze(owner, mn);
        } catch (AnalyzerException | RuntimeException e) {
            return result;
        }
        targets.sort((a, b) -> Integer.compare(insns.indexOf(a), insns.indexOf(b)));
        int ordinal = 0;
        for (LabelNode target : targets) {
            Frame<BasicValue> f = frames[insns.indexOf(target)];
            int position = ordinal++;
            if (f == null || f.getStackSize() != 0) {
                continue; // unreachable, or values on the operand stack the probe cannot see
            }
            List<Integer> slots = new ArrayList<>();
            StringBuilder kinds = new StringBuilder();
            boolean usable = true;
            for (int i = 0; i < f.getLocals(); i++) {
                BasicValue v = f.getLocal(i);
                if (v == null || v == BasicValue.UNINITIALIZED_VALUE) {
                    continue; // never read before it is written again
                }
                if (v == BasicValue.RETURNADDRESS_VALUE) {
                    usable = false;
                    break;
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
                    usable = false;
                    break;
                }
                slots.add(i);
                kinds.append(kind);
            }
            if (!usable) {
                continue;
            }
            result.add(new LoopHead(Positions.of(insns, target), position,
                    slots.stream().mapToInt(Integer::intValue).toArray(), kinds.toString().toCharArray()));
        }
        return result;
    }
}
