package de.uzl.its.cfg.extractor;

import de.uzl.its.cfg.extractor.SwatGuardClassifier.Guard;
import de.uzl.its.cfg.model.CFGEdge;
import de.uzl.its.cfg.model.CFGNode;
import de.uzl.its.cfg.model.ControlFlowGraph;
import sootup.core.jimple.common.ref.JArrayRef;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.core.typehierarchy.TypeHierarchy;
import sootup.core.types.ClassType;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Inserts SWAT's phantom branches into a method CFG.
 *
 * <p>SWAT guards every risky instruction with a branch on the safe condition rather than modelling
 * the implicit exception (see {@link SwatGuardClassifier}). This emitter mirrors that: for each
 * guard of a statement it inserts a node <em>before</em> the statement, whose
 * {@link CFGEdge.EdgeType#PHANTOM_TRUE_BRANCH} continues to the guarded statement and whose
 * {@link CFGEdge.EdgeType#PHANTOM_FALSE_BRANCH} leads to the exception continuation. A statement
 * with several guards gets a chain of them, in SWAT's emission order:
 *
 * <pre>
 *   pred --&gt; guard_0 --true--&gt; guard_1 --true--&gt; stmt
 *              |                  |
 *            false              false
 * </pre>
 *
 * <p>Guards are emitted whether or not the method has a matching handler, because SWAT's visitor
 * guards unconditionally. Without a handler the false branch leads to the method's
 * {@code uncaught[T]} node for the guard's exception type (see {@link EscapeNodes}): the exception
 * leaves the method, so unlike a return it must <em>not</em> be registered as an exit node, or the
 * inter-procedural pass would give it a RETURN edge back into the caller's return site. Where it
 * does resume -- the handler of whichever caller frame catches it -- is decided at walk time.
 */
public final class PhantomGuardEmitter {

    private final ControlFlowGraph cfg;
    private final sootup.core.graph.ControlFlowGraph<?> stmtGraph;
    private final TypeHierarchy hierarchy;
    private final EscapeNodes escapes;

    private int guardCounter = 0;

    public PhantomGuardEmitter(ControlFlowGraph cfg,
            sootup.core.graph.ControlFlowGraph<?> stmtGraph, TypeHierarchy hierarchy,
            EscapeNodes escapes) {
        this.cfg = cfg;
        this.stmtGraph = stmtGraph;
        this.hierarchy = hierarchy;
        this.escapes = escapes;
    }

    /**
     * Adds the guard nodes and phantom edges for every guarded statement in {@code stmts}.
     *
     * @param stmtToId the node id of each statement, i.e. where its <em>outgoing</em> edges start
     * @return the node id at which each statement's <em>incoming</em> edges must arrive: the head
     *         of its guard chain if it has one, otherwise the statement's own node. Callers must
     *         route every edge that targets a statement through this map, or control flow would
     *         bypass the guards.
     */
    public Map<Stmt, String> emit(Collection<Stmt> stmts, Map<Stmt, String> stmtToId) {
        Map<Stmt, String> entryIds = new HashMap<>();
        // Guard node -> the statement it guards. Insertion-ordered so the emitted false branches
        // follow the guard chains rather than hash order.
        Map<String, Stmt> guardedStmts = new LinkedHashMap<>();
        Map<String, ClassType> guardExceptions = new LinkedHashMap<>();

        for (Stmt stmt : stmts) {
            java.util.List<Guard> guards = SwatGuardClassifier.guardsOf(stmt);
            if (guards.isEmpty()) {
                entryIds.put(stmt, stmtToId.get(stmt));
                continue;
            }
            String head = null;
            String previous = null;
            for (Guard guard : guards) {
                String guardId = "guard_" + guardCounter++;
                cfg.addNode(createGuardNode(guardId, guard, stmt));
                if (previous == null) {
                    head = guardId;
                } else {
                    cfg.addEdge(new CFGEdge(previous, guardId,
                            CFGEdge.EdgeType.PHANTOM_TRUE_BRANCH, "safe"));
                }
                previous = guardId;
                guardedStmts.put(guardId, stmt);
                guardExceptions.put(guardId, guard.kind().getExceptionType());
            }
            cfg.addEdge(new CFGEdge(previous, stmtToId.get(stmt),
                    CFGEdge.EdgeType.PHANTOM_TRUE_BRANCH, "safe"));
            entryIds.put(stmt, head);
        }

        // The false branches are emitted only once every entry id is known, since a handler
        // statement could in principle be guarded itself and must then be entered at its chain.
        for (Map.Entry<String, ClassType> guard : guardExceptions.entrySet()) {
            String guardId = guard.getKey();
            ClassType exceptionType = guard.getValue();
            // Guard exception types are precise -- the JVM throws exactly an
            // ArrayIndexOutOfBoundsException -- so the most specific catching handler is the one.
            Stmt handler = ExceptionHandlers.handlerFor(exceptionType,
                    stmtGraph.exceptionalSuccessors(guardedStmts.get(guardId)), hierarchy);
            String target = handler != null ? entryIds.get(handler) : escapes.nodeFor(exceptionType);
            // Kept short to stay readable when drawn along the edge; the guard node's statement
            // already names the exception type.
            cfg.addEdge(new CFGEdge(guardId, target,
                    CFGEdge.EdgeType.PHANTOM_FALSE_BRANCH, "exception"));
        }

        return entryIds;
    }

    private static CFGNode createGuardNode(String guardId, Guard guard, Stmt guardedStmt) {
        String statement = "guard[" + guard.kind().getExceptionType().getClassName() + "]: "
                + conditionOf(guard);
        CFGNode node = new CFGNode(guardId, statement, "PhantomGuard", lineNumberOf(guardedStmt));
        node.setControl(DataflowFacts.guardControl(guard.guarded()));
        return node;
    }

    /** The safe condition, mirroring the constraint SWAT builds for this guard. */
    private static String conditionOf(Guard guard) {
        switch (guard.kind()) {
            case ARRAY_BOUNDS:
                JArrayRef arrayRef = (JArrayRef) guard.guarded();
                return "0 <= " + arrayRef.getIndex()
                        + " && " + arrayRef.getIndex() + " < lengthof " + arrayRef.getBase();
            case ARRAY_SIZE:
                return guard.guarded() + " >= 0";
            case DIVISION_BY_ZERO:
                return guard.guarded() + " != 0";
            default:
                throw new IllegalStateException("Unhandled guard kind: " + guard.kind());
        }
    }

    /** The source line of {@code stmt}, or -1 when the bytecode carries no position info. */
    static int lineNumberOf(Stmt stmt) {
        try {
            if (stmt.getPositionInfo() != null && stmt.getPositionInfo().getStmtPosition() != null) {
                return stmt.getPositionInfo().getStmtPosition().getFirstLine();
            }
        } catch (Exception e) {
            // Position info not available
        }
        return -1;
    }
}
