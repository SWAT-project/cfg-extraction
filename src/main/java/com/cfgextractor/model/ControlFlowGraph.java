package com.cfgextractor.model;

import java.util.*;

/**
 * Represents a Control Flow Graph for a method (intra-procedural)
 * or a program (inter-procedural when extended).
 */
public class ControlFlowGraph {
    private final String methodSignature;
    private final String className;
    private final AnalysisType analysisType;
    private final List<CFGNode> nodes;
    private final List<CFGEdge> edges;
    private String entryNodeId;
    private final Set<String> exitNodeIds;

    // Extension point: Additional metadata for different analysis types
    private Map<String, Object> metadata;

    public enum AnalysisType {
        INTRA_PROCEDURAL,    // Single method CFG
        INTER_PROCEDURAL,    // Cross-method CFG with call edges
        CONTEXT_SENSITIVE    // Context-sensitive inter-procedural
    }

    public ControlFlowGraph(String methodSignature, String className, AnalysisType analysisType) {
        this.methodSignature = methodSignature;
        this.className = className;
        this.analysisType = analysisType;
        this.nodes = new ArrayList<>();
        this.edges = new ArrayList<>();
        this.exitNodeIds = new HashSet<>();
        this.metadata = new HashMap<>();
    }

    public void addNode(CFGNode node) {
        nodes.add(node);
    }

    public void addEdge(CFGEdge edge) {
        edges.add(edge);
    }

    public void setEntryNode(String nodeId) {
        this.entryNodeId = nodeId;
    }

    public void addExitNode(String nodeId) {
        this.exitNodeIds.add(nodeId);
    }

    public String getMethodSignature() {
        return methodSignature;
    }

    public String getClassName() {
        return className;
    }

    public AnalysisType getAnalysisType() {
        return analysisType;
    }

    public List<CFGNode> getNodes() {
        return Collections.unmodifiableList(nodes);
    }

    public List<CFGEdge> getEdges() {
        return Collections.unmodifiableList(edges);
    }

    public String getEntryNodeId() {
        return entryNodeId;
    }

    public Set<String> getExitNodeIds() {
        return Collections.unmodifiableSet(exitNodeIds);
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public void addMetadata(String key, Object value) {
        this.metadata.put(key, value);
    }

    @Override
    public String toString() {
        return "ControlFlowGraph{" +
                "method='" + methodSignature + '\'' +
                ", class='" + className + '\'' +
                ", type=" + analysisType +
                ", nodes=" + nodes.size() +
                ", edges=" + edges.size() +
                '}';
    }
}
