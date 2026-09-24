package de.uzl.its.cfg.model;

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
     * down, or one cut off by the recursion depth limit. The statements and branches of whatever
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
