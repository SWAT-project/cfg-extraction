package de.uzl.its.cfg.staticcheck;

import java.util.*;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

/**
 * Which instructions of the application cannot raise a RuntimeException, by the check's value
 * analysis ({@link ExceptionFreedomCheck#siteFacts}), named so that the static pre-analysis graph
 * can find the same instructions.
 *
 * <p>Two kinds of names:
 *
 * <ul>
 *   <li><b>Guards</b> (array access, integer division, array allocation), which SWAT reports as
 *       branches: their exact branch key {@code owner.name(desc)|line|kind|ordinal}, with kind
 *       {@code arr}, {@code div} or {@code newarr} and the ordinal counted per line and kind in
 *       bytecode order, as SWAT's executor and the extractor's BranchKeys count it.
 *   <li><b>Statement sites</b> (every instruction that may raise one itself: array access,
 *       {@code arraylength}, instance field access, {@code checkcast}, {@code athrow}, monitors,
 *       calls): grouped by method, line and {@link #siteKind}. A group is safe only if every
 *       instruction in it is. The graph's statements are matched to groups by line, and a line is
 *       only used if the kinds on it are the same on both sides ({@link #sitesOn}): SootUp's
 *       statement order is not the bytecode order, so no ordinals are needed this way.
 * </ul>
 *
 * Everything not named safe may raise one. An array access is both a guard and a statement site,
 * with the same fact (null array and bounds are decided together).
 */
public final class SiteFacts {

    /** "closed": the check's facts were used for the methods reachable from Main.main; "local": none. */
    public final String world;
    private final Set<String> safeGuards = new HashSet<>();
    private final Map<String, Map<Integer, List<String>>> sites = new HashMap<>();
    private final Set<String> unsafeSites = new HashSet<>();
    private final String reason;

    SiteFacts(String world) {
        this(world, null);
    }

    private SiteFacts(String world, String reason) {
        this.world = world;
        this.reason = reason;
    }

    /** No facts at all (every site may raise). */
    static SiteFacts none(String reason) {
        return new SiteFacts("none", reason);
    }

    void addSafeGuard(String branchKey) {
        safeGuards.add(branchKey);
    }

    void addSite(String methodKey, int line, String kind, boolean safe) {
        sites.computeIfAbsent(methodKey, k -> new HashMap<>()).computeIfAbsent(line, l -> new ArrayList<>()).add(kind);
        if (!safe) unsafeSites.add(methodKey + "|" + line + "|" + kind);
    }

    /** Whether the guard with this branch key (without a #suffix) cannot raise. */
    public boolean guardIsSafe(String branchKey) {
        return safeGuards.contains(branchKey);
    }

    /** The statement site kinds of a line of a method in bytecode order, or an empty list. */
    public List<String> sitesOn(String methodKey, int line) {
        return sites.getOrDefault(methodKey, Map.of()).getOrDefault(line, List.of());
    }

    /** Whether no instruction of this kind on this line can raise; false if there is none. */
    public boolean siteIsSafe(String methodKey, int line, String kind) {
        return sitesOn(methodKey, line).contains(kind) && !unsafeSites.contains(methodKey + "|" + line + "|" + kind);
    }

    /** The guard kind of the instruction (as BranchKeys names it), or null. */
    public static String guardKind(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        if ((op >= Opcodes.IALOAD && op <= Opcodes.SALOAD) || (op >= Opcodes.IASTORE && op <= Opcodes.SASTORE)) return "arr";
        if (op == Opcodes.IDIV || op == Opcodes.IREM || op == Opcodes.LDIV || op == Opcodes.LREM) return "div";
        if (op == Opcodes.NEWARRAY || op == Opcodes.ANEWARRAY || op == Opcodes.MULTIANEWARRAY) return "newarr";
        return null;
    }

    /**
     * The statement site kind of the instruction, or null if it raises no RuntimeException itself:
     * {@code arr:load}, {@code arr:store}, {@code alen}, {@code getfield:<name>},
     * {@code putfield:<name>}, {@code cast}, {@code throw}, {@code monitor},
     * {@code call:<name><desc>} (as BranchKeys names call sites). Division and array allocation
     * only raise through their guard.
     */
    public static String siteKind(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        if (op >= Opcodes.IALOAD && op <= Opcodes.SALOAD) return "arr:load";
        if (op >= Opcodes.IASTORE && op <= Opcodes.SASTORE) return "arr:store";
        switch (op) {
            case Opcodes.ARRAYLENGTH: return "alen";
            case Opcodes.GETFIELD: return "getfield:" + ((FieldInsnNode) insn).name;
            case Opcodes.PUTFIELD: return "putfield:" + ((FieldInsnNode) insn).name;
            case Opcodes.CHECKCAST: return "cast";
            case Opcodes.ATHROW: return "throw";
            case Opcodes.MONITORENTER: case Opcodes.MONITOREXIT: return "monitor";
            default: break;
        }
        if (insn instanceof MethodInsnNode) return "call:" + ((MethodInsnNode) insn).name + ((MethodInsnNode) insn).desc;
        if (insn instanceof InvokeDynamicInsnNode) return "call:" + ((InvokeDynamicInsnNode) insn).name + ((InvokeDynamicInsnNode) insn).desc;
        return null;
    }

    /** One JSON object: the world, the safe guard keys and every site group with its fact. */
    public String toJson() {
        StringBuilder sb = new StringBuilder("{\"world\": \"").append(world).append('"');
        if (reason != null) sb.append(", \"reason\": \"").append(escape(reason)).append('"');
        sb.append(", \"safeGuards\": [");
        String sep = "";
        for (String k : new TreeSet<>(safeGuards)) {
            sb.append(sep).append('"').append(escape(k)).append('"');
            sep = ", ";
        }
        sb.append("], \"sites\": [");
        sep = "";
        for (String m : new TreeSet<>(sites.keySet())) {
            for (Map.Entry<Integer, List<String>> e : new TreeMap<>(sites.get(m)).entrySet()) {
                for (String kind : new TreeSet<>(e.getValue())) {
                    String group = m + "|" + e.getKey() + "|" + kind;
                    sb.append(sep).append("{\"site\": \"").append(escape(group)).append("\", \"safe\": ")
                            .append(!unsafeSites.contains(group)).append('}');
                    sep = ", ";
                }
            }
        }
        return sb.append("]}").toString();
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.toString();
    }
}
