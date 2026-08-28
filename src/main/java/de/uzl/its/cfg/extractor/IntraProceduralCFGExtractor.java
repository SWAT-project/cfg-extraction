package de.uzl.its.cfg.extractor;

import de.uzl.its.cfg.model.CFGEdge;
import de.uzl.its.cfg.model.CFGNode;
import de.uzl.its.cfg.model.ControlFlowGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sootup.core.jimple.common.expr.JNewExpr;
import sootup.core.jimple.common.stmt.BranchingStmt;
import sootup.core.jimple.common.stmt.JAssignStmt;
import sootup.core.jimple.common.stmt.JIfStmt;
import sootup.core.jimple.common.stmt.JThrowStmt;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.core.model.Body;
import sootup.core.model.SootMethod;
import sootup.core.types.ClassType;
import sootup.java.core.exceptions.StmtExceptionAnalyzer;
import sootup.java.core.views.JavaView;

import java.util.*;

/**
 * Extracts intra-procedural control flow graphs (single method analysis).
 * Each method gets its own CFG showing the flow of statements within that method.
 */
public class IntraProceduralCFGExtractor implements CFGExtractor {

    private static final Logger logger = LoggerFactory.getLogger(IntraProceduralCFGExtractor.class);

    @Override
    public ControlFlowGraph extractCFG(SootMethod method, JavaView view) {
        logger.info("Extracting intra-procedural CFG for method: {}", method.getSignature());

        String methodSig = method.getSignature().toString();
        String className = method.getDeclaringClassType().getClassName();

        ControlFlowGraph cfg = new ControlFlowGraph(methodSig, className, getAnalysisType());

        // Check if method has a body (not abstract/native)
        if (!method.hasBody()) {
            logger.warn("Method {} has no body (abstract or native)", methodSig);
            return cfg;
        }

        Body body = method.getBody();
        // SootUp's StmtGraph was renamed to sootup.core.graph.ControlFlowGraph in 3.0.0;
        // it is fully qualified here because the name clashes with our own model class.
        sootup.core.graph.ControlFlowGraph<?> stmtGraph = body.getControlFlowGraph();
        var stmts = stmtGraph.getStmts();

        // Create nodes for each statement
        Map<Stmt, String> stmtToId = new HashMap<>();
        int nodeCounter = 0;

        for (Stmt stmt : stmts) {
            String nodeId = "node_" + nodeCounter++;
            stmtToId.put(stmt, nodeId);

            CFGNode node = createNode(nodeId, stmt);
            cfg.addNode(node);

            logger.debug("Created node: {} for stmt: {}", nodeId, stmt);
        }

        // Set entry node (first statement)
        cfg.setEntryNode(stmtToId.get(stmtGraph.getStartingStmt()));

        StmtExceptionAnalyzer exceptionAnalyzer = new StmtExceptionAnalyzer(view.getTypeHierarchy());

        // Create edges based on control flow
        for (Stmt stmt : stmts) {
            String sourceId = stmtToId.get(stmt);
            List<Stmt> successors = stmtGraph.successors(stmt);
            Set<ClassType> possibleExceptions = new HashSet<>(
                    exceptionAnalyzer.mightThrow(stmt, stmtGraph).getExceptions());
            // TODO: SootUp is bugged and mightThrow() can fail with a NPE. Figure out what to do in this case.
            // also, how does SWAT even find ArrayIndex bugs right now, if they dont have an explicit check & throw in the bytecode??
            Map<ClassType, Stmt> exceptionHandlers = stmtGraph.exceptionalSuccessors(stmt);

            possibleExceptions.retainAll(exceptionHandlers.keySet());
            if (!possibleExceptions.isEmpty()) {
                // Exception flow
                for (ClassType exception : possibleExceptions) {
                    cfg.addEdge(new CFGEdge(sourceId, stmtToId.get(exceptionHandlers.get(exception)),
                            CFGEdge.EdgeType.EXCEPTION));
                }
            } else if (successors.isEmpty()) {
                // This is an exit node (return, throw, etc.)
                cfg.addExitNode(sourceId);
            } else if (stmt instanceof JIfStmt) {
                // Conditional branch - distinguish true/false branches
                handleConditionalBranch((JIfStmt) stmt, successors, sourceId, stmtToId, cfg);
            } else if (stmt instanceof BranchingStmt) {
                // Other branching statements (goto, switch, etc.)
                for (Stmt successor : successors) {
                    cfg.addEdge(new CFGEdge(sourceId, stmtToId.get(successor), CFGEdge.EdgeType.NORMAL));
                }
            } else {
                // Normal flow
                for (Stmt successor : successors) {
                    cfg.addEdge(new CFGEdge(sourceId, stmtToId.get(successor), CFGEdge.EdgeType.NORMAL));
                }
            }
        }

        // Find assertions
        var assertionPointIds = new ArrayList<String>();
        for (var throwStmt : stmts) {
            if (throwStmt instanceof JThrowStmt) {
                var thrownValue = throwStmt.getUses().get(0);

                // find assignment of thrown value
                for (var assignStmt : stmts) {
                    if (assignStmt instanceof JAssignStmt) {
                        var leftOp = ((JAssignStmt) assignStmt).getLeftOp();
                        var rightOp = ((JAssignStmt) assignStmt).getRightOp();

                        if (leftOp.equals(thrownValue) && rightOp instanceof JNewExpr) {
                            var newExpr = (JNewExpr) rightOp;
                            if (newExpr.getType().getFullyQualifiedName().equals("java.lang.AssertionError")) {
                                assertionPointIds.add(stmtToId.get(assignStmt));
                            }
                        }
                    }
                }
            }
        }

        // Add metadata
        cfg.addMetadata("statementCount", stmts.size());
        cfg.addMetadata("localCount", body.getLocalCount());
        cfg.addMetadata("assertionPointIds", assertionPointIds);

        logger.info("Extracted CFG with {} nodes and {} edges",
                cfg.getNodes().size(), cfg.getEdges().size());

        return cfg;
    }

