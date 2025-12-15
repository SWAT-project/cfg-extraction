package de.uzl.its.cfg.extractor;

import de.uzl.its.cfg.model.ControlFlowGraph;
import sootup.core.model.SootMethod;
import sootup.java.core.views.JavaView;

/**
 * Base interface for CFG extraction strategies.
 * Implement this interface to create different types of extractors:
 * - IntraProceduralCFGExtractor: Single method analysis
 * - InterProceduralCFGExtractor: Cross-method analysis with call graphs
 * - ContextSensitiveCFGExtractor: Context-sensitive analysis
 */
public interface CFGExtractor {

    /**
     * Extract CFG from a single method
     * @param method The method to analyze
     * @param view The JavaView for class loading
     * @return The extracted control flow graph
     */
    ControlFlowGraph extractCFG(SootMethod method, JavaView view);

    /**
     * Get the analysis type this extractor performs
     * @return The type of analysis
     */
    ControlFlowGraph.AnalysisType getAnalysisType();
}
