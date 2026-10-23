// Minimal reproducer for the shared-callee RETURN fan-out.
//
// Compile and extract with:
//   javac -d target/ target/SharedCallee.java
//   ./gradlew run --args="target/ output/ SharedCallee main inter"
//
// check() is called from two sites, but the inter-procedural extractor inlines each method body
// exactly once. The single copy of check() therefore has to return to both call sites, so its
// exit node ends up with two RETURN edges -- and SWAT's SAGraph allows a node only one
// fall-through successor (RETURN counts as one), so it refuses to load the graph:
//
//   File "data/StaticAnalysisGraph/SAGraph.py", line 43, in add_fallthrough_child
//     assert self.next_fallthrough is None
//
// Adding a third call to check() should add a third RETURN edge to the same exit node.
public class SharedCallee {

    static void check(int x) {
        assert x != 42;
    }

    public static void main(String[] args) {
        check(args.length);
        check(7);
    }
}
