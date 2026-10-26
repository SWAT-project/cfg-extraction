package de.uzl.its.cfg.model;

import java.util.List;
import java.util.Objects;

/**
 * Represents a node (statement) in the Control Flow Graph.
 * Extensible for future analysis by adding more fields.
 */
public class CFGNode {
    private final String id;
    private final String statement;
    private final String stmtType;
    private final int lineNumber;

    /**
     * Whether the graph stops describing the program faithfully at this node.
     *
     * <p>Set where something could not be expanded -- a call whose target could not be pinned
     * down, or a {@code throw} whose destination could not be. The statements and branches of whatever
     * was left out are missing from the graph, so a consumer that follows this graph in step with
     * a real execution has to stop trusting it from here on: the execution will report branches
     * that the graph has no counterpart for, and every later pairing along the path would be off
     * by those.
     *
     * <p>Such a node is also reported as an assertion point, which is the other half of the same
     * story: that keeps the paths <em>leading</em> here from being pruned, while this flag keeps
     * the paths <em>beyond</em> here from being trusted.
     */
    private boolean isIncomplete;

    // Extension point: Add metadata for different analysis types
    private String metadata;

    /**
     * For an {@code uncaught[T]} node only: the exception leaving the method here, and its
     * superclass chain from {@code T} up to {@code java.lang.Throwable}.
     *
     * <p>The consumer has no type hierarchy, but has to decide at walk time which frame on its call
     * stack catches the exception. The chain lets it do that with the same most-specific-match
     * rule the extractor uses (see {@code ExceptionHandlers}): walk it upwards and take the first
     * type the call site has a handler for. Null for every other node, so Gson leaves it out.
     */
    private String exceptionType;
    private List<String> exceptionTypeChain;

    /** For a branch or a call site, its key as SWAT's executor reports it (see BranchKeys); else null. */
    private String key;

    /**
     * Whether the node cannot raise a RuntimeException, by the property pre-check's value analysis
     * (see RaiseSites): true or false for a statement that may raise one itself, true for a
     * phantom guard that cannot (unset if it may); null for every other node.
     */
    private Boolean cannotRaise;

    /**
     * Data-flow facts for SWAT's relevance analysis (see DataflowFacts), or null where there are
     * none. Locations are strings: {@code l:<local>}, {@code p:<n>} / {@code p:this} (a parameter),
     * {@code ret} (the method's return value) are local to the node's method; {@code f:<name>:<type>}
     * (a field, static or not), {@code a:<element kind>} (array elements), {@code g:...} (global
     * state: input, library heap, output, the exception in flight) and {@code *} (anything) are
     * global.
     *
     * <ul>
     *   <li>{@code dataflow}: pairs {@code [written, read]}: each written location may afterwards
     *       hold a value computed from the read ones;</li>
     *   <li>{@code control}: what the node's control decision reads (a branch condition, a switch
     *       key, the operands of a phantom guard, an assumption);</li>
     *   <li>{@code terminates}: the node may end the program normally ({@code System.exit},
     *       {@code Runtime.halt}, {@code Verifier.assume});</li>
     *   <li>{@code call}: for a call site expanded into application code, what each argument reads
     *       ({@code args}), what the receiver reads ({@code base}) and the local the result goes
     *       to ({@code result}); SWAT links them to the callee's parameters and return value.</li>
     * </ul>
     */
    private List<List<List<String>>> dataflow;
    private List<String> control;
    private Boolean terminates;
    private java.util.Map<String, Object> call;

    public List<List<List<String>>> getDataflow() {
        return dataflow;
    }

    public void setDataflow(List<List<List<String>>> dataflow) {
        this.dataflow = dataflow;
    }

    public List<String> getControl() {
        return control;
    }

    public void setControl(List<String> control) {
        this.control = control;
    }

    public Boolean getTerminates() {
        return terminates;
    }

    public void setTerminates(Boolean terminates) {
        this.terminates = terminates;
    }

    public java.util.Map<String, Object> getCall() {
        return call;
    }

    public void setCall(java.util.Map<String, Object> call) {
        this.call = call;
    }

    public Boolean getCannotRaise() {
        return cannotRaise;
    }

    public void setCannotRaise(Boolean cannotRaise) {
        this.cannotRaise = cannotRaise;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public CFGNode(String id, String statement, String stmtType, int lineNumber) {
        this.id = id;
        this.statement = statement;
        this.stmtType = stmtType;
        this.lineNumber = lineNumber;
    }

    public String getId() {
        return id;
    }

    public String getStatement() {
        return statement;
    }

    public String getStmtType() {
        return stmtType;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public boolean isIncomplete() {
        return isIncomplete;
    }

    public void setIncomplete(boolean isIncomplete) {
        this.isIncomplete = isIncomplete;
    }

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }

    public String getExceptionType() {
        return exceptionType;
    }

    public List<String> getExceptionTypeChain() {
        return exceptionTypeChain;
    }

    public void setException(String exceptionType, List<String> exceptionTypeChain) {
        this.exceptionType = exceptionType;
        this.exceptionTypeChain = exceptionTypeChain;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CFGNode cfgNode = (CFGNode) o;
        return Objects.equals(id, cfgNode.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "CFGNode{" +
                "id='" + id + '\'' +
                ", statement='" + statement + '\'' +
                ", type='" + stmtType + '\'' +
                ", line=" + lineNumber +
                '}';
    }
}
