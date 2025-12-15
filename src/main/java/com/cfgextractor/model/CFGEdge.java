package com.cfgextractor.model;

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

    public enum EdgeType {
        NORMAL,           // Regular control flow
        TRUE_BRANCH,      // True branch of conditional
        FALSE_BRANCH,     // False branch of conditional
        EXCEPTION,        // Exception handling flow
        // Extension points for inter-procedural analysis:
        CALL,            // Method call edge
        RETURN,          // Method return edge
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
