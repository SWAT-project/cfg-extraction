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