    private CFGNode createNode(String nodeId, Stmt stmt) {
        String stmtString = stmt.toString();
        String stmtType = stmt.getClass().getSimpleName();

        // Try to get line number if available
        int lineNumber = -1;
        try {
            if (stmt.getPositionInfo() != null && stmt.getPositionInfo().getStmtPosition() != null) {
                lineNumber = stmt.getPositionInfo().getStmtPosition().getFirstLine();
            }
        } catch (Exception e) {
            // Position info not available
        }

        return new CFGNode(nodeId, stmtString, stmtType, lineNumber);
    }

    private void handleConditionalBranch(JIfStmt ifStmt, List<Stmt> successors,
            String sourceId, Map<Stmt, String> stmtToId,
            ControlFlowGraph cfg) {
        // For if statements, we have two successors:
        // - The target of the if (true branch)
        // - The fall-through (false branch)

        // Label successors by their index, which JIfStmt pins down: the fall-through
        // (false branch) comes first, the branch target (true branch) second.
        for (int i = 0; i < successors.size(); i++) {
            Stmt successor = successors.get(i);
            String targetId = stmtToId.get(successor);
            assert targetId != null : "Successor statement not found in stmtToId map for conditional branch: "
                    + successor + " (source: " + ifStmt + ")";

            CFGEdge.EdgeType edgeType;
            String label;

            if (i == JIfStmt.FALSE_BRANCH_IDX) {
                edgeType = CFGEdge.EdgeType.FALSE_BRANCH;
                label = "false";
            } else {
                // JIfStmt.TRUE_BRANCH_IDX and any further successors: true branch
                edgeType = CFGEdge.EdgeType.TRUE_BRANCH;
                label = "true";
            }

            CFGEdge edge = new CFGEdge(sourceId, targetId, edgeType, label);
            cfg.addEdge(edge);
        }
    }

    @Override
    public ControlFlowGraph.AnalysisType getAnalysisType() {
        return ControlFlowGraph.AnalysisType.INTRA_PROCEDURAL;
    }
}
