package de.uzl.its.cfg.extractor;

import de.uzl.its.cfg.model.CFGNode;
import de.uzl.its.cfg.model.ControlFlowGraph;
import sootup.core.typehierarchy.TypeHierarchy;
import sootup.core.types.ClassType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@code uncaught[T]} nodes of one method: one per exception type that leaves it.
 *
 * <p>Both an implicit exception without a local handler (a phantom guard's false branch) and an
 * explicit {@code throw} that no local handler catches end here. Such a node is <em>not</em> an
 * exit: control does not resume at the caller's return site. The inter-procedural pass instead
 * resumes it at whichever caller frame catches {@code T}, which is decided at walk time from the
 * call stack, so the node records {@code T} and its superclass chain for that purpose.
 *
 * <p>One node per type rather than one shared node, because different types unwind to different
 * handlers: sharing would merge an {@code ArithmeticException} caught by one caller with an
 * {@code ArrayIndexOutOfBoundsException} caught by another.
 */
public final class EscapeNodes {

    private final ControlFlowGraph cfg;
    private final TypeHierarchy hierarchy;
    private final Map<ClassType, String> nodeIds = new LinkedHashMap<>();

    public EscapeNodes(ControlFlowGraph cfg, TypeHierarchy hierarchy) {
        this.cfg = cfg;
        this.hierarchy = hierarchy;
    }

    /** The node an exception of type {@code type} leaves the method at, created on first use. */
    public String nodeFor(ClassType type) {
        return nodeIds.computeIfAbsent(type, t -> {
            String id = "uncaught[" + t.getFullyQualifiedName() + "]";
            CFGNode node = new CFGNode(id, "uncaught " + t.getClassName(), "UncaughtException", -1);
            node.setException(t.getFullyQualifiedName(), ExceptionHandlers.chainNamesOf(t, hierarchy));
            cfg.addNode(node);
            return id;
        });
    }

    /** Every exception type leaving the method, with the node it leaves at. */
    public Map<ClassType, String> asMap() {
        return Collections.unmodifiableMap(nodeIds);
    }
}
