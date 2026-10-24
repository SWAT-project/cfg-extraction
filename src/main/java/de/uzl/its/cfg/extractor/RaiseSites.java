package de.uzl.its.cfg.extractor;

import de.uzl.its.cfg.model.CFGNode;
import de.uzl.its.cfg.model.ControlFlowGraph;
import de.uzl.its.cfg.staticcheck.SiteFacts;
import java.util.*;
import sootup.core.jimple.common.expr.AbstractInvokeExpr;
import sootup.core.jimple.common.expr.JCastExpr;
import sootup.core.jimple.common.expr.JLengthExpr;
import sootup.core.jimple.common.ref.JArrayRef;
import sootup.core.jimple.common.ref.JInstanceFieldRef;
import sootup.core.jimple.common.stmt.JAssignStmt;
import sootup.core.jimple.common.stmt.JThrowStmt;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.core.jimple.javabytecode.stmt.JEnterMonitorStmt;
import sootup.core.jimple.javabytecode.stmt.JExitMonitorStmt;
import sootup.core.model.SootMethod;
import sootup.core.types.ReferenceType;

/**
 * Marks which nodes of a method cannot raise a RuntimeException, from the property pre-check's
 * value analysis ({@link SiteFacts}): {@code cannotRaise} on a node.
 *
 * <ul>
 *   <li>A phantom guard is matched by its exact branch key (set by {@link BranchKeys} before):
 *       {@code true} if the check proves its instruction cannot raise; otherwise left unset.
 *   <li>A statement that may raise one itself (array access, {@code arraylength}, instance field
 *       access, reference cast, {@code throw}, monitors, calls: {@link #siteKinds}) is matched by
 *       method, line and kind: {@code true} if every instruction of each of its kinds on its line
 *       cannot raise, else {@code false}. A line is only matched if its kinds are the same on
 *       both sides; SootUp occasionally attributes a statement to the wrong line, and the kinds
 *       of a wrong line rarely add up.
 * </ul>
 *
 * Every other node gets no {@code cannotRaise}. A site wrongly marked {@code true} lets SWAT prune
 * a path to a violation, so anything unmatched stays a site that may raise.
 */
final class RaiseSites {

    private RaiseSites() {
    }

    /** Counts over a run, for the graph's metadata: statement sites, those on a matched line, safe ones; safe guards. */
    static final class Stats {
        int sites;
        int matched;
        int safe;
        int safeGuards;

        Map<String, Integer> asMap() {
            return Map.of("sites", sites, "matched", matched, "safe", safe, "safeGuards", safeGuards);
        }
    }

    static void annotate(SootMethod method, Collection<Stmt> stmts, Map<Stmt, String> stmtToId, ControlFlowGraph cfg,
            SiteFacts facts, Stats stats) {
        String methodKey = method.getDeclaringClassType().getFullyQualifiedName().replace('.', '/') + "."
                + method.getName() + IntraProceduralCFGExtractor.jvmDescriptor(method);

        for (CFGNode node : cfg.getNodes()) {
            if (!"PhantomGuard".equals(node.getStmtType()) || node.getKey() == null) {
                continue;
            }
            String key = node.getKey();
            int hash = key.indexOf('#');
            if (hash >= 0) {
                key = key.substring(0, hash); // a dimension of a multi-dimensional allocation
            }
            if (facts.guardIsSafe(key)) {
                node.setCannotRaise(true);
                stats.safeGuards++;
            }
        }

        Map<Integer, List<String>> graphKinds = new HashMap<>();
        Map<Stmt, List<String>> kindsOf = new IdentityHashMap<>();
        for (Stmt stmt : stmts) {
            List<String> kinds = siteKinds(stmt);
            if (kinds.isEmpty()) {
                continue;
            }
            kindsOf.put(stmt, kinds);
            graphKinds.computeIfAbsent(PhantomGuardEmitter.lineNumberOf(stmt), l -> new ArrayList<>()).addAll(kinds);
        }
        for (Map.Entry<Stmt, List<String>> e : kindsOf.entrySet()) {
            int line = PhantomGuardEmitter.lineNumberOf(e.getKey());
            boolean matched = sorted(graphKinds.get(line)).equals(sorted(facts.sitesOn(methodKey, line)));
            boolean safe = matched && e.getValue().stream().allMatch(k -> facts.siteIsSafe(methodKey, line, k));
            stats.sites++;
            stats.matched += matched ? 1 : 0;
            stats.safe += safe ? 1 : 0;
            String id = stmtToId.get(e.getKey());
            if (id != null) {
                cfg.getNode(id).ifPresent(node -> node.setCannotRaise(safe));
            }
        }
    }

    /**
     * The statement site kinds of a Jimple statement, named as {@link SiteFacts#siteKind} names the
     * bytecode instructions it came from.
     */
    static List<String> siteKinds(Stmt stmt) {
        List<String> kinds = new ArrayList<>();
        if (stmt instanceof JThrowStmt) {
            kinds.add("throw");
        } else if (stmt instanceof JEnterMonitorStmt || stmt instanceof JExitMonitorStmt) {
            kinds.add("monitor");
        }
        if (stmt instanceof JAssignStmt assign) {
            var left = assign.getLeftOp();
            var right = assign.getRightOp();
            if (left instanceof JInstanceFieldRef f) {
                kinds.add("putfield:" + f.getFieldSignature().getName());
            } else if (left instanceof JArrayRef) {
                kinds.add("arr:store");
            }
            if (right instanceof JInstanceFieldRef f) {
                kinds.add("getfield:" + f.getFieldSignature().getName());
            } else if (right instanceof JArrayRef) {
                kinds.add("arr:load");
            } else if (right instanceof JLengthExpr) {
                kinds.add("alen");
            } else if (right instanceof JCastExpr c && c.getType() instanceof ReferenceType) {
                kinds.add("cast");
            }
        }
        if (stmt.isInvokableStmt() && stmt.asInvokableStmt().getInvokeExpr().isPresent()) {
            AbstractInvokeExpr invoke = stmt.asInvokableStmt().getInvokeExpr().get();
            kinds.add("call:" + BranchKeys.nameAndDescriptor(invoke.getMethodSignature()));
        }
        return kinds;
    }

    private static List<String> sorted(List<String> kinds) {
        List<String> copy = new ArrayList<>(kinds == null ? List.of() : kinds);
        Collections.sort(copy);
        return copy;
    }
}
