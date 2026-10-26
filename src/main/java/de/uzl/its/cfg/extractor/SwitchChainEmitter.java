package de.uzl.its.cfg.extractor;

import de.uzl.its.cfg.model.CFGEdge;
import de.uzl.its.cfg.model.CFGNode;
import de.uzl.its.cfg.model.ControlFlowGraph;
import sootup.core.jimple.common.constant.IntConstant;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.core.jimple.javabytecode.stmt.JSwitchStmt;
import sootup.core.model.Body;

import java.util.List;
import java.util.Map;

/**
 * Expands a {@code switch} into the chain of equality tests that SWAT actually executes.
 *
 * <p>A switch is a single multi-way statement in Jimple, but the JVM does not branch on it in one
 * step and neither does SWAT. Its visitor walks the cases in order and reports a separate branch
 * for each one it tests, stopping at the first match -- {@code LOOKUPSWITCH} over the key array in
 * order, {@code TABLESWITCH} over {@code min..max} ascending. A switch on 3 cases whose value
 * matches the third therefore produces three branches, not one.
 *
 * <p>So the graph has to spell the same tests out, in the same order:
 *
 * <pre>
 *   switch --&gt; case_0 --false--&gt; case_1 --false--&gt; default target
 *                |                  |
 *              true               true
 *                v                  v
 *            target_0           target_1
 * </pre>
 *
 * <p>Emitting one edge per case straight from the switch statement instead, as a naive reading of
 * the statement's successors suggests, is wrong twice over: the branch counts no longer line up
 * with what SWAT reports, and the switch node ends up with several fall-through successors, which
 * the consumer cannot represent -- it allows one per node and rejects the whole graph.
 *
 * <p>The switch statement keeps its own node, which simply falls through into the head of the
 * chain. It carries no branch of its own, and a consumer looking for the next branch walks over
 * it.
 */
public final class SwitchChainEmitter {

    private final ControlFlowGraph cfg;

    private int caseCounter = 0;

    public SwitchChainEmitter(ControlFlowGraph cfg) {
        this.cfg = cfg;
    }

    /**
     * Emits the test chain for one switch and wires the switch statement into it.
     *
     * @param switchStmt   the switch being expanded
     * @param switchNodeId the node id of the switch statement itself
     * @param body         the enclosing body, needed to resolve the switch's targets
     * @param entryIds     where an edge targeting a statement has to arrive, so that a target
     *                     guarded by a phantom guard is entered at the head of its guard chain
     */
    public void emit(JSwitchStmt switchStmt, String switchNodeId, Body body,
            Map<Stmt, String> entryIds) {
        List<IntConstant> values = switchStmt.getValues();
        // getTargetStmts() lists one target per tested value and appends the default target, so it
        // always holds exactly one entry more than there are values.
        List<Stmt> targets = switchStmt.getTargetStmts(body);
        assert targets.size() == values.size() + 1
                : "Switch has " + values.size() + " values but " + targets.size() + " targets: " + switchStmt;

        Stmt defaultTarget = targets.get(values.size());

        if (values.isEmpty()) {
            // A switch with nothing to test never enters SWAT's loop, so it reports no branch at
            // all and control simply continues at the default target.
            cfg.addEdge(new CFGEdge(switchNodeId, entryIds.get(defaultTarget), CFGEdge.EdgeType.NORMAL));
            return;
        }

        String previousCaseId = null;
        for (int i = 0; i < values.size(); i++) {
            String caseId = "case_" + caseCounter++;
            cfg.addNode(createCaseNode(caseId, switchStmt, values.get(i)));

            if (previousCaseId == null) {
                // The switch statement itself does not branch; it falls into the first test.
                cfg.addEdge(new CFGEdge(switchNodeId, caseId, CFGEdge.EdgeType.NORMAL));
            } else {
                // This case is only reached because the previous one did not match.
                cfg.addEdge(new CFGEdge(previousCaseId, caseId, CFGEdge.EdgeType.FALSE_BRANCH, "false"));
            }

            cfg.addEdge(new CFGEdge(caseId, entryIds.get(targets.get(i)),
                    CFGEdge.EdgeType.TRUE_BRANCH, "true"));
            previousCaseId = caseId;
        }

        // Falling out of the last test means no case matched, which is what default means.
        cfg.addEdge(new CFGEdge(previousCaseId, entryIds.get(defaultTarget),
                CFGEdge.EdgeType.FALSE_BRANCH, "false"));
    }

    private static CFGNode createCaseNode(String caseId, JSwitchStmt switchStmt, IntConstant value) {
        String statement = "case " + value + ": " + switchStmt.getKey() + " == " + value;
        CFGNode node = new CFGNode(caseId, statement, "SwitchCase",
                PhantomGuardEmitter.lineNumberOf(switchStmt));
        node.setControl(DataflowFacts.switchCaseControl(switchStmt));
        return node;
    }
}
