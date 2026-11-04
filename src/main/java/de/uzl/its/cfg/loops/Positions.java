package de.uzl.its.cfg.loops;

import java.util.Map;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;

/**
 * Positions in a method that survive reading it again: a label's position is the number of real
 * instructions (not labels, line numbers or frames) before it, so it does not depend on how the
 * method was read (frames expanded or skipped, line numbers kept or not). The loop facts name their
 * labels by position, and the executor finds the label again with {@link #labels}.
 *
 * <p>Several labels can stand between the same two instructions (a ClassReader makes one label per
 * offset, so only in hand-built code); a position then names the last of them, the one that every
 * jump to any of them passes.
 */
public final class Positions {

    private Positions() {}

    /** Whether a node is a real instruction (not a label, line number or frame). */
    public static boolean isReal(AbstractInsnNode n) {
        return !(n instanceof LabelNode || n instanceof LineNumberNode || n instanceof FrameNode);
    }

    /** The position of a label: the number of real instructions before it. */
    public static int of(InsnList insns, LabelNode label) {
        int position = 0;
        for (AbstractInsnNode n = label.getPrevious(); n != null; n = n.getPrevious()) {
            if (isReal(n)) {
                position++;
            }
        }
        return position;
    }

    /** The label of every position that has one (the last label before that instruction). */
    public static Map<Integer, LabelNode> labels(InsnList insns) {
        Map<Integer, LabelNode> labels = new java.util.HashMap<>();
        int position = 0;
        for (AbstractInsnNode n : insns) {
            if (n instanceof LabelNode l) {
                labels.put(position, l);
            } else if (isReal(n)) {
                position++;
            }
        }
        return labels;
    }
}
