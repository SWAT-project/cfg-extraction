package de.uzl.its.cfg.loops;

import de.uzl.its.cfg.loops.LoopHeads.CallSite;
import de.uzl.its.cfg.loops.LoopHeads.LoopHead;
import de.uzl.its.cfg.loops.LoopSummaries.MethodPlans;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * The loop facts of a method: its loop heads and call sites (for loop-state matching) and its loop
 * summaries.
 *
 * <p>The static analysis computes them for the application ahead of time and writes them to a file
 * ({@link LoopFactsJson}, {@link LoopFactsMain}); the executor reads that file when it instruments
 * a method, and computes the facts itself, with this same code, for a method the file does not
 * have (a class generated at run time, a library class, an analysis that did not run). Each
 * method's facts carry the {@link #hash} of the code they were computed from, so the executor
 * never applies facts to code that has changed since.
 *
 * <p>Everything in them that names a place in the method is a position that survives reading the
 * class again: jumps by their ordinal among the method's jump instructions, labels by
 * {@link Positions}.
 */
public final class LoopFacts {

    /** The version of the facts and of their file format; a file of another version is ignored. */
    public static final int VERSION = 3;

    /**
     * The facts of one method.
     *
     * @param hash the {@link #hash} of the method's code
     * @param heads its loop heads
     * @param plans its loop summaries
     * @param calls its call sites whose caller state can be hashed while the callee runs
     */
    public record MethodFacts(String hash, List<LoopHead> heads, MethodPlans plans, List<CallSite> calls) {
        public boolean isEmpty() {
            return heads.isEmpty() && plans.byJump().isEmpty() && calls.isEmpty();
        }
    }

    private LoopFacts() {}

    /** The key of a method in a facts file: owner (internal name), method name and descriptor. */
    public static String key(String owner, String name, String desc) {
        return owner + "." + name + desc;
    }

    /** Computes the facts of a method of {@code owner}. */
    public static MethodFacts analyze(String owner, MethodNode mn) {
        return new MethodFacts(hash(mn), List.copyOf(LoopHeads.analyze(owner, mn)), LoopSummaries.analyze(owner, mn),
                List.copyOf(LoopHeads.callSites(owner, mn)));
    }

    /**
     * A hash of the method's code: its real instructions with their operands, jump targets by
     * position, and its exception handlers. Labels, line numbers and frames do not count, so it does
     * not depend on how the class was read.
     */
    public static String hash(MethodNode mn) {
        InsnList insns = mn.instructions;
        Map<LabelNode, Integer> positions = new IdentityHashMap<>();
        int position = 0;
        for (AbstractInsnNode n : insns) {
            if (n instanceof LabelNode l) {
                positions.put(l, position);
            } else if (Positions.isReal(n)) {
                position++;
            }
        }
        StringBuilder sb = new StringBuilder(mn.access + mn.name + mn.desc).append('\n');
        for (AbstractInsnNode n : insns) {
            if (!Positions.isReal(n)) {
                continue;
            }
            sb.append(n.getOpcode());
            if (n instanceof VarInsnNode v) {
                sb.append(' ').append(v.var);
            } else if (n instanceof IntInsnNode i) {
                sb.append(' ').append(i.operand);
            } else if (n instanceof IincInsnNode i) {
                sb.append(' ').append(i.var).append(' ').append(i.incr);
            } else if (n instanceof LdcInsnNode l) {
                sb.append(' ').append(constant(l.cst));
            } else if (n instanceof TypeInsnNode t) {
                sb.append(' ').append(t.desc);
            } else if (n instanceof FieldInsnNode f) {
                sb.append(' ').append(f.owner).append('.').append(f.name).append(f.desc);
            } else if (n instanceof MethodInsnNode m) {
                sb.append(' ').append(m.owner).append('.').append(m.name).append(m.desc).append(m.itf ? " itf" : "");
            } else if (n instanceof InvokeDynamicInsnNode d) {
                sb.append(' ').append(d.name).append(d.desc).append(' ').append(constant(d.bsm));
                for (Object arg : d.bsmArgs) {
                    sb.append(' ').append(constant(arg));
                }
            } else if (n instanceof MultiANewArrayInsnNode m) {
                sb.append(' ').append(m.desc).append(' ').append(m.dims);
            } else if (n instanceof JumpInsnNode j) {
                sb.append(" @").append(positions.get(j.label));
            } else if (n instanceof TableSwitchInsnNode t) {
                sb.append(' ').append(t.min).append(' ').append(t.max).append(" @").append(positions.get(t.dflt));
                t.labels.forEach(l -> sb.append(" @").append(positions.get(l)));
            } else if (n instanceof LookupSwitchInsnNode l) {
                sb.append(" @").append(positions.get(l.dflt));
                for (int k = 0; k < l.keys.size(); k++) {
                    sb.append(' ').append(l.keys.get(k)).append(" @").append(positions.get(l.labels.get(k)));
                }
            }
            sb.append('\n');
        }
        for (TryCatchBlockNode tc : mn.tryCatchBlocks) {
            sb.append("try @").append(positions.get(tc.start)).append(" @").append(positions.get(tc.end))
                    .append(" @").append(positions.get(tc.handler)).append(' ').append(tc.type).append('\n');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A constant with its kind, so that 1 (int) and 1L or "1" differ. */
    private static String constant(Object c) {
        if (c instanceof Type t) {
            return "T" + t.getDescriptor();
        }
        if (c instanceof Handle h) {
            return "H" + h.getTag() + h.getOwner() + "." + h.getName() + h.getDesc() + (h.isInterface() ? "itf" : "");
        }
        if (c instanceof Double d) {
            return "D" + Long.toHexString(Double.doubleToRawLongBits(d));
        }
        if (c instanceof Float f) {
            return "F" + Integer.toHexString(Float.floatToRawIntBits(f));
        }
        if (c instanceof String s) {
            return "S" + s.length() + ":" + s;
        }
        return c.getClass().getSimpleName().charAt(0) + String.valueOf(c);
    }

    /** The facts of a class: those of each of its methods that has code, by {@link #key}. */
    public static Map<String, MethodFacts> analyzeClass(org.objectweb.asm.tree.ClassNode cn) {
        Map<String, MethodFacts> facts = new HashMap<>();
        for (MethodNode mn : cn.methods) {
            if (mn.instructions.size() > 0) {
                try {
                    facts.put(key(cn.name, mn.name, mn.desc), analyze(cn.name, mn));
                } catch (RuntimeException e) {
                    // left out: the executor analyses it itself (and fails the same way)
                }
            }
        }
        return facts;
    }
}
