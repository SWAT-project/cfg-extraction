package de.uzl.its.cfg.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Represents an edge (control flow) between two nodes in the CFG.
 * Extensible for inter-procedural analysis by adding call/return edge types.
 */
public class CFGEdge {
    private final String source;
    private final String target;
    private final EdgeType type;
    private String label;

    /**
     * For a CALL edge only: where the caller resumes once the callee returns normally.
     *
     * <p>A method body is in the graph once however many call sites it has, so its exit nodes have
     * a RETURN edge to every one of their return sites and the graph alone cannot say which one a
     * given return goes to. The consumer walks the graph with a call stack instead: it pushes this
     * call site on the CALL edge and, at an exit, resumes at the return address of the frame on
     * top. The RETURN edges stay in the graph for backward reachability.
     */
    private String returnSite;

    /**
     * For a CALL edge only: the handler each exception type is caught by at this call site, keyed
     * by the caught type's fully qualified name.
     *
     * <p>Taken from the call statement's own exceptional successors, so it needs no knowledge of the
     * callee. When the walk reaches an {@code uncaught[T]} node it pops frames until one of them
     * has a handler for {@code T} or one of its superclasses (see
     * {@link CFGNode#getExceptionTypeChain()}).
     */
    private Map<String, String> handlers;

    /**
     * For a CALL edge only: for each pseudo exception type (an exception whose type the graph does
     * not know, e.g. "?Exception"), the handlers at this call site that might catch it. Absent if
     * there are none.
     */
    private Map<String, List<String>> unknownHandlers;

    public enum EdgeType {
        NORMAL,           // Regular control flow
        TRUE_BRANCH,      // True branch of conditional
        FALSE_BRANCH,     // False branch of conditional
        EXCEPTION,        // Exception handling flow SootUp infers; not followed by the walk
        THROW,            // An explicit throw, to its local handler or to uncaught[T]

        // Phantom branches: SWAT turns some implicit exceptions into explicit branches before
        // the risky instruction (see SwatGuardClassifier). These mirror them, and are kept
        // distinct from TRUE_BRANCH/FALSE_BRANCH so that a synthesized guard stays
        // distinguishable from a conditional that exists in the source.
        PHANTOM_TRUE_BRANCH,  // Guard held: the risky instruction executes
        PHANTOM_FALSE_BRANCH, // Guard failed: the implicit exception is raised

        // Extension points for inter-procedural analysis:
        CALL,            // Method call edge
        RETURN,          // Method return edge
        EXCEPTIONAL_RETURN, // uncaught[T] -> a handler in some caller that catches T
        CALLBACK         // Callback invocation
    }

    public CFGEdge(String source, String target, EdgeType type) {
        this.source = source;
        this.target = target;
        this.type = type;
    }

    public CFGEdge(String source, String target, EdgeType type, String label) {
        this.source = source;
        this.target = target;
        this.type = type;
        this.label = label;
    }

    public String getSource() {
        return source;
    }

    public String getTarget() {
        return target;
    }

    public EdgeType getType() {
        return type;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getReturnSite() {
        return returnSite;
    }

    public Map<String, String> getHandlers() {
        return handlers;
    }

    public void setUnknownHandlers(Map<String, List<String>> unknownHandlers) {
        this.unknownHandlers = unknownHandlers;
    }

    public Map<String, List<String>> getUnknownHandlers() {
        return unknownHandlers;
    }

    public void setCallContinuations(String returnSite, Map<String, String> handlers) {
        this.returnSite = returnSite;
        this.handlers = handlers;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CFGEdge cfgEdge = (CFGEdge) o;
        return Objects.equals(source, cfgEdge.source) &&
                Objects.equals(target, cfgEdge.target) &&
                type == cfgEdge.type;
    }

    @Override
    public int hashCode() {
        return Objects.hash(source, target, type);
    }

    @Override
    public String toString() {
        return "CFGEdge{" +
                "source='" + source + '\'' +
                ", target='" + target + '\'' +
                ", type=" + type +
                (label != null ? ", label='" + label + '\'' : "") +
                '}';
    }
}
